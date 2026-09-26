package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorChallenge;
import com.influora.domain.entity.CreatorChallengeDay;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorChallengeStatus;
import com.influora.repository.CreatorChallengeDayRepository;
import com.influora.repository.CreatorChallengeRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.creatorcopilot.CreatorPostRules;
import com.influora.service.creatorcopilot.CreatorRecommendationService;
import com.influora.service.creatorcopilot.CreatorPostingPatternService;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PatternWindow;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PostingPattern;
import com.influora.web.dto.challenge.ChallengeDtos.ActiveChallenge;
import com.influora.web.dto.challenge.ChallengeDtos.ChallengeDay;
import com.influora.web.dto.challenge.ChallengeDtos.ChallengeState;
import com.influora.web.dto.challenge.ChallengeDtos.Comparison;
import com.influora.web.dto.challenge.ChallengeDtos.LastCompleted;
import com.influora.web.dto.challenge.ChallengeDtos.WeekStat;
import com.influora.web.dto.challenge.ChallengeDtos.Window;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creator 7-day challenge (CHALLENGE-SPEC.md, Swapnil 2026-09-23, v1). Deterministic arithmetic
 * only -- exactly like {@link CreatorPostingPatternService}, no model is ever involved in planning
 * a day, ticking one off, or computing the week-over-week comparison. Every number here comes from
 * the creator's OWN {@code media_metrics} rows (via {@link
 * MediaMetricsRepository#findNewestSnapshotPerPostSince}) or from {@link
 * CreatorPostingPatternService#analyse}, which already does that reduction.
 *
 * <p><b>Honesty rules (non-negotiable, CHALLENGE-SPEC.md "Where, and the rules"):</b> a creator is
 * never told they are "growing" from this class -- {@link #computeComparison} returns {@code null}
 * change fields (never a percentage computed from a zero denominator, never a change at all below
 * {@link #MIN_SETTLED_TO_COMPARE} settled posts per week) rather than dressing up a thin or
 * degenerate sample. A day's window is only ever labelled {@code your_posts} when {@link
 * CreatorPostingPatternService} itself reports {@code enoughData} AND a real bucket for that
 * weekday/weekend class; otherwise it is {@code suggestion} -- the frontend's contract to never say
 * "your best time" for a suggested window depends on this field being honest.
 */
@Service
public class CreatorChallengeService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** 6 posting days + 1 REST day, day_index 0-6 (CHALLENGE-SPEC.md Backend &sect;2). */
    static final int CHALLENGE_DAYS = 7;

    private static final String SUGGESTION_LABEL = "evening";
    private static final LocalTime SUGGESTION_FROM = LocalTime.of(17, 0);
    private static final LocalTime SUGGESTION_TO = LocalTime.of(22, 0);
    private static final String WINDOW_SOURCE_YOUR_POSTS = "your_posts";
    private static final String WINDOW_SOURCE_SUGGESTION = "suggestion";

    /** Default mix when {@code bestPostType} is unknown (CHALLENGE-SPEC.md Backend &sect;2). */
    private static final List<ChallengeDayType> DEFAULT_TYPE_SEQUENCE =
            List.of(
                    ChallengeDayType.REEL,
                    ChallengeDayType.CAROUSEL,
                    ChallengeDayType.POST,
                    ChallengeDayType.REEL,
                    ChallengeDayType.CAROUSEL,
                    ChallengeDayType.REEL);

    // SETTLING_PERIOD (48 h) moved to CreatorPostRules (Meera intelligence v1, spec 3.2), shared
    // with CreatorIntelligenceService.

    /** Data refreshes every 6h (MetricsPollingJob) -- a day less than this old that has not yet
     * shown a post is "still checking", not yet "missed" (CHALLENGE-SPEC.md Backend &sect;3).
     * Defined once in {@link CreatorPostRules#CHECKING_GRACE} (slice 2 reuses it). */
    static final Duration CHECKING_GRACE = CreatorPostRules.CHECKING_GRACE;

    /** Never a change computed off a single settled post either week (CHALLENGE-SPEC.md &sect;6). */
    static final int MIN_SETTLED_TO_COMPARE = 2;

    private static final String NOT_ENOUGH_TO_COMPARE_NOTE =
            "Not enough settled posts in both weeks to compare yet.";

    private final CreatorChallengeRepository challengeRepository;
    private final CreatorChallengeDayRepository dayRepository;
    private final MediaMetricsRepository mediaMetricsRepository;
    private final CreatorPostingPatternService postingPatternService;
    private final MetaConnectionService metaConnectionService;
    private final CreatorRecommendationService recommendationService;

    public CreatorChallengeService(
            CreatorChallengeRepository challengeRepository,
            CreatorChallengeDayRepository dayRepository,
            MediaMetricsRepository mediaMetricsRepository,
            CreatorPostingPatternService postingPatternService,
            MetaConnectionService metaConnectionService,
            CreatorRecommendationService recommendationService) {
        this.challengeRepository = challengeRepository;
        this.dayRepository = dayRepository;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.postingPatternService = postingPatternService;
        this.metaConnectionService = metaConnectionService;
        this.recommendationService = recommendationService;
    }

    // ============================== Reads ==============================

    /**
     * {@code GET /api/v1/creator/challenge}. Lazily ticks off the active challenge's days (if any),
     * completes it past day 6, then assembles the always-present {@code lastCompleted}/{@code
     * comparison} halves. {@code now} is separate from {@code today} (both caller-supplied, never
     * {@code Instant.now()}/{@code LocalDate.now()} internally, same discipline as {@link
     * CreatorPostingPatternService#analyse}) because {@code CHECKING}/settling need real wall-clock
     * time, not just the IST calendar date.
     */
    @Transactional
    public ChallengeState getState(CreatorProfile profile, LocalDate today, Instant now) {
        boolean instagramConnected = metaConnectionService.getStatus(profile).connected();
        ActiveChallenge activeDto = loadAndProcessActive(profile, today, now);
        LastCompleted lastCompleted = loadLastCompleted(profile.getUserId());
        Comparison comparison = computeComparison(profile.getId(), today, now);
        return new ChallengeState(instagramConnected, activeDto, lastCompleted, comparison);
    }

    /** Loads the ACTIVE challenge (if any), runs the lazy tick-off, and completes it past day 6. */
    private ActiveChallenge loadAndProcessActive(CreatorProfile profile, LocalDate today, Instant now) {
        CreatorChallenge challenge = challengeRepository.findByActiveKey(profile.getUserId()).orElse(null);
        if (challenge == null) {
            return null;
        }
        List<CreatorChallengeDay> days =
                dayRepository.findByIdChallengeIdOrderByIdDayIndexAsc(challenge.getId());
        tickOff(challenge, days, today);

        // CHALLENGE-SPEC.md Backend &sect;4 -- "past day 6" = today strictly after the 7th day's date.
        if (today.isAfter(challenge.getStartedOn().plusDays(CHALLENGE_DAYS - 1))) {
            challenge.markCompleted();
            challengeRepository.save(challenge);
            return null;
        }
        return toActiveChallengeDto(challenge, days, today, now);
    }

    private LastCompleted loadLastCompleted(String creatorUserId) {
        return challengeRepository
                .findFirstByCreatorUserIdAndStatusInOrderByStartedOnDesc(
                        creatorUserId,
                        List.of(CreatorChallengeStatus.COMPLETED, CreatorChallengeStatus.ENDED))
                .map(this::toLastCompletedDto)
                .orElse(null);
    }

    private LastCompleted toLastCompletedDto(CreatorChallenge challenge) {
        List<CreatorChallengeDay> days =
                dayRepository.findByIdChallengeIdOrderByIdDayIndexAsc(challenge.getId());
        int daysPlanned = (int) days.stream().filter(d -> d.getPlannedType() != ChallengeDayType.REST).count();
        int daysDone =
                (int)
                        days.stream()
                                .filter(d -> d.getPlannedType() != ChallengeDayType.REST && d.isDone())
                                .count();
        return new LastCompleted(challenge.getId(), challenge.getStartedOn(), daysDone, daysPlanned);
    }

    // ============================== Start / End ==============================

    /**
     * {@code POST /api/v1/creator/challenge}. Order matches the contract text exactly: {@code
     * CHALLENGE_ALREADY_ACTIVE} is checked before {@code INSTAGRAM_NOT_CONNECTED}.
     */
    @Transactional
    public ChallengeState start(CreatorProfile profile, LocalDate today, Instant now) {
        if (challengeRepository.findByActiveKey(profile.getUserId()).isPresent()) {
            throw new ApiException(
                    "CHALLENGE_ALREADY_ACTIVE", "A challenge is already active", HttpStatus.CONFLICT);
        }
        if (!metaConnectionService.getStatus(profile).connected()) {
            throw new ApiException(
                    "INSTAGRAM_NOT_CONNECTED", "Connect Instagram to start the challenge", HttpStatus.CONFLICT);
        }

        PostingPattern pattern = postingPatternService.analyse(profile.getUserId(), today);
        CreatorChallenge challenge =
                CreatorChallenge.start(Ulids.newUlid(), profile.getUserId(), profile.getId(), today);
        // Round 2 fix: the findByActiveKey pre-check above is TOCTOU -- two quick "Start" taps can
        // both pass it before either commits. The active_key UNIQUE index is what actually stops
        // the second row, but with plain save() the violation only surfaces at the transaction's
        // final flush/commit, well after this method (and its try/catch, if it had one) has
        // already returned -- the creator would see a raw 500, not a 409. saveAndFlush forces the
        // INSERT (and the constraint check) to happen HERE, synchronously, where it can still be
        // caught and turned into the same CHALLENGE_ALREADY_ACTIVE the pre-check produces for the
        // non-race case.
        try {
            challengeRepository.saveAndFlush(challenge);
        } catch (DataIntegrityViolationException raceLost) {
            throw new ApiException(
                    "CHALLENGE_ALREADY_ACTIVE", "A challenge is already active", HttpStatus.CONFLICT);
        }
        List<CreatorChallengeDay> plan = buildPlan(challenge.getId(), pattern, today);
        dayRepository.saveAll(plan);
        // Meera intelligence v1, slice 2 (spec 8.3): one creator_recommendations row per non-REST
        // day, in THIS transaction -- deterministic server data that stands or falls with the
        // challenge it describes. source_ref = challengeId:dayIndex; a replay is a no-op.
        recommendationService.recordChallengePlan(challenge, plan, now);

        return getState(profile, today, now);
    }

    /**
     * {@code POST /api/v1/creator/challenge/{id}/end}. 404 if the challenge is not this creator's.
     * Returns the updated {@link ChallengeState} (the frontend contract, {@code src/lib/api.ts}'s
     * {@code creatorChallenge.end}, expects the same shape as GET/POST -- {@code active: null}
     * once this call has ended it), exactly the way {@link #start} also reuses {@link #getState}
     * to build its response.
     */
    @Transactional
    public ChallengeState end(CreatorProfile profile, String challengeId, LocalDate today, Instant now) {
        CreatorChallenge challenge =
                challengeRepository
                        .findById(challengeId)
                        .filter(c -> c.getCreatorUserId().equals(profile.getUserId()))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CHALLENGE_NOT_FOUND", "Challenge not found", HttpStatus.NOT_FOUND));
        if (challenge.getStatus() == CreatorChallengeStatus.ACTIVE) {
            challenge.markEnded();
            challengeRepository.save(challenge);
        }
        // Already COMPLETED/ENDED: idempotent no-op rather than an error -- the creator's intent
        // ("stop this challenge") is already satisfied.
        return getState(profile, today, now);
    }

    // ============================== Tick-off ==============================

    /**
     * CHALLENGE-SPEC.md Backend &sect;3 -- for every day with date &lt;= today that is not DONE/REST,
     * finds a real post landing on that IST calendar date and not already claimed by another day.
     */
    private void tickOff(CreatorChallenge challenge, List<CreatorChallengeDay> days, LocalDate today) {
        boolean anyCandidateDay =
                days.stream()
                        .anyMatch(
                                d ->
                                        !d.getDate().isAfter(today)
                                                && !d.isDone()
                                                && d.getPlannedType() != ChallengeDayType.REST);
        if (!anyCandidateDay) {
            return;
        }

        Instant since = challenge.getStartedOn().atStartOfDay(IST).toInstant();
        List<MediaMetric> posts =
                mediaMetricsRepository.findNewestSnapshotPerPostSince(challenge.getCreatorProfileId(), since);

        Map<LocalDate, List<MediaMetric>> byIstDate =
                posts.stream()
                        .filter(p -> p.getPostedAt() != null)
                        .sorted(
                                java.util.Comparator.comparing(MediaMetric::getPostedAt)
                                        .thenComparing(MediaMetric::getMediaId))
                        .collect(Collectors.groupingBy(p -> p.getPostedAt().atZone(IST).toLocalDate()));

        Set<String> usedMediaIds =
                days.stream()
                        .map(CreatorChallengeDay::getDoneMediaId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(java.util.HashSet::new));

        List<CreatorChallengeDay> changed = new ArrayList<>();
        for (CreatorChallengeDay day : days) {
            if (day.isDone() || day.getPlannedType() == ChallengeDayType.REST || day.getDate().isAfter(today)) {
                continue;
            }
            for (MediaMetric candidate : byIstDate.getOrDefault(day.getDate(), List.of())) {
                if (usedMediaIds.contains(candidate.getMediaId())) {
                    continue;
                }
                boolean matched = CreatorPostRules.typeMatches(day.getPlannedType(), candidate.getMediaType());
                day.markDone(candidate.getMediaId(), candidate.getMediaType(), matched, candidate.getPostedAt());
                usedMediaIds.add(candidate.getMediaId());
                changed.add(day);
                break; // one post can't fill two days -- first unclaimed post on the date, deterministic order
            }
        }
        if (!changed.isEmpty()) {
            dayRepository.saveAll(changed);
        }
    }

    // ============================== Status derivation ==============================

    /** CHALLENGE-SPEC.md Backend &sect;3: DONE, REST, TODAY, UPCOMING, CHECKING, MISSED. Never
     * persisted -- derived fresh from {@code doneAt}/{@code plannedType} and the caller's {@code
     * today}/{@code now} every time. */
    static String deriveStatus(CreatorChallengeDay day, LocalDate today, Instant now) {
        if (day.getPlannedType() == ChallengeDayType.REST) {
            return "REST";
        }
        if (day.isDone()) {
            return "DONE";
        }
        if (day.getDate().isEqual(today)) {
            return "TODAY";
        }
        if (day.getDate().isAfter(today)) {
            return "UPCOMING";
        }
        Instant dayEnd = day.getDate().plusDays(1).atStartOfDay(IST).toInstant();
        return Duration.between(dayEnd, now).compareTo(CHECKING_GRACE) < 0 ? "CHECKING" : "MISSED";
    }

    // ============================== Streak ==============================

    /**
     * CHALLENGE-SPEC.md Backend &sect;5: consecutive DONE days counting back from the latest finished
     * day (today counts only if already DONE); REST days neither break nor add.
     */
    static int computeStreak(List<CreatorChallengeDay> days, LocalDate today) {
        int latestStartedIdx = -1;
        for (int i = days.size() - 1; i >= 0; i--) {
            if (!days.get(i).getDate().isAfter(today)) {
                latestStartedIdx = i;
                break;
            }
        }
        if (latestStartedIdx == -1) {
            return 0;
        }

        CreatorChallengeDay latest = days.get(latestStartedIdx);
        int cursor = latestStartedIdx;
        boolean todayNotYetDone =
                latest.getDate().isEqual(today)
                        && !latest.isDone()
                        && latest.getPlannedType() != ChallengeDayType.REST;
        if (todayNotYetDone) {
            cursor = latestStartedIdx - 1;
        }

        int streak = 0;
        for (int i = cursor; i >= 0; i--) {
            CreatorChallengeDay day = days.get(i);
            if (day.getPlannedType() == ChallengeDayType.REST) {
                continue; // neither breaks nor adds
            }
            if (day.isDone()) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    // ============================== DTO assembly ==============================

    private ActiveChallenge toActiveChallengeDto(
            CreatorChallenge challenge, List<CreatorChallengeDay> days, LocalDate today, Instant now) {
        int dayNumber =
                (int)
                        Math.min(
                                CHALLENGE_DAYS, ChronoUnit.DAYS.between(challenge.getStartedOn(), today) + 1);
        int streak = computeStreak(days, today);
        List<ChallengeDay> dayDtos = days.stream().map(d -> toDayDto(d, today, now)).toList();
        return new ActiveChallenge(challenge.getId(), challenge.getStartedOn(), dayNumber, streak, dayDtos);
    }

    private ChallengeDay toDayDto(CreatorChallengeDay day, LocalDate today, Instant now) {
        String status = deriveStatus(day, today, now);
        Window window =
                day.getPlannedType() == ChallengeDayType.REST
                        ? null
                        : new Window(
                                day.getWindowLabel(), formatTime(day.getWindowFrom()), formatTime(day.getWindowTo()));
        String permalink =
                day.getDoneMediaId() == null
                        ? null
                        : mediaMetricsRepository
                                .findFirstByMediaIdOrderByTimeDesc(day.getDoneMediaId())
                                .map(MediaMetric::getPermalink)
                                .orElse(null);
        return new ChallengeDay(
                day.getDayIndex(),
                day.getDate(),
                day.getPlannedType().name(),
                window,
                day.getWindowSource(),
                status,
                day.getMatchedType(),
                day.getPostedType(),
                permalink);
    }

    private static String formatTime(LocalTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // ============================== Plan generation ==============================

    /** CHALLENGE-SPEC.md Backend &sect;2 -- deterministic, NO AI call. */
    private List<CreatorChallengeDay> buildPlan(String challengeId, PostingPattern pattern, LocalDate startedOn) {
        int restIndex = determineRestIndex(pattern, startedOn);
        List<ChallengeDayType> postingTypes = determineTypeSequence(pattern);

        List<CreatorChallengeDay> days = new ArrayList<>(CHALLENGE_DAYS);
        int typeCursor = 0;
        for (int i = 0; i < CHALLENGE_DAYS; i++) {
            LocalDate date = startedOn.plusDays(i);
            if (i == restIndex) {
                days.add(CreatorChallengeDay.plan(challengeId, i, date, ChallengeDayType.REST, null, null, null, null));
                continue;
            }
            ChallengeDayType type = postingTypes.get(typeCursor++);
            WindowPlan window = determineWindow(pattern, date);
            days.add(
                    CreatorChallengeDay.plan(
                            challengeId, i, date, type, window.label(), window.from(), window.to(), window.source()));
        }
        return days;
    }

    /**
     * The weekday in the 7 days with the lowest engagement bucket, if the pattern has one, else
     * Sunday. {@link CreatorPostingPatternService}'s buckets only ever distinguish "weekday" from
     * "weekend" (never a specific day name) -- there is no per-individual-weekday data anywhere in
     * the verified facts this spec hands us. Because the challenge is exactly 7 consecutive days,
     * it always contains exactly one occurrence of every weekday name, so: if the pattern's
     * "weekday" bucket average is strictly worse than its "weekend" bucket average, the rest day is
     * the EARLIEST Mon-Fri date in the window (a stable, deterministic pick given no finer-grained
     * data distinguishes one weekday from another); otherwise -- including "not enough data", "only
     * one class of bucket present", or "weekend is worse or equal" -- the rest day is Sunday.
     */
    private int determineRestIndex(PostingPattern pattern, LocalDate startedOn) {
        if (pattern.enoughData()) {
            Double weekdayAvg = averageEngagementRate(pattern, "weekday ");
            Double weekendAvg = averageEngagementRate(pattern, "weekend ");
            if (weekdayAvg != null && weekendAvg != null && weekdayAvg < weekendAvg) {
                for (int i = 0; i < CHALLENGE_DAYS; i++) {
                    DayOfWeek dow = startedOn.plusDays(i).getDayOfWeek();
                    if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                        return i;
                    }
                }
            }
        }
        for (int i = 0; i < CHALLENGE_DAYS; i++) {
            if (startedOn.plusDays(i).getDayOfWeek() == DayOfWeek.SUNDAY) {
                return i;
            }
        }
        // Unreachable: any 7 consecutive calendar days contain exactly one Sunday.
        return CHALLENGE_DAYS - 1;
    }

    private static Double averageEngagementRate(PostingPattern pattern, String labelPrefix) {
        List<PatternWindow> matches =
                pattern.windows().stream().filter(w -> w.label().startsWith(labelPrefix)).toList();
        if (matches.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (PatternWindow w : matches) {
            sum += parseRate(w.engagementRate());
        }
        return sum / matches.size();
    }

    /**
     * CHALLENGE-SPEC.md Backend &sect;2 -- if {@code bestPostType} is known, it takes 3 of the 6
     * posting days spread out (never three in a row: slots 0/2/4 of the 6), and the other two types
     * share the remaining 3 slots (1/3/5), in the same [REEL, CAROUSEL, POST] relative order the
     * default mix uses. Otherwise the fixed default mix.
     */
    private List<ChallengeDayType> determineTypeSequence(PostingPattern pattern) {
        ChallengeDayType best = CreatorPostRules.canonicalType(pattern.bestPostType());
        if (best == null) {
            return DEFAULT_TYPE_SEQUENCE;
        }
        List<ChallengeDayType> others = new ArrayList<>(List.of(ChallengeDayType.REEL, ChallengeDayType.CAROUSEL, ChallengeDayType.POST));
        others.remove(best);

        ChallengeDayType[] slots = new ChallengeDayType[6];
        slots[0] = best;
        slots[2] = best;
        slots[4] = best;
        slots[1] = others.get(0);
        slots[3] = others.get(1);
        slots[5] = others.get(0);
        return List.of(slots);
    }

    private record WindowPlan(String label, LocalTime from, LocalTime to, String source) {}

    /**
     * The best {@link PatternWindow} for the day's weekday/weekend class when the pattern has
     * enough data AND a real bucket for that class -&gt; {@code your_posts}; else evening 17:00-22:00
     * with {@code suggestion} -- the frontend must say "suggested", never "your best time", for the
     * latter.
     */
    private WindowPlan determineWindow(PostingPattern pattern, LocalDate date) {
        if (pattern.enoughData()) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            String prefix = weekend ? "weekend " : "weekday ";
            // pattern.windows() is already sorted best (highest engagement rate) first.
            Optional<PatternWindow> best =
                    pattern.windows().stream().filter(w -> w.label().startsWith(prefix)).findFirst();
            if (best.isPresent()) {
                String daypart = best.get().label().substring(prefix.length());
                LocalTime[] range = daypartRange(daypart);
                return new WindowPlan(daypart, range[0], range[1], WINDOW_SOURCE_YOUR_POSTS);
            }
        }
        return new WindowPlan(SUGGESTION_LABEL, SUGGESTION_FROM, SUGGESTION_TO, WINDOW_SOURCE_SUGGESTION);
    }

    /** IST dayparts, CHALLENGE-SPEC.md "facts already verified": morning 05-12, afternoon 12-17,
     * evening 17-22, night 22-05. */
    private static LocalTime[] daypartRange(String daypart) {
        return switch (daypart) {
            case "morning" -> new LocalTime[] {LocalTime.of(5, 0), LocalTime.of(12, 0)};
            case "afternoon" -> new LocalTime[] {LocalTime.of(12, 0), LocalTime.of(17, 0)};
            case "evening" -> new LocalTime[] {LocalTime.of(17, 0), LocalTime.of(22, 0)};
            case "night" -> new LocalTime[] {LocalTime.of(22, 0), LocalTime.of(5, 0)};
            default -> new LocalTime[] {SUGGESTION_FROM, SUGGESTION_TO};
        };
    }

    // ============================== Comparison ==============================

    /**
     * CHALLENGE-SPEC.md Backend &sect;6 -- always computed, even with no challenge at all. Never a
     * percentage from zero (a zero last-week reach yields {@code null}, not a divide-by-zero or a
     * misleading 100%+); {@code enoughToCompare} requires at least {@link #MIN_SETTLED_TO_COMPARE}
     * settled posts in BOTH weeks, or both change fields are {@code null} with a plain {@code note}.
     */
    private Comparison computeComparison(String creatorProfileId, LocalDate today, Instant now) {
        LocalDate thisWeekFrom = today.minusDays(7);
        LocalDate thisWeekTo = today.minusDays(1);
        LocalDate lastWeekFrom = today.minusDays(14);
        LocalDate lastWeekTo = today.minusDays(8);

        Instant queryFrom = lastWeekFrom.atStartOfDay(IST).toInstant();
        List<MediaMetric> posts =
                mediaMetricsRepository.findNewestSnapshotPerPostSince(creatorProfileId, queryFrom);

        WeekStat thisWeek = weekStat(posts, thisWeekFrom, thisWeekTo, now);
        WeekStat lastWeek = weekStat(posts, lastWeekFrom, lastWeekTo, now);

        boolean enoughToCompare =
                thisWeek.settledPosts() >= MIN_SETTLED_TO_COMPARE && lastWeek.settledPosts() >= MIN_SETTLED_TO_COMPARE;
        if (!enoughToCompare) {
            return new Comparison(thisWeek, lastWeek, null, null, false, NOT_ENOUGH_TO_COMPARE_NOTE);
        }

        Double reachChangePercent =
                lastWeek.reach() > 0
                        ? round1((thisWeek.reach() - lastWeek.reach()) * 100.0 / lastWeek.reach())
                        : null; // never a percentage from zero
        Double thisRate = parseRateOrNull(thisWeek.engagementRate());
        Double lastRate = parseRateOrNull(lastWeek.engagementRate());
        Double engagementChangePoints =
                thisRate != null && lastRate != null ? round1(thisRate - lastRate) : null;

        return new Comparison(thisWeek, lastWeek, reachChangePercent, engagementChangePoints, true, null);
    }

    private WeekStat weekStat(List<MediaMetric> posts, LocalDate from, LocalDate to, Instant now) {
        List<MediaMetric> inWeek =
                posts.stream()
                        .filter(p -> p.getPostedAt() != null)
                        .filter(
                                p -> {
                                    LocalDate d = p.getPostedAt().atZone(IST).toLocalDate();
                                    return !d.isBefore(from) && !d.isAfter(to);
                                })
                        .toList();
        List<MediaMetric> settled =
                inWeek.stream()
                        .filter(p -> Duration.between(p.getPostedAt(), now).compareTo(CreatorPostRules.SETTLING_PERIOD) >= 0)
                        .toList();
        long reach = settled.stream().mapToLong(p -> p.getReach() == null ? 0L : p.getReach()).sum();
        List<MediaMetric> validForRate =
                settled.stream().filter(p -> p.getReach() != null && p.getReach() > 0).toList();
        String engagementRate =
                validForRate.isEmpty() ? "0.0%" : formatRate(averageRawEngagementRate(validForRate));
        return new WeekStat(from, to, inWeek.size(), settled.size(), reach, engagementRate);
    }

    private static double averageRawEngagementRate(List<MediaMetric> posts) {
        double sum = 0;
        for (MediaMetric post : posts) {
            long engagement = post.getEngagement() == null ? 0L : post.getEngagement();
            sum += engagement / (double) post.getReach();
        }
        return sum / posts.size();
    }

    private static String formatRate(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }

    private static double parseRate(String formattedPercent) {
        return Double.parseDouble(formattedPercent.substring(0, formattedPercent.length() - 1));
    }

    private static Double parseRateOrNull(String formattedPercent) {
        return formattedPercent == null ? null : parseRate(formattedPercent);
    }

    private static Double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
