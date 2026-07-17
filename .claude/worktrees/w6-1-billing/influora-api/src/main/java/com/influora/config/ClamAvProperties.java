package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S7 (2026-07-15, Priya approval) — connection settings for the real ClamAV-backed
 * {@code MalwareScanService} ({@code ClamAvMalwareScanService}, {@code @Profile("prod")}). See
 * wiki/tech/approved-deps.md for why this ships as a hand-rolled `clamd` INSTREAM client
 * ({@code ClamAvClient}) on {@code java.net.Socket} rather than a third-party Maven artifact.
 */
@ConfigurationProperties(prefix = "influora.clamav")
public class ClamAvProperties {

    private String host = "localhost";
    private int port = 3310;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
