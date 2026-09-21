package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.AiMessage;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.ConversationTenantType;
import com.influora.domain.enums.UserType;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.IdempotencyService;
import com.influora.service.credits.CreatorCreditService;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §12, K-15) — A18: proves review findings #10/#13's fix.
 * {@code doPersistAssistantWriteback} is reached only via a self-invoked lambda from {@code
 * IdempotencyService#executeOnce}, which used to make its own {@code @Transactional} inert — the
 * account lock {@code assertTurnNotReleased} took was released (its own, separate, auto-committed
 * transaction) BEFORE the ASSISTANT row was ever saved, leaving a window where a concurrent {@code
 * release()} refunded a turn that then still got its reply persisted for free. The fix wraps the
 * whole write-back body in one {@link org.springframework.transaction.support.TransactionTemplate}
 * transaction. This test proves the two halves of that fix directly against {@link
 * MeeraSessionService}: {@code assertTurnNotReleased} runs BEFORE the ASSISTANT insert (so a 409
 * from it means no row is ever written), in the same call.
 */
@ExtendWith(MockitoExtension.class)
class CreatorWritebackAfterReleaseTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER0000000A";
    private static final String CONVERSATION_ID = "01HCONVERSATION1234AB";
    private static final String TURN_ID = "turn-released-123";
    private static final String CONTENT = "Here's your answer.";

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
                        onBehalfTokenService,
                        idempotencyService,
                        creatorAgentConversationService,
                        creatorAgentPreferencesService,
                        creatorCreditService,
                        transactionManager);

        // executeOnce invokes the supplier directly, the same stub technique
        // MeeraSessionServiceTest uses.
        when(idempotencyService.executeOnce(
                        anyString(), eq(CREATOR_USER_ID), eq(MeeraSessionService.PERSIST_WRITEBACK_SCOPE), any(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<AiMessage> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(creatorConversation()));
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

    @Test
    @DisplayName(
            "A18: release() winning the race -> assertTurnNotReleased throws 409 TURN_RELEASED and"
                    + " NO ASSISTANT row is ever saved")
    void writebackRefusedAfterRelease() {
        doThrow(new ApiException("TURN_RELEASED", "This turn's credit was already released", HttpStatus.CONFLICT))
                .when(creatorCreditService)
                .assertTurnNotReleased(CREATOR_USER_ID, TURN_ID);

        ApiException thrown =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.persistAssistantWriteback(
                                        CREATOR_USER_ID, CONVERSATION_ID, CONTENT, Map.of(), TURN_ID, UserType.CREATOR));

        assertEquals("TURN_RELEASED", thrown.getCode());
        assertEquals(HttpStatus.CONFLICT, thrown.getStatus());
        verify(messageRepository, never()).save(any(AiMessage.class));
        verify(creatorAgentConversationService, never()).recordTurnForUser(any(), any(), any());
    }

    @Test
    @DisplayName(
            "A18 (happy path, ordering proof): assertTurnNotReleased is checked BEFORE the ASSISTANT"
                    + " row is saved, inside the same write-back call -- not after")
    void assertNotReleasedRunsBeforeAssistantRowIsPersisted() {
        when(messageRepository.save(any(AiMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        AiMessage result =
                service.persistAssistantWriteback(
                        CREATOR_USER_ID, CONVERSATION_ID, CONTENT, Map.of(), TURN_ID, UserType.CREATOR);

        assertEquals(0, result.getCreditsCharged());
        InOrder inOrder = Mockito.inOrder(creatorCreditService, messageRepository);
        inOrder.verify(creatorCreditService).assertTurnNotReleased(CREATOR_USER_ID, TURN_ID);
        inOrder.verify(messageRepository).save(any(AiMessage.class));
    }
}
