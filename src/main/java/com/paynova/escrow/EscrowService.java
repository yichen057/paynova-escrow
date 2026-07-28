package com.paynova.escrow;

import com.paynova.account.Account;
import com.paynova.account.AccountLockService;
import com.paynova.account.AccountService;
import com.paynova.auth.UserRepository;
import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;
import com.paynova.common.CorrelationIdFilter;
import com.paynova.ledger.LedgerService;
import com.paynova.ledger.LedgerTransaction;
import com.paynova.ledger.LedgerTransactionRepository;
import com.paynova.ledger.LedgerTransactionType;
import com.paynova.ledger.Posting;
import com.paynova.outbox.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EscrowService {

    private static final String REFERENCE_TYPE = "ESCROW_ORDER";

    private final EscrowRepository escrowRepository;
    private final UserRepository userRepository;
    private final AccountService accountService;
    private final AccountLockService lockService;
    private final LedgerService ledgerService;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final OutboxService outboxService;

    public EscrowService(EscrowRepository escrowRepository, UserRepository userRepository,
                         AccountService accountService, AccountLockService lockService,
                         LedgerService ledgerService,
                         LedgerTransactionRepository ledgerTransactionRepository,
                         OutboxService outboxService) {
        this.escrowRepository = escrowRepository;
        this.userRepository = userRepository;
        this.accountService = accountService;
        this.lockService = lockService;
        this.ledgerService = ledgerService;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.outboxService = outboxService;
    }

    /**
     * Create an escrow order (§9 API #6).
     * Amount validation is done in the business layer → 422 INVALID_AMOUNT (not Bean Validation's 400:
     * the request is well-formed; it is the business semantics that cannot be processed).
     * TODO(Step 3): enforce Idempotency-Key on this endpoint (§8/§9).
     */
    @Transactional
    public EscrowOrder create(Long buyerId, Long sellerId, Long amountCents, String description) {
        if (amountCents == null || amountCents <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "amount_cents must be positive");
        }
        if (sellerId == null || !userRepository.existsById(sellerId)) {
            throw new ApiException(ErrorCode.SELLER_NOT_FOUND, "seller does not exist");
        }
        if (sellerId.equals(buyerId)) {
            // The buyer_not_seller CHECK constraint at the DB layer provides a backstop
            throw new ApiException(ErrorCode.SELLER_IS_BUYER, "buyer and seller must differ");
        }
        return escrowRepository.save(new EscrowOrder(buyerId, sellerId, amountCents, description));
    }

    /** Visible to participants or ADMIN (§9 API #10). */
    @Transactional(readOnly = true)
    public EscrowOrder getAuthorized(UUID id, Long requesterId, boolean isAdmin) {
        EscrowOrder order = escrowRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "escrow order not found"));
        if (!isAdmin && !order.isParticipant(requesterId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "not a participant of this order");
        }
        return order;
    }

    /**
     * State transition (Step 1 legacy: pure state semantics, for tests and internal use).
     *
     * Two lines of defense (order matters):
     *  1) nextFor(): illegal state x action combination → 409, no SQL issued, zero side effects
     *  2) CAS: legal combination but lost a concurrent race → affected_rows == 0 → 409, zero side effects
     */
    @Transactional
    public EscrowStatus transition(UUID orderId, EscrowAction action) {
        EscrowOrder order = escrowRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "escrow order not found"));

        EscrowStatus from = order.getStatus();
        EscrowStatus to = from.nextFor(action)
                .orElseThrow(() -> new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                        action + " is not allowed in state " + from));

        int updated = escrowRepository.transition(orderId, from, to);
        if (updated != 1) {
            // Status was still `from` at read time but a concurrent request changed it before the UPDATE → this request lost the CAS
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "order state changed concurrently, expected " + from);
        }
        return to;
    }

    // ==================== Step 4: money-endpoint orchestration (§7 transaction boundary) ====================
    // Uniform recipe: load order → authorize → nextFor pre-check → lockAll (ascending-id lock order)
    //          → balance check → CAS → LedgerService.post — one transaction; all take effect or none do.
    // These methods join the transaction opened by IdempotencyService.execute (REQUIRED propagation),
    // so the idempotency record is written within the same transaction (§8 option A).

    /** Buyer funds the order: wallet → system:escrow (§9 API #7). */
    @Transactional
    public EscrowOrder fund(UUID orderId, Long actorId) {
        EscrowOrder order = loadOrder(orderId);
        if (!order.getBuyerId().equals(actorId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the buyer can fund this order");
        }
        EscrowStatus from = order.getStatus();
        EscrowStatus to = requireLegal(from, EscrowAction.FUND);

        Account wallet = accountService.walletOf(order.getBuyerId());
        Account escrow = accountService.systemAccount(Account.SYSTEM_ESCROW);
        Map<Long, Account> locked = lockService.lockAll(List.of(wallet.getId(), escrow.getId()));

        if (locked.get(wallet.getId()).getBalance() < order.getAmountCents()) {
        //if (wallet.getBalance() < order.getAmountCents()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "wallet balance is insufficient to fund this order");
        }
        casOrConflict(orderId, from, to);
        ledgerService.post(LedgerTransactionType.ESCROW_FUND, REFERENCE_TYPE, orderId.toString(),
                null, List.of(
                        Posting.debit(wallet.getId(), order.getAmountCents()),
                        Posting.credit(escrow.getId(), order.getAmountCents())));
        appendEvent(order, "escrow.funded", to);
        return loadOrder(orderId);
    }

    /** Buyer confirms delivery and releases funds: system:escrow → seller wallet (§9 API #8). */
    @Transactional
    public EscrowOrder release(UUID orderId, Long actorId) {
        EscrowOrder order = loadOrder(orderId);
        if (!order.getBuyerId().equals(actorId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the buyer can release this order");
        }
        EscrowStatus from = order.getStatus();
        EscrowStatus to = requireLegal(from, EscrowAction.RELEASE);

        Account sellerWallet = accountService.walletOf(order.getSellerId());
        Account escrow = accountService.systemAccount(Account.SYSTEM_ESCROW);
        Map<Long, Account> locked = lockService.lockAll(List.of(sellerWallet.getId(), escrow.getId()));

        requireEscrowSolvency(locked.get(escrow.getId()), order.getAmountCents());
        casOrConflict(orderId, from, to);
        ledgerService.post(LedgerTransactionType.ESCROW_RELEASE, REFERENCE_TYPE, orderId.toString(),
                null, List.of(
                        Posting.debit(escrow.getId(), order.getAmountCents()),
                        Posting.credit(sellerWallet.getId(), order.getAmountCents())));
        appendEvent(order, "escrow.released", to);
        return loadOrder(orderId);
    }

    /** Refund: system:escrow → buyer wallet; the reversal references the original FUND transaction (§9 API #9). */
    @Transactional
    public EscrowOrder refund(UUID orderId, Long actorId, boolean isAdmin) {
        EscrowOrder order = loadOrder(orderId);
        if (!isAdmin && !order.getSellerId().equals(actorId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the seller or an admin can refund");
        }
        EscrowStatus from = order.getStatus();
        EscrowStatus to = requireLegal(from, EscrowAction.REFUND);

        Account buyerWallet = accountService.walletOf(order.getBuyerId());
        Account escrow = accountService.systemAccount(Account.SYSTEM_ESCROW);
        Map<Long, Account> locked = lockService.lockAll(List.of(buyerWallet.getId(), escrow.getId()));

        requireEscrowSolvency(locked.get(escrow.getId()), order.getAmountCents());
        casOrConflict(orderId, from, to);

        // Reversal pair (borrowing Fineract's reversed/reversal_id idea): the refund entries reference
        // the original FUND transaction, so the ledger can always trace which payment a refund reverses
        LedgerTransaction fundTxn = ledgerTransactionRepository
                .findByTypeAndReferenceTypeAndReferenceId(
                        LedgerTransactionType.ESCROW_FUND, REFERENCE_TYPE, orderId.toString())
                .orElseThrow(() -> new IllegalStateException(
                        "invariant violated: FUNDED order has no ESCROW_FUND ledger transaction"));
        ledgerService.post(LedgerTransactionType.ESCROW_REFUND, REFERENCE_TYPE, orderId.toString(),
                fundTxn.getId(), List.of(
                        Posting.debit(escrow.getId(), order.getAmountCents()),
                        Posting.credit(buyerWallet.getId(), order.getAmountCents())));
        appendEvent(order, "escrow.refunded", to);
        return loadOrder(orderId);
    }

    /** The outbox event is written in the same transaction as the state change and ledger entries (§7 step 7, §10). */
    private void appendEvent(EscrowOrder order, String eventType, EscrowStatus newStatus) {
        outboxService.append(REFERENCE_TYPE, order.getId().toString(), eventType, Map.of(
                "order_id", order.getId().toString(),
                "status", newStatus.name(),
                "amount_cents", order.getAmountCents(),
                "currency", order.getCurrency(),
                "buyer_id", order.getBuyerId(),
                "seller_id", order.getSellerId(),
                "correlation_id", CorrelationIdFilter.current().toString()));
    }

    // ---------- private helpers ----------

    private EscrowOrder loadOrder(UUID orderId) {
        return escrowRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "escrow order not found"));
    }

    private EscrowStatus requireLegal(EscrowStatus from, EscrowAction action) {
        return from.nextFor(action)
                .orElseThrow(() -> new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                        action + " is not allowed in state " + from));
    }

    private void casOrConflict(UUID orderId, EscrowStatus from, EscrowStatus to) {
        if (escrowRepository.transition(orderId, from, to) != 1) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "order state changed concurrently, expected " + from);
        }
    }

    /**
     * Escrow account solvency check: an escrow balance lower than the amount to pay out would mean
     * releasing money that was never collected — an invariant violation (a bug), so throw
     * IllegalStateException and surface a 500; it must never be swallowed.
     */
    private void requireEscrowSolvency(Account escrow, long amountCents) {
        if (escrow.getBalance() < amountCents) {
            throw new IllegalStateException(
                    "invariant violated: escrow account balance " + escrow.getBalance()
                            + " < required " + amountCents);
        }
    }
}
