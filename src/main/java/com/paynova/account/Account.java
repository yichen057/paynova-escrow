package com.paynova.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "accounts")
public class Account {

    public static final String SYSTEM_CASH_IN = "system:cash_in";
    public static final String SYSTEM_ESCROW = "system:escrow";
    public static final String SYSTEM_CASH_OUT = "system:cash_out";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", updatable = false)
    private Long ownerUserId;

    @Column(nullable = false, updatable = false)
    private String type;   // USER / SYSTEM

    @Column(nullable = false, unique = true, updatable = false)
    private String name;

    // VARCHAR(3) rather than CHAR(3): Hibernate 6's schema validator maps String to VARCHAR,
    // which mismatches PostgreSQL's bpchar and fails ddl-auto:validate at startup
    // (note: discovered during integration testing)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency = "USD";

    /**
     * Balance snapshot (the ledger is the source of truth, §5). updatable=false:
     * the only mutation path for the balance is the atomic UPDATE in
     * AccountRepository.applyDelta(); there is no read-modify-save path at the entity level.
     */
    @Column(nullable = false, updatable = false)
    private Long balance = 0L;

    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Account() {
        // for JPA
    }

    /** User wallet. System accounts are created by the Flyway seed, not through this factory. */
    public static Account walletFor(Long userId) {
        Account account = new Account();
        account.ownerUserId = userId;
        account.type = "USER";
        account.name = "user:" + userId + ":wallet";
        return account;
    }

    public Long getId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getCurrency() { return currency == null ? null : currency.trim(); }
    public Long getBalance() { return balance; }
    public boolean isAllowNegative() { return allowNegative; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
