package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.service.verification.DeliverableVerificationService.Outcome;
import org.junit.jupiter.api.Test;

/**
 * verified-analytics-0804 — the manual self-report escape hatch opens ONLY on a genuine Meta-API
 * failure for a connected account (or an unsupported platform). It must never open for a real
 * verified result, nor for the not-connected / bad-URL cases (those are "Connect Instagram" /
 * "fix your post URL", not "type it yourself"). This pins the full {@link Outcome} → boolean map.
 */
class CreatorDeliverableServiceManualFallbackTest {

    @Test
    void manualAllowedOnlyForGenuineMetaApiFailures() {
        assertTrue(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_TOKEN_EXPIRED));
        assertTrue(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_RATE_LIMITED));
        assertTrue(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_API_ERROR));
        assertTrue(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_NOT_FOUND));
        assertTrue(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_DATA_INTEGRITY));
        assertTrue(
                CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_YOUTUBE_UNSUPPORTED));
    }

    @Test
    void manualBlockedForVerifiedNotConnectedAndBadUrl() {
        assertFalse(CreatorDeliverableService.manualFallbackAllowed(Outcome.VERIFIED));
        assertFalse(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_NO_TOKEN));
        assertFalse(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_NO_POST_URL));
        assertFalse(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_NO_MILESTONE));
        assertFalse(CreatorDeliverableService.manualFallbackAllowed(Outcome.FALLBACK_UNRECOGNIZED_URL));
    }

    @Test
    void everyOutcomeValueIsClassifiedWithoutThrowing() {
        for (Outcome o : Outcome.values()) {
            CreatorDeliverableService.manualFallbackAllowed(o);
        }
    }
}
