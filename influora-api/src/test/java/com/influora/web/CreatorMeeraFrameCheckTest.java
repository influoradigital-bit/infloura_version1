package com.influora.web;

import com.influora.config.CreatorCreditProperties;
import com.influora.service.credits.CreatorCreditService;
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
import com.influora.integration.ai.MeeraVoiceAiClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorContextService;
import com.influora.service.meera.MeeraSessionService;
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
    @Mock private AuthPrincipal principal;
    @Mock private CreatorProfile creatorProfile;

    // Added by the credits branch to the controller's constructor (merge of 2026-09-24).
    @Mock private CreatorCreditService creatorCreditService;
    @Mock private CreatorCreditProperties creditProperties;

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
        lenient().when(featureProperties.isCreatorEnabled()).thenReturn(true);
        lenient().when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorProfile);
        lenient().when(creatorProfile.getUserId()).thenReturn(CREATOR_USER_ID);
    }

    private static MockMultipartFile jpeg(byte[] bytes) {
        return new MockMultipartFile("image", "frame.jpg", "image/jpeg", bytes);
    }

    @Test
    @DisplayName("a consented creator's frame reaches influora-ai, scoped to the creator's OWN user id")
    void consentedCreator_reachesClient_andPassesTheBodyThrough() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        byte[] body = "{\"fixes\":[\"Step right\"],\"settings\":[],\"ok\":[]}".getBytes();
        when(voiceAiClient.checkFrame(eq(CREATOR_USER_ID), any(), eq("image/jpeg"), eq("static overhead")))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, body, "application/json", 200));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), "  static overhead  ");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(body, response.getBody());
        // Identity comes from the verified principal, never from anything in the request.
        verify(voiceAiClient).checkFrame(eq(CREATOR_USER_ID), eq(JPEG), eq("image/jpeg"), eq("static overhead"));
    }

    @Test
    @DisplayName("an unconsented creator is refused with 403 before any byte leaves this server")
    void unconsentedCreator_refusedBeforeTheClient() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.checkFrame(principal, jpeg(JPEG), null));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("a BRAND principal is refused with 403, and no creator identity is resolved")
    void brandPrincipal_refused() {
        when(creatorContext.requireCreatorProfile(principal))
                .thenThrow(new ApiException("WRONG_USER_TYPE", "creator accounts only", HttpStatus.FORBIDDEN));

        ApiException ex = assertThrows(ApiException.class, () -> controller.checkFrame(principal, jpeg(JPEG), null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("feature flag off: 404 before identity is even resolved")
    void flagOff_notFound() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.checkFrame(principal, jpeg(JPEG), null));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        verify(creatorContext, never()).requireCreatorProfile(principal);
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("no image, or an empty one: 400 FRAME_MISSING, no forward")
    void missingImage_badRequest() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);

        ResponseEntity<?> none = controller.checkFrame(principal, null, null);
        ResponseEntity<?> empty = controller.checkFrame(principal, jpeg(new byte[0]), null);

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

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(big), null);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        verifyNoInteractions(voiceAiClient);
    }

    @Test
    @DisplayName("an image exactly at the limit is forwarded")
    void imageAtTheLimit_forwarded() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        byte[] atLimit = new byte[(int) CreatorMeeraController.MAX_FRAME_BYTES];
        when(voiceAiClient.checkFrame(eq(CREATOR_USER_ID), any(), any(), isNull()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(atLimit), null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("influora-ai fails: 502 FRAME_CHECK_UNAVAILABLE, never a fake empty result the app would show as ok")
    void upstreamFailure_badGateway() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrame(any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(false, null, null, 500));

        ResponseEntity<?> response = controller.checkFrame(principal, jpeg(JPEG), null);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals(Map.of("code", "FRAME_CHECK_UNAVAILABLE"), response.getBody());
    }

    @Test
    @DisplayName("a runaway shot label is cut to a sane length before it is forwarded")
    void longShotLabel_truncated() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(voiceAiClient.checkFrame(any(), any(), any(), any()))
                .thenReturn(new MeeraVoiceAiClient.FrameCheckResult(true, new byte[] {'{', '}'}, "application/json", 200));

        controller.checkFrame(principal, jpeg(JPEG), "x".repeat(5_000));

        ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
        verify(voiceAiClient).checkFrame(any(), any(), any(), label.capture());
        assertEquals(120, label.getValue().length());
    }
}
