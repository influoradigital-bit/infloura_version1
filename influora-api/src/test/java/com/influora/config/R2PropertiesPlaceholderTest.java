package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EV-006 -- the R2 placeholders application.yml and influora-api/env.example ship must never count
 * as a configured bucket. Before this, isConfigured() only checked non-blank, so the placeholders
 * made R2StorageService.isAvailable() true and presign returned a URL on a host that does not exist.
 */
class R2PropertiesPlaceholderTest {

    private static R2Properties props(String accountId, String accessKeyId, String secret) {
        R2Properties p = new R2Properties();
        p.setAccountId(accountId);
        p.setAccessKeyId(accessKeyId);
        p.setSecretAccessKey(secret);
        return p;
    }

    @Test
    @DisplayName("the committed REPLACE_WITH_ placeholders are NOT configured")
    void committedPlaceholdersAreNotConfigured() {
        assertFalse(props(
                        "REPLACE_WITH_YOUR_R2_ACCOUNT_ID",
                        "REPLACE_WITH_YOUR_R2_ACCESS_KEY",
                        "REPLACE_WITH_YOUR_R2_SECRET_KEY")
                .isConfigured());
    }

    @Test
    @DisplayName("any single placeholder field keeps R2 unconfigured")
    void onePlaceholderFieldIsEnough() {
        assertFalse(props("acct-0001", "ak-0001", "REPLACE_WITH_YOUR_R2_SECRET_KEY").isConfigured());
        assertFalse(props("acct-0001", "REPLACE_ME", "sk-0001").isConfigured());
        assertFalse(props("REPLACE_WITH_YOUR_R2_ACCOUNT_ID", "ak-0001", "sk-0001").isConfigured());
    }

    @Test
    @DisplayName("blank values are still not configured; real-looking values are")
    void blankAndRealValues() {
        assertFalse(props("", "ak-0001", "sk-0001").isConfigured());
        assertTrue(props("acct-0001", "ak-0001", "sk-0001").isConfigured());
    }
}
