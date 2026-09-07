package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.AiConversation;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.ConversationStatus;
import com.influora.domain.enums.ConversationTenantType;
import com.influora.domain.enums.UserType;
import com.influora.integration.ai.MeeraVoiceAiClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorContextService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.MeeraDtos.SendTurnRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

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
    @Mock private MeeraVoiceAiClient voiceAiClient;
    @Mock private MeeraCreatorFeatureProperties featureProperties;
    @Mock private AuthPrincipal principal;
    @Mock private CreatorProfile creatorProfile;

    private CreatorMeeraController controller;

    @BeforeEach
    void setUp() {
        controller =
                new CreatorMeeraController(
                        sessionService,
                        creatorContext,
                        streamProperties,
                        preferencesService,
                        voiceAiClient,
                        featureProperties);
        // Every handler on this controller calls requireFeatureEnabled() first (Priya gate review
        // defect 4) -- on by default here so every pre-existing test below still exercises its own
        // real behavior; the flag-off tests further down override this per-test.
        when(featureProperties.isCreatorEnabled()).thenReturn(true);
        // lenient: the flag-off tests never reach requireCreatorProfile at all (requireFeatureEnabled
        // throws first), and the brand-principal tests override this stub entirely -- both would
        // otherwise trip MockitoExtension's strict-stubs UnnecessaryStubbingException.
        lenient().when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        lenient().when(creatorProfile.getUserId()).thenReturn(CREATOR_USER_ID);
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
        when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID))
                .thenReturn(preferencesResponseWithLanguage("en-IN"));
        AiConversation conversation =
                AiConversation.builder()
                        .id(CONVERSATION_ID)
                        .workspaceId(CREATOR_USER_ID)
                        .tenantType(ConversationTenantType.CREATOR)
                        .startedBy(CREATOR_USER_ID)
                        .status(ConversationStatus.ACTIVE)
                        .build();
        when(sessionService.startOrResumeForCreator(CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah", "en-IN"))
                .thenReturn(conversation);

        controller.startSession(principal);

        verify(sessionService)
                .startOrResumeForCreator(CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah", "en-IN");
    }

    @Test
    @DisplayName(
            "Gate fix round 4 (Priya's fourth pass): startSession resolves the persisted greeting's"
                    + " language from CreatorAgentPreferences.creator_language and passes it through to"
                    + " startOrResumeForCreator, rather than always building an English greeting -- V73"
                    + " defaults every creator's creator_language to 'hi-IN'")
    void startSession_passesThroughCreatorPreferenceLanguage() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfile.getDisplayName()).thenReturn("Priya Shah");
        when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID))
                .thenReturn(preferencesResponseWithLanguage("hi-IN"));
        AiConversation conversation =
                AiConversation.builder()
                        .id(CONVERSATION_ID)
                        .workspaceId(CREATOR_USER_ID)
                        .tenantType(ConversationTenantType.CREATOR)
                        .startedBy(CREATOR_USER_ID)
                        .status(ConversationStatus.ACTIVE)
                        .build();
        when(sessionService.startOrResumeForCreator(CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah", "hi-IN"))
                .thenReturn(conversation);

        controller.startSession(principal);

        verify(sessionService)
                .startOrResumeForCreator(CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah", "hi-IN");
    }

    @Test
    @DisplayName(
            "Gate fix round 4 (Priya's fourth pass): startSession falls back to"
                    + " CreatorAgentPreferences.DEFAULT_LANGUAGE when the preferences row has no"
                    + " creator_language set, rather than passing a null/blank language through")
    void startSession_defaultsLanguageWhenPreferenceLanguageAbsent() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfile.getDisplayName()).thenReturn("Priya Shah");
        when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID))
                .thenReturn(preferencesResponseWithLanguage(null));
        AiConversation conversation =
                AiConversation.builder()
                        .id(CONVERSATION_ID)
                        .workspaceId(CREATOR_USER_ID)
                        .tenantType(ConversationTenantType.CREATOR)
                        .startedBy(CREATOR_USER_ID)
                        .status(ConversationStatus.ACTIVE)
                        .build();
        when(sessionService.startOrResumeForCreator(
                        CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah", CreatorAgentPreferences.DEFAULT_LANGUAGE))
                .thenReturn(conversation);

        controller.startSession(principal);

        verify(sessionService)
                .startOrResumeForCreator(
                        CREATOR_USER_ID, CREATOR_USER_ID, "Priya Shah", CreatorAgentPreferences.DEFAULT_LANGUAGE);
    }

    /** Minimal {@link PreferencesResponse} stub -- only {@code creatorLanguage} matters to these tests. */
    private static PreferencesResponse preferencesResponseWithLanguage(String language) {
        return new PreferencesResponse(
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                0,
                language,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                false,
                null,
                true,
                null);
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

    // ---- Priya gate review defect 3: creator voice routes (POST /creator/meera/voice/speak,
    // POST /creator/meera/voice/transcribe) ----

    @Test
    @DisplayName("voice/speak: an unconsented creator is rejected with 403 CONSENT_REQUIRED before any provider call")
    void speak_unconsentedCreator_rejectedBeforeAnyPersistence() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);
        var body = new CreatorMeeraController.VoiceSpeakRequest("hello", null);

        ApiException ex = assertThrows(ApiException.class, () -> controller.speak(principal, body));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName(
            "voice/speak: a consented creator's request reaches MeeraVoiceAiClient.speak, scoped to"
                    + " the creator's OWN user id (never a brand workspace id)")
    void speak_consentedCreator_reachesVoiceClient() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        var body = new CreatorMeeraController.VoiceSpeakRequest("hello meera", "hi-IN");
        when(voiceAiClient.speak(CREATOR_USER_ID, "hello meera", "hi-IN"))
                .thenReturn(MeeraVoiceAiClient.SpeakResult.audio(new byte[] {1, 2, 3}, "audio/wav"));

        ResponseEntity<?> response = controller.speak(principal, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(voiceAiClient).speak(CREATOR_USER_ID, "hello meera", "hi-IN");
    }

    @Test
    @DisplayName("voice/speak: a BRAND-audience principal gets 403 (never a creator identity resolved)")
    void speak_brandPrincipal_rejectedWith403() {
        when(creatorContext.requireCreatorProfile(principal))
                .thenThrow(
                        new ApiException(
                                "WRONG_USER_TYPE",
                                "This endpoint is for creator accounts only",
                                HttpStatus.FORBIDDEN));
        var body = new CreatorMeeraController.VoiceSpeakRequest("hello", null);

        ApiException ex = assertThrows(ApiException.class, () -> controller.speak(principal, body));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
        verifyNoInteractions(preferencesService);
    }

    @Test
    @DisplayName("voice/transcribe: an unconsented creator is rejected with 403 CONSENT_REQUIRED before any provider call")
    void transcribe_unconsentedCreator_rejectedBeforeAnyPersistence() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);
        MockMultipartFile audio = new MockMultipartFile("audio", "clip.webm", "audio/webm", new byte[] {9, 9});

        ApiException ex = assertThrows(ApiException.class, () -> controller.transcribe(principal, audio));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName(
            "voice/transcribe: a consented creator's clip reaches MeeraVoiceAiClient.transcribe,"
                    + " scoped to the creator's OWN user id (never a brand workspace id)")
    void transcribe_consentedCreator_reachesVoiceClient() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        byte[] bytes = new byte[] {9, 9};
        MockMultipartFile audio = new MockMultipartFile("audio", "clip.webm", "audio/webm", bytes);
        when(voiceAiClient.transcribe(eq(CREATOR_USER_ID), any(), eq("audio/webm")))
                .thenReturn(
                        MeeraVoiceAiClient.TranscribeResult.json(
                                "{\"raw_transcript\":\"hi\"}".getBytes(), "application/json"));

        ResponseEntity<?> response = controller.transcribe(principal, audio);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(voiceAiClient).transcribe(eq(CREATOR_USER_ID), any(), eq("audio/webm"));
    }

    @Test
    @DisplayName("voice/transcribe: a BRAND-audience principal gets 403 (never a creator identity resolved)")
    void transcribe_brandPrincipal_rejectedWith403() {
        when(creatorContext.requireCreatorProfile(principal))
                .thenThrow(
                        new ApiException(
                                "WRONG_USER_TYPE",
                                "This endpoint is for creator accounts only",
                                HttpStatus.FORBIDDEN));
        MockMultipartFile audio = new MockMultipartFile("audio", "clip.webm", "audio/webm", new byte[] {9, 9});

        ApiException ex = assertThrows(ApiException.class, () -> controller.transcribe(principal, audio));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
        verifyNoInteractions(preferencesService);
    }

    // ---- Priya gate review defect 4: MEERA_CREATOR_ENABLED rollback flag, all /creator/meera/** ----

    @Test
    @DisplayName("startSession: flag off returns 404 FEATURE_DISABLED before identity is even resolved")
    void startSession_flagOff_returns404WithoutResolvingIdentity() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.startSession(principal));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(sessionService, preferencesService);
        verify(creatorContext, never()).requireCreatorProfile(principal);
    }

    @Test
    @DisplayName("sendTurn: flag off returns 404 FEATURE_DISABLED before identity is even resolved")
    void sendTurn_flagOff_returns404WithoutResolvingIdentity() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);
        SendTurnRequest body = new SendTurnRequest("hello meera");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.sendTurn(principal, CONVERSATION_ID, IDEMPOTENCY_KEY, body));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(sessionService, preferencesService);
        verify(creatorContext, never()).requireCreatorProfile(principal);
    }

    @Test
    @DisplayName("messages: flag off returns 404 FEATURE_DISABLED before identity is even resolved")
    void messages_flagOff_returns404WithoutResolvingIdentity() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.messages(principal, CONVERSATION_ID, null));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(sessionService);
        verify(creatorContext, never()).requireCreatorProfile(principal);
    }

    @Test
    @DisplayName("voice/speak: flag off returns 404 FEATURE_DISABLED before identity is even resolved")
    void speak_flagOff_returns404WithoutResolvingIdentity() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);
        var body = new CreatorMeeraController.VoiceSpeakRequest("hello", null);

        ApiException ex = assertThrows(ApiException.class, () -> controller.speak(principal, body));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(voiceAiClient, preferencesService);
        verify(creatorContext, never()).requireCreatorProfile(principal);
    }

    @Test
    @DisplayName("voice/transcribe: flag off returns 404 FEATURE_DISABLED before identity is even resolved")
    void transcribe_flagOff_returns404WithoutResolvingIdentity() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);
        MockMultipartFile audio = new MockMultipartFile("audio", "clip.webm", "audio/webm", new byte[] {9, 9});

        ApiException ex = assertThrows(ApiException.class, () -> controller.transcribe(principal, audio));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(voiceAiClient, preferencesService);
        verify(creatorContext, never()).requireCreatorProfile(principal);
    }
}
