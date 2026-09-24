package com.influora.service.meera.tool.creator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.enums.CreatorTier;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.FollowerTotals;
import com.influora.service.scoring.CreatorTiers;
import com.influora.service.scoring.QualityScoreService;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.MetricsResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.6) — {@code get_my_metrics}. */
@ExtendWith(MockitoExtension.class)
class GetMyMetricsExecutorTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE123A";

    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private QualityScoreService qualityScoreService;
    // V20260924120000: the tool reads only the account the creator is connected to now.
    @Mock private com.influora.service.creatorcopilot.ConnectedInstagramAccount connectedAccount;

    private GetMyMetricsExecutor executor;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        executor =
                new GetMyMetricsExecutor(
                        preferencesService,
                        creatorMetricsRepository,
                        mediaMetricsRepository,
                        qualityScoreService,
                        connectedAccount);
        profile = CreatorProfile.newForUser(CREATOR_PROFILE_ID, CREATOR_USER_ID, "Priya Shah");
        lenient().when(preferencesService.requireCreatorProfile(CREATOR_USER_ID)).thenReturn(profile);
        // These tests describe a creator with one connection; the unnarrowed read is the path
        // they already pin, so the account resolves to empty here.
        lenient()
                .when(connectedAccount.currentAccountId(CREATOR_PROFILE_ID))
                .thenReturn(java.util.Optional.empty());
        lenient().when(preferencesService.getOrCreatePreferences(CREATOR_USER_ID)).thenReturn(preferences());
    }

    @Test
    @DisplayName(
            "no metric row: connected=false, every measured string null, and NO quality score is"
                    + " even computed -- an unconnected creator is never given a fabricated number")
    void testNoMetricRowIsHonestlyUnconnected() {
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(List.of());

        MetricsResult metrics = executor.execute(CREATOR_USER_ID, Map.of()).metrics();

        assertFalse(metrics.connected());
        assertNull(metrics.followers());
        assertNull(metrics.reach30d());
        assertNull(metrics.engagementRate());
        assertNull(metrics.avgReachPerPost());
        assertNull(metrics.verifiedAt());
        assertNull(metrics.qualityScore());
        // Tier survives: it is derived from the profile, not from a Meta snapshot.
        assertEquals(CreatorTiers.NANO, metrics.tier());
        // Nothing self-reported either, so there is no data source to name at all.
        assertNull(metrics.dataSource());
        verify(qualityScoreService, never()).calculate(any(), any());
        verify(mediaMetricsRepository, never())
                .findByCreatorProfileIdOrderByTimeDesc(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName(
            "no metric row but a self-reported follower count: data_source is SELF_REPORTED and"
                    + " connected stays false -- the number is labelled, never passed off as verified")
    void testSelfReportedFollowersAreLabelled() {
        // Executor only reads getTotalFollowers()/getEngagementRate() on this path, not
        // followersSource, so the label just needs to be non-VERIFIED for a self-reported number.
        profile.applyFollowerTotals(
                new FollowerTotals(25_000L, new BigDecimal("2.1"), FollowerTotals.IMPORTED));
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(List.of());

        MetricsResult metrics = executor.execute(CREATOR_USER_ID, Map.of()).metrics();

        assertFalse(metrics.connected());
        assertEquals("SELF_REPORTED", metrics.dataSource());
        assertNull(metrics.followers(), "a self-reported count is not a verified follower figure");
        assertEquals(CreatorTiers.MICRO, metrics.tier());
    }

    @Test
    @DisplayName(
            "with a metric row: connected=true, every figure pre-rendered in the creator's locale,"
                    + " and the quality score comes from the two-argument calculate(latest, media)")
    void testConnectedMetricsAreRendered() {
        CreatorMetric metric =
                CreatorMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .platform("INSTAGRAM")
                        .time(Instant.parse("2026-10-05T00:00:00Z"))
                        .followers(120_000L)
                        .avgEngagementRate(new BigDecimal("3.24"))
                        .avgReachPerPost(45_000L)
                        .dataSource("META_GRAPH")
                        .build();
        List<MediaMetric> media = List.of();
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(List.of(metric));
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(media);
        when(qualityScoreService.calculate(Optional.of(metric), media))
                .thenReturn(new QualityScoreResult(new BigDecimal("72"), null, null, null, null));

        MetricsResult metrics = executor.execute(CREATOR_USER_ID, Map.of()).metrics();

        assertTrue(metrics.connected());
        // Rendered by Java, never by Python. Note this is WESTERN grouping, not the Indian lakh
        // grouping "1,20,000" that en-IN is often assumed to give: on this JVM's locale data
        // NumberFormat.getIntegerInstance(en-IN) groups in threes. Pinned deliberately, because a
        // JDK or locale-provider change that flipped it would silently change every rupee figure a
        // creator reads out to a brand.
        assertEquals("120,000", metrics.followers());
        assertEquals("3.2%", metrics.engagementRate());
        assertEquals("45,000", metrics.avgReachPerPost());
        assertEquals("5 Oct 2026", metrics.verifiedAt());
        assertEquals("META_GRAPH", metrics.dataSource());
        assertEquals("72", metrics.qualityScore());
        // No column holds a 30-day reach TOTAL, so this stays null rather than repeating the
        // per-post average under a label that would make it a different, larger claim.
        assertNull(metrics.reach30d());
    }

    @Test
    @DisplayName(
            "an unscored creator gets a null quality_score -- absent(), never a fabricated 0 or the"
                    + " neutral 50 (F-0260)")
    void testUnscoredQualityIsNullNotZero() {
        CreatorMetric metric = minimalMetric();
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(List.of(metric));
        when(mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(List.of());
        when(qualityScoreService.calculate(any(), any())).thenReturn(QualityScoreResult.absent());

        MetricsResult metrics = executor.execute(CREATOR_USER_ID, Map.of()).metrics();

        assertTrue(metrics.connected());
        assertNull(metrics.qualityScore());
    }

    @Test
    @DisplayName(
            "a 1M-follower creator's tier is MEGA -- which is NOT a CreatorTier constant, so the"
                    + " executor must never round-trip it through CreatorTier.valueOf")
    void testMegaTierIsAStringAndNotACreatorTierConstant() {
        profile.applyFollowerTotals(new FollowerTotals(1_500_000L, null, FollowerTotals.IMPORTED));
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(List.of());

        String tier = executor.execute(CREATOR_USER_ID, Map.of()).metrics().tier();

        assertEquals("MEGA", tier);
        // Pinning the hazard: if anything downstream tried CreatorTier.valueOf on this, it would
        // throw. The tier stays a String all the way to the wire.
        assertThrows(IllegalArgumentException.class, () -> CreatorTier.valueOf(tier));
    }

    @Test
    @DisplayName("an explicit admin tier override wins over the derived tier")
    void testTierOverrideWins() {
        profile.applyTierAdjustment(CreatorTier.MACRO, "01HADMIN123456789012");
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                        eq(CREATOR_PROFILE_ID), any(Pageable.class)))
                .thenReturn(List.of());

        assertEquals("MACRO", executor.execute(CREATOR_USER_ID, Map.of()).metrics().tier());
    }

    private static CreatorMetric minimalMetric() {
        return CreatorMetric.builder()
                .id("01HMETRIC1234567890AB")
                .creatorProfileId(CREATOR_PROFILE_ID)
                .platform("INSTAGRAM")
                .time(Instant.parse("2026-10-05T00:00:00Z"))
                .followers(1_000L)
                .build();
    }

    private static PreferencesResponse preferences() {
        return new PreferencesResponse(
                null, null, null, null, List.of(), List.of(), 0, "en-IN", null, null, null, null,
                List.of(), null, false, null, true, null, false, null, false, 0, false);
    }
}
