package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorContextService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.web.dto.meera.MeeraDtos.SendTurnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * T-MEERA-CREATOR-PHASE-A (fix round 2, item 1 — Priya Q3). Proves the DPDP consent PRECONDITION
 * in {@link CreatorMeeraController} actually runs BEFORE anything is persisted or minted:
 * previously an unconsented creator's message was written to {@code ai_messages} and a stream +
 * on-behalf token minted before influora-ai's Python-side {@code CONSENT_REQUIRED} 403 ever fired.
 * Now {@link CreatorMeeraController#startSession} and {@link CreatorMeeraController#sendTurn} both
 * reject an unconsented creator with {@code 403 CONSENT_REQUIRED} without ever calling {@link
 * MeeraSessionService}.
 */
@ExtendWith(MockitoExtension.class)
class CreatorMeeraControllerTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String CONVERSATION_ID = "01HCONVERSATION1234567";
    private static final String IDEMPOTENCY_KEY = "idem-key-1";

    @Mock private MeeraSessionService sessionService;
    @Mock private CreatorContextService creatorContext;
    @Mock private MeeraStreamProperties streamProperties;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private AuthPrincipal principal;
    @Mock private CreatorProfile creatorProfile;

    private CreatorMeeraController controller;

    @BeforeEach
    void setUp() {
        controller =
                new CreatorMeeraController(sessionService, creatorContext, streamProperties, preferencesService);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(creatorProfile.getUserId()).thenReturn(CREATOR_USER_ID);
    }

    @Test
    @DisplayName("startSession: an unconsented creator is rejected with 403 CONSENT_REQUIRED before startOrResume runs")
    void startSession_unconsentedCreator_rejectedBeforeAnyPersistence() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.startSession(principal));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName(
            "startSession: a consented creator proceeds to startOrResumeForCreator (gate fix round"
                    + " 1, Priya Q1 -- the CREATOR-specific overload that persists the day-one"
                    + " onboarding greeting on first creation, not the generic BRAND startOrResume)")
    void startSession_consentedCreator_proceeds() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfile.getDisplayName()).thenReturn("Priya Shah");
        AiConversation conversation =
                AiConversation.builder()
                        .id(CONVERSATION_ID)
                        .workspaceId(CREATOR_USER_ID)
                        .startedBy(CREATOR_USER_ID)
                        .status(ConversationStatus.ACTIVE)
                        .build();
        when(sessionService.startOrResumeForCreator(CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah"))
                .thenReturn(conversation);

        controller.startSession(principal);

        verify(sessionService).startOrResumeForCreator(CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah");
    }

    @Test
    @DisplayName(
            "sendTurn: an unconsented creator is rejected with 403 CONSENT_REQUIRED -- the message is"
                    + " NEVER handed to MeeraSessionService, so nothing is persisted and no token is"
                    + " minted for a principal that has never consented")
    void sendTurn_unconsentedCreator_rejectedBeforeAnyPersistence() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);
        SendTurnRequest body = new SendTurnRequest("hello meera");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.sendTurn(principal, CONVERSATION_ID, IDEMPOTENCY_KEY, body));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("sendTurn: a consented creator's turn reaches MeeraSessionService.sendTurn")
    void sendTurn_consentedCreator_proceeds() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        SendTurnRequest body = new SendTurnRequest("hello meera");
        MeeraSessionService.TurnResult result =
                new MeeraSessionService.TurnResult(
                        "msg-1", null, "stream-token", "onbehalf-token", java.util.Map.of(), null);
        when(sessionService.sendTurn(
                        eq(CREATOR_USER_ID),
                        eq(CREATOR_USER_ID),
                        eq(UserType.CREATOR),
                        eq(CONVERSATION_ID),
                        anyString(),
                        eq(IDEMPOTENCY_KEY)))
                .thenReturn(result);

        controller.sendTurn(principal, CONVERSATION_ID, IDEMPOTENCY_KEY, body);

        verify(sessionService)
                .sendTurn(
                        eq(CREATOR_USER_ID),
                        eq(CREATOR_USER_ID),
                        eq(UserType.CREATOR),
                        eq(CONVERSATION_ID),
                        anyString(),
                        eq(IDEMPOTENCY_KEY));
    }
}
