package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.HypeConfigDto;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CampaignValidator {

    public void validateBudget(BudgetDto budget) {
        if (budget == null) {
            throw new ApiException("VALIDATION_ERROR", "Budget is required", HttpStatus.BAD_REQUEST);
        }
        if (budget.min().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("VALIDATION_ERROR", "budget.min must be greater than 0", HttpStatus.BAD_REQUEST);
        }
        if (budget.max().compareTo(budget.min()) < 0) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "budget.max must be greater than or equal to budget.min",
                    HttpStatus.BAD_REQUEST);
        }
    }

    public void validateTimeline(TimelineDto timeline, LocalDate applicationDeadline) {
        if (timeline == null || timeline.startDate() == null || timeline.endDate() == null) {
            throw new ApiException("VALIDATION_ERROR", "Campaign timeline is required", HttpStatus.BAD_REQUEST);
        }
        if (!timeline.endDate().isAfter(timeline.startDate())) {
            throw new ApiException(
                    "VALIDATION_ERROR", "timeline.endDate must be after timeline.startDate", HttpStatus.BAD_REQUEST);
        }
        if (applicationDeadline != null && !applicationDeadline.isBefore(timeline.startDate())) {
            throw new ApiException(
                    "VALIDATION_ERROR",
                    "applicationDeadline must be before timeline.startDate",
                    HttpStatus.BAD_REQUEST);
        }
    }

    public void validateStatusForWorkspace(CampaignStatus status, Workspace workspace) {
        if (status == CampaignStatus.ACTIVE
                && workspace.getVerificationStatus() != VerificationStatus.VERIFIED) {
            throw new ApiException(
                    "WORKSPACE_NOT_VERIFIED",
                    "Workspace must be verified before publishing an ACTIVE campaign",
                    HttpStatus.FORBIDDEN);
        }
    }

    public void ensureEditable(CampaignStatus current) {
        if (current == CampaignStatus.COMPLETED || current == CampaignStatus.CANCELLED) {
            throw new ApiException(
                    "CAMPAIGN_NOT_EDITABLE",
                    "Cannot edit a completed or cancelled campaign",
                    HttpStatus.CONFLICT);
        }
    }

    public void ensureDeletable(CampaignStatus current) {
        if (current != CampaignStatus.DRAFT) {
            throw new ApiException(
                    "CAMPAIGN_NOT_DELETABLE", "Only DRAFT campaigns can be deleted", HttpStatus.CONFLICT);
        }
    }

    public void validateResumeActive(CampaignStatus newStatus, Workspace workspace) {
        if (newStatus == CampaignStatus.ACTIVE) {
            validateStatusForWorkspace(CampaignStatus.ACTIVE, workspace);
        }
    }

    /**
     * Conditional on {@code campaignType} rather than a bean-validation {@code @Valid} cascade so a
     * STANDARD/DIRECT/REVIEW request that omits (or sends a partial) {@code hype} block is never
     * rejected — only a HYPE campaign must carry a well-formed config. Lenient on
     * {@code audioTrack}/{@code currency}/{@code slotsFilled}/{@code liveUntil}, matching the
     * optional sub-fields the brand-new-hype-campaign.tsx form itself treats as optional.
     */
    public void validateHypeConfig(CampaignIntentType campaignType, HypeConfigDto hype) {
        if (campaignType != CampaignIntentType.HYPE) {
            return;
        }
        if (hype == null) {
            throw new ApiException(
                    "VALIDATION_ERROR", "hype config is required for a HYPE campaign", HttpStatus.BAD_REQUEST);
        }
        if (hype.sourceReelUrl() == null || hype.sourceReelUrl().isBlank()) {
            throw new ApiException(
                    "VALIDATION_ERROR", "hype.sourceReelUrl is required", HttpStatus.BAD_REQUEST);
        }
        if (hype.hashtag() == null || hype.hashtag().isBlank()) {
            throw new ApiException("VALIDATION_ERROR", "hype.hashtag is required", HttpStatus.BAD_REQUEST);
        }
        if (hype.formatLanes() == null || hype.formatLanes().isEmpty()) {
            throw new ApiException(
                    "VALIDATION_ERROR", "hype.formatLanes must include at least one lane", HttpStatus.BAD_REQUEST);
        }
        if (hype.perReelRate() == null || hype.perReelRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(
                    "VALIDATION_ERROR", "hype.perReelRate must be greater than 0", HttpStatus.BAD_REQUEST);
        }
        if (hype.slotCap() == null || hype.slotCap() <= 0) {
            throw new ApiException(
                    "VALIDATION_ERROR", "hype.slotCap must be greater than 0", HttpStatus.BAD_REQUEST);
        }
    }
}
