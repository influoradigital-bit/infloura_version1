package com.influora.web.dto.meera;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DTOs for the {@code /internal/meera/*} tool-call executor surface (Phase 4,
 * 02-API-CONTRACT-BRAND.md §3 / 11-AI-FLOW-DETAILED.md Flow 3).
 *
 * <p><b>Wire shape matches {@code influora-ai/app/clients/spring.py} exactly</b> (Domain D,
 * already built): the request body is the raw tool input AS-PROPOSED by Claude, with
 * {@code workspace_id} merged in by Python's tool loop — nothing else. The on-behalf human JWT,
 * idempotency key, and HMAC signature all travel as HEADERS
 * ({@code X-Onbehalf-Authorization}, {@code Idempotency-Key}, {@code X-Meera-Signature} /
 * {@code X-Meera-Timestamp} / {@code X-Meera-Nonce} / {@code X-Meera-Service-Token}), never as
 * body fields — the controller reads them via {@code @RequestHeader}, not from this DTO. Never a
 * chargeable {@code amount} field is trusted from this body on the write-tier requests
 * (Kabir G1 / MF-1) — only identifiers.
 */
public final class MeeraToolDtos {

    private MeeraToolDtos() {}

    // NOTE: the request body itself is bound as a plain Map<String, Object> in the controller —
    // Python's forward_payload is a flat, tool-specific map (`campaign_intent_id`, `product_price`,
    // ...) plus `workspace_id`, not a fixed shape a single record could describe across all 5
    // tools. Executors extract the fields they need from that map; no field on it is ever trusted
    // as an authoritative amount.

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShowCreatorsResult(List<CreatorSummary> creators) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreatorSummary(
            String creatorProfileId,
            String displayName,
            String city,
            List<String> categories,
            long totalFollowers,
            BigDecimal engagementRate,
            boolean verified) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CalculateBudgetResult(
            BigDecimal suggestedPoolTotal,
            BigDecimal suggestedPerCreatorRate,
            int suggestedCreatorCount,
            String currency,
            String rationale) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateCampaignResult(
            String campaignId, String campaignIntentId, String status, boolean replay) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestPaymentResult(
            String status,
            String campaignIntentId,
            BigDecimal serverAmount,
            String currency,
            String confirmActionUrl,
            boolean replay) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfirmLaunchResult(
            String campaignId, String status, int creatorsInvited, boolean replay) {}

    /** Generic error body for a tool-call rejected by {@code ToolCallValidator} before execution. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolRejection(String toolName, String reasonCode, String message) {}

    /**
     * Body for {@code POST /internal/meera/messages} — matches
     * {@code SpringInternalClient.persist_assistant_message} exactly:
     * {@code {conversationId, role, content, metadata}}. Deliberately no
     * {@code workspaceId} field — Python does not have it to send here (this callback fires from
     * the {@code /chat} SSE route, not a tool call with a forwarded workspace context); the
     * controller resolves the tenant by looking up the conversation itself via
     * {@code AiConversationRepository}, the same tenant-scoped-lookup discipline used everywhere
     * else (Guardrail 4). The on-behalf JWT is still required as a header
     * ({@code X-Onbehalf-Authorization}) and its {@code workspaceId} claim must match the
     * resolved conversation's workspace.
     */
    public record MessageWriteback(
            @NotBlank String conversationId,
            String role,
            @NotBlank String content,
            Map<String, Object> metadata) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageWritebackResult(String messageId) {}

    /**
     * Body for {@code POST /internal/meera/turns/release} — the Wave 2 round 2 refund route
     * (Kabir FAILs #1/#2 fix). Matches {@code SpringInternalClient.release_turn_credit} exactly:
     * {@code {conversationId, turnId}}. {@code turnId} MUST be the stream token's server-verified
     * {@code messageId} claim (influora-ai reads this from {@code verified.claims}, never from a
     * client-supplied {@code turn_id} body field — see {@code app/routes/chat.py}), since that is
     * the SAME value {@link AICreditService#tryConsumeForTurn} charged against at send and the
     * SAME value {@link MessageWriteback}'s {@code Idempotency-Key} uses — {@link
     * AICreditService#release}'s guard logic depends on all three referring to the same turn.
     * Deliberately no {@code workspaceId} field, same reasoning as {@link MessageWriteback}: the
     * tenant is resolved from {@code conversationId} and cross-checked against the on-behalf JWT
     * at the controller before this ever reaches the service layer.
     */
    public record ReleaseTurnRequest(@NotBlank String conversationId, @NotBlank String turnId) {}
}
