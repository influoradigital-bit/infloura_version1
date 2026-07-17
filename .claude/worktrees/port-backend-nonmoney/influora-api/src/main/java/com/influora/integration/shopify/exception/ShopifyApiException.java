package com.influora.integration.shopify.exception;

import com.influora.common.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when a Shopify API call (OAuth token exchange, Admin API) fails. Mirrors {@code MetaApiException}. */
public class ShopifyApiException extends ApiException {

    public ShopifyApiException(String message) {
        this("SHOPIFY_API_ERROR", message, HttpStatus.BAD_GATEWAY);
    }

    public ShopifyApiException(String message, Throwable cause) {
        this("SHOPIFY_API_ERROR", message + ": " + cause.getMessage(), HttpStatus.BAD_GATEWAY);
    }

    protected ShopifyApiException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }
}
