package com.influora.service.meera;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The plain-text version of one photo check, stored as the ASSISTANT row's {@code content} so
 * later chat turns can see what the check found (the client replays {@code m.text} of every row
 * through the 20-message / 16,000-character history window).
 *
 * <p>Built ONLY from influora-ai's frame-check JSON -- every text key in it is written by code from
 * ids (influora-ai {@code frame_check.py}), never the model's free text -- plus the shot label.
 * The label is the one piece of creator/model-written text in here, so it is sanitised and put on
 * its own {@code Shot: "..."} line: no newline, control character or bracket in it can forge a
 * header or a {@code Steps:} line (Ash correction 4). {@code shot_context} and {@code answers}
 * never reach this class.
 *
 * <p>Shape (English headings; the check's own lines stay in the check's language):
 *
 * <pre>
 * [Photo check]
 * Shot: "0-3s · Close-up on your face"
 * Photo check saw: I can see you're in a bedroom, ...
 * Steps:
 * 1) Window behind you: Change where the phone points ...
 * 2) Talking head by a window: Lens: 1x Main. Distance: 0.8-1m. ...
 * Looking good: The framing suits this kind of shot. The background is clean ...
 * Can't tell from one photo: A photo can't tell me how your audio sounds. ...
 * I asked: Can you move to a different spot for this shot? (Yes, I can move / No, fixed spot)
 * </pre>
 *
 * <p>Size (Ash correction 2): a step's text is NEVER cut -- the settings step carries the camera
 * values Meera must quote back, and a cut mid-value ("Stabilization: Su") makes her refill it from
 * her own knowledge and contradict the card. When the whole text is over {@link #MAX_CHARS}, the
 * optional lines are dropped in this order: "Looking good" items, then "Can't tell" items, then the
 * question's options, then the question. If the header, what the check saw and the steps alone are
 * over the cap, the text is returned over the cap rather than cut.
 *
 * <p>{@code <} and {@code >} are written as "under" / "over" (Ash correction 14): the replay path
 * turns every {@code <} into {@code &lt;}, which Meera would otherwise echo ("ISO &lt;800").
 */
public final class PhotoCheckSummary {

    /** Soft cap for the whole text; see the class javadoc for what is trimmed and what never is. */
    public static final int MAX_CHARS = 1500;

    /** Same cut as {@code CreatorMeeraController}'s shot label. */
    public static final int MAX_LABEL_CHARS = 120;

    /** The first line of every stored photo-check row. The persona keys on it. */
    public static final String HEADER = "[Photo check]";

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern LESS_THAN = Pattern.compile("\\s*<\\s*");
    private static final Pattern GREATER_THAN = Pattern.compile("\\s*>\\s*");
    private static final Pattern OPEN_PAREN_SPACE = Pattern.compile("\\(\\s+");

    private PhotoCheckSummary() {}

    /**
     * The shot label as it may appear anywhere the model or the card reads it: every control or
     * line-separator character becomes a space, {@code [} and {@code ]} are dropped, whitespace
     * runs collapse, and the result is cut to {@link #MAX_LABEL_CHARS} code points. {@code null}
     * when nothing is left.
     */
    public static String sanitizeLabel(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        raw.codePoints()
                .forEach(
                        cp -> {
                            if (cp == '[' || cp == ']') {
                                return;
                            }
                            // Zero-width joiners (Character.FORMAT) are kept on purpose: Hindi
                            // conjuncts and emoji need them, and they cannot break a line.
                            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0x0085) {
                                out.append(' ');
                                return;
                            }
                            out.appendCodePoint(cp);
                        });
        String collapsed = WHITESPACE_RUN.matcher(out).replaceAll(" ").strip();
        if (collapsed.isEmpty()) {
            return null;
        }
        if (collapsed.codePointCount(0, collapsed.length()) > MAX_LABEL_CHARS) {
            collapsed = collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_LABEL_CHARS)).strip();
        }
        return collapsed;
    }

    /**
     * Renders {@code result} (influora-ai's frame-check body, with or without its {@code fallback}
     * key) for the conversation. {@code shotLabel} may be raw; it is sanitised here.
     */
    public static String render(JsonNode result, String shotLabel) {
        String label = sanitizeLabel(shotLabel);
        String lang = "hi".equals(text(result, "lang")) ? "hi" : "en";

        List<String> head = new ArrayList<>();
        head.add(HEADER);
        if (label != null) {
            head.add("Shot: \"" + clean(label) + "\"");
        }
        String seen = clean(text(result, "what_i_see"));

        if (result != null && result.path("retake").asBoolean(false)) {
            head.add("Couldn't judge the photo: " + (seen.isEmpty() ? "the photo was not usable." : seen));
            head.add("Asked for a retake.");
            return String.join("\n", head);
        }
        if (!seen.isEmpty()) {
            head.add("Photo check saw: " + seen);
        }
        List<String> steps = steps(result);
        if (!steps.isEmpty()) {
            head.add("Steps:");
            for (int i = 0; i < steps.size(); i++) {
                head.add((i + 1) + ") " + steps.get(i));
            }
        }

        List<String> ok = textList(result, "ok");
        List<String> cantTell = textList(result, "cant_tell");
        JsonNode ask = result == null ? null : result.get("ask");
        String question = ask == null || !ask.isObject() ? "" : clean(questionText(ask, lang));
        List<String> options = question.isEmpty() ? new ArrayList<>() : optionTexts(ask, lang);
        boolean keepQuestion = !question.isEmpty();

        // Trim order (Ash correction 2): ok, then cant_tell, then the ask's options, then the ask.
        while (true) {
            String candidate = assemble(head, ok, cantTell, keepQuestion ? question : null, options);
            if (candidate.length() <= MAX_CHARS) {
                return candidate;
            }
            if (!ok.isEmpty()) {
                ok.remove(ok.size() - 1);
            } else if (!cantTell.isEmpty()) {
                cantTell.remove(cantTell.size() - 1);
            } else if (!options.isEmpty()) {
                options.clear();
            } else if (keepQuestion) {
                keepQuestion = false;
            } else {
                // Header, what the check saw and the steps only: never cut (see class javadoc).
                return candidate;
            }
        }
    }

    private static String assemble(
            List<String> head, List<String> ok, List<String> cantTell, String question, List<String> options) {
        List<String> lines = new ArrayList<>(head);
        if (!ok.isEmpty()) {
            lines.add("Looking good: " + joinItems(ok));
        }
        if (!cantTell.isEmpty()) {
            lines.add("Can't tell from one photo: " + joinItems(cantTell));
        }
        if (question != null) {
            lines.add("I asked: " + question + (options.isEmpty() ? "" : " (" + String.join(" / ", options) + ")"));
        }
        return String.join("\n", lines);
    }

    /** Sentences join with a space; anything without end punctuation gets "; " after it. */
    private static String joinItems(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                out.append(last == '.' || last == '!' || last == '?' ? " " : "; ");
            }
            out.append(item);
        }
        return out.toString();
    }

    /** One line per step, "label: text"; falls back to the flat fixes/settings lists of an older body. */
    private static List<String> steps(JsonNode result) {
        List<String> out = new ArrayList<>();
        JsonNode steps = result == null ? null : result.get("steps");
        if (steps != null && steps.isArray() && !steps.isEmpty()) {
            for (JsonNode step : steps) {
                String body = clean(text(step, "text"));
                if (body.isEmpty()) {
                    continue;
                }
                String name = clean(text(step, "label"));
                if (name.isEmpty()) {
                    name = clean(text(step, "note"));
                }
                out.add(name.isEmpty() ? body : name + ": " + body);
            }
            return out;
        }
        out.addAll(textList(result, "fixes"));
        out.addAll(textList(result, "settings"));
        return out;
    }

    private static String questionText(JsonNode ask, String lang) {
        String preferred = text(ask, "question_" + lang);
        return preferred.isBlank() ? text(ask, "question_en") : preferred;
    }

    private static List<String> optionTexts(JsonNode ask, String lang) {
        List<String> out = new ArrayList<>();
        JsonNode options = ask.get("options");
        if (options == null || !options.isArray()) {
            return out;
        }
        for (JsonNode option : options) {
            String value = option.isTextual() ? option.asText() : text(option, lang);
            if (value.isBlank() && !option.isTextual()) {
                value = text(option, "en");
            }
            value = clean(value);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<String> textList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode list = node == null ? null : node.get(field);
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode item : list) {
            if (item.isTextual()) {
                String value = clean(item.asText());
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    /**
     * Server text on one line: control characters and line breaks become spaces, brackets that
     * could open a fake header are softened, {@code <}/{@code >} become words.
     */
    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints()
                .forEach(
                        cp -> {
                            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp == 0x0085) {
                                out.append(' ');
                            } else if (cp == '[') {
                                out.append('(');
                            } else if (cp == ']') {
                                out.append(')');
                            } else {
                                out.appendCodePoint(cp);
                            }
                        });
        String words = LESS_THAN.matcher(out).replaceAll(" under ");
        words = GREATER_THAN.matcher(words).replaceAll(" over ");
        words = OPEN_PAREN_SPACE.matcher(words).replaceAll("(");
        return WHITESPACE_RUN.matcher(words).replaceAll(" ").strip();
    }
}
