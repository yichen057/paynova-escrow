package com.paynova.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * Audit trail integration tests (design doc §11) on real PostgreSQL:
 *  A. Success audits commit WITH the business transaction (escrow.funded row present,
 *     carrying actor / correlation_id / source_ip).
 *  B. THE STAR: a rejected operation (insufficient funds) leaves ZERO business side
 *     effects (order still CREATED, no ledger rows, balance untouched) — yet the
 *     failure audit row SURVIVES, proving the REQUIRES_NEW separate-transaction design.
 *  C. Failed logins are audited.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class AuditFlowIT {

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

    // ---------- A. success audit joins the business transaction ----------

    @Test
    void successfulFundWritesAuditRowWithContext() {
        String buyerEmail = newEmail("buyer");
        String sellerEmail = newEmail("seller");
        Long sellerId = register(sellerEmail);
        register(buyerEmail);
        String buyerToken = login(buyerEmail);

        postJson("/api/wallets/top-ups", Map.of("amount_cents", 9_000L), buyerToken, uuid());
        String orderId = (String) postJson("/api/escrows",
                Map.of("seller_id", sellerId, "amount_cents", 9_000L), buyerToken, uuid())
                .getBody().get("id");
        assertThat(postJson("/api/escrows/" + orderId + "/fund", Map.of(), buyerToken, uuid())
                .getStatusCode().value()).isEqualTo(200);

        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT actor_id, actor_role, correlation_id, source_ip, old_status, new_status,
                       amount, result
                FROM audit_events WHERE event_type = 'escrow.funded' AND escrow_id = ?::uuid
                """, orderId);
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat(audit.get("actor_id")).isNotNull();          // resolved from JWT principal
        assertThat(audit.get("actor_role")).isEqualTo("USER");
        assertThat(audit.get("correlation_id")).isNotNull();
        assertThat(audit.get("source_ip")).isNotNull();
        assertThat(audit.get("old_status")).isEqualTo("CREATED");
        assertThat(audit.get("new_status")).isEqualTo("FUNDED");
        assertThat(((Number) audit.get("amount")).longValue()).isEqualTo(9_000L);

        // top-up / register / login of this flow were audited too
        assertThat(countAudits("wallet.topup")).isGreaterThanOrEqualTo(1);
        assertThat(countAudits("auth.register")).isGreaterThanOrEqualTo(2);
        assertThat(countAudits("auth.login")).isGreaterThanOrEqualTo(1);
    }

    // ---------- B. failure audit survives the business rollback (REQUIRES_NEW proof) ----------

    @Test
    void rejectionAuditSurvivesBusinessRollback() {
        String buyerEmail = newEmail("poor");
        Long sellerId = register(newEmail("seller2"));
        register(buyerEmail);
        String buyerToken = login(buyerEmail);

        postJson("/api/wallets/top-ups", Map.of("amount_cents", 1_000L), buyerToken, uuid());
        String orderId = (String) postJson("/api/escrows",
                Map.of("seller_id", sellerId, "amount_cents", 5_000L), buyerToken, uuid())
                .getBody().get("id");

        long rejectionsBefore = countRejectionsContaining("INSUFFICIENT_FUNDS");
        ResponseEntity<Map> response = postJson(
                "/api/escrows/" + orderId + "/fund", Map.of(), buyerToken, uuid());
        assertThat(response.getStatusCode().value()).isEqualTo(422);

        // Business side effects: ZERO (transaction rolled back)
        assertThat(jdbc.queryForObject(
                "SELECT status FROM escrow_orders WHERE id = ?::uuid", String.class, orderId))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference_id = ?",
                Integer.class, orderId)).isZero();

        // Yet the rejection audit row exists — written in its own REQUIRES_NEW transaction
        assertThat(countRejectionsContaining("INSUFFICIENT_FUNDS"))
                .isEqualTo(rejectionsBefore + 1);
    }

    // ---------- C. failed logins are audited ----------

    @Test
    void failedLoginIsAudited() {
        String email = newEmail("victim");
        register(email);

        long before = countRejectionsContaining("BAD_CREDENTIALS");
        assertThat(postJson("/api/auth/login",
                Map.of("email", email, "password", "wrong-pass1"), null, null)
                .getStatusCode().value()).isEqualTo(401);

        assertThat(countRejectionsContaining("BAD_CREDENTIALS")).isEqualTo(before + 1);
    }

    // ---------- helpers ----------

    private long countAudits(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = ?", Long.class, eventType);
    }

    private long countRejectionsContaining(String errorCode) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE result = 'REJECTED' AND details::text LIKE ?
                """, Long.class, "%" + errorCode + "%");
    }

    private String newEmail(String prefix) {
        return prefix + "+" + System.nanoTime() + "@example.com";
    }

    private Long register(String email) {
        ResponseEntity<Map> response = postJson("/api/auth/register",
                Map.of("email", email, "password", "abcd1234"), null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return ((Number) response.getBody().get("user_id")).longValue();
    }

    private String login(String email) {
        return (String) postJson("/api/auth/login",
                Map.of("email", email, "password", "abcd1234"), null, null).getBody().get("token");
    }

    private UUID uuid() {
        return UUID.randomUUID();
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
