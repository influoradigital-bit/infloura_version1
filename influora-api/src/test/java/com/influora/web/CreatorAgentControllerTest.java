package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.web.dto.creator.CreatorAgentDtos.ConsentResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationExportResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationListResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.UpdatePreferencesRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Gate fix round 1 (Priya Q2, T-MEERA-CREATOR-PHASE-A) — {@code CreatorAgentController} had no
 * test at all. Plain unit test against mocked services, same convention as {@code
 * AnalyticsControllerTest} (no MockMvc/spring-security-test harness in this codebase). Focus: the
 * controller resolves the acting creator STRICTLY from {@code principal.getUserId()} on every
 * route (never a path/body id — a brand or a second creator has no way to name someone else's
 * data through this surface, see {@code SecurityConfigMatcherTest} for the complementary
 * role-gate-at-the-filter proof) and passes it straight through to the service unmodified.
 */
@ExtendWith(MockitoExtension.class)
class CreatorAgentControllerTest {

    private static final String USER_ID = "01HWXYZCREATOR000000001";

    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private CreatorAgentConversationService conversationService;
    @Mock private MeeraCreatorFeatureProperties featureProperties;

    private CreatorAgentController controller;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        controller = new CreatorAgentController(preferencesService, conversationService, featureProperties);
        principal = new AuthPrincipal(USER_ID, "creator@example.com", UserType.CREATOR, null);
    }

    @Test
    @DisplayName("GET resolves the acting creator from the principal, never a request param")
    void getPreferencesUsesPrincipalUserId() {
        when(featureProperties.isCreatorEnabled()).thenReturn(true);
        PreferencesResponse response =
                new PreferencesResponse(
                        new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "INR",
                        List.of(), List.of(), 0, "hi-IN", "FRIENDLY", null, null, "Asia/Kolkata", List.of(),
                        null, false, null, false, "v1");
        when(preferencesService.getOrCreatePreferences(USER_ID)).thenReturn(response);

        ResponseEntity<ApiResponse<PreferencesResponse>> result = controller.getPreferences(principal);

        assertEquals(response, result.getBody().data());
        verify(preferencesService).getOrCreatePreferences(USER_ID);
    }

    @Test
    @DisplayName("PUT passes the principal's userId and the request body straight through, unmodified")
    void updatePreferencesUsesPrincipalUserId() {
        when(featureProperties.isCreatorEnabled()).thenReturn(true);
        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        new BigDecimal("1000"), null, null, null, List.of(), List.of(), 1, "en-IN", "FORMAL",
                        null, null, null, List.of(), null, false, null);
        PreferencesResponse response =
                new PreferencesResponse(
                        new BigDecimal("1000"), null, null, "INR", List.of(), List.of(), 1, "en-IN", "FORMAL",
                        null, null, "Asia/Kolkata", List.of(), null, false, null, false, "v1");
        when(preferencesService.updatePreferences(USER_ID, req)).thenReturn(response);

        ResponseEntity<ApiResponse<PreferencesResponse>> result = controller.updatePreferences(principal, req);

        assertEquals(response, result.getBody().data());
        verify(preferencesService).updatePreferences(USER_ID, req);
    }

    @Test
    @DisplayName("POST /consent records consent for the principal's own userId")
    void recordConsentUsesPrincipalUserId() {
        when(featureProperties.isCreatorEnabled()).thenReturn(true);
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        ConsentResponse consentResponse = new ConsentResponse(now, "v1");
        when(preferencesService.recordConsent(USER_ID)).thenReturn(consentResponse);

        ResponseEntity<ApiResponse<ConsentResponse>> result = controller.recordConsent(principal);

        assertEquals(now, result.getBody().data().consentAcceptedAt());
        assertEquals("v1", result.getBody().data().consentVersion());
        verify(preferencesService).recordConsent(USER_ID);
    }

    // ---- Priya gate review defect 4: MEERA_CREATOR_ENABLED rollback flag ----

    @Test
    @DisplayName("GET returns 404 FEATURE_DISABLED and never touches the service when the flag is off")
    void getPreferences_flagOff_returns404WithoutTouchingService() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.getPreferences(principal));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(preferencesService);
    }

    @Test
    @DisplayName("PUT returns 404 FEATURE_DISABLED and never touches the service when the flag is off")
    void updatePreferences_flagOff_returns404WithoutTouchingService() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);
        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        null, null, null, null, List.of(), List.of(), 0, null, null, null, null, null, List.of(),
                        null, false, null);

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.updatePreferences(principal, req));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(preferencesService);
    }

    @Test
    @DisplayName("POST /consent returns 404 FEATURE_DISABLED and never touches the service when the flag is off")
    void recordConsent_flagOff_returns404WithoutTouchingService() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.recordConsent(principal));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(preferencesService);
    }

    @Test
    @DisplayName("DELETE /consent withdraws consent for the principal's own userId and returns 204")
    void withdrawConsentUsesPrincipalUserId() {
        ResponseEntity<Void> result = controller.withdrawConsent(principal);

        assertEquals(204, result.getStatusCode().value());
        verify(preferencesService).withdrawConsent(USER_ID);
    }

    @Test
    @DisplayName("GET /conversations lists only the principal's own conversations")
    void listConversationsUsesPrincipalUserId() {
        ConversationListResponse response = new ConversationListResponse(List.of());
        when(conversationService.listConversations(USER_ID)).thenReturn(response);

        ResponseEntity<ApiResponse<ConversationListResponse>> result = controller.listConversations(principal);

        assertEquals(response, result.getBody().data());
        verify(conversationService).listConversations(USER_ID);
    }

    @Test
    @DisplayName(
            "GET /conversations/{id}/export passes BOTH the principal's own userId and the path"
                    + " conversationId to the service, so ownership is enforced by the service (a second"
                    + " creator's conversationId cannot be read through this controller) rather than by"
                    + " the controller trusting the path alone")
    void exportConversationPassesPrincipalUserIdAndConversationId() {
        String conversationId = "conv-1";
        ConversationExportResponse response =
                new ConversationExportResponse(conversationId, Instant.now(), List.of());
        when(conversationService.exportConversation(USER_ID, conversationId)).thenReturn(response);

        ResponseEntity<ApiResponse<ConversationExportResponse>> result =
                controller.exportConversation(principal, conversationId);

        assertEquals(response, result.getBody().data());
        verify(conversationService).exportConversation(USER_ID, conversationId);
    }

    @Test
    @DisplayName("DELETE /conversations/{id} passes the principal's own userId and the path conversationId, returns 204")
    void deleteConversationPassesPrincipalUserIdAndConversationId() {
        String conversationId = "conv-1";

        ResponseEntity<Void> result = controller.deleteConversation(principal, conversationId);

        assertEquals(204, result.getStatusCode().value());
        verify(conversationService).deleteConversation(USER_ID, conversationId);
    }
}
