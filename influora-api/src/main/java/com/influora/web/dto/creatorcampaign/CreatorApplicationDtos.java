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
}
