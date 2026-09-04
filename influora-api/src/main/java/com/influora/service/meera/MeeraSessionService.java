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
import com.influora.domain.enums.UserType;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.IdempotencyService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session bookkeeping for Meera conversations — start/resume a conversation, persist turns.
 * Every method is tenant-scoped off {@code workspaceId} (Guardrail 4).
 *
 * <p><b>Streaming-first refactor (Priya, Wave 2):</b> {@link #doSendTurn} previously called
 * influora-ai's {@code POST /chat} synchronously (via the now-removed {@code MeeraChatAiClient})
 * with an empty {@code onbehalf_jwt}, persisted the ASSISTANT reply itself, and charged the AI
 * credit up front. That whole synchronous leg is gone: {@code doSendTurn} persists the USER
 * message and mints the {@link StreamTokenService} token — the browser streams directly from
 * influora-ai's {@code /chat} SSE endpoint with its OWN on-behalf JWT, so tool calls and Living
 * Canvas stages work for real (no more degrading to plain text). {@link #persistAssistantWriteback}
 * is the SOLE writer of the ASSISTANT turn (influora-ai's end-of-stream callback, which previously
 * failed its on-behalf check and was silently dropped when the Java-side call sent {@code ""} —
 * the real browser JWT satisfies it now).
 *
 * <p><b>SECURITY FIX (Wave 2 round 2, Kabir red-team — two HIGH exploits in the charge-on-success
 * model this class originally shipped):</b> charge-on-success (decrementing credit only in {@link
 * #persistAssistantWriteback}) let a client read every {@code token} SSE event and disconnect
 * before {@code done} to dodge the charge AND the 500/day cap entirely (FAIL 1), and let a client
 * pin the write-back's client-supplied {@code turn_id} to a constant across many turns so only the
 * first was ever actually charged (FAIL 2 — the rest short-circuited through {@code
 * AlreadyCompletedException} with {@code creditsCharged=0}). Both are closed by moving the charge
 * back to the SEND gate, keyed on the server-minted {@code messageId}: {@link #doSendTurn} now
 * calls {@link AICreditService#tryConsumeForTurn}, which actually decrements credit and bumps the
 * daily counter, BEFORE the USER message is persisted or any token is minted — there is no longer
 * any path that streams tokens to the browser without the turn already being charged. Because
 * charging moved earlier, a genuine PROVIDER failure (never a plain client disconnect — see {@code
 * app/routes/chat.py}'s explicit separation of those two cases) needs to be able to give the money
 * back: {@link AICreditService#release} handles that, guarded so it can never refund a turn that
 * was never charged or one whose reply already persisted (no refund-and-keep-the-reply). See {@link
 * #releaseTurnCredit} and {@code MeeraInternalController}'s {@code /internal/meera/turns/release}
 * route, which influora-ai calls on provider failure.
 *
 * <p>{@link #persistAssistantWriteback} no longer charges anything — it ONLY persists the
 * ASSISTANT message, idempotently, and sets the persisted row's {@code creditsCharged} by asking
 * {@link AICreditService#wasCharged} whether the SAME {@code turnId} (== its own idempotency key,
 * which is now the write-back caller's SERVER-VERIFIED {@code messageId} claim, never a
 * client-supplied {@code turn_id} — Kabir FAIL 2's other half, fixed on the influora-ai side in
 * {@code app/routes/chat.py}) was actually charged at send. See {@link #doPersistAssistantWriteback}.
 *
 * <p><b>SECURITY FIX #1 ({@code docs/security/meera-onbehalf-auth-security-design.md} §2):</b>
 * {@link #doSendTurn} now also mints a dedicated, per-turn, scoped {@link OnBehalfTokenService}
 * token alongside the {@link StreamTokenService} token, at the same call site. The browser
 * forwards THIS token as {@code onbehalf_jwt} instead of the user's full-lifetime public-API
 * access token (which used to be read out of {@code localStorage.getItem('brand_token')} —
 * {@code MeeraChatPanel.tsx}, pre-fix). See {@link TurnResult#onBehalfToken()}.
 */
@Service
public class MeeraSessionService {

    private static final Logger log = LoggerFactory.getLogger(MeeraSessionService.class);

    private static final int TURN_CREDIT_COST = 1;
    private static final String SEND_TURN_SCOPE = "meera.send_turn";

    /**
     * Package-visible (not {@code private}) so {@link AICreditService#release} can consult it via
     * {@code IdempotencyService#isCompleted} — a COMPLETED row here for a given {@code turnId}
     * means that turn's assistant reply already persisted, which is exactly the condition that
     * must make a release a no-op (never refund a turn whose reply already landed).
     */
    static final String PERSIST_WRITEBACK_SCOPE = "meera.persist_writeback";

    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BrandProfileRepository brandProfileRepository;
    private final AICreditService creditService;
    private final BrandContextAssembler contextAssembler;
    private final StreamTokenService streamTokenService;
    private final OnBehalfTokenService onBehalfTokenService;
    private final IdempotencyService idempotencyService;
    private final CreatorAgentConversationService creatorAgentConversationService;

    public MeeraSessionService(
            AiConversationRepository conversationRepository,
            AiMessageRepository messageRepository,
            WorkspaceRepository workspaceRepository,
            BrandProfileRepository brandProfileRepository,
            AICreditService creditService,
            BrandContextAssembler contextAssembler,
            StreamTokenService streamTokenService,
            OnBehalfTokenService onBehalfTokenService,
            IdempotencyService idempotencyService,
            CreatorAgentConversationService creatorAgentConversationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
        this.brandProfileRepository = brandProfileRepository;
        this.creditService = creditService;
        this.contextAssembler = contextAssembler;
        this.streamTokenService = streamTokenService;
        this.onBehalfTokenService = onBehalfTokenService;
        this.idempotencyService = idempotencyService;
        this.creatorAgentConversationService = creatorAgentConversationService;
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
     * Gate fix round 1 (Priya Q1, T-MEERA-CREATOR-PHASE-A, SPEC.md 4.7/A10) — the CREATOR
     * counterpart to {@link #startOrResume}. SPEC.md 4.7 promises a "day-one onboarding" first
     * message, "sent from backend as first assistant message if conversation is new" — before
     * this method existed, {@code CreatorMeeraController} called the generic {@link
     * #startOrResume}, which creates a bare conversation with no message at all; the greeting the
     * creator actually saw was a client-only string in {@code MeeraCopilotChat.tsx} that never
     * touched {@code ai_messages}, so it silently never appeared in the DPDP conversation export
     * the consent screen promises the creator they can obtain (Priya's Q1 trace).
     *
     * <p>Same find-or-resume as {@link #startOrResume}, but when (and only when) THIS call is the
     * one that creates a brand-new conversation, it also persists Meera's greeting as a real
     * ASSISTANT {@link AiMessage} row — {@code creditsCharged(0)} (Phase A creator turns never
     * touch the brand AI-credit ledger, see class javadoc) — and bumps the {@code
     * meera_creator_conversations} rollup via {@link
     * CreatorAgentConversationService#recordTurnForUser}, the same call {@link
     * #doPersistAssistantWriteback} makes for every other CREATOR assistant turn, so the
     * conversation list's {@code message_count}/{@code last_message_at} are correct from the very
     * first message onward. A RESUMED (pre-existing) conversation is untouched — the greeting is a
     * one-time, first-turn-only event, never repeated on every session start.
     *
     * <p><b>Gate fix round 4 (Priya's fourth pass):</b> {@code creatorLanguage} makes the
     * persisted greeting language-aware. V73 defaults every creator's {@code
     * creator_agent_preferences.creator_language} to {@link
     * com.influora.domain.entity.CreatorAgentPreferences#DEFAULT_LANGUAGE} ({@code "hi-IN"}), and
     * before this fix the persisted greeting was always the English literal — since {@link
     * #createConversationWithOnboardingGreeting} is now the SOLE writer of the first ASSISTANT
     * message, the frontend's Hindi fallback in {@code MeeraCopilotChat.tsx} could never actually
     * be reached for a Hindi-default creator. The caller ({@code CreatorMeeraController}) resolves
     * the BCP-47 tag from {@code CreatorAgentPreferences} and passes it straight through here.
     */
    @Transactional
    public AiConversation startOrResumeForCreator(
            String creatorUserId, String userId, String creatorDisplayName, String creatorLanguage) {
        return conversationRepository
                .findFirstByWorkspaceIdAndStatusOrderByLastMessageAtDesc(
                        creatorUserId, ConversationStatus.ACTIVE)
                .orElseGet(
                        () ->
                                createConversationWithOnboardingGreeting(
                                        creatorUserId, userId, creatorDisplayName, creatorLanguage));
    }

    private AiConversation createConversationWithOnboardingGreeting(
            String creatorUserId, String userId, String creatorDisplayName, String creatorLanguage) {
        AiConversation conversation =
                conversationRepository.save(
                        AiConversation.builder()
                                .id(Ulids.newUlid())
                                .workspaceId(creatorUserId)
                                .startedBy(userId)
                                .status(ConversationStatus.ACTIVE)
                                .build());

        String greeting = onboardingGreeting(creatorDisplayName, creatorLanguage);
        messageRepository.save(
                AiMessage.builder()
                        .id(Ulids.newUlid())
                        .conversationId(conversation.getId())
                        .role(MessageRole.ASSISTANT)
                        .content(greeting)
                        .creditsCharged(0)
                        .build());

        conversation.markMessageAt(Instant.now());
        conversationRepository.save(conversation);
        creatorAgentConversationService.recordTurnForUser(creatorUserId, conversation.getId(), Instant.now());

        return conversation;
    }

    /**
     * Gate fix round 4 (Priya's fourth pass) — the two persisted greeting variants, kept in sync
     * word-for-word with {@code onboardingGreeting()} in {@code
     * src/components/creator/MeeraCopilotChat.tsx} (lines ~70-74) so the client-only placeholder
     * shown before this backend turn lands on the wire and the actual persisted {@code
     * ai_messages} row never diverge. Hindi for any BCP-47 tag starting with {@code "hi"}
     * (case-insensitive — matches the frontend's {@code language.startsWith('hi')}), English for
     * everything else. Do not add other languages here without updating the frontend copy too.
     */
    private static String onboardingGreeting(String creatorDisplayName, String creatorLanguage) {
        String firstName = onboardingFirstName(creatorDisplayName);
        if (creatorLanguage != null && creatorLanguage.toLowerCase(java.util.Locale.ROOT).startsWith("hi")) {
            return "नमस्ते "
                    + firstName
                    + "! मैं Meera हूं, Influora पर आपकी मैनेजर। मैं आपकी डील्स, कमाई और मेट्रिक्स समझने में"
                    + " मदद कर सकती हूं। आप क्या जानना चाहेंगे?";
        }
        return "Hi "
                + firstName
                + "! I'm Meera, your manager here on Influora. I can help you track your"
                + " deals, understand your earnings, and answer questions about the"
                + " platform. What would you like to know?";
    }

    /** Local copy of {@code MeeraContextService#firstNameOf} — same reasoning as that method's
     * javadoc on {@code deriveTier}: a private three-line helper isn't worth a shared util. Falls
     * back to "there" rather than null/blank so the greeting is always grammatical even for a
     * creator profile with no display name set yet. */
    private static String onboardingFirstName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "there";
        }
        String trimmed = displayName.trim();
        int spaceIndex = trimmed.indexOf(' ');
        return spaceIndex > 0 ? trimmed.substring(0, spaceIndex) : trimmed;
    }

    /**
     * Send a turn: charge-gate (Guardrail 5, BEFORE anything else — see {@link
     * AICreditService#tryConsumeForTurn}, which actually decrements credit and bumps the 500/day
     * counter, keyed on the server-minted {@code messageId} — SECURITY FIX, Kabir FAILs #1/#2, see
     * class javadoc), persist the USER message, assemble the sanitized brand context (Guardrail
     * 3), and mint a scoped stream token (Guardrail 2). Returns immediately —
     * {@code assistantMessageId} and the reply text are {@code null}; the browser streams the
     * actual turn directly from influora-ai over SSE using the returned token, and the ASSISTANT
     * message (plus the actual credit charge) lands later via {@link #persistAssistantWriteback}.
     *
     * <p>[E2 audit finding #2, MEDIUM — fixed] {@code idempotencyKey} is required. Without this
     * guard a client retry (double-click, network-timeout auto-retry, back-button resubmit)
     * inserted duplicate {@link AiMessage} rows. Reserved through the shared {@link
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
            UserType userType,
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
                    () -> doSendTurn(workspaceId, userId, userType, conversationId, content));
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
            String workspaceId, String userId, UserType userType, String conversationId, String content) {
        AiConversation conversation =
                conversationRepository
                        .findByIdAndWorkspaceId(conversationId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CONVERSATION_NOT_FOUND",
                                                "Conversation not found",
                                                HttpStatus.NOT_FOUND));

        // Server-minted turn id — generated BEFORE the charge and before anything else is
        // persisted or minted, so the charge (and every downstream token/message) is keyed on the
        // SAME value, never a client-supplied one (Kabir FAIL 2 fix — see class javadoc).
        String messageId = Ulids.newUlid();

        // T-MEERA-CREATOR-PHASE-A (fix round 1, item 2) — a CREATOR turn's workspaceId is
        // actually the creator's USER id (see MeeraContextService#assembleCreatorContext
        // javadoc), never a real Workspace row, and is never charged against this
        // workspace-scoped brand AI-credit ledger at all: influora-ai's spend_tracker enforces
        // the per-creator monthly cap for CREATOR-audience turns on its own side (A8). The BRAND
        // branch below is entirely unchanged.
        boolean isCreatorTurn = userType == UserType.CREATOR;

        if (!isCreatorTurn) {
            // SECURITY FIX (Kabir FAILs #1/#2) — charge HERE, at send, keyed on messageId.
            // Replaces the old non-decrementing assertAvailable pre-check: this ACTUALLY
            // decrements credit and bumps the 500/day counter (same two gates as before —
            // exhausted credits / daily cap — same error codes/statuses), and records the
            // per-turn charge-ledger marker AICreditService#release later consults. If this
            // throws, nothing below runs: no USER message, no stream token, nothing to ever
            // appear "charged" and dangling.
            creditService.tryConsumeForTurn(workspaceId, TURN_CREDIT_COST, messageId);
        }

        AiMessage userMessage =
                messageRepository.save(
                        AiMessage.builder()
                                .id(messageId)
                                .conversationId(conversationId)
                                .role(MessageRole.USER)
                                .content(content)
                                // The USER row itself is still never charged — the charge is
                                // attributed to the ASSISTANT write-back row instead (see
                                // doPersistAssistantWriteback) purely for display/audit purposes;
                                // the actual decrement already happened above, at send.
                                .creditsCharged(0)
                                .build());

        conversation.markMessageAt(Instant.now());
        conversationRepository.save(conversation);

        Map<String, Object> sanitizedContext;
        if (isCreatorTurn) {
            // T-MEERA-CREATOR-PHASE-A (fix round 2, item 2 — Priya Q4). Deliberately NOT calling
            // CreatorAgentConversationService#recordTurnForUser here anymore. This method persists
            // the USER message and returns before the browser has even opened its SSE connection
            // to influora-ai — if Python (or the stream) never completes, recording the turn here
            // left a dangling ai_messages row AND an inflated meera_creator_conversations
            // message_count for a turn that produced no assistant reply. recordTurnForUser is now
            // called exactly once, from doPersistAssistantWriteback, so message_count counts only
            // COMPLETED turns. Block B for a CREATOR turn is sourced separately, via
            // MeeraContextService#assembleCreatorContext (POST /internal/meera/context) — there is
            // no BRAND-shaped sanitizedContext to assemble here.
            sanitizedContext = Map.of();
        } else {
            // Guardrail 3 — sanitized context assembly (not sent anywhere yet in this phase;
            // Domain D is the actual consumer once the Python integration lands).
            Workspace workspace =
                    workspaceRepository
                            .findById(workspaceId)
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
            BrandProfile brandProfile = brandProfileRepository.findByWorkspaceId(workspaceId).orElse(null);
            sanitizedContext = contextAssembler.assemble(workspace, brandProfile);
        }

        // Fix round 1 (BLOCKING): the verified principal type rides IN the stream token so
        // influora-ai derives the Meera audience from a signature-checked claim, never from the
        // (Python-unverifiable) on-behalf JWT a client can simply omit.
        String streamToken =
                streamTokenService.mint(workspaceId, conversationId, messageId, userId, userType);
        // SECURITY FIX #1: mint the dedicated per-turn on-behalf token here, alongside the stream
        // token — the browser forwards THIS as onbehalf_jwt, never the full access token.
        String onBehalfToken =
                onBehalfTokenService.mint(workspaceId, conversationId, messageId, userId, userType);

        // Streaming-first: no synchronous Python call here anymore. The browser opens its own SSE
        // connection to influora-ai's /chat using this token (Priya's locked architecture) and
        // influora-ai posts the finished turn back via persistAssistantWriteback.
        return new TurnResult(userMessage.getId(), null, streamToken, onBehalfToken, sanitizedContext, null);
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
     * Persists the real assistant turn influora-ai posts back via the signed
     * {@code POST /internal/meera/messages} callback (11-AI-FLOW-DETAILED.md Flow 2 step 6) — now
     * the SOLE writer of the ASSISTANT turn, since {@link #doSendTurn} no longer calls Python
     * synchronously. Callers must have already resolved and authorized {@code conversationId} via
     * {@link #resolveConversation} before calling this, and pass that SAME resolved {@code
     * workspaceId} here (see {@code MeeraInternalController#persistTurnWriteback}, which
     * cross-checks it against the on-behalf JWT before either call).
     *
     * <p><b>SECURITY FIX (Wave 2 round 2, Kabir FAILs #1/#2 — see class javadoc): this method no
     * longer charges anything.</b> The charge now happens once, at send, in {@link #doSendTurn}
     * via {@link AICreditService#tryConsumeForTurn}. This method ONLY persists the audit trail
     * (ai_messages), idempotently, and sets the persisted row's {@code creditsCharged} by asking
     * {@link AICreditService#wasCharged} whether {@code idempotencyKey} (== {@code turnId}) was
     * actually charged at send — see {@link #doPersistAssistantWriteback}.
     *
     * <p><b>{@code idempotencyKey} MUST be the server-minted {@code messageId}, never a
     * client-supplied value.</b> This is what Kabir FAIL 2 exploited: the old write-back used the
     * browser's own {@code turn_id} field as this key, so pinning it to a constant across many
     * turns made every turn after the first replay through {@link
     * IdempotencyService.AlreadyCompletedException} uncharged. The fix is enforced on the
     * influora-ai side ({@code app/routes/chat.py} now reads the stream token's VERIFIED {@code
     * messageId} claim instead of {@code body.turn_id}) — this method's contract is simply that
     * whatever {@code idempotencyKey} it's given IS the turn's identity for both the persistence
     * ledger ({@link #PERSIST_WRITEBACK_SCOPE}) and, transitively via {@link
     * AICreditService#wasCharged} / {@link AICreditService#release}, the charge ledger. A stale or
     * unrecognized {@code idempotencyKey} (one {@link AICreditService#wasCharged} doesn't
     * recognize) is not hard-rejected — matching this codebase's existing "never drop a turn the
     * user already watched stream" posture — it is persisted anyway with {@code creditsCharged =
     * 0} and a {@code WARN} log, same as the credit-race case below used to be handled.
     *
     * <p>Idempotent by construction: {@link IdempotencyService#executeOnce} only ever invokes the
     * supplier on the FIRST successful attempt for a given {@code idempotencyKey} — a
     * replayed/duplicate write-back short-circuits through the {@code AlreadyCompletedException}
     * branch below (returning the already-persisted message via {@link #replayPersistedMessage})
     * and never re-enters {@link #doPersistAssistantWriteback}.
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
        return persistAssistantWriteback(workspaceId, conversationId, content, metadata, idempotencyKey, UserType.BRAND);
    }

    /**
     * T-MEERA-CREATOR-PHASE-A (fix round 1, item 4) — {@code userType} overload. Callers that
     * already know the on-behalf JWT's verified {@code userType} (e.g. {@code
     * MeeraInternalController#persistTurnWriteback}, which resolves it via {@link
     * com.influora.security.OnBehalfAuthResolver#resolveForWorkspace}) MUST pass it here rather
     * than relying on the 5-arg overload's {@code UserType.BRAND} default. A CREATOR write-back
     * skips the brand AI-credit ledger entirely (see {@link #doPersistAssistantWriteback}) and
     * records the turn on {@link CreatorAgentConversationService} so it appears in the creator's
     * own Meera conversation list/export/delete surface (SPEC.md 2.5-2.7, A6) — the "write side"
     * gap {@link CreatorAgentConversationService}'s class javadoc used to flag as unimplemented.
     */
    public AiMessage persistAssistantWriteback(
            String workspaceId,
            String conversationId,
            String content,
            Map<String, Object> metadata,
            String idempotencyKey,
            UserType userType) {
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
                    () ->
                            doPersistAssistantWriteback(
                                    workspaceId, conversationId, content, metadata, idempotencyKey, userType),
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
            String workspaceId,
            String conversationId,
            String content,
            Map<String, Object> metadata,
            String turnId,
            UserType userType) {
        AiConversation conversation = resolveConversation(conversationId);

        // T-MEERA-CREATOR-PHASE-A (fix round 1, item 4) — a CREATOR write-back never touches the
        // brand AI-credit ledger at all (see doSendTurn — a CREATOR turn is never charged there
        // either, so creditService.wasCharged(workspaceId, turnId) would only ever legitimately
        // return false and log a spurious WARN for every single creator turn).
        int creditsCharged;
        if (userType == UserType.CREATOR) {
            creditsCharged = 0;
        } else {
            // SECURITY FIX (Wave 2 round 2): the charge already happened at send (doSendTurn ->
            // AICreditService#tryConsumeForTurn) — this method never charges. It only reflects that
            // charge on the persisted row by checking the SAME turnId (== idempotencyKey, the
            // server-verified messageId per the class javadoc) against AICreditService's charge
            // ledger. An unrecognized turnId (should not happen when influora-ai behaves per contract)
            // is persisted uncharged with a WARN, not hard-rejected — same "never drop a turn the user
            // already watched stream" posture this codebase already applied to the old credit race.
            creditsCharged = creditService.wasCharged(workspaceId, turnId) ? TURN_CREDIT_COST : 0;
            if (creditsCharged == 0) {
                log.warn(
                        "write-back turnId={} workspaceId={} conversationId={} has no matching send-time"
                                + " charge -- persisting uncharged",
                        turnId,
                        workspaceId,
                        conversationId);
            }
        }

        AiMessage assistantMessage =
                messageRepository.save(
                        AiMessage.builder()
                                .id(Ulids.newUlid())
                                .conversationId(conversationId)
                                .role(MessageRole.ASSISTANT)
                                .content(content)
                                .metadataJson(metadata == null ? null : JsonLists.toJsonObject(metadata))
                                .creditsCharged(creditsCharged)
                                .build());

        conversation.markMessageAt(Instant.now());
        conversationRepository.save(conversation);

        if (userType == UserType.CREATOR) {
            // A6 — the assistant turn also counts toward the creator's own conversation rollup
            // (message_count/last_message_at) that backs list/export/delete.
            creatorAgentConversationService.recordTurnForUser(workspaceId, conversationId, Instant.now());
        }

        return assistantMessage;
    }

    /**
     * SECURITY FIX (Wave 2 round 2, Kabir FAILs #1/#2 — see class javadoc): refunds a turn's
     * send-time charge after a genuine PROVIDER failure on the influora-ai side. Called from
     * {@code MeeraInternalController}'s {@code POST /internal/meera/turns/release}, which is
     * gated by the exact same dual-credential mesh auth as {@link #persistAssistantWriteback}'s
     * {@code /internal/meera/messages} route (service-token + HMAC + on-behalf JWT, tenant
     * cross-checked via {@code resolveConversation} + {@code OnBehalfAuthResolver} at the
     * controller, same pattern). Deliberately never called for a plain client disconnect — that
     * distinction is enforced entirely on the influora-ai side ({@code app/routes/chat.py}); this
     * method has no way to tell a disconnect from a provider failure and doesn't need to.
     *
     * <p>All of the actual idempotency/guard logic (double-release no-op, refuse to refund a turn
     * that was never charged, refuse to refund a turn whose reply already persisted) lives in
     * {@link AICreditService#release} — this is a thin pass-through so the controller doesn't need
     * to know {@link #TURN_CREDIT_COST} or reach into {@link AICreditService} directly.
     */
    public void releaseTurnCredit(String workspaceId, String turnId) {
        creditService.release(workspaceId, TURN_CREDIT_COST, turnId);
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

    /**
     * Result of a sendTurn call — internal to the service layer, mapped to a DTO by the
     * controller. {@code onBehalfToken} is the SECURITY FIX #1 per-turn credential (see class
     * javadoc) — the controller must return it to the browser, and the browser must forward it
     * as {@code onbehalf_jwt} in place of the old full-access-token read.
     */
    public record TurnResult(
            String userMessageId,
            String assistantMessageId,
            String streamToken,
            String onBehalfToken,
            Map<String, Object> sanitizedContext,
            String placeholderReply) {}
}
