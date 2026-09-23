package com.influora.web.dto.challenge;

import java.time.LocalDate;
import java.util.List;

/**
 * The creator 7-day challenge contract (CHALLENGE-SPEC.md) -- built jointly with the frontend
 * (Ananya) and frozen: every field name/shape below is exactly what {@code
 * GET/POST /api/v1/creator/challenge} sends, and neither side changes it unilaterally.
 */
public final class ChallengeDtos {

    private ChallengeDtos() {}

    public record ChallengeState(
            boolean instagramConnected,
            ActiveChallenge active,
            LastCompleted lastCompleted,
            Comparison comparison) {}

    public record ActiveChallenge(
            String id, LocalDate startedOn, int dayNumber, int streak, List<ChallengeDay> days) {}

    /** @param plannedType REEL | CAROUSEL | POST | REST
     * @param window null on a REST day
     * @param windowSource "your_posts" or "suggestion" -- null on a REST day
     * @param status DONE | TODAY | UPCOMING | CHECKING | MISSED | REST
     * @param matchedType null until the day is DONE
     * @param postedType the raw media type actually posted; null until DONE
     * @param permalink the Instagram permalink of the post that ticked this day off; null until DONE */
    public record ChallengeDay(
            int dayIndex,
            LocalDate date,
            String plannedType,
            Window window,
            String windowSource,
            String status,
            Boolean matchedType,
            String postedType,
            String permalink) {}

    public record Window(String label, String from, String to) {}

    public record LastCompleted(String id, LocalDate startedOn, int daysDone, int daysPlanned) {}

    public record Comparison(
            WeekStat thisWeek,
            WeekStat lastWeek,
            Double reachChangePercent,
            Double engagementChangePoints,
            boolean enoughToCompare,
            String note) {}

    /** @param engagementRate pre-formatted (e.g. "4.1%"), average over SETTLED posts only, same
     * display convention as {@code CreatorPostingPatternService.PatternWindow#engagementRate} --
     * never a number the caller could be tempted to do further arithmetic on. */
    public record WeekStat(
            LocalDate from, LocalDate to, int posts, int settledPosts, long reach, String engagementRate) {}
}
