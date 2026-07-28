package com.paynova.account;

import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sole entry point for locking multiple accounts (hard rule, design doc §7): business code
 * must never issue its own FOR UPDATE.
 *
 * Design decision (Step 4): a direct-style API returning a Map — callers receive the
 * freshly locked account objects and proceed with their writes; the "must be called inside
 * a transaction" contract is enforced by Propagation.MANDATORY.
 *
 * Deadlock prevention: regardless of the order callers pass in, locks are always acquired
 * one by one in ascending account id order. Two opposite-direction fund operations
 * (A→B and B→A) therefore always take locks in the same order and never wait on each other.
 */
@Service
public class AccountLockService {

    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public AccountLockService(EntityManager entityManager, JdbcTemplate jdbc) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Account> lockAll(Collection<Long> accountIds) {
        // Transaction-scoped lock wait limit (§7): SET LOCAL does not leak into the
        // connection pool; timeouts are mapped to 503 by the global exception handler
        jdbc.execute("SET LOCAL lock_timeout = '5s'");

        List<Long> ordered = accountIds.stream().distinct().sorted().toList();
        Map<Long, Account> locked = new LinkedHashMap<>();
        for (Long id : ordered) {
            Account account = entityManager.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (account == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "account not found: " + id);
            }
            // Stale persistence-context snapshot pitfall: if this entity was loaded earlier
            // in the transaction without a lock, find() returns the cached instance — refresh
            // forces a FOR UPDATE re-read from the database, guaranteeing the post-lock
            // balance reflects values committed by other transactions
            entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
            locked.put(id, account);
        }
        return locked;
    }
}
