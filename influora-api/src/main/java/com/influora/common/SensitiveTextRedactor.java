package com.influora.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of {@code influora-ai/app/security/redaction.py}'s regex backstop (Phase 2 item 2.3 —
 * Meera: Label-to-Moat build plan §2.3; B3/B5 gate items from {@code
 * wiki/build/phase2-priya-review.md} / {@code phase2-ash-review.md}). {@code redaction.py} is a
 * Python logging formatter and cannot be called from this module's Java write points ({@code
 * BrandDeliverableService}, {@code ConfirmLaunchExecutor}) — this class ports ONLY the regex
 * patterns + {@code scrub_text}'s exact application order, not the logging-formatter machinery,
 * which is Python-specific.
 *
 * <p><b>Order is load-bearing</b> (mirrors {@code redaction.py::scrub_text} exactly): secret →
 * bare-JWT → PAN → email → phone → bank-account. {@link #BANK_ACCOUNT_RE} ({@code \d{9,18}}) is
 * greedy and will consume phone/secret digit runs if it runs first — do not reorder.
 *
 * <p><b>SR-2 forward-invariant (Ash's required backend change B4):</b> this class is a PII/secret
 * backstop, NOT a prompt-injection defense — injection is {@code _safe()}/{@code
 * wrap_untrusted()}'s job on the Python side. Storing {@code revision_reason} redacted by this
 * class is safe today ONLY because {@code meera_interaction_log} is write-only (no read query
 * exists in this pass — see {@code MeeraInteractionLogRepository}) and this text never re-enters a
 * prompt. <b>If any flywheel free-text is EVER surfaced back into a prompt</b> (few-shot mining, a
 * "past revisions" digest, eval seeding), it MUST additionally pass through Python's {@code
 * _safe()}/{@code wrap_untrusted()} before it does — redaction here is necessary, not sufficient,
 * for SR-2.
 *
 * <p><b>Cross-language sync discipline:</b> kept in explicit lockstep with {@code
 * app/security/redaction.py} by comment cross-reference — there is no CI diff-check enforcing
 * regex parity across languages the way {@code schema-check.yml} does for tool/context-field
 * names. Priya ruled the cross-language parity test (same fixture strings, same expected
 * redactions, run in both suites) BLOCKING for merge of this slice, not a nice-to-have.
 */
public final class SensitiveTextRedactor {

    private SensitiveTextRedactor() {}

    /** Indian PAN: 5 letters, 4 digits, 1 letter (e.g. ABCDE1234F). Mirrors {@code redaction.py::_PAN_RE}. */
    private static final Pattern PAN_RE = Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");

    /** Phone numbers: optional +country code, 10 digits, allow separators. Mirrors {@code _PHONE_RE}. */
    private static final Pattern PHONE_RE =
            Pattern.compile("(?<!\\d)(?:\\+?\\d{1,3}[-.\\s]?)?\\d{10}(?!\\d)");

    /** Bank account / IBAN-ish: 9-18 digit runs. Mirrors {@code _BANK_ACCOUNT_RE}. */
    private static final Pattern BANK_ACCOUNT_RE = Pattern.compile("\\b\\d{9,18}\\b");

    /** Generic secret-looking tokens: sk-..., Bearer <jwt>, long base64/hex blobs. Mirrors {@code _SECRET_RE}. */
    private static final Pattern SECRET_RE =
            Pattern.compile(
                    "(?:sk-[A-Za-z0-9_-]{10,}|Bearer\\s+[A-Za-z0-9\\-_.]{20,}|[A-Za-z0-9+/]{40,}={0,2})");

    /** Bare three-segment JWT (no "Bearer " prefix). Mirrors {@code _JWT_SEGMENT_RE}. */
    private static final Pattern JWT_SEGMENT_RE =
            Pattern.compile("\\b([A-Za-z0-9_-]{8,})\\.([A-Za-z0-9_-]{8,})\\.([A-Za-z0-9_-]{8,})\\b");

    private static final Pattern EMAIL_RE =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    /**
     * Redacts secret/bare-JWT/PAN/email/phone/bank-account patterns, in {@code
     * redaction.py::scrub_text}'s exact order. {@code null}/empty input is returned unchanged.
     */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String value = input;
        value = SECRET_RE.matcher(value).replaceAll("[REDACTED_SECRET]");
        value = scrubBareJwts(value);
        value = PAN_RE.matcher(value).replaceAll("[REDACTED_PAN]");
        value = EMAIL_RE.matcher(value).replaceAll("[REDACTED_EMAIL]");
        value = PHONE_RE.matcher(value).replaceAll("[REDACTED_PHONE]");
        value = BANK_ACCOUNT_RE.matcher(value).replaceAll("[REDACTED_ACCOUNT]");
        return value;
    }

    /**
     * Mirrors {@code redaction.py::_scrub_bare_jwts} exactly — only redacts a dot-separated
     * three-segment match when at least ONE of the three segments is &gt;=20 chars, so ordinary
     * short dotted strings (version numbers, hostnames, "a.b.c"-shaped identifiers) never match;
     * real JWT payload segments are comfortably longer than 20 chars even for a minimal claim set.
     */
    private static String scrubBareJwts(String value) {
        Matcher matcher = JWT_SEGMENT_RE.matcher(value);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            boolean anySegmentLong = false;
            for (int group = 1; group <= 3; group++) {
                if (matcher.group(group).length() >= 20) {
                    anySegmentLong = true;
                    break;
                }
            }
            result.append(value, lastEnd, matcher.start());
            result.append(anySegmentLong ? "[REDACTED_SECRET]" : matcher.group(0));
            lastEnd = matcher.end();
        }
        result.append(value.substring(lastEnd));
        return result.toString();
    }
}
