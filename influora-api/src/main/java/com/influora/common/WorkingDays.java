package com.influora.common;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The single definition of a working day on this platform: <b>Monday to Friday</b>.
 *
 * <p>Every promise Influora makes to a brand or a creator with the words "working days" in it
 * resolves here — the brand's review window, the re-review window after a revision, and any clock
 * added later. Before this class the only "3 days" anywhere was a private constant inside {@code
 * DashboardService} used to paint a due date on a dashboard card; nothing counted, nothing skipped
 * a weekend, and nothing enforced anything. If a second interpretation of "working day" is ever
 * wanted, it changes here once rather than in each clock.
 *
 * <p><b>No holiday list.</b> Weekends are the whole rule. Public holidays are deliberately NOT
 * modelled: inventing a holiday calendar would make the product state, in writing, that a date is
 * a non-working day when nobody has decided that it is. If a holiday list is ever adopted it
 * belongs in this class, sourced from a real published list, and every clock inherits it for free.
 *
 * <p><b>Time zone.</b> All day boundaries are IST ({@code Asia/Kolkata}) — the zone the team
 * operates in and the same zone the creator-facing scheduled jobs already pin their cron to (see
 * {@code CreatorConnectNudgeJob}). A "day" must mean the same day to the brand, the creator and
 * the person in the team inbox; deriving it from the server's default zone would make the answer
 * depend on where the container happens to run.
 */
public final class WorkingDays {

    /** The zone every working-day boundary is measured in. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private WorkingDays() {}

    /** True for Monday through Friday, false for Saturday and Sunday. */
    public static boolean isWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** The calendar date {@code instant} falls on, in {@link #ZONE}. */
    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /**
     * The {@code workingDays}-th working day strictly AFTER {@code start}, skipping weekends.
     *
     * <p>Strictly after, because the day a draft is submitted is a day that is already partly
     * gone. "3 working days to review" therefore means three whole working days on top of the day
     * of submission: submitted Monday → Thursday; submitted Friday → Wednesday; submitted Saturday
     * → Wednesday. This always rounds in the reviewer's favour, so nothing is ever chased early.
     *
     * @param workingDays must be at least 1 — a zero-day window is not a thing this platform
     *     promises anyone, and silently returning {@code start} (which may be a Saturday) would
     *     hand back a due date that is not a working day at all.
     */
    public static LocalDate addWorkingDays(LocalDate start, int workingDays) {
        if (workingDays < 1) {
            throw new IllegalArgumentException("workingDays must be at least 1, got " + workingDays);
        }
        LocalDate date = start;
        int remaining = workingDays;
        while (remaining > 0) {
            date = date.plusDays(1);
            if (isWorkingDay(date)) {
                remaining--;
            }
        }
        return date;
    }

    /**
     * The first instant AFTER {@code date} ends in {@link #ZONE} — i.e. the exclusive upper bound
     * of that day. A deadline of "end of Thursday" is Friday 00:00 IST, so a reviewer acting at
     * 23:59 on Thursday is inside the window and one acting at 00:00 on Friday is not.
     */
    public static Instant endOfDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /**
     * How many working days remain between {@code now} and the end of {@code dueDate}, counted the
     * way the sentence "you have X working days left to review" is read.
     *
     * <p>Today is never counted — the day you are standing in is not a day you still have. So on
     * the due date itself this returns {@code 0}, which the UI renders as "due today", not as
     * "overdue"; past the due date it also returns {@code 0}, and overdue-ness is answered by
     * comparing against the deadline itself ({@link #endOfDay}), never by a negative count.
     */
    public static int workingDaysLeft(Instant now, LocalDate dueDate) {
        return workingDaysBetween(dateOf(now), dueDate);
    }

    /**
     * Working days in the half-open range {@code (exclusiveFrom, inclusiveTo]}. Zero when {@code
     * inclusiveTo} is not after {@code exclusiveFrom}; never negative.
     */
    public static int workingDaysBetween(LocalDate exclusiveFrom, LocalDate inclusiveTo) {
        if (!inclusiveTo.isAfter(exclusiveFrom)) {
            return 0;
        }
        int count = 0;
        for (LocalDate date = exclusiveFrom.plusDays(1);
                !date.isAfter(inclusiveTo);
                date = date.plusDays(1)) {
            if (isWorkingDay(date)) {
                count++;
            }
        }
        return count;
    }
}
