package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.EvidenceType;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.BaselineStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.BeatsOn;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.GroupStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Metric;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PatternKind;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PostStat;
import com.influora.service.meera.tool.creator.GetMyContentPatternsExecutor;
import com.influora.web.dto.meera.CreatorToolDtos.BaselineMetric;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyContentPatternsResult;
import com.influora.web.dto.meera.CreatorToolDtos.PostReading;
import com.influora.web.dto.meera.CreatorToolDtos.WorkingPattern;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Meera intelligence v1 (spec &sect;3.3-3.4, tests T1-T14) -- {@link CreatorIntelligenceService}
 * over a mocked {@link MediaMetricsRepository}. The repository is mocked to hand back exactly the
 * rows each test wants, so every rule under test runs in the SERVICE. {@link
 * ConnectedInstagramAccount} is the real class over a mocked token repository, so the connection
 * rules (T13) are exercised through the code that ships.
 */
@ExtendWith(MockitoExtension.class)
class CreatorIntelligenceServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Friday 25 Sep 2026, 18:00 IST. */
    private static final Instant NOW = LocalDate.of(2026, 9, 25).atTime(18, 0).atZone(IST).toInstant();
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String PROFILE_ID = "profile1";
    private static final String ACCOUNT = "17841400000000001";
    private static final String OTHER_ACCOUNT = "17841400000000999";

    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private MetaOAuthTokenRepository tokenRepository;

    private CreatorIntelligenceService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorIntelligenceService(
                        mediaMetricsRepository,
                        creatorProfileRepository,
                        new ConnectedInstagramAccount(tokenRepository),
                        tokenRepository);
        CreatorProfile profile = mock(CreatorProfile.class);
        lenient().when(profile.getId()).thenReturn(PROFILE_ID);
        lenient().when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.of(profile));
        lenient()
                .when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.of(token(NOW.plus(Duration.ofDays(30)))));
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private static MetaOAuthToken token(Instant expiresAt) {
        return MetaOAuthToken.builder()
                .id("tok1")
                .creatorProfileId(PROFILE_ID)
                .igBusinessAccountId(ACCOUNT)
                .encryptedAccessToken("enc")
                .expiresAt(expiresAt)
                .build();
    }

    private void stubRows(List<MediaMetric> rows) {
        when(mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccount(eq(PROFILE_ID), any(), eq(ACCOUNT)))
                .thenReturn(rows);
    }

    /** The {@code n}th weekday before NOW, counting from 3 days back (so every post can settle). */
    private static LocalDate weekdayBack(int n) {
        LocalDate d = LocalDate.ofInstant(NOW, IST).minusDays(3);
        int seen = 0;
        while (true) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                if (seen == n) {
                    return d;
                }
                seen++;
            }
            d = d.minusDays(1);
        }
    }

    private static Instant ist(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(IST).toInstant();
    }

    /** One reading row. */
    private static MediaMetric row(
            String rowId,
            String mediaId,
            String type,
            Instant postedAt,
            Instant readAt,
            Long reach,
            Long impressions,
            Long engagement) {
        return MediaMetric.builder()
                .id(rowId)
                .mediaId(mediaId)
                .creatorProfileId(PROFILE_ID)
                .igAccountId(ACCOUNT)
                .platform("INSTAGRAM")
                .mediaType(type)
                .time(readAt)
                .postedAt(postedAt)
                .reach(reach)
                .impressions(impressions)
                .engagement(engagement)
                .permalink("https://www.instagram.com/p/" + mediaId + "/")
                .build();
    }

    /** A settled weekday-evening post: newest reading 72 h after posting. */
    private static MediaMetric settled(String mediaId, int weekdayN, String type, long reach, Long engagement) {
        Instant postedAt = ist(weekdayBack(weekdayN), 19);
        return row("r-" + mediaId, mediaId, type, postedAt, postedAt.plus(Duration.ofHours(72)), reach, reach * 3, engagement);
    }

    private static List<MediaMetric> settledImages(int count, long reach) {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(settled("m" + i, i, "IMAGE", reach, reach / 10));
        }
        return rows;
    }

    private static List<String> ids(List<PostStat> posts) {
        return posts.stream().map(PostStat::postId).toList();
    }

    private static BaselineStat baseline(CreatorIntelligenceProfile p, Metric metric) {
        return p.baseline().stream().filter(b -> b.metric() == metric).findFirst().orElse(null);
    }

    private static Set<String> everyClaimedId(CreatorIntelligenceProfile p) {
        Set<String> all = new HashSet<>();
        p.baseline().forEach(b -> all.addAll(b.evidence().postIds()));
        p.bestPosts().forEach(b -> all.addAll(b.evidence().postIds()));
        p.weakPosts().forEach(b -> all.addAll(b.evidence().postIds()));
        p.whatWorks().forEach(b -> all.addAll(b.evidence().postIds()));
        return all;
    }

    // -----------------------------------------------------------------------------------------
    // Profile resolution
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("no creator profile for the user id is a 404 CREATOR_PROFILE_NOT_FOUND")
    void missingProfileIs404() {
        when(creatorProfileRepository.findByUserId("nobody")).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.profile("nobody", NOW));
        assertEquals("CREATOR_PROFILE_NOT_FOUND", ex.getCode());
    }

    // -----------------------------------------------------------------------------------------
    // T1 -- the <10 guard
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("T1: nine settled posts withhold EVERYTHING, the baseline included, and say how many there are")
    void nineSettledPostsWithholdEverything() {
        stubRows(settledImages(9, 1000));

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertTrue(p.available());
        assertFalse(p.enoughData());
        assertEquals(9, p.settledPosts());
        assertEquals(10, p.minPostsNeeded());
        assertEquals(90, p.lookbackDays());
        assertTrue(p.baseline().isEmpty());
        assertTrue(p.bestPosts().isEmpty());
        assertTrue(p.weakPosts().isEmpty());
        assertTrue(p.whatWorks().isEmpty());
        assertNull(p.asOf());
    }

    @Test
    @DisplayName("T1: exactly ten settled posts is enough -- the boundary is inclusive")
    void tenSettledPostsReport() {
        stubRows(settledImages(10, 1000));

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertTrue(p.enoughData());
        assertEquals(10, p.settledPosts());
        assertEquals(10, baseline(p, Metric.REACH).evidence().sampleSize());
        assertEquals(1000.0, baseline(p, Metric.REACH).median());
        assertNotNull(p.asOf());
    }

    // -----------------------------------------------------------------------------------------
    // T2 / T3 -- group guards
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("T2: a group of two is never reported, however far above the usual it is")
    void groupOfTwoIsNeverReported() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(settled("img" + i, i, "IMAGE", 1000, 100L));
        }
        for (int i = 0; i < 3; i++) {
            rows.add(settled("car" + i, 5 + i, "CAROUSEL_ALBUM", 1000, 100L));
        }
        // Ten times the usual reach -- but only two of them.
        rows.add(settled("vid0", 8, "VIDEO", 10_000, 1000L));
        rows.add(settled("vid1", 9, "REELS", 10_000, 1000L));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertTrue(p.enoughData());
        assertTrue(
                p.whatWorks().stream().noneMatch(g -> g.kind() == PatternKind.POST_TYPE),
                "a two-post group was reported: " + p.whatWorks());
    }

    @Test
    @DisplayName(
            "T3: a creator with only one known post type gets NO post-type claim -- \"Reels beat your"
                    + " usual\" from an all-Reels creator is a tautology")
    void singleTypeCreatorGetsNoTypeClaims() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rows.add(settled("r" + i, i, i % 2 == 0 ? "VIDEO" : "REELS", 1000, 100L));
        }
        // Unknown-type posts sit in the baseline (pulling it down) but in no type group, so the
        // single Reel group's median beats the usual -- only the two-groups rule withholds it.
        for (int i = 0; i < 8; i++) {
            rows.add(settled("u" + i, 8 + i, "UNKNOWN", 100, 10L));
        }
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertTrue(p.enoughData());
        assertEquals(550.0, baseline(p, Metric.REACH).median());
        assertTrue(
                p.whatWorks().stream().noneMatch(g -> g.kind() == PatternKind.POST_TYPE),
                "a single-type creator got a post-type claim: " + p.whatWorks());
    }

    // -----------------------------------------------------------------------------------------
    // T4 / T5 -- the settle rule
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("T4: a post from 47 hours ago is unsettled -- counted, never in a median, list or group")
    void postUnder48hIsUnsettled() {
        List<MediaMetric> rows = new ArrayList<>(settledImages(10, 1000));
        Instant postedAt = NOW.minus(Duration.ofHours(47));
        rows.add(row("r-fresh", "fresh", "IMAGE", postedAt, NOW, 90_000L, 1L, 9000L));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(10, p.settledPosts());
        assertEquals(1, p.unsettledPosts());
        assertFalse(everyClaimedId(p).contains("fresh"));
        assertEquals(1000.0, baseline(p, Metric.REACH).median());
    }

    @Test
    @DisplayName(
            "T5: a 10-day-old post whose NEWEST reading was taken 6 h after posting is unsettled --"
                    + " settled is reading time minus posted time, not now minus posted time")
    void staleReadingIsUnsettled() {
        List<MediaMetric> rows = new ArrayList<>(settledImages(10, 1000));
        Instant postedAt = NOW.minus(Duration.ofDays(10));
        rows.add(row("r-stale", "stale", "IMAGE", postedAt, postedAt.plus(Duration.ofHours(6)), 50L, 1L, 5L));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(10, p.settledPosts());
        assertEquals(1, p.unsettledPosts());
        assertFalse(everyClaimedId(p).contains("stale"), "an early snapshot was counted as a result");
        assertEquals(10, baseline(p, Metric.REACH).evidence().sampleSize());
    }

    // -----------------------------------------------------------------------------------------
    // T6 -- one reading per post
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "T6: three readings of one post (two with an identical time) count once -- the newest, and"
                    + " on a tie the later row id")
    void duplicateSnapshotsCountOnce() {
        List<MediaMetric> rows = new ArrayList<>(settledImages(9, 1000));
        Instant postedAt = ist(weekdayBack(12), 19);
        Instant older = postedAt.plus(Duration.ofHours(50));
        Instant newest = postedAt.plus(Duration.ofHours(80));
        rows.add(row("01H0000000000000000000000A", "dup", "IMAGE", postedAt, older, 400L, 1L, 40L));
        rows.add(row("01H0000000000000000000000B", "dup", "IMAGE", postedAt, newest, 700L, 1L, 70L));
        rows.add(row("01H0000000000000000000000C", "dup", "IMAGE", postedAt, newest, 900L, 1L, 90L));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(10, p.settledPosts());
        List<String> reachIds = baseline(p, Metric.REACH).evidence().postIds();
        assertEquals(10, reachIds.size());
        assertEquals(10, new HashSet<>(reachIds).size());
        PostStat dup =
                p.weakPosts().stream().filter(w -> w.postId().equals("dup")).findFirst().orElseThrow();
        assertEquals(900L, dup.reach(), "the tie must go to the later row id, whatever the row order");
    }

    // -----------------------------------------------------------------------------------------
    // T7 -- Reels reported as VIDEO
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("T7: VIDEO and REELS are ONE group, \"Reels and videos\"")
    void reelsReportedAsVideoGroupWithReels() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(settled("v" + i, i, "VIDEO", 2000, 200L));
            rows.add(settled("r" + i, 3 + i, "REELS", 2000, 200L));
        }
        for (int i = 0; i < 6; i++) {
            rows.add(settled("i" + i, 6 + i, "IMAGE", 1000, 100L));
        }
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        GroupStat reels =
                p.whatWorks().stream()
                        .filter(g -> g.kind() == PatternKind.POST_TYPE)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no post-type claim: " + p.whatWorks()));
        assertEquals("Reels and videos", reels.label());
        assertEquals(6, reels.posts());
        assertEquals(6, reels.evidence().sampleSize());
        assertTrue(reels.beatsOn().contains(BeatsOn.REACH));
        assertTrue(p.bestPosts().stream().allMatch(b -> b.type() == ChallengeDayType.REEL));
    }

    // -----------------------------------------------------------------------------------------
    // T8 -- hidden likes
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("T8: interactions come from engagement (total_interactions), never likes -- hidden likes change nothing")
    void hiddenLikesUseInteractions() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant postedAt = ist(weekdayBack(i), 19);
            rows.add(
                    MediaMetric.builder()
                            .id("r" + i)
                            .mediaId("m" + i)
                            .creatorProfileId(PROFILE_ID)
                            .platform("INSTAGRAM")
                            .mediaType("IMAGE")
                            .time(postedAt.plus(Duration.ofHours(72)))
                            .postedAt(postedAt)
                            .reach(1000L)
                            .engagement(300L)
                            .likes(null)
                            .comments(7L)
                            .build());
        }
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(300.0, baseline(p, Metric.INTERACTIONS).median());
        assertEquals(0.3, baseline(p, Metric.ENGAGEMENT_RATE).median(), 1e-12);
    }

    @Test
    @DisplayName(
            "T8: a post with unknown interactions stays in the reach baseline and is left OUT of the"
                    + " interactions and ER samples -- it is not a zero")
    void nullEngagementIsNotZero() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(settled("m" + i, i, "IMAGE", 1000, 100L));
        }
        rows.add(settled("hidden0", 10, "IMAGE", 1000, null));
        rows.add(settled("hidden1", 11, "IMAGE", 1000, null));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(12, baseline(p, Metric.REACH).evidence().sampleSize());
        BaselineStat interactions = baseline(p, Metric.INTERACTIONS);
        assertEquals(10, interactions.evidence().sampleSize());
        assertFalse(interactions.evidence().postIds().contains("hidden0"));
        assertEquals(10, baseline(p, Metric.ENGAGEMENT_RATE).evidence().sampleSize());
        assertEquals(0.1, baseline(p, Metric.ENGAGEMENT_RATE).median(), 1e-12);
        p.bestPosts().stream()
                .filter(b -> b.postId().startsWith("hidden"))
                .forEach(b -> assertNull(b.engagementRate(), "unknown interactions rendered as a rate"));
    }

    // -----------------------------------------------------------------------------------------
    // T9 / T10 -- views and medians
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("T9: views come from the impressions column; video_views (retired by Meta) is null everywhere")
    void viewsComeFromImpressionsColumn() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant postedAt = ist(weekdayBack(i), 19);
            rows.add(
                    MediaMetric.builder()
                            .id("r" + i)
                            .mediaId("m" + i)
                            .creatorProfileId(PROFILE_ID)
                            .platform("INSTAGRAM")
                            .mediaType("VIDEO")
                            .time(postedAt.plus(Duration.ofHours(72)))
                            .postedAt(postedAt)
                            .reach(1000L)
                            .impressions(5000L)
                            .videoViews(null)
                            .engagement(50L)
                            .build());
        }
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        BaselineStat views = baseline(p, Metric.VIEWS);
        assertNotNull(views, "VIEWS was omitted -- it is being read from video_views");
        assertEquals(5000.0, views.median());
        assertEquals(10, views.evidence().sampleSize());
    }

    @Test
    @DisplayName("T10: the usual is a MEDIAN -- one 50x outlier does not become \"your usual\"")
    void medianNotMean() {
        List<MediaMetric> rows = new ArrayList<>(settledImages(10, 1000));
        rows.add(settled("viral", 10, "IMAGE", 50_000, 5000L));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(1000.0, baseline(p, Metric.REACH).median());
        assertEquals("viral", p.bestPosts().get(0).postId());
        assertEquals(50.0, p.bestPosts().get(0).reachRatio(), 1e-12);
    }

    @Test
    @DisplayName("median of an even count is the mean of the two middle values")
    void evenMedian() {
        assertEquals(2.5, CreatorIntelligenceService.median(List.of(4.0, 1.0, 3.0, 2.0)));
        assertEquals(3.0, CreatorIntelligenceService.median(List.of(5.0, 3.0, 1.0)));
    }

    // -----------------------------------------------------------------------------------------
    // T11 -- best and weak
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "T11: best and weak posts never overlap and do not depend on row order -- ties go to the"
                    + " newer post, then the lower id")
    void bestAndWeakAreDisjointAndDeterministic() {
        List<MediaMetric> rows = new ArrayList<>();
        long[] reaches = {3000, 3000, 3000, 3000, 1000, 1000, 1000, 500, 500, 500, 500, 2000};
        for (int i = 0; i < reaches.length; i++) {
            rows.add(settled(String.format("m%02d", i), i, "IMAGE", reaches[i], reaches[i] / 10));
        }
        // Same day, same reach: only the id can break this tie.
        Instant sameTime = ist(weekdayBack(0), 19);
        rows.add(row("r-a", "tieA", "IMAGE", sameTime, sameTime.plus(Duration.ofHours(72)), 3000L, 1L, 300L));

        List<List<String>> bestRuns = new ArrayList<>();
        List<List<String>> weakRuns = new ArrayList<>();
        Random random = new Random(42);
        for (int run = 0; run < 6; run++) {
            List<MediaMetric> shuffled = new ArrayList<>(rows);
            Collections.shuffle(shuffled, random);
            when(mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccount(eq(PROFILE_ID), any(), eq(ACCOUNT)))
                    .thenReturn(shuffled);
            CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);
            bestRuns.add(ids(p.bestPosts()));
            weakRuns.add(ids(p.weakPosts()));

            Set<String> overlap = new HashSet<>(ids(p.bestPosts()));
            overlap.retainAll(ids(p.weakPosts()));
            assertTrue(overlap.isEmpty(), "a post is in both lists: " + overlap);
            p.bestPosts().forEach(b -> assertTrue(b.reachRatio() > 1.0));
            p.weakPosts().forEach(w -> assertTrue(w.reachRatio() < 1.0));
        }
        for (int run = 1; run < bestRuns.size(); run++) {
            assertEquals(bestRuns.get(0), bestRuns.get(run), "best posts changed with row order");
            assertEquals(weakRuns.get(0), weakRuns.get(run), "weak posts changed with row order");
        }
        // m00 and tieA share the newest date and 3000 reach; "m00" < "tieA".
        assertEquals(List.of("m00", "tieA", "m01"), bestRuns.get(0));
        assertEquals(List.of("m07", "m08", "m09"), weakRuns.get(0));
    }

    @Test
    void postExactlyAtTheUsualIsNeitherBestNorWeak() {
        // Median reach is 1,000; ten posts sit exactly on it. A "best post" that is "+0%" against
        // the creator's usual would read as praise for an ordinary post, so it lists nowhere.
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(settled(String.format("u%02d", i), i, "IMAGE", 1000, 100L));
        }
        rows.add(settled("high", 10, "IMAGE", 2000, 200L));
        rows.add(settled("low", 11, "IMAGE", 500, 50L));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(List.of("high"), ids(p.bestPosts()));
        assertEquals(List.of("low"), ids(p.weakPosts()));
    }

    // -----------------------------------------------------------------------------------------
    // T12 / T13 -- account and connection
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "T12: only the account connected today is read -- the narrowed query, never the"
                    + " un-narrowed one that mixes every account the profile ever had")
    void onlyConnectedAccountRows() {
        List<MediaMetric> accountA = settledImages(10, 1000);
        List<MediaMetric> mixed = new ArrayList<>(accountA);
        for (int i = 0; i < 5; i++) {
            mixed.add(settled("other" + i, 10 + i, "IMAGE", 90_000, 9000L));
        }
        stubRows(accountA);
        lenient().when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any())).thenReturn(mixed);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(10, p.settledPosts());
        assertTrue(everyClaimedId(p).stream().noneMatch(id -> id.startsWith("other")));
        verify(mediaMetricsRepository).findNewestSnapshotPerPostSinceForAccount(eq(PROFILE_ID), any(), eq(ACCOUNT));
        verify(mediaMetricsRepository, never()).findNewestSnapshotPerPostSince(any(), any());
        verify(mediaMetricsRepository, never())
                .findNewestSnapshotPerPostSinceForAccount(any(), any(), eq(OTHER_ACCOUNT));
    }

    @Test
    @DisplayName(
            "T13: an EXPIRED token is not connected -- available=false, NOT_CONNECTED, and no post is"
                    + " read (currentAccountId alone still returns the account for it)")
    void expiredTokenIsNotConnected() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.of(token(NOW.minus(Duration.ofHours(1)))));

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertFalse(p.available());
        assertEquals("NOT_CONNECTED", p.reason());
        assertFalse(p.enoughData());
        assertTrue(p.baseline().isEmpty());
        assertTrue(p.bestPosts().isEmpty());
        verifyNoInteractions(mediaMetricsRepository);
    }

    @Test
    @DisplayName("T13: no live token at all (never connected or revoked) is NOT_CONNECTED too")
    void noTokenIsNotConnected() {
        when(tokenRepository.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(Optional.empty());

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertFalse(p.available());
        assertEquals("NOT_CONNECTED", p.reason());
        verifyNoInteractions(mediaMetricsRepository);
    }

    @Test
    @DisplayName("a post with null or zero reach is dropped (no denominator); a null posted_at is dropped")
    void zeroReachAndNullPostedAtAreDropped() {
        List<MediaMetric> rows = new ArrayList<>(settledImages(10, 1000));
        rows.add(settled("zero", 10, "IMAGE", 0, 0L));
        rows.add(row("r-nopost", "nopost", "IMAGE", null, NOW, 5000L, 1L, 1L));
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(10, p.settledPosts());
        assertFalse(everyClaimedId(p).contains("zero"));
        assertFalse(everyClaimedId(p).contains("nopost"));
    }

    @Test
    @DisplayName("posting windows: a window group beats the usual when two or more windows have 3+ posts")
    void windowGroupIsReported() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Instant postedAt = ist(weekdayBack(i), 8); // weekday morning
            rows.add(row("m" + i, "morning" + i, "IMAGE", postedAt, postedAt.plus(Duration.ofHours(72)), 3000L, 1L, 300L));
        }
        for (int i = 0; i < 6; i++) {
            rows.add(settled("eve" + i, 6 + i, "IMAGE", 1000, 100L)); // weekday evening
        }
        stubRows(rows);

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        GroupStat morning =
                p.whatWorks().stream()
                        .filter(g -> g.kind() == PatternKind.POSTING_WINDOW)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no window claim: " + p.whatWorks()));
        assertEquals("weekday morning", morning.label());
        assertEquals(6, morning.posts());
        assertEquals(1.5, morning.reachLift(), 1e-12);
    }

    // -----------------------------------------------------------------------------------------
    // T14 -- evidence on every claim, no confidence anywhere
    // -----------------------------------------------------------------------------------------

    /** Types AND windows both reported, best and weak both populated. */
    private static List<MediaMetric> richRows() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Instant postedAt = ist(weekdayBack(i), 8);
            rows.add(row("a" + i, "reelAm" + i, i % 2 == 0 ? "REELS" : "VIDEO", postedAt, postedAt.plus(Duration.ofHours(72)), 4000L, 20000L, 400L));
        }
        for (int i = 0; i < 4; i++) {
            rows.add(settled("img" + i, 4 + i, "IMAGE", 1000, 100L));
        }
        for (int i = 0; i < 4; i++) {
            rows.add(settled("car" + i, 8 + i, "CAROUSEL_ALBUM", 800, 120L));
        }
        return rows;
    }

    @Test
    @DisplayName(
            "T14: every claim carries evidence -- CREATOR_POST_DATA, sample_size == post_ids.size() on"
                    + " baseline and groups, 1 + baseline n on posts -- and no field anywhere is a confidence")
    void everyClaimCarriesEvidence() {
        stubRows(richRows());

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);
        assertTrue(p.enoughData());
        assertFalse(p.baseline().isEmpty());
        assertFalse(p.bestPosts().isEmpty());
        assertFalse(p.weakPosts().isEmpty());
        assertTrue(p.whatWorks().stream().anyMatch(g -> g.kind() == PatternKind.POST_TYPE));
        assertTrue(p.whatWorks().stream().anyMatch(g -> g.kind() == PatternKind.POSTING_WINDOW));

        for (BaselineStat b : p.baseline()) {
            assertEquals(EvidenceType.CREATOR_POST_DATA, b.evidence().type());
            assertFalse(b.evidence().postIds().isEmpty());
            assertNull(b.evidence().baselineSampleSize());
        }
        for (GroupStat g : p.whatWorks()) {
            assertEquals(EvidenceType.CREATOR_POST_DATA, g.evidence().type());
            assertEquals(g.posts(), g.evidence().postIds().size());
        }

        // The rendered wire record: the same rules, on what actually leaves the backend.
        GetMyContentPatternsResult wire = GetMyContentPatternsExecutor.render(p, Locale.forLanguageTag("en-IN"));
        for (BaselineMetric b : wire.baseline()) {
            assertEquals("CREATOR_POST_DATA", b.evidence().type());
            assertEquals(b.evidence().postIds().size(), b.evidence().sampleSize());
            assertNull(b.evidence().baselineSampleSize());
        }
        List<PostReading> posts = new ArrayList<>(wire.bestPosts());
        posts.addAll(wire.weakPosts());
        for (PostReading post : posts) {
            assertEquals("CREATOR_POST_DATA", post.evidence().type());
            assertEquals(1, post.evidence().sampleSize());
            assertEquals(List.of(post.postId()), post.evidence().postIds());
            assertEquals(p.settledPosts(), post.evidence().baselineSampleSize());
        }
        for (WorkingPattern w : wire.whatWorks()) {
            assertEquals("CREATOR_POST_DATA", w.evidence().type());
            assertEquals(w.posts(), w.evidence().sampleSize());
            assertEquals(w.evidence().postIds().size(), w.evidence().sampleSize());
        }

        List<String> offenders = new ArrayList<>();
        findConfidence(CreatorIntelligenceProfile.class, new HashSet<>(), offenders);
        findConfidence(GetMyContentPatternsResult.class, new HashSet<>(), offenders);
        assertTrue(offenders.isEmpty(), "a confidence field exists: " + offenders);
    }

    /** Walks every record component (and generic argument) reachable from {@code type}. */
    private static void findConfidence(Type type, Set<Type> seen, List<String> offenders) {
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                findConfidence(argument, seen, offenders);
            }
            return;
        }
        if (!(type instanceof Class<?> clazz) || !seen.add(clazz) || !clazz.isRecord()) {
            return;
        }
        for (RecordComponent component : clazz.getRecordComponents()) {
            String json =
                    component.getAccessor().getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class) == null
                            ? ""
                            : component
                                    .getAccessor()
                                    .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class)
                                    .value();
            if (component.getName().toLowerCase(Locale.ROOT).contains("confidence")
                    || json.toLowerCase(Locale.ROOT).contains("confidence")) {
                offenders.add(clazz.getSimpleName() + "." + component.getName());
            }
            findConfidence(component.getGenericType(), seen, offenders);
        }
    }

    @Test
    @DisplayName("Kabir M-1: a creator who has connected two Instagram accounts gets only rows"
            + " tagged with the current account -- untagged legacy rows could be the old account's")
    void accountSwitcherGetsOnlyTaggedRowsOfTheCurrentAccount() {
        when(tokenRepository.countDistinctCreatorIgAccounts(PROFILE_ID)).thenReturn(2L);
        when(mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccountTaggedOnly(
                        eq(PROFILE_ID), any(), eq(ACCOUNT)))
                .thenReturn(settledImages(10, 1000));

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(10, p.settledPosts());
        verify(mediaMetricsRepository, never()).findNewestSnapshotPerPostSinceForAccount(any(), any(), any());
    }

    @Test
    @DisplayName("Kabir M-1: a creator with one Instagram account keeps her untagged legacy rows")
    void singleAccountCreatorKeepsUntaggedRows() {
        when(tokenRepository.countDistinctCreatorIgAccounts(PROFILE_ID)).thenReturn(1L);
        stubRows(settledImages(10, 1000));

        CreatorIntelligenceProfile p = service.profile(CREATOR_USER_ID, NOW);

        assertEquals(10, p.settledPosts());
        verify(mediaMetricsRepository, never()).findNewestSnapshotPerPostSinceForAccountTaggedOnly(any(), any(), any());
    }
}
