package com.influora.service.meera.tool;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignIntent;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.MeeraToolCall;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.IntentStatus;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.domain.enums.ToolResultRefType;
import com.influora.repository.CampaignIntentRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.service.AuditLogService;
import com.influora.service.CampaignTemplateService;
import com.influora.service.IdempotencyService;
import com.influora.web.dto.meera.MeeraToolDtos.CreateCampaignResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-tier executor (06-MEERA-PERMISSIONS-MATRIX.md row 7): "Generate a DRAFT campaign from
 * conversation intent (campaign_intents → draft campaigns row). Draft state; going live funds
 * escrow = Commit (human)."
 *
 * <p><b>Optional {@code template_id} (Platform-AI Phase 1, Wave 1b — Priya A3, Ash's
 * STANDARD-enum ruling).</b> When present, visibility is checked via {@link
 * CampaignTemplateService#requireVisible} (SYSTEM visible to all, CUSTOM only to the owning
 * workspace; 404s on a cross-workspace/unknown id) and {@code requirements}/{@code hashtags}/
 * {@code target_audience}/{@code brand_guidelines} are copied from the template into the draft.
 * Per Ash's ruling ("DERIVE, don't widen"), {@code campaign_type} is then taken from {@code
 * template.getCampaignType()} — which may legitimately be {@code STANDARD} — and any AI-supplied
 * {@code campaign_type} in the input is IGNORED; the AI-facing tool enum itself is unchanged
 * (still {@code HYPE|DIRECT|REVIEW} on the Python side, out of scope here). When {@code
 * template_id} is absent, behavior is byte-for-byte what it was before this change: {@code
 * campaign_type} comes from the AI-supplied value (or the {@code STANDARD} fallback). Budget
 * stays {@code null} either way — money rails are untouched by this change.
 *
 * <p>Idempotent via {@link IdempotencyService#executeOnce} (V15 {@code idempotency_keys},
 * insert-first-wins on {@code UNIQUE(idempotency_key)}) — a concurrent double-submit is
 * arbitrated by the database, not by this class's own check-then-act, so the losing request
 * never races past the check into a duplicate INSERT ([SEC: LB-3]). {@code meera_tool_calls}
 * (V14) remains the result ledger consulted first to serve a cheap replay of the prior payload;
 * the actual creation path is wrapped in {@code executeOnce} so a genuine concurrent collision on
 * the same key can never create two draft campaigns. No money field is writable here:
 * {@link Campaign#budgetMin}/{@link Campaign#budgetMax} are left null on creation (the AI's
 * {@code proposed_budget} on the {@link CampaignIntent} is advisory only per that field's own doc
 * comment) — a human sets a real budget later, and funding escrow is a wholly separate
 * Commit-tier, human-only action.
 */
@Service
public class CreateCampaignExecutor {

    private static final String IDEMPOTENCY_SCOPE = "meera.create_campaign";

    private final CampaignIntentRepository campaignIntentRepository;
    private final CampaignRepository campaignRepository;
    private final MeeraToolCallRepository toolCallRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;
    private final CampaignTemplateService campaignTemplateService;

    public CreateCampaignExecutor(
            CampaignIntentRepository campaignIntentRepository,
            CampaignRepository campaignRepository,
            MeeraToolCallRepository toolCallRepository,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService,
            CampaignTemplateService campaignTemplateService) {
        this.campaignIntentRepository = campaignIntentRepository;
        this.campaignRepository = campaignRepository;
        this.toolCallRepository = toolCallRepository;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
        this.campaignTemplateService = campaignTemplateService;
    }

    public CreateCampaignResult execute(
            String workspaceId,
            String conversationId,
            String userId,
            String idempotencyKey,
            Map<String, Object> input) {
        CreateCampaignResult replay = replayIfPresent(workspaceId, idempotencyKey);
        if (replay != null) {
            return replay;
        }

        try {
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    workspaceId,
                    IDEMPOTENCY_SCOPE,
                    () -> doExecute(workspaceId, conversationId, userId, idempotencyKey, input));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            // Lost the insert-first race to a concurrent caller with the same key — the winner's
            // meera_tool_calls row is now visible (or about to be); replay it instead of a bare
            // 500/409 on the generic idempotency_keys table.
            CreateCampaignResult won = replayIfPresent(workspaceId, idempotencyKey);
            if (won != null) {
                return won;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This request is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    private CreateCampaignResult replayIfPresent(String workspaceId, String idempotencyKey) {
        var existing = toolCallRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return null;
        }
        MeeraToolCall prior = existing.get();
        if (!prior.getWorkspaceId().equals(workspaceId)) {
            // Never replay another tenant's result for a key collision — treat as not found.
            throw new ApiException(
                    "IDEMPOTENCY_KEY_TENANT_MISMATCH",
                    "Idempotency key belongs to a different workspace",
                    HttpStatus.CONFLICT);
        }
        return new CreateCampaignResult(
                prior.getResultRefType() == ToolResultRefType.CAMPAIGN ? prior.getResultRefId() : null,
                null,
                prior.getStatus().name(),
                true);
    }

    @Transactional
    protected CreateCampaignResult doExecute(
            String workspaceId,
            String conversationId,
            String userId,
            String idempotencyKey,
            Map<String, Object> input) {
        String productName = stringArg(input, "product_name");
        String productUrl = stringArg(input, "product_url");
        BigDecimal productPrice = decimalArg(input, "product_price");
        BigDecimal proposedBudget = decimalArg(input, "proposed_budget");
        Integer creatorCount = intArg(input, "creator_count");
        String campaignTypeRaw = stringArg(input, "campaign_type");
        String templateId = stringArg(input, "template_id");

        // Wave 1b (Priya A3 + Ash's STANDARD-enum ruling): template_id present -> the template
        // row is the authority for campaign_type (may be STANDARD); any AI-supplied campaign_type
        // is ignored. template_id absent -> unchanged, AI-supplied value (or STANDARD fallback).
        CampaignTemplate template = null;
        CampaignIntentType campaignType;
        if (templateId != null && !templateId.isBlank()) {
            template = campaignTemplateService.requireVisible(templateId, workspaceId);
            campaignType = template.getCampaignType() != null ? template.getCampaignType() : CampaignIntentType.STANDARD;
        } else {
            campaignType = parseCampaignType(campaignTypeRaw);
        }

        CampaignIntent intent =
                campaignIntentRepository.save(
                        CampaignIntent.builder()
                                .id(Ulids.newUlid())
                                .conversationId(conversationId)
                                .workspaceId(workspaceId)
                                .campaignType(campaignType)
                                .productName(productName)
                                .productUrl(productUrl)
                                .productPrice(productPrice)
                                .proposedBudget(proposedBudget)
                                .creatorCount(creatorCount)
                                .status(IntentStatus.READY)
                                .build());

        // Draft-state only — no budget/money field set (Guardrail: no money field writable by
        // this D-tier action). A human sets a real budget and funds escrow in a later, separate
        // Commit-tier step.
        Campaign.Builder campaignBuilder =
                Campaign.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .title(productName != null ? "Draft: " + productName : "Draft campaign")
                        .description(
                                "Auto-drafted by Meera from conversation intent. Review and confirm before"
                                        + " going live.")
                        .status(CampaignStatus.DRAFT)
                        .createdBy(userId)
                        .campaignType(campaignType);

        // Wave 1b (Priya A3): copy requirements/hashtags/target_audience/brand_guidelines from the
        // template into the draft. Budget (budgetMin/budgetMax) is deliberately NEVER copied here —
        // money stays AI-unwritable regardless of template_id.
        if (template != null) {
            campaignBuilder
                    .requirementsJson(template.getRequirementsJson())
                    .hashtagsJson(template.getHashtagsJson())
                    .targetAudienceJson(template.getTargetAudienceJson())
                    .brandGuidelines(template.getBrandGuidelines());
        }

        Campaign campaign = campaignRepository.save(campaignBuilder.build());

        intent.confirm(campaign.getId());
        campaignIntentRepository.save(intent);

        toolCallRepository.save(
                MeeraToolCall.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .conversationId(conversationId)
                        .toolName(MeeraToolName.create_campaign)
                        .idempotencyKey(idempotencyKey)
                        .status(ToolCallStatus.EXECUTED)
                        .resultRefType(ToolResultRefType.CAMPAIGN)
                        .resultRefId(campaign.getId())
                        .build());

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("campaignId", campaign.getId());
        auditDetail.put("campaignIntentId", intent.getId());
        if (template != null) {
            // No schema change to persist templateId on the row itself (out of scope for this
            // wave) — the audit ledger is the traceability seam for "which template produced this
            // draft" until/unless a dedicated column is added.
            auditDetail.put("templateId", template.getId());
        }
        auditLogService.recordToolCall(
                workspaceId,
                "create_campaign",
                "D",
                AuditLogService.OUTCOME_ALLOWED,
                null,
                idempotencyKey,
                null,
                auditDetail);

        return new CreateCampaignResult(campaign.getId(), intent.getId(), CampaignStatus.DRAFT.name(), false);
    }

    private static CampaignIntentType parseCampaignType(String raw) {
        if (raw == null) {
            return CampaignIntentType.STANDARD;
        }
        try {
            return CampaignIntentType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CampaignIntentType.STANDARD;
        }
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

    private static Integer intArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
