package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.BrandSafetyScoringProperties;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorScore;
import com.influora.domain.entity.MediaMetric;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorScoreRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.scoring.BrandSafetyScoreService;
import com.influora.service.scoring.BrandSafetyScoreService.BrandSafetyResult;
import com.influora.service.scoring.FakeFollowerDetectionService;
import com.influora.service.scoring.FakeFollowerDetectionService.FakeFollowerResult;
import com.influora.service.scoring.QualityScoreService;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.RateEstimationService;
import com.influora.service.scoring.RateEstimationService.RateEstimation;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Phase 3 Scoring Algorithms: Unit tests for ScoreCalculationJob (V22 creator_scores storage
 * layer). Mirrors MetricsPollingJobTest's conventions/resilience-test shape.
 */
@ExtendWith(MockitoExtension.class)
class ScoreCalculationJobTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private FakeFollowerDetectionService fakeFollowerDetectionService;
    @Mock private QualityScoreService qualityScoreService;
    @Mock private RateEstimationService rateEstimationService;
    @Mock private CreatorScoreRepository creatorScoreRepository;
    @Mock private BrandSafetyScoreService brandSafetyScoreService;

    private BrandSafetyScoringProperties brandSafetyScoringProperties;
    private ScoreCalculationJob job;

    @BeforeEach
    void setUp() {
        // Default: brand-safety scoring OFF (matches application.yml default) — the existing tests
        // below assert the pre-flag behaviour (brand-safety columns stay null).
        brandSafetyScoringProperties = new BrandSafetyScoringProperties();
        job = buildJob();
    }

    private ScoreCalculationJob buildJob() {
        return new ScoreCalculationJob(
                creatorProfileRepository,
                creatorMetricsRepository,
                mediaMetricsRepository,
                fakeFollowerDetectionService,
                qualityScoreService,
                rateEstimationService,
                creatorScoreRepository,
                brandSafetyScoreService,
                brandSafetyScoringProperties);
    }

    @Test
    @DisplayName("calculateScores: normal path persists a CreatorScore row per creator")
    void testCalculateScoresPersistsScoreRow() {
        CreatorProfile creator = createTestProfile(CREATOR_ID, "[\"FASHION\"]");
        when(creatorProfileRepository.findAll(any(Specification.class))).thenReturn(List.of(creator));

        CreatorMetric metric = createTestMetric(CREATOR_ID);
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metric));

        List<MediaMetric> media = List.of(mock(MediaMetric.class));
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(media);

        FakeFollowerResult fakeResult =
                new FakeFollowerResult(BigDecimal.valueOf(12.5), List.of("low engagement"), Map.of());
        when(fakeFollowerDetectionService.analyze(eq(Optional.of(metric)), eq(media), eq(List.of())))
                .thenReturn(fakeResult);

        QualityScoreResult qualityResult =
                new QualityScoreResult(
                        BigDecimal.valueOf(80),
                        BigDecimal.valueOf(70),
                        BigDecimal.valueOf(60),
                        BigDecimal.valueOf(90),
                        BigDecimal.valueOf(50));
        when(qualityScoreService.calculate(Optional.of(metric), media)).thenReturn(qualityResult);

        RateEstimation rateEstimation =
                new RateEstimation(
                        BigDecimal.valueOf(10000),
                        BigDecimal.valueOf(50000),
                        "INR",
                        BigDecimal.valueOf(90),
                        "MICRO",
                        Map.of());
        when(rateEstimationService.estimate(
                        eq(Optional.of(metric)), eq(qualityResult), eq(List.of("FASHION"))))
                .thenReturn(rateEstimation);

        job.calculateScores();

        ArgumentCaptor<CreatorScore> captor = ArgumentCaptor.forClass(CreatorScore.class);
        verify(creatorScoreRepository).save(captor.capture());

        CreatorScore saved = captor.getValue();
        assertEquals(CREATOR_ID, saved.getCreatorProfileId());
        assertEquals(BigDecimal.valueOf(12.5), saved.getFakeFollowerScore());
        assertEquals(BigDecimal.valueOf(80), saved.getQualityScore());
        assertEquals(BigDecimal.valueOf(60), saved.getEngagementConsistency());
        assertEquals(BigDecimal.valueOf(90), saved.getPostingFrequency());
        assertEquals(BigDecimal.valueOf(50), saved.getAudienceMatchScore());
        assertEquals(BigDecimal.valueOf(10000), saved.getEstimatedRateMin());
        assertEquals(BigDecimal.valueOf(50000), saved.getEstimatedRateMax());
        assertEquals("INR", saved.getRateCurrency());
        assertEquals(BigDecimal.valueOf(90), saved.getRateConfidence());
        assertEquals("v1.0.0", saved.getAlgorithmVersion());

        // Scope cut: brand safety fields must remain null — BrandSafetyScoreService isn't built yet.
        assertEquals(null, saved.getBrandSafetyScore());
        assertEquals(null, saved.getGarmFlagsJson());
        assertEquals(null, saved.getContentSentiment());
    }

    @Test
    @DisplayName("calculateScores: one creator's exception doesn't abort the batch")
    void testCalculateScoresOneFailureDoesNotAbortBatch() {
        String creator1 = "01HWXYZCREATOR000000001";
        String creator2 = "01HWXYZCREATOR000000002";
        String creator3 = "01HWXYZCREATOR000000003";

        CreatorProfile profile1 = createTestProfile(creator1, null);
        CreatorProfile profile2 = createTestProfile(creator2, null);
        CreatorProfile profile3 = createTestProfile(creator3, null);

        when(creatorProfileRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(profile1, profile2, profile3));

        CreatorMetric metric1 = createTestMetric(creator1);
        CreatorMetric metric3 = createTestMetric(creator3);

        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        creator1, "INSTAGRAM"))
                .thenReturn(Optional.of(metric1));
        // creator2: metrics lookup itself throws an unexpected exception.
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        creator2, "INSTAGRAM"))
                .thenThrow(new RuntimeException("boom"));
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        creator3, "INSTAGRAM"))
                .thenReturn(Optional.of(metric3));

        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        FakeFollowerResult fakeResult = new FakeFollowerResult(BigDecimal.ZERO, List.of(), Map.of());
        when(fakeFollowerDetectionService.analyze(any(), any(), any())).thenReturn(fakeResult);

        QualityScoreResult qualityResult =
                new QualityScoreResult(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(qualityScoreService.calculate(any(), any())).thenReturn(qualityResult);

        RateEstimation rateEstimation =
                new RateEstimation(
                        BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "UNKNOWN", Map.of());
        when(rateEstimationService.estimate(any(), any(), any())).thenReturn(rateEstimation);

        job.calculateScores();

        // creator1 and creator3 still get scored despite creator2's exception aborting mid-batch.
        verify(creatorScoreRepository, times(2)).save(any(CreatorScore.class));
    }

    @Test
    @DisplayName("calculateScores: a creator with no metrics is skipped gracefully, not crashing")
    void testCalculateScoresSkipsCreatorWithNoMetrics() {
        CreatorProfile creator = createTestProfile(CREATOR_ID, null);
        when(creatorProfileRepository.findAll(any(Specification.class))).thenReturn(List.of(creator));

        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        job.calculateScores();

        verify(mediaMetricsRepository, never())
                .findByCreatorProfileIdOrderByTimeDesc(anyString(), any(Pageable.class));
        verify(creatorScoreRepository, never()).save(any(CreatorScore.class));
    }

    @Test
    @DisplayName("calculateScores: empty discoverable-creator list completes cleanly with no saves")
    void testCalculateScoresNoCreators() {
        when(creatorProfileRepository.findAll(any(Specification.class))).thenReturn(List.of());

        job.calculateScores();

        verify(creatorMetricsRepository, never())
                .findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(anyString(), anyString());
        verify(creatorScoreRepository, never()).save(any(CreatorScore.class));
    }

    @Test
    @DisplayName("calculateScores: flag ON populates brand-safety columns from BrandSafetyScoreService")
    void testCalculateScoresPopulatesBrandSafetyWhenEnabled() {
        brandSafetyScoringProperties.setEnabled(true);
        brandSafetyScoringProperties.setMaxCreatorsPerRun(10);
        job = buildJob();

        CreatorProfile creator = createTestProfile(CREATOR_ID, "[\"FASHION\"]");
        when(creatorProfileRepository.findAll(any(Specification.class))).thenReturn(List.of(creator));
        // Selection materializes the staleness key once per creator up front (see
        // selectBrandSafetyTargets javadoc) — never scored yet, so this sorts first regardless of
        // pool size.
        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID))
                .thenReturn(Optional.empty());

        CreatorMetric metric = createTestMetric(CREATOR_ID);
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metric));

        List<MediaMetric> media = List.of(mock(MediaMetric.class));
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(media);

        when(fakeFollowerDetectionService.analyze(any(), any(), any()))
                .thenReturn(new FakeFollowerResult(BigDecimal.ZERO, List.of(), Map.of()));
        when(qualityScoreService.calculate(any(), any()))
                .thenReturn(
                        new QualityScoreResult(
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(rateEstimationService.estimate(any(), any(), any()))
                .thenReturn(
                        new RateEstimation(
                                BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "MICRO", Map.of()));

        BrandSafetyResult bsResult =
                new BrandSafetyResult(
                        BigDecimal.valueOf(88.00).setScale(2), "[\"Debated Sensitive Social Issue\"]",
                        BigDecimal.valueOf(0.25).setScale(2));
        when(brandSafetyScoreService.scoreCreator(CREATOR_ID, media))
                .thenReturn(Optional.of(bsResult));

        job.calculateScores();

        ArgumentCaptor<CreatorScore> captor = ArgumentCaptor.forClass(CreatorScore.class);
        verify(creatorScoreRepository).save(captor.capture());
        CreatorScore saved = captor.getValue();
        assertEquals(BigDecimal.valueOf(88.00).setScale(2), saved.getBrandSafetyScore());
        assertEquals("[\"Debated Sensitive Social Issue\"]", saved.getGarmFlagsJson());
        assertEquals(BigDecimal.valueOf(0.25).setScale(2), saved.getContentSentiment());
        // Regression guard (Kavya/Kabir finding): the staleness key must be queried exactly once per
        // creator, never re-queried per comparator invocation during the sort.
        verify(creatorScoreRepository, times(1))
                .findFirstByCreatorProfileIdOrderByTimeDesc(CREATOR_ID);
    }

    @Test
    @DisplayName("calculateScores: flag ON but scoreCreator empty -> row saved, brand-safety columns null")
    void testCalculateScoresBrandSafetyDegradesToNull() {
        brandSafetyScoringProperties.setEnabled(true);
        brandSafetyScoringProperties.setMaxCreatorsPerRun(10);
        job = buildJob();

        CreatorProfile creator = createTestProfile(CREATOR_ID, null);
        when(creatorProfileRepository.findAll(any(Specification.class))).thenReturn(List.of(creator));

        CreatorMetric metric = createTestMetric(CREATOR_ID);
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        CREATOR_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(metric));

        List<MediaMetric> media = List.of(mock(MediaMetric.class));
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_ID), any(Pageable.class)))
                .thenReturn(media);

        when(fakeFollowerDetectionService.analyze(any(), any(), any()))
                .thenReturn(new FakeFollowerResult(BigDecimal.ZERO, List.of(), Map.of()));
        when(qualityScoreService.calculate(any(), any()))
                .thenReturn(
                        new QualityScoreResult(
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(rateEstimationService.estimate(any(), any(), any()))
                .thenReturn(
                        new RateEstimation(
                                BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "MICRO", Map.of()));

        // influora-ai unreachable / no connection / partial data -> graceful empty.
        when(brandSafetyScoreService.scoreCreator(CREATOR_ID, media)).thenReturn(Optional.empty());

        job.calculateScores();

        ArgumentCaptor<CreatorScore> captor = ArgumentCaptor.forClass(CreatorScore.class);
        verify(creatorScoreRepository).save(captor.capture());
        CreatorScore saved = captor.getValue();
        // Row still persisted with the other scores; brand-safety columns stay null (never "safe").
        assertEquals(null, saved.getBrandSafetyScore());
        assertEquals(null, saved.getGarmFlagsJson());
        assertEquals(null, saved.getContentSentiment());
    }

    @Test
    @DisplayName("calculateScores: brand-safety cap targets the never-scored creator first, not the others")
    void testBrandSafetyPrioritisesStalest() {
        brandSafetyScoringProperties.setEnabled(true);
        brandSafetyScoringProperties.setMaxCreatorsPerRun(1); // only ONE creator may be BS-scored
        job = buildJob();

        String recent = "01HWXYZCREATOR000000001"; // has a recent brand-safety score
        String neverScored = "01HWXYZCREATOR000000002"; // never brand-safety scored -> priority
        String stale = "01HWXYZCREATOR000000003"; // has an older brand-safety score

        CreatorProfile pRecent = createTestProfile(recent, null);
        CreatorProfile pNever = createTestProfile(neverScored, null);
        CreatorProfile pStale = createTestProfile(stale, null);
        when(creatorProfileRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(pRecent, pNever, pStale));

        // Prioritisation lookups: recent (now) and stale (long ago) both HAVE a brand-safety score;
        // neverScored has no prior score row at all -> sorts to the front (Instant.EPOCH).
        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(recent))
                .thenReturn(Optional.of(scoreWithBrandSafety(recent, Instant.now())));
        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(stale))
                .thenReturn(
                        Optional.of(
                                scoreWithBrandSafety(stale, Instant.now().minusSeconds(30L * 24 * 3600))));
        when(creatorScoreRepository.findFirstByCreatorProfileIdOrderByTimeDesc(neverScored))
                .thenReturn(Optional.empty());

        // All three are fully scorable.
        when(creatorMetricsRepository.findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
                        anyString(), eq("INSTAGRAM")))
                .thenAnswer(inv -> Optional.of(createTestMetric(inv.getArgument(0))));
        List<MediaMetric> media = List.of(mock(MediaMetric.class));
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(anyString(), any(Pageable.class)))
                .thenReturn(media);
        when(fakeFollowerDetectionService.analyze(any(), any(), any()))
                .thenReturn(new FakeFollowerResult(BigDecimal.ZERO, List.of(), Map.of()));
        when(qualityScoreService.calculate(any(), any()))
                .thenReturn(
                        new QualityScoreResult(
                                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(rateEstimationService.estimate(any(), any(), any()))
                .thenReturn(
                        new RateEstimation(
                                BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "MICRO", Map.of()));

        when(brandSafetyScoreService.scoreCreator(eq(neverScored), any())).thenReturn(Optional.empty());

        job.calculateScores();

        // All three still get a score row; only the never-scored creator incurred the AI call.
        verify(creatorScoreRepository, times(3)).save(any(CreatorScore.class));
        verify(brandSafetyScoreService).scoreCreator(eq(neverScored), any());
        verify(brandSafetyScoreService, never()).scoreCreator(eq(recent), any());
        verify(brandSafetyScoreService, never()).scoreCreator(eq(stale), any());
        // Regression guard (Kavya/Kabir finding): each creator's staleness key is queried EXACTLY
        // once, regardless of how many pairwise comparisons the sort performs over the 3-creator
        // pool — proves selection no longer re-queries the DB per comparator invocation.
        verify(creatorScoreRepository, times(1)).findFirstByCreatorProfileIdOrderByTimeDesc(recent);
        verify(creatorScoreRepository, times(1)).findFirstByCreatorProfileIdOrderByTimeDesc(stale);
        verify(creatorScoreRepository, times(1))
                .findFirstByCreatorProfileIdOrderByTimeDesc(neverScored);
    }

    private CreatorScore scoreWithBrandSafety(String creatorProfileId, Instant computedAt) {
        return CreatorScore.builder()
                .id("01HWXYZSCORE00000000000" + creatorProfileId.substring(creatorProfileId.length() - 1))
                .creatorProfileId(creatorProfileId)
                .brandSafetyScore(BigDecimal.valueOf(90).setScale(2))
                .computedAt(computedAt)
                .build();
    }

    private CreatorMetric createTestMetric(String creatorProfileId) {
        return CreatorMetric.builder()
                .id("01HWXYZMETRIC1234567890")
                .creatorProfileId(creatorProfileId)
                .platform("INSTAGRAM")
                .followers(15000L)
                .following(500L)
                .avgEngagementRate(BigDecimal.valueOf(4.5))
                .dataSource("META_API")
                .build();
    }

    private CreatorProfile createTestProfile(String id, String categoriesJson) {
        // Anonymous subclass to avoid entity-shape reflection, same technique as
        // MetricsPollingJobTest's createTestToken for MetaOAuthToken.
        return new CreatorProfile() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getCategoriesJson() {
                return categoriesJson;
            }
        };
    }
}
