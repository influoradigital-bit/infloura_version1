package com.influora.web.dto.creatorcampaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Task #7 (creator campaign browse/apply, Creator Week 2 sprint) — DTOs for the creator-facing
 * read/apply surface of {@code Campaign}/{@code Collaboration}. Deliberately narrower than the
 * brand-facing {@code CampaignDtos} (e.g. no {@code workspaceId}, no internal metrics) — see
 * {@code wiki/tech/creator/05_CREATOR_CAMPAIGNS_SPEC.md} section 7.1: brand contact info and
 * internal fields must never leak into a creator-visible campaign listing.
 */
public final class CreatorCampaignDtos {

    private CreatorCampaignDtos() {}

    public record BrandSummary(String workspaceId, String name, String logoUrl) {}

    public record BudgetDto(BigDecimal min, BigDecimal max, String currency) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatorCampaignListItem(
            String id,
            String title,
            String description,
            BrandSummary brand,
            BudgetDto budget,
            List<String> platforms,
            List<String> requirements,
            LocalDate applicationDeadline,
            LocalDate startDate,
            LocalDate endDate,
            Integer maxCollaborators,
            /** Null if the creator has never applied/been invited; otherwise the {@code CollaborationStatus} name. */
            String applicationStatus,
            Instant createdAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatorCampaignDetailResponse(
            String id,
            String title,
            String description,
            BrandSummary brand,
            List<String> objectives,
            BudgetDto budget,
            List<String> platforms,
            List<String> contentTypes,
            List<String> requirements,
            List<String> hashtags,
            String brandGuidelines,
            LocalDate applicationDeadline,
            LocalDate startDate,
            LocalDate endDate,
            Integer maxCollaborators,
            String applicationStatus,
            Instant createdAt) {}

    /** Optional creator-authored note attached to the application; everything else is server-derived. */
    public record ApplyRequest(@Size(max = 2000) String message) {}

    public record ApplyResponse(String collaborationId, String status, Instant appliedAt) {}
}
