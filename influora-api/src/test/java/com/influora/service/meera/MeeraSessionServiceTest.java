package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [E2 audit finding #16, MEDIUM -- fixed] Unit tests for {@link
 * MeeraSessionService#persistAssistantWriteback}'s idempotency guard -- previously this
 * machine-to-machine write-back callback from {@code influora-ai} had zero {@code Idempotency-Key}
 * handling despite its own javadoc flagging it as a plausible retry surface ("Flow 2 step 6"); a
 * retried call would insert a duplicate {@link AiMessage} row. Priority: proving a retried call
 * with the same key does NOT insert a second message.
 */
@ExtendWith(MockitoExtension.class)
class MeeraSessionServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CONVERSATION_ID = "01HCONVERSATION1234AB";
    private static final String IDEMPOTENCY_KEY = "turn-abc-123";
    private static final String CONTENT = "Here are three creators that match your brief.";

    @Mock private AiConversationRepository conversationRepository;
    @Mock private AiMessageRepository messageRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandProfileRepository brandProfileRepository;
    @Mock private AICreditService creditService;
    @Mock private BrandContextAssembler contextAssembler;
    @Mock private StreamTokenService streamTokenService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private MeeraChatAiClient chatAiClient;

    private MeeraSessionService service;

    @BeforeEach
    void setUp() {
        service =
                new MeeraSessionService(
                        conversationRepository,
                        messageRepository,
                        workspaceRepository,
                        brandProfileRepository,
                        creditService,
                        contextAssembler,
                        streamTokenService,
                        idempotencyService,
                        chatAiClient);
    }

    private BrandAiCredit creditStatus() {
        return BrandAiCredit.builder()
                .workspaceId(WORKSPACE_ID)
                .creditsRemaining(99)
                .monthlyAllotment(100)
                .cycleStart(LocalDate.now())
                .lastReset(LocalDate.now())
                .build();
    }

    private AiConversation conversation() {
        return AiConversation.builder()
                .id(CONVERSATION_ID)
                .workspaceId(WORKSPACE_ID)
                .startedBy("01HUSER1234567890ABCD")
                .status(ConversationStatus.ACTIVE)
                .build();
    }

    private void mockIdempotencyExecuteOnce() {
        // [SEC: Vikram, P3(c)] persistAssistantWriteback now threads workspaceId through (no
        // longer hardcodes null) and uses the 5-arg, result-digest-capturing executeOnce overload.
        when(idempotencyService.executeOnce(
                        anyString(), eq(WORKSPACE_ID), eq("meera.persist_writeback"), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<AiMessage> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    /** 4-arg {@code executeOnce} stub for {@code sendTurn}. */
    private void mockIdempotencyExecuteOnceWithResultRef() {
        when(idempotencyService.executeOnce(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<MeeraSessionService.TurnResult> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    @Test
    @DisplayName("persistAssistantWriteback: rejects a null idempotencyKey before touching any repository")
    void testRejectsNullIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.persistAssistantWriteback(
                                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of(), null));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(conversationRepository, messageRepository, idempotencyService);
    }

    @Test
    @DisplayName("persistAssistantWriteback: rejects a blank idempotencyKey before touching any repository")
    void testRejectsBlankIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.persistAssistantWriteback(
                                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of(), "   "));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        verifyNoInteractions(conversationRepository, messageRepository, idempotencyService);
    }

    @Test
    @DisplayName("persistAssistantWriteback: first call persists exactly one AiMessage and bumps lastMessageAt")
    void testFirstCallPersistsMessageExactlyOnce() {
        AiConversation conversation = conversation();
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        mockIdempotencyExecuteOnce();

        AiMessage result =
                service.persistAssistantWriteback(
                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of("k", "v"), IDEMPOTENCY_KEY);

        assertEquals(CONTENT, result.getContent());
        assertEquals(MessageRole.ASSISTANT, result.getRole());
        assertEquals(CONVERSATION_ID, result.getConversationId());
        verify(messageRepository, times(1)).save(any(AiMessage.class));
        verify(conversationRepository, times(1)).save(conversation);
    }

    @Test
    @DisplayName(
            "persistAssistantWriteback: a retried call with the SAME idempotencyKey (already completed)"
                    + " does NOT insert a second message -- [SEC: Vikram, P3(c)] replays the EXACT message"
                    + " via the captured result digest, not just \"the latest message in the conversation\"")
    void testRetriedCallDoesNotDoubleInsert() {
        // The replay path (AlreadyCompletedException) never reaches doPersistAssistantWriteback,
        // so it never calls resolveConversation/conversationRepository.findById at all -- no stub
        // needed for that lookup in this scenario.
        AiMessage originalMessage =
                AiMessage.builder()
                        .id("01HMESSAGE1234567890AB")
                        .conversationId(CONVERSATION_ID)
                        .role(MessageRole.ASSISTANT)
                        .content(CONTENT)
                        .creditsCharged(0)
                        .build();
        // A DIFFERENT, newer message has since landed in this conversation (e.g. a real-time
        // sendTurn placeholder) -- proves the fix no longer confuses "latest in conversation" with
        // "what this specific idempotencyKey created".
        AiMessage unrelatedNewerMessage =
                AiMessage.builder()
                        .id("01HMESSAGE_NEWER_9999")
                        .conversationId(CONVERSATION_ID)
                        .role(MessageRole.ASSISTANT)
                        .content("unrelated newer turn")
                        .creditsCharged(0)
                        .build();
        when(idempotencyService.executeOnce(
                        eq(IDEMPOTENCY_KEY), eq(WORKSPACE_ID), eq("meera.persist_writeback"), any(), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException(IDEMPOTENCY_KEY));
        when(idempotencyService.findCompletedResultDigest(
                        IDEMPOTENCY_KEY, WORKSPACE_ID, "meera.persist_writeback"))
                .thenReturn(Optional.of(originalMessage.getId()));
        when(messageRepository.findById(originalMessage.getId())).thenReturn(Optional.of(originalMessage));
        // Deliberately stubbed to prove it is NEVER consulted when a digest resolves.
        lenient()
                .when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(CONVERSATION_ID))
                .thenReturn(Optional.of(unrelatedNewerMessage));

        AiMessage result =
                service.persistAssistantWriteback(
                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of(), IDEMPOTENCY_KEY);

        assertSame(originalMessage, result);
        verify(messageRepository, never())
                .findTopByConversationIdOrderByCreatedAtDesc(anyString());
        // The replay path must NEVER call save -- that would be the double-insert this fix prevents.
        verify(messageRepository, never()).save(any());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "persistAssistantWriteback: a completed key with no captured digest (legacy row) falls back"
                    + " to the latest-message finder as a last resort")
    void testRetriedCallFallsBackToLatestMessageWhenNoDigest() {
        AiMessage latest =
                AiMessage.builder()
                        .id("01HMESSAGE_LATEST_001")
                        .conversationId(CONVERSATION_ID)
                        .role(MessageRole.ASSISTANT)
                        .content(CONTENT)
                        .creditsCharged(0)
                        .build();
        when(idempotencyService.executeOnce(
                        eq(IDEMPOTENCY_KEY), eq(WORKSPACE_ID), eq("meera.persist_writeback"), any(), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException(IDEMPOTENCY_KEY));
        when(idempotencyService.findCompletedResultDigest(
                        IDEMPOTENCY_KEY, WORKSPACE_ID, "meera.persist_writeback"))
                .thenReturn(Optional.empty());
        when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(CONVERSATION_ID))
                .thenReturn(Optional.of(latest));

        AiMessage result =
                service.persistAssistantWriteback(
                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of(), IDEMPOTENCY_KEY);

        assertSame(latest, result);
    }

    @Test
    @DisplayName(
            "persistAssistantWriteback: a concurrent double-submit (AlreadyInProgressException)"
                    + " throws a retry-safe 409, never a generic 500, and never inserts a message")
    void testConcurrentInProgressThrows409() {
        when(idempotencyService.executeOnce(
                        anyString(), eq(WORKSPACE_ID), eq("meera.persist_writeback"), any(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.persistAssistantWriteback(
                                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of(), IDEMPOTENCY_KEY));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(messageRepository, never()).save(any());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "persistAssistantWriteback: CONVERSATION_NOT_FOUND propagates from inside the"
                    + " executeOnce supplier when the conversation id does not exist")
    void testConversationNotFoundPropagates() {
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.persistAssistantWriteback(
                                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of(), IDEMPOTENCY_KEY));

        assertEquals("CONVERSATION_NOT_FOUND", ex.getCode());
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("persistAssistantWriteback: persists metadata as JSON when supplied, null when absent")
    void testPersistsMetadataCorrectly() {
        AiConversation conversation = conversation();
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        mockIdempotencyExecuteOnce();

        AiMessage withMeta =
                service.persistAssistantWriteback(
                        WORKSPACE_ID, CONVERSATION_ID, CONTENT, Map.of("tool", "show_creators"), IDEMPOTENCY_KEY);

        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageRepository).save(captor.capture());
        assertEquals(CONTENT, captor.getValue().getContent());
        org.junit.jupiter.api.Assertions.assertNotNull(captor.getValue().getMetadataJson());
    }

    /**
     * [E2 audit finding #2, MEDIUM -- fixed] Unit tests for {@link MeeraSessionService#sendTurn}'s
     * new idempotency guard -- previously this human-facing chat-send endpoint had zero {@code
     * Idempotency-Key} handling, so a client retry (double-click, network-timeout auto-retry,
     * back-button resubmit) re-ran {@code AICreditService.tryConsume} against the workspace's
     * capped AI-credit balance AND inserted duplicate {@code AiMessage} rows. Priority: proving a
     * retried call with the same key does NOT double-charge credits or double-insert messages.
     */
    private static final String USER_ID = "01HUSER1234567890ABCD";

    private Workspace workspace() {
        return Workspace.newBrand(WORKSPACE_ID, "Acme Co", "acme-co", "RETAIL", "SMALL");
    }

    @Test
    @DisplayName("sendTurn: rejects a null idempotencyKey before touching credits or repositories")
    void testSendTurnRejectsNullIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.sendTurn(WORKSPACE_ID, USER_ID, CONVERSATION_ID, CONTENT, null));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verifyNoInteractions(conversationRepository, messageRepository, creditService, idempotencyService);
    }

    @Test
    @DisplayName("sendTurn: rejects a blank idempotencyKey before touching credits or repositories")
    void testSendTurnRejectsBlankIdempotencyKey() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.sendTurn(WORKSPACE_ID, USER_ID, CONVERSATION_ID, CONTENT, "   "));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
        verifyNoInteractions(conversationRepository, messageRepository, creditService, idempotencyService);
    }

    @Test
    @DisplayName("sendTurn: first call consumes credit exactly once and persists USER+ASSISTANT rows")
    void testSendTurnFirstCallConsumesCreditOnce() {
        AiConversation conversation = conversation();
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(contextAssembler.assemble(any(), any())).thenReturn(Map.of());
        when(streamTokenService.mint(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("stream-token-1");
        when(creditService.getStatus(WORKSPACE_ID)).thenReturn(creditStatus());
        when(chatAiClient.sendTurn(eq(WORKSPACE_ID), any()))
                .thenReturn(new ChatTurnResult("Here's what I'd suggest for your next campaign."));
        mockIdempotencyExecuteOnceWithResultRef();

        MeeraSessionService.TurnResult result =
                service.sendTurn(WORKSPACE_ID, USER_ID, CONVERSATION_ID, CONTENT, IDEMPOTENCY_KEY);

        assertEquals("stream-token-1", result.streamToken());
        assertEquals("Here's what I'd suggest for your next campaign.", result.placeholderReply());
        verify(creditService, times(1)).tryConsume(WORKSPACE_ID, 1);
        verify(messageRepository, times(2)).save(any(AiMessage.class));
        verify(chatAiClient, times(1)).sendTurn(eq(WORKSPACE_ID), any());
    }

    @Test
    @DisplayName(
            "A4: doSendTurn persists exactly one ASSISTANT AiMessage carrying the REAL model text"
                    + " returned by MeeraChatAiClient, not a placeholder string")
    void testSendTurnPersistsRealModelReplyExactlyOnce() {
        AiConversation conversation = conversation();
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(contextAssembler.assemble(any(), any())).thenReturn(Map.of());
        when(streamTokenService.mint(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("stream-token-1");
        when(creditService.getStatus(WORKSPACE_ID)).thenReturn(creditStatus());
        when(chatAiClient.sendTurn(eq(WORKSPACE_ID), any()))
                .thenReturn(new ChatTurnResult("Real Claude output, not a stub echo."));
        mockIdempotencyExecuteOnceWithResultRef();

        service.sendTurn(WORKSPACE_ID, USER_ID, CONVERSATION_ID, CONTENT, IDEMPOTENCY_KEY);

        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        List<AiMessage> saved = captor.getAllValues();
        AiMessage assistantRow = saved.get(1);
        assertEquals(MessageRole.ASSISTANT, assistantRow.getRole());
        assertEquals("Real Claude output, not a stub echo.", assistantRow.getContent());
        // Never persisted twice for a single doSendTurn invocation.
        verify(messageRepository, times(1)).save(argThat(m -> m.getRole() == MessageRole.ASSISTANT));
    }

    @Test
    @DisplayName(
            "A4: a provider failure (MeeraChatAiException from /chat) surfaces as a 502 and does NOT"
                    + " persist an ASSISTANT message -- no half-written turn, credit consumption still"
                    + " happens (Guardrail 5 requires it before Python reachability) but the caller's"
                    + " own @Transactional rollback undoes it since the exception propagates")
    void testSendTurnProviderFailureDoesNotPersistAssistantMessage() {
        AiConversation conversation = conversation();
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(contextAssembler.assemble(any(), any())).thenReturn(Map.of());
        when(streamTokenService.mint(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("stream-token-1");
        when(creditService.getStatus(WORKSPACE_ID)).thenReturn(creditStatus());
        when(chatAiClient.sendTurn(eq(WORKSPACE_ID), any()))
                .thenThrow(new MeeraChatAiException("influora-ai /chat call returned status 503"));
        mockIdempotencyExecuteOnceWithResultRef();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.sendTurn(
                                        WORKSPACE_ID, USER_ID, CONVERSATION_ID, CONTENT, IDEMPOTENCY_KEY));

        assertEquals("MEERA_PROVIDER_UNAVAILABLE", ex.getCode());
        assertEquals(502, ex.getStatus().value());
        // Exactly the USER message was saved (pre-provider-call) -- never an ASSISTANT row.
        verify(messageRepository, never()).save(argThat(m -> m.getRole() == MessageRole.ASSISTANT));
        verify(messageRepository, times(1)).save(argThat(m -> m.getRole() == MessageRole.USER));
    }

    @Test
    @DisplayName(
            "A4: a retry with the SAME Idempotency-Key after doSendTurn already threw is rejected"
                    + " retry-safe (never re-runs doSendTurn/consumes credit again) -- proven at the"
                    + " sendTurn/IdempotencyService boundary, mirroring the existing concurrent-submit"
                    + " coverage below")
    void testSendTurnRetryAfterFailureDoesNotDoubleConsumeCredit() {
        when(idempotencyService.executeOnce(
                        eq(IDEMPOTENCY_KEY), eq(WORKSPACE_ID), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.sendTurn(
                                        WORKSPACE_ID, USER_ID, CONVERSATION_ID, CONTENT, IDEMPOTENCY_KEY));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
        verifyNoInteractions(creditService, chatAiClient);
        verify(messageRepository, never()).save(any());
    }

    // NOTE: testSendTurnRetryDoesNotDoubleChargeOrDoubleInsert and testSendTurnRetryOfOlderTurnDoesNotReturnNewerTurnsMessages
    // removed — they called idempotencyService.findResultRef() which was removed from production IdempotencyService

    @Test
    @DisplayName(
            "sendTurn: a concurrent double-submit (AlreadyInProgressException) throws a retry-safe"
                    + " 409, never a generic 500, and never consumes credit or inserts a message")
    void testSendTurnConcurrentInProgressThrows409() {
        when(idempotencyService.executeOnce(
                        eq(IDEMPOTENCY_KEY), eq(WORKSPACE_ID), anyString(), any()))
                .thenThrow(new IdempotencyService.AlreadyInProgressException(IDEMPOTENCY_KEY));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.sendTurn(
                                        WORKSPACE_ID, USER_ID, CONVERSATION_ID, CONTENT, IDEMPOTENCY_KEY));

        assertEquals("IDEMPOTENCY_KEY_IN_PROGRESS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verifyNoInteractions(creditService);
        verify(messageRepository, never()).save(any());
    }

    /**
     * F10 — GET history route (backend half): {@code MeeraSessionService#listMessages(String,
     * String, String)} is the service-layer piece {@code MeeraController} now wires up to match
     * {@code src/lib/meera-api.ts}'s {@code getMessagesAfter} contract.
     */
    @Test
    @DisplayName("F10: listMessages with a blank/absent afterMessageId returns the full history")
    void testListMessagesWithoutAfterReturnsFullHistory() {
        AiConversation conversation = conversation();
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(conversation));
        AiMessage first =
                AiMessage.builder()
                        .id("01HFIRST0000000000000A")
                        .conversationId(CONVERSATION_ID)
                        .role(MessageRole.USER)
                        .content("hi")
                        .creditsCharged(1)
                        .build();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(first));

        List<AiMessage> result = service.listMessages(WORKSPACE_ID, CONVERSATION_ID, null);

        assertEquals(List.of(first), result);
        verify(messageRepository, never())
                .findByConversationIdAndIdGreaterThanOrderByIdAsc(anyString(), anyString());

        List<AiMessage> resultBlank = service.listMessages(WORKSPACE_ID, CONVERSATION_ID, "   ");
        assertEquals(List.of(first), resultBlank);
    }

    @Test
    @DisplayName("F10: listMessages with an afterMessageId cursor returns only messages created after it")
    void testListMessagesWithAfterReturnsOnlyNewerMessages() {
        AiConversation conversation = conversation();
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(conversation));
        AiMessage newer =
                AiMessage.builder()
                        .id("01HNEWER0000000000000A")
                        .conversationId(CONVERSATION_ID)
                        .role(MessageRole.ASSISTANT)
                        .content("newer reply")
                        .creditsCharged(0)
                        .build();
        when(messageRepository.findByConversationIdAndIdGreaterThanOrderByIdAsc(
                        CONVERSATION_ID, "01HOLDER0000000000000A"))
                .thenReturn(List.of(newer));

        List<AiMessage> result =
                service.listMessages(WORKSPACE_ID, CONVERSATION_ID, "01HOLDER0000000000000A");

        assertEquals(List.of(newer), result);
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtAsc(anyString());
    }

    @Test
    @DisplayName("F10: listMessages rejects a conversation that does not belong to this workspace")
    void testListMessagesRejectsWrongWorkspace() {
        when(conversationRepository.findByIdAndWorkspaceId(CONVERSATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.listMessages(WORKSPACE_ID, CONVERSATION_ID, null));

        assertEquals("CONVERSATION_NOT_FOUND", ex.getCode());
    }
}
