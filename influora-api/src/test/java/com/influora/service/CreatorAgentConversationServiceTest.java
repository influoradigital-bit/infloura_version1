package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MeeraCreatorConversation;
import com.influora.repository.AiConversationRepository;
import com.influora.repository.AiMessageRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MeeraCreatorConversationRepository;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-MEERA-CREATOR-PHASE-A (fix round 1, item 4). Proves the "write side" gap the class javadoc
 * used to flag is closed: {@link CreatorAgentConversationService#recordTurnForUser} — now called
 * from {@code MeeraSessionService}'s USER-turn and ASSISTANT-write-back paths for every CREATOR
 * turn — actually creates a {@link MeeraCreatorConversation} row that then shows up via {@link
 * CreatorAgentConversationService#listConversations}.
 */
@ExtendWith(MockitoExtension.class)
class CreatorAgentConversationServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE001A";
    private static final String CONVERSATION_ID = "01HCONVERSATION1234AB";

    @Mock private MeeraCreatorConversationRepository conversationRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private AiConversationRepository aiConversationRepository;
    @Mock private AiMessageRepository aiMessageRepository;

    private CreatorAgentConversationService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorAgentConversationService(
                        conversationRepository, creatorProfileRepository, aiConversationRepository, aiMessageRepository);

        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getId()).thenReturn(CREATOR_PROFILE_ID);
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.of(profile));
    }

    @Test
    @DisplayName(
            "recordTurnForUser: resolves the creator's own profile from their userId, then creates a"
                    + " NEW MeeraCreatorConversation row on the first call for a conversationId")
    void testRecordTurnForUserCreatesRowOnFirstCall() {
        when(conversationRepository.findByConversationId(CONVERSATION_ID)).thenReturn(Optional.empty());
        ArgumentCaptor<MeeraCreatorConversation> captor = ArgumentCaptor.forClass(MeeraCreatorConversation.class);
        when(conversationRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.recordTurnForUser(CREATOR_USER_ID, CONVERSATION_ID, Instant.now());

        MeeraCreatorConversation saved = captor.getValue();
        assertEquals(CREATOR_PROFILE_ID, saved.getCreatorId());
        assertEquals(CONVERSATION_ID, saved.getConversationId());
        assertEquals(1, saved.getMessageCount());
    }

    @Test
    @DisplayName(
            "recordTurnForUser: a persisted creator turn (a row created by recordTurnForUser) IS"
                    + " returned by listConversations for that same creator -- closes the 'write side'"
                    + " gap this service's class javadoc used to flag as unimplemented")
    void testPersistedCreatorTurnAppearsInListConversations() {
        // First turn (e.g. doSendTurn's USER-turn call): no row exists yet -- created fresh.
        when(conversationRepository.findByConversationId(CONVERSATION_ID)).thenReturn(Optional.empty());
        ArgumentCaptor<MeeraCreatorConversation> createCaptor =
                ArgumentCaptor.forClass(MeeraCreatorConversation.class);
        when(conversationRepository.save(createCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Instant firstTurnAt = Instant.now();
        service.recordTurnForUser(CREATOR_USER_ID, CONVERSATION_ID, firstTurnAt);
        MeeraCreatorConversation afterFirstTurn = createCaptor.getValue();

        // listConversations reads whatever recordTurnForUser actually persisted -- proving this is
        // real, end-to-end write-then-read coverage, not just an isolated save() call.
        when(conversationRepository.findByCreatorIdOrderByLastMessageAtDesc(CREATOR_PROFILE_ID))
                .thenReturn(List.of(afterFirstTurn));

        List<ConversationSummary> summaries = service.listConversations(CREATOR_USER_ID).conversations();

        assertEquals(1, summaries.size());
        assertEquals(CONVERSATION_ID, summaries.get(0).conversationId());
        assertEquals(1, summaries.get(0).messageCount());
    }

    @Test
    @DisplayName("recordTurnForUser: a SECOND call for the SAME conversationId upserts the EXISTING row (bumps messageCount) rather than creating a duplicate")
    void testRecordTurnForUserUpsertsExistingRowOnSecondCall() {
        MeeraCreatorConversation existing =
                MeeraCreatorConversation.start("row-1", CREATOR_PROFILE_ID, CONVERSATION_ID, Instant.now());
        when(conversationRepository.findByConversationId(CONVERSATION_ID)).thenReturn(Optional.of(existing));
        when(conversationRepository.save(any(MeeraCreatorConversation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordTurnForUser(CREATOR_USER_ID, CONVERSATION_ID, Instant.now());

        assertEquals(1, existing.getMessageCount());
        assertEquals(CONVERSATION_ID, existing.getConversationId());
    }
}
