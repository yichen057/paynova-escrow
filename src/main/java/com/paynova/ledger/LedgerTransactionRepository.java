package com.paynova.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    /** For refunds: locate the original FUND transaction to reference as reversal_of (§5). */
    Optional<LedgerTransaction> findByTypeAndReferenceTypeAndReferenceId(
            LedgerTransactionType type, String referenceType, String referenceId);
}
