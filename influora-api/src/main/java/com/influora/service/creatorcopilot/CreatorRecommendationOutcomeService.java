package com.influora.service.creatorcopilot;

import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import com.influora.domain.enums.EvidenceType;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Evidence;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.FollowedStat;
import com.influora.service.creatorcopilot.CreatorRecommendationOutcomeWriter.Match;
import com.influora.service.creatorcopilot.CreatorRecommendationOutcomeWriter.Outcome;
import com.influora.service.creatorcopilot.CreatorRecommendationOutcomeWriter.Settlement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meera intelligence v1, slice 2 (spec 8.4) -- matches a creator's recommendations to the posts
 * that followed them and stores each settled outcome, lazily and deterministically, on the read
 * of her profile. Then summarises them as {@code followed_recommendations}.
 *
 * <h2>Transactions: one per row, all committed before the profile reads</h2>
 *
 * <p>{@link CreatorIntelligenceService#profile} calls {@link #evaluate} before it reads anything
 * else, with no transaction of its own open. {@link #evaluate} itself runs OUTSIDE any transaction
 * ({@code NOT_SUPPORTED}: it suspends one if a future caller has it open): its loads are plain
 * repository reads, and each row's decided outcome is written by {@link
 * CreatorRecommendationOutcomeWriter#apply} in its OWN {@code REQUIRES_NEW} transaction, through
 * that bean's proxy. So one bad row (a unique-key race, an optimistic-lock loss, a constraint)
 * rolls back only itself and is logged; the other rows still commit (Kabir M-2). A catch inside
 * one shared transaction would not do that: after a failed flush the transaction is rollback-only
 * and its commit discards every row. {@code profile} is deliberately not wrapped in {@code
 * @Transactional(readOnly = true)}: a REPEATABLE READ snapshot fixed at its first read could not
 * see rows committed later by the per-row transactions, so {@code followed_recommendations} would
 * lag a read behind. With every row committed first, every later read in {@code profile} sees its
 * outcome.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Match.</b> OPEN rows in {@code created_at, id} order each take the first post, in the
 *       challenge's candidate order ({@code posted_at, media_id}), that is not already claimed by
 *       any of her recommendations and whose IST date falls in {@code [recommended_for or the
 *       created_at date, match_until)}. Any post fills it; {@code matched_type} records whether
 *       the type matched ({@link CreatorPostRules#typeMatches}), exactly like the challenge's
 *       tick-off. {@code uk_creator_rec_media} makes one post fill at most one row.
 *   <li><b>Missed.</b> An OPEN row with no post once {@code match_until} (IST start of day) plus
 *       {@link CreatorPostRules#CHECKING_GRACE} has passed. For a plan or challenge day that is
 *       {@code recommended_for + 1 day + 12 h}.
 *   <li><b>Settle.</b> When the matched post's newest reading was taken at least {@link
 *       CreatorPostRules#SETTLING_PERIOD} after it was posted (the slice-1 reading-time rule). The
 *       baseline is AS OF THE POST: the median reach of her settled valid posts on the same
 *       account posted in {@code [posted_at - 90 d, posted_at)}, the post itself excluded, so a
 *       later rebuild computes the same number. Below {@link
 *       CreatorPostingPatternService#MIN_POSTS_FOR_PATTERN} only the sample size is stored and the
 *       percentage stays NULL. The percentage is clamped to the INT range, never thrown on.
 *   <li><b>No outcome.</b> A MATCHED row whose post has not settled by {@code match_until} (IST
 *       start of day) + {@link CreatorPostRules#SETTLING_PERIOD} + {@link #NO_OUTCOME_MARGIN}
 *       becomes NO_OUTCOME: the post was deleted, belongs to an account she switched away from,
 *       or dropped out of the 25-post poll, and would otherwise stay live, and scanned, forever
 *       (Kabir M-2).
 *   <li><b>Bounded scan.</b> Readings are loaded from the earliest live row's window start minus
 *       the 90-day baseline, but never from earlier than {@code now -} {@link #MAX_SCAN_DAYS}
 *       days. A row the clamped scan cannot judge fairly is not guessed at: an OPEN row whose
 *       window starts before the scan becomes NO_OUTCOME (not MISSED) once past its grace, and a
 *       MATCHED row whose post's 90-day baseline reaches before the scan is not settled on a
 *       partial baseline (it reaches its NO_OUTCOME deadline instead).
 *   <li><b>Frozen.</b> SETTLED, MISSED and NO_OUTCOME rows are never loaded for evaluation again,
 *       and the writer re-checks the status and {@code version} before every write.
 *   <li><b>Accounts.</b> Candidate posts and baselines use the same account rule as the profile:
 *       the connected account, with untagged legacy rows only for a creator who has only ever
 *       connected one account. Every decision stamps the account it was made on ({@code
 *       outcome_ig_account_id}); for an account-switcher {@link #followed} counts only rows
 *       decided on the current account.
 * </ul>
 */
@Service
public class CreatorRecommendationOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(CreatorRecommendationOutcomeService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final List<CreatorRecommendationStatus> LIVE =
            List.of(CreatorRecommendationStatus.OPEN, CreatorRecommendationStatus.MATCHED);

    /** After match_until + the 48 h settling period, a MATCHED row waits this long more, then NO_OUTCOME. */
    public static final Duration NO_OUTCOME_MARGIN = Duration.ofDays(7);

    /** The readings scan never reaches further back than this many days before now. */
    public static final int MAX_SCAN_DAYS = 180;

    private final CreatorRecommendationRepository repository;
    private final MediaMetricsRepository mediaMetricsRepository;
    private final CreatorRecommendationOutcomeWriter writer;

    public CreatorRecommendationOutcomeService(
            CreatorRecommendationRepository repository,
            MediaMetricsRepository mediaMetricsRepository,
            CreatorRecommendationOutcomeWriter writer) {
        this.repository = repository;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.writer = writer;
    }

    /**
     * Matches, misses, settles and closes this creator's live (OPEN, MATCHED) recommendations as of
     * {@code now}. Outside any transaction; each row is written in its own (see the class javadoc).
     * A row that fails is logged and retried on the next read; it never stops the others.
     *
     * @param taggedOnly true for an account-switcher: untagged legacy readings are left out
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void evaluate(String creatorProfileId, String igAccountId, boolean taggedOnly, Instant now) {
        List<CreatorRecommendation> live =
                repository.findByCreatorProfileIdAndStatusInOrderByCreatedAtAscIdAsc(creatorProfileId, LIVE);
        if (live.isEmpty()) {
            return;
        }
        Set<String> claimed = new HashSet<>(repository.findClaimedMediaIds(creatorProfileId));
        Instant since = scanStart(live, now);
        List<MediaMetric> rows =
                taggedOnly
                        ? mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccountTaggedOnly(
                                creatorProfileId, since, igAccountId)
                        : mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccount(
                                creatorProfileId, since, igAccountId);

        // The challenge's candidate order: posted_at, then media_id.
        List<MediaMetric> posts =
                CreatorIntelligenceService.dedupeToNewestReadingPerPost(rows).stream()
                        .filter(p -> p.getPostedAt() != null)
                        .sorted(Comparator.comparing(MediaMetric::getPostedAt).thenComparing(MediaMetric::getMediaId))
                        .toList();
        Map<String, MediaMetric> byMediaId =
                posts.stream().collect(Collectors.toMap(MediaMetric::getMediaId, Function.identity()));

        for (CreatorRecommendation rec : live) {
            Outcome outcome = decide(rec, posts, byMediaId, claimed, since, now);
            if (outcome == null) {
                continue;
            }
            if (outcome.match() != null) {
                // Claimed even if the write below fails: the post is then most likely taken by a
                // concurrent evaluation, and no later row of this pass should try it either.
                claimed.add(outcome.match().mediaId());
            }
            try {
                writer.apply(rec.getId(), rec.getStatus(), rec.getVersion(), outcome, igAccountId);
            } catch (RuntimeException e) {
                log.warn(
                        "recommendation outcome not written for id={} creatorProfileId={} (other rows unaffected,"
                                + " retried on the next read): {}",
                        rec.getId(),
                        creatorProfileId,
                        e.toString());
            }
        }
    }

    /**
     * Where the readings scan starts: the earliest live row's window start minus the 90-day
     * baseline, clamped to no earlier than {@code now - MAX_SCAN_DAYS} days (Kabir M-2).
     */
    static Instant scanStart(List<CreatorRecommendation> live, Instant now) {
        LocalDate earliest =
                live.stream().map(CreatorRecommendationOutcomeService::windowStart).min(Comparator.naturalOrder()).orElseThrow();
        Instant wanted = earliest.minusDays(CreatorPostingPatternService.LOOKBACK_DAYS).atStartOfDay(IST).toInstant();
        Instant floor = now.minus(Duration.ofDays(MAX_SCAN_DAYS));
        return wanted.isBefore(floor) ? floor : wanted;
    }

    /** Pure: what this evaluation decides for one live row, or null to leave it as it is. */
    private static Outcome decide(
            CreatorRecommendation rec,
            List<MediaMetric> posts,
            Map<String, MediaMetric> byMediaId,
            Set<String> claimed,
            Instant since,
            Instant now) {
        Match match = null;
        String mediaId;
        if (rec.getStatus() == CreatorRecommendationStatus.OPEN) {
            if (windowStart(rec).atStartOfDay(IST).toInstant().isBefore(since)) {
                // The clamped scan did not cover its window: its posts were never looked at.
                return isPastGrace(rec, now) ? new Outcome(null, null, CreatorRecommendationStatus.NO_OUTCOME) : null;
            }
            MediaMetric found = firstUnclaimedInWindow(rec, posts, claimed);
            if (found == null) {
                return isPastGrace(rec, now) ? new Outcome(null, null, CreatorRecommendationStatus.MISSED) : null;
            }
            match =
                    new Match(
                            found.getMediaId(),
                            CreatorPostRules.typeMatches(rec.getPostType(), found.getMediaType()),
                            windowMatches(found.getPostedAt(), rec.getWindowLabel(), rec.getWindowFrom(), rec.getWindowTo()));
            mediaId = found.getMediaId();
        } else {
            mediaId = rec.getMatchedMediaId();
        }

        MediaMetric post = mediaId == null ? null : byMediaId.get(mediaId);
        if (post != null && CreatorIntelligenceService.isSettled(post) && baselineWasScanned(post, since)) {
            return new Outcome(match, settlement(post, posts, now), null);
        }
        if (isPastSettleDeadline(rec, now)) {
            return new Outcome(match, null, CreatorRecommendationStatus.NO_OUTCOME);
        }
        return match == null ? null : new Outcome(match, null, null);
    }

    /** Whether the post's whole 90-day as-of baseline lies inside the (possibly clamped) scan. */
    private static boolean baselineWasScanned(MediaMetric post, Instant since) {
        return !post.getPostedAt().minus(Duration.ofDays(CreatorPostingPatternService.LOOKBACK_DAYS)).isBefore(since);
    }

    /**
     * NO_OUTCOME once {@code match_until} (IST start of day) + the 48 h settling period + {@link
     * #NO_OUTCOME_MARGIN} has passed with the matched post still not settled.
     */
    static boolean isPastSettleDeadline(CreatorRecommendation rec, Instant now) {
        Instant windowEnd = rec.getMatchUntil().atStartOfDay(IST).toInstant();
        return Duration.between(windowEnd, now).compareTo(CreatorPostRules.SETTLING_PERIOD.plus(NO_OUTCOME_MARGIN)) >= 0;
    }

    /** The first IST date a row can be filled on: its day, or the day a script was recommended. */
    static LocalDate windowStart(CreatorRecommendation rec) {
        return rec.getRecommendedFor() != null ? rec.getRecommendedFor() : LocalDate.ofInstant(rec.getCreatedAt(), IST);
    }

    private static MediaMetric firstUnclaimedInWindow(
            CreatorRecommendation rec, List<MediaMetric> posts, Set<String> claimed) {
        LocalDate from = windowStart(rec);
        LocalDate untilExclusive = rec.getMatchUntil();
        for (MediaMetric post : posts) {
            if (claimed.contains(post.getMediaId())) {
                continue;
            }
            LocalDate day = post.getPostedAt().atZone(IST).toLocalDate();
            if (!day.isBefore(from) && day.isBefore(untilExclusive)) {
                return post; // the first unclaimed post -- deterministic order
            }
        }
        return null;
    }

    /** MISSED once {@code match_until} (IST start of day) plus the 12 h checking grace has passed. */
    static boolean isPastGrace(CreatorRecommendation rec, Instant now) {
        Instant windowEnd = rec.getMatchUntil().atStartOfDay(IST).toInstant();
        return Duration.between(windowEnd, now).compareTo(CreatorPostRules.CHECKING_GRACE) >= 0;
    }

    /**
     * Whether the post landed in the recommended window: its {@link CreatorPostRules#windowLabel}
     * equals the label (or ends with it, for the challenge's daypart-only labels such as
     * "evening"), or its IST time falls in {@code [from, to)} (wrapping past midnight when {@code
     * from} is after {@code to}). Null when no window was recommended.
     */
    static Boolean windowMatches(Instant postedAt, String label, LocalTime from, LocalTime to) {
        boolean hasRange = from != null && to != null && !from.equals(to);
        if (label == null && !hasRange) {
            return null;
        }
        if (label != null) {
            String wanted = label.trim().toLowerCase(Locale.ROOT);
            String actual = CreatorPostRules.windowLabel(postedAt);
            if (!wanted.isEmpty() && (actual.equals(wanted) || actual.endsWith(" " + wanted))) {
                return true;
            }
        }
        if (hasRange) {
            LocalTime t = postedAt.atZone(IST).toLocalTime();
            boolean inRange =
                    from.isBefore(to)
                            ? !t.isBefore(from) && t.isBefore(to)
                            : !t.isBefore(from) || t.isBefore(to);
            if (inRange) {
                return true;
            }
        }
        return false;
    }

    private static Settlement settlement(MediaMetric post, List<MediaMetric> posts, Instant now) {
        Instant postedAt = post.getPostedAt();
        Instant baselineFrom = postedAt.minus(Duration.ofDays(CreatorPostingPatternService.LOOKBACK_DAYS));
        List<Double> baselineReach =
                posts.stream()
                        .filter(q -> !q.getMediaId().equals(post.getMediaId()))
                        .filter(q -> !q.getPostedAt().isBefore(baselineFrom) && q.getPostedAt().isBefore(postedAt))
                        .filter(CreatorIntelligenceService::isSettled)
                        .filter(q -> q.getReach() != null && q.getReach() > 0)
                        .map(q -> (double) q.getReach())
                        .toList();
        int n = baselineReach.size();
        Long reach = post.getReach();
        Long baselineMedian = null;
        Integer pct = null;
        if (n >= CreatorPostingPatternService.MIN_POSTS_FOR_PATTERN && reach != null && reach > 0) {
            double median = CreatorIntelligenceService.median(baselineReach);
            baselineMedian = roundedMedian(median);
            pct = reachVsBaselinePct(reach, median);
        }
        return new Settlement(reach, post.getEngagement(), baselineMedian, n, pct, now);
    }

    /**
     * {@code round((reach / median - 1) * 100)}, HALF_UP, clamped to the {@code INT} column's range
     * instead of throwing (Kabir L-4: {@code intValueExact} overflowed for a viral post over a tiny
     * median and failed the evaluation). Never throws.
     */
    static int reachVsBaselinePct(long reach, double median) {
        double ratio = reach / median;
        if (Double.isNaN(ratio)) {
            return 0;
        }
        if (Double.isInfinite(ratio)) {
            return ratio > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
        BigDecimal pct =
                BigDecimal.valueOf(ratio).subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP);
        return pct.max(INT_MIN).min(INT_MAX).intValueExact();
    }

    /** The median rounded HALF_UP, clamped to {@code BIGINT} (a double median can round up to 2^63). */
    static long roundedMedian(double median) {
        if (!Double.isFinite(median)) {
            return median > 0 ? Long.MAX_VALUE : 0L;
        }
        return BigDecimal.valueOf(median)
                .setScale(0, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO)
                .min(LONG_MAX)
                .longValueExact();
    }

    /**
     * {@code followed_recommendations}: one group per source, in {@link
     * CreatorRecommendationSource} order, over the rows created since {@code since} whose outcome
     * is decided (not OPEN). {@code followed} counts the rows a post of the recommended type
     * filled. The median against her usual is given only when at least {@link
     * CreatorPostingPatternService#MIN_BUCKET_POSTS} followed rows settled with a percentage; the
     * evidence lists exactly those rows' posts.
     *
     * <p>For an account-switcher ({@code accountSwitcher}, the profile's rule: more than one
     * distinct Instagram account ever connected) only rows decided on {@code igAccountId} count, so
     * an old account's posts never become "what happened when you followed Meera" on the new one
     * (Kabir L-3).
     */
    @Transactional(readOnly = true)
    public List<FollowedStat> followed(
            String creatorProfileId, Instant since, String igAccountId, boolean accountSwitcher) {
        List<CreatorRecommendation> rows =
                repository.findByCreatorProfileIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
                        creatorProfileId, since);
        if (accountSwitcher) {
            rows = rows.stream().filter(r -> Objects.equals(igAccountId, r.getOutcomeIgAccountId())).toList();
        }
        List<FollowedStat> groups = new ArrayList<>();
        for (CreatorRecommendationSource source : CreatorRecommendationSource.values()) {
            List<CreatorRecommendation> decided =
                    rows.stream()
                            .filter(r -> r.getSource() == source && r.getStatus() != CreatorRecommendationStatus.OPEN)
                            .toList();
            if (decided.isEmpty()) {
                continue;
            }
            List<CreatorRecommendation> followed =
                    decided.stream().filter(r -> Boolean.TRUE.equals(r.getMatchedType())).toList();
            List<CreatorRecommendation> withOutcome =
                    followed.stream()
                            .filter(r -> r.getStatus() == CreatorRecommendationStatus.SETTLED && r.getReachVsBaselinePct() != null)
                            .toList();
            Double medianPct =
                    withOutcome.size() >= CreatorPostingPatternService.MIN_BUCKET_POSTS
                            ? CreatorIntelligenceService.median(
                                    withOutcome.stream().map(r -> (double) r.getReachVsBaselinePct()).toList())
                            : null;
            groups.add(
                    new FollowedStat(
                            source,
                            decided.size(),
                            followed.size(),
                            medianPct,
                            new Evidence(
                                    EvidenceType.CREATOR_POST_DATA,
                                    withOutcome.stream().map(CreatorRecommendation::getMatchedMediaId).toList(),
                                    null)));
        }
        return List.copyOf(groups);
    }
}
