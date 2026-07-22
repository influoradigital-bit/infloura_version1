package com.influora.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-language parity fixture test for {@link SensitiveTextRedactor} vs {@code
 * influora-ai/app/security/redaction.py} (Phase 2 item 2.3 — Priya's B5 ruling: BLOCKING for merge,
 * not a nice-to-have, since there is no CI diff-check enforcing regex parity across languages the
 * way {@code schema-check.yml} does for tool/context-field names).
 *
 * <p>Every fixture string and expected-redaction assertion below is copied verbatim from {@code
 * influora-ai/tests/security/test_redaction.py} (same {@code FAKE_PAN}/{@code FAKE_PHONE}/{@code
 * FAKE_BANK_ACCOUNT}/{@code FAKE_EMAIL}/{@code FAKE_BARE_JWT} constants, same input strings) so a
 * silent divergence between the two ports shows up as a failing test on ONE side, not a leaked PII
 * value discovered in production.
 */
class SensitiveTextRedactorTest {

    private static final String FAKE_PAN = "ABCDE1234F";
    private static final String FAKE_PHONE = "+91-9876543210";
    private static final String FAKE_PHONE_PLAIN = "9876543210";
    private static final String FAKE_BANK_ACCOUNT = "123456789012";
    private static final String FAKE_EMAIL = "brand.owner@example.com";
    private static final String FAKE_BARE_JWT =
            "eyJhbGciOiJFUzI1NiIsImtpZCI6InRlc3Qta2lkIn0"
                    + ".eyJzdWIiOiJ1c2VyLTEiLCJzY29wZSI6ImNoYXQ6c3RyZWFtIn0"
                    + ".c29tZS1zaWduYXR1cmUtYnl0ZXMtaGVyZS1sb25nZW5vdWdo";

    @Test
    @DisplayName("removes PAN — mirrors test_rt_pii_1_scrub_text_removes_pan")
    void removesPan() {
        String raw = "The brand's PAN is " + FAKE_PAN + " for KYC purposes.";
        String scrubbed = SensitiveTextRedactor.redact(raw);
        assertFalse(scrubbed.contains(FAKE_PAN));
        assertTrue(scrubbed.contains("[REDACTED_PAN]"));
    }

    @Test
    @DisplayName("removes phone (with separators) — mirrors test_rt_pii_1_scrub_text_removes_phone")
    void removesPhoneWithSeparators() {
        String raw = "Creator phone number: " + FAKE_PHONE + ", please call.";
        String scrubbed = SensitiveTextRedactor.redact(raw);
        assertFalse(scrubbed.contains(FAKE_PHONE));
        assertFalse(scrubbed.contains(FAKE_PHONE_PLAIN));
        assertTrue(scrubbed.contains("[REDACTED_PHONE]"));
    }

    @Test
    @DisplayName("removes plain phone — mirrors test_rt_pii_1_scrub_text_removes_plain_phone")
    void removesPlainPhone() {
        String raw = "Reach the creator at " + FAKE_PHONE_PLAIN + " anytime.";
        String scrubbed = SensitiveTextRedactor.redact(raw);
        assertFalse(scrubbed.contains(FAKE_PHONE_PLAIN));
        assertTrue(scrubbed.contains("[REDACTED_PHONE]"));
    }

    @Test
    @DisplayName(
            "removes bank account — mirrors test_rt_pii_1_scrub_text_removes_bank_account "
                    + "(any [REDACTED_*] tag is fine; zero raw digits leak is the property under test)")
    void removesBankAccount() {
        String raw = "Bank account number on file: " + FAKE_BANK_ACCOUNT + ".";
        String scrubbed = SensitiveTextRedactor.redact(raw);
        assertFalse(scrubbed.contains(FAKE_BANK_ACCOUNT));
        assertTrue(scrubbed.contains("[REDACTED_"));
    }

    @Test
    @DisplayName(
            "removes 9- and 18-digit accounts outside the phone regex's 10-digit window — mirrors "
                    + "test_rt_pii_1_scrub_text_removes_bank_account_not_matched_by_phone_regex")
    void removesBankAccountNotMatchedByPhoneRegex() {
        for (String acct : new String[] {"123456789", "123456789012345678"}) {
            String scrubbed = SensitiveTextRedactor.redact("account on file: " + acct);
            assertFalse(scrubbed.contains(acct));
            assertTrue(scrubbed.contains("[REDACTED_ACCOUNT]"));
        }
    }

    @Test
    @DisplayName("removes email — mirrors test_rt_pii_1_scrub_text_removes_email")
    void removesEmail() {
        String raw = "Contact the brand owner at " + FAKE_EMAIL + " for approval.";
        String scrubbed = SensitiveTextRedactor.redact(raw);
        assertFalse(scrubbed.contains(FAKE_EMAIL));
        assertTrue(scrubbed.contains("[REDACTED_EMAIL]"));
    }

    @Test
    @DisplayName(
            "removes PAN + bank + phone + email combined in one line — mirrors "
                    + "test_rt_pii_1_scrub_text_removes_all_combined_in_one_line")
    void removesAllCombinedInOneLine() {
        String raw =
                "Brand PAN "
                        + FAKE_PAN
                        + ", bank account "
                        + FAKE_BANK_ACCOUNT
                        + ", creator phone "
                        + FAKE_PHONE
                        + ", contact "
                        + FAKE_EMAIL
                        + ".";
        String scrubbed = SensitiveTextRedactor.redact(raw);
        assertFalse(scrubbed.contains(FAKE_PAN));
        assertFalse(scrubbed.contains(FAKE_BANK_ACCOUNT));
        assertFalse(scrubbed.contains(FAKE_PHONE));
        assertFalse(scrubbed.contains(FAKE_PHONE_PLAIN));
        assertFalse(scrubbed.contains(FAKE_EMAIL));
    }

    @Test
    @DisplayName(
            "removes a bare (no Bearer prefix) three-segment JWT — mirrors "
                    + "test_kabir_fix2_scrub_text_removes_bare_jwt_without_bearer_prefix")
    void removesBareJwtWithoutBearerPrefix() {
        String raw = "forwarding onbehalf_jwt=" + FAKE_BARE_JWT + " to spring";
        String scrubbed = SensitiveTextRedactor.redact(raw);
        assertFalse(scrubbed.contains(FAKE_BARE_JWT));
        assertTrue(scrubbed.contains("[REDACTED_SECRET]"));
    }

    @Test
    @DisplayName(
            "does not over-match ordinary short dotted strings — mirrors "
                    + "test_kabir_fix2_scrub_text_does_not_over_match_ordinary_dotted_strings")
    void doesNotOverMatchOrdinaryDottedStrings() {
        String raw = "app running v1.2.3 on api.example.com, build 20260714.01.beta";
        assertEquals(raw, SensitiveTextRedactor.redact(raw));
    }

    @Test
    @DisplayName("null/empty input passes through unchanged")
    void nullAndEmptyPassThrough() {
        assertEquals(null, SensitiveTextRedactor.redact(null));
        assertEquals("", SensitiveTextRedactor.redact(""));
    }
}
