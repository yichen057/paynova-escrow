package com.paynova.idempotency;

import com.paynova.common.ApiException;
import com.paynova.common.ErrorCode;

import java.util.UUID;

/** Idempotency-Key header parsing for money-write endpoints (§9: missing -> 400; must be a UUID). */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";

    private IdempotencyKeys() {
    }

    public static UUID parse(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key header is required for money-write endpoints");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key must be a UUID");
        }
    }
}
