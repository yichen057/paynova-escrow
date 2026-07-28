package com.paynova.it;

import com.paynova.idempotency.IdempotencyCleanupTask;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 3 integration tests (row-by-row verification of the §8 idempotency decision
 * table, real PostgreSQL):
 *  1. Replaying the same key x10 -> money moves exactly once, responses byte-identical
 *     (killer demo #1)
 *  2. Same key, different request body -> 409 IDEMPOTENCY_KEY_REUSED
 *  3. Missing key -> 400
 *  4. Concurrent requests with the same key -> executed exactly once, both callers
 *     get the same response
 *  5. Business failure rolls back -> the key is freed and retryable
 *  6. Replay after the 24h TTL cleanup -> hits the ledger-level uq_ledger_business
 *     defense -> 409 (live proof of how the two defense lines divide the work)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyFlowIT {

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
    @Autowired IdempotencyCleanupTask cleanupTask;

    @Test
    void replayTenTimesMovesMoneyExactlyOnce() {
        String token = registerAndLogin();
        String key = UUID.randomUUID().toString();
        Map<String, Long> body = Map.of("amount_cents", 100L);

        ResponseEntity<Map> first = topUp(token, key, body);
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        for (int i = 0; i < 9; i++) {
            ResponseEntity<Map> replay = topUp(token, key, body);
            assertThat(replay.getStatusCode().value()).isEqualTo(201);
            // Full response body is cached: byte-identical to the first response
            assertThat(replay.getBody()).isEqualTo(first.getBody());
        }

        assertThat(balance(token)).isEqualTo(100L);
        Integer txns = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE reference_id = ?", Integer.class, key);
        assertThat(txns).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentRequestIs409() {
        String token = registerAndLogin();
        String key = UUID.randomUUID().toString();

        assertThat(topUp(token, key, Map.of("amount_cents", 100L)).getStatusCode().value())
                .isEqualTo(201);
        ResponseEntity<Map> conflict = topUp(token, key, Map.of("amount_cents", 200L));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().get("error_code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(token)).isEqualTo(100L);
    }

    @Test
    void missingKeyIs400() {
        String token = registerAndLogin();
        assertThat(topUp(token, null, Map.of("amount_cents", 100L)).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void concurrentSameKeyExecutesOnce() throws Exception {
        String token = registerAndLogin();
        String key = UUID.randomUUID().toString();
        Map<String, Long> body = Map.of("amount_cents", 100L);

        var barrier = new CyclicBarrier(2);
        Callable<ResponseEntity<Map>> call = () -> {
            barrier.await();
            return topUp(token, key, body);
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.of(call, call));
            ResponseEntity<Map> a = results.get(0).get();
            ResponseEntity<Map> b = results.get(1).get();
            // The latecomer waits on the unique index until the first transaction commits,
            // then replays the cached response -- both get 201 with identical bodies
            assertThat(a.getStatusCode().value()).isEqualTo(201);
            assertThat(b.getStatusCode().value()).isEqualTo(201);
            assertThat(a.getBody()).isEqualTo(b.getBody());
        } finally {
            pool.shutdown();
        }
        assertThat(balance(token)).isEqualTo(100L);
    }

    @Test
    void businessFailureRollsBackAndFreesKey() {
        String token = registerAndLogin();
        Long sellerId = registeredUserId("seller");
        String key = UUID.randomUUID().toString();

        // First attempt: invalid amount -> 422; business write and idempotency record
        // roll back in the same transaction -> the key is freed
        ResponseEntity<Map> failed = postJson("/api/escrows",
                Map.of("seller_id", sellerId, "amount_cents", -5L), token, key);
        assertThat(failed.getStatusCode().value()).isEqualTo(422);

        // Retry with the same key (corrected request) -> executes normally instead of 409
        ResponseEntity<Map> retried = postJson("/api/escrows",
                Map.of("seller_id", sellerId, "amount_cents", 5_000L), token, key);
        assertThat(retried.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void afterTtlCleanupLedgerIsTheLastLineOfDefense() {
        String token = registerAndLogin();
        String key = UUID.randomUUID().toString();
        Map<String, Long> body = Map.of("amount_cents", 300L);
        assertThat(topUp(token, key, body).getStatusCode().value()).isEqualTo(201);

        // Simulate 24h expiry -> the cleanup task deletes the idempotency record
        jdbc.update("UPDATE idempotency_records SET created_at = now() - interval '25 hours' "
                + "WHERE idempotency_key = ?::uuid", key);
        cleanupTask.deleteExpired();
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?::uuid",
                Integer.class, key);
        assertThat(remaining).isZero();

        // With the request-level defense gone, replaying the same key is caught by the
        // ledger-level uq_ledger_business constraint -> 409, never a double booking
        ResponseEntity<Map> replay = topUp(token, key, body);
        assertThat(replay.getStatusCode().value()).isEqualTo(409);
        assertThat(balance(token)).isEqualTo(300L);
    }

    // ---------- helpers ----------

    private ResponseEntity<Map> topUp(String token, String key, Map<String, Long> body) {
        return postJson("/api/wallets/top-ups", body, token, key);
    }

    private long balance(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> me = rest.exchange("/api/accounts/me",
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        return ((Number) me.getBody().get("balance_cents")).longValue();
    }

    private final Map<String, String> emails = new java.util.HashMap<>();
    private final Map<String, Long> userIds = new java.util.HashMap<>();

    private String registerAndLogin() {
        registeredUserId("main");
        ResponseEntity<Map> login = postJson("/api/auth/login",
                Map.of("email", emails.get("main"), "password", "abcd1234"), null, null);
        return (String) login.getBody().get("token");
    }

    private Long registeredUserId(String prefix) {
        return userIds.computeIfAbsent(prefix, p -> {
            String email = p + "+" + System.nanoTime() + "@example.com";
            ResponseEntity<Map> response = postJson("/api/auth/register",
                    Map.of("email", email, "password", "abcd1234"), null, null);
            assertThat(response.getStatusCode().value()).isEqualTo(201);
            emails.put(p, email);
            return ((Number) response.getBody().get("user_id")).longValue();
        });
    }

    private ResponseEntity<Map> postJson(String path, Map<String, ?> body, String token, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class);
    }
}
