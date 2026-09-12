package com.influora.service.meera.tool.creator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.6) — reads the {@code Map<String, Object>} a creator tool
 * executor is handed.
 *
 * <p>That map is raw, model-proposed JSON with {@code snake_case} keys, merged with
 * {@code workspace_id} by influora-ai's tool loop. It is not validated by Jackson into a record, so
 * every read has to survive an absent key, a null, a number where a string was asked for, and a
 * string where a number was asked for — all of which a language model will eventually emit. These
 * helpers make the tolerant reading explicit in one place instead of once per executor.
 *
 * <p>Only the accessors with live callers exist here — SPEC.md &sect;3.6's {@code string} (a
 * REQUIRED value that throws when absent) still has none, because every creator input wired so far
 * is optional or defaulted, so this class carries no method a route cannot reach.
 */
final class ToolInput {

    private ToolInput() {}

    /**
     * @return the trimmed value, or null when the key is absent, null, or blank. Blank collapses to
     *     null deliberately: {@code {"status": ""}} means the model did not choose a status, and a
     *     caller comparing it against {@code "active"} should fall to its default rather than match
     *     nothing.
     */
    static String optionalString(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Reads an integer, clamped into {@code [min, max]}.
     *
     * <p>Accepts a JSON number (which Jackson hands over as {@link Number}) and a numeric string,
     * because a model asked for {@code limit} emits {@code 10} and {@code "10"} interchangeably.
     * Anything else — a word, a list, a null — falls back to {@code defaultValue} rather than
     * throwing: a malformed optional argument must not turn a read tool into an error the model has
     * to narrate.
     *
     * <p>Clamping rather than rejecting is the same judgement: {@code limit: 5000} is a model
     * over-reaching, not an attack, and the right answer is the capped page it was going to get
     * anyway.
     */
    static int intOr(Map<String, Object> input, String key, int defaultValue, int min, int max) {
        int resolved = defaultValue;
        Object value = input == null ? null : input.get(key);
        if (value instanceof Number number) {
            resolved = number.intValue();
        } else if (value != null) {
            try {
                resolved = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                resolved = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, resolved));
    }

    /**
     * SPEC.md &sect;3.6's {@code list(key)} for an array of objects — {@code deliverables} is the
     * only shape any creator tool takes.
     *
     * <p>Jackson hands a JSON array over as {@code List<LinkedHashMap>}, so the element type is
     * unchecked by construction; entries that are not objects are skipped rather than throwing,
     * because a model that emitted {@code ["REEL", "REEL"]} instead of
     * {@code [{"type": "REEL", "qty": 2}]} has made a shape mistake the caller can price around,
     * not an error worth spending the creator's turn on.
     *
     * @return never null; an empty list for an absent key, a null, or a non-array value
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                items.add((Map<String, Object>) map);
            }
        }
        return items;
    }

    /**
     * An array of strings — {@code add_ons} is the only one. Nulls and blanks are dropped and every
     * entry is trimmed, so {@code ["PERPETUITY", "", null]} is one code, not three.
     *
     * @return never null; an empty list for an absent key, a null, or a non-array value
     */
    static List<String> stringList(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (Object item : raw) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isEmpty()) {
                items.add(text);
            }
        }
        return items;
    }

    /**
     * Reads a money figure.
     *
     * <p>{@link BigDecimal#valueOf(double)} rather than {@code new BigDecimal(double)} for the
     * {@link Number} branch: the latter carries the binary representation's full error into the
     * decimal ({@code 8000.1} becomes {@code 8000.099999999999...}), which then renders back to the
     * creator as a figure she did not type. A string goes straight through the exact constructor.
     *
     * @return null for an absent key, a null, a blank, or anything that is not a number — never a
     *     zero standing in for "the model said nothing", which a budget check would read as an
     *     offer of nothing
     */
    static BigDecimal decimal(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
