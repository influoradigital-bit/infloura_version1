package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "influora.r2")
public class R2Properties {

    private String accountId = "";
    private String accessKeyId = "";
    private String secretAccessKey = "";
    private String bucketName = "influora-dev";
    private String endpoint = "";
    private String publicUrl = "https://r2.influora.com";
    private int presignExpirySeconds = 900;
    private long maxVideoBytes = 524_288_000L;

    /**
     * EV-006: the placeholders application.yml and influora-api/env.example ship
     * ({@code REPLACE_WITH_YOUR_R2_*}) used to count as configured because they are non-blank, so
     * {@link com.influora.integration.storage.R2StorageService#isAvailable()} said yes and presign
     * handed the browser a URL on a host that does not exist. A placeholder is now "not configured"
     * -- the same {@code REPLACE_WITH_} sentinel {@link ShopifyProperties} already refuses.
     */
    public boolean isConfigured() {
        return isReal(accountId)
                && isReal(accessKeyId)
                && isReal(secretAccessKey)
                && bucketName != null && !bucketName.isBlank();
    }

    private static boolean isReal(String value) {
        return value != null && !value.isBlank()
                && !value.startsWith("REPLACE_WITH_") && !value.startsWith("REPLACE_ME");
    }

    /** S3 API endpoint: https://{accountId}.r2.cloudflarestorage.com */
    public String resolvedEndpoint() {
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        if (accountId == null || accountId.isBlank()) {
            return "";
        }
        return "https://" + accountId + ".r2.cloudflarestorage.com";
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }

    public void setSecretAccessKey(String secretAccessKey) {
        this.secretAccessKey = secretAccessKey;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public int getPresignExpirySeconds() {
        return presignExpirySeconds;
    }

    public void setPresignExpirySeconds(int presignExpirySeconds) {
        this.presignExpirySeconds = presignExpirySeconds;
    }

    public long getMaxVideoBytes() {
        return maxVideoBytes;
    }

    public void setMaxVideoBytes(long maxVideoBytes) {
        this.maxVideoBytes = maxVideoBytes;
    }
}
