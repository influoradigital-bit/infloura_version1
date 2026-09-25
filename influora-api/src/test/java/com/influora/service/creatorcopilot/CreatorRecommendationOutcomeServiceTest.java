package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.FollowedStat;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meera intelligence v1, slice 2 (spec 8.4) -- matching, missing, settling and summarising a
 * creator's recommendations, over an in-memory recommendation store and mocked readings. Every
 * rule runs in {@link CreatorRecommendationOutcomeService}; nothing is precomputed by the test.
 */
class CreatorRecommendationOutcomeServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Friday 25 Sep 2026, 18:00 IST. */
    private static final Instant NOW = LocalDate.of(2026, 9, 25).atTime(18, 0).atZone(IST).toInstant();
    private static final String USER = "01HCREATORUSER1234567A";
    private static final String PROFILE_ID = "profile1";
    private static final String ACCOUNT = "17841400000000001";
    private static final Duration SETTLED_READ = Duration.ofHours(60);

    private MediaMetricsRepository media;
    private List<CreatorRecommendation> store;
    private CreatorRecommendationRepository repository;
    private CreatorRecommendationOutcomeService service;
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        media = mock(MediaMetricsRepository.class);
        store = new ArrayList<>();
        repository = inMemory(store);
        service = new CreatorRecommendationOutcomeService(repository, media, new CreatorRecommendationOutcomeWriter(repository));
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    static CreatorRecommendationRepository inMemory(List<CreatorRecommendation> store) {
        CreatorRecommendationRepository repo = mock(CreatorRecommendationRepository.class);
        Comparator<CreatorRecommendation> order =
                Comparator.comparing(CreatorRecommendation::getCreatedAt).thenComparing(CreatorRecommendation::getId);
        when(repo.findByCreatorProfileIdAndStatusInOrderByCreatedAtAscIdAsc(any(), any()))
                .thenAnswer(
                        inv -> {
                            Collection<CreatorRecommendationStatus> statuses = inv.getArgument(1);
                            return store.stream()
                                    .filter(r -> r.getCreatorProfileId().equals(inv.getArgument(0)))
                                    .filter(r -> statuses.contains(r.getStatus()))
                                    .sorted(order)
                                    .toList();
                        });
        when(repo.findClaimedMediaIds(any()))
                .thenAnswer(
                        inv ->
                                store.stream()
                                        .filter(r -> r.getCreatorProfileId().equals(inv.getArgument(0)))
                                        .map(CreatorRecommendation::getMatchedMediaId)
                                        .filter(Objects::nonNull)
                                        .toList());
        when(repo.findByCreatorProfileIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(any(), any()))
                .thenAnswer(
                        inv -> {
                            Instant since = inv.getArgument(1);
                            return store.stream()
                                    .filter(r -> r.getCreatorProfileId().equals(inv.getArgument(0)))
                                    .filter(r -> !r.getCreatedAt().isBefore(since))
                                    .sorted(order)
                                    .toList();
                        });
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        // The per-row writer re-reads the row by id and saves it: rows mutate in place.
        when(repo.findById(any()))
                .thenAnswer(inv -> store.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    private static Instant ist(int month, int day, int hour, int minute) {
        return LocalDate.of(2026, month, day).atTime(hour, minute).atZone(IST).toInstant();
    }

    private static MediaMetric post(String mediaId, String type, Instant postedAt, Duration readAfter, Long reach) {
        return MediaMetric.builder()
                .id("r-" + mediaId)
                .mediaId(mediaId)
                .creatorProfileId(PROFILE_ID)
                .igAccountId(ACCOUNT)
                .platform("INSTAGRAM")
                .mediaType(type)
                .postedAt(postedAt)
                .time(postedAt.plus(readAfter))
                .reach(reach)
                .engagement(reach == null ? null : reach / 10)
                .build();
    }

    private void stubPosts(List<MediaMetric> rows) {
        when(media.findNewestSnapshotPerPostSinceForAccount(eq(PROFILE_ID), any(), eq(ACCOUNT))).thenReturn(rows);
    }

    private CreatorRecommendation plan(
            LocalDate day, ChallengeDayType type, String label, LocalTime from, LocalTime to, Instant createdAt) {
        String n = String.format("%02d", ids.incrementAndGet());
        CreatorRecommendation r =
                CreatorRecommendation.open(
                        "rec" + n, USER, PROFILE_ID, CreatorRecommendationSource.PLAN_MY_WEEK, "msg:" + n, "conv",
                        day, day.plusDays(1), type, label, from, to, null, null, null, null, null, null, createdAt);
        store.add(r);
        return r;
    }

    private CreatorRecommendation plan(LocalDate day, ChallengeDayType type) {
        return plan(day, type, null, null, null, ist(9, 1, 9, 0));
    }

    private CreatorRecommendation script(ChallengeDayType type, Instant createdAt) {
        String n = String.format("%02d", ids.incrementAndGet());
        LocalDate createdDay = LocalDate.ofInstant(createdAt, IST);
        CreatorRecommendation r =
                CreatorRecommendation.open(
                        "rec" + n, USER, PROFILE_ID, CreatorRecommendationSource.SCRIPT_CARD, "msg:" + n, "conv",
                        null, createdDay.plusDays(CreatorRecommendationService.SCRIPT_MATCH_DAYS), type,
                        null, null, null, null, null, null, null, null, null, createdAt);
        store.add(r);
        return r;
    }

    /** {@code count} settled IMAGE posts, one a day, ending the day before {@code before}. */
    private static List<MediaMetric> priorPosts(Instant before, int count, long firstReach) {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant postedAt = before.minus(Duration.ofDays(i + 1));
            rows.add(post("prior" + i, "IMAGE", postedAt, Duration.ofHours(72), firstReach + 100L * i));
        }
        return rows;
    }

    // ---------------------------------------------------------------------------------------
    // Matching
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "type match: VIDEO fills a REEL day (matched_type true); a photo on a CAROUSEL day still"
                    + " fills it, with matched_type false -- the challenge's any-post-counts rule")
    void typeMatch() {
        CreatorRecommendation reel = plan(LocalDate.of(2026, 9, 21), ChallengeDayType.REEL);
        CreatorRecommendation carousel = plan(LocalDate.of(2026, 9, 22), ChallengeDayType.CAROUSEL);
        stubPosts(
                List.of(
                        post("v1", "VIDEO", ist(9, 21, 19, 0), SETTLED_READ, 1000L),
                        post("i1", "IMAGE", ist(9, 22, 19, 0), SETTLED_READ, 1000L)));

        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        assertEquals("v1", reel.getMatchedMediaId());
        assertEquals(Boolean.TRUE, reel.getMatchedType());
        assertEquals("i1", carousel.getMatchedMediaId());
        assertEquals(Boolean.FALSE, carousel.getMatchedType());
        assertEquals(CreatorRecommendationStatus.SETTLED, reel.getStatus());
    }

    @Test
    @DisplayName(
            "window flag: label equality, the challenge's daypart-only label, the [from, to) range"
                    + " (wrapping midnight), null when no window was recommended")
    void windowMatchFlag() {
        Instant weekdayEvening = ist(9, 22, 19, 40); // Tuesday
        assertEquals(true, CreatorRecommendationOutcomeService.windowMatches(weekdayEvening, "weekday evening", null, null));
        assertEquals(true, CreatorRecommendationOutcomeService.windowMatches(weekdayEvening, "evening", null, null));
        assertEquals(false, CreatorRecommendationOutcomeService.windowMatches(weekdayEvening, "weekend evening", null, null));
        assertEquals(false, CreatorRecommendationOutcomeService.windowMatches(weekdayEvening, "morning", null, null));
        assertEquals(
                true,
                CreatorRecommendationOutcomeService.windowMatches(
                        weekdayEvening, "weekend evening", LocalTime.of(19, 0), LocalTime.of(20, 0)),
                "the IST time inside [from, to) is enough");
        assertEquals(
                false,
                CreatorRecommendationOutcomeService.windowMatches(weekdayEvening, null, LocalTime.of(19, 40).plusSeconds(1), LocalTime.of(21, 0)));
        assertEquals(
                false,
                CreatorRecommendationOutcomeService.windowMatches(weekdayEvening, null, LocalTime.of(18, 0), LocalTime.of(19, 40)),
                "to is exclusive");
        LocalTime night = LocalTime.of(22, 0);
        LocalTime dawn = LocalTime.of(5, 0);
        assertEquals(true, CreatorRecommendationOutcomeService.windowMatches(ist(9, 22, 23, 30), null, night, dawn));
        assertEquals(true, CreatorRecommendationOutcomeService.windowMatches(ist(9, 23, 2, 0), null, night, dawn));
        assertEquals(false, CreatorRecommendationOutcomeService.windowMatches(ist(9, 23, 6, 0), null, night, dawn));
        assertNull(CreatorRecommendationOutcomeService.windowMatches(weekdayEvening, null, null, null));

        CreatorRecommendation inWindow = plan(LocalDate.of(2026, 9, 22), ChallengeDayType.REEL, "weekday evening", null, null, ist(9, 1, 9, 0));
        CreatorRecommendation offWindow = plan(LocalDate.of(2026, 9, 23), ChallengeDayType.REEL, "weekday evening", null, null, ist(9, 1, 9, 0));
        stubPosts(
                List.of(
                        post("a", "REELS", weekdayEvening, SETTLED_READ, 1000L),
                        post("b", "REELS", ist(9, 23, 8, 30), SETTLED_READ, 1000L)));
        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);
        assertEquals(Boolean.TRUE, inWindow.getMatchedWindow());
        assertEquals(Boolean.FALSE, offWindow.getMatchedWindow());
    }

    @Test
    @DisplayName(
            "one post fills at most one recommendation: the earlier-created row takes it, the other"
                    + " stays unfilled; a post already claimed by a stored row is never reused")
    void onePostOneRecommendation() {
        LocalDate day = LocalDate.of(2026, 9, 22);
        CreatorRecommendation later = plan(day, ChallengeDayType.REEL, null, null, null, ist(9, 20, 12, 0));
        CreatorRecommendation earlier = plan(day, ChallengeDayType.REEL, null, null, null, ist(9, 20, 11, 0));
        CreatorRecommendation claimedElsewhere = plan(LocalDate.of(2026, 9, 23), ChallengeDayType.REEL);
        CreatorRecommendation holder =
                CreatorRecommendation.open(
                        "holder", USER, PROFILE_ID, CreatorRecommendationSource.CHALLENGE, "ch:0", null,
                        LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24), ChallengeDayType.REEL,
                        null, null, null, null, null, null, null, null, null, ist(9, 20, 8, 0));
        holder.matchTo("taken", true, null, ACCOUNT);
        holder.settle(1000L, 100L, null, 3, null, NOW);
        store.add(holder);
        stubPosts(
                List.of(
                        post("only", "REELS", ist(9, 22, 19, 0), SETTLED_READ, 1000L),
                        post("taken", "REELS", ist(9, 23, 9, 0), SETTLED_READ, 1000L)));

        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        assertEquals("only", earlier.getMatchedMediaId());
        assertNull(later.getMatchedMediaId());
        assertEquals(CreatorRecommendationStatus.MISSED, later.getStatus(), "its day passed + 12 h with no free post");
        assertNull(claimedElsewhere.getMatchedMediaId(), "a post already filling a row is never reused");
        assertEquals(1, store.stream().filter(r -> "only".equals(r.getMatchedMediaId())).count());
    }

    @Test
    @DisplayName(
            "MISSED exactly at recommended_for + 1 day + 12 h (the challenge's CHECKING_GRACE), not"
                    + " a nanosecond earlier")
    void missedTiming() {
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 23), ChallengeDayType.POST);
        stubPosts(List.of());
        Instant deadline = LocalDate.of(2026, 9, 24).atTime(12, 0).atZone(IST).toInstant();

        service.evaluate(PROFILE_ID, ACCOUNT, false, deadline.minusNanos(1));
        assertEquals(CreatorRecommendationStatus.OPEN, rec.getStatus());

        service.evaluate(PROFILE_ID, ACCOUNT, false, deadline);
        assertEquals(CreatorRecommendationStatus.MISSED, rec.getStatus());
    }

    @Test
    @DisplayName(
            "script card: filled by a post in the 7 IST days [created, created + 7); a post before"
                    + " the recommendation day or on day 8 does not count; missed at day 8 + 12 h")
    void scriptSevenDayWindow() {
        CreatorRecommendation filled = script(ChallengeDayType.REEL, ist(9, 14, 10, 0));
        stubPosts(
                List.of(
                        post("before", "REELS", ist(9, 13, 20, 0), SETTLED_READ, 1000L),
                        post("day7", "REELS", ist(9, 20, 23, 0), SETTLED_READ, 1000L)));
        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);
        assertEquals("day7", filled.getMatchedMediaId());

        store.clear();
        CreatorRecommendation tooLate = script(ChallengeDayType.REEL, ist(9, 14, 10, 0));
        stubPosts(List.of(post("day8", "REELS", ist(9, 21, 0, 30), SETTLED_READ, 1000L)));
        Instant grace = LocalDate.of(2026, 9, 21).atTime(12, 0).atZone(IST).toInstant();
        service.evaluate(PROFILE_ID, ACCOUNT, false, grace.minusNanos(1));
        assertNull(tooLate.getMatchedMediaId());
        assertEquals(CreatorRecommendationStatus.OPEN, tooLate.getStatus());
        service.evaluate(PROFILE_ID, ACCOUNT, false, grace);
        assertEquals(CreatorRecommendationStatus.MISSED, tooLate.getStatus());
    }

    @Test
    @DisplayName(
            "account-switcher: profile() evaluates with the TAGGED-ONLY query, so a legacy untagged"
                    + " post (possibly the old account's) never fills a recommendation")
    void accountSwitcherRule() {
        CreatorProfileRepository profiles = mock(CreatorProfileRepository.class);
        MetaOAuthTokenRepository tokens = mock(MetaOAuthTokenRepository.class);
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getId()).thenReturn(PROFILE_ID);
        when(profiles.findByUserId(USER)).thenReturn(Optional.of(profile));
        when(tokens.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(
                        Optional.of(
                                MetaOAuthToken.builder()
                                        .id("tok")
                                        .creatorProfileId(PROFILE_ID)
                                        .igBusinessAccountId(ACCOUNT)
                                        .encryptedAccessToken("enc")
                                        .expiresAt(NOW.plus(Duration.ofDays(30)))
                                        .build()));
        when(tokens.countDistinctCreatorIgAccounts(PROFILE_ID)).thenReturn(2L);
        MediaMetric legacy = post("legacy", "REELS", ist(9, 22, 9, 0), SETTLED_READ, 1000L);
        MediaMetric current = post("current", "REELS", ist(9, 22, 19, 0), SETTLED_READ, 1000L);
        when(media.findNewestSnapshotPerPostSinceForAccount(anyString(), any(), anyString())).thenReturn(List.of(legacy, current));
        when(media.findNewestSnapshotPerPostSinceForAccountTaggedOnly(eq(PROFILE_ID), any(), eq(ACCOUNT)))
                .thenReturn(List.of(current));
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 22), ChallengeDayType.REEL);

        new CreatorIntelligenceService(media, profiles, new ConnectedInstagramAccount(tokens), tokens, service)
                .profile(USER, NOW);

        assertEquals("current", rec.getMatchedMediaId());
        verify(media, never()).findNewestSnapshotPerPostSinceForAccount(anyString(), any(), anyString());
    }

    // ---------------------------------------------------------------------------------------
    // Settle
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "settle is the READING-time rule: a 5-day-old post whose newest reading was taken 6 h"
                    + " after posting stays MATCHED (now - posted_at would wrongly settle it)")
    void settleUsesReadingTime() {
        // Inside the NO_OUTCOME grace (match_until 21 Sep + 48 h + 7 d = 30 Sep > NOW), so only the
        // settle rule decides: now - posted_at is ~5 days, the newest reading only 6 h.
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 20), ChallengeDayType.REEL);
        stubPosts(List.of(post("stale", "REELS", ist(9, 20, 19, 0), Duration.ofHours(6), 800L)));

        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        assertEquals("stale", rec.getMatchedMediaId());
        assertEquals(CreatorRecommendationStatus.MATCHED, rec.getStatus());
        assertNull(rec.getSettledAt());
    }

    @Test
    @DisplayName(
            "baseline AS OF THE POST: median reach of settled valid posts in [posted_at - 90 d,"
                    + " posted_at), excluding the post itself, later posts, older posts, unsettled and"
                    + " zero-reach posts; pct = round((reach / median - 1) * 100)")
    void baselineAsOfPostExcludesThePostAndFuturePosts() {
        Instant postedAt = ist(9, 15, 19, 0);
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 15), ChallengeDayType.REEL);
        List<MediaMetric> rows = new ArrayList<>(priorPosts(postedAt, 12, 1000)); // 1000..2100, median 1550
        rows.add(post("thepost", "REELS", postedAt, SETTLED_READ, 3100L));
        rows.add(post("later1", "REELS", postedAt.plus(Duration.ofHours(1)), SETTLED_READ, 900_000L));
        rows.add(post("later2", "REELS", ist(9, 20, 9, 0), SETTLED_READ, 900_000L));
        rows.add(post("tooOld", "IMAGE", postedAt.minus(Duration.ofDays(90)).minusSeconds(1), SETTLED_READ, 1L));
        rows.add(post("unsettledPrior", "IMAGE", postedAt.minus(Duration.ofDays(20)), Duration.ofHours(6), 1L));
        rows.add(post("zeroReach", "IMAGE", postedAt.minus(Duration.ofDays(30)), SETTLED_READ, 0L));
        stubPosts(rows);

        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        assertEquals(CreatorRecommendationStatus.SETTLED, rec.getStatus());
        assertEquals("thepost", rec.getMatchedMediaId());
        assertEquals(12, rec.getBaselineSampleSize());
        assertEquals(1550L, rec.getBaselineMedianReach());
        assertEquals(100, rec.getReachVsBaselinePct(), "3100 / 1550 = 2.0 -> +100%");
        assertEquals(3100L, rec.getReach());
        assertEquals(310L, rec.getEngagement());
        assertEquals(NOW, rec.getSettledAt());
    }

    @Test
    @DisplayName("fewer than 10 posts in the as-of baseline: sample size stored, percentage and median NULL, still SETTLED")
    void thinBaselineLeavesPctNull() {
        Instant postedAt = ist(9, 15, 19, 0);
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 15), ChallengeDayType.REEL);
        List<MediaMetric> rows = new ArrayList<>(priorPosts(postedAt, 9, 1000));
        rows.add(post("thepost", "REELS", postedAt, SETTLED_READ, 3100L));
        stubPosts(rows);

        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        assertEquals(CreatorRecommendationStatus.SETTLED, rec.getStatus());
        assertEquals(9, rec.getBaselineSampleSize());
        assertNull(rec.getReachVsBaselinePct());
        assertNull(rec.getBaselineMedianReach());
        assertEquals(3100L, rec.getReach());
    }

    @Test
    @DisplayName("a SETTLED row is frozen: later readings and later evaluations never change its outcome")
    void settledRowIsFrozen() {
        Instant postedAt = ist(9, 15, 19, 0);
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 15), ChallengeDayType.REEL);
        List<MediaMetric> rows = new ArrayList<>(priorPosts(postedAt, 12, 1000));
        rows.add(post("thepost", "REELS", postedAt, SETTLED_READ, 3100L));
        stubPosts(rows);
        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);
        Integer pct = rec.getReachVsBaselinePct();
        Instant settledAt = rec.getSettledAt();

        List<MediaMetric> newer = new ArrayList<>(priorPosts(postedAt, 12, 5000));
        newer.add(post("thepost", "REELS", postedAt, Duration.ofDays(9), 99_999L));
        stubPosts(newer);
        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW.plus(Duration.ofDays(3)));

        assertEquals(pct, rec.getReachVsBaselinePct());
        assertEquals(3100L, rec.getReach());
        assertEquals(1550L, rec.getBaselineMedianReach());
        assertEquals(settledAt, rec.getSettledAt());
    }

    // ---------------------------------------------------------------------------------------
    // followed_recommendations
    // ---------------------------------------------------------------------------------------

    private CreatorRecommendation settledRow(String mediaId, boolean followed, Integer pct, int day) {
        CreatorRecommendation r = plan(LocalDate.of(2026, 9, day), ChallengeDayType.REEL, null, null, null, ist(9, 1, 9, day));
        r.matchTo(mediaId, followed, null, ACCOUNT);
        r.settle(1000L, 100L, pct == null ? null : 900L, 12, pct, NOW);
        return r;
    }

    @Test
    @DisplayName(
            "followed_recommendations: no median below 3 followed-and-settled rows; at 3 the median"
                    + " of their pct, evidence = exactly those posts; unfollowed and OPEN rows never count")
    void followedFloorOfThreeAndEvidence() {
        Instant since = ist(6, 27, 0, 0);
        settledRow("f1", true, 10, 2);
        settledRow("f2", true, 30, 3);
        settledRow("notFollowed", false, 500, 4);
        plan(LocalDate.of(2026, 9, 5), ChallengeDayType.POST).markMissed(ACCOUNT);
        plan(LocalDate.of(2026, 9, 30), ChallengeDayType.REEL); // OPEN, still ahead

        FollowedStat two = service.followed(PROFILE_ID, since, ACCOUNT, false).get(0);
        assertEquals(CreatorRecommendationSource.PLAN_MY_WEEK, two.source());
        assertEquals(4, two.recommended(), "decided rows only: the OPEN one is not counted yet");
        assertEquals(2, two.followed());
        assertNull(two.medianReachVsUsualPct(), "2 followed settled rows are below the 3-post floor");
        assertEquals(List.of("f1", "f2"), two.evidence().postIds());

        settledRow("f3", true, 50, 6);
        FollowedStat three = service.followed(PROFILE_ID, since, ACCOUNT, false).get(0);
        assertEquals(3, three.followed());
        assertNotNull(three.medianReachVsUsualPct());
        assertEquals(30.0, three.medianReachVsUsualPct(), "median of 10, 30, 50; the unfollowed 500 is not in it");
        assertEquals(List.of("f1", "f2", "f3"), three.evidence().postIds());
        assertEquals(3, three.evidence().sampleSize());
    }

    @Test
    @DisplayName("followed rows settled WITHOUT a percentage (thin baseline) do not count toward the floor")
    void thinBaselineRowsDoNotCountTowardFloor() {
        settledRow("f1", true, 10, 2);
        settledRow("f2", true, 30, 3);
        settledRow("f3", true, null, 4);
        FollowedStat stat = service.followed(PROFILE_ID, ist(6, 27, 0, 0), ACCOUNT, false).get(0);
        assertEquals(3, stat.followed());
        assertNull(stat.medianReachVsUsualPct());
        assertEquals(List.of("f1", "f2"), stat.evidence().postIds());
    }

    // ---------------------------------------------------------------------------------------
    // Transaction design
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "transaction design: profile() opens no transaction; evaluate() runs outside any"
                    + " (NOT_SUPPORTED) and writes each row through the separate writer bean's own"
                    + " REQUIRES_NEW transaction (per-row isolation, committed before the reads)")
    void transactionDesign() throws Exception {
        Method profile = CreatorIntelligenceService.class.getMethod("profile", String.class, Instant.class);
        assertFalse(profile.isAnnotationPresent(Transactional.class));
        assertFalse(CreatorIntelligenceService.class.isAnnotationPresent(Transactional.class));

        Transactional evaluate =
                CreatorRecommendationOutcomeService.class
                        .getMethod("evaluate", String.class, String.class, boolean.class, Instant.class)
                        .getAnnotation(Transactional.class);
        assertNotNull(evaluate);
        assertEquals(Propagation.NOT_SUPPORTED, evaluate.propagation());

        Transactional apply =
                CreatorRecommendationOutcomeWriter.class
                        .getMethod(
                                "apply",
                                String.class,
                                CreatorRecommendationStatus.class,
                                Long.class,
                                CreatorRecommendationOutcomeWriter.Outcome.class,
                                String.class)
                        .getAnnotation(Transactional.class);
        assertNotNull(apply);
        assertFalse(apply.readOnly());
        assertEquals(Propagation.REQUIRES_NEW, apply.propagation());

        Transactional followed =
                CreatorRecommendationOutcomeService.class
                        .getMethod("followed", String.class, Instant.class, String.class, boolean.class)
                        .getAnnotation(Transactional.class);
        assertTrue(followed.readOnly());
    }

    // ---------------------------------------------------------------------------------------
    // Kabir fixes: M-2 (no outcome, bounded scan, per-row isolation), L-4, L-3
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "M-2: a MATCHED row whose post never settles becomes NO_OUTCOME exactly at match_until +"
                    + " 48 h + 7 days, keeps the account it was matched on, and is frozen from then on")
    void matchedRowWithoutSettleBecomesNoOutcome() {
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 10), ChallengeDayType.REEL);
        // Newest reading 6 h after posting: never settles (dropped out of the 25-post poll).
        stubPosts(List.of(post("stuck", "REELS", ist(9, 10, 19, 0), Duration.ofHours(6), 800L)));
        Instant deadline = LocalDate.of(2026, 9, 11).atStartOfDay(IST).toInstant().plus(Duration.ofHours(48)).plus(Duration.ofDays(7));

        service.evaluate(PROFILE_ID, ACCOUNT, false, deadline.minusNanos(1));
        assertEquals(CreatorRecommendationStatus.MATCHED, rec.getStatus());
        assertEquals(ACCOUNT, rec.getOutcomeIgAccountId());

        service.evaluate(PROFILE_ID, "17841400000000999", false, deadline);
        assertEquals(CreatorRecommendationStatus.NO_OUTCOME, rec.getStatus());
        assertTrue(rec.isFrozen());
        assertEquals(ACCOUNT, rec.getOutcomeIgAccountId(), "a matched row keeps the account it was matched on");
        assertNull(rec.getSettledAt());

        // A late settled reading changes nothing: NO_OUTCOME rows are never evaluated again.
        stubPosts(List.of(post("stuck", "REELS", ist(9, 10, 19, 0), Duration.ofDays(5), 99_000L)));
        service.evaluate(PROFILE_ID, ACCOUNT, false, deadline.plus(Duration.ofDays(1)));
        assertEquals(CreatorRecommendationStatus.NO_OUTCOME, rec.getStatus());
        assertNull(rec.getReach());
    }

    @Test
    @DisplayName("M-2: a MATCHED row whose post vanished from the readings (deleted, other account) also ends NO_OUTCOME")
    void matchedRowWhosePostVanishedBecomesNoOutcome() {
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 10), ChallengeDayType.REEL);
        rec.matchTo("deleted", true, null, ACCOUNT);
        stubPosts(List.of());
        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);
        assertEquals(CreatorRecommendationStatus.NO_OUTCOME, rec.getStatus());
    }

    @Test
    @DisplayName(
            "M-2: the readings scan starts no earlier than now - 180 days, however old a live row is;"
                    + " an OPEN row whose window the clamped scan never covered ends NO_OUTCOME, not MISSED")
    void scanIsClampedTo180Days() {
        CreatorRecommendation ancient = plan(LocalDate.of(2025, 11, 1), ChallengeDayType.REEL, null, null, null, ist(9, 1, 9, 0));
        CreatorRecommendation recent = plan(LocalDate.of(2026, 9, 22), ChallengeDayType.REEL);
        stubPosts(List.of(post("p", "REELS", ist(9, 22, 19, 0), SETTLED_READ, 1000L)));

        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(media).findNewestSnapshotPerPostSinceForAccount(eq(PROFILE_ID), since.capture(), eq(ACCOUNT));
        assertEquals(NOW.minus(Duration.ofDays(CreatorRecommendationOutcomeService.MAX_SCAN_DAYS)), since.getValue());
        assertEquals(CreatorRecommendationStatus.NO_OUTCOME, ancient.getStatus());
        assertEquals("p", recent.getMatchedMediaId());
    }

    @Test
    @DisplayName(
            "M-2 per-row isolation: one row's write failing (optimistic lock) does not stop the others,"
                    + " and evaluate() itself does not throw")
    void oneFailingRowDoesNotStopTheOthers() {
        // Same day: without the claim-on-failure rule the second row would take "a" too.
        CreatorRecommendation first = plan(LocalDate.of(2026, 9, 21), ChallengeDayType.REEL);
        CreatorRecommendation second = plan(LocalDate.of(2026, 9, 21), ChallengeDayType.REEL);
        stubPosts(
                List.of(
                        post("a", "REELS", ist(9, 21, 9, 0), SETTLED_READ, 1000L),
                        post("b", "REELS", ist(9, 21, 19, 0), SETTLED_READ, 1000L)));
        CreatorRecommendationOutcomeWriter failingFirst =
                new CreatorRecommendationOutcomeWriter(repository) {
                    @Override
                    public boolean apply(
                            String id,
                            CreatorRecommendationStatus expectedStatus,
                            Long expectedVersion,
                            Outcome outcome,
                            String igAccountId) {
                        if (id.equals(first.getId())) {
                            throw new ObjectOptimisticLockingFailureException(CreatorRecommendation.class, id);
                        }
                        return super.apply(id, expectedStatus, expectedVersion, outcome, igAccountId);
                    }
                };

        new CreatorRecommendationOutcomeService(repository, media, failingFirst).evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        assertEquals(CreatorRecommendationStatus.OPEN, first.getStatus());
        assertEquals(CreatorRecommendationStatus.SETTLED, second.getStatus());
        assertEquals("b", second.getMatchedMediaId(), "the failed row's post is not handed to the next row");
    }

    @Test
    @DisplayName(
            "L-4: reach_vs_baseline_pct is clamped to the INT range, never thrown on; a viral post over"
                    + " a tiny median still SETTLES")
    void pctIsClampedNeverThrows() {
        assertEquals(100, CreatorRecommendationOutcomeService.reachVsBaselinePct(3100L, 1550.0));
        assertEquals(-13, CreatorRecommendationOutcomeService.reachVsBaselinePct(87L, 100.0));
        assertEquals(Integer.MAX_VALUE, CreatorRecommendationOutcomeService.reachVsBaselinePct(Long.MAX_VALUE, 1.0));
        assertEquals(Integer.MAX_VALUE, CreatorRecommendationOutcomeService.reachVsBaselinePct(10_000_000_000L, 1.0));
        assertEquals(-100, CreatorRecommendationOutcomeService.reachVsBaselinePct(1L, 1e18));
        assertEquals(Long.MAX_VALUE, CreatorRecommendationOutcomeService.roundedMedian((double) Long.MAX_VALUE));
        assertEquals(1550L, CreatorRecommendationOutcomeService.roundedMedian(1549.5));

        Instant postedAt = ist(9, 15, 19, 0);
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 15), ChallengeDayType.REEL);
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(post("tiny" + i, "IMAGE", postedAt.minus(Duration.ofDays(i + 1)), Duration.ofHours(72), 1L));
        }
        rows.add(post("viral", "REELS", postedAt, SETTLED_READ, 10_000_000_000L));
        stubPosts(rows);

        service.evaluate(PROFILE_ID, ACCOUNT, false, NOW);

        assertEquals(CreatorRecommendationStatus.SETTLED, rec.getStatus());
        assertEquals(Integer.MAX_VALUE, rec.getReachVsBaselinePct());
        assertEquals(1L, rec.getBaselineMedianReach());
    }

    @Test
    @DisplayName(
            "L-3: for an account-switcher, followed_recommendations counts only rows decided on the"
                    + " current account; for a one-account creator every decided row counts")
    void followedCountsOnlyTheCurrentAccountForASwitcher() {
        String old = "17841400000000999";
        CreatorRecommendation mine = plan(LocalDate.of(2026, 9, 2), ChallengeDayType.REEL, null, null, null, ist(9, 1, 9, 0));
        mine.matchTo("new1", true, null, ACCOUNT);
        mine.settle(1000L, 100L, 900L, 12, 11, NOW);
        CreatorRecommendation oldOne = plan(LocalDate.of(2026, 9, 3), ChallengeDayType.REEL, null, null, null, ist(9, 1, 9, 1));
        oldOne.matchTo("old1", true, null, old);
        oldOne.settle(5000L, 500L, 900L, 12, 455, NOW);
        plan(LocalDate.of(2026, 9, 4), ChallengeDayType.POST, null, null, null, ist(9, 1, 9, 2)).markMissed(old);

        FollowedStat switcher = service.followed(PROFILE_ID, ist(6, 27, 0, 0), ACCOUNT, true).get(0);
        assertEquals(1, switcher.recommended());
        assertEquals(1, switcher.followed());
        // Evidence lists her settled posts even below the floor (followedFloor pins that); only the
        // median waits for 3. What matters here: the old account's post is never among them.
        assertEquals(List.of("new1"), switcher.evidence().postIds(), "old1 is not hers any more");
        assertNull(switcher.medianReachVsUsualPct(), "below the floor of 3");

        FollowedStat oneAccount = service.followed(PROFILE_ID, ist(6, 27, 0, 0), ACCOUNT, false).get(0);
        assertEquals(3, oneAccount.recommended());
        assertEquals(2, oneAccount.followed());
    }

    @Test
    @DisplayName("the entity refuses to rewrite a NO_OUTCOME row, like SETTLED and MISSED")
    void noOutcomeIsFrozenInTheEntity() {
        CreatorRecommendation rec = plan(LocalDate.of(2026, 9, 10), ChallengeDayType.REEL);
        rec.markNoOutcome(ACCOUNT);
        assertTrue(rec.isFrozen());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> rec.matchTo("x", true, null, ACCOUNT));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> rec.markMissed(ACCOUNT));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> rec.markNoOutcome(ACCOUNT));
    }

    @Test
    @DisplayName("an evaluation failure never fails the profile read")
    void evaluationFailureDoesNotFailProfile() {
        CreatorProfileRepository profiles = mock(CreatorProfileRepository.class);
        MetaOAuthTokenRepository tokens = mock(MetaOAuthTokenRepository.class);
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getId()).thenReturn(PROFILE_ID);
        when(profiles.findByUserId(USER)).thenReturn(Optional.of(profile));
        when(tokens.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(
                        Optional.of(
                                MetaOAuthToken.builder()
                                        .id("tok")
                                        .creatorProfileId(PROFILE_ID)
                                        .igBusinessAccountId(ACCOUNT)
                                        .encryptedAccessToken("enc")
                                        .build()));
        CreatorRecommendationRepository broken = inMemory(store);
        when(broken.findByCreatorProfileIdAndStatusInOrderByCreatedAtAscIdAsc(any(), any()))
                .thenThrow(new QueryTimeoutException("down"));
        stubPosts(List.of());

        CreatorIntelligenceProfile result =
                new CreatorIntelligenceService(
                                media,
                                profiles,
                                new ConnectedInstagramAccount(tokens),
                                tokens,
                                new CreatorRecommendationOutcomeService(broken, media, new CreatorRecommendationOutcomeWriter(broken)))
                        .profile(USER, NOW);

        assertTrue(result.available());
        assertEquals(List.of(), result.followedRecommendations());
    }
}
