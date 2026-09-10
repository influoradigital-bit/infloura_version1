package com.influora.service.meera.tool.creator;

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
 * <p>Only the accessors with live callers exist here. The rest of SPEC.md &sect;3.6's helper
 * surface ({@code string} for a required value, {@code list}) arrives with the executors that need
 * it, so this class never carries a method no route reaches.
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
}
