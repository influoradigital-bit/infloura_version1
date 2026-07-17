package com.influora.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SlugUtils {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    private SlugUtils() {}

    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "workspace";
        }
        String nowhitespace = WHITESPACE.matcher(input.trim()).replaceAll("-");
        String normalized =
                Normalizer.normalize(nowhitespace, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "");
        String slug = NON_LATIN.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
        slug = slug.replaceAll("-+", "-");
        if (slug.startsWith("-")) slug = slug.substring(1);
        if (slug.endsWith("-")) slug = slug.substring(0, slug.length() - 1);
        return slug.isBlank() ? "workspace" : slug.substring(0, Math.min(slug.length(), 80));
    }
}
