package com.influora.service.creatorcopilot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.EvidenceType;
import java.time.Instant;
import java.util.List;

/**
 * Meera intelligence v1 (spec &sect;3.3) -- the numeric Creator Intelligence Profile that {@link
 * CreatorIntelligenceService#profile} works out on every read. Nothing here is stored: it is
 * computed only from the creator's own {@code media_metrics} readings, so the AI can never write
 * to it and it can always be rebuilt. {@code GetMyContentPatternsExecutor} renders it into the
 * wire record, the same service/executor split as {@code plan_my_week}.
 *
 * <p>Every claim carries an {@link Evidence}: its source type and the ids of the posts it rests
 * on. There is deliberately no confidence number anywhere in this record.
 *
 * <p>The one piece of text here is {@link PostStat#captionFirstLine()}: the first line of the
 * creator's own caption on a best or weak post (ADR 2026-09-26-creator-own-caption-to-meera). It
 * is redacted from {@code toString()} so logging this record can never log it.
 *
 * @param available false when the creator has no live Instagram connection
 * @param reason {@link CreatorIntelligenceService#REASON_NOT_CONNECTED} when {@code available} is
 *     false, else null
 * @param enoughData false below {@link CreatorPostingPatternService#MIN_POSTS_FOR_PATTERN} settled
 *     posts; everything below is then withheld, the baseline included
 * @param settledPosts settled posts with a real reach, from the last 90 days, on the connected
 *     account (before the {@link CreatorIntelligenceService#MAX_POSTS} cap)
 * @param unsettledPosts posts in the window whose newest reading was taken less than 48 h after
 *     posting; they are in no median, list or group
 * @param postsUsed how many of {@code settledPosts} the claims were computed from (the cap)
 * @param asOf the newest reading time across the posts used, or null when withheld
 * @param followedRecommendations slice 2 (spec 8.4): per source, how many decided
 *     recommendations she followed and how those posts did against her usual as of each post;
 *     empty when there are none or she is not connected
 */
public record CreatorIntelligenceProfile(
        boolean available,
        String reason,
        boolean enoughData,
        int settledPosts,
        int unsettledPosts,
        int postsUsed,
        int minPostsNeeded,
        int lookbackDays,
        Instant asOf,
        List<BaselineStat> baseline,
        List<PostStat> bestPosts,
        List<PostStat> weakPosts,
        List<GroupStat> whatWorks,
        List<FollowedStat> followedRecommendations) {

    /** The four baseline metrics. Engagement rate is interactions per REACH. */
    public enum Metric {
        REACH,
        VIEWS,
        INTERACTIONS,
        ENGAGEMENT_RATE
    }

    /** What a "what works" group is grouped by. */
    public enum PatternKind {
        POST_TYPE,
        POSTING_WINDOW
    }

    /** Which of the creator's own usuals a group beat by at least 20%. */
    public enum BeatsOn {
        REACH,
        ENGAGEMENT
    }

    /**
     * Where a claim comes from and the posts it rests on. {@link #sampleSize()} is derived from
     * {@code postIds}, so the two cannot disagree.
     *
     * @param baselineSampleSize set on per-post claims only: how many posts the usual they are
     *     compared with rests on
     */
    public record Evidence(EvidenceType type, List<String> postIds, Integer baselineSampleSize) {
        public int sampleSize() {
            return postIds.size();
        }
    }

    /** One baseline metric: the median over its own sample of posts. */
    public record BaselineStat(Metric metric, double median, Evidence evidence) {}

    /**
     * One best or weak post against the REACH baseline.
     *
     * @param type null when Meta's {@code media_type} is not one of the known values
     * @param engagementRate interactions per reach, or null when interactions are unknown (never 0)
     * @param captionFirstLine the first line of the creator's OWN caption for this post, cleaned by
     *     {@link CreatorOwnCaption#firstLine} (max 100 chars, no @handles or links), or null when
     *     there is none. Creator-scoped Meera only (ADR 2026-09-26-creator-own-caption-to-meera):
     *     never logged, which is why {@link #toString()} leaves it out, never serialised
     *     ({@code @JsonIgnore}), and never on a brand path.
     */
    public record PostStat(
            String postId,
            ChallengeDayType type,
            Instant postedAt,
            String window,
            String permalink,
            long reach,
            double reachRatio,
            Double engagementRate,
            Evidence evidence,
            // Never serialised: only the creator tool DTO PostReading carries the line on the
            // wire (ADR 2026-09-26-creator-own-caption-to-meera, defence in depth).
            @JsonIgnore String captionFirstLine) {

        /** Every component except the caption line, which must never reach a log. */
        @Override
        public String toString() {
            return "PostStat[postId=" + postId
                    + ", type=" + type
                    + ", postedAt=" + postedAt
                    + ", window=" + window
                    + ", permalink=" + permalink
                    + ", reach=" + reach
                    + ", reachRatio=" + reachRatio
                    + ", engagementRate=" + engagementRate
                    + ", evidence=" + evidence
                    + ", captionFirstLine=" + (captionFirstLine == null ? "null" : "<redacted>")
                    + "]";
        }
    }

    /**
     * A post type or posting window whose median beats the creator's own usual by at least 20%.
     *
     * @param medianEngagementRate null when fewer than 3 posts in the group have interactions
     * @param engagementLift null when {@code medianEngagementRate} or the ER baseline is missing
     */
    public record GroupStat(
            PatternKind kind,
            String label,
            int posts,
            double medianReach,
            double reachLift,
            Double medianEngagementRate,
            Double engagementLift,
            List<BeatsOn> beatsOn,
            Evidence evidence) {}

    /**
     * Slice 2 (spec 8.4) -- the recommendations of one source whose outcome is decided, how many
     * she followed (a post of the recommended type filled it), and, only when at least 3 followed
     * ones settled against a baseline of 10 or more posts, the median of their reach against her
     * usual as of each post. A recommendation she did not follow is never counted as a failure.
     *
     * @param medianReachVsUsualPct median of the settled rows' {@code reach_vs_baseline_pct}, or
     *     null below the 3-post floor
     * @param evidence the posts the median rests on (the followed rows settled with a percentage)
     */
    public record FollowedStat(
            CreatorRecommendationSource source,
            int recommended,
            int followed,
            Double medianReachVsUsualPct,
            Evidence evidence) {}
}
