package com.influora.service;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.web.dto.dashboard.DashboardDtos.ActionItem;
import com.influora.web.dto.dashboard.DashboardDtos.PipelineStage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregations powering the brand dashboard (BACKEND-API-SPEC §33.6). Every figure is
 * derived from live workspace data — a brand with no collaborations/milestones yet gets honest
 * empty lists, never fabricated placeholder rows.
 */
@Service
public class DashboardService {

    /** Review SLA used as the deliverable-review deadline when no milestone due date applies. */
    private static final int REVIEW_SLA_DAYS = 3;

    /**
     * Display order for the pipeline funnel. PL-2 (BrandF.md §69): this used to be its own,
     * 4-bucket collapse of {@link CollaborationStatus} that disagreed with the brand pipeline
     * board's 6-column vocabulary (src/lib/brand-pipeline-stage.ts) on three points — INVITED/
     * APPLIED/SHORTLISTED counted as Negotiating here but Outreach there, REVIEW_PENDING/
     * REVISION_REQUESTED counted as In Progress here but Review there, and the terminal bucket
     * was labelled Completed here vs Settled there — so the same deal could show a different
     * stage on the Dashboard card than on the Pipeline page in the same session. Now mirrors
     * mapCollaborationStatusToPipelineStage() exactly. Terminal CANCELLED/DISPUTED states are
     * still excluded (not "in flight", would distort the funnel).
     */
    private static final String OUTREACH = "Outreach";
    private static final String NEGOTIATING = "Negotiating";
    private static final String CONTRACTED = "Contracted";
    private static final String IN_PROGRESS = "In Progress";
    private static final String REVIEW = "Review";
    private static final String SETTLED = "Settled";
    private static final List<String> STAGE_ORDER =
            List.of(OUTREACH, NEGOTIATING, CONTRACTED, IN_PROGRESS, REVIEW, SETTLED);

    private final CollaborationRepository collaborationRepository;
    private final PaymentMilestoneRepository milestoneRepository;
    private final CampaignRepository campaignRepository;

    public DashboardService(
            CollaborationRepository collaborationRepository,
            PaymentMilestoneRepository milestoneRepository,
            CampaignRepository campaignRepository) {
        this.collaborationRepository = collaborationRepository;
        this.milestoneRepository = milestoneRepository;
        this.campaignRepository = campaignRepository;
    }

    @Transactional(readOnly = true)
    public List<PipelineStage> pipeline(String workspaceId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String stage : STAGE_ORDER) {
            counts.put(stage, 0L);
        }
        for (Collaboration c : collaborationRepository.findByWorkspaceId(workspaceId)) {
            String bucket = bucketFor(c.getStatus());
            if (bucket != null) {
                counts.merge(bucket, 1L, Long::sum);
            }
        }
        // Only surface stages that actually have collaborations in them.
        return counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new PipelineStage(e.getKey(), e.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ActionItem> actions(String workspaceId) {
        List<ActionItem> items = new ArrayList<>();

        // Deliverables awaiting the brand's review.
        for (Collaboration c : collaborationRepository.findByWorkspaceId(workspaceId)) {
            if (c.getStatus() == CollaborationStatus.REVIEW_PENDING) {
                String campaignName =
                        campaignRepository
                                .findById(c.getCampaignId())
                                .map(Campaign::getTitle)
                                .orElse("Campaign");
                items.add(
                        new ActionItem(
                                "act-rev-" + c.getId(),
                                "deliverable_review",
                                "Review pending deliverable",
                                campaignName,
                                c.getCreatedAt().plus(REVIEW_SLA_DAYS, ChronoUnit.DAYS),
                                "high",
                                BigDecimal.ZERO,
                                "/brand/chat?deal=" + c.getId() + "&tab=deliverables"));
            }
        }

        // Funded milestones the brand can release once work is approved.
        for (PaymentMilestone m :
                milestoneRepository.findByWorkspaceIdAndStatus(workspaceId, MilestoneStatus.FUNDED)) {
            Instant deadline =
                    m.getDueDate() != null
                            ? m.getDueDate().atStartOfDay(ZoneOffset.UTC).toInstant()
                            : m.getUpdatedAt();
            items.add(
                    new ActionItem(
                            "act-pay-" + m.getId(),
                            "payment_release",
                            "Release milestone payment",
                            m.getDescription() != null ? m.getDescription() : "Milestone",
                            deadline,
                            "urgent",
                            m.getAmount(),
                            "/brand/chat?deal=" + m.getCollaborationId() + "&tab=payments"));
        }

        return items;
    }

    private static String bucketFor(CollaborationStatus status) {
        return switch (status) {
            case INVITED, APPLIED, SHORTLISTED -> OUTREACH;
            case IN_NEGOTIATION, TERMS_AGREED -> NEGOTIATING;
            case CONTRACT_PENDING, CONTRACTED -> CONTRACTED;
            case IN_PROGRESS -> IN_PROGRESS;
            case REVIEW_PENDING, REVISION_REQUESTED -> REVIEW;
            case COMPLETED -> SETTLED;
            case CANCELLED, DISPUTED -> null;
        };
    }
}
