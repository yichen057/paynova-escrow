package com.paynova.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Request-level idempotency (design doc §8, option A: the idempotency record shares
 * the business transaction).
 *
 * Design decision (Step 3):
 *  - Explicit template method instead of an AOP annotation: the idempotency boundary
 *    is visible at the call site, and two classic pitfalls — aspect ordering and
 *    silent self-invocation bypass — cannot occur.
 *  - Cache the full response body (Stripe's approach): a retry returns a byte-for-byte
 *    copy of the first response.
 *
 * Write protocol: native INSERT ... ON CONFLICT DO NOTHING (never save + catch —
 * a unique-constraint violation inside a Hibernate transaction marks it rollback-only,
 * so execution cannot continue after catching).
 *
 * Invariant (§8): under option A the record shares the business transaction and the
 * COMPLETED update happens before commit, so any committed record must be COMPLETED —
 * reading IN_PROGRESS means the invariant was broken.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Outcome of an idempotent operation = the HTTP response to replay. */
    public record Result(int status, Map<String, Object> body, String resourceId) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;   // Spring's mapper: includes JavaTimeModule

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Result execute(Long userId, UUID key, String requestHash, Supplier<Result> business) {
        // Transaction-scoped timeout (§7: SET LOCAL, does not leak into the connection pool);
        // wait at most 5s for the first transaction holding the same key
        jdbc.execute("SET LOCAL lock_timeout = '5s'");

        int claimed;
        try {
            claimed = jdbc.update("""
                    INSERT INTO idempotency_records (user_id, idempotency_key, request_hash, status)
                    VALUES (?, ?, ?, 'IN_PROGRESS')
                    ON CONFLICT (user_id, idempotency_key) DO NOTHING
                    """, userId, key, requestHash);
        } catch (QueryTimeoutException | PessimisticLockingFailureException e) {
            // The first transaction for this key has been running for over 5s:
            // per the decision table return 409 so the client retries later
            throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS,
                    "a request with this Idempotency-Key is still in progress");
        }

        if (claimed == 1) {
            // This request won the claim. If the business logic throws, the whole
            // transaction rolls back (the idempotency record vanishes with it, releasing the key)
            Result result = business.get();
            jdbc.update("""
                    UPDATE idempotency_records
                    SET status = 'COMPLETED', response_status = ?, response_body = ?::jsonb,
                        resource_id = ?
                    WHERE user_id = ? AND idempotency_key = ?
                    """, result.status(), toJson(result.body()), result.resourceId(), userId, key);
            return result;
        }

        // claimed == 0: the key already exists (possibly after waiting out the first
        // transaction's commit). Under READ COMMITTED a new statement sees committed data.
        Map<String, Object> record = jdbc.queryForMap("""
                SELECT status, request_hash, response_status, response_body::text AS response_body
                FROM idempotency_records
                WHERE user_id = ? AND idempotency_key = ?
                """, userId, key);

        if (!"COMPLETED".equals(String.valueOf(record.get("status")).trim())) {
            // Defensive branch: must not happen per the §8 invariant; if it does,
            // log it and treat the request as still in progress
            log.error("idempotency invariant violated: committed IN_PROGRESS record, user={} key={}",
                    userId, key);
            throw new ApiException(ErrorCode.REQUEST_IN_PROGRESS,
                    "a request with this Idempotency-Key is still in progress");
        }
        String storedHash = String.valueOf(record.get("request_hash")).trim();
        if (!storedHash.equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this Idempotency-Key was already used with a different request");
        }
        // Decision table row 1: replay the first response verbatim
        return new Result(((Number) record.get("response_status")).intValue(),
                fromJson((String) record.get("response_body")), null);
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cached response", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize cached response", e);
        }
    }
}
