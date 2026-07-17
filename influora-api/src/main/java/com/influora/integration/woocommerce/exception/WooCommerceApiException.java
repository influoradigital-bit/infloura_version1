package com.influora.integration.woocommerce.exception;

import com.influora.common.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when a WooCommerce-related internal operation (secret encryption, site-url validation) fails. Mirrors {@code ShopifyApiException}. */
public class WooCommerceApiException extends ApiException {

    public WooCommerceApiException(String message) {
        this("WOOCOMMERCE_API_ERROR", message, HttpStatus.BAD_GATEWAY);
    }

    public WooCommerceApiException(String message, Throwable cause) {
        this("WOOCOMMERCE_API_ERROR", message + ": " + cause.getMessage(), HttpStatus.BAD_GATEWAY);
    }

    protected WooCommerceApiException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }
}
