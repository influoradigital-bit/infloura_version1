package com.influora.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PHONE-0904 sign-off Q3 gap — {@code IndianPhoneUtils} is the single source of truth for what a
 * valid Indian mobile number is across brand signup ({@code AuthService}), creator onboarding
 * ({@code CreatorOnboardingService}) and creator settings ({@code CreatorProfileService}), and
 * until this file existed it had zero direct unit tests: its behaviour was only exercised
 * incidentally through those three service test classes, and 5 of the 10 inputs the sign-off
 * review probed with a throwaway harness (see {@code wiki/reports/phone-0904-signoff-qa.md} Q3)
 * were locked in by no test at all. This file pins the documented contract in {@link
 * IndianPhoneUtils}'s own javadoc — normalize/validate independently, exactly like {@code
 * UsernameUtils}'s normalize/isValid pair — so a future edit here cannot silently change what
 * "valid" means on any of the three signup surfaces without a red test.
 *
 * <p>The frontend mirror is {@code src/lib/phone.ts} ({@code normalizePhone}/{@code
 * isValidPhone}); every case below was checked to normalize and validate identically on both
 * sides before being pinned here (see PHONE-0904 sign-off review).
 */
class IndianPhoneUtilsTest {

    // ------------------------------------------------------------------------------------------
    // normalize(): null/blank in -> null out
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("normalize: null input returns null")
    void normalizeNull() {
        assertNull(IndianPhoneUtils.normalize(null));
    }

    @Test
    @DisplayName("normalize: empty string returns null")
    void normalizeEmpty() {
        assertNull(IndianPhoneUtils.normalize(""));
    }

    @Test
    @DisplayName("normalize: whitespace-only string returns null")
    void normalizeWhitespaceOnly() {
        assertNull(IndianPhoneUtils.normalize("   "));
    }

    // ------------------------------------------------------------------------------------------
    // isValid(): null in -> false, never throws
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("isValid: null normalized value is rejected, not an NPE")
    void isValidNull() {
        assertFalse(IndianPhoneUtils.isValid(null));
    }

    // ------------------------------------------------------------------------------------------
    // The 10 exact inputs from the PHONE-0904 sign-off Q3 probe table, each asserting BOTH the
    // normalized output and isValid() on that output.
    // ------------------------------------------------------------------------------------------

    static Stream<Arguments> signoffProbeInputs() {
        return Stream.of(
                Arguments.of("plain 10-digit, valid leading digit", "9876543210", "9876543210", true),
                Arguments.of("+91 with spaces", "+91 98765 43210", "9876543210", true),
                Arguments.of("+91 no spaces", "+919876543210", "9876543210", true),
                Arguments.of("leading trunk 0", "09876543210", "9876543210", true),
                Arguments.of("91 with hyphen separator", "91-9876543210", "9876543210", true),
                Arguments.of(
                        "leading trunk 0 with trailing space", "098765 43210 ", "9876543210", true),
                Arguments.of(
                        "Devanagari digits collapse to no digits at all (ASCII-only char class)",
                        "+९८७६५४३२१०",
                        "",
                        false),
                Arguments.of(
                        "embedded letters/extension pull in extra digits",
                        "9876543210x123",
                        "9876543210123",
                        false),
                Arguments.of("invalid leading digit 5", "5876543210", "5876543210", false),
                Arguments.of(
                        "12 digits NOT prefixed with 91 stays 12 digits",
                        "987654321012",
                        "987654321012",
                        false));
    }

    @ParameterizedTest(name = "[{index}] {0}: \"{1}\" -> normalized \"{2}\", valid={3}")
    @MethodSource("signoffProbeInputs")
    @DisplayName("normalize()+isValid() match the PHONE-0904 sign-off Q3 probe table exactly")
    void matchesSignoffProbeTable(
            String caseName, String rawInput, String expectedNormalized, boolean expectedValid) {
        String normalized = IndianPhoneUtils.normalize(rawInput);
        assertEquals(expectedNormalized, normalized, "normalize(\"" + rawInput + "\")");
        assertEquals(expectedValid, IndianPhoneUtils.isValid(normalized), "isValid() for case: " + caseName);
    }

    /**
     * Standalone duplicate of the Devanagari row in {@link #signoffProbeInputs()}, kept as its
     * own {@code @Test} (not just a row in the parameterized table) so the ASCII-only-char-class
     * reasoning is documented right next to a directly-runnable assertion.
     */
    @Test
    @DisplayName("normalize: a '+' followed only by Devanagari digits strips to empty, not to the Devanagari value")
    void normalizeDevanagariDigitsOnly() {
        // Devanagari digits (U+0966-U+096F) are not ASCII 0-9; IndianPhoneUtils.normalize's
        // replaceAll("[^0-9]", "") is an ASCII-only character class, so they are treated as
        // non-digits and stripped along with the '+', leaving an empty (non-null) string.
        String devanagariPhone = "+९८७६५४३२१०";
        String normalized = IndianPhoneUtils.normalize(devanagariPhone);
        assertEquals("", normalized);
        assertFalse(IndianPhoneUtils.isValid(normalized));
    }

    // ------------------------------------------------------------------------------------------
    // Boundary cases the implementation actually branches on.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("normalize: 9 digits is left as-is (too short to be a strip candidate) and rejected")
    void nineDigitsTooShort() {
        String normalized = IndianPhoneUtils.normalize("987654321");
        assertEquals("987654321", normalized);
        assertFalse(IndianPhoneUtils.isValid(normalized));
    }

    @Test
    @DisplayName(
            "normalize: 11 digits NOT starting with 0 is left as-is (trunk-0 strip does not fire) and"
                    + " rejected")
    void elevenDigitsNotStartingWithZero() {
        String normalized = IndianPhoneUtils.normalize("19876543210");
        assertEquals("19876543210", normalized);
        assertFalse(IndianPhoneUtils.isValid(normalized));
    }

    @Test
    @DisplayName("normalize: 12 digits starting with 91 strips the prefix to a valid 10-digit number")
    void twelveDigitsStartingWith91() {
        String normalized = IndianPhoneUtils.normalize("919876543210");
        assertEquals("9876543210", normalized);
        assertTrue(IndianPhoneUtils.isValid(normalized));
    }

    @Test
    @DisplayName("normalize: 13+ digits is left as-is (no strip rule matches) and rejected")
    void thirteenPlusDigits() {
        String normalized = IndianPhoneUtils.normalize("1234567890123");
        assertEquals("1234567890123", normalized);
        assertFalse(IndianPhoneUtils.isValid(normalized));
    }

    // ------------------------------------------------------------------------------------------
    // Every leading digit 0-9 on an otherwise-valid 10-digit number: 6/7/8/9 accepted, 0-5
    // rejected. No strip rule fires here (already exactly 10 digits), so this isolates isValid's
    // own [6-9] boundary from the normalize() length/prefix branches above.
    // ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "leading digit {0}: 10-digit number is accepted")
    @ValueSource(chars = {'6', '7', '8', '9'})
    @DisplayName("isValid: leading digits 6-9 are accepted")
    void validLeadingDigitsAccepted(char leadingDigit) {
        String normalized = IndianPhoneUtils.normalize(leadingDigit + "876543210");
        assertEquals(leadingDigit + "876543210", normalized);
        assertTrue(IndianPhoneUtils.isValid(normalized), "leading digit " + leadingDigit + " should be valid");
    }

    @ParameterizedTest(name = "leading digit {0}: 10-digit number is rejected")
    @ValueSource(chars = {'0', '1', '2', '3', '4', '5'})
    @DisplayName("isValid: leading digits 0-5 are rejected")
    void invalidLeadingDigitsRejected(char leadingDigit) {
        String normalized = IndianPhoneUtils.normalize(leadingDigit + "876543210");
        assertEquals(leadingDigit + "876543210", normalized);
        assertFalse(IndianPhoneUtils.isValid(normalized), "leading digit " + leadingDigit + " should be invalid");
    }
}
