package com.influora.integration.meta.exception;

import org.springframework.http.HttpStatus;

/** Thrown when Meta's own 429 is returned, or our pre-flight {@code MetaRateLimitTracker} check trips first. */
public class MetaRateLimitException extends MetaApiException {

    public MetaRateLimitException(String message) {
        super("META_RATE_LIMITED", message, HttpStatus.TOO_MANY_REQUESTS);
    }

    public MetaRateLimitException(String message, Throwable cause) {
        super("META_RATE_LIMITED", message, HttpStatus.TOO_MANY_REQUESTS);
        initCause(cause);
    }
}
