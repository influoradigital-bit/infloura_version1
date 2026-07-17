package com.influora.web.dto.creator;

import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class DiscoveryDtos {

    private DiscoveryDtos() {}

    public record CategoryFacet(String id, long count) {}

    public record FollowerRangeFacet(String range, long count) {}

    public record SearchFiltersMeta(
            Map<String, Object> applied,
            AvailableFiltersMeta available) {}

    public record AvailableFiltersMeta(
            List<CategoryFacet> categories, List<FollowerRangeFacet> followerRanges) {}

    public record DiscoverySearchResponse(
            List<CreatorDtos.CreatorResponse> creators,
            SearchFiltersMeta filters) {}

    public record FeaturedSection(
            String category, String title, List<CreatorDtos.CreatorResponse> creators) {}

    public record FeaturedResponse(List<FeaturedSection> featured) {}

    public record SimilarCreator(
            String id,
            String username,
            String displayName,
            String avatarUrl,
            long totalFollowers,
            BigDecimal engagementRate,
            double matchScore,
            List<String> matchReasons) {}

    public record SimilarCreatorsResponse(List<SimilarCreator> similar) {}

    public record CreatorSuggestionRequest(
            String campaignGoals,
            String targetAudience,
            @Min(0) Integer budget,
            List<String> platforms) {}

    public record CreatorSuggestionItem(
            CreatorDtos.CreatorResponse creator,
            double matchScore,
            List<String> reasons,
            long estimatedReach,
            int estimatedCost) {}

    public record CreatorSuggestionsResponse(List<CreatorSuggestionItem> suggestions) {}

    public record CreatorScores(
            BigDecimal quality, BigDecimal authenticity, BigDecimal brandSafety) {}

    public record CreatorPublicProfileResponse(
            String id,
            String username,
            String displayName,
            String bio,
            String profilePhoto,
            String coverPhoto,
            List<String> categories,
            List<String> languages,
            String city,
            List<CreatorDtos.PlatformStatResponse> platforms,
            long totalFollowers,
            BigDecimal engagementRate,
            CreatorScores scores,
            BigDecimal rateMin,
            BigDecimal rateMax,
            String currency,
            boolean isVerified,
            boolean discoverable,
            long completedCampaigns,
            BigDecimal avgRating,
            Boolean saved) {}
}
