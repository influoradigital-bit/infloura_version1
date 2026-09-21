package com.influora.service.scoring;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.6) — the single follower-count-to-tier mapping.
 *
 * <p>Before this class, three byte-identical copies existed:
 * {@code RateEstimationService.determineTier}, {@code MeeraContextService.deriveTier} (whose own
 * javadoc already flagged itself as a duplicate) and
 * {@code CreatorAgentBaselineService.deriveTier}. All three now delegate here. Because they were
 * identical, that consolidation changed no output: {@code RateEstimationService.estimate()} returns
 * exactly what it did, and {@code RateEstimationServiceTest} is unaffected.
 *
 * <p><b>{@code "MEGA"} is NOT a value of {@code CreatorTier}</b>, whose four constants are
 * {@code NANO, MICRO, MID, MACRO}. Never call {@code CreatorTier.valueOf} on what this returns. Any
 * "MID or above" branch must also treat {@code MEGA} as above {@code MID} — string equality against
 * {@code "MID"} is a bug here, not a shortcut.
 *
 * <p><b>{@code AdminCreatorService.deriveTier} is deliberately NOT merged into this.</b> It is a
 * fourth copy, but a different one: it has no {@code MEGA} branch and collapses everything at or
 * above 500,000 into {@code MACRO}, because the admin frontend's {@code Creator.tier} union has only
 * four buckets. Its javadoc documents that collapse as intentional. Unifying it onto this method
 * would start sending the admin UI a fifth tier string it cannot render.
 */
public final class CreatorTiers {

    public static final String NANO = "NANO";
    public static final String MICRO = "MICRO";
    public static final String MID = "MID";
    public static final String MACRO = "MACRO";

    /** Not a {@code CreatorTier} constant — see the class javadoc. */
    public static final String MEGA = "MEGA";

    private CreatorTiers() {}

    /** Thresholds are inclusive lower bounds, evaluated highest-first. */
    public static String derive(long followers) {
        if (followers >= 1_000_000) return MEGA;
        if (followers >= 500_000) return MACRO;
        if (followers >= 50_000) return MID;
        if (followers >= 10_000) return MICRO;
        return NANO;
    }
}
