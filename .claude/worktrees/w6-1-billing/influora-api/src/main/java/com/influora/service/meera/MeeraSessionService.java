package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.MessageRole;
import com.influora.integration.ai.MeeraChatAiClient;
import com.influora.integration.ai.MeeraChatAiClient.ChatTurnResult;
import com.influora.integration.ai.MeeraChatAiException;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.IdempotencyService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session bookkeeping for Meera conversations — start/resume a conversation, persist turns.
 * Every method is tenant-scoped off {@code workspaceId} (Guardrail 4).
 *
 * <p><b>Wave 3 task A4 fix — the KNOWN GAP below is closed.</b> {@link #sendTurn} used to persist a
 * hardcoded placeholder ASSISTANT echo with no real LLM call. It now routes through influora-ai's
 * existing {@code POST /chat} (Domain D, 04-AI-SERVICE-SPEC.md) via {@link MeeraChatAiClient} —
 * the same Java-&gt;Python service-token pattern every other client in {@code integration/ai/*}
 * already uses (e.g. {@code AnalyzeSiteAiClient}) — and persists the REAL model reply exactly
 * once. See {@link #doSendTurn} for the full flow and {@link MeeraChatAiClient}'s javadoc for why
 * {@code onbehalf_jwt} is deliberately omitted on this call path. The independently-minted {@link
 * StreamTokenService} token is unaffected — the browser still connects directly to {@code /chat}
 * for its own live SSE experience per Priya's locked streaming architecture; this class's own
 * Python call is Spring's separate, synchronous turn used only to obtain and persist the
 * authoritative reply.
 */
@Service
public class MeeraSessionService {

    private static final Logger log = LoggerFactory.getLogger(MeeraSessionService.class);

    private static final int TURN_CREDIT_COST = 1;
    private static final String SEND_TURN_SCOPE = "meera.send_turn";
    private static final String PERSIST_WRITEBACK_SCOPE = "meera.persist_writeback";

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BrandProfileRepository brandProfileRepository;
    private final AICreditService creditService;
    private final BrandContextAssembler contextAssembler;
    private final StreamTokenService streamTokenService;
    private final IdempotencyService idempotencyService;
    private final MeeraChatAiClient chatAiClient;

    public MeeraSessionService(
            AiConversationRepository conversationRepository,
            AiMessageRepository messageRepository,
            WorkspaceRepository workspaceRepository,
            BrandProfileRepository brandProfileRepository,
            AICreditService creditService,
            BrandContextAssembler contextAssembler,
            StreamTokenService streamTokenService,
            IdempotencyService idempotencyService,
            MeeraChatAiClient chatAiClient) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.creditService = creditService;
        this.contextAssembler = contextAssembler;
        this.streamTokenService = streamTokenService;
        this.idempotencyService = idempotencyService;
        this.chatAiClient = chatAiClient;
    }

    /** Reuses the workspace's ACTIVE conversation, or opens a new one. Tenant-scoped. */
    @Transactional
    public AiConversation startOrResume(String workspaceId, String userId) {
        return conversationRepository
                .findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc(
                        workspaceId, ConversationStatus.ACTIVE)
                .orElseGet(
                        () ->
                                conversationRepository.save(
                                        AiConversation.builder()
                                                .id(Ulids.newUlid())
                                                .workspaceId(workspaceId)
                                                .startedBy(userId)
                                                .status(ConversationStatus.ACTIVE)
                                                .build()));
    }

    /** Current brand-profile analysis status, tenant-scoped — for the session-start gate. */
    @Transactional(readOnly = true)
    public BrandProfile getBrandProfile(String workspaceId) {
        return brandProfileRepository.findByWorkspaceId(workspaceId).orElse(null);
    }

    /**
     * Send a turn: credit-gate + decrement (Guardrail 5, BEFORE anything else), persist the USER
     * message, assemble the sanitized brand context (Guardrail 3), mint a scoped stream token
     * (Guardrail 2), call influora-ai's {@code POST /chat} for the real model reply (A4, see
     * {@link #doSendTurn}), and persist the ASSISTANT reply. Returns the persisted USER message
     * id, the assistant message id, the stream token, and remaining credits.
     *
     * <p>[E2 audit finding #2, MEDIUM — fixed] {@code idempotencyKey} is required. Without this
     * guard a client retry (double-click, network-timeout auto-retry, back-button resubmit)
     * re-ran {@link AICreditService#tryConsume} against the workspace's capped AI-credit balance
     * AND inserted duplicate {@link AiMessage} rows. Reserved through the shared {@link
     * IdempotencyService} FIRST (its own insert-first-wins table), same pattern as {@code
     * RedemptionService#redeem}. A concurrent double-submit (in-flight or already-completed) is
     * rejected with a retry-safe 409 — this human-facing send has no stored result reference to
     * replay from, unlike {@link #persistAssistantWriteback}'s replay path.
     *
     * <p>Deliberately NOT {@code @Transactional} itself (matches {@code RedemptionService#redeem}
     * / {@code AffiliateEarningsService#recordEarning}'s pattern) — {@link
     * IdempotencyService#executeOnce} reserves the key in its OWN transaction, which must commit
     * independently of the effect it guards; {@link #doSendTurn} carries the real
     * {@code @Transactional} boundary for the effect itself.
     */
    public TurnResult sendTurn(
            String workspaceId,
            String userId,
            String conversationId,
            String content,
            String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required to send a turn",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    workspaceId,
                    SEND_TURN_SCOPE,
                    () -> doSendTurn(workspaceId, userId, conversationId, content));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This turn is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    @Transactional
    protected TurnResult doSendTurn(
            String workspaceId, String userId, String conversationId, String content) {
        AiConversation conversation =
                conversationRepository
                        .findByIdAndWorkspaceId(conversationId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CONVERSATION_NOT_FOUND",
                                                "Conversation not found",
                                                HttpStatus.NOT_FOUND));

        // Guardrail 5 — credit gate + atomic decrement BEFORE any Python/LLM reachability.
        creditService.tryConsume(workspaceId, TURN_CREDIT_COST);

        AiMessage userMessage =
                messageRepository.save(
                        AiMessage.builder()
                                .id(Ulids.newUlid())
                                .conversationId(conversationId)
                                .role(MessageRole.USER)
                                .content(content)
                                .creditsCharged(TURN_CREDIT_COST)
                                .build());

        conversation.markMessageAt(Instant.now());
        conversationRepository.save(conversation);

        // Guardrail 3 — sanitized context assembly (not sent anywhere yet in this phase; Domain D
        // is the actual consumer once the Python integration lands).
        Workspace workspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
        BrandProfile brandProfile = brandProfileRepository.findByWorkspaceId(workspaceId).orElse(null);
        Map<String, Object> sanitizedContext = contextAssembler.assemble(workspace, brandProfile);

        String messageId = userMessage.getId();
        String streamToken = streamTokenService.mint(workspaceId, conversationId, messageId, userId);

        // A4: real model turn via influora-ai's existing POST /chat (MeeraChatAiClient), replacing
        // the old placeholder echo. Full turn history (including the USER message just persisted
        // above) is replayed so Claude has the same conversation the browser's own SSE connection
        // would see. Any provider failure (transport, non-200, an `error` SSE event, or a stream
        // with no usable text) throws MeeraChatAiException, which propagates out of this
        // @Transactional method — Spring rolls back the credit consumption and the USER message
        // together with it, so no half-written turn (user message with no reply, credit already
        // spent) is ever left behind, and IdempotencyService marks the outer SEND_TURN_SCOPE key
        // FAILED, which is reclaimable by a genuine client retry of the same Idempotency-Key (see
        // IdempotencyService#executeOnce's reclaim-on-FAILED path) — never a duplicate turn.
        List<AiMessage> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        BrandAiCredit creditStatus = creditService.getStatus(workspaceId);
        Map<String, Object> chatRequestBody =
                buildChatRequestBody(workspaceId, conversationId, messageId, history, sanitizedContext, creditStatus);

        String assistantText;
        try {
            ChatTurnResult chatResult = chatAiClient.sendTurn(workspaceId, chatRequestBody);
            assistantText = chatResult.text();
        } catch (MeeraChatAiException e) {
            log.warn(
                    "MeeraSessionService: /chat call failed for workspace={}, conversation={}: {}",
                    workspaceId,
                    conversationId,
                    e.getMessage());
            throw new ApiException(
                    "MEERA_PROVIDER_UNAVAILABLE",
                    "Meera could not generate a reply right now — please retry",
                    HttpStatus.BAD_GATEWAY);
        }

        AiMessage assistantMessage =
                messageRepository.save(
                        AiMessage.builder()
                                .id(Ulids.newUlid())
                                .conversationId(conversationId)
                                .role(MessageRole.ASSISTANT)
                                .content(assistantText)
                                .metadataJson(JsonLists.toJsonObject(Map.of("placeholder", false)))
                                .creditsCharged(0)
                                .build());

        return new TurnResult(
                userMessage.getId(),
                assistantMessage.getId(),
                streamToken,
                sanitizedContext,
                assistantText);
    }

    /**
     * Adapts Java's sanitized brand context ({@link BrandContextAssembler}, camelCase/flat) and
     * turn history into the shape influora-ai's {@code app/prompt/assembler.py::assemble_prompt} /
     * {@code build_block_b} actually read (snake_case top-level {@code workspace_id}, a nested
     * {@code brand}/{@code credit_state} object) — a deliberate adaptation, not a fabricated
     * contract, mirroring {@code AnalyzeSiteTriggerService#applySuccess}'s "Contract mismatch,
     * handled deliberately" pattern for the same reason: the two sides' shapes are documented
     * independently and were never byte-identical.
     *
     * <p>{@code onbehalf_jwt} is deliberately {@code ""} — see {@link MeeraChatAiClient}'s javadoc.
     * Message roles are lowercased ({@code MessageRole.name()} is uppercase) because {@code
     * build_block_c_messages} matches literal {@code "user"}/{@code "assistant"}/{@code "tool"} —
     * anything else (including the uppercase enum name) falls into its untrusted "unknown_role"
     * branch, which would wrongly wrap every replayed assistant turn as untrusted user data.
     */
    private Map<String, Object> buildChatRequestBody(
            String workspaceId,
            String conversationId,
            String turnId,
            List<AiMessage> history,
            Map<String, Object> sanitizedContext,
            BrandAiCredit creditStatus) {
        List<Map<String, Object>> conversation =
                history.stream()
                        .map(
                                m ->
                                        Map.<String, Object>of(
                                                "role", m.getRole().name().toLowerCase(Locale.ROOT),
                                                "content", m.getContent() == null ? "" : m.getContent()))
                        .collect(Collectors.toList());

        Map<String, Object> brand = new LinkedHashMap<>();
        putIfPresent(brand, "display_name", sanitizedContext.get("brandName"));
        putIfPresent(brand, "niche_tags", sanitizedContext.get("nicheTags"));
        putIfPresent(brand, "tone_dial", sanitizedContext.get("toneProfile"));
        Object brandAesthetic = sanitizedContext.get("brandAesthetic");
        if (brandAesthetic instanceof Map<?, ?> aestheticMap) {
            putIfPresent(brand, "brand_color", aestheticMap.get("accent_color"));
        }
        putIfPresent(brand, "product_catalog", sanitizedContext.get("productCatalog"));

        Map<String, Object> creditState = new LinkedHashMap<>();
        boolean unlimited = creditStatus.isUnlimited(Instant.now());
        creditState.put("mode", unlimited ? "unlimited" : "metered");
        creditState.put("credits_remaining", creditStatus.getCreditsRemaining());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace_id", workspaceId);
        body.put("conversation_id", conversationId);
        body.put("turn_id", turnId);
        body.put("onbehalf_jwt", "");
        body.put("conversation", conversation);
        body.put("brand", brand);
        body.put("credit_state", creditState);
        return body;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * Resolves the tenant (workspaceId) for a conversation, tenant-agnostic-by-necessity because
     * Python's {@code POST /internal/meera/messages} callback does not carry a workspaceId in its
     * body (it fires from the {@code /chat} SSE route, not a tool call). Callers MUST validate the
     * on-behalf JWT's {@code workspaceId} claim against the returned value before calling
     * {@link #persistAssistantWriteback} — this lookup alone is not a tenant-authorization
     * decision, just the tenant lookup that authorization is checked against (Guardrail 4).
     */
    @Transactional(readOnly = true)
    public AiConversation resolveConversation(String conversationId) {
        return conversationRepository
                .findById(conversationId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CONVERSATION_NOT_FOUND", "Conversation not found", HttpStatus.NOT_FOUND));
    }

    /**
     * Persists the real assistant turn Python posts back via the signed
     * {@code POST /internal/meera/messages} callback (11-AI-FLOW-DETAILED.md Flow 2 step 6). This
     * is the write-back that eventually retires the {@link #sendTurn} placeholder-echo path once
     * the browser talks to Python directly over SSE for the reasoning itself — Spring's role here
     * is solely to persist the audit trail (ai_messages). Callers must have already resolved and
     * authorized {@code conversationId} via {@link #resolveConversation} before calling this, and
     * pass that SAME resolved {@code workspaceId} here (see {@code MeeraInternalController
     * #persistTurnWriteback}, which cross-checks it against the on-behalf JWT before either call).
     *
     * <p>[E2 audit finding #16, MEDIUM — fixed] {@code idempotencyKey} is required.
     *
     * <p>[SEC: Vikram, P3(c) fix — two bugs closed] The replay path previously (a) reserved under
     * {@code workspaceId=null} unconditionally — despite the caller having a real, already-verified
     * workspace identity available — so this key was never actually workspace-scoped even though
     * every other Meera idempotency guard in this codebase is; and (b), worse, on replay it called
     * {@link AiMessageRepository#findTopByConversationIdOrderByCreatedAtDesc}, i.e. "whatever the
     * newest message in this conversation happens to be right now" — which is only correct if
     * NOTHING else has been persisted to the conversation since the original write-back. A second,
     * unrelated write-back (or a real-time human {@link #sendTurn} placeholder reply) landing in
     * between the original call and its retry would make this return the WRONG message entirely.
     * Both are fixed together: {@code workspaceId} is now threaded through to {@link
     * IdempotencyService#executeOnce(String, String, String, java.util.function.Supplier,
     * java.util.function.Function)}
     * (making the reservation genuinely workspace-scoped, per {@link IdempotencyService}'s own
     * P3(c) fix), and the digest-capture overload stores the newly-created {@link AiMessage#getId()}
     * as the row's {@code result_digest} — so a replay looks up the EXACT message this specific
     * idempotency key created via {@link IdempotencyService#findCompletedResultDigest} +
     * {@link AiMessageRepository#findById}, never an approximation. The "latest message" finder is
     * kept as a legacy fallback ONLY for a digest-less completed row (defensive; should not occur
     * for any row written after this fix).
     */
    public AiMessage persistAssistantWriteback(
            String workspaceId,
            String conversationId,
            String content,
            Map<String, Object> metadata,
            String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required to persist an assistant write-back",
                    HttpStatus.BAD_REQUEST);
        }
        try {
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    workspaceId,
                    PERSIST_WRITEBACK_SCOPE,
                    () -> doPersistAssistantWriteback(conversationId, content, metadata),
                    AiMessage::getId);
        } catch (IdempotencyService.AlreadyCompletedException replay) {
            AiMessage previous = replayPersistedMessage(workspaceId, conversationId, idempotencyKey);
            if (previous != null) {
                return previous;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This write-back is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        } catch (IdempotencyService.AlreadyInProgressException raced) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This write-back is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Resolves the EXACT message a completed {@code idempotencyKey} created, via the digest
     * captured on {@link AiMessage#getId()} at completion time — see {@link #persistAssistantWriteback}
     * javadoc. Falls back to "the newest message in the conversation" only if no digest was
     * recorded (a digest-less legacy row, or the digested message id no longer resolves) — this
     * fallback is deliberately a last resort, not the primary replay path.
     */
    private AiMessage replayPersistedMessage(String workspaceId, String conversationId, String idempotencyKey) {
        AiMessage byDigest =
                idempotencyService
                        .findCompletedResultDigest(idempotencyKey, workspaceId, PERSIST_WRITEBACK_SCOPE)
                        .flatMap(messageRepository::findById)
                        .orElse(null);
        if (byDigest != null) {
            return byDigest;
        }
        return messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversationId).orElse(null);
    }

    @Transactional
    protected AiMessage doPersistAssistantWriteback(
            String conversationId, String content, Map<String, Object> metadata) {
        AiConversation conversation = resolveConversation(conversationId);

        AiMessage assistantMessage =
                messageRepository.save(
                        AiMessage.builder()
                                .id(Ulids.newUlid())
                                .conversationId(conversationId)
                                .role(MessageRole.ASSISTANT)
                                .content(content)
                                .metadataJson(metadata == null ? null : JsonLists.toJsonObject(metadata))
                                .creditsCharged(0)
                                .build());

        conversation.markMessageAt(Instant.now());
        conversationRepository.save(conversation);

        return assistantMessage;
    }

    /** Tenant-scoped full turn history for a conversation. */
    @Transactional(readOnly = true)
    public List<AiMessage> listMessages(String workspaceId, String conversationId) {
        conversationRepository
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CONVERSATION_NOT_FOUND", "Conversation not found", HttpStatus.NOT_FOUND));
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * F10 — GET history route (backend half): reopening a chat previously had no way to load
     * prior turns, since {@code MeeraController} had no {@code GET} route for {@code
     * /meera/sessions/{id}/messages} at all. Matches {@code src/lib/meera-api.ts}'s {@code
     * getMessagesAfter} contract exactly: {@code GET .../messages?after={messageId}} — when {@code
     * afterMessageId} is blank/absent, returns the FULL history (the "reopen a chat from scratch"
     * case); when present, returns only messages created after that message id (the "catch up
     * after a stream failure" case the frontend client's javadoc already describes). Cursors on
     * {@code AiMessage#getId()} (a ULID, monotonically sortable by creation time) rather than
     * {@code createdAt} — a millisecond-resolution timestamp can collide across two turns saved in
     * the same millisecond, which the id cannot.
     */
    @Transactional(readOnly = true)
    public List<AiMessage> listMessages(String workspaceId, String conversationId, String afterMessageId) {
        conversationRepository
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CONVERSATION_NOT_FOUND", "Conversation not found", HttpStatus.NOT_FOUND));
        if (afterMessageId == null || afterMessageId.isBlank()) {
            return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        }
        return messageRepository.findByConversationIdAndIdGreaterThanOrderByIdAsc(conversationId, afterMessageId);
    }

    /** Result of a sendTurn call — internal to the service layer, mapped to a DTO by the controller. */
    public record TurnResult(
            String userMessageId,
            String assistantMessageId,
            String streamToken,
            Map<String, Object> sanitizedContext,
            String placeholderReply) {}
}
