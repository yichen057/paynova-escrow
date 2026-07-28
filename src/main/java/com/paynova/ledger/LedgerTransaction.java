package com.paynova.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Parent record for a group of entries. Immutable: every column updatable=false, no setters. */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @UuidGenerator
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerTransactionType type;

    @Column(name = "reference_type", nullable = false, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private String referenceId;

    @Column(name = "reversal_of", updatable = false)
    private UUID reversalOf;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LedgerTransaction() {
        // for JPA
    }

    public LedgerTransaction(LedgerTransactionType type, String referenceType,
                             String referenceId, UUID reversalOf) {
        this.type = type;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reversalOf = reversalOf;
    }

    public UUID getId() { return id; }
    public LedgerTransactionType getType() { return type; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public UUID getReversalOf() { return reversalOf; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
