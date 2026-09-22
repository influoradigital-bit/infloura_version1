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

    /**
     * Meta did not answer in time (or could not be reached). The message is shown to the user
     * as-is by the OAuth callback page, so it says what to do next. Starting again is the only
     * recovery: the authorization code in flight is single-use.
     */
    public static MetaApiException unavailable(Throwable cause) {
        MetaApiException e =
                new MetaApiException(
                        "META_UNAVAILABLE",
                        "Instagram/Facebook took too long to respond. Please start the connection again.",
                        HttpStatus.GATEWAY_TIMEOUT);
        e.initCause(cause);
        return e;
    }
}
