package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.InviteTokenService;
import com.influora.web.dto.admin.AdminCreatorConnectionDtos.ImportResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

/**
 * T-CREATORCONNECT-0902 — first backend test coverage for {@code AdminCreatorConnectionService}
 * (previously zero, per Q4.1/Q4.5). Scoped to this fix wave's own findings: Q4.4 (handle
 * validation + guarded delete), Q4.2 (real workspace name in the invite email), Q4.3 (rate-limit
 * handling stops the batch instead of silently mis-reporting it). Plain Mockito, matches every
 * other {@code Admin*ServiceTest} in this package.
 */
@ExtendWith(MockitoExtension.class)
class AdminCreatorConnectionServiceTest {

    private static final String ADMIN_ID = "01HADMIN00000000000001";
    private static final String WORKSPACE_ID = "01HWORKSPACE0000000001";
    private static final String EXTERNAL_ID = "01HEXTCREATOR000000001";
    private static final String REQUEST_ID = "01HREQUEST0000000001";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private CreatorConnectionRequestRepository connectionRequestRepository;
    @Mock private ExternalCreatorRepository externalCreatorRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private UserRepository userRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private MetaTokenStorage metaTokenStorage;
    @Mock private InstagramInsightsClient instagramInsightsClient;
    @Mock private Msg91EmailClient msg91EmailClient;
    @Mock private InviteTokenService inviteTokenService;
    @Mock private AuthPrincipal principal;
    @Mock private HttpServletRequest httpRequest;

    private AdminCreatorConnectionService service;

    @BeforeEach
    void setUp() {
        service =
                new AdminCreatorConnectionService(
                        adminContext,
                        adminAuditLogService,
                        connectionRequestRepository,
                        externalCreatorRepository,
                        workspaceRepository,
                        userRepository,
                        metaOAuthTokenRepository,
                        metaTokenStorage,
                        instagramInsightsClient,
                        msg91EmailClient,
                        inviteTokenService,
                        "https://app.example.com");
    }

    private ExternalCreator externalCreator(String username) {
        return ExternalCreator.builder()
                .id(EXTERNAL_ID)
                .source(ExternalCreatorSource.ADMIN_IMPORT)
                .igUsername(username)
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // Q4.4 — import handle validation
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Q4.4: garbage handles ('foo bar', 'foo)') are skipped, never persisted")
    void importHandles_rejectsGarbageHandles() {
        ImportResult result = service.importHandles(principal, httpRequest, List.of("foo bar", "foo)"));

        assertEquals(0, result.imported());
        assertEquals(0, result.enriched());
        assertEquals(2, result.skipped().size());
        assertTrue(result.skipped().containsAll(List.of("foo bar", "foo)")));
        verify(externalCreatorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Q4.4: an 81-character handle is skipped, not persisted and not flush-failed")
    void importHandles_rejectsOverLengthHandle() {
        String tooLong = "a".repeat(81);

        ImportResult result = service.importHandles(principal, httpRequest, List.of(tooLong));

        assertEquals(0, result.imported());
        assertEquals(1, result.skipped().size());
        verify(externalCreatorRepository, never()).save(any());
    }

    @Test
    @DisplayName("Q4.4: a valid handle is imported normally when no Business Discovery caller is available")
    void importHandles_validHandle_isImported() {
        when(externalCreatorRepository.findByIgUsernameIgnoreCase("valid.handle_1")).thenReturn(Optional.empty());
        when(metaOAuthTokenRepository.findByRevokedFalseAndExpiresAtAfter(any())).thenReturn(List.of());

        ImportResult result = service.importHandles(principal, httpRequest, List.of("@Valid.Handle_1"));

        assertEquals(1, result.imported());
        assertEquals(0, result.enriched());
        assertTrue(result.skipped().isEmpty());
        verify(externalCreatorRepository).save(any(ExternalCreator.class));
    }

    // ------------------------------------------------------------------------------------------
    // Q4.3 — rate limit stops enrichment for the rest of the batch
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Q4.3: a MetaRateLimitException on one handle stops further Graph calls for the rest of"
                    + " the batch; every handle is still saved as an un-enriched stub, but rate-limited"
                    + " ones are reported as skipped (needs retry), NOT counted as ordinary imports")
    void importHandles_rateLimit_stopsEnrichmentButStillSavesStubs() throws Exception {
        MetaOAuthToken token = mock(MetaOAuthToken.class);
        when(token.getAuthPath()).thenReturn(MetaAuthPath.FACEBOOK_LOGIN);
        when(token.getIgBusinessAccountId()).thenReturn("17841400000000099");
        when(token.getWorkspaceId()).thenReturn(null);
        when(token.getCreatorProfileId()).thenReturn("01HCREATORPROFILE00099");
        when(metaOAuthTokenRepository.findByRevokedFalseAndExpiresAtAfter(any())).thenReturn(List.of(token));
        when(metaTokenStorage.getValidCreatorToken("01HCREATORPROFILE00099")).thenReturn(Optional.of("access-token"));

        when(externalCreatorRepository.findByIgUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(instagramInsightsClient.businessDiscovery(anyString(), eq("firsthandle"), anyString()))
                .thenThrow(new MetaRateLimitException("rate limited"));

        ImportResult result = service.importHandles(principal, httpRequest, List.of("firsthandle", "secondhandle"));

        // Only ONE Graph call was attempted — the second handle never reaches Meta at all.
        verify(instagramInsightsClient, times(1)).businessDiscovery(anyString(), anyString(), anyString());
        assertEquals(0, result.enriched());
        // Q4.3 fix: both stubs ARE still persisted (not lost)...
        verify(externalCreatorRepository, times(2)).save(any(ExternalCreator.class));
        // ...but neither is counted as an ordinary successful `imported` — that would make this
        // rate-limited batch indistinguishable from a fully successful one. They land in `skipped`
        // instead, which the admin-console summary already surfaces as a count, with a distinguishing
        // reason string identifying them as rate-limited (not garbage/invalid).
        assertEquals(0, result.imported(), "rate-limited stubs must not be reported as ordinary imports");
        assertEquals(2, result.skipped().size());
        assertTrue(
                result.skipped().stream().allMatch(s -> s.contains("rate-limited")),
                "skipped entries for rate-limited stubs must say why, distinct from a garbage/invalid handle");
    }

    // ------------------------------------------------------------------------------------------
    // Q4.2 — invite email uses the real workspace name
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Q4.2: invite() resolves the real workspace name into the join-invitation email")
    void invite_usesRealWorkspaceName() {
        CreatorConnectionRequest request =
                CreatorConnectionRequest.builder()
                        .id(REQUEST_ID)
                        .workspaceId(WORKSPACE_ID)
                        .requestedByUserId("01HUSERBRAND00000001")
                        .externalCreatorId(EXTERNAL_ID)
                        .build();
        when(connectionRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        ExternalCreator external = externalCreator("foodie.mumbai");
        when(externalCreatorRepository.findById(EXTERNAL_ID)).thenReturn(Optional.of(external));
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Snacks Co", "acme-snacks", "FMCG", "11-50");
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(inviteTokenService.issue(eq(EXTERNAL_ID), any())).thenReturn("signed-token");
        when(msg91EmailClient.sendTemplateEmail(anyString(), anyString(), anyString())).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(workspaceRepository.findAllById(any())).thenReturn(List.of(workspace));
        when(externalCreatorRepository.findAllById(any())).thenReturn(List.of(external));

        service.invite(principal, httpRequest, REQUEST_ID, "creator@example.com", "please join");

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(msg91EmailClient)
                .sendTemplateEmail(eq("creator@example.com"), eq("creator.join_invitation"), jsonCaptor.capture());
        assertTrue(
                jsonCaptor.getValue().contains("Acme Snacks Co"),
                "expected the real workspace name in the templated email data, got: " + jsonCaptor.getValue());
        assertFalse(jsonCaptor.getValue().contains("A brand"));
    }

    @Test
    @DisplayName("Q4.2: invite() falls back to 'A brand' only when the workspace row is gone")
    void invite_fallsBackToGenericBrandName_whenWorkspaceMissing() {
        CreatorConnectionRequest request =
                CreatorConnectionRequest.builder()
                        .id(REQUEST_ID)
                        .workspaceId(WORKSPACE_ID)
                        .requestedByUserId("01HUSERBRAND00000001")
                        .externalCreatorId(EXTERNAL_ID)
                        .build();
        when(connectionRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        ExternalCreator external = externalCreator("foodie.mumbai");
        when(externalCreatorRepository.findById(EXTERNAL_ID)).thenReturn(Optional.of(external));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(inviteTokenService.issue(eq(EXTERNAL_ID), any())).thenReturn("signed-token");
        when(msg91EmailClient.sendTemplateEmail(anyString(), anyString(), anyString())).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(workspaceRepository.findAllById(any())).thenReturn(List.of());
        when(externalCreatorRepository.findAllById(any())).thenReturn(List.of(external));

        service.invite(principal, httpRequest, REQUEST_ID, "creator@example.com", null);

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(msg91EmailClient)
                .sendTemplateEmail(eq("creator@example.com"), eq("creator.join_invitation"), jsonCaptor.capture());
        assertTrue(jsonCaptor.getValue().contains("A brand"));
    }

    // ------------------------------------------------------------------------------------------
    // Q4.4 — guarded delete
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Q4.4: deleteExternalCreator removes an unreferenced row")
    void deleteExternalCreator_unreferenced_deletes() {
        ExternalCreator external = externalCreator("garbage_stub");
        when(externalCreatorRepository.findById(EXTERNAL_ID)).thenReturn(Optional.of(external));
        when(connectionRequestRepository.exists(org.mockito.ArgumentMatchers.<Specification<CreatorConnectionRequest>>any()))
                .thenReturn(false);

        service.deleteExternalCreator(principal, httpRequest, EXTERNAL_ID);

        verify(externalCreatorRepository).deleteById(EXTERNAL_ID);
    }

    @Test
    @DisplayName("Q4.4: deleteExternalCreator refuses a row still referenced by a connection request")
    void deleteExternalCreator_referenced_refuses() {
        ExternalCreator external = externalCreator("real_creator");
        when(externalCreatorRepository.findById(EXTERNAL_ID)).thenReturn(Optional.of(external));
        when(connectionRequestRepository.exists(org.mockito.ArgumentMatchers.<Specification<CreatorConnectionRequest>>any()))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.deleteExternalCreator(principal, httpRequest, EXTERNAL_ID));

        assertEquals("EXTERNAL_CREATOR_REFERENCED", ex.getCode());
        verify(externalCreatorRepository, never()).deleteById(anyString());
    }
}
