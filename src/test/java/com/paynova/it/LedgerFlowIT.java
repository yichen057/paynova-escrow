package com.paynova.it;

import com.paynova.account.Account;
import com.paynova.account.AccountRepository;
import com.paynova.ledger.LedgerEntryRepository;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 2 integration tests (real PostgreSQL):
 *  A. Registration creates the wallet (same transaction); top-ups are double-entry,
 *     cash_in goes negative, global SUM=0
 *  B. Snapshot balance == ledger-derived balance (the two books cross-check each other)
 *  C. Concurrent top-ups lose no update (existence proof of the atomic increment UPDATE)
 *  D. Live-fire tests of the database-level defenses: append-only trigger,
 *     non-negative CHECK constraint
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerFlowIT {

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
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository entryRepository;

    // ---------- A. Wallet on registration + double-entry top-up ----------

    @Test
    void registrationCreatesWalletAndTopUpIsDoubleEntry() {
        String token = registerAndLogin();

        // Wallet exists right after registration, balance 0
        ResponseEntity<Map> me = get("/api/accounts/me", token);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) me.getBody().get("balance_cents")).longValue()).isZero();

        // Top up $100.00
        ResponseEntity<Map> topUp = postJson("/api/wallets/top-ups",
                Map.of("amount_cents", 10_000L), token);
        assertThat(topUp.getStatusCode().value()).isEqualTo(201);
        assertThat(((Number) topUp.getBody().get("balance_cents")).longValue()).isEqualTo(10_000L);

        // Non-positive amount -> 422
        assertThat(postJson("/api/wallets/top-ups", Map.of("amount_cents", -5L), token)
                .getStatusCode().value()).isEqualTo(422);

        // Global invariant: SUM = 0 per currency (a top-up does not print money out of
        // thin air -- cash_in carries the liability)
        assertThat(entryRepository.globalSum("USD")).isZero();
        Long cashInBalance = jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE name = 'system:cash_in'", Long.class);
        assertThat(cashInBalance).isLessThan(0);

        // Transaction history is visible
        ResponseEntity<Map> txns = get("/api/accounts/me/transactions", token);
        assertThat(((List<?>) txns.getBody().get("items"))).hasSize(1);
    }

    // ---------- B. Snapshot vs ledger-derived balance ----------

    @Test
    void snapshotBalanceMatchesLedgerDerivedBalance() {
        String token = registerAndLogin();
        postJson("/api/wallets/top-ups", Map.of("amount_cents", 1_234L), token);
        postJson("/api/wallets/top-ups", Map.of("amount_cents", 4_321L), token);

        long accountId = ((Number) get("/api/accounts/me", token).getBody()
                .get("account_id")).longValue();
        Account wallet = accountRepository.findById(accountId).orElseThrow();

        // The ledger is the source of truth; the snapshot must agree with it
        assertThat(wallet.getBalance()).isEqualTo(5_555L);
        assertThat(entryRepository.derivedBalance(accountId)).isEqualTo(5_555L);
    }

    // ---------- C. Concurrent top-ups lose no update ----------

    @Test
    void concurrentTopUpsLoseNoUpdate() throws Exception {
        String token = registerAndLogin();
        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        Callable<Integer> topUp = () -> {
            barrier.await();
            return postJson("/api/wallets/top-ups", Map.of("amount_cents", 100L), token)
                    .getStatusCode().value();
        };

        var pool = Executors.newFixedThreadPool(threads);
        try {
            var results = pool.invokeAll(java.util.Collections.nCopies(threads, topUp));
            for (var f : results) {
                assertThat(f.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdown();
        }

        // balance = balance + delta is atomic: 8 x 100, not a cent lost
        long balance = ((Number) get("/api/accounts/me", token).getBody()
                .get("balance_cents")).longValue();
        assertThat(balance).isEqualTo(800L);
        assertThat(entryRepository.globalSum("USD")).isZero();
    }

    // ---------- D. Live-fire tests of the database-level defenses ----------

    @Test
    void ledgerEntriesAreAppendOnlyAtDatabaseLevel() {
        String token = registerAndLogin();
        postJson("/api/wallets/top-ups", Map.of("amount_cents", 500L), token);

        // The trigger rejects UPDATE / DELETE outright -- the ledger cannot be altered
        // even by bypassing all Java code
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .hasMessageContaining("append-only");
    }

    @Test
    void userWalletCannotGoNegativeAtDatabaseLevel() {
        String token = registerAndLogin();
        long accountId = ((Number) get("/api/accounts/me", token).getBody()
                .get("account_id")).longValue();

        // CHECK (allow_negative OR balance >= 0): the last line of defense
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE accounts SET balance = balance - 1 WHERE id = ?", accountId))
                .hasMessageContaining("balance_non_negative");
    }

    @Test
    void duplicateBusinessReferenceIsRejectedByLedger() {
        String token = registerAndLogin();
        postJson("/api/wallets/top-ups", Map.of("amount_cents", 700L), token);

        // uq_ledger_business: inserting a second parent record with the same type and
        // reference as an existing TOP_UP -> rejected by the unique constraint
        Map<String, Object> existing = jdbc.queryForMap(
                "SELECT type, reference_type, reference_id FROM ledger_transactions LIMIT 1");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ledger_transactions (type, reference_type, reference_id) VALUES (?,?,?)",
                existing.get("type"), existing.get("reference_type"), existing.get("reference_id")))
                .hasMessageContaining("uq_ledger_business");
    }

    // ---------- helpers ----------

    private String registerAndLogin() {
        String email = "ledger+" + System.nanoTime() + "@example.com";
        assertThat(postJson("/api/auth/register",
                Map.of("email", email, "password", "abcd1234"), null)
                .getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map> login = postJson("/api/auth/login",
                Map.of("email", email, "password", "abcd1234"), null);
        return (String) login.getBody().get("token");
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> postJson(String path, Map<String, ?> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Since Step 3, money-moving endpoints require an idempotency key; this class generates
        // a fresh key per call (idempotency semantics are covered by IdempotencyFlowIT)
        headers.set("Idempotency-Key", java.util.UUID.randomUUID().toString());
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class);
    }
}
