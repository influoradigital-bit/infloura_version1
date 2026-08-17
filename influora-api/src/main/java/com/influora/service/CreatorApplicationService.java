package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.PageMeta;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorcampaign.CreatorApplicationDtos.CreatorApplicationListItem;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "My Applications" page (my-applications-plan-2026-07-24.md). CTO arbitration #1: source of
 * truth is {@code Collaboration} rows where {@code source = APPLICATION} — this excludes brand
 * invites and, because {@code source} never mutates, still captures an application after it
 * progresses into a deal. Identity is always {@code requireCreatorProfile(principal).getUserId()}
 * — there is no creator-id request param (Kabir R1 IDOR gate).
 */
@Service
public class CreatorApplicationService {

    private static final int MAX_LIMIT = 50;

    private final CollaborationRepository collaborationRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorContextService creatorContext;

    public CreatorApplicationService(
            CollaborationRepository collaborationRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            CreatorContextService creatorContext) {
        this.collaborationRepository = collaborationRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorContext = creatorContext;
    }

    public record PagedApplications(List<CreatorApplicationListItem> items, PageMeta meta) {}

    @Transactional(readOnly = true)
    public PagedApplications list(AuthPrincipal principal, String status, int page, int limit) {
        CreatorProfile creator = creatorContext.requireCreatorProfile(principal);
        String creatorUserId = creator.getUserId();

        CollaborationStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = CollaborationStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApiException(
                        "INVALID_STATUS", "Unknown application status: " + status, HttpStatus.BAD_REQUEST);
            }
        }

        List<Collaboration> all =
                collaborationRepository.findByCreatorIdAndSource(creatorUserId, CollaborationSource.APPLICATION);

        if (statusFilter != null) {
            CollaborationStatus finalStatusFilter = statusFilter;
            all = all.stream().filter(c -> c.getStatus() == finalStatusFilter).toList();
        }

        List<Collaboration> sorted =
                // F-0225 — sort on appliedAt, matching the date this list actually displays. A
                // revived (withdraw-then-re-apply) row otherwise sorts by the abandoned attempt's
                // date and sinks to the bottom of the creator's list.
                all.stream().sorted(Comparator.comparing(Collaboration::getAppliedAt).reversed()).toList();

        int safePage = Math.max(1, page);
        int safeLimit = Math.min(Math.max(1, limit), MAX_LIMIT);

        int total = sorted.size();
        int fromIndex = Math.min((safePage - 1) * safeLimit, total);
        int toIndex = Math.min(fromIndex + safeLimit, total);
        List<Collaboration> pageSlice = sorted.subList(fromIndex, toIndex);
        boolean hasMore = toIndex < total;

        Map<String, Campaign> campaigns = loadCampaigns(pageSlice);
        Map<String, Workspace> workspaces = loadWorkspaces(campaigns.values());

        List<CreatorApplicationListItem> items =
                pageSlice.stream()
                        .map(
                                c -> {
                                    Campaign campaign = campaigns.get(c.getCampaignId());
                                    Workspace workspace =
                                            campaign != null ? workspaces.get(campaign.getWorkspaceId()) : null;
                                    return CreatorApplicationMapper.toListItem(c, campaign, workspace);
                                })
                        .toList();

        return new PagedApplications(items, new PageMeta(safePage, safeLimit, total, hasMore));
    }

    private Map<String, Campaign> loadCampaigns(List<Collaboration> collaborations) {
        List<String> campaignIds = collaborations.stream().map(Collaboration::getCampaignId).distinct().toList();
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        return campaignRepository.findAllById(campaignIds).stream()
                .collect(Collectors.toMap(Campaign::getId, Function.identity()));
    }

    private Map<String, Workspace> loadWorkspaces(java.util.Collection<Campaign> campaigns) {
        List<String> workspaceIds = campaigns.stream().map(Campaign::getWorkspaceId).distinct().toList();
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        return workspaceRepository.findAllById(workspaceIds).stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));
    }
}
