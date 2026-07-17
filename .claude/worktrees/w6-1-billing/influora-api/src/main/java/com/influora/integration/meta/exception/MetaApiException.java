package com.influora.integration.meta.exception;

import com.influora.common.ApiException;
import org.springframework.http.HttpStatus;

/** Base exception for any Meta Graph API transport/parsing/business failure. */
public class MetaApiException extends ApiException {

    public MetaApiException(String message) {
        this("META_API_ERROR", message, HttpStatus.BAD_GATEWAY);
    }

    public MetaApiException(String message, Throwable cause) {
        this("META_API_ERROR", message, HttpStatus.BAD_GATEWAY);
        initCause(cause);
    }

    protected MetaApiException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }
}
