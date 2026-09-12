package com.influora.service.rates;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;4.1, B0-30) — the PRICING vocabulary for a package quote,
 * and the reel-equivalent weight of each entry.
 *
 * <p><b>This is deliberately NOT {@link com.influora.domain.enums.DeliverableType}.</b> That enum
 * already exists with a completely different, platform-shaped taxonomy
 * ({@code INSTAGRAM_POST, INSTAGRAM_REEL, ... TIKTOK_VIDEO}); it is persisted on the
 * {@code Deliverable} entity, parsed by {@code ContractService}'s {@code valueOf}, and asserted in
 * a dozen test classes. Adding the pricing values to it would change that parse behaviour and
 * pollute a persisted column, so SPEC.md &sect;4.1 resolved the collision by putting the pricing
 * vocabulary in its own package under its own name. <b>Nothing here is ever persisted</b> — a
 * value of this enum never reaches a {@code deliverables} row, and the wire keeps the type as a
 * free string ({@code DealDtos.DeliverableSlot.type}, {@code BriefDtos.DeliverableLine.type}).
 *
 * <p><b>Why {@link #parse(String)} maps the platform names.</b> Live proposal metadata written by
 * {@code DealService.persistProposalMessage} carries whatever the brand's client sent — in
 * practice the platform names above, not the pricing names. A parse that only understood the
 * pricing names would silently price every real deal at {@link #OTHER}'s neutral weight, which is
 * how a static post ends up costing what a reel costs. The platform mapping is therefore not
 * optional, and the legacy short forms ({@code "reel"}, {@code "story"}, {@code "post"}) are kept
 * because older proposal rows contain them.
 *
 * <p><b>Deviation from SPEC.md &sect;4.1, flagged for review.</b> &sect;4.1 enumerates six platform
 * mappings and leaves the remaining three persisted {@code DeliverableType} constants
 * ({@code FACEBOOK_POST}, {@code FACEBOOK_REEL}, {@code TIKTOK_VIDEO}) to the unknown-to-OTHER
 * rule. Two of those are harmless ({@code OTHER} and {@link #REEL} share weight 1.00), but
 * {@code FACEBOOK_POST} falling to {@code OTHER} would price a static post at 1.00 instead of
 * {@link #STATIC_POST}'s 0.50 — double. They are a real, enumerable platform name rather than an
 * unknown string, so they are mapped here in the spirit of &sect;4.1's own stated reason. Revert
 * these three lines if the spec's literal list is preferred; nothing else depends on them.
 */
public enum QuoteDeliverableType {
    REEL(1.00),
    STATIC_POST(0.50),
    STORY_SET(0.50),
    SHORT(0.70),
    YT_INTEGRATION(1.50),
    YT_DEDICATED(3.00),
    UGC_ONLY(0.60),
    OTHER(1.00);

    /** Reel-equivalents: one unit of this type costs {@code unitWeight} x the reel unit price. */
    public final double unitWeight;

    QuoteDeliverableType(double unitWeight) {
        this.unitWeight = unitWeight;
    }

    /**
     * Case-insensitive, punctuation-tolerant parse. Accepts this enum's own names, the legacy short
     * forms, and the persisted platform names; anything else — including {@code null} and blank —
     * is {@link #OTHER}, never an exception. A quote must not fail because a brand typed
     * "IG reel"; it must price the unknown thing neutrally and say so.
     */
    public static QuoteDeliverableType parse(String raw) {
        if (raw == null) {
            return OTHER;
        }
        // Fold spaces/hyphens/dots into underscores so "story set", "instagram-reel" and
        // "YouTube.Video" all reach the same key as their canonical form.
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (key.isEmpty()) {
            return OTHER;
        }
        return switch (key) {
            // --- this enum's own names ---
            case "REEL" -> REEL;
            case "STATIC_POST" -> STATIC_POST;
            case "STORY_SET" -> STORY_SET;
            case "SHORT" -> SHORT;
            case "YT_INTEGRATION" -> YT_INTEGRATION;
            case "YT_DEDICATED" -> YT_DEDICATED;
            case "UGC_ONLY" -> UGC_ONLY;
            case "OTHER" -> OTHER;
            // --- legacy short forms (SPEC.md 4.1) ---
            case "STORY" -> STORY_SET;
            case "POST" -> STATIC_POST;
            // --- persisted platform taxonomy (com.influora.domain.enums.DeliverableType) ---
            case "INSTAGRAM_REEL" -> REEL;
            case "INSTAGRAM_STORY" -> STORY_SET;
            case "INSTAGRAM_POST", "INSTAGRAM_CAROUSEL" -> STATIC_POST;
            case "YOUTUBE_SHORT" -> SHORT;
            case "YOUTUBE_VIDEO" -> YT_INTEGRATION;
            // --- the three DeliverableType constants 4.1's list omits; see the class javadoc ---
            case "FACEBOOK_REEL", "TIKTOK_VIDEO" -> REEL;
            case "FACEBOOK_POST" -> STATIC_POST;
            default -> OTHER;
        };
    }
}
