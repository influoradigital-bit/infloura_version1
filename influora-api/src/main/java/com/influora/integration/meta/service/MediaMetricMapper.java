package com.influora.integration.meta.service;

import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.MediaMetric;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.common.Ulids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Maps one fetched Instagram media item plus its insights onto a {@link MediaMetric} row (F-0479).
 *
 * <p><b>This class is the decision {@code MetricsPollingJob}'s TODO deferred.</b> That TODO said the
 * metric-name-to-field mapping "wasn't specced with enough precision to hardcode confidently",
 * naming two worries. Both are answered here rather than in a comment somewhere else:
 *
 * <ol>
 *   <li><b>"Which metrics apply to which media_type."</b> No media-type table is needed, and
 *       building one would be the wrong shape — it would have to be re-derived every time Meta
 *       changes a metric. Meta simply omits metrics it does not support for a type, and 400s on a
 *       few combinations; {@code InstagramMetricsFetcher} already degrades that 400 to a null
 *       insights response per item. So the mapping reads whatever came back and leaves the rest
 *       {@code null}, which every {@code media_metrics} metric column is. The type-compatibility
 *       question dissolves into "absent stays absent".
 *   <li><b>The name-to-column mapping</b>, which is genuinely non-obvious in three places and is
 *       spelled out on the constants and fields below.
 * </ol>
 *
 * <p>Pure function, no I/O, no repository access — the same shape as {@code QualityScoreService},
 * so it is unit-testable without mocking Meta.
 */
public final class MediaMetricMapper {

    /**
     * Meta stamps media timestamps as {@code 2024-01-15T10:30:00+0000} — a numeric offset with NO
     * colon, which {@link Instant#parse} rejects outright. Parsing this wrong is not cosmetic:
     * {@code posted_at} is what {@code QualityScoreService.calculateFrequencyScore} counts inside
     * its 30-day window, so a silently null timestamp scores every creator's cadence as 20/100.
     */
    private static final DateTimeFormatter META_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private MediaMetricMapper() {}

    /**
     * @param insights may be {@code null} — a rate-limited, rejected, or unsupported per-item
     *     insights call. The row is still written from the media item's own like/comment counts
     *     rather than dropped: a post that exists with a known like count is real data, and
     *     discarding it would leave the creator unscored for a reason unrelated to their content.
     */
    public static MediaMetric toMediaMetric(
            String creatorProfileId,
            String platform,
            InstagramMediaResponse.MediaItem media,
            InstagramInsightsResponse insights,
            Instant fetchedAt) {

        Long likes = InstagramInsightValues.metricValue(insights, InstagramInsightValues.LIKES);
        Long comments = InstagramInsightValues.metricValue(insights, InstagramInsightValues.COMMENTS);

        return MediaMetric.builder()
                .id(Ulids.newUlid())
                .time(fetchedAt)
                .mediaId(media.id())
                .creatorProfileId(creatorProfileId)
                .platform(platform)
                // NOT NULL in V21. Meta always sends media_type on the /media edge, but a null here
                // would fail the insert for the whole creator rather than this one post.
                .mediaType(media.mediaType() == null ? "UNKNOWN" : media.mediaType())
                // V26 column, BrandSafety pipeline input ONLY — BrandSafetyScoreService reads it
                // via getCaption(). Never surface raw caption text through a brand-facing DTO and
                // keep it out of logs (see the MediaMetric field javadoc).
                .caption(media.caption())
                .permalink(media.permalink())
                // `views` is Meta's unified view count and lands in `impressions`, matching the
                // mapping DeliverableVerificationService already uses for the same metric. The
                // `impressions` METRIC was deprecated 2025-04-21; the COLUMN is simply where the
                // surviving view count goes. `video_views` stays null on purpose: Meta retired that
                // metric, and copying `views` into it as well would double-count one number under
                // two names and imply a video-specific measurement we did not receive.
                .impressions(InstagramInsightValues.metricValue(insights, InstagramInsightValues.VIEWS))
                .reach(InstagramInsightValues.metricValue(insights, InstagramInsightValues.REACH))
                .engagement(
                        InstagramInsightValues.metricValue(
                                insights, InstagramInsightValues.TOTAL_INTERACTIONS))
                // The /media edge carries like_count/comments_count directly, so prefer the insights
                // figure and fall back to the media item's own. This is the one place a fallback is
                // right: both numbers come from Meta for the same post, so the fallback substitutes
                // a measurement for a measurement — not a fabricated value for an absent one.
                .likes(likes != null ? likes : media.likeCount())
                .comments(comments != null ? comments : media.commentsCount())
                .saves(InstagramInsightValues.metricValue(insights, InstagramInsightValues.SAVED))
                .shares(InstagramInsightValues.metricValue(insights, InstagramInsightValues.SHARES))
                .postedAt(parseTimestamp(media.timestamp()))
                .dataSource(CreatorMetric.DATA_SOURCE_META_API)
                .fetchedAt(fetchedAt)
                .build();
    }

    /**
     * @return the parsed instant, or {@code null} when Meta sent nothing or something unparseable —
     *     never {@code Instant.now()}, which would silently backdate an unknown post into the
     *     frequency window and invent a cadence the creator does not have.
     */
    static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, META_TIMESTAMP).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through — Meta has used a colon-bearing offset on some edges/versions.
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through.
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
