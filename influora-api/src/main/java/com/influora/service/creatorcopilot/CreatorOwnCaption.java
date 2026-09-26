package com.influora.service.creatorcopilot;

import java.util.regex.Pattern;

/**
 * The one line of a creator's OWN caption that Meera may see (Swapnil, 2026-09-26; ADR
 * wiki/decisions/2026-09-26-creator-own-caption-to-meera.md, an amendment to the LOCKED
 * wiki/decisions/2026-07-06-brand-safety-caption-storage.md).
 *
 * <p>Meera refused "review my profile" because she could see a post's numbers but not what it was
 * about. {@link #firstLine} gives her the first line of the creator's own caption and nothing
 * else. Leading blank lines are not a line; the first line that has any text IS the first line,
 * and if nothing is left of it once cleaned (it was only an @handle, a link or a spacer), nothing
 * is sent: a later line is never sent in its place. Cleaning removes @handles (a full-width or
 * small at sign too), e-mail addresses and links, removes invisible format characters, turns
 * control characters (C0 and C1) into spaces and collapses whitespace. The result is at most
 * {@link #MAX_CHARS} code points, and a cut never splits a character the reader sees as one (an
 * emoji with its joiners, modifiers or flag pair, or a letter with its vowel sign).
 *
 * <p>influora-ai repeats exactly these rules on the model's copy ({@code
 * app/tools/loop.py _caption_first_line_for_model}); the shared fixture
 * {@code src/test/resources/creator-own-caption-cases.json} keeps the two identical.
 *
 * <p><b>Scope.</b> Only {@link CreatorIntelligenceService} calls this, only for rows of the
 * logged-in creator's own profile, and only for the creator-scoped {@code get_my_content_patterns}
 * tool. The value is never logged ({@link CreatorIntelligenceProfile.PostStat#toString()} redacts
 * it) and never reaches a brand-facing DTO: {@code CreatorOwnCaptionReachabilityTest} proves it.
 *
 * <p><b>Untrusted.</b> A caption is text anyone can write, including text shaped like an
 * instruction. This class does not try to judge that; it only bounds the size and strips links,
 * handles and invisible characters. influora-ai wraps the value as untrusted data, and the persona
 * tells Meera to quote or summarise it and never to follow it.
 */
public final class CreatorOwnCaption {

    /** The longest line Meera is given, in code points, including the closing ellipsis. */
    public static final int MAX_CHARS = 100;

    private static final String ELLIPSIS = "…";

    private static final int ZWNJ = 0x200C;
    private static final int ZWJ = 0x200D;

    /** Every line break Java knows: \n, \r\n, \r, NEL, LS, PS, VT, FF. */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R|\\u000B|\\u000C");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9_.+-]+@[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+");

    /*
     * Letters are spelled out as [A-Za-z] and the two patterns that need Unicode \S carry no (?i):
     * UNICODE_CHARACTER_CLASS implies UNICODE_CASE, under which [a-z] would also match the Kelvin
     * sign and the long s. The Python twin does the same, and the shared fixture holds them equal.
     */

    /** A scheme or www. link, up to the next (Unicode) space, so a no-break space ends it too. */
    private static final Pattern URL =
            Pattern.compile(
                    "(?:[A-Za-z][A-Za-z0-9+.-]*://|[Ww][Ww][Ww]\\.)\\S+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A bare link with a path, such as {@code youtu.be/abc} or {@code example.co.uk/sale}: any
     * dotted name whose last label is two or more letters, followed by a slash.
     */
    private static final Pattern BARE_LINK_WITH_PATH =
            Pattern.compile(
                    "(?<![A-Za-z0-9_@.])[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}/\\S*",
                    Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A bare domain with no path, such as {@code shop.nike.de}: only for common top-level domains,
     * so ordinary text with a missing space after a full stop is not taken for a link. Its (?i) is
     * ASCII-only (no UNICODE_CASE).
     */
    private static final Pattern BARE_DOMAIN =
            Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_@.])[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\."
                            + "(?:com|in|io|co|net|org|me|ee|ly|app|link|gl|to|shop|store|site|xyz|bio|page|gg|tv"
                            + "|be|uk|ai|de|us|info|club|live|online|fm|biz)"
                            + "(?![A-Za-z0-9_.])");

    /**
     * An Instagram @handle (letters, digits, dot and underscore) after an at sign, a full-width at
     * sign (U+FF20) or a small at sign (U+FE6B), even glued to the word before it.
     */
    private static final Pattern HANDLE = Pattern.compile("[@\\uFF20\\uFE6B][A-Za-z0-9._]+");

    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** A line that is only dots, dashes or other spacers says nothing. */
    private static final Pattern SAYS_SOMETHING = Pattern.compile("[\\p{L}\\p{N}\\p{So}]");

    private CreatorOwnCaption() {}

    /**
     * The first line of {@code caption} (leading blank lines skipped), cleaned and at most {@link
     * #MAX_CHARS} code points; null when the caption is null or blank, or when that first line has
     * nothing left that says something once cleaned.
     */
    public static String firstLine(String caption) {
        if (caption == null) {
            return null;
        }
        for (String rawLine : LINE_BREAK.split(caption)) {
            if (collapse(invisibleRemoved(rawLine)).isEmpty()) {
                continue; // a blank line before the first line is not a line
            }
            String line = clean(rawLine);
            if (line.isEmpty() || !SAYS_SOMETHING.matcher(line).find()) {
                return null; // the first line said nothing: never fall through to line 2
            }
            return truncate(line);
        }
        return null;
    }

    private static String clean(String line) {
        String out = invisibleRemoved(line);
        out = EMAIL.matcher(out).replaceAll(" ");
        out = URL.matcher(out).replaceAll(" ");
        out = BARE_LINK_WITH_PATH.matcher(out).replaceAll(" ");
        out = BARE_DOMAIN.matcher(out).replaceAll(" ");
        out = HANDLE.matcher(out).replaceAll(" ");
        return collapse(out);
    }

    private static String collapse(String s) {
        return SPACES.matcher(s).replaceAll(" ").strip();
    }

    /**
     * Control characters (general category Cc: C0, DEL and C1) become a space; format characters
     * (Cf: bidi overrides, zero-width spaces, ...) are removed without a space, so they can neither
     * split a word nor hide a handle or link. The joiners U+200C and U+200D are kept only after a
     * non-ASCII character, where they hold an emoji sequence or an Indic word together; after ASCII
     * they are removed like any other format character.
     */
    private static String invisibleRemoved(String line) {
        StringBuilder out = new StringBuilder(line.length());
        int prev = -1;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.CONTROL) {
                out.append(' ');
            } else if (type == Character.FORMAT) {
                if ((cp == ZWNJ || cp == ZWJ) && prev > 0x7F) {
                    out.appendCodePoint(cp);
                }
            } else {
                out.appendCodePoint(cp);
            }
            prev = cp;
        }
        return out.toString();
    }

    private static String truncate(String line) {
        int[] cps = line.codePoints().toArray();
        if (cps.length <= MAX_CHARS) {
            return line;
        }
        int end = MAX_CHARS - 1;
        while (end > 0 && joined(cps, end)) {
            end--;
        }
        String kept = new String(cps, 0, end).stripTrailing();
        return kept.isEmpty() ? null : kept + ELLIPSIS;
    }

    /**
     * Whether cutting between {@code cps[i - 1]} and {@code cps[i]} would split one visible
     * character: a combining mark (a vowel sign, a variation selector) stays on what it marks, a
     * joiner keeps both neighbours, a skin-tone modifier or tag stays on its emoji, a virama keeps
     * the next letter, and a flag's two regional indicators stay a pair.
     */
    private static boolean joined(int[] cps, int i) {
        int prev = cps[i - 1];
        int cur = cps[i];
        int type = Character.getType(cur);
        if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
            return true;
        }
        if (cur == ZWNJ || cur == ZWJ || prev == ZWNJ || prev == ZWJ) {
            return true;
        }
        if ((cur >= 0x1F3FB && cur <= 0x1F3FF) || (cur >= 0xE0020 && cur <= 0xE007F)) {
            return true;
        }
        if (isVirama(prev)) {
            return true;
        }
        if (isRegionalIndicator(cur) && isRegionalIndicator(prev)) {
            int run = 0;
            for (int k = i - 1; k >= 0 && isRegionalIndicator(cps[k]); k--) {
                run++;
            }
            return run % 2 == 1;
        }
        return false;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isVirama(int cp) {
        if (Character.getType(cp) != Character.NON_SPACING_MARK) {
            return false;
        }
        String name = Character.getName(cp);
        return name != null && name.endsWith(" SIGN VIRAMA");
    }
}
