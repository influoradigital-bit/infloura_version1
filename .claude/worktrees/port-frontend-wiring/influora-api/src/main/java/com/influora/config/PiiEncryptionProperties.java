package com.influora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AES-256-GCM keys for Spec 12 §3.1 PII (Kabir M-K6-C3-1 / M-K6-C3-2). Distinct from OAuth / MFA /
 * webhook keys — never shared.
 */
@ConfigurationProperties(prefix = "influora.pii")
public class PiiEncryptionProperties {

    /** Email + phone at-rest encryption (blind-index migration in progress). */
    private String emailPhoneEncryptionKey = "";

    /** Bank account / UPI encrypt-before-ship gate — required before any bank row is persisted. */
    private String bankEncryptionKey = "";

    public String getEmailPhoneEncryptionKey() {
        return emailPhoneEncryptionKey;
    }

    public void setEmailPhoneEncryptionKey(String emailPhoneEncryptionKey) {
        this.emailPhoneEncryptionKey = emailPhoneEncryptionKey;
    }

    public String getBankEncryptionKey() {
        return bankEncryptionKey;
    }

    public void setBankEncryptionKey(String bankEncryptionKey) {
        this.bankEncryptionKey = bankEncryptionKey;
    }
}
