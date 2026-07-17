package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.AuditLogService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wave B task B2: unit tests for StaleTokenCleanupJob, mirroring MetricsPollingJobTest's
 * conventions. Verifies soft-revoke semantics (never delete), the grace-period query, and
 * per-token error isolation.
 */
@ExtendWith(MockitoExtension.class)
class StaleTokenCleanupJobTest {

    private static final String WORKSPACE_ID = "01HWXYZ123456789012345";
    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";

    @Mock private MetaOAuthTokenRepository tokenRepository;
    @Mock private AuditLogService auditLog;

    private StaleTokenCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        cleanupJob = new StaleTokenCleanupJob(tokenRepository, auditLog);
    }

    @Test
    @DisplayName("cleanup semantics: token expired past the grace period is soft-revoked, never deleted")
    void testStaleTokenIsSoftRevokedNotDeleted() {
        RevokeTrackingToken token = new RevokeTrackingToken(WORKSPACE_ID, CREATOR_ID, false);
        when(tokenRepository.findByExpiresAtBeforeAndRevokedFalse(any(Instant.class)))
                .thenReturn(List.of(token));

        cleanupJob.cleanupStaleTokens();

        assertTrue(token.revokeCalled);
        verify(tokenRepository, never()).delete(any(MetaOAuthToken.class));
        verify(tokenRepository, never()).deleteById(any());
        ArgumentCaptor<MetaOAuthToken> savedCaptor = ArgumentCaptor.forClass(MetaOAuthToken.class);
        verify(tokenRepository).save(savedCaptor.capture());
        assertEquals(token, savedCaptor.getValue());
    }

    @Test
    @DisplayName("cleanup semantics: query uses a threshold older than 'now' (the grace period), not the raw expiry instant")
    void testCleanupUsesGracePeriodThreshold() {
        when(tokenRepository.findByExpiresAtBeforeAndRevokedFalse(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        Instant before = Instant.now();
        cleanupJob.cleanupStaleTokens();

        ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(tokenRepository).findByExpiresAtBeforeAndRevokedFalse(thresholdCaptor.capture());

        // Threshold must be meaningfully in the past (now - grace period), not "now" itself —
        // otherwise this job would race/duplicate MetaTokenRefreshService's own expiry check.
        assertTrue(thresholdCaptor.getValue().isBefore(before));
        long daysBeforeNow = ChronoUnit.DAYS.between(thresholdCaptor.getValue(), before);
        assertTrue(daysBeforeNow >= StaleTokenCleanupJob.STALE_GRACE_PERIOD.toDays() - 1);
    }

    @Test
    @DisplayName("nothing expiring/stale: no-op, no saves, audit still records zero counts")
    void testNoStaleCandidatesIsNoOp() {
        when(tokenRepository.findByExpiresAtBeforeAndRevokedFalse(any(Instant.class)))
                .thenReturn(Collections.emptyList());

        cleanupJob.cleanupStaleTokens();

        verify(tokenRepository, never()).save(any(MetaOAuthToken.class));
        verify(auditLog).recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("cleanup failure isolation: one token's unexpected failure doesn't abort the rest of the batch")
    void testOneTokenFailureDoesNotAbortBatch() {
        RevokeTrackingToken badToken =
                new RevokeTrackingToken(WORKSPACE_ID, "01HWXYZCREATOR_BAD00001", true);
        RevokeTrackingToken goodToken =
                new RevokeTrackingToken(WORKSPACE_ID, "01HWXYZCREATOR_GOOD0001", false);

        when(tokenRepository.findByExpiresAtBeforeAndRevokedFalse(any(Instant.class)))
                .thenReturn(List.of(badToken, goodToken));

        cleanupJob.cleanupStaleTokens();

        // badToken.revoke() throws (simulated below); goodToken must still be revoked and saved.
        assertTrue(goodToken.revokeCalled);
        verify(tokenRepository, times(1)).save(goodToken);
        verify(tokenRepository, never()).save(badToken);
    }

    @Test
    @DisplayName("overlap guard blocks concurrent runs via AtomicBoolean")
    void testOverlapGuardPreventsConcurrentRuns() throws InterruptedException {
        RevokeTrackingToken token = new RevokeTrackingToken(WORKSPACE_ID, CREATOR_ID, false);
        when(tokenRepository.findByExpiresAtBeforeAndRevokedFalse(any(Instant.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return List.of(token);
                        });

        Thread thread1 = new Thread(() -> cleanupJob.cleanupStaleTokens());
        thread1.start();
        Thread.sleep(10);

        cleanupJob.cleanupStaleTokens();

        thread1.join();

        verify(tokenRepository, times(1)).findByExpiresAtBeforeAndRevokedFalse(any(Instant.class));
        verify(auditLog, times(1))
                .recordToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** Anonymous-subclass style entity stub, matching MetricsPollingJobTest's createTestToken pattern. */
    private static class RevokeTrackingToken extends MetaOAuthToken {
        private final String workspaceId;
        private final String creatorProfileId;
        private final boolean throwOnRevoke;
        boolean revokeCalled = false;

        RevokeTrackingToken(String workspaceId, String creatorProfileId, boolean throwOnRevoke) {
            this.workspaceId = workspaceId;
            this.creatorProfileId = creatorProfileId;
            this.throwOnRevoke = throwOnRevoke;
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
        public Instant getExpiresAt() {
            return Instant.now().minus(20, ChronoUnit.DAYS);
        }

        @Override
        public void revoke() {
            revokeCalled = true;
            if (throwOnRevoke) {
                throw new RuntimeException("unexpected revoke failure");
            }
        }
    }
}
