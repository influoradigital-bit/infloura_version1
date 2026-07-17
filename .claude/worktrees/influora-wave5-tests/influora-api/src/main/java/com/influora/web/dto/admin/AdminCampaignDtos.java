package com.influora.web.dto.admin;

import com.influora.domain.enums.CampaignStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class AdminCampaignDtos {

    private AdminCampaignDtos() {}

    /**
     * Admin campaign-monitoring list item. Matches {@code CampaignSummary} in
     * {@code src/admin/types/admin.types.ts} (line 431), which {@code useCampaignList.ts} is
     * already shaped to receive. The frontend's {@code STANDARD}/{@code HYPE} type taxonomy and
     * {@code DRAFT}/{@code ACTIVE}/{@code PAUSED}/{@code COMPLETED}/{@code CANCELLED} status
     * taxonomy are the same as {@link CampaignStatus} (already maps 1:1 — see the FE mock data in
     * {@code useCampaignList.ts}, every status value already exists in our enum).
     *
     * <p>Fields like {@code slaBreachRate} are not stored denormalized — computed on the fly from
     * {@code deliverables}/{@code collaborations} in the service layer (follow-up scope, not
     * P2-7's MVP — P2-7 acceptance only requires real campaign rows, not computed aggregates).
     */
    public record CampaignSummaryDto(
            String id,
            String name,
            String brandName,
            /**
             * {@code STANDARD}/{@code HYPE} — NOT {@code CampaignIntentType}, which is the internal
             * campaign_type column for AI-drafted vs. human-created gating (see
             * {@code wiki/decisions/2026-07-07-d3-campaign-gating-scope.md}). The admin panel's
             * "type" is purely a UI concept for filtering/sorting in the campaign-monitoring table;
             * hardcoded to {@code STANDARD} for now (every campaign is STANDARD by default), and
             * future cycles can derive it from tags/flags/ai-origin markers once the UX
             * taxonomy is ratified by Priya.
             */
            String type,
            CampaignStatus status,
            BigDecimal budget,
            BigDecimal spent,
            Integer creatorCount,
            Integer deliverablesPending,
            Integer deliverablesApproved,
            Double slaBreachRate,
            Instant createdAt,
            LocalDate endsAt) {}
}
