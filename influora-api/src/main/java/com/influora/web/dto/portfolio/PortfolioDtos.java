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

    public record PortfolioStats(
            int totalCollabs, double avgRating, int onTimeRate, int repeatBrands) {}

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

    public record PortfolioPageResponse(
            String username,
            String displayName,
            String bio,
            String city,
            List<String> niches,
            String avatarUrl,
            String coverUrl,
            boolean verified,
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

    public record PortfolioContactRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 2000) String message,
            String captchaToken) {}

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
