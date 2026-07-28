package com.paynova.it;

import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;
import com.paynova.escrow.EscrowAction;
import com.paynova.escrow.EscrowOrder;
import com.paynova.escrow.EscrowRepository;
import com.paynova.escrow.EscrowService;
import com.paynova.escrow.EscrowStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 1 integration tests (real PostgreSQL):
 *  A. API layer: business validation on order creation (three 422 cases) and
 *     participant-only visibility (403)
 *  B. Service layer: the legal transition chain; illegal transitions return 409
 *     and leave the state unchanged
 *  C. Concurrency: two threads RELEASE the same FUNDED order simultaneously and
 *     exactly one succeeds -- existence proof of the CAS guard
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EscrowFlowIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TestRestTemplate rest;
    @Autowired EscrowService escrowService;
    @Autowired EscrowRepository escrowRepository;

    private Long buyerId;
    private Long sellerId;
    private String buyerToken;
    private String strangerToken;

    @BeforeEach
    void setUpUsers() {
        buyerId = register("buyer");
        sellerId = register("seller");
        buyerToken = login("buyer");
        register("stranger");
        strangerToken = login("stranger");
    }

    // ---------- A. API layer ----------

    @Test
    void createValidatesBusinessRulesWith422() {
        // Happy-path creation
        ResponseEntity<Map> created = createEscrow(buyerToken, sellerId, 5_000L);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("status")).isEqualTo("CREATED");

        // Non-positive amount / unknown seller / buyer equals seller -> all 422
        assertThat(createEscrow(buyerToken, sellerId, 0L).getStatusCode().value()).isEqualTo(422);
        assertThat(createEscrow(buyerToken, 999_999L, 5_000L).getStatusCode().value()).isEqualTo(422);
        assertThat(createEscrow(buyerToken, buyerId, 5_000L).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void onlyParticipantsCanReadOrder() {
        ResponseEntity<Map> created = createEscrow(buyerToken, sellerId, 5_000L);
        String orderId = (String) created.getBody().get("id");

        assertThat(getEscrow(buyerToken, orderId).getStatusCode().value()).isEqualTo(200);
        // Non-participant -> 403 (§9 API #10)
        assertThat(getEscrow(strangerToken, orderId).getStatusCode().value()).isEqualTo(403);
    }

    // ---------- B. Service-layer state machine ----------

    @Test
    void legalChainAndIllegalTransitions() {
        UUID orderId = newOrder();

        assertThat(escrowService.transition(orderId, EscrowAction.FUND)).isEqualTo(EscrowStatus.FUNDED);
        assertThat(escrowService.transition(orderId, EscrowAction.RELEASE)).isEqualTo(EscrowStatus.RELEASED);

        // Any action after a terminal state -> 409, and the state stays RELEASED (zero side effects)
        assertThatThrownBy(() -> escrowService.transition(orderId, EscrowAction.REFUND))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
        assertThat(status(orderId)).isEqualTo(EscrowStatus.RELEASED);

        // RELEASE directly from CREATED -> 409 (illegal transition)
        UUID another = newOrder();
        assertThatThrownBy(() -> escrowService.transition(another, EscrowAction.RELEASE))
                .isInstanceOf(ApiException.class);
        assertThat(status(another)).isEqualTo(EscrowStatus.CREATED);
    }

    // ---------- C. Concurrent double RELEASE: existence proof of the CAS guard ----------

    @Test
    void concurrentDoubleReleaseExactlyOneWins() throws Exception {
        UUID orderId = newOrder();
        escrowService.transition(orderId, EscrowAction.FUND);

        var barrier = new CyclicBarrier(2);
        Callable<Boolean> release = () -> {
            barrier.await();
            try {
                escrowService.transition(orderId, EscrowAction.RELEASE);
                return true;
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
                return false;
            }
        };

        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.of(release, release));
            long winners = results.stream().filter(f -> {
                try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).count();
            // However the threads interleave: exactly one winner, never a double RELEASE
            assertThat(winners).isEqualTo(1);
            assertThat(status(orderId)).isEqualTo(EscrowStatus.RELEASED);
        } finally {
            pool.shutdown();
        }
    }

    // ---------- helpers ----------

    private UUID newOrder() {
        return escrowService.create(buyerId, sellerId, 5_000L, "test order").getId();
    }

    private EscrowStatus status(UUID orderId) {
        return escrowRepository.findById(orderId).map(EscrowOrder::getStatus).orElseThrow();
    }

    private Long register(String prefix) {
        String email = prefix + "+" + System.nanoTime() + "@example.com";
        ResponseEntity<Map> response = postJson("/api/auth/register",
                Map.of("email", email, "password", "abcd1234"), null);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        emails.put(prefix, email);
        return ((Number) response.getBody().get("user_id")).longValue();
    }

    private final Map<String, String> emails = new java.util.HashMap<>();

    private String login(String prefix) {
        ResponseEntity<Map> response = postJson("/api/auth/login",
                Map.of("email", emails.get(prefix), "password", "abcd1234"), null);
        return (String) response.getBody().get("token");
    }

    private ResponseEntity<Map> createEscrow(String token, Long sellerId, Long amountCents) {
        return postJson("/api/escrows",
                Map.of("seller_id", sellerId, "amount_cents", amountCents, "description", "it"), token);
    }

    private ResponseEntity<Map> getEscrow(String token, String orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/api/escrows/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
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
