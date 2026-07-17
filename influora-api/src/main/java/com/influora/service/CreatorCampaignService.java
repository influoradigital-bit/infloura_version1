package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignSpecs;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.ApplicationCreatedEvent;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyRequest;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.ApplyResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignDetailResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignListItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task #7 (creator campaign browse/apply, Creator Week 2 sprint — TASK_INBOX.md P0 #7). Creator
 * -facing counterpart to the brand-facing {@link CampaignService}: browse/apply are read paths
 * plus one mutation (apply), all gated through {@link CreatorContextService#requireCreatorProfile}
 * — never a client-supplied creator id (TECH-STACK.md cross-cutting rule #2).
 */
@Service
public class CreatorCampaignService {

    private static final Logger log = LoggerFactory.getLogger(CreatorCampaignService.class);

    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorContextService creatorContext;
    private final BrandContextService brandContext;
    private final ApplicationEventPublisher eventPublisher;

    public CreatorCampaignService(
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            WorkspaceRepository workspaceRepository,
            CreatorContextService creatorContext,
            BrandContextService brandContext,
            ApplicationEventPublisher eventPublisher) {
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorContext = creatorContext;
        this.brandContext = brandContext;
        this.eventPublisher = eventPublisher;
    }

    public record PagedCreatorCampaigns(List<CreatorCampaignListItem> items, PageMeta meta) {}

    /**
     * Platform/niche filters are applied in-memory after the DB-level status/visibility/deadline/
     * budget filters, same pattern as {@code CreatorDiscoveryService.search}'s vertical post-filter
     * (Campaign has no dedicated niche/category column to filter on at the DB level — see
     * TECH-STACK.md's note that 05_CREATOR_CAMPAIGNS_SPEC.md entity shapes are a feature reference,
     * not literal). When either post-filter is active, {@code total}/{@code hasMore} reflect only
     * the current page (same documented limitation as the vertical post-filter above it) — TODO for
     * Kavya: flag if creators need exact totals under these filters, which would require moving the
     * niche/platform match into the DB query (e.g. promoting platforms to a join table like
     * PlatformStat).
     */
    @Transactional(readOnly = true)
    public PagedCreatorCampaigns browse(
            AuthPrincipal principal,
            String niche,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            String platform,
            int page,
            int limit) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        Specification<Campaign> spec =
                Specification.where(CampaignSpecs.browsableForCreator())
                        .and(CampaignSpecs.applicationDeadlineNotPassed())
                        .and(CampaignSpecs.budgetOverlap(budgetMin, budgetMax));

        Page<Campaign> result =
                campaignRepository.findAll(
                        spec,
                        PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Campaign> campaigns = result.getContent();
        boolean postFiltered = false;
        if (platform != null && !platform.isBlank()) {
            campaigns = campaigns.stream().filter(c -> matchesPlatform(c, platform)).toList();
            postFiltered = true;
        }
        if (niche != null && !niche.isBlank()) {
            campaigns = campaigns.stream().filter(c -> matchesNiche(c, niche)).toList();
            postFiltered = true;
        }

        Map<String, Workspace> workspaces = loadWorkspaces(campaigns);
        Map<String, Collaboration> applicationsByCampaign = loadApplications(creator, campaigns);

        List<CreatorCampaignListItem> items =
                campaigns.stream()
                        .map(
                                c ->
                                        CreatorCampaignMapper.toListItem(
                                                c,
                                                workspaces.get(c.getWorkspaceId()),
                                                applicationsByCampaign.get(c.getId())))
                        .toList();

        long total = postFiltered ? items.size() : result.getTotalElements();
        boolean hasMore = postFiltered ? false : result.hasNext();
        return new PagedCreatorCampaigns(items, new PageMeta(safePage, safeLimit, total, hasMore));
    }

    @Transactional(readOnly = true)
    public CreatorCampaignDetailResponse getDetail(AuthPrincipal principal, String campaignId) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        Campaign campaign = requireVisibleCampaign(campaignId, creator.getUserId());
        Workspace workspace = workspaceRepository.findById(campaign.getWorkspaceId()).orElse(null);
        Collaboration existing =
                collaborationRepository
                        .findByCampaignIdAndCreatorId(campaign.getId(), creator.getUserId())
                        .orElse(null);
        return CreatorCampaignMapper.toDetail(campaign, workspace, existing);
    }

    /**
     * Idempotency: the {@code UNIQUE(campaign_id, creator_id)} constraint on {@code collaborations}
     * (V6__creators_collaborations.sql) already exists and backs this — the {@code existsBy...}
     * pre-check below handles the sequential duplicate, and the {@code DataIntegrityViolationException}
     * catch handles the concurrent-race loser, same shape as {@code CreatorDiscoveryService#invite}'s
     * documented TOCTOU fix.
     */
    @Transactional
    public ApplyResponse apply(AuthPrincipal principal, String campaignId, ApplyRequest req) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        Campaign campaign = requireVisibleCampaign(campaignId, creator.getUserId());

        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            throw new ApiException(
                    "CAMPAIGN_NOT_OPEN", "This campaign is not open for applications", HttpStatus.CONFLICT);
        }
        if (campaign.getApplicationDeadline() != null
                && campaign.getApplicationDeadline().isBefore(LocalDate.now())) {
            throw new ApiException(
                    "APPLICATION_DEADLINE_PASSED",
                    "The application deadline for this campaign has passed",
                    HttpStatus.CONFLICT);
        }
        if (collaborationRepository.existsByCampaignIdAndCreatorId(
                campaign.getId(), creator.getUserId())) {
            throw new ApiException(
                    "ALREADY_APPLIED", "You have already applied to this campaign", HttpStatus.CONFLICT);
        }

        Collaboration collaboration =
                Collaboration.apply(
                        Ulids.newUlid(),
                        campaign.getId(),
                        creator.getUserId(),
                        req != null ? req.message() : null,
                        campaign.getCurrency());
        try {
            collaborationRepository.save(collaboration);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "ALREADY_APPLIED", "You have already applied to this campaign", HttpStatus.CONFLICT);
        }

        // W3-1 — #9 "creator applies to campaign" (07-NOTIFICATION-SYSTEM-SPEC.md §3.2). Notifies
        // the brand a new application arrived. Best-effort — a lookup failure here must never fail
        // the application itself, which has already fully succeeded above.
        try {
            notifyApplicationCreated(campaign, creator, collaboration);
        } catch (RuntimeException e) {
            log.error(
                    "ApplicationCreatedEvent notification failed for collaboration {} — application"
                            + " itself already succeeded",
                    collaboration.getId(),
                    e);
        }

        return new ApplyResponse(
                collaboration.getId(), collaboration.getStatus().name(), collaboration.getCreatedAt());
    }

    private void notifyApplicationCreated(
            Campaign campaign, CreatorProfile creator, Collaboration collaboration) {
        var recipient = brandContext.resolveBillingRecipient(campaign.getWorkspaceId());
        if (recipient == null) {
            return;
        }
        eventPublisher.publishEvent(
                new ApplicationCreatedEvent(
                        recipient.userId(),
                        campaign.getWorkspaceId(),
                        collaboration.getId(),
                        creator.getDisplayName(),
                        campaign.getTitle()));
    }

    /**
     * Never trust campaign visibility on id alone: DRAFT campaigns are never creator-visible, and a
     * {@code isPrivate} (invite-only) campaign is visible only if this creator already holds a
     * {@code Collaboration} row (i.e. was invited) — otherwise this 404s exactly like a campaign
     * that doesn't exist, so brands' invite-only campaigns can't be enumerated by id.
     */
    private Campaign requireVisibleCampaign(String campaignId, String creatorUserId) {
        Campaign campaign =
                campaignRepository
                        .findById(campaignId)
                        .filter(c -> c.getStatus() != CampaignStatus.DRAFT)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
        if (campaign.isPrivate()
                && !collaborationRepository.existsByCampaignIdAndCreatorId(campaign.getId(), creatorUserId)) {
            throw new ApiException("CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND);
        }
        return campaign;
    }

    private Map<String, Workspace> loadWorkspaces(List<Campaign> campaigns) {
        List<String> workspaceIds = campaigns.stream().map(Campaign::getWorkspaceId).distinct().toList();
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        return workspaceRepository.findAllById(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));
    }

    private Map<String, Collaboration> loadApplications(CreatorProfile creator, List<Campaign> campaigns) {
        List<String> campaignIds = campaigns.stream().map(Campaign::getId).toList();
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        return collaborationRepository
                .findByCreatorIdAndCampaignIdIn(creator.getUserId(), campaignIds)
                .stream()
                .collect(Collectors.toMap(Collaboration::getCampaignId, Function.identity()));
    }

    private static boolean matchesPlatform(Campaign c, String platform) {
        String needle = platform.trim().toUpperCase(Locale.ROOT);
        return JsonLists.stringListFromJson(c.getPlatformsJson()).stream()
                .anyMatch(p -> p.toUpperCase(Locale.ROOT).equals(needle));
    }

    private static boolean matchesNiche(Campaign c, String niche) {
        String needle = niche.trim().toLowerCase(Locale.ROOT);
        if (containsIgnoreCase(c.getTitle(), needle) || containsIgnoreCase(c.getDescription(), needle)) {
            return true;
        }
        return anyListContains(c.getHashtagsJson(), needle)
                || anyListContains(c.getRequirementsJson(), needle)
                || anyListContains(c.getObjectivesJson(), needle);
    }

    private static boolean anyListContains(String json, String needleLower) {
        return JsonLists.stringListFromJson(json).stream()
                .anyMatch(v -> containsIgnoreCase(v, needleLower));
    }

    private static boolean containsIgnoreCase(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
    }
}
