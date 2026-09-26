package com.influora.service.creatorcopilot;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MediaMetricsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CONTENT-TOPICS's sibling for {@code plan_my_week} -- deterministic arithmetic over a creator's
 * OWN {@code media_metrics} rows. No model is ever involved in computing a {@link PostingPattern}:
 * every number here is plain Java arithmetic over data the creator's own posts produced, which is
 * exactly why {@link PatternWindow#engagementRate} is a pre-formatted string rather than a number
 * the model could be asked to divide itself.
 *
 * <p><b>ONE ROW PER POST -- the single most important rule in this class.</b> {@code media_metrics}
 * (V21) is a snapshot-PER-POLL table: {@code MetricsPollingJob} re-fetches the latest 25 posts on
 * every poll, so one real post shows up as many rows, each stamped with the fetch time ({@code
 * time}). Counting raw rows would multiply every post by however many times it happened to be
 * polled, which would silently inflate both the bucket counts and the {@code enoughData} threshold
 * for a creator who has been on the platform a while. {@link #analyse} therefore collapses every
 * {@code media_id} down to its newest snapshot (by {@code time}) before anything else runs.
 *
 * <p><b>Profile resolution mirrors {@link ContentTopicService}.</b> The creator's {@code
 * creator_profiles} row is resolved from the verified {@code creatorUserId} via {@link
 * CreatorProfileRepository#findByUserId} -- never from a caller-supplied profile id -- and a
 * missing profile is a 404 ({@code CREATOR_PROFILE_NOT_FOUND}), not a silently empty pattern.
 *
 * <p><b>{@code today} is decided entirely by the CALLER</b>, exactly like {@link
 * ContentTopicService#topicsFor}: this class never calls {@code LocalDate.now()} itself.
 * {@code GetPlanMyWeekExecutor} is the one production caller and passes {@code
 * LocalDate.now(ZoneId.of("Asia/Kolkata"))}.
 */
@Service
public class CreatorPostingPatternService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * The pattern looks back this many days from (and including) {@code today}. A post posted
     * exactly {@link #LOOKBACK_DAYS} days before {@code today} is INCLUDED (the boundary is
     * inclusive on both ends); a post one day older than that is excluded.
     */
    public static final int LOOKBACK_DAYS = 90;

    /** A bucket is reported only when at least this many valid posts fall in it. */
    public static final int MIN_BUCKET_POSTS = 3;

    /** A media type is eligible as "best post type" only with at least this many valid posts. */
    public static final int MIN_BEST_TYPE_POSTS = 3;

    /**
     * Below this many valid (deduped, in-window, non-null/non-zero-reach) posts, the whole pattern
     * is withheld rather than reported off a thin sample.
     */
    public static final int MIN_POSTS_FOR_PATTERN = 10;

    private final MediaMetricsRepository mediaMetricsRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final ConnectedInstagramAccount connectedAccount;

    public CreatorPostingPatternService(
            MediaMetricsRepository mediaMetricsRepository,
            CreatorProfileRepository creatorProfileRepository,
            ConnectedInstagramAccount connectedAccount) {
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.connectedAccount = connectedAccount;
    }

    /**
     * The full deterministic posting-pattern result.
     *
     * @param enoughData false when fewer than {@link #MIN_POSTS_FOR_PATTERN} valid posts were
     *     found; when false, {@code bestPostType} is null and {@code windows} is empty regardless
     *     of what a thin sample might otherwise have suggested
     * @param postsCounted the number of posts that survived deduplication, the 90-day window, AND
     *     the reach filter -- a post with null or zero reach is never part of this count
     * @param bestPostType the {@code media_type} with the highest average engagement rate among
     *     types with at least {@link #MIN_BEST_TYPE_POSTS} valid posts, or null when fewer than two
     *     distinct types are present among the valid posts, or when no type reaches the threshold
     * @param windows day-part/weekday buckets with at least {@link #MIN_BUCKET_POSTS} valid posts,
     *     sorted best (highest average engagement rate) first
     * @param note non-null only when {@code enoughData} is false, explaining why the pattern was
     *     withheld
     */
    public record PostingPattern(
            boolean enoughData,
            int postsCounted,
            String bestPostType,
            List<PatternWindow> windows,
            String note) {}

    /**
     * One weekday/weekend x day-part bucket, e.g. {@code label = "weekday morning"}.
     *
     * @param engagementRate pre-formatted, e.g. {@code "4.8%"} -- the model must never do
     *     arithmetic on this string, only display it
     */
    public record PatternWindow(String label, int posts, String engagementRate) {}

    /**
     * The creator-facing read: a deterministic {@link PostingPattern} for {@code creatorUserId} as
     * of {@code today}.
     */
    @Transactional(readOnly = true)
    public PostingPattern analyse(String creatorUserId, LocalDate today) {
        CreatorProfile profile = resolveProfile(creatorUserId);

        // The database returns one row per post already (newest snapshot per media_id, posted in
        // the window). The Java dedupe below stays as a second line of defence -- it is what the
        // snapshot test pins -- but it is no longer what keeps this read from loading every poll
        // this creator has ever had.
        // Only the account the creator is connected to today. "Best time to post" read over 90
        // days of a profile that has switched Instagram accounts was averaging the posting habits
        // of two or three different accounts into one recommendation.
        java.time.Instant windowStart = today.minusDays(LOOKBACK_DAYS).atStartOfDay(IST).toInstant();
        String igAccountId = connectedAccount.currentAccountId(profile.getId()).orElse(null);
        List<MediaMetric> rawRows =
                igAccountId == null
                        ? mediaMetricsRepository.findNewestSnapshotPerPostSince(profile.getId(), windowStart)
                        : mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccount(
                                profile.getId(), windowStart, igAccountId);

        List<MediaMetric> newestPerPost = dedupeToNewestSnapshotPerPost(rawRows);
        List<MediaMetric> inWindow = filterToWindow(newestPerPost, today);
        List<MediaMetric> valid = filterToValidReach(inWindow);

        int postsCounted = valid.size();
        if (postsCounted < MIN_POSTS_FOR_PATTERN) {
            return new PostingPattern(
                    false,
                    postsCounted,
                    null,
                    List.of(),
                    "Not enough posts yet to spot a pattern -- need at least "
                            + MIN_POSTS_FOR_PATTERN
                            + " posts with reach data from the last "
                            + LOOKBACK_DAYS
                            + " days, found "
                            + postsCounted
                            + ".");
        }

        return new PostingPattern(true, postsCounted, bestPostType(valid), buildWindows(valid), null);
    }

    private CreatorProfile resolveProfile(String creatorUserId) {
        return creatorProfileRepository
                .findByUserId(creatorUserId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "CREATOR_PROFILE_NOT_FOUND",
                                        "Creator profile not found",
                                        HttpStatus.NOT_FOUND));
    }

    /**
     * Collapses every {@code media_id} to its single newest snapshot (by {@link
     * MediaMetric#getTime()}), regardless of the order {@code rows} arrives in -- see the class
     * javadoc for why this is the one rule that matters most in this class.
     */
    private static List<MediaMetric> dedupeToNewestSnapshotPerPost(List<MediaMetric> rows) {
        Map<String, MediaMetric> newestByMediaId = new LinkedHashMap<>();
        for (MediaMetric row : rows) {
            MediaMetric existing = newestByMediaId.get(row.getMediaId());
            if (existing == null || row.getTime().isAfter(existing.getTime())) {
                newestByMediaId.put(row.getMediaId(), row);
            }
        }
        return List.copyOf(newestByMediaId.values());
    }

    /**
     * {@code posted_at} within the last {@link #LOOKBACK_DAYS} days ending {@code today}, both
     * boundaries inclusive, evaluated against the IST calendar day: the earliest instant kept is
     * the start of {@code today.minusDays(LOOKBACK_DAYS)} in Asia/Kolkata, and the latest instant
     * kept is the end of {@code today} in Asia/Kolkata. A post with a null {@code posted_at} is
     * dropped -- it cannot be placed in the window at all.
     */
    private static List<MediaMetric> filterToWindow(List<MediaMetric> posts, LocalDate today) {
        Instant windowStart = today.minusDays(LOOKBACK_DAYS).atStartOfDay(IST).toInstant();
        Instant windowEndExclusive = today.plusDays(1).atStartOfDay(IST).toInstant();

        List<MediaMetric> inWindow = new ArrayList<>();
        for (MediaMetric post : posts) {
            Instant postedAt = post.getPostedAt();
            if (postedAt == null) {
                continue;
            }
            if (!postedAt.isBefore(windowStart) && postedAt.isBefore(windowEndExclusive)) {
                inWindow.add(post);
            }
        }
        return inWindow;
    }

    /**
     * Engagement rate is only meaningful when reach is a real, positive denominator. A post with
     * null or zero reach is dropped here, which is what removes it from every average, every
     * bucket count, and {@link PostingPattern#postsCounted} -- there is no other filter point.
     */
    private static List<MediaMetric> filterToValidReach(List<MediaMetric> posts) {
        List<MediaMetric> valid = new ArrayList<>();
        for (MediaMetric post : posts) {
            Long reach = post.getReach();
            if (reach != null && reach > 0) {
                valid.add(post);
            }
        }
        return valid;
    }

    /**
     * One bucket per distinct (weekday|weekend, daypart) label actually populated by {@code valid},
     * capped to the ones with at least {@link #MIN_BUCKET_POSTS} posts, best (highest average
     * engagement rate) first.
     */
    private static List<PatternWindow> buildWindows(List<MediaMetric> valid) {
        Map<String, List<MediaMetric>> byBucket = new LinkedHashMap<>();
        for (MediaMetric post : valid) {
            byBucket.computeIfAbsent(CreatorPostRules.windowLabel(post.getPostedAt()), key -> new ArrayList<>()).add(post);
        }

        record ScoredWindow(String label, int posts, double rate) {}

        List<ScoredWindow> scored = new ArrayList<>();
        for (Map.Entry<String, List<MediaMetric>> entry : byBucket.entrySet()) {
            List<MediaMetric> posts = entry.getValue();
            if (posts.size() < MIN_BUCKET_POSTS) {
                continue;
            }
            scored.add(new ScoredWindow(entry.getKey(), posts.size(), averageEngagementRate(posts)));
        }
        scored.sort(Comparator.comparingDouble(ScoredWindow::rate).reversed());

        return scored.stream()
                .map(w -> new PatternWindow(w.label(), w.posts(), formatRate(w.rate())))
                .toList();
    }

    /**
     * The {@code media_type} with the highest average engagement rate among types with at least
     * {@link #MIN_BEST_TYPE_POSTS} valid posts -- null when fewer than two distinct {@code
     * media_type}s are present among {@code valid} at all (a single-type creator has nothing to
     * compare), or when no type individually reaches the post-count threshold.
     */
    private static String bestPostType(List<MediaMetric> valid) {
        Map<String, List<MediaMetric>> byType = new LinkedHashMap<>();
        for (MediaMetric post : valid) {
            byType.computeIfAbsent(post.getMediaType(), key -> new ArrayList<>()).add(post);
        }
        if (byType.size() < 2) {
            return null;
        }

        String best = null;
        double bestRate = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, List<MediaMetric>> entry : byType.entrySet()) {
            List<MediaMetric> posts = entry.getValue();
            if (posts.size() < MIN_BEST_TYPE_POSTS) {
                continue;
            }
            double rate = averageEngagementRate(posts);
            if (rate > bestRate) {
                bestRate = rate;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static double averageEngagementRate(List<MediaMetric> posts) {
        double sum = 0;
        for (MediaMetric post : posts) {
            sum += engagementRateOf(post);
        }
        return sum / posts.size();
    }

    /** Caller must have already filtered to posts with non-null, positive reach. */
    private static double engagementRateOf(MediaMetric post) {
        long engagement = post.getEngagement() == null ? 0L : post.getEngagement();
        long reach = post.getReach();
        return engagement / (double) reach;
    }

    private static String formatRate(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }
}
