package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "influora.jwt")
public class JwtProperties {

    private String accessSecret;
    private String refreshSecret;
    private long accessExpirySeconds = 900;
    private long refreshExpirySeconds = 2_592_000;

    public String getAccessSecret() {
        return accessSecret;
    }

    public void setAccessSecret(String accessSecret) {
        this.accessSecret = accessSecret;
    }

    public String getRefreshSecret() {
        return refreshSecret;
    }

    public void setRefreshSecret(String refreshSecret) {
        this.refreshSecret = refreshSecret;
    }

    public long getAccessExpirySeconds() {
        return accessExpirySeconds;
    }

    public void setAccessExpirySeconds(long accessExpirySeconds) {
        this.accessExpirySeconds = accessExpirySeconds;
    }

    public long getRefreshExpirySeconds() {
        return refreshExpirySeconds;
    }

    public void setRefreshExpirySeconds(long refreshExpirySeconds) {
        this.refreshExpirySeconds = refreshExpirySeconds;
    }
}
