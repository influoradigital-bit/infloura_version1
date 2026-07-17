package com.influora.integration.ai;

/**
 * Wraps any transport/auth/parsing failure talking to influora-ai's {@code POST /analyze-site}
 * endpoint, or a non-2xx/malformed response from it (H-23). Deliberately a single unchecked
 * exception type, mirroring {@link BrandSafetyAiException} — {@code AnalyzeSiteTriggerService}
 * catches this ONE type and marks the {@code BrandProfile} row {@code FAILED} rather than letting
 * an influora-ai outage propagate anywhere near the request thread that triggered onboarding.
 */
public class AnalyzeSiteAiException extends RuntimeException {
    public AnalyzeSiteAiException(String message) {
        super(message);
    }

    public AnalyzeSiteAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
