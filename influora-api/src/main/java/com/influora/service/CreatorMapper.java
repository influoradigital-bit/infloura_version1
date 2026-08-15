package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.web.dto.creator.CreatorDtos;
import com.influora.web.dto.creator.CreatorDtos.CreatorResponse;
import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
import com.influora.web.dto.creator.CreatorDtos.PortfolioItemResponse;
import com.influora.web.dto.creator.DiscoveryDtos;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPinnedPost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CreatorMapper {

    private CreatorMapper() {}

    /**
     * M-2 (BrandF.md §87): {@code portfolioItems} used to be hardcoded {@code
     * Collections.emptyList()} unconditionally. Batch/list callers (search results) still pass
     * {@link Collections#emptyList()} deliberately — a full portfolio isn't shown on a search
     * card, and hydrating it per-row would be an N+1 across the results page — only the
     * single-profile read path (CreatorDiscoveryService#toResponseForWorkspace) passes real data.
     */
    public static CreatorResponse toResponse(
            CreatorProfile profile,
            List<PlatformStat> platforms,
            Boolean saved,
            DiscoveryDtos.CreatorScores scores) {
        return toResponse(profile, platforms, saved, scores, Collections.emptyList());
    }

    public static CreatorResponse toResponse(
            CreatorProfile profile,
            List<PlatformStat> platforms,
            Boolean saved,
            DiscoveryDtos.CreatorScores scores,
            List<PortfolioPinnedPost> pinnedPosts) {
        BigDecimal avgRate = averageRate(profile.getRateMin(), profile.getRateMax());
        List<PlatformStatResponse> platformDtos =
                platforms.stream().map(CreatorMapper::toPlatform).toList();
        List<PortfolioItemResponse> portfolioItems =
                pinnedPosts.stream().map(CreatorMapper::toPortfolioItem).toList();
        return new CreatorResponse(
                profile.getId(),
                profile.getUserId(),
                profile.getUsername(),
                profile.getDisplayName(),
                profile.getBio(),
                profile.getAvatarUrl(),
                profile.getCoverImageUrl(),
                profile.getCity(),
                JsonLists.stringListFromJson(profile.getCategoriesJson()),
                JsonLists.stringListFromJson(profile.getLanguagesJson()),
                JsonLists.stringListFromJson(profile.getContentStylesJson()),
                platformDtos,
                profile.getTotalFollowers(),
                profile.getEngagementRate(),
                avgRate,
                profile.getCurrency(),
                profile.isVerified(),
                portfolioItems,
                saved,
                scores);
    }

    /**
     * {@link PortfolioPinnedPost} (the pinned-post shape the creator's own portfolio page uses)
     * has no title/description split — {@code caption} is one free-text field. Mapped into
     * {@code title} (so the card has something to head with) and left out of {@code description}
     * rather than duplicating the same text into both fields.
     */
    private static PortfolioItemResponse toPortfolioItem(PortfolioPinnedPost post) {
        return new PortfolioItemResponse(
                post.id(), post.caption(), null, post.thumbnailUrl(), post.embedUrl(), post.platform());
    }

    public static Map<String, List<PlatformStat>> groupPlatforms(List<PlatformStat> all) {
        return all.stream().collect(Collectors.groupingBy(PlatformStat::getCreatorProfileId));
    }

    public static CreatorDtos.PlatformStatResponse toPlatformResponse(PlatformStat ps) {
        return toPlatform(ps);
    }

    private static PlatformStatResponse toPlatform(PlatformStat ps) {
        return new PlatformStatResponse(
                ps.getPlatform(),
                ps.getHandle() != null ? ps.getHandle() : "",
                ps.getFollowers(),
                ps.getEngagementRate(),
                ps.isVerified(),
                ps.getProfileUrl());
    }

    private static BigDecimal averageRate(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return null;
        }
        if (min == null) {
            return max;
        }
        if (max == null) {
            return min;
        }
        return min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }
}
