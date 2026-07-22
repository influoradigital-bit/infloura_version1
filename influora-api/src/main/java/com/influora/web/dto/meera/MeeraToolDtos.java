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
            String rationale,
            /**
             * C1 (Kabir P1-B audit, condition 1): {@code "scraped"} when the {@code product_price}
             * this suggestion was derived from is a verified scraped fact (per the brand context's
             * {@code price_source}), {@code "inferred"} otherwise — including when no
             * {@code price_source} was supplied at all (fail safe: unknown provenance is never
             * treated as confirmed). Lets Meera say "based on an estimated price" instead of
             * quoting a guess as fact; never changes the math itself, which stays exactly as
             * before — this is provenance-awareness only, and the charged amount at commit is
             * still always independently re-derived server-side (see class javadoc).
             */
            String priceConfidence) {}

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

    /**
     * One deliverable's platform-verified performance numbers — Phase 2 item 2.2's {@code
     * get_campaign_performance} R-tier tool. PII-stripped by construction: no creator name/IG
     * handle/caption/any free-text field, only an opaque {@code milestoneId} + numeric metrics,
     * mirroring {@link CreatorSummary}'s precedent of exposing an opaque id but never a free-text
     * creator-authored field (Kabir's mandatory Phase-2 gate — no per-deliverable PII leak).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliverablePerformanceEntry(
            String milestoneId, Long reach, Long impressions, Long engagements) {}

    /**
     * {@code get_campaign_performance}'s result — the exact contract Priya ruled in
     * {@code wiki/build/phase2-priya-review.md} §2 Q2, reconciling Vikram's raw-aggregate design
     * with Ananya's card fields: every derived number ({@code roi}/{@code responseRate}/{@code
     * avgCreatorScore}) is SERVER-COMPUTED here, never left for the frontend or the model to
     * compute from two provenanced numbers (that would manufacture an orphaned figure the card
     * would present as fact — Ash's B1). {@code provenance} is a SINGLE top-level 2-state tag
     * (never per-field) because v1 surfaces PLATFORM_VERIFIED numbers only — every number that
     * reaches this DTO is, by construction, drawn from an authoritative server record (released
     * escrow, platform-verified deliverable metrics, UTM revenue, settled affiliate earnings), so
     * the tag is always {@code "PLATFORM_VERIFIED"} in v1 (Ash's Q1 ruling; {@code
     * "SELF_REPORTED"} is reserved for a future fast-follow that surfaces flagged self-reported
     * numbers, not used by this executor today). No {@code narrative} field — the one-sentence
     * summary is Meera's own LLM turn in the chat bubble, grounded on these numbers, never a
     * server string echoed back into this DTO (Priya/Ash Q2).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetCampaignPerformanceResult(
            String campaignId,
            long creatorCount,
            BigDecimal spendInr,
            Long verifiedReach,
            BigDecimal attributedRevenueInr,
            BigDecimal settledCommissionInr,
            // D1 (Priya impl-review): roi is null for zero-spend/no-revenue campaigns (a common
            // early-state). It MUST serialize as explicit JSON `null`, not be omitted by the
            // record-level NON_NULL, or the frontend guard (meera-api.ts `roi === null ||
            // typeof === 'number'`) fails on an absent field and StagePerformance spins forever.
            @JsonInclude(JsonInclude.Include.ALWAYS)
            BigDecimal roi,
            Double responseRate,
            Double avgCreatorScore,
            String provenance,
            List<DeliverablePerformanceEntry> deliverables) {}

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
