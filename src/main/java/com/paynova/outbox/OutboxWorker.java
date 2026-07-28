package com.paynova.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Outbox delivery worker + lease reaper (§10 claim protocol; design decision: two
 * independent @Scheduled methods).
 *
 * Three-phase flow (core principle: HTTP never happens inside a database transaction):
 *   short transaction 1 — claim (per-event claim_token + 60s lease, FOR UPDATE SKIP LOCKED)
 *   -> HTTP send outside any transaction
 *   -> short transaction 2 — conditional write-back (fencing: WHERE claim_token = this
 *      run's token; affected=0 means abandon)
 *
 * Transactions are managed explicitly via TransactionTemplate instead of @Transactional
 * self-calls — self-invocation silently bypasses the proxy; the explicit template rules
 * that pitfall out structurally.
 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    /** Backoff ladder (minutes): wait time after the Nth failure; the 7th failure -> FAILED (§10). */
    private static final int[] BACKOFF_MINUTES = {1, 5, 10, 30, 60, 360};

    record ClaimedEvent(UUID id, UUID claimToken, int attemptCount, String eventType, String payload) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebhookProperties props;
    private final RestClient restClient;
    private final boolean schedulingEnabled;

    public OutboxWorker(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        WebhookProperties props,
                        @Value("${paynova.outbox.scheduling-enabled:true}") boolean schedulingEnabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.props = props;
        this.schedulingEnabled = schedulingEnabled;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // ---------- Scheduling entry points (tests set scheduling-enabled=false and invoke manually for determinism) ----------

    @Scheduled(fixedDelay = 5_000)
    public void deliverPendingScheduled() {
        if (schedulingEnabled) {
            deliverPending();
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void reapExpiredLeasesScheduled() {
        if (schedulingEnabled) {
            reapExpiredLeases();
        }
    }

    // ---------- Worker ----------

    public void deliverPending() {
        List<ClaimedEvent> batch = tx.execute(status -> claimBatch());
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (ClaimedEvent event : batch) {
            boolean delivered = send(event);            // <- outside any transaction
            tx.executeWithoutResult(status -> writeBack(event, delivered));
        }
    }

    /** Short transaction 1: claim. Each row gets its own claim_token; locks are released immediately at COMMIT. */
    private List<ClaimedEvent> claimBatch() {
        return jdbc.query("""
                UPDATE outbox_events
                SET status = 'PROCESSING', claim_token = gen_random_uuid(),
                    claimed_at = now(), locked_until = now() + interval '60 seconds'
                WHERE id IN (SELECT id FROM outbox_events
                             WHERE status = 'PENDING' AND next_attempt_at <= now()
                             ORDER BY next_attempt_at
                             LIMIT 10
                             FOR UPDATE SKIP LOCKED)
                RETURNING id, claim_token, attempt_count, event_type, payload::text AS payload
                """, (rs, i) -> new ClaimedEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("claim_token", UUID.class),
                rs.getInt("attempt_count"),
                rs.getString("event_type"),
                rs.getString("payload")));
    }

    /** Send outside any transaction. Signs the stored raw payload string; the consumer verifies it byte-for-byte. */
    private boolean send(ClaimedEvent event) {
        try {
            restClient.post()
                    .uri(props.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-PayNova-Signature", HmacSigner.sign(props.getSecret(), event.payload()))
                    .header("X-PayNova-Event-Id", event.id().toString())
                    .header("X-PayNova-Event-Type", event.eventType())
                    .body(event.payload())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            // Hard rule: never log the payload (may contain business data) — only the event id and the reason
            log.warn("webhook delivery failed, event={} attempt={} reason={}",
                    event.id(), event.attemptCount() + 1, e.getMessage());
            return false;
        }
    }

    /**
     * Short transaction 2: conditional write-back (fencing). The WHERE clause includes
     * claim_token — once the reaper has taken over the lease, a slow worker returning
     * late sees affected=0 and must abandon the write. Fencing protects the status
     * write-back, not delivery itself: delivery remains at-least-once, and exactly-once
     * effect relies on consumer-side webhook_receipts deduplication.
     */
    private void writeBack(ClaimedEvent event, boolean delivered) {
        int updated;
        if (delivered) {
            updated = jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'DELIVERED', delivered_at = now(),
                        claim_token = NULL, claimed_at = NULL, locked_until = NULL
                    WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                    """, event.id(), event.claimToken());
        } else {
            int backoffMinutes = BACKOFF_MINUTES[Math.min(event.attemptCount(), BACKOFF_MINUTES.length - 1)];
            updated = jdbc.update("""
                    UPDATE outbox_events
                    SET attempt_count = attempt_count + 1,
                        status = CASE WHEN attempt_count >= 6 THEN 'FAILED' ELSE 'PENDING' END,
                        next_attempt_at = now() + make_interval(mins => ?),
                        claim_token = NULL, claimed_at = NULL, locked_until = NULL
                    WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                    """, backoffMinutes, event.id(), event.claimToken());
        }
        if (updated == 0) {
            log.warn("lease for event {} was taken over; abandoning write-back (fencing)", event.id());
        }
    }

    // ---------- Reaper ----------

    /** Reclaim orphaned leases from workers that crashed or stalled between claim and write-back: reset to PENDING and clear the three lease fields. */
    public void reapExpiredLeases() {
        Integer reclaimed = tx.execute(status -> jdbc.update("""
                UPDATE outbox_events
                SET status = 'PENDING', claim_token = NULL, claimed_at = NULL, locked_until = NULL
                WHERE status = 'PROCESSING' AND locked_until < now()
                """));
        if (reclaimed != null && reclaimed > 0) {
            log.warn("reaper reclaimed {} orphaned outbox events", reclaimed);
        }
    }
}
