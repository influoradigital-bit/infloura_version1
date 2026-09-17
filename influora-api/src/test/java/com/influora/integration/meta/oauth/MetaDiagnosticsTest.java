package com.influora.integration.meta.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0870 — the fingerprints go into production logs, so the property that matters most is what
 * they must NEVER contain. Every test below asserts on leakage as well as on format.
 */
class MetaDiagnosticsTest {

    private static final String TOKEN = "IGAAQ1abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("F-0870: a token prints its 4-char type marker and length, nothing more")
    void tokenShowsOnlyTypeMarkerAndLength() {
        String fp = MetaDiagnostics.tokenFingerprint(TOKEN);

        assertEquals("IGAA...(len=" + TOKEN.length() + ")", fp);
        assertFalse(fp.contains(TOKEN.substring(4, 10)), "no character past the type marker: " + fp);
    }

    @Test
    @DisplayName("F-0870: a short value prints no prefix, since 4 chars would be most of it")
    void shortTokenPrintsNoPrefix() {
        String fp = MetaDiagnostics.tokenFingerprint("IGAAshort");

        assertEquals("?...(len=9)", fp);
    }

    @Test
    @DisplayName("F-0870: a trailing carriage return is flagged — the CRLF-pasted env value case")
    void trailingCarriageReturnIsFlagged() {
        assertTrue(MetaDiagnostics.tokenFingerprint(TOKEN + "\r").contains("whitespace"));
        assertTrue(MetaDiagnostics.secretFingerprint(SECRET + "\r").contains("whitespace"));
        assertFalse(MetaDiagnostics.secretFingerprint(SECRET).contains("whitespace"));
    }

    @Test
    @DisplayName("F-0870: null and blank are named, not hashed")
    void nullAndBlankAreNamed() {
        assertEquals("null", MetaDiagnostics.tokenFingerprint(null));
        assertEquals("blank(len=2)", MetaDiagnostics.tokenFingerprint("  "));
        assertEquals("null", MetaDiagnostics.secretFingerprint(null));
    }

    @Test
    @DisplayName("F-0870: a secret prints a hash prefix and length, and no character of the secret")
    void secretNeverPrintsItself() {
        String fp = MetaDiagnostics.secretFingerprint(SECRET);

        assertTrue(fp.matches("sha256:[0-9a-f]{8}\\(len=32\\)"), fp);
        assertFalse(fp.contains(SECRET.substring(0, 4)), "no prefix of the secret: " + fp);
    }

    @Test
    @DisplayName("F-0870: equal secrets hash equal and different secrets differ, so they can be compared")
    void secretFingerprintsAreComparable() {
        assertEquals(
                MetaDiagnostics.secretFingerprint(SECRET), MetaDiagnostics.secretFingerprint(SECRET));
        assertNotEquals(
                MetaDiagnostics.secretFingerprint(SECRET),
                MetaDiagnostics.secretFingerprint(SECRET.replace('f', 'e')));
    }
}
