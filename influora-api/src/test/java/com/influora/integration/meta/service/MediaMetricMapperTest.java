package com.influora.integration.meta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MediaMetric;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramInsightsResponse.InsightMetric;
import com.influora.integration.meta.dto.InstagramInsightsResponse.InsightValue;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MediaMetricMapper} (F-0479) — the metric-name-to-column mapping that
 * {@code MetricsPollingJob}'s TODO deferred. Pure deterministic mapping, no mocking required,
 * matching {@code QualityScoreServiceTest}'s conventions.
 */
class MediaMetricMapperTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";
    private static final String PLATFORM = "INSTAGRAM";
    private static final Instant FETCHED_AT = Instant.parse("2026-09-02T12:00:00Z");

    @Test
    @DisplayName("toMediaMetric: maps every supported insight metric onto its column")
    void testMapsAllMetrics() {
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(
                        CREATOR_ID, PLATFORM, mediaItem(), fullInsights(), FETCHED_AT);

        assertEquals(1200L, row.getReach());
        assertEquals(340L, row.getLikes());
        assertEquals(28L, row.getComments());
        assertEquals(57L, row.getSaves());
        assertEquals(12L, row.getShares());
        assertEquals(437L, row.getEngagement(), "total_interactions maps to the engagement column");
        assertEquals(5000L, row.getImpressions(), "views maps to the impressions column");
    }

    @Test
    @DisplayName("toMediaMetric: video_views stays null — Meta retired the metric, views is not copied twice")
    void testVideoViewsNotDoubleCounted() {
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(
                        CREATOR_ID, PLATFORM, mediaItem(), fullInsights(), FETCHED_AT);

        assertNull(
                row.getVideoViews(),
                "views already landed in impressions; duplicating it into video_views would report"
                        + " one Meta number twice under two names");
    }

    @Test
    @DisplayName("toMediaMetric: an unsupported metric stays NULL rather than becoming 0")
    void testAbsentMetricIsNullNotZero() {
        // Meta omits metrics it does not support for a media_type — e.g. no shares/saved on some
        // types. Absent must not become 0: F-0478 is what happens when the two are conflated.
        InstagramInsightsResponse partial =
                new InstagramInsightsResponse(List.of(metric("reach", 900)));

        MediaMetric row =
                MediaMetricMapper.toMediaMetric(CREATOR_ID, PLATFORM, mediaItem(), partial, FETCHED_AT);

        assertEquals(900L, row.getReach());
        assertNull(row.getSaves());
        assertNull(row.getShares());
        assertNull(row.getEngagement());
        assertNull(row.getImpressions());
    }

    @Test
    @DisplayName("toMediaMetric: null insights still writes a row from the media item's own counts")
    void testNullInsightsStillWritesRow() {
        // A rate-limited or 400'd per-item insights call degrades to null insights. The post is
        // still real and its like/comment counts came from the /media edge — dropping the row would
        // leave the creator unscored for a reason unrelated to their content.
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(CREATOR_ID, PLATFORM, mediaItem(), null, FETCHED_AT);

        assertNotNull(row);
        assertEquals(300L, row.getLikes(), "falls back to media_item like_count");
        assertEquals(25L, row.getComments(), "falls back to media_item comments_count");
        assertNull(row.getReach());
        assertNull(row.getEngagement());
    }

    @Test
    @DisplayName("toMediaMetric: insights likes win over the media item's own like_count")
    void testInsightsLikesPreferredOverMediaItem() {
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(
                        CREATOR_ID, PLATFORM, mediaItem(), fullInsights(), FETCHED_AT);

        assertEquals(340L, row.getLikes(), "insights value, not the media item's 300");
    }

    @Test
    @DisplayName("toMediaMetric: carries caption and permalink, and stamps META_API provenance")
    void testCarriesCaptionAndProvenance() {
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(
                        CREATOR_ID, PLATFORM, mediaItem(), fullInsights(), FETCHED_AT);

        assertEquals("a caption", row.getCaption(), "BrandSafetyScoreService reads this");
        assertEquals("https://instagram.com/p/abc", row.getPermalink());
        assertEquals(CreatorMetric.DATA_SOURCE_META_API, row.getDataSource());
        assertEquals(FETCHED_AT, row.getTime());
        assertEquals(FETCHED_AT, row.getFetchedAt());
        assertEquals("media_1", row.getMediaId());
        assertEquals(CREATOR_ID, row.getCreatorProfileId());
    }

    @Test
    @DisplayName("toMediaMetric: a null media_type becomes UNKNOWN rather than failing the insert")
    void testNullMediaTypeDefaulted() {
        InstagramMediaResponse.MediaItem noType =
                new InstagramMediaResponse.MediaItem(
                        "media_1", "c", null, null, null, "2026-08-20T10:30:00+0000", 1L, 1L);

        MediaMetric row =
                MediaMetricMapper.toMediaMetric(CREATOR_ID, PLATFORM, noType, null, FETCHED_AT);

        assertEquals("UNKNOWN", row.getMediaType(), "media_type is NOT NULL in V21");
    }

    // --- timestamp parsing: posted_at drives QualityScoreService's frequency window ---

    @Test
    @DisplayName("parseTimestamp: Meta's colon-less offset (+0000) parses, which Instant.parse cannot")
    void testParsesMetaOffsetFormat() {
        Instant parsed = MediaMetricMapper.parseTimestamp("2026-08-20T10:30:00+0000");

        assertEquals(Instant.parse("2026-08-20T10:30:00Z"), parsed);
    }

    @Test
    @DisplayName("parseTimestamp: a non-zero colon-less offset is converted to UTC, not truncated")
    void testParsesNonZeroOffset() {
        Instant parsed = MediaMetricMapper.parseTimestamp("2026-08-20T10:30:00+0530");

        assertEquals(Instant.parse("2026-08-20T05:00:00Z"), parsed);
    }

    @Test
    @DisplayName("parseTimestamp: a colon-bearing offset also parses")
    void testParsesColonOffset() {
        assertEquals(
                Instant.parse("2026-08-20T10:30:00Z"),
                MediaMetricMapper.parseTimestamp("2026-08-20T10:30:00+00:00"));
    }

    @Test
    @DisplayName("parseTimestamp: unparseable or missing input yields null, never Instant.now()")
    void testUnparseableTimestampIsNull() {
        // Substituting now() here would backdate an unknown post into the 30-day frequency window
        // and invent a posting cadence the creator does not have.
        assertNull(MediaMetricMapper.parseTimestamp(null));
        assertNull(MediaMetricMapper.parseTimestamp(""));
        assertNull(MediaMetricMapper.parseTimestamp("   "));
        assertNull(MediaMetricMapper.parseTimestamp("not-a-timestamp"));
    }

    @Test
    @DisplayName("toMediaMetric: a real Meta timestamp reaches posted_at")
    void testPostedAtPopulated() {
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(
                        CREATOR_ID, PLATFORM, mediaItem(), fullInsights(), FETCHED_AT);

        assertEquals(Instant.parse("2026-08-20T10:30:00Z"), row.getPostedAt());
    }

    // --- fixtures ---

    private InstagramMediaResponse.MediaItem mediaItem() {
        return new InstagramMediaResponse.MediaItem(
                "media_1",
                "a caption",
                "IMAGE",
                "https://cdn.example/img.jpg",
                "https://instagram.com/p/abc",
                "2026-08-20T10:30:00+0000",
                300L,
                25L);
    }

    private InstagramInsightsResponse fullInsights() {
        return new InstagramInsightsResponse(
                List.of(
                        metric("reach", 1200),
                        metric("likes", 340),
                        metric("comments", 28),
                        metric("saved", 57),
                        metric("shares", 12),
                        metric("views", 5000),
                        metric("total_interactions", 437)));
    }

    private static InsightMetric metric(String name, long value) {
        return new InsightMetric(
                name, "lifetime", name, name, List.of(new InsightValue(value, null)));
    }
}
