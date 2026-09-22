package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCreditProperties;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.integration.ai.MeeraVoiceAiClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorContextService;
import com.influora.service.credits.CreatorCreditService;
import com.influora.service.meera.MeeraSessionService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md &sect;12, &sect;7.2, Kabir K-06) — A19/A20: {@link
 * CreatorMeeraController#speak}'s binding of a real Sarvam call to an OWNED, unrefunded {@code
 * tts:<turnId>} debit (A19), and the refund scoping on a Sarvam failure (A20). Constructed exactly
 * like {@link CreatorMeeraControllerTest} — the controller built directly over Mockito
 * collaborators, not a {@code @WebMvcTest} slice — because A19/A20 are about what the CONTROLLER
 * decides to call (Sarvam, {@code hasVoiceCharge}, {@code claimVoiceSpeak}, {@code release}), not
 * about {@link CreatorCreditService}'s own internal ledger math (covered elsewhere by the
 * {@code @DataJpaTest} suites under {@code service.credits}).
 */
@ExtendWith(MockitoExtension.class)
class CreatorVoiceSpeakChargeTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String OWN_TURN_ID = "01HTURN0000000000000A1";

    @Mock private MeeraSessionService sessionService;
    @Mock private CreatorContextService creatorContext;
    @Mock private MeeraStreamProperties streamProperties;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private MeeraVoiceAiClient voiceAiClient;
    @Mock private MeeraCreatorFeatureProperties featureProperties;
    @Mock private CreatorCreditService creatorCreditService;
    @Mock private CreatorCreditProperties creditProperties;
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
                        featureProperties,
                        creatorCreditService,
                        creditProperties);
        when(featureProperties.isCreatorEnabled()).thenReturn(true);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        when(creatorProfile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        // Every test in this class exercises the flag-ON branch of #speak; the flag-off contract
        // (turnId ignored entirely) is already covered by CreatorMeeraControllerTest.
        when(creditProperties.isEnabled()).thenReturn(true);
    }

    private static CreatorMeeraController.VoiceSpeakRequest body(String turnId) {
        return new CreatorMeeraController.VoiceSpeakRequest("hello meera", "en-IN", turnId);
    }

    // ------------------------------------------------------------------
    // A19
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A19: no turnId, a text-only turn, or another creator's turnId all fall back without ever"
                    + " calling Sarvam; the creator's own paid voice turn reaches Sarvam; the 4th claim"
                    + " for the same turn falls back too")
    void speakRequiresOwnPaidVoiceTurn() {
        // No turnId at all: the controller short-circuits before ever asking CreatorCreditService.
        ResponseEntity<?> noTurnId = controller.speak(principal, body(null));
        assertEquals(Map.of("fallback", true), noTurnId.getBody());
        verifyNoInteractions(voiceAiClient);

        // A text-only (non-voice) turn: no tts: debit exists for it, so hasVoiceCharge is false.
        when(creatorCreditService.hasVoiceCharge(CREATOR_USER_ID, "text-only-turn")).thenReturn(false);
        ResponseEntity<?> textOnly = controller.speak(principal, body("text-only-turn"));
        assertEquals(Map.of("fallback", true), textOnly.getBody());
        verifyNoInteractions(voiceAiClient);

        // Another creator's turnId: hasVoiceCharge is scoped to the RESOLVED creator's own user id
        // (CREATOR_USER_ID), so a turn that belongs to someone else never has a charge to find under
        // this creator's id either — the controller cannot tell the two cases apart, and must not.
        when(creatorCreditService.hasVoiceCharge(CREATOR_USER_ID, "someone-elses-turn")).thenReturn(false);
        ResponseEntity<?> otherCreatorsTurn = controller.speak(principal, body("someone-elses-turn"));
        assertEquals(Map.of("fallback", true), otherCreatorsTurn.getBody());
        verifyNoInteractions(voiceAiClient);

        // The creator's OWN paid voice turn: hasVoiceCharge + claimVoiceSpeak both true -> Sarvam IS called.
        when(creatorCreditService.hasVoiceCharge(CREATOR_USER_ID, OWN_TURN_ID)).thenReturn(true);
        when(creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, OWN_TURN_ID)).thenReturn(true);
        when(voiceAiClient.speak(CREATOR_USER_ID, "hello meera", "en-IN"))
                .thenReturn(MeeraVoiceAiClient.SpeakResult.audio(new byte[] {1, 2, 3}, "audio/wav"));

        ResponseEntity<?> own = controller.speak(principal, body(OWN_TURN_ID));
        assertEquals(HttpStatus.OK, own.getStatusCode());
        verify(voiceAiClient).speak(CREATOR_USER_ID, "hello meera", "en-IN");

        // The 4th claim for the SAME turn: hasVoiceCharge is still true (nothing refunded it), but
        // claimVoiceSpeak returns false at the per-turn cap (voice-speaks-per-turn=3) -> fallback,
        // and Sarvam is NOT called a second time.
        when(creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, OWN_TURN_ID)).thenReturn(false);
        ResponseEntity<?> fourthCall = controller.speak(principal, body(OWN_TURN_ID));
        assertEquals(Map.of("fallback", true), fourthCall.getBody());
        verify(voiceAiClient, times(1)).speak(any(), any(), any());
        verify(creatorCreditService, never()).release(any(), any(), any());
    }

    // ------------------------------------------------------------------
    // A20
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A20/K-15 round-2 fix — a Sarvam failure on a paid voice turn asks CreatorCreditService to"
                    + " decide the VOICE_ONLY (tts:) refund, and marks nothing delivered; it never calls"
                    + " release(...) directly or touches the TURN (text) credit from the controller")
    void sarvamFailureRefundsVoiceSurchargeOnly() {
        when(creatorCreditService.hasVoiceCharge(CREATOR_USER_ID, OWN_TURN_ID)).thenReturn(true);
        when(creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, OWN_TURN_ID)).thenReturn(true);
        when(voiceAiClient.speak(CREATOR_USER_ID, "hello meera", "en-IN"))
                .thenReturn(MeeraVoiceAiClient.SpeakResult.fallback());

        ResponseEntity<?> response = controller.speak(principal, body(OWN_TURN_ID));

        assertEquals(Map.of("fallback", true), response.getBody());
        verify(creatorCreditService).releaseVoiceIfUndelivered(CREATOR_USER_ID, OWN_TURN_ID);
        verify(creatorCreditService, never()).markVoiceDelivered(any(), any());
        verify(creatorCreditService, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName(
            "A Sarvam success marks the turn delivered (never calls release directly) — the actual"
                    + " no-double-refund invariant for concurrent/retried claims now lives in"
                    + " CreatorCreditService#releaseVoiceIfUndelivered, proven for real (not mocked) in"
                    + " CreatorVoiceRefundRaceTest")
    void sarvamSuccessMarksDeliveredNeverReleases() {
        when(creatorCreditService.hasVoiceCharge(CREATOR_USER_ID, OWN_TURN_ID)).thenReturn(true);
        when(creatorCreditService.claimVoiceSpeak(CREATOR_USER_ID, OWN_TURN_ID)).thenReturn(true);
        when(voiceAiClient.speak(CREATOR_USER_ID, "hello meera", "en-IN"))
                .thenReturn(MeeraVoiceAiClient.SpeakResult.audio(new byte[] {1, 2, 3}, "audio/wav"));

        ResponseEntity<?> first = controller.speak(principal, body(OWN_TURN_ID));

        assertEquals(HttpStatus.OK, first.getStatusCode());
        verify(creatorCreditService).markVoiceDelivered(CREATOR_USER_ID, OWN_TURN_ID);
        verify(creatorCreditService, never()).release(any(), any(), any());
        verify(creatorCreditService, never()).releaseVoiceIfUndelivered(any(), any());
    }
}
