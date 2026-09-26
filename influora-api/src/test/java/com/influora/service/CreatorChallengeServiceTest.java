package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorChallenge;
import com.influora.domain.entity.CreatorChallengeDay;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorChallengeStatus;
import com.influora.repository.CreatorChallengeDayRepository;
import com.influora.repository.CreatorChallengeRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.creatorcopilot.CreatorPostingPatternService;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PatternWindow;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PostingPattern;
import com.influora.web.dto.challenge.ChallengeDtos.ChallengeState;
import com.influora.web.dto.challenge.ChallengeDtos.Comparison;
import com.influora.web.dto.meta.MetaDtos.MetaConnectionStatusResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * CHALLENGE-SPEC.md Backend &sect;8 test list: plan generation (best type spread, rest day,
 * suggestion fallback), tick-off (type mapping, one post can't fill two days, different-type still
 * ticks with matchedType=false, IST date boundaries), status derivation incl. CHECKING, streak
 * (rest days), comparison (48h settling, thin/zero data -> null), one-active-challenge (409), end
 * ownership.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorChallengeServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String USER_ID = "01USERCHALLENGE0000001";
    private static final String PROFILE_ID = "01PROFILECHALLENGE0001";

    @Mock private CreatorChallengeRepository challengeRepository;
    @Mock private CreatorChallengeDayRepository dayRepository;
    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private CreatorPostingPatternService postingPatternService;
    @Mock private MetaConnectionService metaConnectionService;

    private CreatorChallengeService service;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        service =
                new CreatorChallengeService(
                        challengeRepository,
                        dayRepository,
                        mediaMetricsRepository,
                        postingPatternService,
                        metaConnectionService);
        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Test Creator");
        when(mediaMetricsRepository.findNewestSnapshotPerPostSince(anyString(), any()))
                .thenReturn(List.of());
    }

    private static MetaConnectionStatusResponse metaConnected() {
        return new MetaConnectionStatusResponse(true, "@x", 100L, Instant.now(), List.of(), null, null, null);
    }

    private static MetaConnectionStatusResponse metaNotConnected() {
        return new MetaConnectionStatusResponse(false, null, null, null, List.of(), null, null, null);
    }

    /** A Monday, so day_index 0..6 = Mon..Sun deterministically, regardless of calendar trivia. */
    private static LocalDate mondayStart() {
        return LocalDate.of(2026, 9, 21).with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
    }

    private static List<CreatorChallengeDay> sortedCopy(List<CreatorChallengeDay> days) {
        List<CreatorChallengeDay> copy = new ArrayList<>(days);
        copy.sort(java.util.Comparator.comparingInt(CreatorChallengeDay::getDayIndex));
        return copy;
    }

    // ============================== start() ==============================

    @Nested
    @DisplayName("start(): one active challenge enforced, plan generation")
    class StartTests {

        @Test
        @DisplayName("409 CHALLENGE_ALREADY_ACTIVE when one is already active")
        void alreadyActive() {
            when(challengeRepository.findByActiveKey(USER_ID))
                    .thenReturn(Optional.of(CreatorChallenge.start("01X", USER_ID, PROFILE_ID, mondayStart())));

            ApiException ex =
                    assertThrows(ApiException.class, () -> service.start(profile, mondayStart(), Instant.now()));
            assertEquals("CHALLENGE_ALREADY_ACTIVE", ex.getCode());
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        }

        @Test
        @DisplayName("409 INSTAGRAM_NOT_CONNECTED when Instagram is not connected")
        void notConnected() {
            when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty());
            when(metaConnectionService.getStatus(profile)).thenReturn(metaNotConnected());

            ApiException ex =
                    assertThrows(ApiException.class, () -> service.start(profile, mondayStart(), Instant.now()));
            assertEquals("INSTAGRAM_NOT_CONNECTED", ex.getCode());
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        }

        @Test
        @DisplayName("CHALLENGE_ALREADY_ACTIVE is checked before INSTAGRAM_NOT_CONNECTED")
        void activeCheckedBeforeConnection() {
            when(challengeRepository.findByActiveKey(USER_ID))
                    .thenReturn(Optional.of(CreatorChallenge.start("01X", USER_ID, PROFILE_ID, mondayStart())));

            ApiException ex =
                    assertThrows(ApiException.class, () -> service.start(profile, mondayStart(), Instant.now()));
            assertEquals("CHALLENGE_ALREADY_ACTIVE", ex.getCode());
            verify(metaConnectionService, never()).getStatus(any());
        }

        /**
         * Round 2 fix: {@code findByActiveKey}'s pre-check is TOCTOU -- two near-simultaneous
         * "Start" calls can both pass it. The real {@code active_key} UNIQUE index is what stops
         * the second INSERT, and it must be caught HERE (via {@code saveAndFlush}, not a bare
         * {@code save} that would only fail later, outside any catch this method has) and turned
         * into the same 409 the non-race path produces -- never a raw 500.
         */
        @Test
        @DisplayName("saveAndFlush losing the active_key race becomes 409 CHALLENGE_ALREADY_ACTIVE, not a 500")
        void raceLoserGetsConflictNotServerError() {
            LocalDate startedOn = mondayStart();
            when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty());
            when(metaConnectionService.getStatus(profile)).thenReturn(metaConnected());
            when(postingPatternService.analyse(eq(USER_ID), eq(startedOn)))
                    .thenReturn(new PostingPattern(false, 0, null, List.of(), "not enough"));
            when(challengeRepository.saveAndFlush(any(CreatorChallenge.class)))
                    .thenThrow(
                            new DataIntegrityViolationException(
                                    "could not execute statement; SQL [n/a]; constraint"
                                            + " [uk_creator_challenges_active_key]"));

            ApiException ex =
                    assertThrows(ApiException.class, () -> service.start(profile, startedOn, Instant.now()));

            assertEquals("CHALLENGE_ALREADY_ACTIVE", ex.getCode());
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            // The losing attempt's days must never be persisted -- the method must throw before
            // reaching dayRepository.saveAll at all.
            verify(dayRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("no pattern data: default REEL/CAROUSEL/POST mix in order, rest day = Sunday, suggestion window")
        void defaultMixAndSuggestionFallback() {
            LocalDate startedOn = mondayStart();
            when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty());
            when(metaConnectionService.getStatus(profile)).thenReturn(metaConnected());
            when(postingPatternService.analyse(eq(USER_ID), eq(startedOn)))
                    .thenReturn(new PostingPattern(false, 2, null, List.of(), "not enough"));
            // getState (called at the end of start()) needs an active row to find.
            CreatorChallenge saved = CreatorChallenge.start("01NEW", USER_ID, PROFILE_ID, startedOn);
            when(challengeRepository.saveAndFlush(any(CreatorChallenge.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(challengeRepository.findByActiveKey(USER_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(saved));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<CreatorChallengeDay>> daysCaptor = ArgumentCaptor.forClass(List.class);
            when(dayRepository.saveAll(daysCaptor.capture())).thenReturn(List.of());
            when(dayRepository.findByIdChallengeIdOrderByIdDayIndexAsc(anyString()))
                    .thenAnswer(inv -> sortedCopy(daysCaptor.getValue()));

            service.start(profile, startedOn, Instant.now());

            List<CreatorChallengeDay> planned = sortedCopy(daysCaptor.getAllValues().get(0));
            assertEquals(7, planned.size());
            // Sunday (day_index 6, since startedOn is Monday) is REST.
            assertEquals(ChallengeDayType.REST, planned.get(6).getPlannedType());
            List<ChallengeDayType> postingTypesInOrder =
                    planned.subList(0, 6).stream().map(CreatorChallengeDay::getPlannedType).toList();
            assertEquals(
                    List.of(
                            ChallengeDayType.REEL,
                            ChallengeDayType.CAROUSEL,
                            ChallengeDayType.POST,
                            ChallengeDayType.REEL,
                            ChallengeDayType.CAROUSEL,
                            ChallengeDayType.REEL),
                    postingTypesInOrder);
            for (CreatorChallengeDay day : planned.subList(0, 6)) {
                assertEquals("suggestion", day.getWindowSource());
                assertEquals("evening", day.getWindowLabel());
                assertEquals(LocalTime.of(17, 0), day.getWindowFrom());
                assertEquals(LocalTime.of(22, 0), day.getWindowTo());
            }
            assertNull(planned.get(6).getWindowLabel());
            assertNull(planned.get(6).getWindowSource());
        }

        @Test
        @DisplayName(
                "best type known + weekday worse than weekend: best type spread not 3-in-a-row,"
                        + " others share the rest, rest day is the earliest weekday, your_posts windows")
        void bestTypeSpreadAndWeekdayRestDay() {
            LocalDate startedOn = mondayStart();
            when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty());
            when(metaConnectionService.getStatus(profile)).thenReturn(metaConnected());
            when(postingPatternService.analyse(eq(USER_ID), eq(startedOn)))
                    .thenReturn(
                            new PostingPattern(
                                    true,
                                    20,
                                    "CAROUSEL_ALBUM",
                                    List.of(
                                            // weekday worse (2.0%) than weekend (6.0%) -> rest = a weekday
                                            new PatternWindow("weekday evening", 5, "2.0%"),
                                            new PatternWindow("weekend morning", 4, "6.0%")),
                                    null));
            CreatorChallenge saved = CreatorChallenge.start("01NEW2", USER_ID, PROFILE_ID, startedOn);
            when(challengeRepository.saveAndFlush(any(CreatorChallenge.class))).thenAnswer(inv -> inv.getArgument(0));
            when(challengeRepository.findByActiveKey(USER_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(saved));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<CreatorChallengeDay>> daysCaptor = ArgumentCaptor.forClass(List.class);
            when(dayRepository.saveAll(daysCaptor.capture())).thenReturn(List.of());
            when(dayRepository.findByIdChallengeIdOrderByIdDayIndexAsc(anyString()))
                    .thenAnswer(inv -> sortedCopy(daysCaptor.getValue()));

            service.start(profile, startedOn, Instant.now());

            List<CreatorChallengeDay> planned = sortedCopy(daysCaptor.getAllValues().get(0));
            // Monday (index 0) is the earliest weekday -> REST.
            assertEquals(ChallengeDayType.REST, planned.get(0).getPlannedType());
            // Best type (CAROUSEL) never 3-in-a-row among the 6 posting days (indices 1..6).
            List<ChallengeDayType> postingTypes =
                    planned.subList(1, 7).stream().map(CreatorChallengeDay::getPlannedType).toList();
            long carouselCount = postingTypes.stream().filter(t -> t == ChallengeDayType.CAROUSEL).count();
            assertEquals(3, carouselCount);
            for (int i = 0; i + 2 < postingTypes.size(); i++) {
                assertFalse(
                        postingTypes.get(i) == ChallengeDayType.CAROUSEL
                                && postingTypes.get(i + 1) == ChallengeDayType.CAROUSEL
                                && postingTypes.get(i + 2) == ChallengeDayType.CAROUSEL,
                        "best type must not appear three days in a row");
            }
            // Weekday posting days (Tue-Fri, index 1-4) get the weekday bucket's window.
            for (CreatorChallengeDay day : planned.subList(1, 5)) {
                assertEquals("your_posts", day.getWindowSource());
                assertEquals("evening", day.getWindowLabel());
            }
            // Weekend posting days (Sat/Sun, index 5-6) get the weekend bucket's window.
            for (CreatorChallengeDay day : planned.subList(5, 7)) {
                assertEquals("your_posts", day.getWindowSource());
                assertEquals("morning", day.getWindowLabel());
                assertEquals(LocalTime.of(5, 0), day.getWindowFrom());
                assertEquals(LocalTime.of(12, 0), day.getWindowTo());
            }
        }
    }

    // ============================== end() ==============================

    @Nested
    @DisplayName("end(): ownership, idempotency, returns the updated ChallengeState")
    class EndTests {

        private void stubDownstreamGetState() {
            // end() reuses getState() to build its response (src/lib/api.ts's creatorChallenge.end
            // expects a ChallengeState body, active: null, not 204 -- same contract as start()).
            when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty());
            when(challengeRepository.findFirstByCreatorUserIdAndStatusInOrderByStartedOnDesc(
                            eq(USER_ID), any()))
                    .thenReturn(Optional.empty());
            when(metaConnectionService.getStatus(profile)).thenReturn(metaConnected());
        }

        @Test
        @DisplayName("404 CHALLENGE_NOT_FOUND when the challenge is not theirs")
        void notTheirs() {
            CreatorChallenge other =
                    CreatorChallenge.start("01OTHER", "01SOMEONE_ELSE00000001", "01OTHERPROFILE0000001", mondayStart());
            when(challengeRepository.findById("01OTHER")).thenReturn(Optional.of(other));

            ApiException ex =
                    assertThrows(
                            ApiException.class,
                            () -> service.end(profile, "01OTHER", mondayStart(), Instant.now()));
            assertEquals("CHALLENGE_NOT_FOUND", ex.getCode());
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        }

        @Test
        @DisplayName("404 CHALLENGE_NOT_FOUND when the id does not exist")
        void doesNotExist() {
            when(challengeRepository.findById("01MISSING")).thenReturn(Optional.empty());
            assertThrows(
                    ApiException.class, () -> service.end(profile, "01MISSING", mondayStart(), Instant.now()));
        }

        @Test
        @DisplayName("ends an active challenge owned by the caller, returns active: null")
        void endsOwnChallenge() {
            CreatorChallenge mine = CreatorChallenge.start("01MINE", USER_ID, PROFILE_ID, mondayStart());
            when(challengeRepository.findById("01MINE")).thenReturn(Optional.of(mine));
            stubDownstreamGetState();

            ChallengeState state = service.end(profile, "01MINE", mondayStart(), Instant.now());

            assertEquals(CreatorChallengeStatus.ENDED, mine.getStatus());
            assertNull(mine.getActiveKey());
            verify(challengeRepository).save(mine);
            assertNull(state.active());
        }

        @Test
        @DisplayName("ending an already-ended challenge is an idempotent no-op, still returns a state")
        void idempotentOnAlreadyEnded() {
            CreatorChallenge mine = CreatorChallenge.start("01MINE2", USER_ID, PROFILE_ID, mondayStart());
            mine.markEnded();
            when(challengeRepository.findById("01MINE2")).thenReturn(Optional.of(mine));
            stubDownstreamGetState();

            ChallengeState state = service.end(profile, "01MINE2", mondayStart(), Instant.now());

            assertEquals(CreatorChallengeStatus.ENDED, mine.getStatus());
            verify(challengeRepository, never()).save(any());
            assertNull(state.active());
        }
    }

    // ============================== status derivation ==============================

    @Nested
    @DisplayName("deriveStatus(): DONE, REST, TODAY, UPCOMING, CHECKING, MISSED")
    class StatusDerivationTests {

        private CreatorChallengeDay day(LocalDate date, ChallengeDayType type) {
            return CreatorChallengeDay.plan(
                    "01CHAL", 0, date, type, "evening", LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
        }

        @Test
        void rest() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            assertEquals(
                    "REST",
                    CreatorChallengeService.deriveStatus(
                            day(today, ChallengeDayType.REST), today, Instant.now()));
        }

        @Test
        void done() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            CreatorChallengeDay d = day(today.minusDays(1), ChallengeDayType.REEL);
            d.markDone("m1", "VIDEO", true, Instant.now());
            assertEquals("DONE", CreatorChallengeService.deriveStatus(d, today, Instant.now()));
        }

        @Test
        void today() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            assertEquals(
                    "TODAY",
                    CreatorChallengeService.deriveStatus(day(today, ChallengeDayType.REEL), today, Instant.now()));
        }

        @Test
        void upcoming() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            assertEquals(
                    "UPCOMING",
                    CreatorChallengeService.deriveStatus(
                            day(today.plusDays(1), ChallengeDayType.REEL), today, Instant.now()));
        }

        @Test
        @DisplayName("CHECKING just under 12h after the day ended, MISSED just at/over 12h")
        void checkingThenMissedBoundary() {
            LocalDate dayDate = LocalDate.of(2026, 9, 19);
            LocalDate today = LocalDate.of(2026, 9, 20); // dayDate is yesterday
            Instant dayEnd = dayDate.plusDays(1).atStartOfDay(IST).toInstant();

            Instant justUnder12h = dayEnd.plus(java.time.Duration.ofHours(11).plusMinutes(59));
            Instant exactly12h = dayEnd.plus(java.time.Duration.ofHours(12));

            assertEquals(
                    "CHECKING",
                    CreatorChallengeService.deriveStatus(day(dayDate, ChallengeDayType.REEL), today, justUnder12h));
            assertEquals(
                    "MISSED",
                    CreatorChallengeService.deriveStatus(day(dayDate, ChallengeDayType.REEL), today, exactly12h));
        }
    }

    // ============================== streak ==============================

    @Nested
    @DisplayName("computeStreak(): consecutive DONE counting back, REST neither breaks nor adds")
    class StreakTests {

        private CreatorChallengeDay planned(LocalDate date, ChallengeDayType type) {
            return CreatorChallengeDay.plan(
                    "01CHAL", 0, date, type, "evening", LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
        }

        private CreatorChallengeDay done(LocalDate date, ChallengeDayType type) {
            CreatorChallengeDay d = planned(date, type);
            d.markDone("m", "VIDEO", true, Instant.now());
            return d;
        }

        @Test
        @DisplayName("today counts only if already DONE -- an undone today doesn't zero yesterday's streak")
        void todayNotDoneDoesNotResetStreak() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            List<CreatorChallengeDay> days =
                    List.of(
                            done(today.minusDays(2), ChallengeDayType.REEL),
                            done(today.minusDays(1), ChallengeDayType.CAROUSEL),
                            planned(today, ChallengeDayType.POST) // not done yet
                            );
            assertEquals(2, CreatorChallengeService.computeStreak(days, today));
        }

        @Test
        @DisplayName("today DONE extends the streak")
        void todayDoneExtendsStreak() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            List<CreatorChallengeDay> days =
                    List.of(
                            done(today.minusDays(1), ChallengeDayType.REEL), done(today, ChallengeDayType.CAROUSEL));
            assertEquals(2, CreatorChallengeService.computeStreak(days, today));
        }

        @Test
        @DisplayName("REST days neither break nor add to the streak")
        void restNeitherBreaksNorAdds() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            List<CreatorChallengeDay> days =
                    List.of(
                            done(today.minusDays(2), ChallengeDayType.REEL),
                            planned(today.minusDays(1), ChallengeDayType.REST),
                            done(today, ChallengeDayType.CAROUSEL));
            assertEquals(2, CreatorChallengeService.computeStreak(days, today));
        }

        @Test
        @DisplayName("a MISSED day breaks the streak")
        void missedBreaksStreak() {
            LocalDate today = LocalDate.of(2026, 9, 20);
            List<CreatorChallengeDay> days =
                    List.of(
                            done(today.minusDays(2), ChallengeDayType.REEL),
                            planned(today.minusDays(1), ChallengeDayType.CAROUSEL), // missed
                            done(today, ChallengeDayType.POST));
            assertEquals(1, CreatorChallengeService.computeStreak(days, today));
        }
    }

    // ============================== tick-off (via getState) ==============================

    @Nested
    @DisplayName("tick-off: type mapping, one post can't fill two days, mismatched type, IST boundaries")
    class TickOffTests {

        private CreatorChallenge activeChallenge(LocalDate startedOn) {
            return CreatorChallenge.start("01ACTIVE0000000000001", USER_ID, PROFILE_ID, startedOn);
        }

        private MediaMetric post(String mediaId, String mediaType, Instant postedAt, Long reach) {
            return MediaMetric.builder()
                    .id("01MM" + mediaId)
                    .mediaId(mediaId)
                    .creatorProfileId(PROFILE_ID)
                    .platform("instagram")
                    .mediaType(mediaType)
                    .permalink("https://instagram.com/p/" + mediaId)
                    .reach(reach)
                    .engagement(reach == null ? null : reach / 10)
                    .postedAt(postedAt)
                    .time(postedAt)
                    .build();
        }

        private void stubActive(CreatorChallenge challenge, List<CreatorChallengeDay> days) {
            when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.of(challenge));
            when(dayRepository.findByIdChallengeIdOrderByIdDayIndexAsc(challenge.getId())).thenReturn(days);
            when(challengeRepository.findFirstByCreatorUserIdAndStatusInOrderByStartedOnDesc(
                            eq(USER_ID), any()))
                    .thenReturn(Optional.empty());
            when(metaConnectionService.getStatus(profile)).thenReturn(metaConnected());
        }

        @Test
        @DisplayName("VIDEO/REELS match REEL, CAROUSEL_ALBUM matches CAROUSEL, IMAGE matches POST")
        void typeMapping() {
            LocalDate startedOn = mondayStart();
            CreatorChallenge challenge = activeChallenge(startedOn);
            CreatorChallengeDay reelDay =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 0, startedOn, ChallengeDayType.REEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            CreatorChallengeDay carouselDay =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 1, startedOn.plusDays(1), ChallengeDayType.CAROUSEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            CreatorChallengeDay postDay =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 2, startedOn.plusDays(2), ChallengeDayType.POST, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            List<CreatorChallengeDay> days = new ArrayList<>(List.of(reelDay, carouselDay, postDay));
            for (int i = 3; i < 7; i++) {
                days.add(
                        CreatorChallengeDay.plan(
                                challenge.getId(), i, startedOn.plusDays(i), ChallengeDayType.REST, null, null,
                                null, null));
            }
            stubActive(challenge, days);

            Instant t0 = startedOn.atTime(18, 0).atZone(IST).toInstant();
            Instant t1 = startedOn.plusDays(1).atTime(18, 0).atZone(IST).toInstant();
            Instant t2 = startedOn.plusDays(2).atTime(18, 0).atZone(IST).toInstant();
            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(
                            List.of(
                                    post("reelpost", "REELS", t0, 1000L),
                                    post("carouselpost", "CAROUSEL_ALBUM", t1, 1000L),
                                    post("postpost", "IMAGE", t2, 1000L)));
            when(mediaMetricsRepository.findFirstByMediaIdOrderByTimeDesc(anyString()))
                    .thenAnswer(
                            inv ->
                                    Optional.of(
                                            post(inv.getArgument(0), "IGNORED", Instant.now(), 1L)));

            ChallengeState state = service.getState(profile, startedOn.plusDays(2), t2);

            assertTrue(reelDay.isDone());
            assertEquals(Boolean.TRUE, reelDay.getMatchedType());
            assertTrue(carouselDay.isDone());
            assertEquals(Boolean.TRUE, carouselDay.getMatchedType());
            assertTrue(postDay.isDone());
            assertEquals(Boolean.TRUE, postDay.getMatchedType());
            assertNotNull(state.active());
        }

        @Test
        @DisplayName(
                "a reel reported as VIDEO (how Instagram actually reports reels) ticks a planned REEL"
                        + " with matchedType=true")
        void reelReportedAsVideoMatchesPlannedReel() {
            // The typeMapping test above uses "REELS"; Instagram's media_type for a reel is VIDEO,
            // so this is the case real creators hit. Meera's falsification (2026-09-24) showed that
            // dropping VIDEO from the REEL mapping left every test green.
            LocalDate startedOn = mondayStart();
            CreatorChallenge challenge = activeChallenge(startedOn);
            CreatorChallengeDay reelDay =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 0, startedOn, ChallengeDayType.REEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            List<CreatorChallengeDay> days = fillRemainingRest(challenge.getId(), startedOn, reelDay);
            stubActive(challenge, days);

            Instant postedAt = startedOn.atTime(19, 0).atZone(IST).toInstant();
            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(List.of(post("videoreel", "VIDEO", postedAt, 900L)));
            when(mediaMetricsRepository.findFirstByMediaIdOrderByTimeDesc(anyString()))
                    .thenAnswer(inv -> Optional.of(post(inv.getArgument(0), "VIDEO", postedAt, 900L)));

            service.getState(profile, startedOn, postedAt);

            assertTrue(reelDay.isDone());
            assertEquals(Boolean.TRUE, reelDay.getMatchedType());
            assertEquals("VIDEO", reelDay.getPostedType());
        }

        @Test
        @DisplayName("a post from BEFORE the challenge started never ticks day 0, even if the query returns it")
        void postBeforeStartNeverTicks() {
            // Structurally a pre-start post has an earlier date than every challenge day, but the
            // repository call is mocked with any() in these tests, so nothing pinned it. This does.
            LocalDate startedOn = mondayStart();
            CreatorChallenge challenge = activeChallenge(startedOn);
            CreatorChallengeDay day0 =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 0, startedOn, ChallengeDayType.POST, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            List<CreatorChallengeDay> days = fillRemainingRest(challenge.getId(), startedOn, day0);
            stubActive(challenge, days);

            Instant dayBefore = startedOn.minusDays(1).atTime(21, 0).atZone(IST).toInstant();
            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(List.of(post("earlypost", "IMAGE", dayBefore, 700L)));
            when(mediaMetricsRepository.findFirstByMediaIdOrderByTimeDesc(anyString()))
                    .thenAnswer(inv -> Optional.of(post(inv.getArgument(0), "IMAGE", dayBefore, 700L)));

            service.getState(profile, startedOn, startedOn.atTime(23, 0).atZone(IST).toInstant());

            assertFalse(day0.isDone());
        }

        @Test
        @DisplayName("a different-type post still ticks the day off, with matchedType=false")
        void mismatchedTypeStillTicks() {
            LocalDate startedOn = mondayStart();
            CreatorChallenge challenge = activeChallenge(startedOn);
            CreatorChallengeDay reelDay =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 0, startedOn, ChallengeDayType.REEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            List<CreatorChallengeDay> days = fillRemainingRest(challenge.getId(), startedOn, reelDay);
            stubActive(challenge, days);

            Instant postedAt = startedOn.atTime(18, 0).atZone(IST).toInstant();
            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(List.of(post("imagepost", "IMAGE", postedAt, 500L)));
            when(mediaMetricsRepository.findFirstByMediaIdOrderByTimeDesc(anyString()))
                    .thenAnswer(inv -> Optional.of(post(inv.getArgument(0), "IMAGE", postedAt, 500L)));

            service.getState(profile, startedOn, postedAt);

            assertTrue(reelDay.isDone());
            assertEquals(Boolean.FALSE, reelDay.getMatchedType());
            assertEquals("IMAGE", reelDay.getPostedType());
        }

        @Test
        @DisplayName("a media id already used by another day is never reused (one post can't fill two days)")
        void onePostCannotFillTwoDays() {
            LocalDate startedOn = mondayStart();
            CreatorChallenge challenge = activeChallenge(startedOn);
            CreatorChallengeDay day0 =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 0, startedOn, ChallengeDayType.REEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            // Already ticked off by an earlier GET, using media "shared".
            Instant priorDoneAt = startedOn.atTime(9, 0).atZone(IST).toInstant();
            day0.markDone("shared", "VIDEO", true, priorDoneAt);

            CreatorChallengeDay day1 =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 1, startedOn.plusDays(1), ChallengeDayType.CAROUSEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            List<CreatorChallengeDay> days = fillRemainingRest(challenge.getId(), startedOn, day0, day1);
            stubActive(challenge, days);

            // "shared" reappears (a later poll snapshot) now dated on day1 -- must NOT be reused.
            Instant sharedOnDay1 = startedOn.plusDays(1).atTime(10, 0).atZone(IST).toInstant();
            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(List.of(post("shared", "CAROUSEL_ALBUM", sharedOnDay1, 500L)));

            service.getState(profile, startedOn.plusDays(1), sharedOnDay1);

            assertFalse(day1.isDone(), "the media id already claimed by day0 must not tick day1 off too");
        }

        @Test
        @DisplayName("IST date boundary: 23:59:59 IST ticks THIS day, 00:00:00 IST ticks the NEXT day")
        void istDateBoundary() {
            LocalDate startedOn = mondayStart();
            CreatorChallenge challenge = activeChallenge(startedOn);
            CreatorChallengeDay day0 =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 0, startedOn, ChallengeDayType.REEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            CreatorChallengeDay day1 =
                    CreatorChallengeDay.plan(
                            challenge.getId(), 1, startedOn.plusDays(1), ChallengeDayType.CAROUSEL, "evening",
                            LocalTime.of(17, 0), LocalTime.of(22, 0), "your_posts");
            List<CreatorChallengeDay> days = fillRemainingRest(challenge.getId(), startedOn, day0, day1);
            stubActive(challenge, days);

            Instant lastMomentOfDay0 = startedOn.atTime(23, 59, 59).atZone(IST).toInstant();
            Instant firstMomentOfDay1 = startedOn.plusDays(1).atStartOfDay(IST).toInstant();
            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(
                            List.of(
                                    post("late", "VIDEO", lastMomentOfDay0, 100L),
                                    post("early", "CAROUSEL_ALBUM", firstMomentOfDay1, 100L)));
            when(mediaMetricsRepository.findFirstByMediaIdOrderByTimeDesc(anyString()))
                    .thenAnswer(inv -> Optional.of(post(inv.getArgument(0), "IGNORED", Instant.now(), 1L)));

            service.getState(profile, startedOn.plusDays(1), firstMomentOfDay1);

            assertTrue(day0.isDone(), "23:59:59 IST belongs to day 0's calendar date");
            assertEquals("late", day0.getDoneMediaId());
            assertTrue(day1.isDone(), "00:00:00 IST belongs to day 1's calendar date, not day 0's");
            assertEquals("early", day1.getDoneMediaId());
        }

        private List<CreatorChallengeDay> fillRemainingRest(
                String challengeId, LocalDate startedOn, CreatorChallengeDay... explicit) {
            List<CreatorChallengeDay> days = new ArrayList<>(List.of(explicit));
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (CreatorChallengeDay d : explicit) {
                used.add(d.getDayIndex());
            }
            for (int i = 0; i < 7; i++) {
                if (!used.contains(i)) {
                    days.add(
                            CreatorChallengeDay.plan(
                                    challengeId, i, startedOn.plusDays(i), ChallengeDayType.REST, null, null, null,
                                    null));
                }
            }
            days.sort(java.util.Comparator.comparingInt(CreatorChallengeDay::getDayIndex));
            return days;
        }
    }

    // ============================== comparison (via getState) ==============================

    @Nested
    @DisplayName("comparison: 48h settling, thin data -> null, zero last week -> null")
    class ComparisonTests {

        @BeforeEach
        void noActiveChallenge() {
            when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty());
            when(challengeRepository.findFirstByCreatorUserIdAndStatusInOrderByStartedOnDesc(
                            eq(USER_ID), any()))
                    .thenReturn(Optional.empty());
            when(metaConnectionService.getStatus(profile)).thenReturn(metaConnected());
        }

        private MediaMetric settledPost(String id, Instant postedAt, long reach, long engagement) {
            return MediaMetric.builder()
                    .id("01MM" + id)
                    .mediaId(id)
                    .creatorProfileId(PROFILE_ID)
                    .platform("instagram")
                    .mediaType("IMAGE")
                    .reach(reach)
                    .engagement(engagement)
                    .postedAt(postedAt)
                    .time(postedAt)
                    .build();
        }

        @Test
        @DisplayName("no posts at all -> not enough to compare, both change fields null")
        void noPosts() {
            LocalDate today = LocalDate.of(2026, 9, 23);
            ChallengeState state = service.getState(profile, today, Instant.now());
            Comparison c = state.comparison();
            assertFalse(c.enoughToCompare());
            assertNull(c.reachChangePercent());
            assertNull(c.engagementChangePoints());
            assertNotNull(c.note());
        }

        @Test
        @DisplayName("a post exactly 48h old is settled; one hour younger is not")
        void settlingBoundary() {
            LocalDate today = LocalDate.of(2026, 9, 23);
            Instant now = today.atStartOfDay(IST).toInstant();
            // thisWeek = today-7..today-1. Two posts, 3 days ago.
            Instant threeDaysAgo = today.minusDays(3).atTime(10, 0).atZone(IST).toInstant();
            Instant exactly48hOld = now.minus(java.time.Duration.ofHours(48));
            Instant under48hOld = now.minus(java.time.Duration.ofHours(47));

            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(
                            List.of(
                                    settledPost("s1", exactly48hOld, 1000, 100),
                                    settledPost("s2", threeDaysAgo, 1000, 100),
                                    settledPost("unsettled", under48hOld, 1000, 100)));

            ChallengeState state = service.getState(profile, today, now);
            Comparison c = state.comparison();
            // exactly48hOld and threeDaysAgo count; under48hOld does not.
            assertEquals(3, c.thisWeek().posts());
            assertEquals(2, c.thisWeek().settledPosts());
        }

        @Test
        @DisplayName("fewer than 2 settled posts in a week -> not enough to compare")
        void fewerThanTwoSettled() {
            LocalDate today = LocalDate.of(2026, 9, 23);
            Instant now = today.atStartOfDay(IST).toInstant();
            Instant longSettled = today.minusDays(3).atTime(10, 0).atZone(IST).toInstant();
            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(List.of(settledPost("only-one", longSettled, 1000, 100)));

            ChallengeState state = service.getState(profile, today, now);
            assertFalse(state.comparison().enoughToCompare());
            assertNull(state.comparison().reachChangePercent());
        }

        @Test
        @DisplayName("zero reach last week -> reachChangePercent is null even when enoughToCompare")
        void zeroLastWeekReachNeverPercentaged() {
            LocalDate today = LocalDate.of(2026, 9, 23);
            Instant now = today.atStartOfDay(IST).toInstant();
            // thisWeek: 2 settled (>= 48h old) posts with real reach, still inside today-7..today-1.
            Instant d1 = today.minusDays(4).atTime(10, 0).atZone(IST).toInstant();
            Instant d2 = today.minusDays(5).atTime(10, 0).atZone(IST).toInstant();
            // lastWeek: 2 settled posts, but zero reach each.
            Instant d3 = today.minusDays(9).atTime(10, 0).atZone(IST).toInstant();
            Instant d4 = today.minusDays(10).atTime(10, 0).atZone(IST).toInstant();

            when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                    .thenReturn(
                            List.of(
                                    settledPost("tw1", d1, 1000, 100),
                                    settledPost("tw2", d2, 1000, 100),
                                    settledPost("lw1", d3, 0, 0),
                                    settledPost("lw2", d4, 0, 0)));

            ChallengeState state = service.getState(profile, today, now);
            Comparison c = state.comparison();
            assertTrue(c.enoughToCompare());
            assertNull(c.reachChangePercent(), "never a percentage change from a zero last-week reach");
        }
    }
}
