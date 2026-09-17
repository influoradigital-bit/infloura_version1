package com.influora.integration.meta.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * F-0870 — safe-to-log fingerprints of Meta credentials, for diagnosing a connect failure without
 * ever writing a usable token or secret to the log.
 *
 * <p><b>Why this exists.</b> The Instagram long-lived exchange fails in production with
 * {@code 400 code 100 "Unsupported request - method type: get"} although the request matches
 * Meta's documentation exactly and the code exchange before it yields a non-blank token. The
 * remaining suspects are all properties of the VALUES, which nothing logs: the token is not the
 * type graph.instagram.com accepts, or the Instagram app secret is the wrong app's, or either
 * carries stray whitespace (a trailing {@code \r} from an env file edited on Windows would survive
 * {@code urlEncode} as {@code %0D} and silently corrupt the request).
 *
 * <p><b>What is safe to print.</b>
 *
 * <ul>
 *   <li>A token's first {@value #TOKEN_PREFIX} characters are a TYPE marker, not entropy —
 *       {@code IGAA} (Instagram Business Login), {@code IGQV} (legacy Basic Display), {@code EAA}
 *       (Facebook). Suppressed for values of {@value #MIN_LENGTH_FOR_PREFIX} characters or fewer,
 *       where four characters would be a meaningful fraction of the value.
 *   <li>A secret is never printed in any part — only the first 8 hex characters of its SHA-256,
 *       which cannot be reversed, but can be compared against the hash of the value shown in the
 *       Meta dashboard to answer "is this the right app's secret".
 *   <li>Lengths and whitespace flags, which are exactly what a pasted-with-CRLF value gets wrong.
 * </ul>
 */
public final class MetaDiagnostics {

    static final int TOKEN_PREFIX = 4;
    static final int MIN_LENGTH_FOR_PREFIX = 12;

    private MetaDiagnostics() {}

    /** e.g. {@code IGAA...(len=183)}, {@code IGAA...(len=184,whitespace)}, {@code null}. */
    public static String tokenFingerprint(String token) {
        if (token == null) {
            return "null";
        }
        if (token.isBlank()) {
            return "blank(len=" + token.length() + ")";
        }
        String prefix =
                token.length() > MIN_LENGTH_FOR_PREFIX ? token.substring(0, TOKEN_PREFIX) : "?";
        return prefix + "...(len=" + token.length() + flags(token) + ")";
    }

    /** e.g. {@code sha256:1a2b3c4d(len=32)} — never any character of the secret itself. */
    public static String secretFingerprint(String secret) {
        if (secret == null) {
            return "null";
        }
        if (secret.isBlank()) {
            return "blank(len=" + secret.length() + ")";
        }
        return "sha256:" + sha256Prefix(secret) + "(len=" + secret.length() + flags(secret) + ")";
    }

    private static String flags(String value) {
        StringBuilder flags = new StringBuilder();
        if (!value.strip().equals(value) || value.chars().anyMatch(Character::isWhitespace)) {
            flags.append(",whitespace");
        }
        if (value.chars().anyMatch(c -> c > 0x7E || c < 0x20)) {
            flags.append(",non-printable-or-non-ascii");
        }
        return flags.toString();
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to ship SHA-256; if it is somehow absent, say so rather than
            // fall back to printing anything derived more weakly from the secret.
            return "unavailable";
        }
    }
}
