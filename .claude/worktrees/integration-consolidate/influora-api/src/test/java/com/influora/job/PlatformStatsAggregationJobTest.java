package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .followers(20000L)
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
        assertEquals(true, saved.isVerified(), "verified flag must survive an aggregation update");
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
