package com.paynova.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 24h TTL cleanup for idempotency records (§8 API contract: after 24h the same key
 * is treated as a new request).
 * Note: replaying a money-write key after the TTL hits the ledger-level
 * uq_ledger_business constraint -> 409. Failing loudly is preferred over double
 * posting (division of labor between the two defense lines; see README).
 */
@Component
public class IdempotencyCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupTask.class);

    private final JdbcTemplate jdbc;

    public IdempotencyCleanupTask(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void deleteExpired() {
        int deleted = jdbc.update(
                "DELETE FROM idempotency_records WHERE created_at < now() - interval '24 hours'");
        if (deleted > 0) {
            log.info("idempotency cleanup: {} expired records removed", deleted);
        }
    }
}
