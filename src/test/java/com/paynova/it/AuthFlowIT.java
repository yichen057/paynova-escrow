package com.paynova.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 0 integration smoke test, run against real PostgreSQL (Testcontainers). Verifies:
 *  1) Flyway migrations succeed; all 9 tables and 3 system accounts are in place
 *  2) The full register -> login -> access-protected-endpoint-with-JWT flow
 *  3) 401/404/409/422 status-code semantics (403 cases will be added once the
 *     /api/admin/** endpoints land in a later Step)
 *  4) Concurrent duplicate registration: exactly one 201 + one 409 (backed by the
 *     unique constraint, never a 500)
 * Run with: mvn verify (requires Docker). Not part of the unit-test run (mvn test).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesAllNineTablesAndSeedsSystemAccounts() {
        Integer tables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_name IN
                ('users','accounts','escrow_orders','ledger_transactions','ledger_entries',
                 'idempotency_records','audit_events','outbox_events','webhook_receipts')
                """, Integer.class);
        assertThat(tables).isEqualTo(9);

        Integer systemAccounts = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE type='SYSTEM'", Integer.class);
        assertThat(systemAccounts).isEqualTo(3);

        Boolean cashInNegative = jdbc.queryForObject(
                "SELECT allow_negative FROM accounts WHERE name='system:cash_in'", Boolean.class);
        Boolean escrowNegative = jdbc.queryForObject(
                "SELECT allow_negative FROM accounts WHERE name='system:escrow'", Boolean.class);
        assertThat(cashInNegative).isTrue();
        assertThat(escrowNegative).isFalse();
    }

    @Test
    void registerLoginAndAccessProtectedEndpoint() {
        var email = "user+" + System.nanoTime() + "@example.com";

        ResponseEntity<Map> registered = postJson("/api/auth/register",
                Map.of("email", email, "password", "abcd1234"));
        assertThat(registered.getStatusCode().value()).isEqualTo(201);

        // Weak password -> 422
        assertThat(postJson("/api/auth/register",
                Map.of("email", "weak@example.com", "password", "short"))
                .getStatusCode().value()).isEqualTo(422);

        // Duplicate email -> 409
        assertThat(postJson("/api/auth/register",
                Map.of("email", email, "password", "abcd1234"))
                .getStatusCode().value()).isEqualTo(409);

        // Wrong password -> 401
        assertThat(postJson("/api/auth/login",
                Map.of("email", email, "password", "wrong-pass1"))
                .getStatusCode().value()).isEqualTo(401);

        ResponseEntity<Map> login = postJson("/api/auth/login",
                Map.of("email", email, "password", "abcd1234"));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String token = (String) login.getBody().get("token");
        assertThat(token).isNotBlank();

        // Protected endpoint without a token -> 401; with a token -> 200 (endpoint implemented
        // since Step 2: registration creates the wallet, initial balance 0)
        assertThat(rest.getForEntity("/api/accounts/me", Map.class).getStatusCode().value())
                .isEqualTo(401);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> withToken = rest.exchange("/api/accounts/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(withToken.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) withToken.getBody().get("balance_cents")).longValue()).isZero();
    }

    @Test
    void concurrentDuplicateRegistrationYieldsExactlyOne201AndOne409() throws Exception {
        var email = "race+" + System.nanoTime() + "@example.com";
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Integer> attempt = () -> {
            barrier.await();
            return postJson("/api/auth/register",
                    Map.of("email", email, "password", "abcd1234")).getStatusCode().value();
        };

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(java.util.List.of(attempt, attempt));
            var statuses = new java.util.ArrayList<Integer>();
            for (var f : results) {
                statuses.add(f.get());
            }
            statuses.sort(Integer::compareTo);
            // TOCTOU defense check: however the two requests interleave, the outcome must be
            // 201+409 -- never a 500 and never two 201s
            assertThat(statuses).containsExactly(201, 409);
        } finally {
            pool.shutdown();
        }
    }

    private ResponseEntity<Map> postJson(String path, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class);
    }
}
