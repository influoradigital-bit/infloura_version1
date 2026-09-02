package com.influora.common;

import java.util.regex.Pattern;

/**
 * PHONE-0829 — shared normalization/validation for the Indian-mobile rule the frontend already
 * enforces at {@code src/components/brand/onboarding/onboarding-steps.tsx:465}
 * ({@code /^[6-9]\d{9}$/}: exactly 10 digits, first digit 6-9). Used by both creator phone capture
 * ({@code CreatorProfileService}) and the brand onboarding phone field ({@code OnboardingService})
 * so the two call sites can never drift on what "valid" means — same precedent as {@link
 * UsernameUtils}'s normalize/isValid pair.
 *
 * <p>Deliberately narrower than {@code WorkspaceService#isValidPhone} (that one accepts loose
 * international numbers, 7-15 digits, for the general brand-settings phone field backed by {@code
 * workspaces.phone}). This class is specifically the strict India-only rule product asked for here.
 */
public final class IndianPhoneUtils {

    private static final Pattern VALID = Pattern.compile("^[6-9]\\d{9}$");

    private IndianPhoneUtils() {}

    /**
     * Strips everything but digits, then drops a leading {@code 91} country code (12 digits ->
     * 10) or a single leading trunk {@code 0} (11 digits -> 10) — the exact three noise patterns
     * called out in the brief: spaces, {@code +91}, and a leading {@code 0}. Does NOT itself
     * validate the result is a real 10-digit Indian mobile number — callers must check {@link
     * #isValid} on the return value before persisting. Returns null for null/blank input (a
     * blank/absent phone, same "null means no value" convention {@code Workspace#updatePhone}
     * already uses).
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    /** Exactly 10 digits, first digit 6-9 — matches the frontend's {@code /^[6-9]\d{9}$/} rule. */
    public static boolean isValid(String normalized) {
        return normalized != null && VALID.matcher(normalized).matches();
    }
}
