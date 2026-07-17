package com.influora.service.meera.tool;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.MeeraToolCall;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.domain.enums.ToolResultRefType;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.service.AmountDerivationService;
import com.influora.service.AmountDerivationService.DerivedAmount;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import com.influora.web.dto.meera.MeeraToolDtos.RequestPaymentResult;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-tier executor (06-MEERA-PERMISSIONS-MATRIX.md row 4 + Ruling A/B; 11-AI-FLOW-DETAILED.md Flow
 * 3): "Re-derive amount server-side, create PENDING human-confirm action; DO NOT move money;
 * return {status: PENDING_CONFIRM}."
 *
 * <p><b>The money guarantee, concretely (per Flow 3):</b> this executor NEVER debits anything.
 * {@code input.get("amount")} / {@code input.get("display_amount_hint")} are read ONLY to detect
 * tampering/drift against the server-derived figure (via
 * {@link AmountDerivationService#exceedsDriftTolerance}) — the returned {@code serverAmount} is
 * always {@code DerivedAmount.totalAmount()}, never the AI's number. If the AI's hint drifts
 * beyond tolerance, the call is rejected with {@code 409 AMOUNT_MISMATCH} and an audit row is
 * written; it is never "corrected" and silently allowed through. The actual money movement is a
 * wholly separate leg: the browser calls the public {@code POST /brand/escrow/fund} on the human
 * JWT — this class has no code path that touches a wallet, escrow hold, or ledger.
 *
 * <p>Idempotent via {@link IdempotencyService#executeOnce} (V15 {@code idempotency_keys},
 * insert-first-wins) — a concurrent double-submit is arbitrated by the database's
 * {@code UNIQUE(idempotency_key)}, not by a check-then-act read against {@code meera_tool_calls}
 * ([SEC: LB-3]). {@code meera_tool_calls} (V14) remains the result ledger consulted first so a
 * replay is served without re-deriving the amount.
 */
@Service
public class RequestPaymentExecutor {

    private static final String IDEMPOTENCY_SCOPE = "meera.request_payment";

    private final AmountDerivationService amountDerivationService;
    private final MeeraToolCallRepository toolCallRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    public RequestPaymentExecutor(
            AmountDerivationService amountDerivationService,
            MeeraToolCallRepository toolCallRepository,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService) {
        this.amountDerivationService = amountDerivationService;
        this.toolCallRepository = toolCallRepository;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
    }

    public RequestPaymentResult execute(
            String workspaceId, String conversationId, String idempotencyKey, Map<String, Object> input) {
        RequestPaymentResult replay = replayIfPresent(workspaceId, idempotencyKey);
        if (replay != null) {
            return replay;
        }

        try {
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    workspaceId,
                    IDEMPOTENCY_SCOPE,
                    () -> doExecute(workspaceId, conversationId, idempotencyKey, input));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            RequestPaymentResult won = replayIfPresent(workspaceId, idempotencyKey);
            if (won != null) {
                return won;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This request is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    private RequestPaymentResult replayIfPresent(String workspaceId, String idempotencyKey) {
        var existing = toolCallRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return null;
        }
        MeeraToolCall prior = existing.get();
        if (!prior.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_TENANT_MISMATCH",
                    "Idempotency key belongs to a different workspace",
                    HttpStatus.CONFLICT);
        }
        return new RequestPaymentResult(
                "PENDING_CONFIRM",
                prior.getResultRefType() == ToolResultRefType.INTENT ? prior.getResultRefId() : null,
                prior.getServerAmount(),
                "INR",
                confirmActionUrl(prior.getResultRefId()),
                true);
    }

    @Transactional
    protected RequestPaymentResult doExecute(
            String workspaceId, String conversationId, String idempotencyKey, Map<String, Object> input) {
        String campaignIntentId = stringArg(input, "campaign_intent_id");
        if (campaignIntentId == null || campaignIntentId.isBlank()) {
            throw new ApiException(
                    "CAMPAIGN_INTENT_ID_REQUIRED",
                    "request_payment requires campaign_intent_id",
                    HttpStatus.BAD_REQUEST);
        }

        // [SEC: Kabir G1] amount/display_amount_hint are read ONLY for drift detection below —
        // never used as the chargeable figure.
        BigDecimal aiProposedHint = decimalArg(input, "display_amount_hint");
        if (aiProposedHint == null) {
            aiProposedHint = decimalArg(input, "amount");
        }

        DerivedAmount derived = amountDerivationService.deriveForCampaignIntent(workspaceId, campaignIntentId);

        if (amountDerivationService.exceedsDriftTolerance(derived.totalAmount(), aiProposedHint)) {
            auditLogService.recordToolCall(
                    workspaceId,
                    "request_payment",
                    "C",
                    AuditLogService.OUTCOME_REJECTED,
                    "AMOUNT_MISMATCH",
                    idempotencyKey,
                    derived.totalAmount(),
                    Map.of(
                            "campaignIntentId", campaignIntentId,
                            "aiProposedHint", String.valueOf(aiProposedHint)));
            throw new ApiException(
                    "AMOUNT_MISMATCH",
                    "AI-proposed amount drifts beyond tolerance from the server-derived amount",
                    HttpStatus.CONFLICT);
        }

        toolCallRepository.save(
                MeeraToolCall.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .conversationId(conversationId)
                        .toolName(MeeraToolName.request_payment)
                        .idempotencyKey(idempotencyKey)
                        .status(ToolCallStatus.EXECUTED)
                        .resultRefType(ToolResultRefType.INTENT)
                        .resultRefId(campaignIntentId)
                        .serverAmount(derived.totalAmount())
                        .build());

        auditLogService.recordToolCall(
                workspaceId,
                "request_payment",
                "C",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                idempotencyKey,
                derived.totalAmount(),
                Map.of("campaignIntentId", campaignIntentId, "status", "PENDING_CONFIRM"));

        return new RequestPaymentResult(
                "PENDING_CONFIRM",
                campaignIntentId,
                derived.totalAmount(),
                "INR",
                confirmActionUrl(campaignIntentId),
                false);
    }

    /** The browser renders this as the human confirm button — Python/Meera is not in that leg at all. */
    private static String confirmActionUrl(String campaignIntentId) {
        return "/brand/escrow/fund?campaignIntentId=" + campaignIntentId;
    }

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimalArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
