package com.paynova.account;

import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;
import com.paynova.ledger.LedgerService;
import com.paynova.ledger.LedgerTransactionType;
import com.paynova.ledger.Posting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;

    public AccountService(AccountRepository accountRepository, LedgerService ledgerService) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
    }

    /** Creates the wallet within the registration transaction (§4) — called by AuthService.register. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Account createWallet(Long userId) {
        return accountRepository.save(Account.walletFor(userId));
    }

    @Transactional(readOnly = true)
    public Account walletOf(Long userId) {
        return accountRepository.findByName("user:" + userId + ":wallet")
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "wallet not found"));
    }

    @Transactional(readOnly = true)
    public Account systemAccount(String name) {
        return accountRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("system account missing: " + name));
    }

    /**
     * Simulated top-up (§5, fund flow ①): system:cash_in → user wallet.
     * cash_in is the only allow_negative account, a placeholder for external funds —
     * top-ups also go through double-entry posting, so the global SUM stays 0 and the
     * platform never creates money out of thin air.
     *
     * reference = the caller's Idempotency-Key (since Step 3): if the request-level
     * defense fails or expires, the ledger-level uq_ledger_business constraint still
     * rejects a duplicate posting for the same key — better a 409 than a double entry.
     */
    @Transactional
    public Account topUp(Long userId, Long amountCents, String reference) {
        if (amountCents == null || amountCents <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "amount_cents must be positive");
        }
        Account wallet = walletOf(userId);
        Account cashIn = systemAccount(Account.SYSTEM_CASH_IN);

        ledgerService.post(LedgerTransactionType.TOP_UP, "TOP_UP", reference,
                null, List.of(
                        Posting.debit(cashIn.getId(), amountCents),
                        Posting.credit(wallet.getId(), amountCents)));

        // The snapshot was already updated by applyDelta; re-read to return the latest balance
        return accountRepository.findById(wallet.getId()).orElseThrow();
    }
}
