package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response/request records for {@code AdminCreatorController}. Field names match {@code
 * CreatorDetail}/{@code PlatformStats}/{@code CreatorQualityMetrics}/{@code CollaborationRecord}
 * in {@code src/admin/types/admin.types.ts} exactly, mirroring {@code AdminBrandDtos}'s
 * convention (same "honest zeros / honest nulls for data this schema doesn't track yet" rule —
 * see field-level comments below for which ones apply here).
 */
public final class AdminCreatorDtos {

    private AdminCreatorDtos() {}

    public record CreatorSummaryDto(
            String id,
            String name,
            String email,
            String instagramHandle,
            long followers,
            String applicationStatus,
            String tier,
            boolean isSuspended,
            Instant createdAt) {}

    /** Matches {@code PaginatedResponse<T>} in admin.types.ts exactly. */
    public record PagedCreatorsDto(
            List<CreatorSummaryDto> data, long total, int page, int pageSize, int totalPages) {}

    public record CreatorDetailDto(
            String id,
            String name,
            String email,
            List<String> niche,
            long followers,
            BigDecimal engagementRate,
            String applicationStatus,
            boolean instagramVerified,
            BigDecimal qualityScore,
            String tier,
            boolean isSuspended,
            Instant createdAt,
            String bio,
            String location,
            String instagramHandle,
            // Never persisted anywhere in this schema (confirmed by grep — Meta's ig-user-id is
            // only ever held transiently inside InstagramInsightsClient's request/response DTOs,
            // never written to a column). Honestly null rather than fabricated, per TECH-STACK
            // rule 7.
            String instagramUserId,
            Instant instagramOauthAt,
            PlatformStatsDto platformStats,
            List<CollaborationRecordDto> collaborationHistory,
            QualityMetricsDto qualityMetrics,
            long flaggedContentCount,
            String applicationReviewedBy,
            Instant applicationReviewedAt,
            String applicationRejectionReason) {}

    public record PlatformStatsDto(
            long avgReach, long avgEngagement, int postsLast30Days, int reelsLast30Days) {}

    /**
     * {@code deadlineAdherence}/{@code revisionRate}/{@code disputeRate} are honest zeros — no
     * deadline/revision/dispute tracking table exists in this schema (this codebase's only
     * per-deliverable numbers are {@code DeliverableMetric}'s creator-self-reported
     * reach/impressions/engagements, which is a different concept entirely). Same documented gap
     * class as {@code AdminBrandDtos.CampaignSummaryDto}. {@code overallScore} is real —
     * {@code CreatorScore.qualityScore} from the latest {@code ScoreCalculationJob} run, when one
     * exists.
     */
    public record QualityMetricsDto(
            double deadlineAdherence, double revisionRate, double disputeRate, BigDecimal overallScore) {}

    public record CollaborationRecordDto(
            String id, String campaignName, String brandName, BigDecimal amount, String status, Instant completedAt) {}

    /**
     * {@code action} must be APPROVE or REJECT; {@code reason} is mandatory per admin.types.ts's
     * {@code CreatorApplicationAction}. {@code creatorId} mirrors that type's shape but is
     * deliberately IGNORED server-side — same "path variable is the only trusted id source"
     * discipline as {@code AdminBrandDtos.VerifyKycRequest}.
     */
    public record ReviewApplicationRequest(
            String creatorId, @NotBlank String action, @NotBlank @Size(max = 2000) String reason) {}

    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ReinstateRequest(@NotBlank @Size(max = 500) String reason) {}

    /**
     * PUT /admin/creators/{id} ({@code creatorApi.update} — api-contracts.ts:244-248). SAFE profile
     * fields ONLY: {@code name} (creator_profiles.display_name) and {@code niche}
     * (creator_profiles.categories). Both optional ({@code Partial<Creator>} — absent/null means
     * "leave unchanged", enforced by the null-guarded {@code CreatorProfile.applyAdminProfileEdit}).
     * Deliberately excludes applicationStatus/isSuspended/tier/followers/engagementRate/qualityScore/
     * instagramVerified/email — application review, suspension and tier have dedicated flows; the
     * rest are computed metrics (aggregated from real platform stats / scoring jobs) or auth
     * identity (email lives on {@code users}, not this profile), never free-form admin-editable.
     * Any out-of-allow-list JSON property is dropped by Jackson — the record shape IS the
     * allow-list, never a blind copy onto the entity.
     */
    public record UpdateCreatorRequest(@Size(max = 100) String name, List<String> niche) {}

    /**
     * PUT /admin/creators/{id}/tier ({@code creatorApi.adjustTier} — api-contracts.ts:256-260).
     * {@code newTier} is validated against the {@code CreatorTier} enum server-side (NANO/MICRO/MID/
     * MACRO); {@code reason} mandatory, min 10 chars (same money/identity-path convention as the
     * billing DTOs). {@code creatorId} mirrors {@code CreatorTierAdjustment}'s shape but is
     * IGNORED server-side — the path variable is the only trusted id source (same discipline as
     * {@code ReviewApplicationRequest.creatorId}). {@code newQualityScore} is accepted for wire
     * compatibility with the frontend type but intentionally NOT persisted — qualityScore is a
     * computed metric ({@code CreatorScore}/{@code ScoreCalculationJob}), never an admin free-set
     * value.
     */
    public record AdjustTierRequest(
            String creatorId,
            @NotBlank String newTier,
            Double newQualityScore,
            @NotBlank @Size(min = 10, max = 500) String reason) {}
}
