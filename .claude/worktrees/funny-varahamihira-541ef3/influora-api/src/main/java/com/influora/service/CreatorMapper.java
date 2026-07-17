package com.influora.service;

import com.influora.common.JsonLists;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.web.dto.creator.CreatorDtos.CreatorResponse;
import com.influora.web.dto.creator.CreatorDtos.PlatformStatResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CreatorMapper {

    private CreatorMapper() {}

    public static CreatorResponse toResponse(
            CreatorProfile profile,
            List<PlatformStat> platforms,
            Boolean saved) {
        BigDecimal avgRate = averageRate(profile.getRateMin(), profile.getRateMax());
        List<PlatformStatResponse> platformDtos =
                platforms.stream().map(CreatorMapper::toPlatform).toList();
        return new CreatorResponse(
                profile.getId(),
                profile.getUserId(),
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
                Collections.emptyList(),
                saved);
    }

    public static Map<String, List<PlatformStat>> groupPlatforms(List<PlatformStat> all) {
        return all.stream().collect(Collectors.groupingBy(PlatformStat::getCreatorProfileId));
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
