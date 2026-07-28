package com.paynova.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByAccountIdOrderByIdDesc(Long accountId, Pageable pageable);

    /** Reconciliation query 1: global per-currency Σ(CREDIT) − Σ(DEBIT), must be 0 at all times (invariant 2, §5). */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries WHERE currency = :currency
            """, nativeQuery = true)
    long globalSum(@Param("currency") String currency);

    /** Reconciliation query 2: debit/credit difference within a single transaction, must be 0 (invariant 1, §5). */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries WHERE transaction_id = :txnId
            """, nativeQuery = true)
    long transactionSum(@Param("txnId") UUID txnId);

    /** Reconciliation query 3: ledger-derived balance (source of truth), cross-checkable against the accounts.balance snapshot. */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries WHERE account_id = :accountId
            """, nativeQuery = true)
    long derivedBalance(@Param("accountId") Long accountId);
}
