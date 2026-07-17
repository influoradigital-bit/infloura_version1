package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.enums.MeeraToolName;
import com.influora.security.OnBehalfAuthResolver;
import com.influora.security.OnBehalfAuthResolver.OnBehalfContext;
import com.influora.service.meera.MeeraSessionService;
import com.influora.service.meera.tool.CalculateBudgetExecutor;
import com.influora.service.meera.tool.ConfirmLaunchExecutor;
import com.influora.service.meera.tool.CreateCampaignExecutor;
import com.influora.service.meera.tool.RequestPaymentExecutor;
import com.influora.service.meera.tool.ShowCreatorsExecutor;
import com.influora.service.meera.tool.ToolCallValidator;
import com.influora.service.meera.tool.ToolCallValidator.ToolCallRejectedException;
import com.influora.web.dto.meera.MeeraToolDtos.CalculateBudgetResult;
import com.influora.web.dto.meera.MeeraToolDtos.ConfirmLaunchResult;
import com.influora.web.dto.meera.MeeraToolDtos.CreateCampaignResult;
import com.influora.web.dto.meera.MeeraToolDtos.MessageWriteback;
import com.influora.web.dto.meera.MeeraToolDtos.MessageWritebackResult;
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
    private final MeeraSessionService sessionService;

    public MeeraInternalController(
            OnBehalfAuthResolver onBehalfAuthResolver,
            ToolCallValidator toolCallValidator,
            ShowCreatorsExecutor showCreatorsExecutor,
            CalculateBudgetExecutor calculateBudgetExecutor,
            CreateCampaignExecutor createCampaignExecutor,
            RequestPaymentExecutor requestPaymentExecutor,
            ConfirmLaunchExecutor confirmLaunchExecutor,
            MeeraSessionService sessionService) {
        this.onBehalfAuthResolver = onBehalfAuthResolver;
        this.toolCallValidator = toolCallValidator;
        this.showCreatorsExecutor = showCreatorsExecutor;
        this.calculateBudgetExecutor = calculateBudgetExecutor;
        this.createCampaignExecutor = createCampaignExecutor;
        this.requestPaymentExecutor = requestPaymentExecutor;
        this.confirmLaunchExecutor = confirmLaunchExecutor;
        this.sessionService = sessionService;
    }

    @PostMapping("/show_creators")
    public ResponseEntity<ApiResponse<ShowCreatorsResult>> showCreators(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx = onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, workspaceId);
        requireTool(MeeraToolName.show_creators, workspaceId);
        var result = showCreatorsExecutor.execute(ctx.workspaceId(), body);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/calculate_budget")
    public ResponseEntity<ApiResponse<CalculateBudgetResult>> calculateBudget(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx = onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, workspaceId);
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
        OnBehalfContext ctx = onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, workspaceId);
        requireTool(MeeraToolName.create_campaign, workspaceId);
        var result =
                createCampaignExecutor.execute(
                        ctx.workspaceId(), conversationIdOf(body), ctx.userId(), idempotencyKey, body);
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
        // who can act on money, matching the matrix's "human confirms" model.
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringElevatedRole(onBehalfJwt, workspaceId);
        requireTool(MeeraToolName.request_payment, workspaceId);
        var result =
                requestPaymentExecutor.execute(
                        ctx.workspaceId(), conversationIdOf(body), idempotencyKey, body);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/confirm_launch")
    public ResponseEntity<ApiResponse<ConfirmLaunchResult>> confirmLaunch(
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestBody Map<String, Object> body) {
        String workspaceId = requireWorkspaceId(body);
        OnBehalfContext ctx =
                onBehalfAuthResolver.resolveForWorkspaceRequiringElevatedRole(onBehalfJwt, workspaceId);
        requireTool(MeeraToolName.confirm_launch, workspaceId);
        var result =
                confirmLaunchExecutor.execute(
                        ctx.workspaceId(), conversationIdOf(body), idempotencyKey, body);
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
            @RequestHeader(ON_BEHALF_HEADER) String onBehalfJwt, @Valid @RequestBody MessageWriteback body) {
        AiConversation conversation = sessionService.resolveConversation(body.conversationId());
        onBehalfAuthResolver.resolveForWorkspace(onBehalfJwt, conversation.getWorkspaceId());

        var message =
                sessionService.persistAssistantWriteback(body.conversationId(), body.content(), body.metadata());
        return ResponseEntity.ok(ApiResponse.ok(new MessageWritebackResult(message.getId())));
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

    /** {@code conversation_id} is optional on most tool bodies — not every tool schema carries one. */
    private static String conversationIdOf(Map<String, Object> body) {
        Object value = body == null ? null : body.get("conversation_id");
        return value == null ? null : String.valueOf(value);
    }
}
