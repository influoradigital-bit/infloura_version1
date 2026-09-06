package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "influora.jwt")
public class JwtProperties {

    private String accessSecret;
    private String refreshSecret;
    private long accessExpirySeconds = 900;

    /** F-0551 — refresh-token lifetime for a "remember me" login. 30 days (unchanged default). */
    private long refreshExpirySeconds = 2_592_000;

    /**
     * F-0551 — refresh-token lifetime for a NOT-remembered login. 24 hours: long enough that a
     * normal single-day working session (the access token itself re-mints every 15 min off this
     * refresh token) never forces a surprise re-login mid-task, short enough that a lost/shared/
     * public device's session is dead again within one calendar day rather than persisting for
     * the full 30-day "remembered" window the caller explicitly did not ask for.
     */
    private long refreshExpiryNotRememberedSeconds = 86_400;

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

    public long getRefreshExpiryNotRememberedSeconds() {
        return refreshExpiryNotRememberedSeconds;
    }

    public void setRefreshExpiryNotRememberedSeconds(long refreshExpiryNotRememberedSeconds) {
        this.refreshExpiryNotRememberedSeconds = refreshExpiryNotRememberedSeconds;
    }
}
