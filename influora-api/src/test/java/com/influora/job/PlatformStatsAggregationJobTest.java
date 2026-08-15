package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.service.AuditLogService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * H-10 unit tests for {@link PlatformStatsAggregationJob} — mirrors {@code
 * ScoreCalculationJobTest}/{@code MetricsPollingJobTest}'s conventions (mocked repositories, no
 * real DB, per-creator error isolation, "never fabricate" coverage).
 */
@ExtendWith(MockitoExtension.class)
class PlatformStatsAggregationJobTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private AuditLogService auditLog;

    private PlatformStatsAggregationJob job;

    @BeforeEach
    void setUp() {
        job =
                new PlatformStatsAggregationJob(
                        creatorProfileRepository, creatorMetricsRepository, platformStatRepository, auditLog);
    }

    @Test
    @DisplayName("aggregate: creates a new platform_stats row and denormalizes onto creator_profiles when none exists yet")
    void aggregate_createsNewPlatformStatAndDenormalizes() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(creator)));

        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(12000L)
                        .avgEngagementRate(BigDecimal.valueOf(3.75))
                        .dataSource("META_API")
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metric));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        PlatformStat saved = statCaptor.getValue();
        assertEquals(CREATOR_ID, saved.getCreatorProfileId());
        assertEquals("INSTAGRAM", saved.getPlatform());
        assertEquals(12000L, saved.getFollowers());
        assertEquals(BigDecimal.valueOf(3.75), saved.getEngagementRate());

        verify(creatorProfileRepository).save(creator);
        assertEquals(12000L, creator.getTotalFollowers());
        assertEquals(BigDecimal.valueOf(3.75), creator.getEngagementRate());
    }

    @Test
    @DisplayName("aggregate: existing platform_stats row is updated in place, not duplicated")
    void aggregate_updatesExistingPlatformStatRatherThanDuplicating() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(creator)));

        // CR-119 — provenance is now stated explicitly. This fixture previously omitted
        // .dataSource(...) and leaned on the builder default, which was harmless while `verified`
        // was ignored entirely. It matters now: the flag tracks the incoming snapshot's
        // provenance, so "verified survives" is only a true claim when the snapshot doing the
        // updating is ITSELF platform-verified — which is exactly what MetricsPollingJob writes.
        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(20000L)
                        .dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metric));

        PlatformStat existing =
                PlatformStat.builder()
                        .id("01HEXISTINGSTAT1234567")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(9000L)
                        .verified(true)
                        .build();
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(existing));

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        PlatformStat saved = statCaptor.getValue();
        assertEquals("01HEXISTINGSTAT1234567", saved.getId(), "must update the same row, not create a new one");
        assertEquals(20000L, saved.getFollowers());
        assertEquals(
                true,
                saved.isVerified(),
                "a verified row updated by another platform-verified snapshot must stay verified");
    }

    @Test
    @DisplayName("aggregate: creator with no creator_metrics yet is skipped, never zero-filled")
    void aggregate_skipsCreatorWithNoMetricsRatherThanFabricatingZero() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(creator)));
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.aggregate();

        verify(platformStatRepository, never()).save(any());
        verify(creatorProfileRepository, never()).save(any());
    }

    @Test
    @DisplayName("aggregate: engagement rate stays null when the underlying metric has none yet (never fabricated)")
    void aggregate_leavesEngagementRateNullWhenMetricHasNone() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(creator)));

        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(5000L)
                        .avgEngagementRate(null)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metric));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.aggregate();

        assertEquals(5000L, creator.getTotalFollowers());
        assertNull(creator.getEngagementRate());
    }

    @Test
    @DisplayName("aggregate: one creator's unexpected exception doesn't abort the batch")
    void aggregate_perCreatorFailureIsolationDoesNotAbortBatch() {
        CreatorProfile creator1 = testProfile("01HCREATOR0000000000001");
        CreatorProfile creator2 = testProfile("01HCREATOR0000000000002");
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(creator1, creator2)));

        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        "01HCREATOR0000000000001", "INSTAGRAM"))
                .thenThrow(new RuntimeException("unexpected blow-up"));

        CreatorMetric metric2 =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId("01HCREATOR0000000000002")
                        .platform("INSTAGRAM")
                        .followers(3000L)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        "01HCREATOR0000000000002", "INSTAGRAM"))
                .thenReturn(Optional.of(metric2));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(
                        "01HCREATOR0000000000002", "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.aggregate();

        verify(creatorProfileRepository, never()).save(creator1);
        verify(creatorProfileRepository).save(creator2);
        assertEquals(3000L, creator2.getTotalFollowers());
    }

    @Test
    @DisplayName(
            "aggregate CR-116: an existing handle survives a poll whose snapshot carries no username (never clobbered with null)")
    void aggregate_preservesExistingHandleWhenSnapshotHasNoUsername() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(creator)));

        // This poll's snapshot has no username (Meta omitted it) — username(null), same as if
        // .username(...) were never called.
        CreatorMetric metricWithNoUsername =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(20000L)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metricWithNoUsername));

        PlatformStat existingWithHandle =
                PlatformStat.builder()
                        .id("01HEXISTINGSTAT1234567")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .handle("priya_creates")
                        .followers(9000L)
                        .build();
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(existingWithHandle));

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        assertEquals(
                "priya_creates",
                statCaptor.getValue().getHandle(),
                "a snapshot with no username must not blank out a previously-recorded handle");
        assertEquals(20000L, statCaptor.getValue().getFollowers(), "followers must still update normally");
    }

    @Test
    @DisplayName("aggregate CR-116: a fresh username on an existing row overwrites the stale one")
    void aggregate_updatesHandleWhenSnapshotCarriesANewUsername() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(creator)));

        CreatorMetric metricWithNewUsername =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .username("priya_rebranded")
                        .followers(20000L)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metricWithNewUsername));

        PlatformStat existingWithStaleHandle =
                PlatformStat.builder()
                        .id("01HEXISTINGSTAT1234567")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .handle("priya_creates")
                        .followers(9000L)
                        .build();
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(existingWithStaleHandle));

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        assertEquals("priya_rebranded", statCaptor.getValue().getHandle());
    }

    @Test
    @DisplayName("aggregate: empty discoverable-creator list completes cleanly with no saves")
    void aggregate_noCreators() {
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        job.aggregate();

        verify(creatorMetricsRepository, never())
                .findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(anyString(), anyString());
        verify(platformStatRepository, never()).save(any());
        verify(auditLog)
                .recordToolCall(
                        eq(null),
                        eq("PLATFORM_STATS_AGGREGATION_COMPLETED"),
                        eq("SYSTEM"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq(null),
                        eq(null),
                        eq(null),
                        any());
    }

    @Test
    @DisplayName(
            "aggregate CR-119: a META_API-sourced snapshot marks a NEW platform stat verified")
    void aggregate_marksNewStatVerifiedWhenSnapshotCameFromThePlatformApi() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(creator)));

        // dataSource set EXPLICITLY, exactly as MetricsPollingJob and PortfolioService both do.
        // Deliberately not leaning on the builder default: the default is fail-closed
        // (CREATOR_REPORTED) precisely so a forgotten provenance can never mint a verified claim,
        // and a test that omitted it would be pinning the wrong behaviour as intended.
        CreatorMetric metaSourced =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .username("priya_creates")
                        .followers(20000L)
                        .dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metaSourced));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        assertTrue(
                statCaptor.getValue().isVerified(),
                "a stat built from a real Meta Graph API snapshot must claim verified provenance —"
                        + " this was hardcoded false, which left the brand-facing verified badge dead");
    }

    @Test
    @DisplayName(
            "aggregate CR-119: a creator-reported snapshot must NOT mark the stat verified")
    void aggregate_leavesStatUnverifiedWhenSnapshotIsSelfReported() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(creator)));

        // The shape a YouTube/TikTok/Twitter row would have — no platform API exists for these
        // (the still-open half of CR-119), so the number can only be creator-reported.
        CreatorMetric selfReported =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .username("priya_creates")
                        .followers(20000L)
                        .dataSource(CreatorMetric.DATA_SOURCE_CREATOR_REPORTED)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(selfReported));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        assertFalse(
                statCaptor.getValue().isVerified(),
                "a self-reported number must never be presented to brands as platform-verified");
    }

    @Test
    @DisplayName(
            "aggregate CR-119: a self-reported snapshot DEMOTES a previously-verified row to unverified")
    void aggregate_demotesPreviouslyVerifiedRowWhenNewSnapshotIsSelfReported() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(creator)));

        CreatorMetric selfReported =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .username("priya_creates")
                        .followers(20000L)
                        .dataSource(CreatorMetric.DATA_SOURCE_CREATOR_REPORTED)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(selfReported));

        PlatformStat previouslyVerified =
                PlatformStat.builder()
                        .id("01HEXISTINGSTAT1234567")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .handle("priya_creates")
                        .followers(9000L)
                        .verified(true)
                        .build();
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(previouslyVerified));

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        // `verified` describes the provenance of the follower number the row CURRENTLY holds.
        // The row's number has just been overwritten with a self-reported one, so continuing to
        // advertise it as platform-verified would be a live lie to a brand. A never-demote latch
        // (`existing.isVerified() || metric.isPlatformVerified()`) passes every other test in
        // this file — this is the only thing standing between that and production.
        assertFalse(
                statCaptor.getValue().isVerified(),
                "once the stored number comes from self-report, the row must stop claiming verified");
    }

    @Test
    @DisplayName(
            "aggregate CR-119: a snapshot with NO declared dataSource fails CLOSED (not verified)")
    void aggregate_failsClosedWhenSnapshotDeclaresNoDataSource() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(creator)));

        // The trap this pins: `verified` gates a claim brands spend money on, so a caller that
        // simply forgets .dataSource(...) must NOT get a platform-verified row for free. The
        // builder default used to be META_API, which made exactly that mistake silent.
        CreatorMetric noDeclaredSource =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .username("priya_creates")
                        .followers(20000L)
                        .build();
        assertEquals(
                CreatorMetric.DATA_SOURCE_CREATOR_REPORTED,
                noDeclaredSource.getDataSource(),
                "an undeclared provenance must default to creator-reported, never to a platform API");
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(noDeclaredSource));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        assertFalse(
                statCaptor.getValue().isVerified(),
                "a forgotten .dataSource(...) must never silently mint a platform-verified claim");
    }

    @Test
    @DisplayName(
            "aggregate CR-119: an EXISTING stat's verified flag tracks the new snapshot, not its stale value")
    void aggregate_updatesVerifiedFlagOnExistingStatFromSnapshotProvenance() {
        CreatorProfile creator = testProfile(CREATOR_ID);
        when(creatorProfileRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(creator)));

        CreatorMetric metaSourced =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .username("priya_creates")
                        .followers(20000L)
                        .dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .build();
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metaSourced));

        // The regression this pins: every row this job or PortfolioService ever created is
        // verified=false, because no Java writer ever set true. Passing `existing.isVerified()`
        // through meant a real Meta poll could never lift a row out of that state — the flag was
        // permanently latched false.
        PlatformStat staleUnverified =
                PlatformStat.builder()
                        .id("01HEXISTINGSTAT1234567")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .handle("priya_creates")
                        .followers(9000L)
                        .verified(false)
                        .build();
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(staleUnverified));

        job.aggregate();

        ArgumentCaptor<PlatformStat> statCaptor = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(statCaptor.capture());
        assertTrue(
                statCaptor.getValue().isVerified(),
                "a real Meta poll must be able to promote a legacy unverified row to verified");
    }

    private CreatorProfile testProfile(String id) {
        // Anonymous subclass, same technique as ScoreCalculationJobTest's createTestProfile —
        // applyAggregatedStats/getTotalFollowers/getEngagementRate run as real (non-overridden)
        // entity code, so assertions below observe the actual mutation.
        return new CreatorProfile() {
            @Override
            public String getId() {
                return id;
            }
        };
    }
}
