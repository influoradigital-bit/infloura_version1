package com.influora.web.dto.meera;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.influora.integration.ai.dto.AnalyzeSiteAiDtos;
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

    /**
     * {@code messageId} is the persisted USER message id — the {@code turn_id} the browser passes
     * on its own direct SSE connection to influora-ai (Priya's streaming-first architecture; see
     * {@code src/components/feature/meera/MeeraChatPanel.tsx}'s {@code handleLiveSend}).
     * {@code assistantMessageId} and {@code reply} are always {@code null} now that the assistant
     * turn is written back asynchronously by influora-ai's end-of-stream callback ({@link
     * com.influora.service.meera.MeeraSessionService#persistAssistantWriteback}) rather than
     * synchronously by this call — {@code @JsonInclude(NON_NULL)} omits both from the wire response
     * rather than sending literal JSON nulls. {@code workspaceId} is required by the browser to
     * build the SSE stream body ({@code chat.py} requires {@code workspace_id} and 403s a
     * token/body mismatch). {@code onBehalfToken} is the SECURITY FIX #1 per-turn on-behalf
     * credential ({@code docs/security/meera-onbehalf-auth-security-design.md} §2) — the browser
     * MUST forward this exact value as {@code onbehalf_jwt} in its SSE stream body instead of
     * reading a full access token out of {@code localStorage}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendTurnResponse(
            String messageId,
            String assistantMessageId,
            String streamToken,
            String streamUrl,
            int creditsRemaining,
            String reply,
            String workspaceId,
            String onBehalfToken) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StreamTokenResponse(String streamToken, String streamUrl, long expiresInSeconds) {}

    /**
     * F10 — one row of {@code GET /meera/sessions/{conversationId}/messages}. Shape matches {@code
     * src/lib/meera-api.ts}'s {@code getMessagesAfter} return type exactly ({@code {id, role,
     * content}}) — deliberately no extra fields (e.g. no {@code createdAt}) so this stays a strict
     * match to the frontend's already-declared contract rather than a superset it doesn't ask for.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageHistoryItem(String id, String role, String content) {}

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

    /**
     * Write-back for the Meera CHAT tool loop's LOCAL {@code analyze_site} tool (influora-ai's
     * {@code app/tools/loop.py} runs {@code perform_site_analysis} in-process for a fast reply and
     * never forwards it to Spring like every other tool — see {@code AnalyzeSiteTriggerService}
     * class javadoc). {@code data}/{@code error} deliberately reuse influora-ai's real {@code
     * /analyze-site} response shape ({@link AnalyzeSiteAiDtos}) rather than re-declaring it, since
     * {@code perform_site_analysis} is the SAME function backing both call sites — the shapes are
     * identical by construction.
     */
    public record AnalyzeSiteChatResult(
            @NotBlank String workspaceId,
            String url,
            boolean success,
            AnalyzeSiteAiDtos.Data data,
            AnalyzeSiteAiDtos.ErrorDetail error) {}
}
