package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.BrandProfile;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.meera.AICreditService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.web.dto.meera.MeeraDtos.BrandProfileResponse;
import com.influora.web.dto.meera.MeeraDtos.CreditStatusResponse;
import com.influora.web.dto.meera.MeeraDtos.CreditsSummary;
import com.influora.web.dto.meera.MeeraDtos.SendTurnRequest;
import com.influora.web.dto.meera.MeeraDtos.SendTurnResponse;
import com.influora.web.dto.meera.MeeraDtos.SessionStartResponse;
import com.influora.common.JsonLists;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public brand-facing Meera surface (02-API-CONTRACT-BRAND.md §1). Read-only chat in this phase:
 * no tool-call execution happens here — {@code sendTurn} persists the turn, credit-gates, and
 * returns a placeholder reply (see {@link MeeraSessionService} class doc for the known gap).
 *
 * <p>Every endpoint is scoped off {@code principal.getWorkspaceId()} — never a body-supplied
 * workspace id (Guardrail 4).
 */
@RestController
@RequestMapping("/meera")
public class MeeraController {

    private final MeeraSessionService sessionService;
    private final AICreditService creditService;
    private final BrandContextService brandContextService;
    private final MeeraStreamProperties streamProperties;

    public MeeraController(
            MeeraSessionService sessionService,
            AICreditService creditService,
            BrandContextService brandContextService,
            MeeraStreamProperties streamProperties) {
        this.sessionService = sessionService;
        this.creditService = creditService;
        this.brandContextService = brandContextService;
        this.streamProperties = streamProperties;
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SessionStartResponse>> startSession(
            @AuthenticationPrincipal AuthPrincipal principal) {
        var workspace = brandContextService.requireBrandWorkspace(principal);
        String workspaceId = workspace.getId();

        AiConversation conversation = sessionService.startOrResume(workspaceId, principal.getUserId());
        BrandProfile profile = sessionService.getBrandProfile(workspaceId);
        var credit = creditService.getStatus(workspaceId);

        String profileStatus = profile != null ? profile.getAnalysisStatus().name() : "PENDING";
        boolean unlimited = credit.isUnlimited(Instant.now());

        var response =
                new SessionStartResponse(
                        conversation.getId(),
                        conversation.getStatus().name(),
                        profileStatus,
                        new CreditsSummary(credit.getCreditsRemaining(), unlimited));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/sessions/{conversationId}/messages")
    public ResponseEntity<ApiResponse<SendTurnResponse>> sendTurn(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SendTurnRequest body) {
        var workspace = brandContextService.requireBrandWorkspace(principal);
        String workspaceId = workspace.getId();

        var result =
                sessionService.sendTurn(
                        workspaceId,
                        principal.getUserId(),
                        conversationId,
                        body.content(),
                        idempotencyKey);
        var credit = creditService.getStatus(workspaceId);

        var response =
                new SendTurnResponse(
                        result.userMessageId(),
                        result.assistantMessageId(),
                        result.streamToken(),
                        // Browser-reachable Python /chat SSE URL — the browser connects here
                        // DIRECTLY with the scoped stream token above (Priya's locked
                        // architecture: no Spring proxy). Config-driven, never hardcoded —
                        // see influora.meera.stream.public-chat-url.
                        streamProperties.getPublicChatUrl(),
                        credit.getCreditsRemaining(),
                        result.placeholderReply());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/credits")
    public ResponseEntity<ApiResponse<CreditStatusResponse>> credits(
            @AuthenticationPrincipal AuthPrincipal principal) {
        var workspace = brandContextService.requireBrandWorkspace(principal);
        var credit = creditService.getStatus(workspace.getId());
        boolean unlimited = credit.isUnlimited(Instant.now());

        var response =
                new CreditStatusResponse(
                        credit.getCreditsRemaining(),
                        credit.getMonthlyAllotment(),
                        unlimited,
                        credit.getUnlimitedUntil(),
                        credit.getCycleStart(),
                        unlimited ? "UNLIMITED" : credit.getCreditsRemaining() > 0 ? "FREE" : "EXHAUSTED");
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/brand-profile")
    public ResponseEntity<ApiResponse<BrandProfileResponse>> brandProfile(
            @AuthenticationPrincipal AuthPrincipal principal) {
        var workspace = brandContextService.requireBrandWorkspace(principal);
        BrandProfile profile = sessionService.getBrandProfile(workspace.getId());

        if (profile == null) {
            var response =
                    new BrandProfileResponse(workspace.getId(), null, "PENDING", null, null, null);
            return ResponseEntity.ok(ApiResponse.ok(response));
        }

        var response =
                new BrandProfileResponse(
                        profile.getWorkspaceId(),
                        profile.getWebsiteUrl(),
                        profile.getAnalysisStatus().name(),
                        JsonLists.stringListFromJson(profile.getNicheTagsJson()),
                        JsonLists.objectFromJson(profile.getProductCatalogJson(), Object.class),
                        profile.getAnalysisError());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
