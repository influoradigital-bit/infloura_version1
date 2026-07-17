package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay (orders/payments) + RazorpayX (payouts) credentials and fee config.
 *
 * <p>[SEC: C-16] Distinct secret from JWT/internal-service-token/R2 keys — never shared.
 * No {@code razorpay-java} SDK dependency exists in pom.xml yet; {@code RazorpayClient} /
 * {@code RazorpayXClient} use plain {@code java.net.http.HttpClient} against these values so the
 * module compiles today. Swap to the official SDK once Priya approves the dependency.
 */
@ConfigurationProperties(prefix = "influora.razorpay")
public class RazorpayProperties {

    private String keyId = "";
    private String keySecret = "";
    private String webhookSecret = "";
    private String payoutAccountNumber = "";
    private String apiBaseUrl = "https://api.razorpay.com/v1";
    private String payoutApiBaseUrl = "https://api.razorpay.com/v1";

    /** Platform fee applied on escrow fund, as a percentage (e.g. 15.00 = 15%). */
    private java.math.BigDecimal platformFeePercent = new java.math.BigDecimal("15.00");

    public boolean isConfigured() {
        return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getPayoutAccountNumber() {
        return payoutAccountNumber;
    }

    public void setPayoutAccountNumber(String payoutAccountNumber) {
        this.payoutAccountNumber = payoutAccountNumber;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getPayoutApiBaseUrl() {
        return payoutApiBaseUrl;
    }

    public void setPayoutApiBaseUrl(String payoutApiBaseUrl) {
        this.payoutApiBaseUrl = payoutApiBaseUrl;
    }

    public java.math.BigDecimal getPlatformFeePercent() {
        return platformFeePercent;
    }

    public void setPlatformFeePercent(java.math.BigDecimal platformFeePercent) {
        this.platformFeePercent = platformFeePercent;
    }
}
