package com.influora.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Kabir M-K6-C2-2 — complexity + common-password denylist. */
class PasswordPolicyTest {

    @Test
    @DisplayName("accepts a strong password with upper, lower, and digit")
    void acceptsStrongPassword() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Supersecret1"));
    }

    @Test
    @DisplayName("rejects all-lowercase even when long enough")
    void rejectsAllLowercase() {
        ApiException ex = assertThrows(ApiException.class, () -> PasswordPolicy.validate("alllowercase1"));
        assertEquals("WEAK_PASSWORD", ex.getCode());
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    @DisplayName("rejects common password password1 even with mixed case")
    void rejectsCommonPassword1() {
        ApiException ex = assertThrows(ApiException.class, () -> PasswordPolicy.validate("Password1"));
        assertEquals("WEAK_PASSWORD", ex.getCode());
        assertTrue(ex.getMessage().toLowerCase().contains("common"));
    }

    @Test
    @DisplayName("denylist is loaded from classpath resource")
    void denylistLoaded() {
        assertTrue(PasswordPolicy.denylistSize() > 1000);
    }
}
