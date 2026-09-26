package com.influora.domain.enums;

/**
 * Meera intelligence v1, slice 2 (spec 8.2) -- where a {@code creator_recommendations} row came
 * from. {@code name()} is the stored and wire value.
 */
public enum CreatorRecommendationSource {
    /** A line of Meera's 7-line week plan, resolved against the same turn's plan_my_week days. */
    PLAN_MY_WEEK,
    /** A non-REST day of the 7-day challenge, recorded at start. */
    CHALLENGE,
    /** A script card's Plan line; no date, matched within 7 days of the recommendation. */
    SCRIPT_CARD
}
