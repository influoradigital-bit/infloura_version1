package com.influora.common;

import java.util.Locale;
import java.util.regex.Pattern;

public final class UsernameUtils {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9_]{1,28}[a-z0-9]$|^[a-z0-9]{3}$");

    private UsernameUtils() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        String normalized = SlugUtils.slugify(raw).replace('-', '_');
        if (normalized.length() < 3) {
            normalized = normalized + "_cr";
        }
        return normalized.substring(0, Math.min(normalized.length(), 30));
    }

    public static boolean isValid(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return VALID.matcher(username.toLowerCase(Locale.ROOT)).matches();
    }
}
