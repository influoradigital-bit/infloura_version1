package com.influora.web.dto.creator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CreatorDtos {

    private CreatorDtos() {}

    /**
     * The per-post averages are boxed {@code Long}, never {@code long}, and every one of them is
     * routinely null: absent is not zero (F-0589). A creator who has not connected Meta has no
     * media insights at all, which today is very nearly the whole creator base, so the consuming UI
     * must key off the value being present rather than off the platform existing — otherwise a
     * block that used to not render starts rendering empty, which is strictly worse.
     *
     * <p>{@code avgViews} is Meta's unified {@code views} count. It is stored in the {@code
     * impressions} column upstream; the column name is an implementation detail and "views" is what
     * the UI says. {@code lastSyncedAt} is the last Meta sync of this platform, and is null for a
     * creator-declared one.
     */
    public record PlatformStatResponse(
            String platform,
            String handle,
            long followers,
            BigDecimal engagementRate,
            boolean isVerified,
            String profileUrl,
            Long avgReach,
            Long avgViews,
            Long avgLikes,
            Long avgComments,
            Instant lastSyncedAt) {}

    public record CreatorResponse(
            String id,
            String userId,
            String username,
            String displayName,
            String bio,
            String avatarUrl,
            String coverImageUrl,
            String location,
            List<String> categories,
            List<String> languages,
            List<String> contentStyles,
            List<PlatformStatResponse> platforms,
            long totalFollowers,
            BigDecimal engagementRate,
            BigDecimal averageRate,
            String currency,
            boolean isVerified,
            List<PortfolioItemResponse> portfolioItems,
            Boolean saved,
            // BR-18 — nullable nested projection of the canonical creator_scores read model
            // (DiscoveryDtos.CreatorScores, same shape CreatorPublicProfileResponse uses). Null
            // stays null end-to-end: brandSafety is null for every creator until BR-42 ships, and
            // quality/authenticity are null for any creator never polled/scored yet. Never coerce
            // to BigDecimal.ZERO here (see CreatorDiscoveryService#buildScores /
            // AdminCreatorService#latestQualityScore for the wrong way to do this).
            DiscoveryDtos.CreatorScores scores,
            // F-0965: VERIFIED | IMPORTED | NONE — what totalFollowers is made of. IMPORTED means
            // Marketplace/admin-imported numbers, shown as "imported, not verified".
            String followersSource) {}

    public record PortfolioItemResponse(
            String id,
            String title,
            String description,
            String thumbnailUrl,
            String mediaUrl,
            String platform) {}

    public record SaveRequest(@NotNull Boolean saved) {}

    public record SaveResponse(boolean saved) {}

    public record InviteRequest(@NotBlank String campaignId, String message) {}

    /**
     * F-0225 — the last field is {@code appliedAt}, not {@code createdAt}. A re-invite revives the
     * existing collaboration rather than inserting one, so the row's {@code created_at} is the
     * date of the engagement that was already withdrawn; {@code applied_at} is when THIS invitation
     * started. Named to match {@code CreatorCampaignDtos.ApplyResponse.appliedAt}, which the
     * creator side has always used.
     */
    public record InviteResponse(String collaborationId, String status, Instant appliedAt) {}
}
