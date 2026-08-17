package com.influora.service;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.web.dto.creatorcampaign.CreatorApplicationDtos.CreatorApplicationListItem;

/**
 * "My Applications" page (my-applications-plan-2026-07-24.md) — maps a {@code Collaboration}
 * (source = APPLICATION) plus its {@code Campaign}/{@code Workspace} into the allowlist DTO.
 * {@code statusLabel} follows the CTO-arbitrated creator-facing status map exactly — there is NO
 * "Rejected" label; {@code CANCELLED} always reads as "Closed" (Kabir R5 — never surface
 * brand-internal triage as a decision the brand hasn't finalized).
 */
public final class CreatorApplicationMapper {

    private CreatorApplicationMapper() {}

    public static CreatorApplicationListItem toListItem(
            Collaboration collaboration, Campaign campaign, Workspace workspace) {
        return new CreatorApplicationListItem(
                campaign != null ? campaign.getId() : collaboration.getCampaignId(),
                campaign != null ? campaign.getTitle() : null,
                workspace != null ? workspace.getName() : null,
                workspace != null ? workspace.getLogoUrl() : null,
                // F-0225 — `appliedAt`, not `createdAt`: after a withdraw-then-re-apply the row is
                // revived rather than recreated, so `createdAt` is the date of the attempt the
                // creator already withdrew. This field is the creator-facing "Applied" date.
                collaboration.getAppliedAt(),
                collaboration.getStatus().name(),
                statusLabel(collaboration.getStatus()),
                collaboration.getAgreedRate(),
                collaboration.getCurrency(),
                collaboration.getId());
    }

    /** Canonical creator-facing status map (my-applications-plan-2026-07-24.md). */
    public static String statusLabel(CollaborationStatus status) {
        return switch (status) {
            case APPLIED -> "Applied";
            case SHORTLISTED -> "Shortlisted";
            case IN_NEGOTIATION, TERMS_AGREED -> "In negotiation";
            case CONTRACT_PENDING -> "Contract pending";
            case CONTRACTED, IN_PROGRESS, REVIEW_PENDING, REVISION_REQUESTED -> "Active";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Closed";
            case DISPUTED -> "In dispute";
            case INVITED -> "Applied"; // defensive default; INVITED never occurs for source=APPLICATION rows
        };
    }
}
