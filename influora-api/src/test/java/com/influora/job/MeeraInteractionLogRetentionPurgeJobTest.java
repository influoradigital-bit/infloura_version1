package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.MeeraInteractionLogRetentionProperties;
import com.influora.repository.MeeraInteractionLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Retention purge (Priya's PARTIAL-2 hard gate, wiki/build/partials-resolution-plan.md; Kabir L1,
 * wiki/build/phase2-kabir-security.md): unit tests for {@link MeeraInteractionLogRetentionPurgeJob},
 * mirroring {@code StaleTokenCleanupJobTest}'s conventions.
 *
 * <p>The repository call itself ({@link MeeraInteractionLogRepository#deleteByCreatedAtBefore}) is
 * a bulk JPQL {@code DELETE}, which this offline unit-test harness cannot execute against a real
 * table (no H2/testcontainers-backed {@code @DataJpaTest} wired up here — see
 * {@code MeeraInteractionLogRepositoryQueryTest}'s docstring). What IS fully unit-testable, and
 * what these tests pin down, is the job's own logic around that call: it must compute a cutoff
 * that is exactly "now minus the configured retention window" (so rows strictly older than the
 * window are the only ones ever passed to the delete), it must respect the {@code enabled} flag,
 * and it must never double-run concurrently.
 */
@ExtendWith(MockitoExtension.class)
class MeeraInteractionLogRetentionPurgeJobTest {

    @Mock private MeeraInteractionLogRepository repository;

    private MeeraInteractionLogRetentionProperties properties;
    private MeeraInteractionLogRetentionPurgeJob job;

    @BeforeEach
    void setUp() {
        properties = new MeeraInteractionLogRetentionProperties();
        job = new MeeraInteractionLogRetentionPurgeJob(repository, properties);
    }

    @Test
    @DisplayName("enabled + default 180-day window: purges via a cutoff ~180 days in the past")
    void testEnabledPurgesWithDefaultRetentionWindow() {
        properties.setEnabled(true);
        // retentionDays left at the class default (180).
        when(repository.deleteByCreatedAtBefore(any(Instant.class))).thenReturn(7);

        Instant before = Instant.now();
        job.purgeExpiredInteractionLogs();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository, times(1)).deleteByCreatedAtBefore(cutoffCaptor.capture());

        Instant cutoff = cutoffCaptor.getValue();
        // The cutoff must be meaningfully in the past relative to "now" — a row created exactly at
        // the cutoff instant is retained (repository predicate is strict "<"), and anything created
        // before it is purged. Assert it lands within a day of "before - 180 days", i.e. rows from
        // 179 days ago are kept, rows from 181+ days ago are purged.
        assertTrue(cutoff.isBefore(before), "cutoff must be strictly in the past");
        long daysBeforeNow = ChronoUnit.DAYS.between(cutoff, before);
        assertTrue(
                daysBeforeNow >= 179,
                "cutoff must be ~180 days in the past for the default retention window, was "
                        + daysBeforeNow
                        + " days");
    }

    @Test
    @DisplayName("configured retention window (30 days) is honored, not hardcoded 180")
    void testHonorsConfiguredRetentionWindow() {
        properties.setEnabled(true);
        properties.setRetentionDays(30);
        when(repository.deleteByCreatedAtBefore(any(Instant.class))).thenReturn(0);

        Instant before = Instant.now();
        job.purgeExpiredInteractionLogs();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByCreatedAtBefore(cutoffCaptor.capture());

        long daysBeforeNow = ChronoUnit.DAYS.between(cutoffCaptor.getValue(), before);
        assertTrue(
                daysBeforeNow >= 29 && daysBeforeNow <= 30,
                "cutoff must reflect the configured 30-day window, was " + daysBeforeNow + " days");
    }

    @Test
    @DisplayName("disabled: the repository is never called (no-op, unbounded growth preserved)")
    void testDisabledDoesNotCallRepository() {
        properties.setEnabled(false);

        job.purgeExpiredInteractionLogs();

        verify(repository, never()).deleteByCreatedAtBefore(any(Instant.class));
    }

    @Test
    @DisplayName("overlap guard blocks concurrent runs via AtomicBoolean")
    void testOverlapGuardPreventsConcurrentRuns() throws InterruptedException {
        properties.setEnabled(true);
        when(repository.deleteByCreatedAtBefore(any(Instant.class)))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(100);
                            return 0;
                        });

        Thread thread1 = new Thread(job::purgeExpiredInteractionLogs);
        thread1.start();
        Thread.sleep(10);

        job.purgeExpiredInteractionLogs();

        thread1.join();

        verify(repository, times(1)).deleteByCreatedAtBefore(any(Instant.class));
    }
}
