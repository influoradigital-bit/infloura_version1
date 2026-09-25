package com.influora.service.creatorcopilot;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.EvidenceType;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.BaselineStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.BeatsOn;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Evidence;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.GroupStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Metric;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PatternKind;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PostStat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meera intelligence v1 (spec &sect;3) -- the Creator Intelligence Profile, worked out fresh on
 * every read from the creator's OWN stored Instagram post readings. No model is involved and nothing
 * is stored: plain arithmetic over {@code media_metrics}, so the AI cannot write to it and it can
 * always be rebuilt.
 *
 * <p><b>Settled means the newest READING was taken at least 48 h after posting</b> -- reading time
 * minus posted time, not now minus posted time. A post that dropped out of the 25-post poll window
 * (or whose polling stopped) can be ten days old with a newest reading taken six hours after it
 * went up; its numbers are an early snapshot, not its result, and it must not become "your usual".
 * The challenge's own week comparison uses {@code now - posted_at}, which is looser; that rule is
 * left unchanged there.
 *
 * <p><b>Thin data is said, not dressed up.</b> Below {@link
 * CreatorPostingPatternService#MIN_POSTS_FOR_PATTERN} settled posts nothing is claimed, the baseline
 * included, and no group below 3 posts is ever reported. Medians, never means, so one viral Reel is
 * not "your usual". {@code likes} is never read (hidden-likes accounts), {@code video_views} is never
 * read (retired by Meta; views live in {@code impressions}), and {@code caption} is never read.
 *
 * <p>{@code now} is decided by the CALLER, never read here, the same discipline as {@link
 * CreatorPostingPatternService#analyse}.
 */
@Service
public class CreatorIntelligenceService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** {@link CreatorIntelligenceProfile#reason()} when there is no live Instagram connection. */
    public static final String REASON_NOT_CONNECTED = "NOT_CONNECTED";

    /**
     * The claims rest on at most this many settled posts, the most recent by {@code posted_at}. It
     * keeps the tool result bounded for a creator who posts three or more times a day.
     */
    public static final int MAX_POSTS = 150;

    /** A group "works" when its median beats the creator's own usual by at least 20%. */
    static final double WORKS_MIN_LIFT = 1.20;

    /** Best and weak posts: at most this many of each. What works: at most this many per kind. */
    static final int MAX_LISTED = 3;

    /** The groups the model is shown by name. VIDEO and REELS are one group (spec &sect;3.4). */
    private static final Map<ChallengeDayType, String> TYPE_LABELS = new EnumMap<>(ChallengeDayType.class);

    static {
        TYPE_LABELS.put(ChallengeDayType.REEL, "Reels and videos");
        TYPE_LABELS.put(ChallengeDayType.CAROUSEL, "Carousels");
        TYPE_LABELS.put(ChallengeDayType.POST, "Photo posts");
    }

    private final MediaMetricsRepository mediaMetricsRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final ConnectedInstagramAccount connectedAccount;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;

    public CreatorIntelligenceService(
            MediaMetricsRepository mediaMetricsRepository,
            CreatorProfileRepository creatorProfileRepository,
            ConnectedInstagramAccount connectedAccount,
            MetaOAuthTokenRepository metaOAuthTokenRepository) {
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.connectedAccount = connectedAccount;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
    }

    /** One settled post with a real reach, reduced to the numbers the claims need. */
    private record Post(
            String mediaId,
            ChallengeDayType type,
            Instant postedAt,
            Instant readAt,
            String window,
            String permalink,
            long reach,
            Long views,
            Long interactions) {

        /** Interactions per reach, or null when interactions are unknown -- never 0. */
        Double engagementRate() {
            return interactions == null ? null : interactions / (double) reach;
        }
    }

    /**
     * The creator-facing read: the profile of {@code creatorUserId} (from the verified JWT only) as
     * of {@code now}.
     */
    @Transactional(readOnly = true)
    public CreatorIntelligenceProfile profile(String creatorUserId, Instant now) {
        CreatorProfile profile = resolveProfile(creatorUserId);

        // Only with a LIVE connection (LOW-2 data-policy ruling, as Meera's audience and account
        // lines already follow). currentAccountId filters revoked tokens only, despite its javadoc,
        // so the expiry check is made here explicitly.
        Optional<String> igAccountId = connectedAccount.currentAccountId(profile.getId());
        if (igAccountId.isEmpty() || !hasLiveConnection(profile.getId(), now)) {
            return notConnected();
        }

        LocalDate today = LocalDate.ofInstant(now, IST);
        Instant windowStart =
                today.minusDays(CreatorPostingPatternService.LOOKBACK_DAYS).atStartOfDay(IST).toInstant();
        Instant windowEndExclusive = today.plusDays(1).atStartOfDay(IST).toInstant();

        // One row per post, on the account connected today. Untagged legacy rows (written before
        // V20260924120000 stamped the account) are kept only when she has ever connected ONE
        // account: for an account-switcher they could be the old account's posts, shown to Meera
        // as "your best posts" (Kabir M-1). Leaving them out may show thin data for a while; that
        // is honest, mixing two accounts is not.
        List<MediaMetric> rows =
                metaOAuthTokenRepository.countDistinctCreatorIgAccounts(profile.getId()) > 1
                        ? mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccountTaggedOnly(
                                profile.getId(), windowStart, igAccountId.get())
                        : mediaMetricsRepository.findNewestSnapshotPerPostSinceForAccount(
                                profile.getId(), windowStart, igAccountId.get());

        int unsettled = 0;
        List<Post> valid = new ArrayList<>();
        for (MediaMetric row : dedupeToNewestReadingPerPost(rows)) {
            Instant postedAt = row.getPostedAt();
            if (postedAt == null || postedAt.isBefore(windowStart) || !postedAt.isBefore(windowEndExclusive)) {
                continue;
            }
            if (!isSettled(row)) {
                unsettled++;
                continue;
            }
            Long reach = row.getReach();
            if (reach == null || reach <= 0) {
                continue; // no denominator
            }
            valid.add(
                    new Post(
                            row.getMediaId(),
                            CreatorPostRules.canonicalType(row.getMediaType()),
                            postedAt,
                            row.getTime(),
                            CreatorPostRules.windowLabel(postedAt),
                            row.getPermalink(),
                            reach,
                            row.getImpressions(),
                            row.getEngagement()));
        }

        valid.sort(Comparator.comparing(Post::postedAt).reversed().thenComparing(Post::mediaId));
        int settled = valid.size();
        List<Post> used = settled > MAX_POSTS ? List.copyOf(valid.subList(0, MAX_POSTS)) : List.copyOf(valid);

        if (used.size() < CreatorPostingPatternService.MIN_POSTS_FOR_PATTERN) {
            return new CreatorIntelligenceProfile(
                    true,
                    null,
                    false,
                    settled,
                    unsettled,
                    used.size(),
                    CreatorPostingPatternService.MIN_POSTS_FOR_PATTERN,
                    CreatorPostingPatternService.LOOKBACK_DAYS,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        List<BaselineStat> baseline = buildBaseline(used);
        double baselineReach = medianOf(baseline, Metric.REACH);
        Double baselineEr = baseline.stream()
                .filter(b -> b.metric() == Metric.ENGAGEMENT_RATE)
                .map(BaselineStat::median)
                .findFirst()
                .orElse(null);
        int baselineReachN = used.size();

        List<PostStat> best = new ArrayList<>();
        List<PostStat> weak = new ArrayList<>();
        rankPosts(used, baselineReach, baselineReachN, best, weak);

        List<GroupStat> whatWorks = new ArrayList<>();
        whatWorks.addAll(
                groups(
                        used,
                        PatternKind.POST_TYPE,
                        p -> p.type() == null ? null : TYPE_LABELS.get(p.type()),
                        CreatorPostingPatternService.MIN_BEST_TYPE_POSTS,
                        baselineReach,
                        baselineEr));
        whatWorks.addAll(
                groups(
                        used,
                        PatternKind.POSTING_WINDOW,
                        Post::window,
                        CreatorPostingPatternService.MIN_BUCKET_POSTS,
                        baselineReach,
                        baselineEr));
        whatWorks.sort(
                Comparator.comparingDouble(CreatorIntelligenceService::maxLift)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(GroupStat::posts).reversed())
                        .thenComparing(GroupStat::label));
        List<GroupStat> capped = capPerKind(whatWorks);

        Instant asOf = used.stream().map(Post::readAt).max(Comparator.naturalOrder()).orElse(null);

        return new CreatorIntelligenceProfile(
                true,
                null,
                true,
                settled,
                unsettled,
                used.size(),
                CreatorPostingPatternService.MIN_POSTS_FOR_PATTERN,
                CreatorPostingPatternService.LOOKBACK_DAYS,
                asOf,
                baseline,
                List.copyOf(best),
                List.copyOf(weak),
                capped);
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
     * The same definition {@code MeeraContextService#hasLiveMetaConnection} uses for this
     * creator-owned key-space: a non-revoked token whose {@code expiresAt} is absent or still in
     * the future -- here measured against the caller's {@code now}.
     */
    private boolean hasLiveConnection(String creatorProfileId, Instant now) {
        return metaOAuthTokenRepository
                .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(now))
                .isPresent();
    }

    private static CreatorIntelligenceProfile notConnected() {
        return new CreatorIntelligenceProfile(
                false,
                REASON_NOT_CONNECTED,
                false,
                0,
                0,
                0,
                CreatorPostingPatternService.MIN_POSTS_FOR_PATTERN,
                CreatorPostingPatternService.LOOKBACK_DAYS,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * Settled when the post's newest reading was taken at least {@link
     * CreatorPostRules#SETTLING_PERIOD} after it was posted. Reading time, not now: see the class
     * javadoc.
     */
    private static boolean isSettled(MediaMetric row) {
        return row.getTime() != null
                && Duration.between(row.getPostedAt(), row.getTime()).compareTo(CreatorPostRules.SETTLING_PERIOD) >= 0;
    }

    /**
     * Collapses every {@code media_id} to its newest reading. The query already returns one row per
     * post; this is the second line of defence, and it also settles the case the correlated
     * {@code max(time)} subquery cannot: two readings with an identical {@code time}, which it
     * returns as two rows. On a tie the row with the greater id (the later ULID) wins, so the
     * choice does not depend on the order rows arrive in.
     */
    private static List<MediaMetric> dedupeToNewestReadingPerPost(List<MediaMetric> rows) {
        Map<String, MediaMetric> newest = new LinkedHashMap<>();
        for (MediaMetric row : rows) {
            if (row.getMediaId() == null) {
                continue;
            }
            MediaMetric existing = newest.get(row.getMediaId());
            if (existing == null || isNewer(row, existing)) {
                newest.put(row.getMediaId(), row);
            }
        }
        return List.copyOf(newest.values());
    }

    private static boolean isNewer(MediaMetric candidate, MediaMetric existing) {
        if (existing.getTime() == null) {
            return candidate.getTime() != null || compareIds(candidate, existing) > 0;
        }
        if (candidate.getTime() == null) {
            return false;
        }
        int byTime = candidate.getTime().compareTo(existing.getTime());
        return byTime > 0 || (byTime == 0 && compareIds(candidate, existing) > 0);
    }

    private static int compareIds(MediaMetric a, MediaMetric b) {
        String left = a.getId() == null ? "" : a.getId();
        String right = b.getId() == null ? "" : b.getId();
        return left.compareTo(right);
    }

    /**
     * One {@link BaselineStat} per metric, each over its own sample. REACH covers every post used.
     * VIEWS, INTERACTIONS and ENGAGEMENT_RATE cover only the posts that carry that number and are
     * omitted below {@link CreatorPostingPatternService#MIN_POSTS_FOR_PATTERN} -- a post with
     * unknown interactions stays in the reach baseline and is never counted as 0.
     */
    private static List<BaselineStat> buildBaseline(List<Post> used) {
        List<BaselineStat> baseline = new ArrayList<>();
        baseline.add(stat(Metric.REACH, used, p -> (double) p.reach()));

        List<Post> withViews = used.stream().filter(p -> p.views() != null).toList();
        if (withViews.size() >= CreatorPostingPatternService.MIN_POSTS_FOR_PATTERN) {
            baseline.add(stat(Metric.VIEWS, withViews, p -> (double) p.views()));
        }
        List<Post> withInteractions = used.stream().filter(p -> p.interactions() != null).toList();
        if (withInteractions.size() >= CreatorPostingPatternService.MIN_POSTS_FOR_PATTERN) {
            baseline.add(stat(Metric.INTERACTIONS, withInteractions, p -> (double) p.interactions()));
            baseline.add(stat(Metric.ENGAGEMENT_RATE, withInteractions, Post::engagementRate));
        }
        return List.copyOf(baseline);
    }

    private static BaselineStat stat(Metric metric, List<Post> sample, Function<Post, Double> value) {
        return new BaselineStat(
                metric,
                median(sample.stream().map(value).toList()),
                new Evidence(EvidenceType.CREATOR_POST_DATA, idsOf(sample), null));
    }

    private static double medianOf(List<BaselineStat> baseline, Metric metric) {
        return baseline.stream()
                .filter(b -> b.metric() == metric)
                .findFirst()
                .orElseThrow()
                .median();
    }

    /**
     * Best: reach at or above the usual, highest first. Weak: below it, lowest first. Ties go to the
     * newer post, then the lower media id, so the lists never depend on row order. A post can only
     * be in one list because the two conditions do not overlap.
     */
    private static void rankPosts(
            List<Post> used, double baselineReach, int baselineN, List<PostStat> best, List<PostStat> weak) {
        record Ranked(Post post, double ratio) {}
        Comparator<Ranked> newerThenId =
                Comparator.comparing((Ranked r) -> r.post().postedAt())
                        .reversed()
                        .thenComparing(r -> r.post().mediaId());

        List<Ranked> ranked = used.stream().map(p -> new Ranked(p, p.reach() / baselineReach)).toList();
        ranked.stream()
                .filter(r -> r.ratio() > 1.0) // strictly above their usual; a post at it is neither
                .sorted(Comparator.comparingDouble(Ranked::ratio).reversed().thenComparing(newerThenId))
                .limit(MAX_LISTED)
                .forEach(r -> best.add(toPostStat(r.post(), r.ratio(), baselineN)));
        ranked.stream()
                .filter(r -> r.ratio() < 1.0)
                .sorted(Comparator.comparingDouble(Ranked::ratio).thenComparing(newerThenId))
                .limit(MAX_LISTED)
                .forEach(r -> weak.add(toPostStat(r.post(), r.ratio(), baselineN)));
    }

    private static PostStat toPostStat(Post post, double ratio, int baselineN) {
        return new PostStat(
                post.mediaId(),
                post.type(),
                post.postedAt(),
                post.window(),
                post.permalink(),
                post.reach(),
                ratio,
                post.engagementRate(),
                new Evidence(EvidenceType.CREATOR_POST_DATA, List.of(post.mediaId()), baselineN));
    }

    /**
     * The groups of one kind that beat the usual. Evaluated only when at least two groups of this
     * kind have {@code minPosts} or more: with one populated group there is nothing to compare it
     * with ("Reels beat your usual" from an all-Reels creator is a tautology). A group below
     * {@code minPosts} is never reported. A post with no known type is left out of type groups but
     * is still in the baseline.
     */
    private static List<GroupStat> groups(
            List<Post> used,
            PatternKind kind,
            Function<Post, String> labelOf,
            int minPosts,
            double baselineReach,
            Double baselineEr) {
        Map<String, List<Post>> byLabel = new LinkedHashMap<>();
        for (Post post : used) {
            String label = labelOf.apply(post);
            if (label != null) {
                byLabel.computeIfAbsent(label, key -> new ArrayList<>()).add(post);
            }
        }
        List<Map.Entry<String, List<Post>>> eligible =
                byLabel.entrySet().stream().filter(e -> e.getValue().size() >= minPosts).toList();
        if (eligible.size() < 2) {
            return List.of();
        }

        List<GroupStat> reported = new ArrayList<>();
        for (Map.Entry<String, List<Post>> entry : eligible) {
            List<Post> posts = entry.getValue();
            double medianReach = median(posts.stream().map(p -> (double) p.reach()).toList());
            double reachLift = medianReach / baselineReach;

            List<Post> withInteractions = posts.stream().filter(p -> p.interactions() != null).toList();
            Double medianEr =
                    withInteractions.size() >= minPosts
                            ? median(withInteractions.stream().map(Post::engagementRate).toList())
                            : null;
            Double erLift = medianEr != null && baselineEr != null && baselineEr > 0 ? medianEr / baselineEr : null;

            List<BeatsOn> beatsOn = new ArrayList<>();
            if (reachLift >= WORKS_MIN_LIFT) {
                beatsOn.add(BeatsOn.REACH);
            }
            if (erLift != null && erLift >= WORKS_MIN_LIFT) {
                beatsOn.add(BeatsOn.ENGAGEMENT);
            }
            if (beatsOn.isEmpty()) {
                continue;
            }
            reported.add(
                    new GroupStat(
                            kind,
                            entry.getKey(),
                            posts.size(),
                            medianReach,
                            reachLift,
                            medianEr,
                            erLift,
                            List.copyOf(beatsOn),
                            new Evidence(EvidenceType.CREATOR_POST_DATA, idsOf(posts), null)));
        }
        return reported;
    }

    private static double maxLift(GroupStat group) {
        return group.engagementLift() == null
                ? group.reachLift()
                : Math.max(group.reachLift(), group.engagementLift());
    }

    private static List<GroupStat> capPerKind(List<GroupStat> sorted) {
        Map<PatternKind, Integer> taken = new EnumMap<>(PatternKind.class);
        List<GroupStat> kept = new ArrayList<>();
        for (GroupStat group : sorted) {
            int count = taken.getOrDefault(group.kind(), 0);
            if (count < MAX_LISTED) {
                kept.add(group);
                taken.put(group.kind(), count + 1);
            }
        }
        return List.copyOf(kept);
    }

    private static List<String> idsOf(List<Post> posts) {
        return posts.stream().map(Post::mediaId).toList();
    }

    /** The middle value; for an even count, the mean of the two middle values. */
    static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n == 0) {
            throw new IllegalArgumentException("median of no values");
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
