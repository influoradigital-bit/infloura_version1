package com.influora.integration.razorpay;

/** Wraps any transport/parsing failure talking to Razorpay/RazorpayX. */
public class RazorpayIntegrationException extends RuntimeException {
    public RazorpayIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
