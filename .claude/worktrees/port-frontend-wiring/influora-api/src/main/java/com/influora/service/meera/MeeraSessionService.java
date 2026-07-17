package com.influora.service.meera;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.Ulids;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.MessageRole;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.IdempotencyService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session bookkeeping for Meera conversations — start/resume a conversation, persist turns.
 * Every method is tenant-scoped off {@code workspaceId} (Guardrail 4).
 *
 * <p><b>KNOWN GAP (documented, not hidden):</b> {@link #sendTurn} does NOT call a real LLM. The
 * Python/Domain D service (04-AI-SERVICE-SPEC.md) owns the actual Claude/Gemini call and streams
 * the reply directly to the browser over SSE using the token minted by {@link StreamTokenService}.
 * This phase persists a placeholder ASSISTANT echo so the data layer, credit gating, and turn
 * audit trail are fully exercised end-to-end without a live model — swap the placeholder for the
 * real write-back once {@code POST /internal/meera/messages} (§3.6 of the API contract) is wired
 * by the Python integration.
 */
@Service
public class MeeraSessionService {

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

    public MeeraSessionService(
            AiConversationRepository conversationRepository,
            AiMessageRepository messageRepository,
            WorkspaceRepository workspaceRepository,
            BrandProfileRepository brandProfileRepository,
            AICreditService creditService,
            BrandContextAssembler contextAssembler,
            StreamTokenService streamTokenService,
            IdempotencyService idempotencyService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.creditService = creditService;
        this.contextAssembler = contextAssembler;
        this.streamTokenService = streamTokenService;
        this.idempotencyService = idempotencyService;
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
     * (Guardrail 2), and persist a placeholder ASSISTANT reply (see class-level KNOWN GAP).
     * Returns the persisted USER message id, the stream token, and remaining credits.
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

        // KNOWN GAP: placeholder echo persisted as the ASSISTANT turn. No real LLM call happens
        // here — Domain D (Python) is the real integration point; this keeps the audit trail
        // (ai_messages, prompt_version-less for now) exercised end-to-end.
        String placeholderReply =
                "Meera (placeholder): received your message. Live AI responses are wired by the "
                        + "Python service (Domain D) — this is a stub echo for Phase 2 verification.";
        AiMessage assistantMessage =
                messageRepository.save(
                        AiMessage.builder()
                                .id(Ulids.newUlid())
                                .conversationId(conversationId)
                                .role(MessageRole.ASSISTANT)
                                .content(placeholderReply)
                                .metadataJson(
                                        JsonLists.toJsonObject(
                                                Map.of(
                                                        "placeholder", true,
                                                        "note", "no LLM call in Phase 2 — Domain D pending")))
                                .creditsCharged(0)
                                .build());

        return new TurnResult(
                userMessage.getId(),
                assistantMessage.getId(),
                streamToken,
                sanitizedContext,
                placeholderReply);
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

    /** Result of a sendTurn call — internal to the service layer, mapped to a DTO by the controller. */
    public record TurnResult(
            String userMessageId,
            String assistantMessageId,
            String streamToken,
            Map<String, Object> sanitizedContext,
            String placeholderReply) {}
}
