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
    /** V20260924120000: rows now name the Instagram account they were read from. */
    private static final String IG_ACCOUNT = "ig-acct-1";
    private static final Instant FETCHED_AT = Instant.parse("2026-09-02T12:00:00Z");

    @Test
    @DisplayName("toMediaMetric: maps every supported insight metric onto its column")
    void testMapsAllMetrics() {
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(
                        CREATOR_ID, PLATFORM, IG_ACCOUNT, mediaItem(), fullInsights(), FETCHED_AT);

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
                        CREATOR_ID, PLATFORM, IG_ACCOUNT, mediaItem(), fullInsights(), FETCHED_AT);

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
                MediaMetricMapper.toMediaMetric(CREATOR_ID, PLATFORM, IG_ACCOUNT, mediaItem(), partial, FETCHED_AT);

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
                MediaMetricMapper.toMediaMetric(CREATOR_ID, PLATFORM, IG_ACCOUNT, mediaItem(), null, FETCHED_AT);

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
                        CREATOR_ID, PLATFORM, IG_ACCOUNT, mediaItem(), fullInsights(), FETCHED_AT);

        assertEquals(340L, row.getLikes(), "insights value, not the media item's 300");
    }

    @Test
    @DisplayName("toMediaMetric: carries caption and permalink, and stamps META_API provenance")
    void testCarriesCaptionAndProvenance() {
        MediaMetric row =
                MediaMetricMapper.toMediaMetric(
                        CREATOR_ID, PLATFORM, IG_ACCOUNT, mediaItem(), fullInsights(), FETCHED_AT);

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
                        "media_1", "c", null, null, null, "2026-08-20T10:30:00+0000", 1L, 1L, null);

        MediaMetric row =
                MediaMetricMapper.toMediaMetric(CREATOR_ID, PLATFORM, IG_ACCOUNT, noType, null, FETCHED_AT);

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
                        CREATOR_ID, PLATFORM, IG_ACCOUNT, mediaItem(), fullInsights(), FETCHED_AT);

        assertEquals(Instant.parse("2026-08-20T10:30:00Z"), row.getPostedAt());
    }

    // --- preview image: which URL, and only from Meta's image CDNs ---

    private static final String IG_MP4 =
            "https://scontent-bom1-1.cdninstagram.com/o1/v/t16/f2/m86/video.mp4?oe=6A1B2C3D&oh=00_sig";
    private static final String IG_COVER =
            "https://scontent-bom1-2.cdninstagram.com/v/t51.29350-15/cover.jpg?oe=6A1B2C3D&oh=00_sig";
    private static final String IG_IMAGE =
            "https://scontent.cdninstagram.com/v/t51.29350-15/image.jpg?oe=6A1B2C3D&oh=00_sig";
    private static final String FB_IMAGE =
            "https://scontent.xx.fbcdn.net/v/t51.29350-15/image.jpg?oe=6A1B2C3D&oh=00_sig";

    @Test
    @DisplayName("previewImageUrl: VIDEO uses thumbnail_url, never the mp4 media_url")
    void testVideoUsesThumbnailNeverMediaUrl() {
        assertEquals(IG_COVER, previewOf(itemWithUrls("VIDEO", IG_MP4, IG_COVER)));
    }

    @Test
    @DisplayName("previewImageUrl: REELS uses thumbnail_url, never the mp4 media_url")
    void testReelsUsesThumbnailNeverMediaUrl() {
        assertEquals(IG_COVER, previewOf(itemWithUrls("REELS", IG_MP4, IG_COVER)));
    }

    @Test
    @DisplayName("previewImageUrl: VIDEO/REELS with no thumbnail_url is null, not the mp4")
    void testVideoWithoutThumbnailIsNullNotMp4() {
        // The media_url fallback is for images only; for a video it would put a video file in an
        // <img>, which renders nothing.
        assertNull(previewOf(itemWithUrls("VIDEO", IG_MP4, null)));
        assertNull(previewOf(itemWithUrls("REELS", IG_MP4, null)));
    }

    @Test
    @DisplayName("previewImageUrl: IMAGE and CAROUSEL_ALBUM use media_url")
    void testImageAndCarouselUseMediaUrl() {
        assertEquals(IG_IMAGE, previewOf(itemWithUrls("IMAGE", IG_IMAGE, IG_COVER)));
        assertEquals(IG_IMAGE, previewOf(itemWithUrls("CAROUSEL_ALBUM", IG_IMAGE, null)));
        assertEquals(FB_IMAGE, previewOf(itemWithUrls("IMAGE", FB_IMAGE, null)));
    }

    @Test
    @DisplayName("previewImageUrl: IMAGE with no media_url falls back to thumbnail_url")
    void testImageFallsBackToThumbnail() {
        assertEquals(IG_COVER, previewOf(itemWithUrls("IMAGE", null, IG_COVER)));
    }

    @Test
    @DisplayName(
            "previewImageUrl: rejects http, other hosts, look-alike hosts and userinfo URLs -> null")
    void testRejectsUntrustedUrls() {
        String[] rejected = {
            "http://scontent.cdninstagram.com/v/image.jpg",
            "https://cdn.example/img.jpg",
            "https://cdninstagram.com.evil.com/v/image.jpg",
            "https://scontent.cdninstagram.com.evil.com/v/image.jpg",
            "https://evilcdninstagram.com/v/image.jpg",
            "https://fbcdn.net.evil.com/v/image.jpg",
            "https://user:pw@scontent.cdninstagram.com/v/image.jpg",
            "https://evil.com@scontent.cdninstagram.com/v/image.jpg",
            "javascript:alert(1)",
            "not a url",
            ""
        };
        for (String url : rejected) {
            assertNull(previewOf(itemWithUrls("IMAGE", url, null)), "IMAGE media_url " + url);
            assertNull(previewOf(itemWithUrls("VIDEO", IG_MP4, url)), "VIDEO thumbnail_url " + url);
            assertNull(previewOf(itemWithUrls("REELS", null, url)), "REELS thumbnail_url " + url);
        }
    }

    @Test
    @DisplayName("previewImageUrl: the bare apex hosts are accepted")
    void testApexHostsAccepted() {
        assertEquals(
                "https://cdninstagram.com/i.jpg",
                previewOf(itemWithUrls("IMAGE", "https://cdninstagram.com/i.jpg", null)));
        assertEquals(
                "https://fbcdn.net/i.jpg",
                previewOf(itemWithUrls("IMAGE", "https://fbcdn.net/i.jpg", null)));
    }

    @Test
    @DisplayName("previewImageUrl: a 521-character signed URL survives the mapper untruncated")
    void testLongSignedUrlUntruncated() {
        String prefix =
                "https://scontent-bom1-1.cdninstagram.com/v/t51.29350-15/cover.jpg?oe=6A1B2C3D&oh=00_";
        String url = prefix + "a".repeat(521 - prefix.length());
        assertEquals(521, url.length());

        String stored = previewOf(itemWithUrls("REELS", IG_MP4, url));

        assertEquals(url, stored);
        assertEquals(521, stored.length());
    }

    @Test
    @DisplayName(
            "previewImageUrl: a link longer than the 2048 column degrades to null instead of"
                    + " failing the whole poll batch")
    void testOverlongUrlIsDroppedNotPersisted() {
        String prefix = "https://scontent.cdninstagram.com/v/cover.jpg?oh=00_";
        String atLimit = prefix + "a".repeat(MediaMetricMapper.PREVIEW_IMAGE_URL_MAX_LENGTH - prefix.length());
        String overLimit = atLimit + "a";
        assertEquals(2048, atLimit.length());

        assertEquals(atLimit, previewOf(itemWithUrls("REELS", IG_MP4, atLimit)));
        assertNull(
                previewOf(itemWithUrls("REELS", IG_MP4, overLimit)),
                "a 2049-char link would make saveAll throw Data too long and lose every media row"
                        + " for this creator this cycle");
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
                25L,
                null);
    }

    private static InstagramMediaResponse.MediaItem itemWithUrls(
            String mediaType, String mediaUrl, String thumbnailUrl) {
        return new InstagramMediaResponse.MediaItem(
                "media_p",
                "c",
                mediaType,
                mediaUrl,
                "https://instagram.com/p/p",
                "2026-08-20T10:30:00+0000",
                1L,
                1L,
                thumbnailUrl);
    }

    private static String previewOf(InstagramMediaResponse.MediaItem item) {
        return MediaMetricMapper.toMediaMetric(CREATOR_ID, PLATFORM, IG_ACCOUNT, item, null, FETCHED_AT)
                .getPreviewImageUrl();
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
