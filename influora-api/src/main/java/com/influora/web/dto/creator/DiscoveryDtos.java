package com.influora.web.dto.creator;

import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.List;
import com.influora.web.dto.portfolio.PortfolioDtos;
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
            List<String> matchReasons,
            // EV-008: same provenance field as CreatorResponse.followersSource.
            String followersSource) {}

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
            Boolean saved,
            // EV-008: VERIFIED | IMPORTED | NONE - what totalFollowers/engagementRate are made of
            // (CreatorProfile.followersSource, F-0965). The brand profile page labels IMPORTED.
            String followersSource,
            /**
             * F-0972/F-0974 -- everything the creator authored in their portfolio editor,
             * assembled by {@code PortfolioService#getForBrand} under the same visibility
             * rules the public page obeys. ONE nested field on purpose: this record had
             * already grown a column at a time three times (completedCampaigns/avgRating,
             * scores, followersSource), and flattening eight more would hand-duplicate
             * every visibility rule that lives in PortfolioService#assemble.
             *
             * <p>Never null in practice -- a creator who never opened the editor still gets
             * PortfolioVisibility.defaults() -- but consumers should treat its list fields
             * as possibly empty and its {@code stats} as possibly null (F-0589).
             */
            PortfolioDtos.PortfolioBrandView portfolio) {}
}
