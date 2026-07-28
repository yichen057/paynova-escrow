package com.paynova.it;

import com.paynova.outbox.OutboxWorker;
import com.paynova.outbox.WebhookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 5 integration tests (real PostgreSQL; background scheduling disabled, the
 * Worker/Reaper are driven manually to keep the tests deterministic):
 *  A. fund emits an outbox event (same transaction as the business write) -> Worker
 *     delivers it -> DELIVERED + merchant receipt
 *  B. at-least-once redelivery -> the consumer dedupes via webhook_receipts and
 *     processes it only once
 *  C. Delivery failure -> back off to PENDING (attempt+1); retry succeeds after the fix
 *  D. 7th failure -> terminal FAILED (manual-intervention slot)
 *  E. Reaper reclaims expired leases + fencing: a write-back with a stale claim_token
 *     affects 0 rows
 *  F. Wrong signature -> 401 INVALID_SIGNATURE
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxFlowIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxWorker worker;
    @Autowired WebhookProperties webhookProperties;
    @LocalServerPort int port;

    private String goodUrl;

    @BeforeEach
    void pointWebhookAtThisInstance() {
        goodUrl = "http://localhost:" + port + "/api/webhooks/mock-merchant";
        webhookProperties.setUrl(goodUrl);
    }

    // ---------- A + B: delivery and deduplication ----------

    @Test
    void fundEmitsEventWorkerDeliversAndConsumerDedupes() {
        String orderId = fundedOrder();

        UUID eventId = jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type='escrow.funded'",
                UUID.class, orderId);
        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");

        worker.deliverPending();

        assertThat(outboxStatus(eventId)).isEqualTo("DELIVERED");
        assertThat(receiptCount(eventId)).isEqualTo(1);

        // Simulate an at-least-once redelivery: reset the event to PENDING and deliver again
        jdbc.update("UPDATE outbox_events SET status='PENDING', next_attempt_at=now(), "
                + "delivered_at=NULL WHERE id = ?", eventId);
        worker.deliverPending();

        assertThat(outboxStatus(eventId)).isEqualTo("DELIVERED");
        // The consumer processed it only once -- exactly-once effect is guaranteed by webhook_receipts
        assertThat(receiptCount(eventId)).isEqualTo(1);
    }

    // ---------- C: failure, backoff, then recovery ----------

    @Test
    void failedDeliveryBacksOffThenSucceedsAfterFix() {
        webhookProperties.setUrl("http://localhost:1/unreachable");
        String orderId = fundedOrder();
        UUID eventId = eventOf(orderId);

        worker.deliverPending();

        Map<String, Object> after = jdbc.queryForMap(
                "SELECT status, attempt_count, next_attempt_at > now() AS backed_off "
                        + "FROM outbox_events WHERE id = ?", eventId);
        assertThat(after.get("status")).isEqualTo("PENDING");
        assertThat(((Number) after.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat((Boolean) after.get("backed_off")).isTrue();   // 1-minute backoff

        // Fix the target URL and move the retry time to now -> retry succeeds
        webhookProperties.setUrl(goodUrl);
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE id = ?", eventId);
        worker.deliverPending();

        assertThat(outboxStatus(eventId)).isEqualTo("DELIVERED");
        assertThat(receiptCount(eventId)).isEqualTo(1);
    }

    // ---------- D: 7th failure -> FAILED ----------

    @Test
    void seventhFailureIsTerminalFailed() {
        webhookProperties.setUrl("http://localhost:1/unreachable");
        String orderId = fundedOrder();
        UUID eventId = eventOf(orderId);

        // The first 6 attempts already failed (attempt_count=6); this run is the 7th
        jdbc.update("UPDATE outbox_events SET attempt_count = 6, next_attempt_at = now() "
                + "WHERE id = ?", eventId);
        worker.deliverPending();

        assertThat(outboxStatus(eventId)).isEqualTo("FAILED");
    }

    // ---------- E: Reaper + fencing ----------

    @Test
    void reaperReclaimsExpiredLeaseAndFencingBlocksStaleWriteBack() {
        String orderId = fundedOrder();
        UUID eventId = eventOf(orderId);
        UUID staleToken = UUID.randomUUID();

        // Fabricate an orphan from a crash between claim and write-back: PROCESSING + expired lease
        jdbc.update("""
                UPDATE outbox_events
                SET status='PROCESSING', claim_token=?, claimed_at=now() - interval '5 minutes',
                    locked_until=now() - interval '4 minutes'
                WHERE id = ?
                """, staleToken, eventId);

        worker.reapExpiredLeases();

        Map<String, Object> reclaimed = jdbc.queryForMap(
                "SELECT status, claim_token FROM outbox_events WHERE id = ?", eventId);
        assertThat(reclaimed.get("status")).isEqualTo("PENDING");
        assertThat(reclaimed.get("claim_token")).isNull();

        // A slow Worker returns with the stale token -> fencing rejects the write-back
        int staleWriteBack = jdbc.update("""
                UPDATE outbox_events SET status='DELIVERED', delivered_at=now()
                WHERE id = ? AND status='PROCESSING' AND claim_token = ?
                """, eventId, staleToken);
        assertThat(staleWriteBack).isZero();
    }

    // ---------- F: signature verification ----------

    @Test
    void wrongSignatureIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-PayNova-Signature", "deadbeef");
        headers.set("X-PayNova-Event-Id", UUID.randomUUID().toString());

        ResponseEntity<Map> response = rest.postForEntity("/api/webhooks/mock-merchant",
                new HttpEntity<>("{\"x\":1}", headers), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().get("error_code")).isEqualTo("INVALID_SIGNATURE");
    }

    // ---------- helpers ----------

    private String outboxStatus(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private int receiptCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM webhook_receipts WHERE event_id = ?", Integer.class, eventId);
    }

    private UUID eventOf(String orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type='escrow.funded'",
                UUID.class, orderId);
    }

    /** Register buyer and seller -> top up -> create order -> fund; returns the order id (its outbox event is emitted as a side effect). */
    private String fundedOrder() {
        String buyerEmail = "ob+" + System.nanoTime() + "@example.com";
        String sellerEmail = "os+" + System.nanoTime() + "@example.com";
        postJson("/api/auth/register", Map.of("email", buyerEmail, "password", "abcd1234"), null, null);
        ResponseEntity<Map> sellerReg = postJson("/api/auth/register",
                Map.of("email", sellerEmail, "password", "abcd1234"), null, null);
        Long sellerId = ((Number) sellerReg.getBody().get("user_id")).longValue();
        String buyerToken = (String) postJson("/api/auth/login",
                Map.of("email", buyerEmail, "password", "abcd1234"), null, null).getBody().get("token");

        postJson("/api/wallets/top-ups", Map.of("amount_cents", 9_000L), buyerToken, UUID.randomUUID());
        ResponseEntity<Map> order = postJson("/api/escrows",
                Map.of("seller_id", sellerId, "amount_cents", 9_000L), buyerToken, UUID.randomUUID());
        String orderId = (String) order.getBody().get("id");
        ResponseEntity<Map> funded = postJson("/api/escrows/" + orderId + "/fund",
                Map.of(), buyerToken, UUID.randomUUID());
        assertThat(funded.getStatusCode().value()).isEqualTo(200);
        return orderId;
    }

    private ResponseEntity<Map> postJson(String path, Map<String, ?> body, String token, UUID key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (key != null) {
            headers.set("Idempotency-Key", key.toString());
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class);
    }
}
