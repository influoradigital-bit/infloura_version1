package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.integration.ai.MeeraVoiceAiClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorContextService;
import com.influora.service.meera.MeeraSessionService;
import com.influora.service.meera.OnBehalfTokenService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@code POST /creator/meera/shoot-check/frame} -- the proxy that lets the app reach influora-ai's
 * Shoot Check Level 2 route.
 *
 * <p>Why this exists: before this route, the app posted to {@code /ai/shoot-check/frame} on this
 * server, which has no such path. influora-ai's route accepts only a service token, so the browser
 * could never reach it directly. Every "Check my frame" tap failed in production while the Python
 * route tests and the frontend tests both passed -- each side was tested against its own idea of
 * the other.
 *
 * <p>The gates mirror {@code voice/transcribe}: flag, creator identity from the principal, consent,
 * then the upload checks -- all before a single byte leaves this server.
 */
@ExtendWith(MockitoExtension.class)
class CreatorMeeraFrameCheckTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER000000000001";
    private static final byte[] JPEG = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    @Mock private MeeraSessionService sessionService;
    @Mock private CreatorContextService creatorContext;
    @Mock private MeeraStreamProperties streamProperties;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private MeeraVoiceAiClient voiceAiClient;
    @Mock private MeeraCreatorFeatureProperties featureProperties;
    @Mock private OnBehalfTokenService onBehalfTokenService;
    // release/0924: the controller also takes the creator-credits collaborators. The credits
    // flag is off by default on a mock, so the frame-check behaviour under test is unchanged.
    @Mock private com.influora.service.credits.CreatorCreditService creatorCreditService;
    @Mock private com.influora.config.CreatorCreditProperties creditProperties;
    @Mock private AuthPrincipal principal;
    @Mock private CreatorProfile creatorProfile;

    private static final String ONBEHALF_JWT = "stub-onbehalf-jwt-frame-check";

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
                        creditProperties,
                        onBehalfTokenService);
        lenient().when(featureProperties.isCreatorEnabled()).thenReturn(true);
        lenient().when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        lenient().when(creatorProfile.getUserId()).thenReturn(CREATOR_USER_ID);
        // F-audit-A1: the frame-check route mints a real on-behalf JWT for EVERY forwarded call --
        // stubbed here so the many consented-path tests below don't each need to repeat it.
        lenient()
                .when(
                        onBehalfTokenService.mint(
                                eq(CREATOR_USER_ID), isNull(), isNull(), eq(CREATOR_USER_ID), eq(UserType.CREATOR), eq("")))
                .thenReturn(ONBEHALF_JWT);
    }

    private static MockMultipartFile jpeg(byte[] bytes) {
        return new MockMultipartFile("image", "frame.jpg", "image/jpeg", bytes);
    }

    @Test
    @DisplayName("a consented creator's frame reaches influora-ai, scoped to the creator's OWN user id")
    void consentedCreator_reachesClient_andPassesTheBodyThrough() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        byte[] body = "{\"fixes\":[\"Step right\"],\"settings\":[],\"ok\":[]}".getBytes();
        when(voiceAiClient.checkFrameForCreator(
                        eq(CREATOR_USER_ID), any(), eq("image/jpeg"), eq("static overhead"), isNull(), isNull(), isNull(), eq(ONBEHALF_JWT)))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, body, "application/json", 200));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), "  static overhead  ", null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(body, response.getBody());
        // Identity comes from the verified principal, never from anything in the request. The
        // on-behalf JWT forwarded is the REAL one minted for this creator (F-audit-A1) -- never
        // the service bearer, never a hand-built stand-in.
        verify(voiceAiClient)
                .checkFrameForCreator(
                        eq(CREATOR_USER_ID), eq(JPEG), eq("image/jpeg"), eq("static overhead"), isNull(), isNull(), isNull(), eq(ONBEHALF_JWT));
    }

    @Test
    @DisplayName("an unconsented creator is refused with 403 before any byte leaves this server")
    void unconsentedCreator_refusedBeforeTheClient() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.checkFrame(principal, jpeg(JPEG), null, null, null));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("a BRAND principal is refused with 403, and no creator identity is resolved")
    void brandPrincipal_refused() {
        when(creatorContext.requireCreatorProfile(principal))
                .thenThrow(new ApiException("WRONG_USER_TYPE", "creator accounts only", HttpStatus.FORBIDDEN));

        ApiException ex = assertThrows(ApiException.class, () -> controller.checkFrame(principal, jpeg(JPEG), null, null, null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("feature flag off: 404 before identity is even resolved")
    void flagOff_notFound() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.checkFrame(principal, jpeg(JPEG), null, null, null));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        verify(creatorContext, never()).requireCreatorProfile(principal);
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("no image, or an empty one: 400 FRAME_MISSING, no forward")
    void missingImage_badRequest() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);

        ResponseEntity<?> none = controller.checkFrame(principal, null, null, null, null);
        ResponseEntity<?> empty = controller.checkFrame(principal, jpeg(new byte[0]), null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, none.getStatusCode());
        assertEquals(Map.of("code", "FRAME_MISSING"), none.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("an image over the limit: 413, and the bytes never leave this server")
    void oversizeImage_payloadTooLarge() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        byte[] big = new byte[(int) CreatorMeeraController.MAX_FRAME_BYTES + 1];

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(big), null, null, null);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("an image exactly at the limit is forwarded")
    void imageAtTheLimit_forwarded() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        byte[] atLimit = new byte[(int) CreatorMeeraController.MAX_FRAME_BYTES];
        when(voiceAiClient.checkFrameForCreator(eq(CREATOR_USER_ID), any(), any(), isNull(), isNull(), isNull(), isNull(), eq(ONBEHALF_JWT)))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(atLimit), null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("influora-ai fails: 502 FRAME_CHECK_UNAVAILABLE, never a fake empty result the app would show as ok")
    void upstreamFailure_badGateway() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(false, null, null, 500));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), null, null, null);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(Map.of("code", "FRAME_CHECK_UNAVAILABLE"), response.getBody());
    }

    @Test
    @DisplayName("a runaway shot label is cut to a sane length before it is forwarded")
    void longShotLabel_truncated() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        controller.checkFrame(principal, jpeg(JPEG), "x".repeat(5_000), null, null);

        ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
        verify(voiceAiClient).checkFrameForCreator(any(), any(), any(), label.capture(), any(), isNull(), isNull(), any());
        assertEquals(120, label.getValue().length());
    }

    @Test
    @DisplayName(
            "F-audit-A1: a real, creator-scoped on-behalf JWT is minted for every forwarded frame check")
    void consentedCreator_mintsARealOnBehalfJwtScopedToTheCreator() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        controller.checkFrame(principal, jpeg(JPEG), null, null, null);

        // workspaceId == userId == the creator's OWN id (never a brand workspace id), userType ==
        // CREATOR, and an EMPTY scope (this token authenticates exactly one read, nothing else --
        // see CreatorMeeraController#mintCreatorOnBehalfJwt's javadoc).
        verify(onBehalfTokenService)
                .mint(eq(CREATOR_USER_ID), isNull(), isNull(), eq(CREATOR_USER_ID), eq(UserType.CREATOR), eq(""));
        // And the SAME minted value is what actually gets forwarded to influora-ai -- not
        // recomputed, not a different token, not silently dropped.
        verify(voiceAiClient).checkFrameForCreator(any(), any(), any(), any(), any(), isNull(), isNull(), eq(ONBEHALF_JWT));
    }

    // ---------------------------------------------------------------------------------------
    // V76 -- the creator's saved phone model rides along so the camera settings fit her phone.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("V76: the creator's saved phone model is looked up by her OWN user id and forwarded")
    void savedPhoneModel_isForwardedToTheClient() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(preferencesService.findPhoneModelForUser(CREATOR_USER_ID))
                .thenReturn(java.util.Optional.of("Redmi Note 13"));
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(voiceAiClient)
                .checkFrameForCreator(
                        eq(CREATOR_USER_ID), eq(JPEG), eq("image/jpeg"), isNull(), eq("Redmi Note 13"), isNull(), isNull(), eq(ONBEHALF_JWT));
    }

    @Test
    @DisplayName("V76: no phone on file forwards null (the client then omits the phone_model part)")
    void noSavedPhoneModel_forwardsNull() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(preferencesService.findPhoneModelForUser(CREATOR_USER_ID)).thenReturn(java.util.Optional.empty());
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        controller.checkFrame(principal, jpeg(JPEG), null, null, null);

        verify(voiceAiClient).checkFrameForCreator(any(), any(), any(), any(), isNull(), isNull(), isNull(), eq(ONBEHALF_JWT));
    }

    @Test
    @DisplayName("V76: a failed phone lookup never fails the frame check -- it is treated as no phone")
    void phoneLookupFailure_doesNotFailTheFrameCheck() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(preferencesService.findPhoneModelForUser(CREATOR_USER_ID)).thenThrow(new RuntimeException("db down"));
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(voiceAiClient).checkFrameForCreator(any(), any(), any(), any(), isNull(), isNull(), isNull(), eq(ONBEHALF_JWT));
    }

    // ---------------------------------------------------------------------------------------
    // Coaching photo check -- the optional shot_context and answers text fields.
    // ---------------------------------------------------------------------------------------

    private static final String SHOT_CONTEXT =
            "{\"angle\":\"eye level\",\"where\":\"bedroom\",\"line\":\"Aaj ka look\"}";
    private static final String ANSWERS = "[{\"id\":\"other_light\",\"option\":1}]";

    @Test
    @DisplayName("coaching: shot_context and answers are forwarded (stripped) to the client when given")
    void shotContextAndAnswers_forwarded() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        ResponseEntity<?> response =
                controller.checkFrame(principal, jpeg(JPEG), null, "  " + SHOT_CONTEXT + "\n", " " + ANSWERS + " ");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(voiceAiClient)
                .checkFrameForCreator(
                        eq(CREATOR_USER_ID),
                        eq(JPEG),
                        eq("image/jpeg"),
                        isNull(),
                        any(),
                        eq(SHOT_CONTEXT),
                        eq(ANSWERS),
                        eq(ONBEHALF_JWT));
    }

    @Test
    @DisplayName("coaching: blank shot_context and answers are forwarded as null (the parts are then omitted)")
    void blankShotContextAndAnswers_forwardedAsNull() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        controller.checkFrame(principal, jpeg(JPEG), null, "   ", "");

        verify(voiceAiClient)
                .checkFrameForCreator(any(), any(), any(), any(), any(), isNull(), isNull(), eq(ONBEHALF_JWT));
    }

    @Test
    @DisplayName("coaching: a 1001-char shot_context is refused with 400 FIELD_TOO_LONG, nothing forwarded")
    void shotContextOverTheCap_badRequest() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), null, "x".repeat(1001), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("code", "FIELD_TOO_LONG"), response.getBody());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("coaching: a 601-char answers is refused with 400 FIELD_TOO_LONG, nothing forwarded")
    void answersOverTheCap_badRequest() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), null, null, "x".repeat(601));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Map.of("code", "FIELD_TOO_LONG"), response.getBody());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("coaching: exactly 1000 / 600 chars is at the cap and forwarded")
    void fieldsExactlyAtTheCap_forwarded() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        ResponseEntity<?> response =
                controller.checkFrame(principal, jpeg(JPEG), null, "x".repeat(1000), "y".repeat(600));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(voiceAiClient)
                .checkFrameForCreator(
                        any(), any(), any(), any(), any(), eq("x".repeat(1000)), eq("y".repeat(600)), eq(ONBEHALF_JWT));
    }

    @Test
    @DisplayName("coaching: the cap counts characters, not UTF-8 bytes -- 1000 Devanagari letters pass")
    void capCountsCharactersNotBytes() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrameForCreator(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        ResponseEntity<?> response =
                controller.checkFrame(principal, jpeg(JPEG), null, "\u0915".repeat(1000), null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
