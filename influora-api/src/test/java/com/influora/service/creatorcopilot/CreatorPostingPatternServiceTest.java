package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PatternWindow;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PostingPattern;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-PLAN-MY-WEEK -- {@link CreatorPostingPatternService}: deterministic arithmetic over a mocked
 * {@link MediaMetricsRepository} result. Mirrors {@code ContentTopicServiceTest}'s style: the
 * repository is mocked to return exactly the rows each test wants, and the SERVICE (not the
 * repository) is what every filtering/bucketing rule under test actually runs.
 */
@ExtendWith(MockitoExtension.class)
class CreatorPostingPatternServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String PROFILE_ID = "profile1";

    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    // V20260924120000: reads are narrowed to the creator's CURRENT Instagram account.
    @Mock private com.influora.service.creatorcopilot.ConnectedInstagramAccount connectedAccount;

    private CreatorPostingPatternService service;

    @BeforeEach
    void setUp() {
        service = new CreatorPostingPatternService(
                mediaMetricsRepository, creatorProfileRepository, connectedAccount);
        // No connection on file in these tests: the service must fall back to the unnarrowed
        // read rather than hide a disconnected creator's own history.
        lenient().when(connectedAccount.currentAccountId(PROFILE_ID)).thenReturn(java.util.Optional.empty());
        CreatorProfile profile = mock(CreatorProfile.class);
        lenient().when(profile.getId()).thenReturn(PROFILE_ID);
        lenient()
                .when(creatorProfileRepository.findByUserId(CREATOR_USER_ID))
                .thenReturn(Optional.of(profile));
    }

    private void stubRows(List<MediaMetric> rows) {
        when(mediaMetricsRepository.findNewestSnapshotPerPostSince(eq(PROFILE_ID), any()))
                .thenReturn(rows);
    }

    private static Instant ist(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(IST).toInstant();
    }

    private static MediaMetric post(
            String id,
            String mediaId,
            Instant time,
            Instant postedAt,
            Long reach,
            Long engagement,
            String mediaType) {
        return MediaMetric.builder()
                .id(id)
                .mediaId(mediaId)
                .creatorProfileId(PROFILE_ID)
                .platform("INSTAGRAM")
                .mediaType(mediaType)
                .time(time)
                .postedAt(postedAt)
                .reach(reach)
                .engagement(engagement)
                .build();
    }

    /** A single-snapshot "valid" (non-null, positive reach) IMAGE post at a given IST date/time. */
    private static MediaMetric validPost(
            String mediaId, LocalDate date, int hour, int minute, long reach, long engagement) {
        return validPostOfType(mediaId, date, hour, minute, reach, engagement, "IMAGE");
    }

    private static MediaMetric validPostOfType(
            String mediaId,
            LocalDate date,
            int hour,
            int minute,
            long reach,
            long engagement,
            String mediaType) {
        Instant postedAt = ist(date, hour, minute);
        return post(
                "row-" + mediaId + "-" + hour + "-" + minute,
                mediaId,
                postedAt,
                postedAt,
                reach,
                engagement,
                mediaType);
    }

    // -----------------------------------------------------------------------------------------
    // Profile resolution
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("analyse: no creator profile for the user id is a 404, not a silent empty pattern")
    void missingProfileThrows() {
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.analyse(CREATOR_USER_ID, TODAY));
        assertEquals("CREATOR_PROFILE_NOT_FOUND", ex.getCode());
    }

    // -----------------------------------------------------------------------------------------
    // Dedup: one row per post -- THE most important rule
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("three snapshots of ONE post count as one post -- newest snapshot by `time` wins")
    void threeSnapshotsOfOnePostCountAsOne() {
        Instant postedAt = ist(TODAY.minusDays(1), 10, 0);
        List<MediaMetric> rows = new ArrayList<>();
        // Same media_id, three poll snapshots at different `time`s (out of chronological order,
        // on purpose -- the dedupe must not depend on list order). Only the newest-by-time row
        // (row2) should survive.
        rows.add(post("row1", "media-1", postedAt.plusSeconds(3600), postedAt, 100L, 5L, "IMAGE"));
        rows.add(post("row2", "media-1", postedAt.plusSeconds(7200), postedAt, 200L, 10L, "IMAGE"));
        rows.add(post("row3", "media-1", postedAt.plusSeconds(1800), postedAt, 50L, 2L, "IMAGE"));
        // Pad with 9 other distinct single-snapshot posts so the sample clears the enoughData
        // floor -- without padding, postsCounted would be withheld below 10 regardless of dedupe.
        for (int i = 0; i < 9; i++) {
            rows.add(validPost("media-pad-" + i, TODAY.minusDays(2), 10, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        // 1 (deduped media-1) + 9 padding = 10. If dedupe failed, this would be 12.
        assertEquals(10, pattern.postsCounted());
    }

    // -----------------------------------------------------------------------------------------
    // 90-day window
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a post older than 90 days is excluded; one exactly on the boundary is included")
    void windowBoundary() {
        List<MediaMetric> rows = new ArrayList<>();
        Instant onBoundary = ist(TODAY.minusDays(90), 10, 0);
        rows.add(post("boundary", "media-boundary", onBoundary, onBoundary, 100L, 10L, "IMAGE"));
        Instant tooOld = ist(TODAY.minusDays(91), 10, 0);
        rows.add(post("too-old", "media-too-old", tooOld, tooOld, 100L, 10L, "IMAGE"));
        for (int i = 0; i < 9; i++) {
            rows.add(validPost("media-pad-" + i, TODAY.minusDays(1), 10, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        // boundary (1) + 9 padding = 10; too-old must not be counted.
        assertEquals(10, pattern.postsCounted());
    }

    // -----------------------------------------------------------------------------------------
    // Reach filter
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a post with null reach, and one with reach 0, are excluded from counts and averages")
    void nullAndZeroReachExcluded() {
        List<MediaMetric> rows = new ArrayList<>();
        rows.add(
                post(
                        "null-reach",
                        "media-null",
                        ist(TODAY, 10, 0),
                        ist(TODAY, 10, 0),
                        null,
                        10L,
                        "IMAGE"));
        rows.add(
                post(
                        "zero-reach",
                        "media-zero",
                        ist(TODAY, 10, 0),
                        ist(TODAY, 10, 0),
                        0L,
                        10L,
                        "IMAGE"));
        for (int i = 0; i < 10; i++) {
            rows.add(validPost("media-pad-" + i, TODAY.minusDays(1), 10, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        // Only the 10 padding posts are counted; null/zero-reach posts never enter any average.
        assertEquals(10, pattern.postsCounted());
    }

    // -----------------------------------------------------------------------------------------
    // enoughData threshold
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("9 counted posts -> enoughData false, a note is set, and windows is empty")
    void ninePostsIsNotEnough() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            rows.add(validPost("media-" + i, TODAY.minusDays(1), 10, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        assertFalse(pattern.enoughData());
        assertEquals(9, pattern.postsCounted());
        assertTrue(pattern.windows().isEmpty());
        assertNull(pattern.bestPostType());
        assertTrue(pattern.note() != null && !pattern.note().isBlank());
    }

    @Test
    @DisplayName("10 counted posts -> enoughData true")
    void tenPostsIsEnough() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(validPost("media-" + i, TODAY.minusDays(1), 10, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        assertTrue(pattern.enoughData());
        assertEquals(10, pattern.postsCounted());
        assertNull(pattern.note());
    }

    // -----------------------------------------------------------------------------------------
    // Bucket minimum
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a bucket with 2 posts is not reported while one with 3 is")
    void bucketMinimumIsThree() {
        LocalDate weekday = TODAY.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekend = TODAY.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));

        List<MediaMetric> rows = new ArrayList<>();
        // "weekday morning" bucket: exactly 2 posts -- must NOT be reported.
        rows.add(validPost("wd-morning-1", weekday, 6, 0, 100, 10));
        rows.add(validPost("wd-morning-2", weekday, 7, 0, 100, 10));
        // "weekend evening" bucket: exactly 3 posts -- MUST be reported.
        rows.add(validPost("we-evening-1", weekend, 18, 0, 100, 10));
        rows.add(validPost("we-evening-2", weekend, 19, 0, 100, 10));
        rows.add(validPost("we-evening-3", weekend, 20, 0, 100, 10));
        // Pad to clear the enoughData floor, in a THIRD bucket ("weekend night") so neither bucket
        // under test crosses 3 by coincidence of shared padding.
        for (int i = 0; i < 5; i++) {
            rows.add(validPost("pad-" + i, weekend, 1, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        assertTrue(pattern.enoughData());
        List<String> labels = pattern.windows().stream().map(PatternWindow::label).toList();
        assertFalse(labels.contains("weekday morning"), "a 2-post bucket must not be reported: " + labels);
        assertTrue(labels.contains("weekend evening"), "a 3-post bucket must be reported: " + labels);
    }

    // -----------------------------------------------------------------------------------------
    // IST daypart boundaries
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("IST daypart boundaries: 16:59 is afternoon, 17:00 is evening, 23:30 is night")
    void istDaypartBoundaries() {
        LocalDate weekday = TODAY.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        List<MediaMetric> rows = new ArrayList<>();
        // 16:59 IST = 11:29 UTC; 17:00 IST = 11:30 UTC; 23:30 IST = 18:00 UTC. A missing IST
        // conversion (using the instant's own UTC hour) would put every one of these in the wrong
        // daypart.
        for (int i = 0; i < 3; i++) {
            rows.add(validPost("afternoon-" + i, weekday, 16, 59, 100, 10));
        }
        for (int i = 0; i < 3; i++) {
            rows.add(validPost("evening-" + i, weekday, 17, 0, 100, 10));
        }
        for (int i = 0; i < 3; i++) {
            rows.add(validPost("night-" + i, weekday, 23, 30, 100, 10));
        }
        rows.add(validPost("pad-0", weekday, 8, 0, 100, 10));
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        List<String> labels = pattern.windows().stream().map(PatternWindow::label).toList();
        assertTrue(labels.contains("weekday afternoon"), labels.toString());
        assertTrue(labels.contains("weekday evening"), labels.toString());
        assertTrue(labels.contains("weekday night"), labels.toString());
    }

    // -----------------------------------------------------------------------------------------
    // Weekday vs weekend split is by IST day
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "weekday vs weekend split is by IST day, not UTC -- IST Saturday 02:00 (UTC Friday"
                    + " 20:30) is weekend, never weekday")
    void weekendSplitIsByIstDay() {
        LocalDate saturday = TODAY.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        // IST Saturday 02:00 is UTC Friday 20:30 -- a bug that reads the day-of-week off the
        // instant's UTC calendar day would call this "Friday" (weekday), not "Saturday" (weekend).
        Instant saturdayEarlyMorningIst = saturday.atTime(2, 0).atZone(IST).toInstant();

        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(
                    post(
                            "sat-night-" + i,
                            "media-sat-" + i,
                            saturdayEarlyMorningIst,
                            saturdayEarlyMorningIst,
                            100L,
                            10L,
                            "IMAGE"));
        }
        for (int i = 0; i < 7; i++) {
            rows.add(validPost("pad-" + i, saturday.minusDays(3), 10, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        List<String> labels = pattern.windows().stream().map(PatternWindow::label).toList();
        assertTrue(labels.contains("weekend night"), labels.toString());
        assertFalse(
                labels.stream().anyMatch(l -> l.startsWith("weekday") && l.endsWith("night")),
                labels.toString());
    }

    // -----------------------------------------------------------------------------------------
    // Best post type
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("best post type: null with only one type present")
    void bestPostTypeNullWithOneType() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(validPost("media-" + i, TODAY.minusDays(1), 10, 0, 100, 10));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        assertNull(pattern.bestPostType());
    }

    @Test
    @DisplayName(
            "best post type: null when no type reaches 3 posts, even with several types present")
    void bestPostTypeNullWhenNoTypeReachesThree() {
        List<MediaMetric> rows = new ArrayList<>();
        String[] types = {"IMAGE", "VIDEO", "CAROUSEL", "REEL", "STORY"};
        int hour = 8;
        for (String type : types) {
            for (int i = 0; i < 2; i++) {
                rows.add(validPostOfType("media-" + type + i, TODAY.minusDays(1), hour, 0, 100, 10, type));
                hour++;
            }
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        assertEquals(10, pattern.postsCounted());
        assertNull(pattern.bestPostType());
    }

    @Test
    @DisplayName("best post type: set (highest average engagement rate) when it has 3+ posts")
    void bestPostTypeSetWhenBestHasThreePosts() {
        List<MediaMetric> rows = new ArrayList<>();
        // VIDEO: 3 posts, 50% engagement rate each.
        for (int i = 0; i < 3; i++) {
            Instant postedAt = ist(TODAY.minusDays(1), 8 + i, 0);
            rows.add(
                    post(
                            "video-" + i,
                            "media-video-" + i,
                            postedAt,
                            postedAt,
                            100L,
                            50L,
                            "VIDEO"));
        }
        // IMAGE: 7 posts, 5% engagement rate each -- more posts, but a lower rate.
        for (int i = 0; i < 7; i++) {
            Instant postedAt = ist(TODAY.minusDays(1), 12 + i, 0);
            rows.add(
                    post(
                            "image-" + i,
                            "media-image-" + i,
                            postedAt,
                            postedAt,
                            100L,
                            5L,
                            "IMAGE"));
        }
        stubRows(rows);

        PostingPattern pattern = service.analyse(CREATOR_USER_ID, TODAY);

        assertEquals(10, pattern.postsCounted());
        assertEquals("VIDEO", pattern.bestPostType());
    }
}
