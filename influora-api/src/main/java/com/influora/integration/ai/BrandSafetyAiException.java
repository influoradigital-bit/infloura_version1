package com.influora.integration.ai;

/**
 * Wraps any transport/auth/parsing failure talking to influora-ai's {@code
 * /internal/brand-safety} endpoint, or a non-2xx/malformed response from it. Deliberately a
 * single unchecked exception type (mirrors {@code RazorpayIntegrationException}) — {@code
 * BrandSafetyScoreService} catches this ONE type to implement graceful degradation (Wave C task
 * C3 acceptance criterion: influora-ai being unreachable must never fail the whole {@code
 * ScoreCalculationJob} run or block other creators).
 */
public class BrandSafetyAiException extends RuntimeException {
    public BrandSafetyAiException(String message) {
        super(message);
    }

    public BrandSafetyAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
