package com.influora.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared plain-text sanitizer for user-supplied free-text fields (Task #22 / Kabir M-2 + M-9-1).
 * Strips HTML tags (including {@code script}/{@code style} blocks) before persistence so stored
 * values are safe for downstream text rendering without relying on client-side escaping alone.
 */
public final class TextSanitizer {

    private static final Pattern SCRIPT_OR_STYLE_BLOCK =
            Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

    private TextSanitizer() {}

    /**
     * Returns {@code null} for {@code null} input; otherwise strips HTML and trims outer whitespace.
     */
    public static String sanitizePlainText(String input) {
        if (input == null) {
            return null;
        }
        String cleaned = decodeBasicEntities(input);
        cleaned = SCRIPT_OR_STYLE_BLOCK.matcher(cleaned).replaceAll("");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll("");
        return cleaned.strip();
    }

    /** Sanitizes each hashtag; drops null/blank entries after cleaning. */
    public static List<String> sanitizeHashtags(List<String> hashtags) {
        if (hashtags == null) {
            return null;
        }
        List<String> result = new ArrayList<>(hashtags.size());
        for (String tag : hashtags) {
            if (tag == null) {
                continue;
            }
            String cleaned = sanitizePlainText(tag);
            if (cleaned != null && !cleaned.isBlank()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static String decodeBasicEntities(String value) {
        return value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
