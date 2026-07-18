package com.influora.security;

import java.util.regex.Pattern;

/**
 * Best-effort, cheap regex scrub of high-value secrets/PII out of error-log message + stack strings
 * BEFORE they are persisted to {@code error_log} and later read by a SUPER_ADMIN in the admin
 * console (Kabir M-1). No Java redaction util existed in this module — the Python service has its
 * own backstop at {@code influora-ai/app/security/redaction.py}; this is the Java-side counterpart,
 * deliberately narrower (just the patterns that matter for a stored exception message/stack).
 *
 * <p><b>Contract:</b> null-safe and never throws — a redaction failure must never break the
 * best-effort {@code ErrorLogService} writer that calls it. Cheap regex only; no allocation beyond
 * the scrubbed string.
 */
public final class ErrorLogRedactor {

    private static final String MARK = "[REDACTED]";

    // JWT-like tokens: base64url header starting "eyJ" plus 1-2 dotted segments.
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{5,}(?:\\.[A-Za-z0-9_-]+){1,2}");
    // Authorization "Bearer <token>" — scrub the token, keep the scheme word.
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[A-Za-z0-9._~+/=-]+");
    // Sensitive key=value pairs — keep the key, scrub the value.
    private static final Pattern SECRET_KV =
            Pattern.compile(
                    "(?i)(password|passwd|secret|token|api[_-]?key)\\s*=\\s*[^\\s,;&\"']+");
    // Email addresses.
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // Long digit runs (13-19), card-like PANs / long account numbers.
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{13,19}\\b");

    private ErrorLogRedactor() {}

    /** Scrub secrets/PII from {@code value}; returns {@code null}/empty unchanged, never throws. */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String out = JWT.matcher(value).replaceAll(MARK);
            out = BEARER.matcher(out).replaceAll("$1 " + MARK);
            out = SECRET_KV.matcher(out).replaceAll("$1=" + MARK);
            out = EMAIL.matcher(out).replaceAll(MARK);
            out = LONG_DIGITS.matcher(out).replaceAll(MARK);
            return out;
        } catch (RuntimeException scrubFailure) {
            // Best-effort: never let a redaction failure break the error-log writer.
            return value;
        }
    }
}
