package com.paynova.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable ledger entry: no setters, every column updatable=false;
 * an append-only trigger at the database layer (V1__init.sql) provides a second
 * line of defense.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amountCents;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    public LedgerEntry(UUID transactionId, Long accountId, Direction direction,
                       Long amountCents, String currency) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountCents = amountCents;
        this.currency = currency;
    }

    public Long getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public Long getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
