package com.paynova.common;

import org.springframework.http.HttpStatus;

/**
 * Global error codes. Status code semantics (design doc §9):
 * 401 unauthenticated / 403 authenticated but not authorized / 409 resource-state or idempotency conflict /
 * 422 well-formed request that the business logic cannot process.
 */
public enum ErrorCode {
    // auth
    EMAIL_EXISTS(HttpStatus.CONFLICT),
    WEAK_PASSWORD(HttpStatus.UNPROCESSABLE_ENTITY),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    // generic
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    // escrow (Step 1)
    INVALID_AMOUNT(HttpStatus.UNPROCESSABLE_ENTITY),
    SELLER_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY),
    SELLER_IS_BUYER(HttpStatus.UNPROCESSABLE_ENTITY),
    // The codes below are enabled in later steps of the six-phase plan (Steps 3/4/5)
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY),
    LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
