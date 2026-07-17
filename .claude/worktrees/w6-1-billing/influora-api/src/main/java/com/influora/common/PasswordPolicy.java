package com.influora.common;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

/**
 * Shared password complexity + common-password denylist (spec §1.1 / Kabir M-K6-C2-2). Used by
 * brand/creator register and password-reset — one validator, not per-endpoint copies.
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;
    private static final Pattern HAS_UPPER = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");

    private static final Set<String> COMMON_PASSWORDS = loadCommonPasswords();

    private PasswordPolicy() {}

    /**
     * Validates {@code password} against complexity and the common-password denylist. Throws
     * {@link ApiException} {@code WEAK_PASSWORD} (400) on failure.
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw weak("Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!HAS_UPPER.matcher(password).find()
                || !HAS_LOWER.matcher(password).find()
                || !HAS_DIGIT.matcher(password).find()) {
            throw weak("Password must include uppercase, lowercase, and a number");
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw weak("Password is too common — choose a stronger one");
        }
    }

    private static ApiException weak(String message) {
        return new ApiException("WEAK_PASSWORD", message, HttpStatus.BAD_REQUEST);
    }

    private static Set<String> loadCommonPasswords() {
        try {
            ClassPathResource resource = new ClassPathResource("common-passwords.txt");
            if (!resource.exists()) {
                return Set.of();
            }
            Set<String> set = new HashSet<>();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return Collections.unmodifiableSet(set);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load common-passwords.txt", e);
        }
    }

    /** Test/introspection helper — size of the loaded denylist. */
    static int denylistSize() {
        return COMMON_PASSWORDS.size();
    }
}
