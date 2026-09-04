package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.integration.ai.MeeraVoiceAiClient;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

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

    /**
     * Mirrors {@link MeeraController#MAX_VOICE_CLIP_BYTES} exactly (Kabir H-1) — same DoS/OOM
     * guard, same silent-fallback-not-413 contract, applied to the CREATOR-audience upload leg.
     */
    private static final long MAX_VOICE_CLIP_BYTES = 10L * 1024 * 1024;

    private final MeeraSessionService sessionService;
    private final CreatorContextService creatorContext;
    private final MeeraStreamProperties streamProperties;
    private final CreatorAgentPreferencesService preferencesService;
    private final MeeraVoiceAiClient voiceAiClient;
    private final MeeraCreatorFeatureProperties featureProperties;

    public CreatorMeeraController(
            MeeraSessionService sessionService,
            CreatorContextService creatorContext,
            MeeraStreamProperties streamProperties,
            CreatorAgentPreferencesService preferencesService,
            MeeraVoiceAiClient voiceAiClient,
            MeeraCreatorFeatureProperties featureProperties) {
        this.sessionService = sessionService;
        this.creatorContext = creatorContext;
        this.streamProperties = streamProperties;
        this.preferencesService = preferencesService;
        this.voiceAiClient = voiceAiClient;
        this.featureProperties = featureProperties;
    }

    /**
     * Priya gate review defect 4 — the Phase A rollback flag, applied to EVERY route on this
     * controller (all of {@code /creator/meera/**}). Called first in every handler, before {@link
     * CreatorContextService#requireCreatorProfile} or {@link #requireConsent} run, so a disabled
     * feature 404s uniformly regardless of the caller's identity or consent state.
     */
    private void requireFeatureEnabled() {
        if (!featureProperties.isCreatorEnabled()) {
            throw new ApiException(
                    "FEATURE_DISABLED", "Meera for Creators is currently disabled", HttpStatus.NOT_FOUND);
        }
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
     *
     * <p>Gate fix round 2, item 1 (Priya Q3) — {@link
     * CreatorAgentPreferencesService#isConsentAccepted} (via {@code
     * CreatorAgentPreferences#isConsentAccepted}) now requires the stored {@code consentVersion}
     * to equal {@code CreatorAgentPreferences#CURRENT_CONSENT_VERSION}, not merely a non-null
     * timestamp — so a creator who consented under a since-superseded DPDP notice is rejected here
     * with the same {@code 403 CONSENT_REQUIRED} as a creator who never consented at all, without
     * this method needing its own separate version comparison.
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
        requireFeatureEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        String creatorUserId = profile.getUserId();
        requireConsent(creatorUserId);

        // Gate fix round 1 (Priya Q1, SPEC.md 4.7/A10) — startOrResumeForCreator persists the
        // day-one onboarding greeting as a real ASSISTANT message on first creation; the generic
        // startOrResume (still used by the BRAND-audience MeeraController) creates a bare
        // conversation with no message at all.
        //
        // Gate fix round 4 (Priya's fourth pass) — the persisted greeting must be language-aware.
        // requireConsent() above already guarantees a creator_agent_preferences row exists (consent
        // can only ever be recorded via CreatorAgentPreferencesService#recordConsent, which creates
        // the row with computed defaults first), so getOrCreatePreferences here never actually
        // creates a new row on this path -- it just reads the language the creator already has.
        String creatorLanguage = preferencesService.getOrCreatePreferences(creatorUserId).creatorLanguage();
        if (creatorLanguage == null || creatorLanguage.isBlank()) {
            creatorLanguage = CreatorAgentPreferences.DEFAULT_LANGUAGE;
        }
        AiConversation conversation =
                sessionService.startOrResumeForCreator(
                        creatorUserId, principal.getUserId(), profile.getDisplayName(), creatorLanguage);

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
        requireFeatureEnabled();
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
        requireFeatureEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);

        List<AiMessage> messages =
                sessionService.listMessages(profile.getUserId(), conversationId, after);
        List<MessageHistoryItem> response =
                messages.stream()
                        .map(m -> new MessageHistoryItem(m.getId(), m.getRole().name(), m.getContent()))
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Priya gate review defect 3 — CREATOR-audience mirror of {@link MeeraController#speak}, EXACT
     * same request/response contract (frontend's {@code src/lib/meera-api.ts} works unchanged once
     * its creator null-returns are removed) so it can share {@code meeraApi.speak}'s call shape.
     * Identity is resolved via {@link CreatorContextService#requireCreatorProfile} (never a
     * body-supplied id), and the resolved creator's OWN user id is threaded through to {@link
     * MeeraVoiceAiClient#speak} in the {@code workspaceId} parameter slot — the same "{@code
     * workspace_id} carries a creator user id on the CREATOR audience" convention {@link
     * com.influora.service.meera.MeeraContextService#assembleCreatorContext} already documents, so
     * the minted service token's {@code workspace_id} claim matches what influora-ai's CREATOR-
     * audience context resolution (and consent check) expects — never a BRAND workspace id.
     *
     * <p>DPDP consent PRECONDITION ({@link #requireConsent}) applies here exactly as it does to
     * {@link #sendTurn} — an unconsented creator gets {@code 403 CONSENT_REQUIRED} before any
     * provider call is attempted.
     */
    @PostMapping("/voice/speak")
    public ResponseEntity<?> speak(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody VoiceSpeakRequest body) {
        requireFeatureEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        String creatorUserId = profile.getUserId();
        requireConsent(creatorUserId);

        MeeraVoiceAiClient.SpeakResult result = voiceAiClient.speak(creatorUserId, body.text(), body.lang());
        if (result.ok()) {
            MediaType mediaType;
            try {
                mediaType =
                        (result.contentType() == null || result.contentType().isBlank())
                                ? MediaType.parseMediaType("audio/wav")
                                : MediaType.parseMediaType(result.contentType());
            } catch (Exception e) {
                mediaType = MediaType.parseMediaType("audio/wav");
            }
            return ResponseEntity.ok().contentType(mediaType).body(result.audioBytes());
        }

        return ResponseEntity.ok(Map.of("fallback", true));
    }

    /**
     * Priya gate review defect 3 — CREATOR-audience mirror of {@link MeeraController#transcribe},
     * EXACT same {@code multipart/form-data} request shape (single {@code audio} file part) and
     * response contract (real transcript JSON, or a silent {@code {"fallback": true}} 200 — never a
     * 4xx/5xx for a provider/transport hiccup). Same {@link #MAX_VOICE_CLIP_BYTES} DoS/OOM guard
     * (Kabir H-1) and the same identity/consent discipline as {@link #speak} above.
     */
    @PostMapping(value = "/voice/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(value = "audio", required = false) MultipartFile audio) {
        requireFeatureEnabled();
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        String creatorUserId = profile.getUserId();
        requireConsent(creatorUserId);

        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.ok(Map.of("fallback", true));
        }

        if (audio.getSize() > MAX_VOICE_CLIP_BYTES) {
            return ResponseEntity.ok(Map.of("fallback", true));
        }

        byte[] audioBytes;
        try {
            audioBytes = audio.getBytes();
        } catch (IOException e) {
            return ResponseEntity.ok(Map.of("fallback", true));
        }

        MeeraVoiceAiClient.TranscribeResult result =
                voiceAiClient.transcribe(creatorUserId, audioBytes, audio.getContentType());
        if (result.ok()) {
            MediaType mediaType;
            try {
                mediaType =
                        (result.contentType() == null || result.contentType().isBlank())
                                ? MediaType.APPLICATION_JSON
                                : MediaType.parseMediaType(result.contentType());
            } catch (Exception e) {
                mediaType = MediaType.APPLICATION_JSON;
            }
            return ResponseEntity.ok().contentType(mediaType).body(result.jsonBytes());
        }

        return ResponseEntity.ok(Map.of("fallback", true));
    }

    /** Request body for {@link #speak} — identical shape to {@link MeeraController.VoiceSpeakRequest}. */
    public record VoiceSpeakRequest(
            @NotBlank @Size(max = 1000) String text, @Size(max = 20) String lang) {}
}
