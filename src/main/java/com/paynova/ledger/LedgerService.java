package com.paynova.ledger;

import com.paynova.account.Account;
import com.paynova.account.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single write entry point for the ledger (design doc §5).
 *
 * Design decision (Step 2):
 *  - API shape = a list of postings (Formance-style): one business operation applies
 *    N entries atomically, and adding fees/splits later requires no interface change;
 *    balance validation is enforced once at this entry point.
 *  - The balance snapshot update happens inside post(): entries and snapshot are never
 *    separated, so no call path can "post the entries but forget the balance".
 *
 * MANDATORY propagation: post() must run inside a transaction opened by the caller —
 * the ledger always lives and dies with the business state change that triggered it;
 * independent commits are forbidden (§7 transaction boundaries).
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AccountRepository accountRepository;

    public LedgerService(LedgerTransactionRepository transactionRepository,
                         LedgerEntryRepository entryRepository,
                         AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(LedgerTransactionType type, String referenceType,
                                  String referenceId, UUID reversalOf, List<Posting> postings) {
        validate(postings);

        // Load and validate accounts: all must exist and share one currency
        // (V1 is single-currency, but the check is done right from day one)
        Map<Long, Account> accounts = accountRepository
                .findAllById(postings.stream().map(Posting::accountId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Account::getId, Function.identity()));
        for (Posting p : postings) {
            if (!accounts.containsKey(p.accountId())) {
                throw new IllegalArgumentException("unknown account: " + p.accountId());
            }
        }
        List<String> currencies = accounts.values().stream()
                .map(Account::getCurrency).distinct().toList();
        if (currencies.size() != 1) {
            throw new IllegalArgumentException("cross-currency posting is not supported: " + currencies);
        }
        String currency = currencies.get(0);

        // 1) Parent record (uq_ledger_business unique constraint = last line of defense against double posting, §4)
        LedgerTransaction txn = transactionRepository.save(
                new LedgerTransaction(type, referenceType, referenceId, reversalOf));

        // 2) Immutable entries
        for (Posting p : postings) {
            entryRepository.save(new LedgerEntry(
                    txn.getId(), p.accountId(), p.direction(), p.amountCents(), currency));
        }

        // 3) Balance snapshots: aggregate the net delta per account, apply in ascending
        //    accountId order (lock-ordering discipline, §7)
        Map<Long, Long> deltas = new HashMap<>();
        for (Posting p : postings) {
            long signed = p.direction() == Direction.CREDIT ? p.amountCents() : -p.amountCents();
            deltas.merge(p.accountId(), signed, Long::sum);
        }
        deltas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    int updated = accountRepository.applyDelta(e.getKey(), e.getValue());
                    if (updated != 1) {
                        throw new IllegalStateException("balance update failed for account " + e.getKey());
                    }
                });

        return txn;
    }

    /**
     * Entry-point validation of invariant 1 (§5): at least 2 entries, strictly positive
     * amounts, debits and credits balanced within the group.
     * static + package-private: enables pure unit tests without a database.
     */
    static void validate(List<Posting> postings) {
        Objects.requireNonNull(postings, "postings");
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a ledger transaction needs at least 2 entries");
        }
        long debits = 0;
        long credits = 0;
        for (Posting p : postings) {
            if (p.amountCents() <= 0) {
                throw new IllegalArgumentException("entry amount must be positive");
            }
            if (p.direction() == Direction.DEBIT) {
                debits += p.amountCents();
            } else {
                credits += p.amountCents();
            }
        }
        if (debits != credits) {
            throw new IllegalArgumentException(
                    "unbalanced transaction: debits=" + debits + " credits=" + credits);
        }
    }
}
