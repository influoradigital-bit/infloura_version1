package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.ConversationTenantType;
import com.influora.domain.enums.MessageRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.IdempotencyService;
import com.influora.service.creatorcopilot.CreatorRecommendationService;
import com.influora.service.creatorcopilot.CreatorRecommendationWriter;
import com.influora.service.credits.CreatorCreditService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Meera intelligence v1, slice 2 (spec 8.3) -- a CREATOR write-back's {@code
 * metadata.recommendations} are recorded through the REAL {@link CreatorRecommendationService} and
 * {@link CreatorRecommendationWriter} (repositories mocked), AFTER the write-back transaction has
 * committed, keyed on the turn's messageId, and a recording failure never fails the write-back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorWritebackRecommendationsTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER0000000A";
    private static final String PROFILE_ID = "01HCREATORPROFILE0000A";
    private static final String CONVERSATION_ID = "01HCONVERSATION1234AB";
    private static final String TURN_ID = "01HTURNMESSAGEID00000000A";
    private static final String CONTENT = "Your week:\n1. Fri 26 Sep - Reel ...";

    @Mock private AiConversationRepository conversationRepository;
    @Mock private AiMessageRepository messageRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandProfileRepository brandProfileRepository;
    @Mock private BrandContextAssembler contextAssembler;
    @Mock private StreamTokenService streamTokenService;
    @Mock private OnBehalfTokenService onBehalfTokenService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private CreatorAgentConversationService creatorAgentConversationService;
    @Mock private CreatorAgentPreferencesService creatorAgentPreferencesService;
    @Mock private CreatorCreditService creatorCreditService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AICreditService creditService;
    @Mock private CreatorRecommendationRepository recommendationRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;

    private MeeraSessionService service;

    @BeforeEach
    void setUp() {
        CreatorRecommendationService recommendationService =
                new CreatorRecommendationService(
                        recommendationRepository,
                        new CreatorRecommendationWriter(recommendationRepository, creatorProfileRepository),
                        new ObjectMapper());
        service =
                new MeeraSessionService(
                        conversationRepository,
                        messageRepository,
                        workspaceRepository,
                        brandProfileRepository,
                        creditService,
                        contextAssembler,
                        streamTokenService,
                        onBehalfTokenService,
                        idempotencyService,
                        creatorAgentConversationService,
                        creatorAgentPreferencesService,
                        creatorCreditService,
                        transactionManager,
                        recommendationService);
        when(idempotencyService.executeOnce(anyString(), anyString(), eq(MeeraSessionService.PERSIST_WRITEBACK_SCOPE), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<AiMessage> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(creatorConversation()));
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, CREATOR_USER_ID, "Test Creator");
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.of(profile));
        when(recommendationRepository.findExistingSourceRefs(any(), any(), any())).thenReturn(List.of());
        when(recommendationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AiConversation creatorConversation() {
        return AiConversation.builder()
                .id(CONVERSATION_ID)
                .workspaceId(CREATOR_USER_ID)
                .tenantType(ConversationTenantType.CREATOR)
                .startedBy(CREATOR_USER_ID)
                .status(ConversationStatus.ACTIVE)
                .build();
    }

    private static Map<String, Object> metadataWithTwoPlanLines() {
        return Map.of(
                "prompt_version", "meera-2026.09.25.15",
                "knowledge_version", "ck-2026.09.25.1",
                "recommendations",
                        List.of(
                                Map.of(
                                        "source", "PLAN_MY_WEEK",
                                        "line_index", 0,
                                        "recommended_for", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1).toString(),
                                        "post_type", "REEL"),
                                Map.of("source", "SCRIPT_CARD", "line_index", 1, "post_type", "CAROUSEL"),
                                Map.of("source", "NOT_A_SOURCE", "line_index", 2, "post_type", "REEL"),
                                Map.of("source", "PLAN_MY_WEEK", "line_index", 3, "recommended_for", "2099-01-01", "post_type", "REEL")));
    }

    @Test
    @DisplayName(
            "CREATOR write-back with recommendations: rows recorded with source_ref = the turn's"
                    + " messageId:line_index and the conversation id, AFTER the write-back transaction commits;"
                    + " the unknown-source item is dropped")
    @SuppressWarnings("unchecked")
    void creatorWritebackRecordsAfterCommit() {
        AiMessage message =
                service.persistAssistantWriteback(
                        CREATOR_USER_ID, CONVERSATION_ID, CONTENT, metadataWithTwoPlanLines(), TURN_ID, UserType.CREATOR);

        assertEquals(MessageRole.ASSISTANT, message.getRole());
        ArgumentCaptor<List<CreatorRecommendation>> rows = ArgumentCaptor.forClass(List.class);
        verify(recommendationRepository).saveAll(rows.capture());
        // Unknown source and a far-future plan date are dropped; the plan line and script are kept.
        assertEquals(
                List.of(TURN_ID + ":0", TURN_ID + ":1"),
                rows.getValue().stream().map(CreatorRecommendation::getSourceRef).toList());
        assertEquals(CONVERSATION_ID, rows.getValue().get(0).getConversationId());
        assertEquals(PROFILE_ID, rows.getValue().get(0).getCreatorProfileId());

        // Ordering proof: the write-back's transaction commits BEFORE any recommendation SQL runs,
        // so the insert can never wait on the credit-account lock that transaction holds.
        InOrder order = Mockito.inOrder(messageRepository, transactionManager, recommendationRepository);
        order.verify(messageRepository).save(any(AiMessage.class));
        order.verify(transactionManager).commit(any());
        order.verify(recommendationRepository, Mockito.times(2)).findExistingSourceRefs(any(), any(), any());
        order.verify(recommendationRepository).saveAll(any());
    }

    @Test
    @DisplayName("a recording failure (database error on insert) never fails the write-back: the message still returns")
    void recordingFailureDoesNotFailWriteback() {
        doThrow(new QueryTimeoutException("lock wait timeout")).when(recommendationRepository).saveAll(any());

        AiMessage message =
                service.persistAssistantWriteback(
                        CREATOR_USER_ID, CONVERSATION_ID, CONTENT, metadataWithTwoPlanLines(), TURN_ID, UserType.CREATOR);

        assertEquals(CONTENT, message.getContent());
        verify(creatorAgentConversationService).recordTurnForUser(eq(CREATOR_USER_ID), eq(CONVERSATION_ID), any());
    }

    @Test
    @DisplayName("replay of a completed write-back returns the same message and re-records as a no-op")
    @SuppressWarnings("unchecked")
    void replayReRecordsAsNoOp() {
        AiMessage previous =
                AiMessage.builder().id("01HPREVIOUSMESSAGE000000A").conversationId(CONVERSATION_ID).role(MessageRole.ASSISTANT).content(CONTENT).build();
        when(idempotencyService.executeOnce(anyString(), anyString(), eq(MeeraSessionService.PERSIST_WRITEBACK_SCOPE), any(), any()))
                .thenThrow(new IdempotencyService.AlreadyCompletedException(TURN_ID));
        when(idempotencyService.findCompletedResultDigest(TURN_ID, CREATOR_USER_ID, MeeraSessionService.PERSIST_WRITEBACK_SCOPE))
                .thenReturn(Optional.of(previous.getId()));
        when(messageRepository.findById(previous.getId())).thenReturn(Optional.of(previous));
        when(recommendationRepository.findExistingSourceRefs(any(), any(), any()))
                .thenAnswer(inv -> List.copyOf((java.util.Collection<String>) inv.getArgument(2)));

        AiMessage replayed =
                service.persistAssistantWriteback(
                        CREATOR_USER_ID, CONVERSATION_ID, CONTENT, metadataWithTwoPlanLines(), TURN_ID, UserType.CREATOR);

        assertSame(previous, replayed);
        verify(recommendationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a BRAND write-back never records creator recommendations, even if the metadata carries some")
    void brandWritebackRecordsNothing() {
        when(creditService.wasCharged(anyString(), anyString())).thenReturn(true);

        service.persistAssistantWriteback(
                CREATOR_USER_ID, CONVERSATION_ID, CONTENT, metadataWithTwoPlanLines(), TURN_ID, UserType.BRAND);

        verifyNoInteractions(recommendationRepository);
    }
}
