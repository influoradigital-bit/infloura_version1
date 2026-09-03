package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorContextService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.web.dto.meera.MeeraDtos.CreditsSummary;
import com.influora.web.dto.meera.MeeraDtos.MessageHistoryItem;
import com.influora.web.dto.meera.MeeraDtos.SendTurnRequest;
import com.influora.web.dto.meera.MeeraDtos.SendTurnResponse;
import com.influora.web.dto.meera.MeeraDtos.SessionStartResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-MEERA-CREATOR-PHASE-A (fix round 1, item 2) — the CREATOR-audience counterpart to {@link
 * MeeraController}, which hard-gates every route through {@code
 * BrandContextService#requireBrandWorkspace} and so unconditionally 403s a CREATOR principal.
 * Before this controller existed, no creator could ever start a Meera session or obtain a stream
 * token / on-behalf token — the entire CREATOR branch in influora-ai's {@code chat.py} (consent
 * gate, creator Block B, empty tool set) was unreachable end-to-end.
 *
 * <p>Identity is always resolved from {@code principal.getUserId()} via {@link
 * CreatorContextService#requireCreatorProfile} — never a body/path id (same discipline as every
 * other creator-scoped controller). The workspaceId threaded into {@link MeeraSessionService} and
 * minted into both the stream token and the on-behalf token is deliberately the creator's own
 * USER id (see {@code MeeraContextService#assembleCreatorContext} javadoc for why {@code
 * workspace_id} carries a creator user id on this audience) — the on-behalf token's {@code
 * workspaceId} claim MUST equal {@code principal.getUserId()} or every subsequent creator turn
 * (starting with {@code POST /internal/meera/context}, which does {@code
 * creatorProfileRepository.findByUserId(workspaceId)}) 404s.
 *
 * <p>Phase A is conversational + profile/deals summary only (SPEC.md A4): there is no credits or
 * brand-profile route here, and no money tools reach a CREATOR turn at all — {@link
 * MeeraSessionService#sendTurn} never charges the brand AI-credit ledger for a CREATOR turn (see
 * its class javadoc); influora-ai's {@code spend_tracker} enforces the per-creator monthly cap on
 * its own side (A8).
 */
@RestController
@RequestMapping("/creator/meera")
public class CreatorMeeraController {

    private final MeeraSessionService sessionService;
    private final CreatorContextService creatorContext;
    private final MeeraStreamProperties streamProperties;
    private final CreatorAgentPreferencesService preferencesService;

    public CreatorMeeraController(
            MeeraSessionService sessionService,
            CreatorContextService creatorContext,
            MeeraStreamProperties streamProperties,
            CreatorAgentPreferencesService preferencesService) {
        this.sessionService = sessionService;
        this.creatorContext = creatorContext;
        this.streamProperties = streamProperties;
        this.preferencesService = preferencesService;
    }

    /**
     * DPDP consent PRECONDITION (fix round 2, item 1 — Priya Q3). Must run BEFORE anything is
     * persisted or minted for a Meera turn: previously an unconsented creator's message was
     * written to {@code ai_messages} and a {@code meera_creator_conversations} row created/bumped
     * (in {@link MeeraSessionService#sendTurn}) before influora-ai's Python-side {@code
     * CONSENT_REQUIRED} 403 ever fired — the platform had already stored the personal data the
     * consent screen exists to gate. This check now runs first, in Spring, so an unconsented
     * creator's turn is rejected at the controller and nothing downstream ever sees it. The Python
     * gate ({@code chat.py}'s {@code consent_accepted} check) stays as defence-in-depth.
     */
    private void requireConsent(String creatorUserId) {
        if (!preferencesService.isConsentAccepted(creatorUserId)) {
            throw new ApiException(
                    "CONSENT_REQUIRED",
                    "Consent is required before using Meera",
                    HttpStatus.FORBIDDEN);
        }
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SessionStartResponse>> startSession(
            @AuthenticationPrincipal AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        String creatorUserId = profile.getUserId();
        requireConsent(creatorUserId);

        // Gate fix round 1 (Priya Q1, SPEC.md 4.7/A10) — startOrResumeForCreator persists the
        // day-one onboarding greeting as a real ASSISTANT message on first creation; the generic
        // startOrResume (still used by the BRAND-audience MeeraController) creates a bare
        // conversation with no message at all.
        AiConversation conversation =
                sessionService.startOrResumeForCreator(
                        creatorUserId, principal.getUserId(), profile.getDisplayName());

        var response =
                new SessionStartResponse(
                        conversation.getId(),
                        conversation.getStatus().name(),
                        // No brand-profile concept for a creator session, and no AI-credit concept
                        // either (see class javadoc) -- both NON_NULL-annotated fields are simply
                        // omitted from the response.
                        null,
                        (CreditsSummary) null);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/sessions/{conversationId}/messages")
    public ResponseEntity<ApiResponse<SendTurnResponse>> sendTurn(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SendTurnRequest body) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        String creatorUserId = profile.getUserId();
        requireConsent(creatorUserId);

        var result =
                sessionService.sendTurn(
                        creatorUserId,
                        principal.getUserId(),
                        UserType.CREATOR,
                        conversationId,
                        body.content(),
                        idempotencyKey);

        var response =
                new SendTurnResponse(
                        result.userMessageId(),
                        result.assistantMessageId(),
                        result.streamToken(),
                        streamProperties.getPublicChatUrl(),
                        // No AI-credit concept for a creator turn (see class javadoc) -- 0 is not
                        // "credits remaining", it is simply unused on this audience.
                        0,
                        result.placeholderReply(),
                        creatorUserId,
                        result.onBehalfToken());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** Mirrors {@link MeeraController#messages} — same {@code after}-cursor contract. */
    @GetMapping("/sessions/{conversationId}/messages")
    public ResponseEntity<ApiResponse<List<MessageHistoryItem>>> messages(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestParam(value = "after", required = false) String after) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        List<AiMessage> messages =
                sessionService.listMessages(profile.getUserId(), conversationId, after);
        List<MessageHistoryItem> response =
                messages.stream()
                        .map(m -> new MessageHistoryItem(m.getId(), m.getRole().name(), m.getContent()))
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
