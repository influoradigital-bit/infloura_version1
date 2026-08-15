package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.CreatorScoreMath;
import com.influora.common.JsonLists;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.FeaturedCreator;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.entity.Review;
import com.influora.domain.entity.SavedCreator;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ReviewerType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.FeaturedCreatorRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.SavedCreatorRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.portfolio.PortfolioService;
import com.influora.web.dto.creator.CreatorDtos.CreatorResponse;
import com.influora.web.dto.creator.CreatorDtos.InviteResponse;
import com.influora.web.dto.creator.CreatorDtos.SaveResponse;
import com.influora.web.dto.creator.DiscoveryDtos.AvailableFiltersMeta;
import com.influora.web.dto.creator.DiscoveryDtos.CategoryFacet;
import com.influora.web.dto.creator.DiscoveryDtos.CreatorPublicProfileResponse;
import com.influora.web.dto.creator.DiscoveryDtos.CreatorScores;
import com.influora.web.dto.creator.DiscoveryDtos.CreatorSuggestionItem;
import com.influora.web.dto.creator.DiscoveryDtos.CreatorSuggestionRequest;
import com.influora.web.dto.creator.DiscoveryDtos.CreatorSuggestionsResponse;
import com.influora.web.dto.creator.DiscoveryDtos.DiscoverySearchResponse;
import com.influora.web.dto.creator.DiscoveryDtos.FeaturedResponse;
import com.influora.web.dto.creator.DiscoveryDtos.FeaturedSection;
import com.influora.web.dto.creator.DiscoveryDtos.FollowerRangeFacet;
import com.influora.web.dto.creator.DiscoveryDtos.SearchFiltersMeta;
import com.influora.web.dto.creator.DiscoveryDtos.SimilarCreator;
import com.influora.web.dto.creator.DiscoveryDtos.SimilarCreatorsResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreatorDiscoveryService {

    private static final Map<String, String> FEATURED_TITLES =
            Map.of(
                    "top_fitness", "Top Fitness Creators",
                    "rising_star", "Rising Stars",
                    "editors_pick", "Editor's Picks",
                    "top_creators", "Top Creators");

    private static final List<String> SUGGESTION_NICHE_KEYWORDS =
            List.of(
                    "fitness", "food", "travel", "beauty", "fashion", "tech", "lifestyle", "health",
                    "gaming", "skincare", "gadgets", "wellness");

    private static final List<FollowerRangeBucket> FOLLOWER_RANGE_BUCKETS =
            List.of(
                    new FollowerRangeBucket("1K-10K", 1_000L, 10_000L),
                    new FollowerRangeBucket("10K-100K", 10_000L, 100_000L),
                    new FollowerRangeBucket("100K-1M", 100_000L, 1_000_000L));

    private final BrandContextService brandContext;
    private final CreatorProfileRepository creatorProfileRepository;
    private final PlatformStatRepository platformStatRepository;
    private final SavedCreatorRepository savedCreatorRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final FeaturedCreatorRepository featuredCreatorRepository;
    private final CreatorScoreRepository creatorScoreRepository;
    private final ReviewRepository reviewRepository;
    private final PortfolioService portfolioService;

    public CreatorDiscoveryService(
            BrandContextService brandContext,
            CreatorProfileRepository creatorProfileRepository,
            PlatformStatRepository platformStatRepository,
            SavedCreatorRepository savedCreatorRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            FeaturedCreatorRepository featuredCreatorRepository,
            CreatorScoreRepository creatorScoreRepository,
            ReviewRepository reviewRepository,
            PortfolioService portfolioService) {
        this.brandContext = brandContext;
        this.creatorProfileRepository = creatorProfileRepository;
        this.platformStatRepository = platformStatRepository;
        this.savedCreatorRepository = savedCreatorRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.featuredCreatorRepository = featuredCreatorRepository;
        this.creatorScoreRepository = creatorScoreRepository;
        this.reviewRepository = reviewRepository;
        this.portfolioService = portfolioService;
    }

    public record PagedCreators(List<CreatorResponse> items, PageMeta meta) {}

    public record SearchResult(PagedCreators page, DiscoverySearchResponse envelope) {}

    @Transactional(readOnly = true)
    public SearchResult search(
            AuthPrincipal principal,
            String q,
            String platforms,
            String city,
            String verticals,
            String categories,
            String languages,
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
        List<String> categoryList = mergeCategoryFilters(verticals, categories);
        List<String> languageList = parseCsvLower(languages);

        Specification<CreatorProfile> spec =
                CreatorProfileSpecifications.combine(
                        CreatorProfileSpecifications.nameSearch(q),
                        CreatorProfileSpecifications.singleCity(city),
                        CreatorProfileSpecifications.followersBetween(minFollowers, maxFollowers),
                        CreatorProfileSpecifications.engagementBetween(minEngagementRate, maxEngagementRate),
                        CreatorProfileSpecifications.verifiedOnly(isVerified),
                        CreatorProfileSpecifications.rateOverlap(minRate, maxRate),
                        CreatorProfileSpecifications.hasPlatforms(platformList),
                        CreatorProfileSpecifications.hasCategories(categoryList),
                        CreatorProfileSpecifications.hasLanguages(languageList));

        Sort sort = toSort(sortBy);
        Page<CreatorProfile> result =
                creatorProfileRepository.findAll(spec, PageRequest.of(safePage - 1, safeLimit, sort));

        List<CreatorProfile> profiles = result.getContent();
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

        // BR-18 — one batch query for the whole page, never a per-creator lookup inside the .map()
        // below (see CreatorScoreRepository#findLatestByCreatorProfileIdIn javadoc).
        Map<String, CreatorScores> scoresByCreator = loadScoresByCreator(profileIds);

        List<CreatorResponse> items =
                profiles.stream()
                        .map(
                                p ->
                                        CreatorMapper.toResponse(
                                                p,
                                                platformsByCreator.getOrDefault(
                                                        p.getId(), List.of()),
                                                savedIds.contains(p.getId()),
                                                scoresByCreator.get(p.getId())))
                        .toList();

        long total = result.getTotalElements();
        boolean hasMore = (long) safePage * safeLimit < total;
        PagedCreators pageResult = new PagedCreators(items, new PageMeta(safePage, safeLimit, total, hasMore));

        Map<String, Object> applied = buildAppliedFilters(
                q,
                categoryList,
                languageList,
                minFollowers,
                maxFollowers,
                minEngagementRate,
                maxEngagementRate,
                minRate,
                maxRate,
                city,
                platformList,
                isVerified);

        DiscoverySearchResponse envelope =
                new DiscoverySearchResponse(items, new SearchFiltersMeta(applied, buildAvailableFacets()));

        return new SearchResult(pageResult, envelope);
    }

    @Transactional(readOnly = true)
    public CreatorResponse get(AuthPrincipal principal, String creatorId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        CreatorProfile profile = requireDiscoverableProfile(creatorId);
        return toResponseForWorkspace(workspace, profile);
    }

    @Transactional(readOnly = true)
    public CreatorPublicProfileResponse getPublicProfile(AuthPrincipal principal, String usernameOrId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        CreatorProfile profile = resolveDiscoverableProfile(usernameOrId);
        List<PlatformStat> platforms = platformStatRepository.findByCreatorProfileId(profile.getId());
        boolean saved =
                savedCreatorRepository
                        .findByWorkspaceIdAndCreatorProfileId(workspace.getId(), profile.getId())
                        .map(SavedCreator::isSaved)
                        .orElse(false);

        CreatorScore score =
                creatorScoreRepository
                        .findFirstByCreatorProfileIdOrderByTimeDesc(profile.getId())
                        .orElse(null);

        long completedCampaigns =
                collaborationRepository
                        .findByCreatorIdAndStatus(profile.getUserId(), CollaborationStatus.COMPLETED)
                        .size();

        return new CreatorPublicProfileResponse(
                profile.getId(),
                profile.getUsername(),
                profile.getDisplayName(),
                profile.getBio(),
                profile.getAvatarUrl(),
                profile.getCoverImageUrl(),
                JsonLists.stringListFromJson(profile.getCategoriesJson()),
                JsonLists.stringListFromJson(profile.getLanguagesJson()),
                profile.getCity(),
                platforms.stream().map(CreatorMapper::toPlatformResponse).toList(),
                profile.getTotalFollowers(),
                profile.getEngagementRate(),
                buildScores(score),
                profile.getRateMin(),
                profile.getRateMax(),
                profile.getCurrency(),
                profile.isVerified(),
                profile.isDiscoverable(),
                completedCampaigns,
                // H-22: previously score.getQualityScore() -- the AI/algorithmic quality score,
                // not a rating at all -- was passed off as "avgRating". Real average of brand->
                // creator star reviews now; null (not a fabricated 0) when there are no reviews
                // yet, same "don't backfill missing data with zero" convention
                // PortfolioService#computeStats/DeliverableMetricService already use.
                computeAvgRating(profile.getUserId()),
                saved);
    }

    @Transactional(readOnly = true)
    public FeaturedResponse getFeatured(AuthPrincipal principal, String category, int limit) {
        brandContext.requireBrandWorkspace(principal);
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        Instant now = Instant.now();

        List<FeaturedCreator> rows =
                featuredCreatorRepository.findActiveFeatured(
                        blankToNull(category), now, PageRequest.of(0, safeLimit * 3));

        if (rows.isEmpty()) {
            return buildAlgorithmicFeatured(safeLimit, blankToNull(category));
        }

        Map<String, List<FeaturedCreator>> byCategory =
                rows.stream()
                        .collect(
                                Collectors.groupingBy(
                                        FeaturedCreator::getFeaturedCategory,
                                        LinkedHashMap::new,
                                        Collectors.toList()));

        List<FeaturedSection> sections = new ArrayList<>();
        for (Map.Entry<String, List<FeaturedCreator>> entry : byCategory.entrySet()) {
            List<CreatorProfile> profiles =
                    loadDiscoverableProfiles(
                            entry.getValue().stream()
                                    .map(FeaturedCreator::getCreatorProfileId)
                                    .limit(safeLimit)
                                    .toList());
            if (!profiles.isEmpty()) {
                sections.add(
                        new FeaturedSection(
                                entry.getKey(),
                                titleForCategory(entry.getKey()),
                                mapResponses(principal, profiles)));
            }
        }
        return new FeaturedResponse(sections);
    }

    @Transactional(readOnly = true)
    public SimilarCreatorsResponse getSimilar(AuthPrincipal principal, String username, int limit) {
        brandContext.requireBrandWorkspace(principal);
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        CreatorProfile source = requireDiscoverableByUsername(username);

        long minFollowers = Math.max(1, Math.round(source.getTotalFollowers() * 0.6));
        long maxFollowers = Math.max(minFollowers, Math.round(source.getTotalFollowers() * 1.4));
        List<String> sourceCategories = JsonLists.stringListFromJson(source.getCategoriesJson());

        Specification<CreatorProfile> spec =
                CreatorProfileSpecifications.combine(
                        CreatorProfileSpecifications.followersBetween(minFollowers, maxFollowers),
                        CreatorProfileSpecifications.excludeId(source.getId()));

        List<CreatorProfile> candidates =
                creatorProfileRepository
                        .findAll(
                                spec,
                                PageRequest.of(
                                        0,
                                        safeLimit * 4,
                                        Sort.by(Sort.Direction.DESC, "engagementRate")))
                        .getContent();

        List<SimilarCreator> ranked =
                candidates.stream()
                        .map(candidate -> scoreSimilarity(source, candidate, sourceCategories))
                        .sorted(Comparator.comparingDouble(SimilarCreator::matchScore).reversed())
                        .limit(safeLimit)
                        .toList();

        return new SimilarCreatorsResponse(ranked);
    }

    @Transactional(readOnly = true)
    public CreatorSuggestionsResponse suggest(
            AuthPrincipal principal, CreatorSuggestionRequest request) {
        brandContext.requireBrandWorkspace(principal);
        List<String> inferredCategories = inferCategories(request.campaignGoals(), request.targetAudience());
        List<String> platformList =
                request.platforms() != null
                        ? request.platforms().stream()
                                .map(p -> p.toUpperCase(Locale.ROOT))
                                .toList()
                        : List.of();

        int budget = request.budget() != null ? request.budget() : 0;
        BigDecimal maxRate = budget > 0 ? BigDecimal.valueOf(budget) : null;

        Specification<CreatorProfile> spec =
                CreatorProfileSpecifications.combine(
                        CreatorProfileSpecifications.hasCategories(inferredCategories),
                        CreatorProfileSpecifications.hasPlatforms(platformList),
                        CreatorProfileSpecifications.rateOverlap(null, maxRate));

        List<CreatorProfile> candidates =
                creatorProfileRepository
                        .findAll(
                                spec,
                                PageRequest.of(
                                        0, 30, Sort.by(Sort.Direction.DESC, "engagementRate")))
                        .getContent();

        if (candidates.isEmpty() && !inferredCategories.isEmpty()) {
            candidates =
                    creatorProfileRepository
                            .findAll(
                                    CreatorProfileSpecifications.combine(
                                            CreatorProfileSpecifications.hasCategories(inferredCategories)),
                                    PageRequest.of(
                                            0, 30, Sort.by(Sort.Direction.DESC, "engagementRate")))
                            .getContent();
        }

        Map<String, CreatorScores> scoresByCreator =
                loadScoresByCreator(candidates.stream().map(CreatorProfile::getId).toList());

        List<CreatorSuggestionItem> suggestions =
                candidates.stream()
                        .map(
                                profile ->
                                        toSuggestionItem(
                                                principal, profile, inferredCategories, budget, scoresByCreator))
                        .sorted(Comparator.comparingDouble(CreatorSuggestionItem::matchScore).reversed())
                        .limit(10)
                        .toList();

        return new CreatorSuggestionsResponse(suggestions);
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
        try {
            collaborationRepository.save(collaboration);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(
                    "COLLABORATION_EXISTS",
                    "This creator has already been invited to this campaign",
                    HttpStatus.CONFLICT);
        }
        return new InviteResponse(
                collaboration.getId(),
                collaboration.getStatus().name(),
                collaboration.getCreatedAt());
    }

    private CreatorResponse toResponseForWorkspace(Workspace workspace, CreatorProfile profile) {
        List<PlatformStat> platforms = platformStatRepository.findByCreatorProfileId(profile.getId());
        boolean saved =
                savedCreatorRepository
                        .findByWorkspaceIdAndCreatorProfileId(workspace.getId(), profile.getId())
                        .map(SavedCreator::isSaved)
                        .orElse(false);
        CreatorScores scores = loadScoresByCreator(List.of(profile.getId())).get(profile.getId());
        // M-2 (BrandF.md §87): this is the single-profile read (GET /creators/{id}, the brand
        // profile detail view) — the one surface where hydrating the real portfolio is worth the
        // extra read, unlike the batch/list paths elsewhere in this file.
        return CreatorMapper.toResponse(
                profile, platforms, saved, scores, portfolioService.getVisiblePinnedPosts(profile));
    }

    private List<CreatorResponse> mapResponses(AuthPrincipal principal, List<CreatorProfile> profiles) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
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
        Map<String, CreatorScores> scoresByCreator = loadScoresByCreator(profileIds);
        return profiles.stream()
                .map(
                        p ->
                                CreatorMapper.toResponse(
                                        p,
                                        platformsByCreator.getOrDefault(p.getId(), List.of()),
                                        savedIds.contains(p.getId()),
                                        scoresByCreator.get(p.getId())))
                .toList();
    }

    private FeaturedResponse buildAlgorithmicFeatured(int limit, String categoryFilter) {
        List<FeaturedSection> sections = new ArrayList<>();
        if (categoryFilter == null || "rising_star".equals(categoryFilter)) {
            sections.add(
                    algorithmicSection(
                            "rising_star",
                            CreatorProfileSpecifications.combine(
                                    CreatorProfileSpecifications.followersBetween(50_000L, 300_000L)),
                            Sort.by(Sort.Direction.DESC, "engagementRate"),
                            limit));
        }
        if (categoryFilter == null || "editors_pick".equals(categoryFilter)) {
            sections.add(
                    algorithmicSection(
                            "editors_pick",
                            CreatorProfileSpecifications.combine(
                                    CreatorProfileSpecifications.verifiedOnly(true)),
                            Sort.by(Sort.Direction.DESC, "totalFollowers"),
                            limit));
        }
        if (categoryFilter == null || "top_fitness".equals(categoryFilter)) {
            sections.add(
                    algorithmicSection(
                            "top_fitness",
                            CreatorProfileSpecifications.combine(
                                    CreatorProfileSpecifications.hasCategories(List.of("fitness", "health"))),
                            Sort.by(Sort.Direction.DESC, "totalFollowers"),
                            limit));
        }
        if (categoryFilter == null || "top_creators".equals(categoryFilter)) {
            sections.add(
                    algorithmicSection(
                            "top_creators",
                            CreatorProfileSpecifications.discoverable(),
                            Sort.by(Sort.Direction.DESC, "totalFollowers"),
                            limit));
        }
        return new FeaturedResponse(sections.stream().filter(s -> !s.creators().isEmpty()).toList());
    }

    private FeaturedSection algorithmicSection(
            String category, Specification<CreatorProfile> spec, Sort sort, int limit) {
        List<CreatorProfile> profiles =
                creatorProfileRepository.findAll(spec, PageRequest.of(0, limit, sort)).getContent();
        Map<String, CreatorScores> scoresByCreator =
                loadScoresByCreator(profiles.stream().map(CreatorProfile::getId).toList());
        return new FeaturedSection(category, titleForCategory(category), profiles.stream()
                .map(p -> CreatorMapper.toResponse(p, List.of(), false, scoresByCreator.get(p.getId())))
                .toList());
    }

    private List<CreatorProfile> loadDiscoverableProfiles(List<String> profileIds) {
        if (profileIds.isEmpty()) {
            return List.of();
        }
        return creatorProfileRepository.findAllById(profileIds).stream()
                .filter(CreatorProfile::isDiscoverable)
                .toList();
    }

    private SimilarCreator scoreSimilarity(
            CreatorProfile source, CreatorProfile candidate, List<String> sourceCategories) {
        List<String> candidateCategories = JsonLists.stringListFromJson(candidate.getCategoriesJson());
        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        if (categoryOverlap(sourceCategories, candidateCategories)) {
            score += 0.45;
            reasons.add("same_niche");
        }
        if (source.getCity() != null
                && candidate.getCity() != null
                && source.getCity().equalsIgnoreCase(candidate.getCity())) {
            score += 0.25;
            reasons.add("same_location");
        }
        double followerDelta =
                Math.abs(source.getTotalFollowers() - candidate.getTotalFollowers())
                        / (double) Math.max(source.getTotalFollowers(), 1);
        if (followerDelta <= 0.3) {
            score += 0.2;
            reasons.add("similar_followers");
        }
        if (candidate.getEngagementRate() != null
                && source.getEngagementRate() != null
                && candidate.getEngagementRate().compareTo(BigDecimal.valueOf(3)) >= 0) {
            score += 0.1;
            reasons.add("high_engagement");
        }

        return new SimilarCreator(
                candidate.getId(),
                candidate.getUsername(),
                candidate.getDisplayName(),
                candidate.getAvatarUrl(),
                candidate.getTotalFollowers(),
                candidate.getEngagementRate(),
                roundScore(score),
                reasons);
    }

    private CreatorSuggestionItem toSuggestionItem(
            AuthPrincipal principal,
            CreatorProfile profile,
            List<String> inferredCategories,
            int budget,
            Map<String, CreatorScores> scoresByCreator) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        List<PlatformStat> platforms = platformStatRepository.findByCreatorProfileId(profile.getId());
        boolean saved =
                savedCreatorRepository
                        .findByWorkspaceIdAndCreatorProfileId(workspace.getId(), profile.getId())
                        .map(SavedCreator::isSaved)
                        .orElse(false);
        CreatorResponse creator =
                CreatorMapper.toResponse(profile, platforms, saved, scoresByCreator.get(profile.getId()));

        List<String> profileCategories = JsonLists.stringListFromJson(profile.getCategoriesJson());
        double score = 0.5;
        List<String> reasons = new ArrayList<>();
        if (categoryOverlap(inferredCategories, profileCategories)) {
            score += 0.25;
            reasons.add("Strong niche overlap with your campaign goals");
        }
        if (profile.getEngagementRate() != null
                && profile.getEngagementRate().compareTo(BigDecimal.valueOf(4)) >= 0) {
            score += 0.15;
            reasons.add("High engagement rate indicates authentic audience");
        }
        if (profile.isVerified()) {
            score += 0.1;
            reasons.add("Verified creator with platform trust signals");
        }

        int estimatedCost =
                profile.getRateMin() != null
                        ? profile.getRateMin().intValue()
                        : profile.getRateMax() != null ? profile.getRateMax().intValue() : 0;
        if (budget > 0 && estimatedCost > budget) {
            score -= 0.2;
            reasons.add("Estimated cost may exceed your stated budget");
        } else if (budget > 0) {
            reasons.add("Fits within your campaign budget range");
        }

        long estimatedReach = Math.round(profile.getTotalFollowers() * 0.35);
        return new CreatorSuggestionItem(
                creator, roundScore(score), reasons, estimatedReach, estimatedCost);
    }

    private AvailableFiltersMeta buildAvailableFacets() {
        List<CreatorProfile> discoverable =
                creatorProfileRepository
                        .findAll(
                                CreatorProfileSpecifications.discoverable(),
                                PageRequest.of(0, 5_000, Sort.unsorted()))
                        .getContent();

        Map<String, Long> categoryCounts = new HashMap<>();
        Map<String, Long> followerCounts = new LinkedHashMap<>();
        for (FollowerRangeBucket bucket : FOLLOWER_RANGE_BUCKETS) {
            followerCounts.put(bucket.range(), 0L);
        }

        for (CreatorProfile profile : discoverable) {
            for (String category : JsonLists.stringListFromJson(profile.getCategoriesJson())) {
                String key = category.toLowerCase(Locale.ROOT);
                categoryCounts.merge(key, 1L, Long::sum);
            }
            for (FollowerRangeBucket bucket : FOLLOWER_RANGE_BUCKETS) {
                if (profile.getTotalFollowers() >= bucket.min()
                        && profile.getTotalFollowers() <= bucket.max()) {
                    followerCounts.merge(bucket.range(), 1L, Long::sum);
                    break;
                }
            }
        }

        List<CategoryFacet> categories =
                categoryCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(20)
                        .map(e -> new CategoryFacet(e.getKey(), e.getValue()))
                        .toList();

        List<FollowerRangeFacet> followerRanges =
                followerCounts.entrySet().stream()
                        .map(e -> new FollowerRangeFacet(e.getKey(), e.getValue()))
                        .toList();

        return new AvailableFiltersMeta(categories, followerRanges);
    }

    private Map<String, Object> buildAppliedFilters(
            String q,
            List<String> categories,
            List<String> languages,
            Long minFollowers,
            Long maxFollowers,
            BigDecimal minEngagement,
            BigDecimal maxEngagement,
            BigDecimal minRate,
            BigDecimal maxRate,
            String city,
            List<String> platforms,
            Boolean verified) {
        Map<String, Object> applied = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            applied.put("q", q.trim());
        }
        if (!categories.isEmpty()) {
            applied.put("categories", categories);
        }
        if (!languages.isEmpty()) {
            applied.put("languages", languages);
        }
        if (minFollowers != null) {
            applied.put("followers_min", minFollowers);
        }
        if (maxFollowers != null) {
            applied.put("followers_max", maxFollowers);
        }
        if (minEngagement != null) {
            applied.put("engagement_min", minEngagement);
        }
        if (maxEngagement != null) {
            applied.put("engagement_max", maxEngagement);
        }
        if (minRate != null) {
            applied.put("rate_min", minRate);
        }
        if (maxRate != null) {
            applied.put("rate_max", maxRate);
        }
        if (city != null && !city.isBlank()) {
            applied.put("location", city.trim());
        }
        if (!platforms.isEmpty()) {
            applied.put("platforms", platforms);
        }
        if (Boolean.TRUE.equals(verified)) {
            applied.put("verified_only", true);
        }
        return applied;
    }

    private List<String> inferCategories(String campaignGoals, String targetAudience) {
        String combined =
                ((campaignGoals != null ? campaignGoals : "")
                                + " "
                                + (targetAudience != null ? targetAudience : ""))
                        .toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String keyword : SUGGESTION_NICHE_KEYWORDS) {
            if (combined.contains(keyword)) {
                matches.add(keyword);
            }
        }
        return matches;
    }

    // Backs GET /creators/profile/{usernameOrId} (CreatorController.getPublicProfile), the single
    // API call behind the one /brand/creators/:id page every "view creator" link in the app routes
    // through. Every CreatorProfile#id AND every User#id are 26-char ULIDs from the same generator
    // (Ulids#newUlid) -- disjoint id spaces in principle, but not shape-distinguishable -- and
    // UsernameUtils#isValid's regex (^[a-z0-9][a-z0-9_]{1,28}[a-z0-9]$) matches any lowercased
    // alphanumeric string of length 3-30, so a real ULID (profile id OR user id) always passes
    // isValid() too. Sniffing "is this a username, a profile id, or a user id" from shape alone is
    // therefore not possible -- try each id-typed repository lookup in turn before falling back to
    // username. The real live callers of this page pass two different id shapes:
    //   - creator-discovery.tsx's search grid and this page's own Similar Creators grid both pass a
    //     CreatorProfile id (CreatorResponse#id / SimilarCreator#id are CreatorProfile.getId()).
    //   - brand-campaign-detail.tsx's bids tab passes a User id: DealService's Counterparty.id (->
    //     Deal#counterpartyId -> dealToBidView -> creator.id) is collaboration.getCreatorId(), a
    //     Collaboration.creatorId / users.id, never a CreatorProfile id.
    // (command-bar.tsx's "Recent" creator shortcuts are demo-only fixtures explicitly gated out of
    // live mode -- see that file's own comment -- so they never reach this endpoint live.)
    // The userId branch mirrors the identical id/userId ambiguity fix already applied to
    // requireDiscoverableProfile below. All three branches keep the exact discoverable +
    // not-suspended filtering requireDiscoverableProfile/requireDiscoverableByUsername already
    // enforce.
    private CreatorProfile resolveDiscoverableProfile(String usernameOrId) {
        return creatorProfileRepository
                .findByIdAndDiscoverableTrue(usernameOrId)
                .or(() ->
                        creatorProfileRepository
                                .findByUserId(usernameOrId)
                                .filter(CreatorProfile::isDiscoverable))
                .or(() ->
                        creatorProfileRepository
                                .findByUsernameIgnoreCase(usernameOrId)
                                .filter(CreatorProfile::isDiscoverable))
                .filter(p -> !p.isSuspended())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));
    }

    // [SEC: Wave-1 S4-discovery] Both lookups below now also reject a suspended profile, not just
    // a non-discoverable one — CreatorProfile#suspend() (admin moderation, V38) never flips
    // `discoverable`, so without this a suspended creator stayed fully resolvable by
    // username/id here (portfolio view, save, and — critically — brand invite via `invite()`
    // above) even though CreatorProfileSpecifications.discoverable() now excludes them from
    // search/browse. Same CREATOR_NOT_FOUND shape as today so a suspended profile doesn't leak
    // through a different error/status.
    private CreatorProfile requireDiscoverableByUsername(String username) {
        return creatorProfileRepository
                .findByUsernameIgnoreCase(username)
                .filter(CreatorProfile::isDiscoverable)
                .filter(p -> !p.isSuspended())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));
    }

    private CreatorProfile requireDiscoverableProfile(String id) {
        return creatorProfileRepository
                .findByIdAndDiscoverableTrue(id)
                .or(() -> creatorProfileRepository.findByUserId(id).filter(CreatorProfile::isDiscoverable))
                .filter(p -> !p.isSuspended())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));
    }

    private static CreatorScores buildScores(CreatorScore score) {
        if (score == null) {
            return null;
        }
        // Priya ruling (BR-18, 2026-07-30): authenticity is the inverse of the fake-follower
        // suspicion score, not the raw value — see CreatorScoreMath#toAuthenticity javadoc. Null
        // in (never scored) stays null out; never fabricated as 0 or 100.
        return new CreatorScores(
                score.getQualityScore(),
                CreatorScoreMath.toAuthenticity(score.getFakeFollowerScore()),
                score.getBrandSafetyScore());
    }

    /**
     * BR-18 — one batch query (greatest-n-per-group over {@code idx_creator_scores_creator_time})
     * for every id in {@code profileIds}, never a per-creator lookup. Every call site below feeds
     * one of the four discovery endpoints ({@code /creators}, {@code /creators/{id}}, {@code
     * /creators/featured}, {@code /creators/suggestions}) that render a page of up to 100
     * creators — see TECH-STACK.md "Score Exposure". A creator with no row yet (never
     * scored) is simply absent from the returned map, so callers get {@code null} from {@code
     * .get(id)} and must NOT substitute a fabricated {@code BigDecimal.ZERO} for it (see {@code
     * AdminCreatorService#latestQualityScore} for the anti-pattern this deliberately avoids).
     */
    private Map<String, CreatorScores> loadScoresByCreator(List<String> profileIds) {
        if (profileIds.isEmpty()) {
            return Map.of();
        }
        return creatorScoreRepository.findLatestByCreatorProfileIdIn(profileIds).stream()
                .collect(
                        Collectors.toMap(
                                CreatorScore::getCreatorProfileId,
                                CreatorDiscoveryService::buildScores));
    }

    /**
     * H-22 — real average of brand->creator star reviews (mirrors {@code
     * PortfolioService#computeStats}'s identical calculation off the same {@link ReviewRepository
     * #findReceivedByCreatorUserId} query). Returns {@code null} rather than a fabricated 0 when
     * the creator has no reviews yet.
     */
    private BigDecimal computeAvgRating(String creatorUserId) {
        List<Review> reviews = reviewRepository.findReceivedByCreatorUserId(creatorUserId, ReviewerType.BRAND);
        if (reviews == null || reviews.isEmpty()) {
            return null;
        }
        double sum = reviews.stream().mapToInt(Review::getStars).sum();
        return BigDecimal.valueOf(sum / (double) reviews.size()).setScale(1, RoundingMode.HALF_UP);
    }

    private record FollowerRangeBucket(String range, long min, long max) {}

    private static boolean categoryOverlap(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> normalized =
                left.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        return right.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(
                        cat ->
                                normalized.contains(cat)
                                        || normalized.stream().anyMatch(n -> n.contains(cat) || cat.contains(n)));
    }

    private static String titleForCategory(String category) {
        return FEATURED_TITLES.getOrDefault(
                category, category.substring(0, 1).toUpperCase(Locale.ROOT) + category.substring(1));
    }

    private static double roundScore(double score) {
        return BigDecimal.valueOf(Math.min(score, 1.0))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> mergeCategoryFilters(String verticals, String categories) {
        List<String> merged = new ArrayList<>();
        merged.addAll(parseCsvLower(verticals));
        merged.addAll(parseCsvLower(categories));
        return merged.stream().distinct().toList();
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

    private static Sort toSort(String sortBy) {
        if (sortBy == null) {
            return Sort.by(Sort.Direction.DESC, "totalFollowers");
        }
        return switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "engagement" -> Sort.by(Sort.Direction.DESC, "engagementRate");
            case "rate", "price_low" -> Sort.by(Sort.Direction.ASC, "rateMin");
            case "price_high" -> Sort.by(Sort.Direction.DESC, "rateMax");
            // H-22 (documented limitation, not silently fabricated): "rating" cannot yet sort by
            // real average review stars -- that value is computed at read time per creator (see
            // computeAvgRating), not a persisted/indexed CreatorProfile column, so a DB-level
            // ORDER BY over it would need either a denormalized avg_rating column (kept in sync
            // by a job, mirroring engagementRate/totalFollowers) or a correlated subquery this
            // Specification/Sort-based search doesn't support today. Falls back to engagementRate
            // (closest available proxy for "well-regarded creator") rather than an unsorted list
            // -- follow-up scope once a denormalized rating column exists.
            case "rating" -> Sort.by(Sort.Direction.DESC, "engagementRate");
            case "relevance" -> Sort.by(Sort.Direction.DESC, "totalFollowers");
            default -> Sort.by(Sort.Direction.DESC, "totalFollowers");
        };
    }
}
