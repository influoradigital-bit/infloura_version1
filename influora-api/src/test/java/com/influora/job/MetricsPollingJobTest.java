package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import com.influora.domain.entity.MetaAuthPath;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramUserResponse;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.exception.MetaTokenExpiredException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
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
 * Phase 2 Data Pipeline: Unit tests for MetricsPollingJob (V21 metrics storage layer).
 * Coverage requirements per KAVYA_QA_TEST_PLAN.md §2.4 + SHARED_CONTEXT.md Phase 2 entry.
 */
@ExtendWith(MockitoExtension.class)
class MetricsPollingJobTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";
    private static final String TOKEN_VALUE = "test-meta-token-1234567890";
    // CR-99/F-0113 regression coverage: Meta's Graph API takes this numeric IG Business Account
    // ID in the request path, never the internal ULID creatorProfileId above.
    private static final String IG_BUSINESS_ACCOUNT_ID = "17841400000000001";

    @Mock private MetaOAuthTokenRepository tokenRepository;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private InstagramInsightsClient instagramClient;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private MetaRateLimitTracker rateLimitTracker;
    @Mock private AuditLogService auditLog;

    private MetricsPollingJob pollingJob;

    @BeforeEach
    void setUp() {
        pollingJob =
                new MetricsPollingJob(
                        tokenRepository,
                        tokenStorage,
                        instagramClient,
                        creatorMetricsRepository,
                        rateLimitTracker,
                        auditLog);
    }

    @Test
    @DisplayName("pollMetrics: normal fetch-and-store path with valid token and profile data")
    void testPollMetricsSuccess() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);

        InstagramUserResponse profile =
                new InstagramUserResponse("ig_12345", "testuser", "Test User", "Bio", 10000L, 500L, 150L, null, null);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN)).thenReturn(profile);

        pollingJob.pollMetrics();

        // Verify metric row was saved with correct mapping from InstagramUserResponse
        ArgumentCaptor<CreatorMetric> metricCaptor = ArgumentCaptor.forClass(CreatorMetric.class);
        verify(creatorMetricsRepository).save(metricCaptor.capture());

        CreatorMetric saved = metricCaptor.getValue();
        assertEquals(CREATOR_ID, saved.getCreatorProfileId());
        assertEquals("INSTAGRAM", saved.getPlatform());
        assertEquals(10000L, saved.getFollowers());
        assertEquals(500L, saved.getFollowing());
        assertEquals(150, saved.getMediaCount());
        assertEquals("META_API", saved.getDataSource());

        // Verify audit log recorded completion with 1 polled, 0 failed
        verify(auditLog)
                .recordToolCall(
                        eq(null),
                        eq("METRICS_POLLING_COMPLETED"),
                        eq("SYSTEM"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        any());
    }

    @Test
    @DisplayName("pollMetrics: rate-limit pre-check at 90%+ usage skips the creator without crashing")
    void testPollMetricsSkipsWhenRateLimitApproaching() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(92); // >= 90 threshold

        pollingJob.pollMetrics();

        // Verify InstagramInsightsClient was never called (deferred to next cycle)
        verify(instagramClient, never()).getProfile(anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));

        // Verify audit log still recorded completion (0 polled, 0 failed — skipped is not a failure)
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: expired/invalid token skips creator without throwing exception")
    void testPollMetricsSkipsWhenTokenInvalid() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.empty()); // No valid token (expired, revoked, or doesn't exist)

        pollingJob.pollMetrics();

        // Verify InstagramInsightsClient was never called
        verify(instagramClient, never()).getProfile(anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));

        // Verify audit log recorded 0 polled, 0 failed (skip is not failure)
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: MetaRateLimitException during fetch marks limited and skips save")
    void testPollMetricsHandlesRateLimitException() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaRateLimitException("Rate limit exceeded"));

        pollingJob.pollMetrics();

        // Verify rateLimitTracker.markLimited was called
        // F-0126: must use the same key as getCurrentUsage (igBusinessAccountId), not the ULID.
        verify(rateLimitTracker).markLimited(IG_BUSINESS_ACCOUNT_ID);

        // Verify no metric was saved
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));

        // Verify audit log recorded 0 polled, 1 failed
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: MetaTokenExpiredException skips creator without crashing")
    void testPollMetricsHandlesTokenExpiredException() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaTokenExpiredException("Token expired"));

        pollingJob.pollMetrics();

        // Verify no metric was saved, no crash
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: MetaApiException logs error and continues to next creator")
    void testPollMetricsHandlesMetaApiException() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaApiException("Server error"));

        pollingJob.pollMetrics();

        // Verify no metric was saved
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));

        // Verify audit log recorded 0 polled, 1 failed
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: overlap guard blocks concurrent runs via AtomicBoolean")
    void testPollMetricsOverlapGuardPreventsConcurrentRuns() throws InterruptedException {
        // Setup a slow-running poll (blocks for 100ms)
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);

        InstagramUserResponse profile =
                new InstagramUserResponse("ig_12345", "testuser", "Test User", "Bio", 10000L, 500L, 150L, null, null);
        when(instagramClient.getProfile(eq(IG_BUSINESS_ACCOUNT_ID), eq(TOKEN_VALUE), eq(MetaAuthPath.FACEBOOK_LOGIN)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100); // Simulate slow Meta API call
                            return profile;
                        });

        // Start first poll in background thread
        Thread thread1 =
                new Thread(
                        () -> {
                            pollingJob.pollMetrics();
                        });
        thread1.start();

        // Give thread1 time to acquire the lock
        Thread.sleep(10);

        // Attempt second poll on main thread (should be blocked by overlap guard)
        pollingJob.pollMetrics();

        // Wait for thread1 to complete
        thread1.join();

        // Verify instagramClient.getProfile was called exactly ONCE (second call was skipped)
        verify(instagramClient, times(1)).getProfile(eq(IG_BUSINESS_ACCOUNT_ID), eq(TOKEN_VALUE), eq(MetaAuthPath.FACEBOOK_LOGIN));

        // Verify audit log was called exactly ONCE (second call returned early without executing runPoll)
        // The overlap guard (compareAndSet returns false) causes an early return, so the audit log
        // call inside runPoll() never executes for the blocked attempt.
        verify(auditLog, times(1))
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: processes multiple creators in batch, one failure doesn't abort others")
    void testPollMetricsProcessesMultipleCreators() {
        String creator1 = "01HWXYZCREATOR000000001";
        String creator2 = "01HWXYZCREATOR000000002";
        String creator3 = "01HWXYZCREATOR000000003";
        // Distinct per creator, same as production: each token row carries its own Meta account.
        String igId1 = "17841400000000011";
        String igId2 = "17841400000000012";
        String igId3 = "17841400000000013";

        MetaOAuthToken token1 = createTestToken(WORKSPACE_ID, creator1, igId1);
        MetaOAuthToken token2 = createTestToken(WORKSPACE_ID, creator2, igId2);
        MetaOAuthToken token3 = createTestToken(WORKSPACE_ID, creator3, igId3);

        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token1, token2, token3));

        // Creator 1: success
        when(tokenStorage.getValidCreatorToken(creator1))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(igId1)).thenReturn(50);
        InstagramUserResponse profile1 =
                new InstagramUserResponse("ig_1", "user1", "User 1", "Bio 1", 5000L, 100L, 50L, null, null);
        when(instagramClient.getProfile(igId1, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN)).thenReturn(profile1);

        // Creator 2: failure (MetaApiException)
        when(tokenStorage.getValidCreatorToken(creator2))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(igId2)).thenReturn(50);
        when(instagramClient.getProfile(igId2, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN))
                .thenThrow(new MetaApiException("Error"));

        // Creator 3: success
        when(tokenStorage.getValidCreatorToken(creator3))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(igId3)).thenReturn(50);
        InstagramUserResponse profile3 =
                new InstagramUserResponse("ig_3", "user3", "User 3", "Bio 3", 8000L, 200L, 80L, null, null);
        when(instagramClient.getProfile(igId3, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN)).thenReturn(profile3);

        pollingJob.pollMetrics();

        // Verify 2 metrics saved (creator1 and creator3), creator2's failure didn't abort the batch
        verify(creatorMetricsRepository, times(2)).save(any(CreatorMetric.class));

        // Verify audit log recorded 2 polled, 1 failed, 3 total
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: null follower counts from Meta API default to 0, not NPE")
    void testPollMetricsHandlesNullFollowerCounts() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);

        // InstagramUserResponse with null followersCount
        InstagramUserResponse profile =
                new InstagramUserResponse("ig_12345", "testuser", "Test User", "Bio", null, null, null, null, null);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN)).thenReturn(profile);

        pollingJob.pollMetrics();

        ArgumentCaptor<CreatorMetric> metricCaptor = ArgumentCaptor.forClass(CreatorMetric.class);
        verify(creatorMetricsRepository).save(metricCaptor.capture());

        CreatorMetric saved = metricCaptor.getValue();
        assertEquals(0L, saved.getFollowers()); // Defaults to 0, not NPE
        assertEquals(null, saved.getFollowing());
        assertEquals(null, saved.getMediaCount());
    }

    @Test
    @DisplayName(
            "pollMetrics: CR-99/F-0113 regression — passes igBusinessAccountId, never the internal"
                    + " creatorProfileId ULID, to Meta's Graph API")
    void testPollMetricsUsesIgBusinessAccountIdNotUlid() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, IG_BUSINESS_ACCOUNT_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);
        InstagramUserResponse profile =
                new InstagramUserResponse("ig_12345", "testuser", "Test User", "Bio", 10000L, 500L, 150L, null, null);
        when(instagramClient.getProfile(IG_BUSINESS_ACCOUNT_ID, TOKEN_VALUE, MetaAuthPath.FACEBOOK_LOGIN)).thenReturn(profile);

        pollingJob.pollMetrics();

        // The regression this guards against: calling getProfile with the ULID instead.
        verify(instagramClient, never()).getProfile(eq(CREATOR_ID), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
        verify(instagramClient).getProfile(eq(IG_BUSINESS_ACCOUNT_ID), eq(TOKEN_VALUE), eq(MetaAuthPath.FACEBOOK_LOGIN));
    }

    @Test
    @DisplayName(
            "pollMetrics: F-0126 regression — pre-flight rate-limit check reads/marks usage under"
                    + " igBusinessAccountId, the same key MetaGraphApiClient itself uses, never the"
                    + " internal creatorProfileId ULID")
    void testPollMetricsRateLimitTrackerKeyedOnIgBusinessAccountIdNotUlid() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, IG_BUSINESS_ACCOUNT_ID);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        // Stub the CORRECT key at the tripping threshold; the ULID key is left unstubbed (defaults
        // to 0). If production regressed to keying on creatorProfileId, this stub would never be
        // consulted and the creator would wrongly be polled instead of deferred.
        when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(95);

        pollingJob.pollMetrics();

        verify(rateLimitTracker, never()).getCurrentUsage(CREATOR_ID);
        verify(instagramClient, never()).getProfile(anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));
    }

    @Test
    @DisplayName(
            "pollMetrics: a token row with no igBusinessAccountId on file is skipped, never called"
                    + " with a null/ULID path segment")
    void testPollMetricsSkipsWhenIgBusinessAccountIdMissing() {
        MetaOAuthToken token = createTestToken(WORKSPACE_ID, CREATOR_ID, null);
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(List.of(token));
        // Priya review finding C: a valid token must be stubbed so the igBusinessAccountId guard,
        // not the unrelated no-valid-token branch, is what actually stops this call.
        lenient()
                .when(tokenStorage.getValidCreatorToken(CREATOR_ID))
                .thenReturn(Optional.of(TOKEN_VALUE));
        lenient().when(rateLimitTracker.getCurrentUsage(IG_BUSINESS_ACCOUNT_ID)).thenReturn(50);

        pollingJob.pollMetrics();

        verify(instagramClient, never()).getProfile(anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pollMetrics: empty token list completes successfully with 0 polled")
    void testPollMetricsNoTokens() {
        when(tokenRepository.findByRevokedFalseAndExpiresAtAfter(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        pollingJob.pollMetrics();

        verify(instagramClient, never()).getProfile(anyString(), anyString(), eq(MetaAuthPath.FACEBOOK_LOGIN));
        verify(creatorMetricsRepository, never()).save(any(CreatorMetric.class));
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private MetaOAuthToken createTestToken(String workspaceId, String creatorProfileId) {
        return createTestToken(workspaceId, creatorProfileId, IG_BUSINESS_ACCOUNT_ID);
    }

    private MetaOAuthToken createTestToken(
            String workspaceId, String creatorProfileId, String igBusinessAccountId) {
        // Using anonymous subclass to mock the entity without reflection complexity
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
                return Instant.now().plus(30, ChronoUnit.DAYS);
            }

            @Override
            public boolean isRevoked() {
                return false;
            }
        };
    }
}
