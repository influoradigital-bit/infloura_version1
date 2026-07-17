package com.influora.integration.clamav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.integration.clamav.ClamAvClient.ScanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S7 — pure parsing tests for the hand-rolled INSTREAM reply format
 * (`stream: OK` / `stream: <sig> FOUND` / anything else). No socket involved; a live clamd smoke
 * test is deferred to Meera's build verification.
 */
class ClamAvClientTest {

    @Test
    @DisplayName("'stream: OK' parses as clean")
    void testCleanResponse() {
        ScanResult result = ScanResult.fromClamdResponse("stream: OK");
        assertTrue(result.clean());
        assertFalse(result.infected());
    }

    @Test
    @DisplayName("'stream: Eicar-Test-Signature FOUND' parses as infected")
    void testInfectedResponse() {
        ScanResult result = ScanResult.fromClamdResponse("stream: Eicar-Test-Signature FOUND");
        assertFalse(result.clean());
        assertTrue(result.infected());
    }

    @Test
    @DisplayName("an empty/unrecognized response is neither clean nor infected (fail-closed upstream)")
    void testUnrecognizedResponse() {
        ScanResult empty = ScanResult.fromClamdResponse("");
        assertFalse(empty.clean());
        assertFalse(empty.infected());

        ScanResult garbage = ScanResult.fromClamdResponse("ERROR: something went wrong");
        assertFalse(garbage.clean());
        assertFalse(garbage.infected());
    }
}
