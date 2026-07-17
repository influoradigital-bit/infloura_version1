package com.influora.integration.meta.exception;

import org.springframework.http.HttpStatus;

/** Thrown when Meta returns 403 (OAuthException with a missing-scope subcode) for a call. */
public class MetaPermissionDeniedException extends MetaApiException {

    public MetaPermissionDeniedException(String message) {
        super("META_PERMISSION_DENIED", message, HttpStatus.FORBIDDEN);
    }

    public MetaPermissionDeniedException(String message, Throwable cause) {
        super("META_PERMISSION_DENIED", message, HttpStatus.FORBIDDEN);
        initCause(cause);
    }
}
