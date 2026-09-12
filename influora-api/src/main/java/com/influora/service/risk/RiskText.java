package com.influora.service.risk;

import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;5.2) — the string plumbing the fourteen rules share:
 * null-safe regex matching, case/punctuation-insensitive list membership, and the ordered
 * {@code data} map every {@link RiskFlag} carries.
 *
 * <p>Deliberately holds no rule-specific pattern. Each rule owns its own regex, next to the
 * behaviour it drives, because a shared pattern constant is a pattern nobody dares change.
 */
public final class RiskText {

    /**
     * U+2248, the character SPEC.md &sect;3.5 mandates for {@code RiskFlag.cost} ("&asymp; 6,000 of
     * lost income"), written as a Unicode escape rather than the literal glyph.
     *
     * <p><b>Why the escape.</b> Java processes {@code \}{@code u} escapes before the lexer sees the
     * file, so this constant is identical whatever encoding the source is read with. A literal
     * glyph is only correct while every tool in the chain agrees the file is UTF-8, and a build
     * that silently mojibakes one character in a creator-facing string is not a build that fails
     * loudly.
     */
    public static final String APPROX = "\u2248";

    /** U+2019 RIGHT SINGLE QUOTATION MARK — what word processors and WhatsApp write for an apostrophe. */
    private static final char CURLY_APOSTROPHE = '\u2019';

    /** U+02BC MODIFIER LETTER APOSTROPHE — the other one, produced by some mobile keyboards. */
    private static final char MODIFIER_APOSTROPHE = '\u02bc';

    private RiskText() {}

    /**
     * Lower-cased, trimmed, with curly apostrophes folded to straight ones.
     *
     * <p>The apostrophe fold is load-bearing for {@code HIDE_DISCLOSURE}: a brand pasting
     * {@code don't} from any rich-text source produces {@link #CURLY_APOSTROPHE}, which the spec's
     * {@code don'?t} alternative does not match. Without the fold the rule reads as working — its
     * firing test passes on typed text — and silently misses the real messages.
     */
    public static String norm(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace(CURLY_APOSTROPHE, '\'')
                .replace(MODIFIER_APOSTROPHE, '\'')
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * True when {@code pattern} finds anything in {@code text}; false for null/blank text.
     *
     * <p>Matches against {@link #norm}'s output, so every rule pattern may be written in lower
     * case and still match SHOUTED text.
     */
    public static boolean matches(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return pattern.matcher(norm(text)).find();
    }

    /** Case-insensitive, trimmed membership — how SPEC.md &sect;5.2 compares brands and categories. */
    public static boolean containsNormalised(List<String> haystack, String needle) {
        if (haystack == null || haystack.isEmpty() || blank(needle)) {
            return false;
        }
        String target = norm(needle);
        for (String candidate : haystack) {
            String normalised = norm(candidate);
            if (!normalised.isEmpty() && normalised.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Null/blank-safe emptiness check, so a rule never fires on a whitespace-only brand name. */
    public static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The {@code data} map of a {@link RiskFlag}: INSERTION-ORDERED, rendered strings only (SPEC.md
     * &sect;3.5), and silently dropping any key whose value is null so a rule can pass an optional
     * figure through without an {@code if}.
     *
     * <p>{@link Collections#unmodifiableMap} over a {@link LinkedHashMap}, not {@code Map.copyOf} —
     * the latter is explicitly unordered, and these maps are read by a language model, where
     * "value then floor" and "floor then value" are not the same sentence.
     *
     * @param keyValues alternating key, value; an odd length is a programming error and throws
     */
    public static Map<String, String> data(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("data() takes alternating key/value pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
