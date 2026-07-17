package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.SavedCreator;
import com.influora.domain.entity.Workspace;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.SavedCreatorRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creator.CreatorDtos.CreatorResponse;
import com.influora.web.dto.creator.CreatorDtos.InviteResponse;
import com.influora.web.dto.creator.CreatorDtos.SaveResponse;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreatorDiscoveryService {

    private final BrandContextService brandContext;
    private final CreatorProfileRepository creatorProfileRepository;
    private final PlatformStatRepository platformStatRepository;
    private final SavedCreatorRepository savedCreatorRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;

    public CreatorDiscoveryService(
            BrandContextService brandContext,
            CreatorProfileRepository creatorProfileRepository,
            PlatformStatRepository platformStatRepository,
            SavedCreatorRepository savedCreatorRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository) {
        this.brandContext = brandContext;
        this.creatorProfileRepository = creatorProfileRepository;
        this.platformStatRepository = platformStatRepository;
        this.savedCreatorRepository = savedCreatorRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
    }

    public record PagedCreators(List<CreatorResponse> items, PageMeta meta) {}

    @Transactional(readOnly = true)
    public PagedCreators search(
            AuthPrincipal principal,
            String q,
            String platforms,
            String city,
            String verticals,
            Long minFollowers,
            Long maxFollowers,
            BigDecimal minRate,
            BigDecimal maxRate,
            BigDecimal minEngagementRate,
            BigDecimal maxEngagementRate,
            Boolean isVerified,
            int page,
            int limit,
            String sortBy) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        List<String> platformList = parseCsvUpper(platforms);
        List<String> verticalList = parseCsvLower(verticals);

        Specification<CreatorProfile> spec =
                CreatorProfileSpecifications.combine(
                        CreatorProfileSpecifications.nameSearch(q),
                        CreatorProfileSpecifications.singleCity(city),
                        CreatorProfileSpecifications.followersBetween(minFollowers, maxFollowers),
                        CreatorProfileSpecifications.engagementBetween(minEngagementRate, maxEngagementRate),
                        CreatorProfileSpecifications.verifiedOnly(isVerified),
                        CreatorProfileSpecifications.rateOverlap(minRate, maxRate),
                        CreatorProfileSpecifications.hasPlatforms(platformList));

        Sort sort = toSort(sortBy);
        Page<CreatorProfile> result =
                creatorProfileRepository.findAll(spec, PageRequest.of(safePage - 1, safeLimit, sort));

        List<CreatorProfile> profiles = result.getContent();
        if (!verticalList.isEmpty()) {
            profiles =
                    profiles.stream()
                            .filter(p -> categoriesMatch(p, verticalList))
                            .toList();
        }

        List<String> profileIds = profiles.stream().map(CreatorProfile::getId).toList();
        Map<String, List<PlatformStat>> platformsByCreator =
                CreatorMapper.groupPlatforms(platformStatRepository.findByCreatorProfileIdIn(profileIds));

        Set<String> savedIds =
                new HashSet<>(
                        savedCreatorRepository
                                .findByWorkspaceIdAndCreatorProfileIdInAndSavedTrue(
                                        workspace.getId(), profileIds)
                                .stream()
                                .map(SavedCreator::getCreatorProfileId)
                                .toList());

        List<CreatorResponse> items =
                profiles.stream()
                        .map(
                                p ->
                                        CreatorMapper.toResponse(
                                                p,
                                                platformsByCreator.getOrDefault(
                                                        p.getId(), List.of()),
                                                savedIds.contains(p.getId())))
                        .toList();

        long total = verticalList.isEmpty() ? result.getTotalElements() : items.size();
        boolean hasMore = (long) safePage * safeLimit < total;
        return new PagedCreators(items, new PageMeta(safePage, safeLimit, total, hasMore));
    }

    @Transactional(readOnly = true)
    public CreatorResponse get(AuthPrincipal principal, String creatorId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        CreatorProfile profile = requireDiscoverableProfile(creatorId);
        List<PlatformStat> platforms = platformStatRepository.findByCreatorProfileId(profile.getId());
        boolean saved =
                savedCreatorRepository
                        .findByWorkspaceIdAndCreatorProfileId(workspace.getId(), profile.getId())
                        .map(SavedCreator::isSaved)
                        .orElse(false);
        return CreatorMapper.toResponse(profile, platforms, saved);
    }

    @Transactional
    public SaveResponse toggleSaved(AuthPrincipal principal, String creatorId, boolean saved) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        CreatorProfile profile = requireDiscoverableProfile(creatorId);
        SavedCreator row =
                savedCreatorRepository
                        .findByWorkspaceIdAndCreatorProfileId(workspace.getId(), profile.getId())
                        .orElse(null);
        if (row == null) {
            if (saved) {
                savedCreatorRepository.save(
                        SavedCreator.of(Ulids.newUlid(), workspace.getId(), profile.getId(), true));
            }
        } else {
            row.setSaved(saved);
            savedCreatorRepository.save(row);
        }
        return new SaveResponse(saved);
    }

    @Transactional
    public InviteResponse invite(
            AuthPrincipal principal, String creatorProfileId, String campaignId, String message) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        CreatorProfile profile = requireDiscoverableProfile(creatorProfileId);
        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspace.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND",
                                                "Campaign not found",
                                                HttpStatus.NOT_FOUND));
        if (collaborationRepository.existsByCampaignIdAndCreatorId(
                campaign.getId(), profile.getUserId())) {
            throw new ApiException(
                    "COLLABORATION_EXISTS",
                    "This creator has already been invited to this campaign",
                    HttpStatus.CONFLICT);
        }
        Collaboration collaboration =
                Collaboration.invite(
                        Ulids.newUlid(),
                        campaign.getId(),
                        profile.getUserId(),
                        message,
                        campaign.getCurrency());
        collaborationRepository.save(collaboration);
        return new InviteResponse(
                collaboration.getId(),
                collaboration.getStatus().name(),
                collaboration.getCreatedAt());
    }

    private CreatorProfile requireDiscoverableProfile(String id) {
        return creatorProfileRepository
                .findByIdAndDiscoverableTrue(id)
                .or(() -> creatorProfileRepository.findByUserId(id).filter(CreatorProfile::isDiscoverable))
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));
    }

    private static List<String> parseCsvUpper(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<String> parseCsvLower(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean categoriesMatch(CreatorProfile profile, List<String> verticals) {
        List<String> categories = JsonLists.stringListFromJson(profile.getCategoriesJson());
        if (categories.isEmpty()) {
            return false;
        }
        Set<String> normalized =
                categories.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String v : verticals) {
            String needle = v.toLowerCase(Locale.ROOT);
            if (normalized.stream().anyMatch(c -> c.contains(needle) || needle.contains(c))) {
                return true;
            }
        }
        return false;
    }

    private static Sort toSort(String sortBy) {
        if (sortBy == null) {
            return Sort.by(Sort.Direction.DESC, "totalFollowers");
        }
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "engagement" -> Sort.by(Sort.Direction.DESC, "engagementRate");
            case "rate", "price_low" -> Sort.by(Sort.Direction.ASC, "rateMin");
            case "price_high" -> Sort.by(Sort.Direction.DESC, "rateMax");
            default -> Sort.by(Sort.Direction.DESC, "totalFollowers");
        };
    }
}
