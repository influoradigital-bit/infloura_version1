package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.domain.enums.CreatorToolName;
import com.influora.domain.enums.MeeraToolTier;
import com.influora.domain.enums.UserType;
import com.influora.security.OnBehalfAuthResolver;
import com.influora.security.OnBehalfAuthResolver.OnBehalfContext;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.meera.tool.ToolCallValidator.ToolCallRejectedException;
import com.influora.service.meera.tool.creator.CheckDealRisksExecutor;
import com.influora.service.meera.tool.creator.CreatorToolCallValidator;
import com.influora.service.meera.tool.creator.EstimateMyRateExecutor;
import com.influora.service.meera.tool.creator.GetBriefExecutor;
import com.influora.service.meera.tool.creator.GetMyDealsExecutor;
import com.influora.service.meera.tool.creator.GetMyMetricsExecutor;
import com.influora.service.meera.tool.creator.GetTodaysTopicsExecutor;
import com.influora.web.dto.meera.CreatorToolDtos.CheckDealRisksResult;
import com.influora.web.dto.meera.CreatorToolDtos.EstimateMyRateResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetBriefResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyDealsResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyMetricsResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetTodaysTopicsResult;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.4) — the CREATOR-audience tool surface, the counterpart
 * to {@link MeeraInternalController}.
 *
 * <p>Sits under {@code /internal/**}, so the dual-credential mesh gate applies unchanged:
 * {@code InternalServiceTokenFilter} authenticates the influora-ai process, and the per-turn
 * on-behalf JWT authenticates the creator. Neither alone is sufficient, and this controller re-proves
 * the human on every call rather than trusting the body.
 *
 * <p><b>Six routes, not the nine in SPEC.md &sect;3.1.</b> {@code estimate_my_rate} and
 * {@code check_deal_risks} joined the first two in Wave 3, with {@code RateQuoteService} and
 * {@code DealRiskService}; {@code get_brief} followed once {@code CreatorBriefService} existed to
 * read; {@code get_todays_topics} (T-CONTENT-TOPICS) is outside SPEC.md &sect;3.1 entirely, added
 * later with {@code ContentTopicService}. The drafts and campaign tools are later waves still.
 * They are deliberately not stubbed. A
 * registered route that 404s or returns an empty shape is worse than an absent one: the model is
 * told the capability exists, spends a turn on it, and narrates a failure to the creator.
 *
 * <p><b>Adding a route here is half a change.</b> The other half is
 * {@code CreatorToolScopes.WIRED_TOOL_NAMES}, which is what the assembler actually offers the
 * model; a route with no name there is unreachable, and a name there with no route costs the
 * creator a turn. {@code MeeraContextServiceTest#testCreatorContextCarriesWiredToolNames} reflects
 * over this class's {@code @PostMapping} values and fails on either half alone.
 *
 * <p>Handler order is fixed and lives in one place ({@link #handleRead}) rather than being repeated
 * per route — the flag before identity, identity before consent, consent before the validator,
 * validator before any executor. Six steps copy-pasted twice is how one route ends up checking
 * consent after it has already read the creator's deals.
 */
@RestController
@RequestMapping("/internal/meera/creator")
public class CreatorMeeraToolController {

    private static final String ON_BEHALF_HEADER = "X-Onbehalf-Authorization";

    private final OnBehalfAuthResolver onBehalfAuthResolver;
    private final CreatorToolCallValidator creatorToolCallValidator;
    private final CreatorAgentPreferencesService preferencesService;
    private final AuditLogService auditLogService;
    private final MeeraCreatorFeatureProperties featureProperties;
    private final GetMyDealsExecutor getMyDealsExecutor;
    private final GetMyMetricsExecutor getMyMetricsExecutor;
    private final EstimateMyRateExecutor estimateMyRateExecutor;
    private final CheckDealRisksExecutor checkDealRisksExecutor;
    private final GetBriefExecutor getBriefExecutor;
    private final GetTodaysTopicsExecutor getTodaysTopicsExecutor;

    public CreatorMeeraToolController(
            OnBehalfAuthResolver onBehalfAuthResolver,
            CreatorToolCallValidator creatorToolCallValidator,
            CreatorAgentPreferencesService preferencesService,
            AuditLogService auditLogService,
            MeeraCreatorFeatureProperties featureProperties,
            GetMyDealsExecutor getMyDealsExecutor,
            GetMyMetricsExecutor getMyMetricsExecutor,
            EstimateMyRateExecutor estimateMyRateExecutor,
            CheckDealRisksExecutor checkDealRisksExecutor,
            GetBriefExecutor getBriefExecutor,
            GetTodaysTopicsExecutor getTodaysTopicsExecutor) {
        this.onBehalfAuthResolver = onBehalfAuthResolver;
        this.creatorToolCallValidator = creatorToolCallValidator;
        this.preferencesService = preferencesService;
        this.auditLogService = auditLogService;
        this.featureProperties = featureProperties;
        this.getMyDealsExecutor = getMyDealsExecutor;
        this.getMyMetricsExecutor = getMyMetricsExecutor;
        this.estimateMyRateExecutor = estimateMyRateExecutor;
        this.checkDealRisksExecutor = checkDealRisksExecutor;
        this.getBriefExecutor = getBriefExecutor;
        this.getTodaysTopicsExecutor = getTodaysTopicsExecutor;
    }

    @PostMapping("/get_my_deals")
    public ResponseEntity<ApiResponse<GetMyDealsResult>> getMyDeals(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestBody Map<String, Object> body) {
        return handleRead(onBehalfJwt, body, CreatorToolName.get_my_deals, getMyDealsExecutor::execute);
    }

    /**
     * {@code get_brief} — one brief with its extraction, risk flags and quote. The quote carries the
     * creator's floor, which is why this route, like {@code estimate_my_rate}, relies on
     * {@link #handleRead}'s {@code requireCreatorPrincipal}: {@code FloorBarrierTest} permits this
     * controller to serve a floor-bearing type on exactly that basis.
     */
    @PostMapping("/get_brief")
    public ResponseEntity<ApiResponse<GetBriefResult>> getBrief(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestBody Map<String, Object> body) {
        return handleRead(onBehalfJwt, body, CreatorToolName.get_brief, getBriefExecutor::execute);
    }

    @PostMapping("/get_my_metrics")
    public ResponseEntity<ApiResponse<GetMyMetricsResult>> getMyMetrics(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestBody Map<String, Object> body) {
        return handleRead(
                onBehalfJwt, body, CreatorToolName.get_my_metrics, getMyMetricsExecutor::execute);
    }

    @PostMapping("/estimate_my_rate")
    public ResponseEntity<ApiResponse<EstimateMyRateResult>> estimateMyRate(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestBody Map<String, Object> body) {
        return handleRead(
                onBehalfJwt, body, CreatorToolName.estimate_my_rate, estimateMyRateExecutor::execute);
    }

    @PostMapping("/check_deal_risks")
    public ResponseEntity<ApiResponse<CheckDealRisksResult>> checkDealRisks(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestBody Map<String, Object> body) {
        return handleRead(
                onBehalfJwt, body, CreatorToolName.check_deal_risks, checkDealRisksExecutor::execute);
    }

    /**
     * T-CONTENT-TOPICS -- {@code get_todays_topics}: today's date/weekday plus up to five matched,
     * safety-screened rows from the hand-typed {@code content_topics} catalogue. Like every other
     * route here, {@code today} is decided by {@link GetTodaysTopicsExecutor}, never by this
     * request.
     */
    @PostMapping("/get_todays_topics")
    public ResponseEntity<ApiResponse<GetTodaysTopicsResult>> getTodaysTopics(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestBody Map<String, Object> body) {
        return handleRead(
                onBehalfJwt, body, CreatorToolName.get_todays_topics, getTodaysTopicsExecutor::execute);
    }

    /**
     * The whole gate chain for an R-tier creator read, in the order SPEC.md &sect;3.4 requires.
     *
     * <p>The executor is passed as a function of {@code (creatorUserId, body)} — the user id comes
     * from the verified JWT, never from the body, so an executor structurally cannot be handed a
     * caller-chosen identity.
     *
     * <p><b>[SEC: Kabir Wave 2, finding 3] Every rejection writes a row.</b> Only the success path
     * used to audit, so every way this chain can refuse — a disabled feature, a token pointed at
     * another creator's {@code workspace_id}, a scope that does not cover the tool, a brand
     * principal on a creator route, a creator who has not consented — left no trace whatsoever.
     * Probing for another creator's id was free and invisible. The validator's own rejection rows
     * did not cover the gap: it is only ever handed {@code expected.name()}, a real enum constant
     * of a tiered tool, so neither {@code UNKNOWN_TOOL_NAME} nor {@code FORBIDDEN_TIER} can fire
     * from here — the only rows this surface could ever write were ALLOWED ones.
     *
     * <p>The rejection row is written <b>before</b> the exception is thrown, and deliberately not
     * wrapped in a {@code try}: an audit write that fails silently is the defect this fixes, and
     * the ALLOWED path below has never swallowed one either.
     */
    private <T> ResponseEntity<ApiResponse<T>> handleRead(
            String onBehalfJwt,
            Map<String, Object> body,
            CreatorToolName tool,
            BiFunction<String, Map<String, Object>, T> executor) {

        requireFeatureEnabled(tool);
        String workspaceId = requireWorkspaceId(body, tool);

        // Asserts the token's scope claim authorises THIS tool, not merely that the token is valid.
        // For a CREATOR turn the "workspace id" IS the creator's users.id — see
        // MeeraContextService#assembleCreatorContext — and resolveForWorkspace is audience-agnostic,
        // so it equality-checks that claim against the body with no change needed here.
        OnBehalfContext ctx;
        try {
            ctx =
                    onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                            onBehalfJwt, workspaceId, tool.name());
        } catch (ApiException e) {
            // The tenant key is the workspace_id the CALLER asked for. On a scope rejection that id
            // equals the token's own verified claim; on ON_BEHALF_WORKSPACE_MISMATCH it is by
            // definition the id the caller was probing, which is precisely the value a forensic
            // query needs and the reason this row has to exist. It is caller-supplied either way,
            // so the detail says so rather than letting a reader assume it was proven.
            recordRejected(
                    workspaceId,
                    tool,
                    e.getCode(),
                    Map.of("stage", "on_behalf_resolve", "requestedWorkspaceIdVerified", false));
            throw e;
        }

        requireCreatorPrincipal(ctx, tool);
        requireConsentByUserId(ctx, tool);

        CreatorToolName resolved = requireTool(tool, ctx.userId());

        T result;
        try {
            result = executor.apply(ctx.userId(), body);
        } catch (RuntimeException e) {
            // A FAILED row, distinct from a REJECTED one: the caller was entitled to this read and
            // the server could not serve it. recordToolCall runs in its own transaction, so the row
            // survives the rollback of whatever the executor was doing.
            recordOutcome(
                    ctx.userId(),
                    tool,
                    AuditLogService.OUTCOME_FAILED,
                    e instanceof ApiException apiException ? apiException.getCode() : "EXECUTOR_ERROR",
                    Map.of("exception", e.getClass().getSimpleName()));
            throw e;
        }

        MeeraToolTier tier = creatorToolCallValidator.tierOf(resolved);
        auditLogService.recordToolCall(
                ctx.userId(),
                resolved.name(),
                tier == null ? null : tier.name(),
                AuditLogService.OUTCOME_ALLOWED,
                null,
                null,
                null,
                Map.of());

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * The Phase-A rollback flag, applied to every route here.
     *
     * <p>Copied, not inherited: {@code CreatorMeeraController#requireFeatureEnabled} is
     * {@code private}, so it is neither callable nor inheritable from this class. Called first in
     * every handler, before identity and consent, so a disabled feature 404s uniformly regardless of
     * who is asking — a 403 here would tell an unauthenticated caller that the feature exists.
     *
     * <p>Its audit row therefore carries a null tenant key: nothing has been proven about who is
     * asking at this point, and inventing an identity for the row would be worse than recording
     * that the surface was probed while switched off.
     */
    private void requireFeatureEnabled(CreatorToolName tool) {
        if (!featureProperties.isCreatorEnabled()) {
            recordRejected(null, tool, "FEATURE_DISABLED", Map.of("stage", "feature_flag"));
            throw new ApiException(
                    "FEATURE_DISABLED", "Meera for Creators is currently disabled", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * A BRAND-audience token must never reach a creator tool even if its scope claim somehow named
     * one — the executors below read a creator's private floors and deal history, and the audience
     * check is the structural half of the info barrier that scope alone does not provide.
     */
    private void requireCreatorPrincipal(OnBehalfContext ctx, CreatorToolName tool) {
        if (ctx.userType() != UserType.CREATOR) {
            recordRejected(
                    ctx.userId(),
                    tool,
                    "AUDIENCE_PRINCIPAL_MISMATCH",
                    Map.of("presentedUserType", String.valueOf(ctx.userType())));
            throw new ApiException(
                    "AUDIENCE_PRINCIPAL_MISMATCH",
                    "This tool is available to creator principals only",
                    HttpStatus.FORBIDDEN);
        }
    }

    /**
     * DPDP consent precondition. {@code isConsentAccepted} also requires the stored consent version
     * to be current, so a creator who consented under a superseded notice is rejected here with the
     * same 403 as one who never consented.
     */
    private void requireConsentByUserId(OnBehalfContext ctx, CreatorToolName tool) {
        if (!preferencesService.isConsentAccepted(ctx.userId())) {
            recordRejected(ctx.userId(), tool, "CONSENT_REQUIRED", Map.of("stage", "consent"));
            throw new ApiException(
                    "CONSENT_REQUIRED", "Consent is required before using Meera", HttpStatus.FORBIDDEN);
        }
    }

    /** A REJECTED row carrying the reason code the caller is about to be refused with. */
    private void recordRejected(
            String tenantKey, CreatorToolName tool, String reasonCode, Map<String, Object> detail) {
        recordOutcome(tenantKey, tool, AuditLogService.OUTCOME_REJECTED, reasonCode, detail);
    }

    /**
     * The one place a non-ALLOWED row is built, so every rejection carries the tool name and its
     * tier in the same shape as the ALLOWED row a successful call writes — an audit table where the
     * refusals are shaped differently from the successes cannot be queried as one series.
     */
    private void recordOutcome(
            String tenantKey,
            CreatorToolName tool,
            String outcome,
            String reasonCode,
            Map<String, Object> detail) {
        MeeraToolTier tier = creatorToolCallValidator.tierOf(tool);
        auditLogService.recordToolCall(
                tenantKey,
                tool.name(),
                tier == null ? null : tier.name(),
                outcome,
                reasonCode,
                null,
                null,
                detail);
    }

    /**
     * Runs the tool through the validator (whitelist + tier gate + rejection audit row) and asserts
     * the resolved tool is the one this route serves — the same route/tool cross-check
     * {@code MeeraInternalController#requireTool} performs.
     *
     * <p>A {@link ToolCallRejectedException} is NOT audited here: the validator has already written
     * its own REJECTED row with the reason code before throwing, and a second row for one rejection
     * would double-count every query over this table. {@code TOOL_ROUTE_MISMATCH} is the
     * controller's own refusal and nothing else records it, so it is written here.
     */
    private CreatorToolName requireTool(CreatorToolName expected, String creatorUserId) {
        CreatorToolName resolved;
        try {
            resolved = creatorToolCallValidator.validateAndResolve(expected.name(), creatorUserId);
        } catch (ToolCallRejectedException e) {
            throw new ApiException(e.getReasonCode(), e.getMessage(), HttpStatus.FORBIDDEN);
        }
        if (resolved != expected) {
            recordRejected(
                    creatorUserId,
                    expected,
                    "TOOL_ROUTE_MISMATCH",
                    Map.of("resolvedTool", String.valueOf(resolved)));
            throw new ApiException(
                    "TOOL_ROUTE_MISMATCH", "Resolved tool does not match the called route", HttpStatus.FORBIDDEN);
        }
        return resolved;
    }

    /**
     * {@code workspace_id} is always snake_case in the body — Python merges it in verbatim.
     *
     * <p>Audits its refusal like every other gate in {@link #handleRead}. It was the one rejection
     * path left without a row, which contradicted this class's "every rejection writes a row"
     * promise for a path that is reachable with the flag on and a malformed body — so a caller
     * hammering the surface with bodies that omit {@code workspace_id} produced no trace at all,
     * and the audit series silently under-counted every probe of this endpoint.
     *
     * <p>The tenant key is null, for the same reason {@link #requireFeatureEnabled}'s row is: the
     * body carried no workspace id to record and the token has not been parsed yet, so there is no
     * identity here that is not invented. An instance method rather than {@code static} only
     * because writing the row needs the audit service.
     */
    private String requireWorkspaceId(Map<String, Object> body, CreatorToolName tool) {
        Object value = body == null ? null : body.get("workspace_id");
        if (value == null || String.valueOf(value).isBlank()) {
            recordRejected(null, tool, "WORKSPACE_ID_REQUIRED", Map.of("stage", "workspace_id"));
            throw new ApiException(
                    "WORKSPACE_ID_REQUIRED", "Request body must include workspace_id", HttpStatus.BAD_REQUEST);
        }
        return String.valueOf(value);
    }
}
