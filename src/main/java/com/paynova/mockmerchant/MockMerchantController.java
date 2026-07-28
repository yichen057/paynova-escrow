package com.paynova.mockmerchant;

import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;
import com.paynova.outbox.HmacSigner;
import com.paynova.outbox.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mock merchant receiving endpoint (§9 API #12): signature verification + persistent
 * event_id deduplication.
 *
 * The dedup record and the "business side effect" share one transaction — this is the
 * precondition for exactly-once effect: processing the business first and writing the
 * dedup record in a separate transaction leaves a crash window in which a redelivery
 * gets processed twice.
 * In this sandbox the "business side effect" is a log line; a real merchant would ship
 * goods or post funds here.
 */
@RestController
@RequestMapping("/api/webhooks")
public class MockMerchantController {

    private static final Logger log = LoggerFactory.getLogger(MockMerchantController.class);

    private final JdbcTemplate jdbc;
    private final WebhookProperties props;

    public MockMerchantController(JdbcTemplate jdbc, WebhookProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostMapping("/mock-merchant")
    @Transactional
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "X-PayNova-Signature", required = false) String signature,
            @RequestHeader(value = "X-PayNova-Event-Id", required = false) String eventId,
            @RequestBody String payload) {

        if (!HmacSigner.verify(props.getSecret(), payload, signature)) {
            throw new ApiException(ErrorCode.INVALID_SIGNATURE, "webhook signature verification failed");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "missing X-PayNova-Event-Id header");
        }

        int inserted = jdbc.update("""
                INSERT INTO webhook_receipts (event_id, payload_hash) VALUES (?::uuid, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId.trim(), HmacSigner.sha256Hex(payload));

        if (inserted == 0) {
            // At-least-once redelivery: already processed, so ACK without side effects
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }
        log.info("mock merchant processed webhook event {}", eventId);
        return ResponseEntity.ok(Map.of("status", "processed"));
    }
}
