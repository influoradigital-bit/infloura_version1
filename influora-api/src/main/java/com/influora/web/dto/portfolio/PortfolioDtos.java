package com.influora.web.dto.portfolio;

import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class PortfolioDtos {

    private PortfolioDtos() {}

    public record PortfolioVisibility(
            boolean trustBar,
            boolean badges,
            boolean platformStats,
            boolean pastCollabs,
            boolean contentPortfolio,
            boolean customLinks,
            String rateCard,
            boolean languages,
            boolean contactForm) {

        public static PortfolioVisibility defaults() {
            return new PortfolioVisibility(
                    true, true, true, true, true, true, "brands_only", true, true);
        }
    }

    /**
     * [F-0589] {@code onTimeRate} is NULLABLE and must stay nullable. It is a public,
     * brand-visible trust number, and it is only a true statement about a creator when we
     * actually hold the evidence to compute it: a deliverable is measurable for timeliness only
     * when it carries BOTH a {@code deadline} and a {@code submittedAt}. When a creator has no
     * measurable deliverable at all, {@code onTimeRate} is {@code null} ("we cannot say") rather
     * than {@code 100} (which inflated every creator with missing deadline data — the defect this
     * field shape closes) or {@code 0} (which would blame the creator for OUR missing data).
     *
     * <p>{@code onTimeSampleSize} is the denominator the rate was actually measured over — the
     * number of completed collaborations that had at least one measurable deliverable. It is
     * always present, always {@code >= 0}, and is {@code 0} exactly when {@code onTimeRate} is
     * {@code null}; it lets a client distinguish "100% of one deliverable" from "100% of forty"
     * without re-deriving anything.
     *
     * <p>Consumers must render {@code null} as an explicit "no data" ("—"), never as a number —
     * the same discipline {@code brand-creator-profile.tsx}'s {@code onTimeDelivery: number |
     * null} already applies for the discovery-side metric.
     */
    public record PortfolioStats(
            int totalCollabs,
            double avgRating,
            Integer onTimeRate,
            int onTimeSampleSize,
            int repeatBrands) {}

    public record PortfolioCollab(
            String id,
            String brandId,
            String brandName,
            String brandLogoUrl,
            String campaignTitle,
            String deliverables,
            String platform,
            String completedAt,
            Double rating,
            String publicQuote,
            String displayMode) {}

    public record PortfolioPinnedPost(
            String id,
            String platform,
            String embedUrl,
            String thumbnailUrl,
            String caption,
            Long views,
            Long likes) {}

    public record PortfolioCustomLink(
            String id, String label, String url, String icon, Long clicks) {}

    public record PortfolioRateRow(
            String id, String label, BigDecimal min, BigDecimal max, String currency) {}

    /**
     * The slice of a creator's portfolio a signed-in brand is served, assembled by
     * {@code PortfolioService#getForBrand} under {@code ViewerMode.BRAND} and nested into
     * {@code DiscoveryDtos.CreatorPublicProfileResponse} as ONE field (F-0972/F-0974).
     *
     * <p>Nested rather than flattened on purpose. Every visibility rule these fields obey
     * lives in {@code PortfolioService#assemble}; spreading them across the brand record as
     * loose columns is what produced two projections of one creator that disagreed about
     * whether the portfolio existed. A brand-only concern (saved, scores, discoverable,
     * completedCampaigns, followersSource) stays on the outer record and must NOT migrate
     * here -- the portfolio has no concept of a viewing workspace.
     *
     * <p>{@code stats} is nullable, matching {@code PortfolioPageResponse}: null means the
     * creator hid their trust bar, never zero (F-0589).
     */
    public record PortfolioBrandView(
            List<String> badges,
            List<PortfolioCollab> pastCollabs,
            List<PortfolioPinnedPost> contentPortfolio,
            List<PortfolioCustomLink> customLinks,
            List<PortfolioRateRow> rateCard,
            PortfolioStats stats,
            List<String> topAudienceCities,
            String coverUrl) {}

    public record PortfolioPageResponse(
            String username,
            String displayName,
            String bio,
            String city,
            List<String> niches,
            String avatarUrl,
            String coverUrl,
            boolean verified,
            /**
             * F-0972 -- NULLABLE. Null means the creator switched their trust bar off and
             * these numbers were withheld; it is never a zeroed PortfolioStats, because a
             * published 0 collabs / 0.0 rating reads as measured fact (F-0589).
             */
            PortfolioStats stats,
            List<String> badges,
            List<PlatformStatResponse> platforms,
            List<PortfolioCollab> collabs,
            List<PortfolioPinnedPost> pinnedPosts,
            List<PortfolioCustomLink> customLinks,
            List<PortfolioRateRow> rateCard,
            List<String> languages,
            List<String> topAudienceCities,
            PortfolioVisibility visibility) {}

    public record PortfolioAnalyticsResponse(
            PageViews pageViews,
            long profileClicks,
            /**
             * CR-71 — {@code profileClicks} above is {@code totalFollowers / 100}, a rough proxy,
             * not a real click measurement (no click-tracking event exists for this metric today,
             * unlike {@code pageViews}/{@code mediaKitDownloads}, which are real {@code
             * portfolio_events} counts). This flag lets the client label it honestly instead of
             * presenting it as measured. Always {@code true} until real tracking is built.
             */
            boolean profileClicksEstimated,
            List<LinkClick> linkClicks,
            long brandInquiries,
            long mediaKitDownloads) {

        public record PageViews(long last30Days, int deltaPercent) {}

        public record LinkClick(String linkId, String label, long clicks) {}

        public static PortfolioAnalyticsResponse empty() {
            return new PortfolioAnalyticsResponse(
                    new PageViews(0, 0), 0, true, List.of(), 0, 0);
        }
    }

    /**
     * F-0976 -- carried a {@code String captchaToken} until 2026-09-20 whose only
     * occurrence in the whole repository was its own declaration. No verifier ever
     * existed, so it read to anyone auditing this endpoint as though a captcha gate were
     * in place, and it contradicted {@code PortfolioService#contact}'s own javadoc,
     * which reasons explicitly that throttling alone is this endpoint's control.
     * Removed rather than implemented: a control that is documented but absent is worse
     * than one that is honestly missing. Reinstate it only alongside a real verifier.
     */
    public record PortfolioContactRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 2000) String message) {}

    public record PortfolioContactResponse(boolean delivered) {}

    public record SyncPlatformsResponse(String syncedAt) {}

    /**
     * F-0694/F-0695 — what a creator types when they have no Meta connection. Deliberately carries
     * no engagement rate: {@code PlatformStat.engagementRate} feeds discovery ranking and the
     * brand's engagement filter, and a self-declared number there would be a claim competing with
     * measured ones. Followers is the minimum a creator needs for the brand's {@code
     * platforms=INSTAGRAM} filter to find them at all, and it lands marked {@code CREATOR_REPORTED}
     * (see {@link com.influora.domain.entity.CreatorMetric#DATA_SOURCE_CREATOR_REPORTED}) so it
     * never reads as platform-verified.
     */
    public record PlatformDeclarationRequest(String platform, String handle, Long followers) {}

    public record CoverUploadResponse(String url) {}

    public record PortfolioPatchRequest(
            String username,
            String displayName,
            String bio,
            String city,
            List<String> niches,
            String avatarUrl,
            String coverUrl,
            List<String> languages,
            List<PortfolioCustomLink> customLinks,
            List<PortfolioPinnedPost> pinnedPosts,
            List<PortfolioRateRow> rateCard,
            /**
             * F-0665/F-0434 — the "Past collabs — what shows on your page" control. Reuses {@link
             * PortfolioCollab}, the exact type {@link PortfolioPageResponse#collabs} already returns
             * (same convention as {@code rateCard} above reusing {@link PortfolioRateRow}), rather than
             * a second PATCH-only shape. Only {@link PortfolioCollab#id()} and {@link
             * PortfolioCollab#displayMode()} are trusted/persisted server-side — the rest of each row
             * (brandName, campaignTitle, rating, ...) is business data {@code PortfolioService}
             * recomputes live from {@code Collaboration}/{@code Campaign}/{@code Workspace}, never
             * something a client PATCH may overwrite.
             */
            List<PortfolioCollab> collabs,
            PortfolioVisibility visibility) {}
}
