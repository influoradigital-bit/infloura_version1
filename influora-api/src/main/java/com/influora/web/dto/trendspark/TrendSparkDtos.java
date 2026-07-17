package com.influora.web.dto.trendspark;

import java.util.List;

/** Response shapes for {@code GET /api/v1/brand/trendspark/nudge} (Ananya, T7, wires this). */
public final class TrendSparkDtos {

    private TrendSparkDtos() {}

    public record NudgeResponse(
            String nudgeId,
            String mode, // SNAPSBY | OWN_CONTENT
            String campaignType,
            String trendText,
            String message,
            String messageSource, // AI | FALLBACK
            List<VideoCard> videos) {}

    public record VideoCard(String videoId, String title, String previewUrl, int priceInr) {}

    public record CallbackRequest(String videoId) {}
}
