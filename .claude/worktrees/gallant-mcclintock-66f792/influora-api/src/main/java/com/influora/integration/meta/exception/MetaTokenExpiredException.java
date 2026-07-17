package com.influora.integration.meta.exception;

import org.springframework.http.HttpStatus;

/** Thrown when Meta returns 401 for a stored access token — caller should trigger re-auth/refresh. */
public class MetaTokenExpiredException extends MetaApiException {

    public MetaTokenExpiredException(String message) {
        super("META_TOKEN_EXPIRED", message, HttpStatus.UNAUTHORIZED);
    }

    public MetaTokenExpiredException(String message, Throwable cause) {
        super("META_TOKEN_EXPIRED", message, HttpStatus.UNAUTHORIZED);
        initCause(cause);
    }
}
