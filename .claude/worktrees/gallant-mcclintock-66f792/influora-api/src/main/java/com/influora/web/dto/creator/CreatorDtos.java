package com.influora.web.dto.creator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CreatorDtos {

    private CreatorDtos() {}

    public record PlatformStatResponse(
            String platform,
            String handle,
            long followers,
            BigDecimal engagementRate,
            boolean isVerified,
            String profileUrl) {}

    public record CreatorResponse(
            String id,
            String userId,
            String username,
            String displayName,
            String bio,
            String avatarUrl,
            String coverImageUrl,
            String location,
            List<String> categories,
            List<String> languages,
            List<String> contentStyles,
            List<PlatformStatResponse> platforms,
            long totalFollowers,
            BigDecimal engagementRate,
            BigDecimal averageRate,
            String currency,
            boolean isVerified,
            List<PortfolioItemResponse> portfolioItems,
            Boolean saved) {}

    public record PortfolioItemResponse(
            String id,
            String title,
            String description,
            String thumbnailUrl,
            String mediaUrl,
            String platform) {}

    public record SaveRequest(@NotNull Boolean saved) {}

    public record SaveResponse(boolean saved) {}

    public record InviteRequest(@NotBlank String campaignId, String message) {}

    public record InviteResponse(String collaborationId, String status, Instant createdAt) {}
}
