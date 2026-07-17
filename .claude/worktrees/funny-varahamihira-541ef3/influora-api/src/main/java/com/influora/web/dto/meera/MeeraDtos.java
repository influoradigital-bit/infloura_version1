package com.influora.web.dto.meera;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTOs for the Meera public surface (session start, send-turn, credit status, brand profile) and
 * the site-analysis callback. Per Phase 2 scope: no {@code ToolCallRequest},
 * {@code CreateCampaignRequest}, or {@code RequestPaymentRequest} — those belong to Phase 4's
 * tool executors.
 */
public final class MeeraDtos {

    private MeeraDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreditsSummary(int remaining, boolean unlimited) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionStartResponse(
            String conversationId,
            String status,
            String brandProfileStatus,
            CreditsSummary credits) {}

    /** Body for {@code POST /meera/sessions/{conversationId}/messages}. */
    public record SendTurnRequest(@NotBlank @Size(max = 8000) String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendTurnResponse(
            String messageId,
            String assistantMessageId,
            String streamToken,
            String streamUrl,
            int creditsRemaining,
            String reply) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StreamTokenResponse(String streamToken, String streamUrl, long expiresInSeconds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreditStatusResponse(
            int creditsRemaining,
            int monthlyAllotment,
            boolean unlimited,
            Instant unlimitedUntil,
            LocalDate cycleStart,
            String state) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrandProfileResponse(
            String workspaceId,
            String websiteUrl,
            String analysisStatus,
            List<String> nicheTags,
            Object productCatalog,
            String analysisError) {}

    /**
     * Callback body the website analyzer (Python/Domain D) posts back with scrape results.
     * Internal-only in practice (mesh boundary enforced at the controller/filter-chain level,
     * not by this DTO), but kept in the public {@code web/dto/meera} package per the manifest.
     */
    public record AnalyzeSiteCallback(
            @NotBlank String workspaceId,
            String status,
            List<Map<String, Object>> productCatalog,
            Map<String, Object> brandAesthetic,
            Map<String, Object> toneProfile,
            List<String> nicheTags,
            List<String> competitorUrls,
            String error) {}
}
