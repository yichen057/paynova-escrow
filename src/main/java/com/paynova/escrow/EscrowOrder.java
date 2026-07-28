package com.paynova.escrow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "escrow_orders")
public class EscrowOrder {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private Long sellerId;

    /** Amounts are always BIGINT cents (hard rule); the field name carries the unit to prevent misreading. */
    @Column(name = "amount", nullable = false, updatable = false)
    private Long amountCents;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency = "USD";

    @Column(length = 500, updatable = false)
    private String description;

    /**
     * Read-only status mapping: the entity deliberately provides no setStatus — the only path
     * for state transitions is the conditional UPDATE (CAS) in EscrowRepository.transition(),
     * ruling out at the code level any "read, mutate, save" pattern that bypasses the state machine.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EscrowStatus status = EscrowStatus.CREATED;

    // Set on the Java side (DB DEFAULT remains as a fallback) so the creation response
    // carries a real timestamp instead of null — a freshly saved entity never re-reads
    // DB-generated column defaults. (Known issue found during acceptance.)
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected EscrowOrder() {
        // for JPA
    }

    public EscrowOrder(Long buyerId, Long sellerId, Long amountCents, String description) {
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.amountCents = amountCents;
        this.description = description;
    }

    public UUID getId() { return id; }
    public Long getBuyerId() { return buyerId; }
    public Long getSellerId() { return sellerId; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public EscrowStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public boolean isParticipant(Long userId) {
        return buyerId.equals(userId) || sellerId.equals(userId);
    }
}
