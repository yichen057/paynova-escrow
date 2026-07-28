package com.paynova.it;

import com.paynova.account.AccountLockService;
import com.paynova.account.AccountRepository;
import com.paynova.ledger.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;
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
 * Step 4 integration tests (real PostgreSQL) -- home of this project's flagship test:
 *  A. Full flow: top-up -> create order -> fund -> release; money moves through all
 *     three legs and the global SUM stays 0
 *  B. Refund flow: fund -> refund; reversal_of points at the original FUND transaction
 *  C. Permissions: non-buyer fund/release -> 403; refund by anyone other than the
 *     seller or an admin -> 403
 *  D. Insufficient funds -> 422; order stays CREATED, zero ledger entries
 *  E. [Flagship] $100 balance, two concurrent $80 payments -> exactly one succeeds
 *     and one gets 422; final balance $20
 *  F. Idempotent replay on money endpoints: replaying fund with the same key debits once
 *  G. Concurrent lockAll with reversed input order -> no deadlock (existence proof
 *     of the internal ascending-order discipline)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EscrowPaymentFlowIT {

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
    @Autowired LedgerEntryRepository entryRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountLockService lockService;
    @Autowired TransactionTemplate transactionTemplate;

    private String buyerToken;
    private String sellerToken;
    private String strangerToken;
    private Long sellerId;

    @BeforeEach
    void setUp() {
        buyerToken = registerAndLogin("buyer");
        sellerId = lastRegisteredId("seller");
        sellerToken = lastLogin;
        strangerToken = registerAndLogin("stranger");
    }

    // ---------- A. Full-flow happy path ----------

    @Test
    void fundThenReleaseMovesMoneyThroughEscrow() {
        // The escrow account is a system account shared across this test class, and other
        // tests also deposit into it -- so assertions must use before/after deltas, never
        // absolute values (test-isolation lesson; see interviewQ&A 11.11)
        long escrowBefore = escrowBalance();
        topUp(buyerToken, 10_000L);
        String orderId = createOrder(buyerToken, sellerId, 5_000L);

        // fund: buyer 10000 -> 5000, escrow +5000, order FUNDED
        ResponseEntity<Map> funded = money(buyerToken, orderId, "fund", UUID.randomUUID());
        assertThat(funded.getStatusCode().value()).isEqualTo(200);
        assertThat(funded.getBody().get("status")).isEqualTo("FUNDED");
        assertThat(balance(buyerToken)).isEqualTo(5_000L);
        assertThat(escrowBalance()).isEqualTo(escrowBefore + 5_000L);

        // release: escrow -> seller, order RELEASED
        ResponseEntity<Map> released = money(buyerToken, orderId, "release", UUID.randomUUID());
        assertThat(released.getStatusCode().value()).isEqualTo(200);
        assertThat(released.getBody().get("status")).isEqualTo("RELEASED");
        assertThat(balance(sellerToken)).isEqualTo(5_000L);
        assertThat(escrowBalance()).isEqualTo(escrowBefore);

        assertThat(entryRepository.globalSum("USD")).isZero();
    }

    // ---------- B. Refund flow + reversal reference ----------

    @Test
    void refundReversesTheOriginalFundTransaction() {
        long escrowBefore = escrowBalance();
        topUp(buyerToken, 8_000L);
        String orderId = createOrder(buyerToken, sellerId, 8_000L);
        money(buyerToken, orderId, "fund", UUID.randomUUID());

        ResponseEntity<Map> refunded = money(sellerToken, orderId, "refund", UUID.randomUUID());
        assertThat(refunded.getStatusCode().value()).isEqualTo(200);
        assertThat(refunded.getBody().get("status")).isEqualTo("REFUNDED");
        assertThat(balance(buyerToken)).isEqualTo(8_000L);
        // Money funded in and refunded out: the escrow account returns to its level at
        // the start of this test (delta assertion)
        assertThat(escrowBalance()).isEqualTo(escrowBefore);

        // reversal_of points at the original ESCROW_FUND transaction (reversal pair is traceable)
        UUID reversalOf = jdbc.queryForObject("""
                SELECT reversal_of FROM ledger_transactions
                WHERE type='ESCROW_REFUND' AND reference_id = ?
                """, UUID.class, orderId);
        UUID fundTxnId = jdbc.queryForObject("""
                SELECT id FROM ledger_transactions
                WHERE type='ESCROW_FUND' AND reference_id = ?
                """, UUID.class, orderId);
        assertThat(reversalOf).isEqualTo(fundTxnId);
    }

    // ---------- C. Permissions ----------

    @Test
    void moneyActionsEnforceActorPermissions() {
        topUp(buyerToken, 5_000L);
        String orderId = createOrder(buyerToken, sellerId, 5_000L);

        assertThat(money(strangerToken, orderId, "fund", UUID.randomUUID())
                .getStatusCode().value()).isEqualTo(403);
        assertThat(money(sellerToken, orderId, "fund", UUID.randomUUID())
                .getStatusCode().value()).isEqualTo(403);

        money(buyerToken, orderId, "fund", UUID.randomUUID());
        assertThat(money(sellerToken, orderId, "release", UUID.randomUUID())
                .getStatusCode().value()).isEqualTo(403);
        assertThat(money(buyerToken, orderId, "refund", UUID.randomUUID())
                .getStatusCode().value()).isEqualTo(403);
    }

    // ---------- D. Insufficient funds ----------

    @Test
    void insufficientFundsIs422AndZeroSideEffects() {
        topUp(buyerToken, 1_000L);
        String orderId = createOrder(buyerToken, sellerId, 5_000L);

        ResponseEntity<Map> response = money(buyerToken, orderId, "fund", UUID.randomUUID());
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("error_code")).isEqualTo("INSUFFICIENT_FUNDS");

        // Zero side effects: order still CREATED, no ledger entries for it, balance untouched
        assertThat(orderStatus(orderId)).isEqualTo("CREATED");
        Integer entries = jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE reference_id = ?
                """, Integer.class, orderId);
        assertThat(entries).isZero();
        assertThat(balance(buyerToken)).isEqualTo(1_000L);
    }

    // ---------- E. Flagship test: $100 balance, two concurrent $80 payments ----------

    @Test
    void hundredDollarsTwoConcurrent80DollarPaymentsExactlyOneSucceeds() throws Exception {
        long escrowBefore = escrowBalance();
        topUp(buyerToken, 10_000L);   // $100
        String orderA = createOrder(buyerToken, sellerId, 8_000L);   // $80
        String orderB = createOrder(buyerToken, sellerId, 8_000L);   // $80

        var barrier = new CyclicBarrier(2);
        Callable<Integer> fundA = () -> {
            barrier.await();
            return money(buyerToken, orderA, "fund", UUID.randomUUID()).getStatusCode().value();
        };
        Callable<Integer> fundB = () -> {
            barrier.await();
            return money(buyerToken, orderB, "fund", UUID.randomUUID()).getStatusCode().value();
        };

        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.of(fundA, fundB));
            var statuses = new java.util.ArrayList<Integer>();
            for (var f : results) {
                statuses.add(f.get());
            }
            statuses.sort(Integer::compareTo);
            // The pessimistic lock serializes the two debits: exactly one 200 and one 422 -- never a double spend
            assertThat(statuses).containsExactly(200, 422);
        } finally {
            pool.shutdown();
        }

        assertThat(balance(buyerToken)).isEqualTo(2_000L);   // $20
        assertThat(escrowBalance()).isEqualTo(escrowBefore + 8_000L);
        assertThat(entryRepository.globalSum("USD")).isZero();
    }

    // ---------- F. Idempotent replay on the money endpoint ----------

    @Test
    void fundReplayWithSameKeyDebitsOnce() {
        topUp(buyerToken, 6_000L);
        String orderId = createOrder(buyerToken, sellerId, 5_000L);
        UUID key = UUID.randomUUID();

        ResponseEntity<Map> first = money(buyerToken, orderId, "fund", key);
        ResponseEntity<Map> replay = money(buyerToken, orderId, "fund", key);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(balance(buyerToken)).isEqualTo(1_000L);   // debited exactly once
    }

    // ---------- G. lockAll with reversed input order: no deadlock ----------

    @Test
    void lockAllWithReversedInputOrderDoesNotDeadlock() throws Exception {
        Long a = accountRepository.findByName("system:cash_in").orElseThrow().getId();
        Long b = accountRepository.findByName("system:escrow").orElseThrow().getId();

        var barrier = new CyclicBarrier(2);
        Callable<Boolean> forward = () -> {
            barrier.await();
            transactionTemplate.execute(tx -> {
                lockService.lockAll(List.of(a, b));
                sleep(300);   // hold the locks to open a window for the other thread
                return null;
            });
            return true;
        };
        Callable<Boolean> reversed = () -> {
            barrier.await();
            transactionTemplate.execute(tx -> {
                lockService.lockAll(List.of(b, a));   // reversed input order
                sleep(300);
                return null;
            });
            return true;
        };

        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.of(forward, reversed));
            // lockAll sorts by account id internally -> both transactions acquire locks in the
            // same order -> they queue behind each other instead of waiting on each other
            assertThat(results.get(0).get()).isTrue();
            assertThat(results.get(1).get()).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    // ---------- helpers ----------

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long escrowBalance() {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE name = 'system:escrow'", Long.class);
    }

    private String orderStatus(String orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM escrow_orders WHERE id = ?::uuid", String.class, orderId);
    }

    private void topUp(String token, long amountCents) {
        ResponseEntity<Map> response = postJson("/api/wallets/top-ups",
                Map.of("amount_cents", amountCents), token, UUID.randomUUID());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    private String createOrder(String token, Long sellerId, long amountCents) {
        ResponseEntity<Map> response = postJson("/api/escrows",
                Map.of("seller_id", sellerId, "amount_cents", amountCents), token, UUID.randomUUID());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<Map> money(String token, String orderId, String action, UUID key) {
        return postJson("/api/escrows/" + orderId + "/" + action, Map.of(), token, key);
    }

    private long balance(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> me = rest.exchange("/api/accounts/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        return ((Number) me.getBody().get("balance_cents")).longValue();
    }

    private String lastLogin;

    private String registerAndLogin(String prefix) {
        lastRegisteredId(prefix);
        return lastLogin;
    }

    private Long lastRegisteredId(String prefix) {
        String email = prefix + "+" + System.nanoTime() + "@example.com";
        ResponseEntity<Map> registered = postJson("/api/auth/register",
                Map.of("email", email, "password", "abcd1234"), null, null);
        assertThat(registered.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map> login = postJson("/api/auth/login",
                Map.of("email", email, "password", "abcd1234"), null, null);
        lastLogin = (String) login.getBody().get("token");
        return ((Number) registered.getBody().get("user_id")).longValue();
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
