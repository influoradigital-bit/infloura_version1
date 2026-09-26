package com.influora.domain.enums;

/**
 * A planned day's type in a {@link com.influora.domain.entity.CreatorChallenge} (CHALLENGE-SPEC.md,
 * Backend &sect;2). {@code REST} carries no posting window -- {@code CreatorChallengeService} never
 * assigns a window to a {@code REST} day, and the frontend strip renders it "rest" rather than a
 * media type. The other three map to Instagram's own {@code media_metrics.media_type} values the
 * way the spec's "facts already verified" section states: {@code REEL} is satisfied by
 * {@code VIDEO} or {@code REELS}, {@code CAROUSEL} by {@code CAROUSEL_ALBUM}, {@code POST} by
 * {@code IMAGE}.
 */
public enum ChallengeDayType {
    REEL,
    CAROUSEL,
    POST,
    REST
}
