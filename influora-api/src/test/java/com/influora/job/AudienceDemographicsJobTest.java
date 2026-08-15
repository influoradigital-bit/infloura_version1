package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AudienceDemographics;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.AudienceDemographicsResponse;
import com.influora.integration.meta.dto.AudienceDemographicsResponse.DemographicBreakdown;
import com.influora.integration.meta.dto.AudienceDemographicsResponse.DemographicValue;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wave B task B4: unit tests for {@link AudienceDemographicsJob}, mirroring {@code
 * MetricsPollingJobTest}/{@code MetaTokenRefreshServiceTest}'s conventions (mocked collaborators,
 * no real DB/HTTP, per-creator error isolation, rate-limit + overlap-guard coverage).
 */
@ExtendWith(MockitoExtension.class)
class AudienceDemographicsJobTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE1234567";
    private static final String CREATOR_ID = "01HWXYZCREATOR000000001";
    private static final String TOKEN = "valid-access-token";
    // CR-99/F-0113 regression coverage: Meta's Graph API takes this numeric IG Business Account
    // ID in the request path, never the internal ULID creatorProfileId above.
    private static final String IG_BUSINESS_ACCOUNT_ID = "17841400000000021";

    @Mock private MetaOAuthTokenRepository tokenRepository;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private InstagramInsightsClient instagramClient;
    @Mock private AudienceDemographicsRepository demographicsRepository;
    @Mock private MetaRateLimitTracker rateLimitTracker;
    @Mock private AuditLogService auditLog;

    private AudienceDemographicsJob job;

    @BeforeEach
    void setUp() {
        job =
                new AudienceDemographicsJob(
                        tokenRepository,
                        tokenStorage,
                        instagramClient,
                        demographicsRepository,
                        rateLimitTracker,
                        auditLog);
    }

    @Test
    @DisplayName("success: valid response is mapped into breakdown JSON columns and persisted")
    void testSuccessfulPollPersistsSnapshotWithAllBreakdowns() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);

        AudienceDemographicsResponse response =
                new AudienceDemographicsResponse(
                        List.of(
                                new DemographicBreakdown(
                                        "audience_gender_age",
                                        "lifetime",
                                        List.of(new DemographicValue(Map.of("F.25-34", 400L), null))),
                                new DemographicBreakdown(
                                        "audience_country",
                                        "lifetime",
                                        List.of(new DemographicValue(Map.of("US", 1200L), null))),
                                new DemographicBreakdown(
                                        "audience_city",
                                        "lifetime",
                                        List.of(new DemographicValue(Map.of("New York, NY", 210L), null))),
                                new DemographicBreakdown(
                                        "audience_locale",
                                        "lifetime",
                                        List.of(new DemographicValue(Map.of("en_US", 900L), null)))));
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN)).thenReturn(response);

        job.pollDemographics();

        ArgumentCaptor<AudienceDemographics> captor = ArgumentCaptor.forClass(AudienceDemographics.class);
        verify(demographicsRepository).save(captor.capture());

        AudienceDemographics saved = captor.getValue();
        assertEquals(CREATOR_ID, saved.getCreatorProfileId());
        assertEquals("INSTAGRAM", saved.getPlatform());
        assertTrue(saved.getAgeGenderBreakdownJson().contains("F.25-34"));
        assertTrue(saved.getCountryBreakdownJson().contains("US"));
        assertTrue(saved.getCityBreakdownJson().contains("New York"));
        assertTrue(saved.getLocaleBreakdownJson().contains("en_US"));

        verify(auditLog)
                .recordToolCall(
                        eq(null),
                        eq("AUDIENCE_DEMOGRAPHICS_POLL_COMPLETED"),
                        eq("SYSTEM"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        any());
    }

    @Test
    @DisplayName("no valid token: creator is skipped, no fetch attempted")
    void testNoValidTokenSkipsCreator() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.empty());

        job.pollDemographics();

        verify(instagramClient, never()).getAudienceDemographics(anyString(), anyString());
        verify(demographicsRepository, never()).save(any());
    }

    @Test
    @DisplayName("empty response (e.g. sub-100-follower account): skipped, not persisted as a fabricated empty row")
    void testEmptyResponseIsSkippedNotFabricated() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(0);
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN))
                .thenReturn(new AudienceDemographicsResponse(Collections.emptyList()));

        job.pollDemographics();

        verify(demographicsRepository, never()).save(any());
    }

    @Test
    @DisplayName("null response: skipped gracefully, not an unexpected failure")
    void testNullResponseIsSkipped() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(0);
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN)).thenReturn(null);

        job.pollDemographics();

        verify(demographicsRepository, never()).save(any());
    }

    @Test
    @DisplayName("rate limit pre-flight: creator at/above 90% usage is deferred, no API call made")
    void testRateLimitedCreatorIsDeferred() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(95);

        job.pollDemographics();

        verify(instagramClient, never()).getAudienceDemographics(anyString(), anyString());
        verify(demographicsRepository, never()).save(any());
    }

    @Test
    @DisplayName("MetaRateLimitException from the API call itself marks the tracker and skips, doesn't throw")
    void testMetaRateLimitExceptionDuringFetchIsHandled() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN))
                .thenThrow(new MetaRateLimitException("rate limited"));

        job.pollDemographics();

        // F-0126: must use the same key as getCurrentUsage (igBusinessAccountId), not the ULID.
        verify(rateLimitTracker).markLimited(IG_BUSINESS_ACCOUNT_ID);
        verify(demographicsRepository, never()).save(any());
    }

    @Test
    @DisplayName("MetaApiException from the API call is logged and skipped, doesn't abort the run")
    void testMetaApiExceptionDuringFetchIsHandled() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN))
                .thenThrow(new MetaApiException("boom"));

        job.pollDemographics();

        verify(demographicsRepository, never()).save(any());
        verify(auditLog)
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("per-creator failure isolation: one creator's unexpected exception doesn't abort the batch")
    void testPerCreatorFailureIsolationDoesNotAbortBatch() {
        String creator1 = "01HWXYZCREATOR000000002";
        String creator2 = "01HWXYZCREATOR000000003";
        MetaOAuthToken token1 = createTestToken(WORKSPACE_ID, creator1);
        MetaOAuthToken token2 = createTestToken(WORKSPACE_ID, creator2);

        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token1, token2));

        when(tokenStorage.getValidCreatorToken(creator1))
                .thenThrow(new RuntimeException("unexpected decryption blow-up"));

        when(tokenStorage.getValidCreatorToken(creator2)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN))
                .thenReturn(
                        new AudienceDemographicsResponse(
                                List.of(
                                        new DemographicBreakdown(
                                                "audience_country",
                                                "lifetime",
                                                List.of(new DemographicValue(Map.of("US", 500L), null))))));

        job.pollDemographics();

        ArgumentCaptor<AudienceDemographics> captor = ArgumentCaptor.forClass(AudienceDemographics.class);
        verify(demographicsRepository, times(1)).save(captor.capture());
        assertEquals(creator2, captor.getValue().getCreatorProfileId());
    }

    @Test
    @DisplayName(
            "CR-99/F-0113 regression — passes igBusinessAccountId, never the internal creatorProfileId"
                    + " ULID, to Meta's Graph API")
    void testUsesIgBusinessAccountIdNotUlid() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, IG_BUSINESS_ACCOUNT_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN))
                .thenReturn(
                        new AudienceDemographicsResponse(
                                List.of(
                                        new DemographicBreakdown(
                                                "audience_country",
                                                "lifetime",
                                                List.of(new DemographicValue(Map.of("US", 500L), null))))));

        job.pollDemographics();

        verify(instagramClient, never()).getAudienceDemographics(eq(CREATOR_ID), anyString());
        verify(instagramClient).getAudienceDemographics(eq(IG_BUSINESS_ACCOUNT_ID), eq(TOKEN));
    }

    @Test
    @DisplayName(
            "F-0126 regression — pre-flight rate-limit check reads usage under igBusinessAccountId,"
                    + " the same key MetaGraphApiClient itself uses, never the internal creatorProfileId"
                    + " ULID")
    void testRateLimitTrackerKeyedOnIgBusinessAccountIdNotUlid() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, IG_BUSINESS_ACCOUNT_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        // Stub only the CORRECT key at the tripping threshold; the ULID key is left unstubbed
        // (defaults to 0). If production regressed to keying on creatorProfileId, this stub would
        // never be consulted and the creator would wrongly be polled instead of deferred.
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(95);

        job.pollDemographics();

        verify(rateLimitTracker, never()).getCurrentUsage(CREATOR_ID);
        verify(instagramClient, never()).getAudienceDemographics(anyString(), anyString());
        verify(demographicsRepository, never()).save(any());
    }

    @Test
    @DisplayName("a token row with no igBusinessAccountId on file is skipped, no API call attempted")
    void testSkipsWhenIgBusinessAccountIdMissing() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, null);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        // Priya review finding C: a valid token must be stubbed so the igBusinessAccountId guard,
        // not the unrelated no-valid-token branch, is what actually stops this call.
        lenient().when(tokenStorage.getValidCreatorToken(CREATOR_ID)).thenReturn(Optional.of(TOKEN));
        lenient().when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);

        job.pollDemographics();

        verify(instagramClient, never()).getAudienceDemographics(anyString(), anyString());
        verify(demographicsRepository, never()).save(any());
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("no tokens at all: no-op, audit still records zero counts")
    void testNoTokensIsNoOp() {
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        job.pollDemographics();

        verify(instagramClient, never()).getAudienceDemographics(anyString(), anyString());
        verify(demographicsRepository, never()).save(any());
        verify(auditLog)
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("overlap guard blocks concurrent runs via AtomicBoolean")
    void testOverlapGuardPreventsConcurrentRuns() throws InterruptedException {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return Optional.of(TOKEN);
                        });
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(10);
        when(instagramClient.getAudienceDemographics(IG_BUSINESS_ACCOUNT_ID, TOKEN))
                .thenReturn(
                        new AudienceDemographicsResponse(
                                List.of(
                                        new DemographicBreakdown(
                                                "audience_country",
                                                "lifetime",
                                                List.of(new DemographicValue(Map.of("US", 500L), null))))));

        Thread thread1 = new Thread(() -> job.pollDemographics());
        thread1.start();
        Thread.sleep(10);

        job.pollDemographics();

        thread1.join();

        verify(tokenStorage, times(1)).getValidCreatorToken(CREATOR_ID);
        verify(auditLog, times(1))
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private MetaOAuthToken createTestToken(String workspaceId, String creatorProfileId) {
        return createTestToken(workspaceId, creatorProfileId, IG_BUSINESS_ACCOUNT_ID);
    }

    private MetaOAuthToken createTestToken(
            String workspaceId, String creatorProfileId, String igBusinessAccountId) {
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
            public String getIgBusinessAccountId() {
                return igBusinessAccountId;
            }

            @Override
            public Instant getExpiresAt() {
                return Instant.now().plusSeconds(3600);
            }

            @Override
            public boolean isRevoked() {
                return false;
            }
        };
    }
}
