package com.influora.common;

import java.math.BigDecimal;

/**
 * Shared score-derivation helpers for the {@code creator_scores} read models (BR-18, Priya ruling
 * 2026-07-30 — see TECH-STACK.md "Score Exposure").
 *
 * <p>{@code CreatorScore.fakeFollowerScore} is 0-100 where <b>higher = more suspicious</b> (see
 * that entity's javadoc / {@code FakeFollowerDetectionService}). "Authenticity" means the
 * opposite: higher = more genuine. Every brand-facing surface that reports an authenticity value
 * (discovery's {@code DiscoveryDtos.CreatorScores}, analytics' {@code
 * CreatorScoresResponse.authenticityScore}) must derive it the same way via {@link
 * #toAuthenticity} instead of passing the raw suspicion score straight through under a field name
 * that means the inverse of what it holds.
 */
public final class CreatorScoreMath {

    private CreatorScoreMath() {}

    /**
     * authenticity = 100 - fakeFollowerScore, clamped to {@code [0, 100]}. Null in, null out — an
     * unscored creator has no authenticity value to report; never fabricate one (0 or 100 both
     * lie about a creator we simply haven't scored yet).
     */
    public static BigDecimal toAuthenticity(BigDecimal fakeFollowerScore) {
        if (fakeFollowerScore == null) {
            return null;
        }
        return BigDecimal.valueOf(100)
                .subtract(fakeFollowerScore)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(100));
    }
}
