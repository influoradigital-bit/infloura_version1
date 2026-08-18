package com.influora.web.dto.creatorcampaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * "My Applications" page (my-applications-plan-2026-07-24.md) — CTO-approved allowlist DTO for
 * {@code GET /creator/applications}. Deliberately narrower than {@code Collaboration} itself
 * (Kabir R2 minimization): no brand notes, no budget internals, no other-applicant data. {@code
 * status} is the raw {@link com.influora.domain.enums.CollaborationStatus} name; {@code
 * statusLabel} is the creator-facing label computed server-side (plan decision #4 — one shared
 * label source, no duplicated enum maps on the frontend). {@code dealId} is the collaboration id
 * (same row backs both the application and, once it progresses, the deal).
 */
public final class CreatorApplicationDtos {

    private CreatorApplicationDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatorApplicationListItem(
            String campaignId,
            String campaignTitle,
            String brandName,
            String brandLogoUrl,
            Instant appliedAt,
            String status,
            String statusLabel,
            BigDecimal agreedRate,
            String currency,
            String dealId) {}

    /**
     * One row of the append-only application-history timeline — {@code GET
     * /creator/applications/{dealId}/history}. Field-for-field mirror of {@code
     * ApplicationHistoryEvent}, renamed only where the entity's JPA column name and the wire name
     * already differ (none do here). {@code eventType}/{@code eventStatus}/{@code actorType} are
     * serialized as their raw enum names, same convention {@code status} uses on {@link
     * CreatorApplicationListItem} above. {@code dealRoomId} is {@code null} until the application
     * is accepted; {@code metadata}, {@code targetRoute} and {@code targetId} are optional and
     * omitted when absent.
     *
     * <p>{@code dealPhase} is computed server-side, not stored — see {@code
     * DealPhaseCalculator}, which mirrors {@code getDealPhase} in {@code
     * deal-room-step-progress.tsx}. One of {@code negotiate | contract | escrow | deliver | pay},
     * or {@code null} when the event predates any deal room (this application was
     * rejected/cancelled and never had one) rather than defaulting to a phase that would be
     * misleading.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApplicationHistoryEventItem(
            String historyId,
            String campaignId,
            String applicationId,
            String dealRoomId,
            String eventType,
            String eventStatus,
            String actorType,
            String actorId,
            String description,
            Instant createdAt,
            String metadata,
            String targetRoute,
            String targetId,
            String dealPhase) {}
}
