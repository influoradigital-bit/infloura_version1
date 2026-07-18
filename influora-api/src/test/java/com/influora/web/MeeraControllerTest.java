package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.MeeraStreamProperties;
import com.influora.domain.entity.Workspace;
import com.influora.integration.ai.MeeraVoiceAiClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.meera.AICreditService;
import com.influora.service.meera.MeeraSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link MeeraController#speak} (voice proxy endpoint). Mocks {@link
 * BrandContextService} and {@link MeeraVoiceAiClient} rather than standing up MockMvc/Spring
 * Security, same "mock the collaborators, assert delegation" convention as {@code
 * BrandDeliverableControllerTest}. The 401/403 case is exercised the same way {@code
 * BrandContextService.requireBrand} enforces it everywhere else on this controller: a
 * missing/wrong-type principal makes {@code requireBrandWorkspace} throw {@link ApiException}
 * with {@link HttpStatus#FORBIDDEN}, which a global exception handler (untouched by this test)
 * turns into the HTTP 403 response.
 */
@ExtendWith(MockitoExtension.class)
class MeeraControllerTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456789";

    @Mock private MeeraSessionService sessionService;
    @Mock private AICreditService creditService;
    @Mock private BrandContextService brandContextService;
    @Mock private MeeraStreamProperties streamProperties;
    @Mock private MeeraVoiceAiClient voiceAiClient;
    @Mock private AuthPrincipal principal;

    private MeeraController controller;

    @BeforeEach
    void setUp() {
        controller =
                new MeeraController(
                        sessionService, creditService, brandContextService, streamProperties, voiceAiClient);
    }

    @Test
    @DisplayName("POST /meera/voice/speak: success passthrough returns 200 audio/wav bytes")
    void testSpeakSuccessPassthrough() {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "1-10");
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);

        byte[] audioBytes = {1, 2, 3, 4};
        when(voiceAiClient.speak(eq(WORKSPACE_ID), eq("hello there")))
                .thenReturn(MeeraVoiceAiClient.SpeakResult.audio(audioBytes, "audio/wav"));

        ResponseEntity<?> response =
                controller.speak(principal, new MeeraController.VoiceSpeakRequest("hello there"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("audio/wav", response.getHeaders().getContentType().toString());
        assertArrayEquals(audioBytes, (byte[]) response.getBody());
    }

    @Test
    @DisplayName("POST /meera/voice/speak: provider fallback returns 200 {\"fallback\": true}, never a 5xx")
    void testSpeakFallbackReturnsFallbackJson() {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "1-10");
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(voiceAiClient.speak(eq(WORKSPACE_ID), eq("hello there")))
                .thenReturn(MeeraVoiceAiClient.SpeakResult.fallback());

        ResponseEntity<?> response =
                controller.speak(principal, new MeeraController.VoiceSpeakRequest("hello there"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(java.util.Map.of("fallback", true), response.getBody());
    }

    @Test
    @DisplayName("POST /meera/voice/speak: unauthenticated/non-brand principal is rejected with 403 before any AI call")
    void testSpeakUnauthenticatedRejectedWithForbidden() {
        when(brandContextService.requireBrandWorkspace(null))
                .thenThrow(new ApiException("WRONG_USER_TYPE", "This endpoint is for brand accounts only", HttpStatus.FORBIDDEN));

        ApiException exception =
                assertThrows(
                        ApiException.class,
                        () -> controller.speak(null, new MeeraController.VoiceSpeakRequest("hello there")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        org.mockito.Mockito.verifyNoInteractions(voiceAiClient);
    }
}
