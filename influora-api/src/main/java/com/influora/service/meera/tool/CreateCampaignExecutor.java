package com.influora.service.meera.tool;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignIntent;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.MeeraToolCall;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.IntentStatus;
import com.influora.domain.enums.MeeraInteractionEventType;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.domain.enums.ToolResultRefType;
import com.influora.domain.enums.UserType;
import com.influora.domain.entity.Workspace;
import com.influora.repository.CampaignIntentRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.AuditLogService;
import com.influora.service.BrandContextService;
import com.influora.service.CampaignTemplateService;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.MeeraInteractionLogService;
import com.influora.web.dto.campaign.CampaignDtos;
import com.influora.web.dto.meera.MeeraToolDtos.CreateCampaignResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * {@code platforms}/{@code content_types}/{@code objectives} are taken from the template's own
 * columns where it has them and from the AI-composed equivalents where it does not (P1-11 — the
 * template path used to skip all three, so a recommended template produced a THINNER draft than no
 * template at all); the template's values go through the same {@link #ALLOWED_PLATFORMS}/{@link
 * #ALLOWED_CONTENT_TYPES} filter as the AI's, since a CUSTOM template row is workspace-authored
 * free-form JSON.
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

    /**
     * Server-side allow-lists for the AI-supplied {@code platforms}/{@code content_types} lists
     * (Ash risk R2: never trust the model's own claim of a valid enum value — filter, don't
     * assume). There is no dedicated Java {@code Platform}/{@code ContentType} enum in this
     * codebase today (the whole campaign write path — see {@code CampaignDtos.CampaignWriteRequest}
     * — stores these as free-form {@code List<String>} JSON columns), so these constants are the
     * enforcement point. Values are byte-for-byte the {@code platformOptions}/{@code
     * contentTypeOptions} arrays in {@code src/components/brand/campaigns/campaign-form.tsx} — NOT
     * the wider {@code Platform}/{@code ContentType} TS union types in {@code src/lib/types.ts},
     * which include values (e.g. {@code OTHER}, {@code IMAGE}) the form itself never emits.
     */
    private static final Set<String> ALLOWED_PLATFORMS =
            Set.of("INSTAGRAM", "YOUTUBE", "TIKTOK", "TWITTER", "LINKEDIN", "FACEBOOK", "TWITCH");

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("POST", "STORY", "REEL", "VIDEO", "LIVE_STREAM", "ARTICLE", "PODCAST");

    /** Fallback when the workspace itself has no {@code industry} set — see the class javadoc
     * "Gate fix round 1" note on {@code endBrandCategory} below. Matches {@code
     * AdminBrandService}'s existing "STANDARD"/generic-fallback convention of never leaving a
     * required-for-new column NULL rather than inventing a specific-sounding lie. */
    private static final String UNSPECIFIED_END_BRAND_CATEGORY = "Unspecified";

    private final CampaignIntentRepository campaignIntentRepository;
    private final CampaignRepository campaignRepository;
    private final MeeraToolCallRepository toolCallRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;
    private final CampaignTemplateService campaignTemplateService;
    private final MeeraInteractionLogService meeraInteractionLogService;
    private final WorkspaceRepository workspaceRepository;
    private final BrandContextService brandContext;

    public CreateCampaignExecutor(
            CampaignIntentRepository campaignIntentRepository,
            CampaignRepository campaignRepository,
            MeeraToolCallRepository toolCallRepository,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService,
            CampaignTemplateService campaignTemplateService,
            MeeraInteractionLogService meeraInteractionLogService,
            WorkspaceRepository workspaceRepository,
            BrandContextService brandContext) {
        this.campaignIntentRepository = campaignIntentRepository;
        this.campaignRepository = campaignRepository;
        this.toolCallRepository = toolCallRepository;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
        this.campaignTemplateService = campaignTemplateService;
        this.meeraInteractionLogService = meeraInteractionLogService;
        this.workspaceRepository = workspaceRepository;
        this.brandContext = brandContext;
    }

    public CreateCampaignResult execute(
            String workspaceId,
            String conversationId,
            String userId,
            UserType userType,
            String idempotencyKey,
            Map<String, Object> input) {
        // F-0530: this D-tier tool path is the Meera sibling of CampaignService#create (the human
        // REST write path) — that path now hard-gates on brandContext.requireRole(OWNER, ADMIN,
        // MANAGER) (see its own F-0530 comment), but this executor never checked the caller's
        // MemberRole at all: the on-behalf JWT's scope claim proves WHICH TOOL may run, not WHICH
        // MEMBER may act, so a VIEWER/MEMBER-role workspace member could still mint/replay a
        // create_campaign turn and draft a campaign they are refused to update/publish/delete
        // afterward. Reuses the exact same brandContext.requireRole helper (not a second,
        // independently-drifting check). workspaceId/userId/userType all come from the
        // JWT-verified OnBehalfContext (MeeraInternalController), never from client-supplied body
        // fields. Runs before replay/idempotency lookups so an unauthorized call never even
        // touches the idempotency ledger.
        AuthPrincipal principal = new AuthPrincipal(userId, null, userType, workspaceId);
        var member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER);

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
        Integer creatorCount = intArg(input, "creator_count");
        String campaignTypeRaw = stringArg(input, "campaign_type");
        String templateId = stringArg(input, "template_id");

        // Tier-1 content-composition inputs (AI-composed CONTENT only — never money/dates; see the
        // class-level guardrail note). Parsed unconditionally. title/description/hashtags/
        // target_audience and the HYPE content lanes are applied only on the no-template path (a
        // template is the sole authority for those of its own fields); platforms/content_types/
        // objectives are applied on BOTH paths — template value first, these as the fallback when
        // the template's own column is empty (P1-11).
        String aiTitle = stringArg(input, "title");
        String aiDescription = stringArg(input, "description");
        List<String> aiObjectives = stringListArg(input, "objectives");
        List<String> aiPlatforms = filterAllowed(stringListArg(input, "platforms"), ALLOWED_PLATFORMS);
        List<String> aiContentTypes = filterAllowed(stringListArg(input, "content_types"), ALLOWED_CONTENT_TYPES);
        List<String> aiHashtags = stringListArg(input, "hashtags");
        // Free-text audience descriptors (string or string[] on the wire) -- serialized below into
        // TargetAudienceDto.interests, never treated as verified demographics.
        List<String> aiTargetAudience = stringListArg(input, "target_audience");

        // HYPE content fields (Ash's HYPE completion spec §3, Vikram build 2026-07-23) -- FLAT,
        // content-only schema additions (schemas.py PART C: no combinators, no money). Meera is
        // allowed to author the source reel + remix format lanes; perReelRate/slotCap/liveUntil
        // stay human-only and are NEVER touched here (guardrail: no AI money/date write).
        String aiSourceReelUrl = stringArg(input, "source_reel_url");
        List<String> aiFormatLanes = stringListArg(input, "format_lanes");

        // Gate fix round 1 (Priya Q2.4/Q9.6, T-MEERA-CREATOR-PHASE-A) — CampaignService#create
        // (the human write path) hard-requires endBrandName/endBrandCategory on every NEW
        // campaign (END_BRAND_NAME_REQUIRED/END_BRAND_CATEGORY_REQUIRED, V72). This AI-drafted
        // path built Campaign.builder() directly and never applied that rule, so a Meera-created
        // draft was born with NULL end-brand fields — indistinguishable from a pre-V72 legacy row
        // and silently exempt from a "required for every new campaign" invariant. The current
        // create_campaign tool schema (influora-ai, out of this file's scope) does not yet ask the
        // model for an end-brand name/category, so this cannot 400 the AI the way the human form
        // does without breaking every AI-drafted campaign today — instead: honor an AI-supplied
        // value if the schema ever adds one (forward-compatible), else default from the
        // workspace's own name/industry (the common case — a brand running a campaign for its own
        // products), so the columns are always meaningfully filled, never NULL, on a fresh AI
        // draft. A human reviewing the DRAFT before it goes live can still correct either field.
        String aiEndBrandName = stringArg(input, "end_brand_name");
        String aiEndBrandCategory = stringArg(input, "end_brand_category");
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        String resolvedEndBrandName =
                nonBlankOrElse(
                        aiEndBrandName,
                        nonBlankOrElse(workspace != null ? workspace.getName() : null, "Unspecified brand"));
        String resolvedEndBrandCategory =
                nonBlankOrElse(
                        aiEndBrandCategory,
                        nonBlankOrElse(
                                workspace != null ? workspace.getIndustry() : null,
                                UNSPECIFIED_END_BRAND_CATEGORY));

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
                                .creatorCount(creatorCount)
                                .status(IntentStatus.READY)
                                .build());

        // Draft-state only — no budget/money field set (Guardrail: no money field writable by
        // this D-tier action). A human sets a real budget and funds escrow in a later, separate
        // Commit-tier step. title/description fall back to the pre-existing generic strings so an
        // old-style call (no Tier-1 content fields) still works byte-for-byte as before.
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
                        .campaignType(campaignType)
                        .endBrandName(resolvedEndBrandName)
                        .endBrandCategory(resolvedEndBrandCategory);

        // Wave 1b (Priya A3): copy requirements/hashtags/target_audience/brand_guidelines from the
        // template into the draft. Budget (budgetMin/budgetMax) is deliberately NEVER copied here —
        // money stays AI-unwritable regardless of template_id.
        if (template != null) {
            campaignBuilder
                    .requirementsJson(template.getRequirementsJson())
                    .hashtagsJson(template.getHashtagsJson())
                    .targetAudienceJson(template.getTargetAudienceJson())
                    .brandGuidelines(template.getBrandGuidelines());

            // P1-11: platforms/content_types/objectives used to be applied ONLY in the else-branch
            // below, so taking Meera's template recommendation produced a THINNER draft than
            // ignoring it — all three columns came out empty and the brand opened a half-filled
            // form. persona.py explicitly tells the model NOT to compose those itself once a
            // template_id is set ("When you DO build it from scratch (no template_id), compose a
            // real draft ... objectives, platforms, content_types ..."), so nothing else was ever
            // going to fill them. The template row is the authority for its own columns (same
            // "DERIVE, don't widen" ruling that already governs campaign_type above); the
            // AI-composed equivalents are only a fallback for a template whose own column is
            // NULL/empty — campaign_templates.platforms/content_types/objectives are all nullable
            // (V20260714150000), and a CUSTOM template saved from a partly-filled form legitimately
            // has holes in them.
            //
            // The ALLOWED_PLATFORMS/ALLOWED_CONTENT_TYPES allow-lists are applied to the template's
            // values too, exactly as they are to the AI's: a CUSTOM template row is workspace-authored
            // free-form JSON, so routing it into the draft unfiltered would be a way around the
            // server-side filter. filterAllowed also upper-cases what it keeps, which the SYSTEM seed
            // rows need — they store lowercase ('instagram', 'reel') while every campaign row written
            // by the human form stores the canonical upper-case spelling.
            // objectives has no allow-list on either path (free-form on the human write path too), so
            // it is only cleaned of null/blank entries.
            List<String> templatePlatforms =
                    filterAllowed(templateStringList(template.getPlatformsJson()), ALLOWED_PLATFORMS);
            List<String> templateContentTypes =
                    filterAllowed(templateStringList(template.getContentTypesJson()), ALLOWED_CONTENT_TYPES);
            List<String> templateObjectives = templateStringList(template.getObjectivesJson());

            List<String> resolvedPlatforms = templatePlatforms.isEmpty() ? aiPlatforms : templatePlatforms;
            List<String> resolvedContentTypes =
                    templateContentTypes.isEmpty() ? aiContentTypes : templateContentTypes;
            List<String> resolvedObjectives = templateObjectives.isEmpty() ? aiObjectives : templateObjectives;

            if (!resolvedPlatforms.isEmpty()) {
                campaignBuilder.platformsJson(JsonLists.toJson(resolvedPlatforms));
            }
            if (!resolvedContentTypes.isEmpty()) {
                campaignBuilder.contentTypesJson(JsonLists.toJson(resolvedContentTypes));
            }
            if (!resolvedObjectives.isEmpty()) {
                campaignBuilder.objectivesJson(JsonLists.toJson(resolvedObjectives));
            }
            // Still NO money/date field on this branch: budgetMin/budgetMax are never copied from
            // the template (it has both columns populated on every SYSTEM seed row — deliberately
            // ignored), and perReelRate/slotCap/liveUntil remain human-only.
        } else {
            // Tier-1 content composition (no template_id): apply the AI-composed fields the model
            // passed. A template, when set, is the sole authority for its own 4 fields above — the
            // AI-composed equivalents here are only ever applied on the from-scratch path. Still no
            // money/date field is touched anywhere in this branch.
            if (aiTitle != null && !aiTitle.isBlank()) {
                campaignBuilder.title(aiTitle);
            }
            if (aiDescription != null && !aiDescription.isBlank()) {
                campaignBuilder.description(aiDescription);
            }
            if (!aiObjectives.isEmpty()) {
                campaignBuilder.objectivesJson(JsonLists.toJson(aiObjectives));
            }
            if (!aiPlatforms.isEmpty()) {
                campaignBuilder.platformsJson(JsonLists.toJson(aiPlatforms));
            }
            if (!aiContentTypes.isEmpty()) {
                campaignBuilder.contentTypesJson(JsonLists.toJson(aiContentTypes));
            }
            if (!aiHashtags.isEmpty()) {
                campaignBuilder.hashtagsJson(JsonLists.toJson(aiHashtags));
            }
            if (!aiTargetAudience.isEmpty()) {
                // Coordinator fix (post-review, 2026-07-23): store in the SAME structured
                // TargetAudienceDto shape the human write path uses (CampaignService.java:152 ->
                // {ageRange, genders, locations, interests, languages}), not a bare string[] --
                // one canonical targetAudienceJson shape everywhere, so the campaign edit form
                // never has to branch on which path produced the draft. The AI's descriptive
                // strings become `interests`; it can't verify real demographics (age/gender/
                // location/language), so those stay unset for the human to fill in in the form.
                campaignBuilder.targetAudienceJson(
                        JsonLists.toJsonObject(
                                new CampaignDtos.TargetAudienceDto(null, null, null, aiTargetAudience, null)));
            }

            // HYPE completion (Ash §3 / build plan PART D): when the draft resolves to HYPE and
            // there's no template, persist a PARTIAL HypeConfig with only the content Meera is
            // allowed to author -- sourceReelUrl, formatLanes, and the hashtag she composed (first
            // of aiHashtags, if any). perReelRate/slotCap/slotsFilled/liveUntil are left UNSET
            // (null) here on purpose: the human sets rate + slots and launches from the hype edit
            // form (Ananya, src/pages/brand-new-hype-campaign.tsx), which PATCH-merges over this
            // partial via CampaignService#mergeHype. Field names match CampaignDtos.HypeConfigDto
            // byte-for-byte, which itself matches src/lib/types.ts:216-226's HypeConfig interface,
            // so the FE hype edit form reads this straight off the draft. NEVER set
            // perReelRate/slotCap/budgetMin/budgetMax from AI input anywhere in this class.
            if (campaignType == CampaignIntentType.HYPE
                    && (aiSourceReelUrl != null || !aiFormatLanes.isEmpty() || !aiHashtags.isEmpty())) {
                String composedHashtag = aiHashtags.isEmpty() ? null : aiHashtags.get(0);
                CampaignDtos.HypeConfigDto partialHype =
                        new CampaignDtos.HypeConfigDto(
                                aiSourceReelUrl,
                                null, // audioTrack -- not authored by Meera
                                composedHashtag,
                                aiFormatLanes.isEmpty() ? null : aiFormatLanes,
                                null, // perReelRate -- HUMAN ONLY, never AI-set
                                "INR",
                                null, // slotCap -- HUMAN ONLY, never AI-set
                                null, // slotsFilled -- unset until launch
                                null); // liveUntil -- HUMAN ONLY, set on launch
                campaignBuilder.hypeConfigJson(JsonLists.toJsonObject(partialHype));
            }
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

        // Phase 2 item 2.3 — flywheel logging. Fire-and-forget, own REQUIRES_NEW transaction; a
        // logging failure can never fail this draft-creation call (see
        // MeeraInteractionLogService#record's contract).
        meeraInteractionLogService.record(
                workspaceId,
                conversationId,
                MeeraInteractionEventType.DRAFT_CREATED,
                "create_campaign",
                null,
                campaign.getId(),
                null,
                null);

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

    /**
     * Parses a Tier-1 content-composition input as a list of strings. Accepts a JSON array (the
     * normal case for {@code objectives}/{@code platforms}/{@code content_types}/{@code hashtags})
     * OR a single scalar string (the {@code target_audience} field's schema is {@code string OR
     * string[]} — a bare string collapses to a one-element list here). Blank/null entries are
     * dropped; never throws on a malformed value, just returns an empty list.
     */
    private static List<String> stringListArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> rawList) {
            List<String> out = new ArrayList<>();
            for (Object item : rawList) {
                if (item == null) {
                    continue;
                }
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    /**
     * Server-side enum filter (Ash risk R2): drops any AI-supplied value not in {@code allowed},
     * case-insensitively, and upper-cases what's kept to the canonical enum spelling. Never trusts
     * the model to have only emitted values from the schema's {@code enum} constraint.
     */
    private static List<String> filterAllowed(List<String> values, Set<String> allowed) {
        if (values.isEmpty()) {
            return List.of();
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String value : values) {
            String upper = value.toUpperCase();
            if (allowed.contains(upper)) {
                kept.add(upper);
            }
        }
        return new ArrayList<>(kept);
    }

    /**
     * P1-11: reads a {@code CampaignTemplate} JSON array column into a clean {@code List<String>}.
     * Null/blank column, malformed JSON, or a JSON object where an array was expected all collapse
     * to an empty list ({@link JsonLists#stringListFromJson} swallows the parse error), which in
     * turn makes the caller fall back to the AI-composed equivalent instead of writing garbage into
     * the draft. Null and blank array entries are dropped so the value handed to
     * {@link #filterAllowed} can never NPE on {@code toUpperCase()}.
     */
    private static List<String> templateStringList(String json) {
        List<String> raw = JsonLists.stringListFromJson(json);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String s = item.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** {@code primary} if non-null/non-blank, else {@code fallback} (which may itself be null). */
    private static String nonBlankOrElse(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
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
