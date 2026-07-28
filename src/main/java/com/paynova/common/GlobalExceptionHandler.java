package com.paynova.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Uniform error body: { error_code, message, correlation_id } (design doc §9). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final com.paynova.audit.RejectionAuditRecorder rejectionAudit;

    public GlobalExceptionHandler(com.paynova.audit.RejectionAuditRecorder rejectionAudit) {
        this.rejectionAudit = rejectionAudit;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        // §11: security-relevant rejections (illegal transitions, insufficient funds,
        // permission denials, bad credentials, ...) get a failure audit in a FRESH
        // transaction — the business transaction has already rolled back at this point.
        if (isAuditable(e.code())) {
            rejectionAudit.rejected("request.rejected",
                    e.code().name() + ": " + e.getMessage()
                            + " [" + request.getMethod() + " " + request.getRequestURI() + "]");
        }
        return body(e.code(), e.getMessage());
    }

    private boolean isAuditable(ErrorCode code) {
        return switch (code) {
            case FORBIDDEN, BAD_CREDENTIALS, ILLEGAL_STATE_TRANSITION, INSUFFICIENT_FUNDS,
                 IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS, INVALID_SIGNATURE, LOCK_TIMEOUT -> true;
            default -> false;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("validation failed");
        return body(ErrorCode.VALIDATION_ERROR, message);
    }

    /**
     * Spring Boot 3.2+ throws NoResourceFoundException for unmatched paths instead of returning 404 directly;
     * without a dedicated handler it would fall into the catch-all Exception handler below and surface as 500.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound() {
        return body(ErrorCode.NOT_FOUND, "resource not found");
    }

    /**
     * Last line of defense against double-posting at the ledger level (§4 uq_ledger_business): if the
     * same money-movement key is replayed after the request-level idempotency record expires (24h TTL),
     * the ledger unique constraint rejects it here → 409 — better to fail than to record the funds twice.
     * Only this constraint gets the narrowed mapping; other integrity violations still surface as 500
     * (those are bugs and must not be masked by a 409).
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(
            org.springframework.dao.DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uq_ledger_business")) {
            return body(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this operation was already recorded in the ledger");
        }
        log.error("data integrity violation", e);
        return body(ErrorCode.INTERNAL_ERROR, "internal error");
    }

    /**
     * Account lock wait timeout / deadlock victim (§7: SET LOCAL lock_timeout='5s') → 503,
     * explicitly telling the client the request is retryable. Note: lock waits on IdempotencyService's
     * own INSERT are caught internally and mapped to 409 REQUEST_IN_PROGRESS, so they never reach here.
     */
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleLockTimeout(
            org.springframework.dao.PessimisticLockingFailureException e) {
        return body(ErrorCode.LOCK_TIMEOUT, "lock acquisition timed out, please retry");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        // Hard rule: never log request headers or credentials; log only the exception itself
        log.error("unhandled exception", e);
        return body(ErrorCode.INTERNAL_ERROR, "internal error");
    }

    private ResponseEntity<Map<String, Object>> body(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(Map.of(
                "error_code", code.name(),
                "message", message,
                "correlation_id", String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY))
        ));
    }
}
