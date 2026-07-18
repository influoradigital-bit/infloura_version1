package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.BrandProfile;
import com.influora.integration.ai.MeeraVoiceAiClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.meera.AICreditService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.web.dto.meera.MeeraDtos.BrandProfileResponse;
import com.influora.web.dto.meera.MeeraDtos.CreditStatusResponse;
import com.influora.web.dto.meera.MeeraDtos.CreditsSummary;
import com.influora.web.dto.meera.MeeraDtos.MessageHistoryItem;
import com.influora.web.dto.meera.MeeraDtos.SendTurnRequest;
import com.influora.web.dto.meera.MeeraDtos.SendTurnResponse;
import com.influora.web.dto.meera.MeeraDtos.SessionStartResponse;
import com.influora.common.JsonLists;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
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
 * Public brand-facing Meera surface (02-API-CONTRACT-BRAND.md §1). {@code sendTurn} is
 * streaming-first (Priya's locked architecture): it charge-gates (see {@link
 * com.influora.service.meera.AICreditService#tryConsumeForTurn} — SECURITY FIX, Wave 2 round 2,
 * Kabir FAILs #1/#2: this ACTUALLY decrements credit at send now, not a non-decrementing
 * pre-check), persists the USER message, and mints a scoped stream token, then returns immediately
 * with {@code reply=null}. The browser connects DIRECTLY to influora-ai's {@code POST /chat} SSE
 * endpoint (at {@code streamUrl}) with its own on-behalf JWT so tool calls and Living Canvas stages
 * work — Spring never proxies the stream. The ASSISTANT turn is persisted (charge already spent,
 * at send) when influora-ai's end-of-stream write-back lands at {@code POST
 * /internal/meera/messages} ({@link MeeraSessionService#persistAssistantWriteback}); if the
 * provider call fails instead, influora-ai calls {@code POST /internal/meera/turns/release}
 * ({@link MeeraSessionService#releaseTurnCredit}) to refund the charge — never on a plain client
 * disconnect, which stays charged. See {@code src/hooks/useMeeraStream.ts} / {@code
 * MeeraChatPanel.handleLiveSend} for the client half of this contract.
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
    private final MeeraVoiceAiClient voiceAiClient;

    /**
     * Endpoint-LOCAL voice-clip size cap (Kabir H-1). The GLOBAL multipart cap
     * (spring.servlet.multipart.max-file-size 500MB / max-request-size 1GB) is deliberately huge for
     * deliverable uploads elsewhere and MUST NOT be lowered — but a voice clip is tiny (a few seconds
     * of Opus/webm is tens of KB), so accepting hundreds of MB here just to buffer it into a byte[]
     * for the STT proxy is a DoS/OOM vector. 10MB is generous headroom for any legitimate clip while
     * bounding {@code getBytes()}. Over-limit is a SILENT fallback (200 {@code {"fallback":true}}),
     * never a 413/500 — the locked contract keeps auth as the ONLY non-200 case (M-3).
     */
    private static final long MAX_VOICE_CLIP_BYTES = 10L * 1024 * 1024;

    public MeeraController(
            MeeraSessionService sessionService,
            AICreditService creditService,
            BrandContextService brandContextService,
            MeeraStreamProperties streamProperties,
            MeeraVoiceAiClient voiceAiClient) {
        this.sessionService = sessionService;
        this.creditService = creditService;
        this.brandContextService = brandContextService;
        this.streamProperties = streamProperties;
        this.voiceAiClient = voiceAiClient;
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
                        principal.getUserType(),
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
                        result.placeholderReply(),
                        // Streaming-first: the browser needs workspaceId to build its own SSE
                        // request body (chat.py requires workspace_id and 403s a mismatch).
                        workspaceId,
                        // SECURITY FIX #1 (docs/security/meera-onbehalf-auth-security-design.md
                        // §2): the dedicated per-turn on-behalf credential — the browser forwards
                        // THIS as onbehalf_jwt, never a localStorage-read full access token.
                        result.onBehalfToken());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * F10 — history route (backend half): reopening a chat previously had no way to load prior
     * turns. Path/shape match {@code src/lib/meera-api.ts}'s {@code getMessagesAfter} exactly:
     * {@code GET /meera/sessions/{conversationId}/messages?after={messageId}}. {@code after} is
     * optional (the frontend client omits the query param entirely when called with an empty
     * string) — omitted/blank returns the full history, present returns only messages created
     * after that message id.
     */
    @GetMapping("/sessions/{conversationId}/messages")
    public ResponseEntity<ApiResponse<List<MessageHistoryItem>>> messages(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestParam(value = "after", required = false) String after) {
        var workspace = brandContextService.requireBrandWorkspace(principal);

        List<AiMessage> messages = sessionService.listMessages(workspace.getId(), conversationId, after);
        List<MessageHistoryItem> response =
                messages.stream()
                        .map(m -> new MessageHistoryItem(m.getId(), m.getRole().name(), m.getContent()))
                        .toList();
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

    /**
     * Voice-output proxy: the browser posts the (already-rendered) reply text here to get back
     * Sarvam TTS audio, scoped off {@code principal}'s workspace (never a body-supplied id,
     * Guardrail 4) via {@code MeeraVoiceAiClient}, which forwards to influora-ai's {@code POST
     * /voice/speak} with a minted service token. {@code text} is capped at 1000 chars here purely
     * to bound the request payload — influora-ai truncates further to ~200 chars itself (P18) for
     * TTS cost control.
     *
     * <p><b>Response contract is LOCKED</b> (frontend built against this in parallel): success is
     * 200 with raw {@code audio/wav} (or whatever content type influora-ai reports) bytes; any
     * provider fallback or failure is ALSO 200, with a bare JSON body {@code {"fallback": true}} —
     * voice output must degrade silently, never a 4xx/5xx for a TTS provider hiccup. The only
     * non-200 case is this endpoint's own auth gate ({@code requireBrandWorkspace}, unchanged from
     * every other route on this controller) and Bean Validation on {@code text}.
     */
    @PostMapping("/voice/speak")
    public ResponseEntity<?> speak(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody VoiceSpeakRequest body) {
        var workspace = brandContextService.requireBrandWorkspace(principal);

        MeeraVoiceAiClient.SpeakResult result = voiceAiClient.speak(workspace.getId(), body.text());
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
     * Voice-INPUT proxy: the browser posts a recorded audio clip here to get back a transcript,
     * scoped off {@code principal}'s workspace (never a body-supplied id, Guardrail 4 — same as
     * {@link #speak}) via {@code MeeraVoiceAiClient}, which forwards to influora-ai's {@code POST
     * /voice/transcribe} (Sarvam STT + Gemini cleanup) with a minted service token. This is the
     * mirror image of {@link #speak}: the frontend does Sarvam-STT-first and falls back to the
     * browser's {@code webkitSpeechRecognition} whenever this endpoint signals a fallback.
     *
     * <p><b>Request:</b> {@code multipart/form-data} with a single file part named {@code audio}
     * (the recorded clip, e.g. {@code audio/webm}); the browser sends NO workspace id (it's derived
     * from auth). <b>Response contract is LOCKED</b> (frontend built against this in parallel):
     * success is 200 with influora-ai's transcript JSON forwarded verbatim ({@code {raw_transcript,
     * cleaned_text, lang_detected, fallback}}); any provider miss, transport failure, missing/empty
     * audio, or Python's own soft-fail is ALSO 200 — either Python's {@code {fallback:true,message}}
     * envelope passed through, or a bare {@code {"fallback": true}} synthesized here. Voice input
     * must degrade silently, NEVER a 4xx/5xx for a provider/transport hiccup. The only non-200 case
     * is this endpoint's own auth gate ({@code requireBrandWorkspace}, unchanged from every other
     * route on this controller), so the browser can always distinguish "not signed in" from
     * "transcription unavailable, use the native recognizer".
     */
    @PostMapping(value = "/voice/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(value = "audio", required = false) MultipartFile audio) {
        var workspace = brandContextService.requireBrandWorkspace(principal);

        // Missing/empty audio is a silent fallback, not a 400 — keeps the "never a dead end"
        // contract so a flaky recorder still lets the browser fall back to its native recognizer.
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.ok(Map.of("fallback", true));
        }

        // Kabir H-1 — endpoint-local DoS/OOM guard BEFORE getBytes() buffers the whole part into a
        // byte[]. The global multipart cap is intentionally huge for deliverable uploads; a voice
        // clip that exceeds MAX_VOICE_CLIP_BYTES is a silent fallback (200), never a 413/500, so the
        // "only auth is non-200" contract holds and the browser falls back to webkitSpeechRecognition.
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
                voiceAiClient.transcribe(workspace.getId(), audioBytes, audio.getContentType());
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

    /** Request body for {@link #speak}. Deliberately local to this controller rather than {@code
     * MeeraDtos} — this endpoint's shape (bare {@code {"fallback": true}} JSON / raw audio bytes on
     * the response side) is intentionally outside the {@code ApiResponse} envelope every other
     * route here uses, so it doesn't share that file's conventions either. */
    public record VoiceSpeakRequest(@NotBlank @Size(max = 1000) String text) {}
}
