package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.dto.MetaTokenResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaOAuthService;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.service.AuditLogService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
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
 * Wave B task B2: unit tests for MetaTokenRefreshService, mirroring MetricsPollingJobTest's
 * conventions (mocked collaborators, no real DB/HTTP, per-item error isolation coverage).
 */
@ExtendWith(MockitoExtension.class)
class MetaTokenRefreshServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";
    private static final String CURRENT_TOKEN = "current-plaintext-token";
    private static final String REFRESHED_TOKEN = "refreshed-plaintext-token";

    @Mock private MetaTokenStorage tokenStorage;
    @Mock private MetaOAuthService oAuthService;
    @Mock private AuditLogService auditLog;

    private MetaApiProperties props;
    private MetaTokenRefreshService refreshService;

    @BeforeEach
    void setUp() {
        props = new MetaApiProperties();
        refreshService = new MetaTokenRefreshService(tokenStorage, oAuthService, props, auditLog);
    }

    @Test
    @DisplayName("refresh success: expiring token is refreshed and re-stored with new expiry, scopes preserved")
    void testRefreshSuccessRotatesTokenAndPreservesScopes() {
        MetaOAuthToken token =
                createTestToken(WORKSPACE_ID, CREATOR_ID, "[\"instagram_basic\",\"pages_show_list\"]");
        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidToken(WORKSPACE_ID, CREATOR_ID)).thenReturn(Optional.of(CURRENT_TOKEN));
        when(oAuthService.refreshLongLivedToken(CURRENT_TOKEN))
                .thenReturn(new MetaTokenResponse(REFRESHED_TOKEN, "bearer", 5184000L));

        refreshService.refreshExpiringTokens();

        ArgumentCaptor<List<String>> scopesCaptor = ArgumentCaptor.forClass(List.class);
        verify(tokenStorage)
                .storeToken(
                        eq(CREATOR_ID),
                        eq(WORKSPACE_ID),
                        eq(REFRESHED_TOKEN),
                        any(Instant.class),
                        scopesCaptor.capture());

        assertEquals(List.of("instagram_basic", "pages_show_list"), scopesCaptor.getValue());

        verify(auditLog)
                .recordToolCall(
                        eq(null),
                        eq("META_TOKEN_REFRESH_SWEEP_COMPLETED"),
                        eq("SYSTEM"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        any());
    }

    @Test
    @DisplayName("refresh success: new expiry is derived from Meta's expires_in, roughly 60 days out")
    void testRefreshSuccessComputesExpiryFromResponse() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, null);
        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidToken(WORKSPACE_ID, CREATOR_ID)).thenReturn(Optional.of(CURRENT_TOKEN));
        when(oAuthService.refreshLongLivedToken(CURRENT_TOKEN))
                .thenReturn(new MetaTokenResponse(REFRESHED_TOKEN, "bearer", 5_184_000L)); // 60 days

        refreshService.refreshExpiringTokens();

        ArgumentCaptor<Instant> expiryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(tokenStorage)
                .storeToken(anyString(), anyString(), anyString(), expiryCaptor.capture(), any());

        Instant expected = Instant.now().plusSeconds(5_184_000L);
        long diffSeconds = Math.abs(ChronoUnit.SECONDS.between(expected, expiryCaptor.getValue()));
        assertEquals(true, diffSeconds < 5); // allow small test-execution skew
    }

    @Test
    @DisplayName("refresh failure isolation: one creator's MetaApiException doesn't abort the batch")
    void testRefreshFailureIsolationDoesNotAbortBatch() {
        String creator1 = "01HWXYZCREATOR000000001";
        String creator2 = "01HWXYZCREATOR000000002";
        MetaOAuthToken token1 = createTestToken(WORKSPACE_ID, creator1, null);
        MetaOAuthToken token2 = createTestToken(WORKSPACE_ID, creator2, null);

        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(List.of(token1, token2));

        when(tokenStorage.getValidToken(WORKSPACE_ID, creator1)).thenReturn(Optional.of(CURRENT_TOKEN));
        when(oAuthService.refreshLongLivedToken(CURRENT_TOKEN))
                .thenThrow(new MetaApiException("Meta refresh failed"));

        when(tokenStorage.getValidToken(WORKSPACE_ID, creator2)).thenReturn(Optional.of("other-token"));
        when(oAuthService.refreshLongLivedToken("other-token"))
                .thenReturn(new MetaTokenResponse(REFRESHED_TOKEN, "bearer", 5184000L));

        refreshService.refreshExpiringTokens();

        // creator1's failure must not prevent creator2 from being refreshed and stored.
        verify(tokenStorage, times(1))
                .storeToken(eq(creator2), eq(WORKSPACE_ID), eq(REFRESHED_TOKEN), any(), any());
        verify(tokenStorage, never())
                .storeToken(eq(creator1), anyString(), anyString(), any(), any());

        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("refresh failure isolation: unexpected RuntimeException from one token doesn't abort the batch")
    void testRefreshUnexpectedExceptionIsolatedPerToken() {
        String creator1 = "01HWXYZCREATOR000000003";
        String creator2 = "01HWXYZCREATOR000000004";
        MetaOAuthToken token1 = createTestToken(WORKSPACE_ID, creator1, null);
        MetaOAuthToken token2 = createTestToken(WORKSPACE_ID, creator2, null);

        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(List.of(token1, token2));

        when(tokenStorage.getValidToken(WORKSPACE_ID, creator1))
                .thenThrow(new RuntimeException("unexpected decryption blow-up"));

        when(tokenStorage.getValidToken(WORKSPACE_ID, creator2)).thenReturn(Optional.of(CURRENT_TOKEN));
        when(oAuthService.refreshLongLivedToken(CURRENT_TOKEN))
                .thenReturn(new MetaTokenResponse(REFRESHED_TOKEN, "bearer", 5184000L));

        refreshService.refreshExpiringTokens();

        verify(tokenStorage, times(1))
                .storeToken(eq(creator2), eq(WORKSPACE_ID), eq(REFRESHED_TOKEN), any(), any());
    }

    @Test
    @DisplayName("nothing expiring: no-op, no refresh calls, audit still records zero counts")
    void testNothingExpiringSoonIsNoOp() {
        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(Collections.emptyList());

        refreshService.refreshExpiringTokens();

        verify(oAuthService, never()).refreshLongLivedToken(anyString());
        verify(tokenStorage, never()).storeToken(anyString(), anyString(), anyString(), any(), any());
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("token already gone by the time the sweep reaches it (expired/revoked mid-run) is skipped, not thrown")
    void testTokenGoneByRefreshTimeIsSkipped() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, null);
        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidToken(WORKSPACE_ID, CREATOR_ID)).thenReturn(Optional.empty());

        refreshService.refreshExpiringTokens();

        verify(oAuthService, never()).refreshLongLivedToken(anyString());
        verify(tokenStorage, never()).storeToken(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Meta returns an empty/blank access token: treated as a failure, not stored")
    void testEmptyRefreshedTokenIsNotStored() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, null);
        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidToken(WORKSPACE_ID, CREATOR_ID)).thenReturn(Optional.of(CURRENT_TOKEN));
        when(oAuthService.refreshLongLivedToken(CURRENT_TOKEN))
                .thenReturn(new MetaTokenResponse("", "bearer", 5184000L));

        refreshService.refreshExpiringTokens();

        verify(tokenStorage, never()).storeToken(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("overlap guard blocks concurrent runs via AtomicBoolean")
    void testOverlapGuardPreventsConcurrentRuns() throws InterruptedException {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, null);
        when(tokenStorage.findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry()))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidToken(WORKSPACE_ID, CREATOR_ID))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return Optional.of(CURRENT_TOKEN);
                        });
        when(oAuthService.refreshLongLivedToken(CURRENT_TOKEN))
                .thenReturn(new MetaTokenResponse(REFRESHED_TOKEN, "bearer", 5184000L));

        Thread thread1 = new Thread(() -> refreshService.refreshExpiringTokens());
        thread1.start();
        Thread.sleep(10);

        refreshService.refreshExpiringTokens();

        thread1.join();

        verify(tokenStorage, times(1)).getValidToken(WORKSPACE_ID, CREATOR_ID);
        verify(auditLog, times(1))
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private MetaOAuthToken createTestToken(
            String workspaceId, String creatorProfileId, String grantedScopesJson) {
        return new MetaOAuthToken() {
            @Override
            public String getId() {
                return "01HWXYZTOKEN12345678901";
            }

            @Override
            public String getWorkspaceId() {
                return workspaceId;
            }

            @Override
            public String getCreatorProfileId() {
                return creatorProfileId;
            }

            @Override
            public String getGrantedScopesJson() {
                return grantedScopesJson;
            }

            @Override
            public Instant getExpiresAt() {
                return Instant.now().plus(3, ChronoUnit.DAYS);
            }

            @Override
            public boolean isRevoked() {
                return false;
            }
        };
    }
}
