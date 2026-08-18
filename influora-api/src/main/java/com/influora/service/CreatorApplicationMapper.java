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
 * "Rejected" label; {@code CANCELLED} always reads as "Closed". CEO ruling
 * (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md, Decision 1) upheld that conclusion but on
 * different grounds: a brand's decline through this product IS finalized, not internal triage —
 * "Closed" stands because the word "Rejected" adds emotional weight without adding clarity the
 * "Closed" label plus its description doesn't already give.
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

    /**
     * Canonical creator-facing status map (my-applications-plan-2026-07-24.md).
     *
     * <p>Deliberately NOT the creator-facing label: {@code TERMS_AGREED} -> "In negotiation" and
     * {@code CANCELLED} -> "Closed" below are ruling-compliant as server strings (CEO ruling
     * Decision 5, .proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md, kept backend mappers as-is),
     * but the FE now shows "Accepted" for {@code TERMS_AGREED} (Decision 2) and routes decline
     * wording through a switchable constant in {@code src/lib/application-status.ts}. Nothing
     * renders this field today. Whatever starts rendering it must reconcile with that file first.
     */
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
