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

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BrandProfileRepository brandProfileRepository;
    private final AICreditService creditService;
    private final BrandContextAssembler contextAssembler;
    private final StreamTokenService streamTokenService;

    public MeeraSessionService(
            AiConversationRepository conversationRepository,
            AiMessageRepository messageRepository,
            WorkspaceRepository workspaceRepository,
            BrandProfileRepository brandProfileRepository,
            AICreditService creditService,
            BrandContextAssembler contextAssembler,
            StreamTokenService streamTokenService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.creditService = creditService;
        this.contextAssembler = contextAssembler;
        this.streamTokenService = streamTokenService;
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
     */
    @Transactional
    public TurnResult sendTurn(String workspaceId, String userId, String conversationId, String content) {
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
     * authorized {@code conversationId} via {@link #resolveConversation} before calling this.
     */
    @Transactional
    public AiMessage persistAssistantWriteback(String conversationId, String content, Map<String, Object> metadata) {
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
