package com.influora.web.dto.admin;

/**
 * DTOs for {@link com.influora.web.AdminMarketingController} — the admin marketing/analytics
 * console. Mounted under {@code /admin/marketing/*}.
 *
 * <p><b>Scope note (law 5 / honesty):</b> reputation and growth ship here; every field on both is a
 * real aggregate over live tables, never fabricated. {@code /acquisition} (CAC + source
 * attribution) and {@code /referrals} remain deliberately NOT implemented — there is no ad-spend
 * and no signup-source attribution anywhere in the schema for acquisition, and no {@code Referral}
 * entity/table for referrals. {@code /growth} ships a smaller shape than {@code GrowthMetrics} in
 * {@code src/admin/types/admin.types.ts}: {@code funnel.profileComplete}, {@code cohortRetention},
 * and {@code referralStats} are all omitted for the same reason — no profile-completion flag, no
 * retention/activity-event tracking, and no {@code Referral} table exist to back them. See {@link
 * GrowthMetricsDto} for exactly what growth does serve and how each field is derived.
 */
public final class AdminMarketingDtos {

    private AdminMarketingDtos() {}

    /**
     * {@code GET /admin/marketing/reputation} — platform reputation snapshot. Mirrors {@code
     * PlatformReputationScore} in {@code src/admin/types/admin.types.ts}. Every field is a live
     * aggregate; nothing fabricated:
     *
     * <ul>
     *   <li>{@code overall} — average stars across all non-hidden reviews (both directions), 0–5.
     *   <li>{@code creatorQualityAvg} — average stars from {@code BRAND} reviewers (brands rating
     *       creators).
     *   <li>{@code brandSatisfactionAvg} — average stars from {@code CREATOR} reviewers (creators
     *       rating brands).
     *   <li>{@code disputeResolutionSpeed} — average {@code resolved_at − created_at} across every
     *       resolved dispute, in HOURS (repository returns seconds; service divides by 3600).
     *   <li>{@code calculatedAt} — ISO-8601 instant the snapshot was computed.
     * </ul>
     *
     * <p>Any aggregate with no underlying rows is returned as {@code 0.0} (the repository returns
     * {@code null}; the service coalesces) — an honest "no data yet", not an invented figure.
     */
    public record PlatformReputationScoreDto(
            double overall,
            double creatorQualityAvg,
            double brandSatisfactionAvg,
            double disputeResolutionSpeed,
            String calculatedAt) {}

    /**
     * {@code GET /admin/marketing/growth} — the honest subset of {@code GrowthMetrics} in {@code
     * src/admin/types/admin.types.ts} that has real backing data. Every field is a live aggregate;
     * nothing fabricated, nothing defaulted to a fake non-zero value:
     *
     * <ul>
     *   <li>{@code funnel.signups} — total registered {@code User} rows (both brand and creator
     *       accounts) — the honest platform-wide definition of "signups" ({@code
     *       UserRepository.count()}).
     *   <li>{@code funnel.firstCampaign} — distinct BRAND-type workspaces with at least one
     *       campaign ({@code CampaignRepository.countBrandWorkspacesWithCampaign()}).
     *   <li>{@code funnel.repeatCampaign} — BRAND-type workspaces with 2 or more campaigns ({@code
     *       CampaignRepository.countBrandWorkspacesWithRepeatCampaigns()}).
     *   <li>{@code conversionRates.creatorApplicationToApproval} — {@code CreatorProfile} rows with
     *       {@code applicationStatus = APPROVED} divided by all {@code CreatorProfile} rows (every
     *       creator profile carries an application status, so the full table is the honest
     *       denominator). {@code 0.0} when there are zero creator profiles — never a fabricated
     *       ratio.
     *   <li>{@code conversionRates.brandSignupToFirstCampaign} — {@code funnel.firstCampaign}
     *       divided by total BRAND-type workspaces ({@code WorkspaceRepository.countByType(BRAND)}).
     *       {@code 0.0} when there are zero brand workspaces.
     * </ul>
     *
     * <p>Deliberately omitted (no backing data — see class javadoc): {@code funnel.profileComplete},
     * {@code cohortRetention}, {@code referralStats}.
     */
    public record GrowthMetricsDto(
            FunnelDto funnel, ConversionRatesDto conversionRates, String calculatedAt) {}

    /** See {@link GrowthMetricsDto} for exact field derivation. */
    public record FunnelDto(long signups, long firstCampaign, long repeatCampaign) {}

    /** See {@link GrowthMetricsDto} for exact field derivation. */
    public record ConversionRatesDto(
            double creatorApplicationToApproval, double brandSignupToFirstCampaign) {}
}
