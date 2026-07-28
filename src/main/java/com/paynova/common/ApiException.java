package com.paynova.common;

/** Business exception: carries a global error code; mapped to the standard error body by GlobalExceptionHandler. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
