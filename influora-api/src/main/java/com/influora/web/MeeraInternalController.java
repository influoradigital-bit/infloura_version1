package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.enums.MeeraInteractionEventType;
import com.influora.domain.enums.MeeraToolName;
import com.influora.security.OnBehalfAuthResolver;
import com.influora.security.OnBehalfAuthResolver.OnBehalfContext;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import com.influora.service.meera.MeeraContextService;
import com.influora.service.meera.MeeraInteractionLogService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.service.meera.tool.CalculateBudgetExecutor;
import com.influora.service.meera.tool.ConfirmLaunchExecutor;
import com.influora.service.meera.tool.CreateCampaignExecutor;
import com.influora.service.meera.tool.GetCampaignPerformanceExecutor;
import com.influora.service.meera.tool.RequestPaymentExecutor;
import com.influora.service.meera.tool.ShowCreatorsExecutor;
import com.influora.service.meera.tool.ToolCallValidator;
import com.influora.service.meera.tool.ToolCallValidator.ToolCallRejectedException;
import com.influora.web.dto.meera.MeeraContextDtos.ContextRequest;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import com.influora.web.dto.meera.MeeraDtos.AnalyzeSiteChatResult;
import com.influora.web.dto.meera.MeeraToolDtos.CalculateBudgetResult;
import com.influora.web.dto.meera.MeeraToolDtos.ConfirmLaunchResult;
import com.influora.web.dto.meera.MeeraToolDtos.CreateCampaignResult;
import com.influora.web.dto.meera.MeeraToolDtos.GetCampaignPerformanceResult;
import com.influora.web.dto.meera.MeeraToolDtos.MessageWriteback;
import com.influora.web.dto.meera.MeeraToolDtos.MessageWritebackResult;
import com.influora.web.dto.meera.MeeraToolDtos.ReleaseTurnRequest;
import com.influora.web.dto.meera.MeeraToolDtos.RequestPaymentResult;
import com.influora.web.dto.meera.MeeraToolDtos.ShowCreatorsResult;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The real {@code /internal/meera/*} tool-call executor surface (Phase 4,
 * 16-VIKRAM-REMAINING-TASKS.md; 11-AI-FLOW-DETAILED.md Flow 3).
 *
 * <p><b>Wire contract matches {@code influora-ai/app/clients/spring.py} exactly</b> (Domain D,
 * already built) — the request BODY is the raw tool input Claude proposed, plus
 * {@code workspace_id} merged in by Python's tool loop; nothing else lives in the body. The
 * on-behalf human JWT and the idempotency key both travel as HEADERS
 * ({@code X-Onbehalf-Authorization}, {@code Idempotency-Key}), not body fields.
 *
 * <p>Every route here is reached only after the dual-credential mesh gate:
 * <ol>
 *   <li>{@code InternalServiceTokenFilter} (wired in {@code SecurityConfig}) — verifies
 *       {@code X-Meera-Service-Token} + the HMAC/nonce request signature BEFORE this controller
 *       method runs; a rejected call never reaches here.</li>
 *   <li>{@link OnBehalfAuthResolver}, invoked explicitly below for every tool — re-validates the
 *       forwarded human JWT (header) and asserts {@code token.workspaceId == body.workspace_id},
 *       so a stolen/forged service token alone can never pick a victim workspace.</li>
 *   <li>{@link ToolCallValidator} — name-whitelist + R/D/C/Forbidden tier gate. An unknown tool
 *       name or a Forbidden-tier mapping is rejected + logged here, never executed.</li>
 * </ol>
 *
 * <p><b>Governing rule (06-MEERA-PERMISSIONS-MATRIX.md):</b> Meera proposes; Spring disposes; the
 * human commits money. There is no route here for bid-approval, payment-method changes, payout
 * config, or code/config — those capabilities have no endpoint at all (structural absence, not a
 * soft block). {@code request_payment} only ever returns {@code PENDING_CONFIRM}; the actual money
 * movement happens on a wholly separate public endpoint the browser calls on human click.
 */
@RestController
@RequestMapping("/internal/meera")
public class MeeraInternalController {

    /** Forwarded human access JWT — same header name {@code clients/spring.py} sends. */
    private static final String ON_BEHALF_HEADER = "X-Onbehalf-Authorization";

    /** Idempotency key header — {@code tool_use.id + workspace_id} for tool calls, {@code turn_id} for /messages. */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final OnBehalfAuthResolver onBehalfAuthResolver;
    private final ToolCallValidator toolCallValidator;
    private final ShowCreatorsExecutor showCreatorsExecutor;
    private final CalculateBudgetExecutor calculateBudgetExecutor;
    private final CreateCampaignExecutor createCampaignExecutor;
    private final RequestPaymentExecutor requestPaymentExecutor;
    private final ConfirmLaunchExecutor confirmLaunchExecutor;
    private final GetCampaignPerformanceExecutor getCampaignPerformanceExecutor;
    private final MeeraSessionService sessionService;
    private final AnalyzeSiteTriggerService analyzeSiteTriggerService;
    private final MeeraContextService contextService;
    private final MeeraInteractionLogService meeraInteractionLogService;

    public MeeraInternalController(
            OnBehalfAuthResolver onBehalfAuthResolver,
            ToolCallValidator toolCallValidator,
            ShowCreatorsExecutor showCreatorsExecutor,
            CalculateBudgetExecutor calculateBudgetExecutor,
            CreateCampaignExecutor createCampaignExecutor,
            RequestPaymentExecutor requestPaymentExecutor,
            ConfirmLaunchExecutor confirmLaunchExecutor,
            GetCampaignPerformanceExecutor getCampaignPerformanceExecutor,
            MeeraSessionService sessionService,
            AnalyzeSiteTriggerService analyzeSiteTriggerService,
            MeeraContextService contextService,
            MeeraInteractionLogService meeraInteractionLogService) {
        this.onBehalfAuthResolver = onBehalfAuthResolver;
        this.toolCallValidator = toolCallValidator;
        this.showCreatorsExecutor = showCreatorsExecutor;
        this.calculateBudgetExecutor = calculateBudgetExecutor;
        this.createCampaignExecutor = createCampaignExecutor;
        this.requestPaymentExecutor = requestPaymentExecutor;
        this.confirmLaunchExecutor = confirmLaunchExecutor;
        this.getCampaignPerformanceExecutor = getCampaignPerformanceExecutor;
        this.sessionService = sessionService;
        this.analyzeSiteTriggerService = analyzeSiteTriggerService;
        this.contextService = contextService;
        this.meeraInteractionLogService = meeraInteractionLogService;
    }

    /**
     * Server-sources Block B for influora-ai (Platform-AI Phase 1, Wave 1a — Priya A2). POST, not
     * GET: the on-behalf JWT + HMAC mesh signature cover the request body, and Priya's ruling on
     * A1's correction is explicit that a GET's querystring-carried {@code audience} would ride
     * UNSIGNED unless the HMAC canonical string is changed everywhere else too — POST with a
     * signed JSON body is the least-ambiguous fix. Same dual-credential mesh gate as every other
     * {@code /internal/meera/*} route; unlike the tool routes this is a plain read with no
     * tool-tier concept, so it uses {@link OnBehalfAuthResolver#resolveForWorkspace} (matching
     * {@code /messages} and {@code /turns/release}), not the scope-gated variants — there is no
     * new auth here, per Priya's ruling.
     */
    @PostMapping("/context")
    public ResponseEntity<ApiResponse<ContextResponse>> context(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @Valid @RequestBody ContextRequest body) {
        onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, body.workspaceId());
        ContextResponse result = contextService.assemble(body.workspaceId(), body.audience());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/show_creators")
    public ResponseEntity<ApiResponse<ShowCreatorsResult>> showCreators(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        // SECURITY FIX (Kabir's SECURITY FIX #1 follow-up #1 — scope enforcement, see
        // OnBehalfAuthResolver class javadoc): asserts the on-behalf token's scope claim actually
        // authorizes THIS tool, not just that the token is valid for the workspace.
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        onBehalfJwt, workspaceId, MeeraToolName.show_creators.name());
        requireTool(MeeraToolName.show_creators, workspaceId);
        var result = showCreatorsExecutor.execute(ctx.workspaceId(), body);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/calculate_budget")
    public ResponseEntity<ApiResponse<CalculateBudgetResult>> calculateBudget(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        onBehalfJwt, workspaceId, MeeraToolName.calculate_budget.name());
        requireTool(MeeraToolName.calculate_budget, workspaceId);
        var result = calculateBudgetExecutor.execute(ctx.workspaceId(), body);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/create_campaign")
    public ResponseEntity<ApiResponse<CreateCampaignResult>> createCampaign(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        onBehalfJwt, workspaceId, MeeraToolName.create_campaign.name());
        requireTool(MeeraToolName.create_campaign, workspaceId);
        // BUG FIX (2026-07-23 live 409, "Column 'conversation_id' cannot be null"): the
        // create_campaign tool body is raw AI-proposed input + workspace_id (see class javadoc) —
        // it never carries a conversation_id field at all, so conversationIdOf(body) was always
        // null on this route and CampaignIntent.conversationId (NOT NULL, FK ai_conversations.id)
        // failed on insert. ctx.conversationId() is the JWT-verified, server-minted claim (see
        // OnBehalfAuthResolver.OnBehalfContext javadoc) — tenant-safe and always the real
        // conversation for this turn, unlike a client-body value.
        var result =
                createCampaignExecutor.execute(
                        ctx.workspaceId(), ctx.conversationId(), ctx.userId(), idempotencyKey, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    @PostMapping("/request_payment")
    public ResponseEntity<ApiResponse<RequestPaymentResult>> requestPayment(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        // C-tier: on-behalf resolution requires OWNER/ADMIN even though this only STAGES a
        // PENDING_CONFIRM action — the human-confirm click still belongs to a workspace member
        // who can act on money, matching the matrix's "human confirms" model. Also now scope-gated
        // (see OnBehalfAuthResolver class javadoc) — both checks must pass.
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringElevatedRoleAndScope(
                        onBehalfJwt, workspaceId, MeeraToolName.request_payment.name());
        requireTool(MeeraToolName.request_payment, workspaceId);
        // BUG FIX (2026-07-23 follow-up to create_campaign): like create_campaign, the
        // request_payment tool body is raw AI-proposed input + workspace_id (see class javadoc) —
        // it never carries a conversation_id field, so conversationIdOf(body) was always null and
        // the meera_tool_calls ledger row was written with no conversation link. ctx.conversationId()
        // is the JWT-verified, server-minted claim (see OnBehalfAuthResolver.OnBehalfContext javadoc)
        // — tenant-safe and always the real conversation for this turn, unlike a client-body value.
        var result =
                requestPaymentExecutor.execute(
                        ctx.workspaceId(), ctx.conversationId(), idempotencyKey, body);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/confirm_launch")
    public ResponseEntity<ApiResponse<ConfirmLaunchResult>> confirmLaunch(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringElevatedRoleAndScope(
                        onBehalfJwt, workspaceId, MeeraToolName.confirm_launch.name());
        requireTool(MeeraToolName.confirm_launch, workspaceId);
        // BUG FIX (2026-07-23 follow-up to create_campaign): same reasoning as request_payment
        // above. The confirm_launch body carries no conversation_id, so conversationIdOf(body) was
        // always null — leaving both the meera_tool_calls ledger row AND the fire-and-forget
        // DRAFT_FUNDED flywheel event (MeeraInteractionLog.sessionId) with no conversation link.
        // ctx.conversationId() is the JWT-verified, server-minted claim (see
        // OnBehalfAuthResolver.OnBehalfContext javadoc) — tenant-safe and always the real
        // conversation for this turn.
        var result =
                confirmLaunchExecutor.execute(
                        ctx.workspaceId(), ctx.conversationId(), idempotencyKey, body);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Phase 2 item 2.2 — R-tier, read-only, no money. Same auth shape as {@code calculate_budget}/
     * {@code show_creators} (scope-gated {@link OnBehalfAuthResolver#resolveForWorkspaceRequiringScope},
     * not the elevated-role C-tier variant) — no new auth pattern introduced. IDOR is enforced
     * inside {@link GetCampaignPerformanceExecutor}, not here (see its class javadoc).
     */
    @PostMapping("/get_campaign_performance")
    public ResponseEntity<ApiResponse<GetCampaignPerformanceResult>> getCampaignPerformance(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        onBehalfJwt, workspaceId, MeeraToolName.get_campaign_performance.name());
        requireTool(MeeraToolName.get_campaign_performance, workspaceId);
        var result = getCampaignPerformanceExecutor.execute(ctx.workspaceId(), body);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Write-back callback for the real assistant turn (11-AI-FLOW-DETAILED.md Flow 2 step 6). Not
     * a tool-call — no {@link ToolCallValidator} gate applies — but still runs behind the same
     * dual-credential mesh filter as every other {@code /internal/meera/*} route. The body has no
     * {@code workspaceId} (Python doesn't have one to send here); the tenant is resolved from the
     * conversation itself and cross-checked against the on-behalf JWT before anything is persisted.
     */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<MessageWritebackResult>> persistTurnWriteback(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody MessageWriteback body) {
        AiConversation conversation = sessionService.resolveConversation(body.conversationId());
        onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, conversation.getWorkspaceId());

        var message =
                sessionService.persistAssistantWriteback(
                        conversation.getWorkspaceId(),
                        body.conversationId(),
                        body.content(),
                        body.metadata(),
                        idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(new MessageWritebackResult(message.getId())));
    }

    /**
     * SECURITY FIX (Wave 2 round 2, Kabir FAILs #1/#2): refund route influora-ai calls on a
     * genuine PROVIDER failure (an {@code error} SSE event, or the stream ending with no assistant
     * text / before {@code done}) — NEVER for a plain client disconnect, which stays charged (see
     * {@code app/routes/chat.py}'s explicit separation of the two cases). Same dual-credential mesh
     * gate and same tenant-resolution pattern as {@link #persistTurnWriteback} above: no {@code
     * workspaceId} in the body (Python doesn't have one to send here either), tenant resolved from
     * {@code conversationId} and cross-checked against the on-behalf JWT before anything is
     * mutated. No {@code Idempotency-Key} header here — {@link MeeraSessionService#releaseTurnCredit}
     * / {@link com.influora.service.meera.AICreditService#release} are already self-idempotent,
     * keyed on {@code body.turnId()} (the server-verified {@code messageId}), so a duplicate or
     * racing call is a no-op there.
     */
    @PostMapping("/turns/release")
    public ResponseEntity<ApiResponse<Void>> releaseTurnCredit(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @Valid @RequestBody ReleaseTurnRequest body) {
        AiConversation conversation = sessionService.resolveConversation(body.conversationId());
        onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, conversation.getWorkspaceId());

        sessionService.releaseTurnCredit(conversation.getWorkspaceId(), body.turnId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Write-back for the Meera CHAT tool loop's LOCAL {@code analyze_site} tool (root cause:
     * {@code influora-ai/app/tools/loop.py} runs {@code perform_site_analysis} in-process for a
     * fast reply — see {@code is_local_tool} — so the result only ever fed back into that turn's
     * Claude reply and was never persisted; {@code BrandProfile.analysisStatus} stayed {@code
     * PENDING} forever for a chat-pasted URL, even though the FORM/onboarding path already wired
     * this via {@link AnalyzeSiteTriggerService#trigger}). Same dual-credential mesh gate as every
     * other {@code /internal/meera/*} route; same tenant-resolution pattern as {@link
     * #persistTurnWriteback}/{@link #releaseTurnCredit} — unlike those two, {@code workspaceId} IS
     * present in the body here (Python's local-tool path already has one), so it's cross-checked
     * directly against the on-behalf JWT before anything is persisted. Not a {@link
     * ToolCallValidator}-gated tool call — {@code analyze_site} never reaches Spring as a proposed
     * tool at all; this is a pure persistence callback, same reasoning as {@link
     * #persistTurnWriteback}.
     */
    @PostMapping("/analyze_site_result")
    public ResponseEntity<ApiResponse<Void>> analyzeSiteResult(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @Valid @RequestBody AnalyzeSiteChatResult body) {
        onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, body.workspaceId());
        analyzeSiteTriggerService.applyChatResult(
                body.workspaceId(),
                body.url(),
                body.success(),
                body.data(),
                body.error() != null ? body.error().message() : null);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * Write path for Phase 2 item 2.3's {@code OPTIONS_PRESENTED} flywheel event (Meera:
     * Label-to-Moat build plan §2.3, Priya's Q3 ruling). {@code present_options} is a Python LOCAL
     * tool — it never reaches Spring as a proposed tool call, so this is a dedicated logging
     * write-back, same shape as {@link #analyzeSiteResult}: same dual-credential mesh gate as
     * every other {@code /internal/meera/*} route, NOT a {@link ToolCallValidator}-gated tool call
     * (there is no tool tier here, only a fire-and-forget analytics append).
     *
     * <p><b>SR-1:</b> {@code workspace_id} in the body is only used for {@link
     * OnBehalfAuthResolver#resolveForWorkspace}'s cross-check against the signed on-behalf JWT —
     * the row is persisted under {@code ctx.workspaceId()} (the JWT-verified value), never the
     * raw body field directly.
     */
    @PostMapping("/interaction-log")
    public ResponseEntity<ApiResponse<Void>> interactionLog(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx = onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, workspaceId);
        meeraInteractionLogService.record(
                ctx.workspaceId(),
                stringOrNull(body.get("session_id")),
                MeeraInteractionEventType.OPTIONS_PRESENTED,
                stringOrNull(body.get("tool_name")),
                null,
                stringOrNull(body.get("campaign_id")),
                null,
                stringOrNull(body.get("prompt_version")));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** Throws (mapped to 4xx by {@code GlobalExceptionHandler}) if the tool fails the whitelist/tier gate. */
    private void requireTool(MeeraToolName expected, String workspaceId) {
        MeeraToolName resolved;
        try {
            resolved = toolCallValidator.validateAndResolve(expected.name(), workspaceId);
        } catch (ToolCallRejectedException e) {
            throw new ApiException(e.getReasonCode(), e.getMessage(), HttpStatus.FORBIDDEN);
        }
        if (resolved != expected) {
            throw new ApiException(
                    "TOOL_ROUTE_MISMATCH", "Resolved tool does not match the called route", HttpStatus.FORBIDDEN);
        }
    }

    /** {@code workspace_id} is always snake_case in the body — Python merges it in verbatim, never renamed. */
    private static String requireWorkspaceId(Map<String, Object> body) {
        Object value = body == null ? null : body.get("workspace_id");
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ApiException(
                    "WORKSPACE_ID_REQUIRED", "Request body must include workspace_id", HttpStatus.BAD_REQUEST);
        }
        return String.valueOf(value);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
