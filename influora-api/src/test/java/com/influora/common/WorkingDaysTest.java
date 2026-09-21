package com.influora.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one definition of "working day" on this platform, tested directly.
 *
 * <p>Every date in here is a real weekday that was checked, not a number that happened to make the
 * assertion pass: 2026-09-21 is a Monday, 2026-09-25 a Friday, 2026-09-26/27 the weekend.
 */
class WorkingDaysTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 21);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 22);
    private static final LocalDate WED = LocalDate.of(2026, 9, 23);
    private static final LocalDate THU = LocalDate.of(2026, 9, 24);
    private static final LocalDate FRI = LocalDate.of(2026, 9, 25);
    private static final LocalDate SAT = LocalDate.of(2026, 9, 26);
    private static final LocalDate SUN = LocalDate.of(2026, 9, 27);
    private static final LocalDate NEXT_MON = LocalDate.of(2026, 9, 28);
    private static final LocalDate NEXT_WED = LocalDate.of(2026, 9, 30);

    @Test
    @DisplayName("Monday to Friday are working days, Saturday and Sunday are not")
    void weekdaysOnly() {
        assertThat(WorkingDays.isWorkingDay(MON)).isTrue();
        assertThat(WorkingDays.isWorkingDay(FRI)).isTrue();
        assertThat(WorkingDays.isWorkingDay(SAT)).isFalse();
        assertThat(WorkingDays.isWorkingDay(SUN)).isFalse();
    }

    @Nested
    @DisplayName("addWorkingDays")
    class AddWorkingDays {

        @Test
        @DisplayName("counts forward from the day after, skipping the weekend")
        void skipsTheWeekend() {
            // Mid-week, no weekend crossed: Monday + 3 = Thursday.
            assertThat(WorkingDays.addWorkingDays(MON, 3)).isEqualTo(THU);
            // Crosses a weekend: Wednesday + 3 would be Saturday on a calendar count.
            assertThat(WorkingDays.addWorkingDays(WED, 3)).isEqualTo(NEXT_MON);
            // Friday + 3 = the following Wednesday, not the following Monday.
            assertThat(WorkingDays.addWorkingDays(FRI, 3)).isEqualTo(NEXT_WED);
        }

        @Test
        @DisplayName("falsified: a plain calendar count would land on the weekend")
        void falsifyCalendarCount() {
            // If addWorkingDays were LocalDate::plusDays, Wednesday + 3 would be Saturday.
            assertThat(WorkingDays.addWorkingDays(WED, 3)).isNotEqualTo(WED.plusDays(3));
            assertThat(WorkingDays.addWorkingDays(WED, 3)).isNotEqualTo(SAT);
            // And the returned date is always itself a working day - never a Saturday or Sunday.
            for (int start = 0; start < 7; start++) {
                for (int days = 1; days <= 5; days++) {
                    assertThat(WorkingDays.isWorkingDay(WorkingDays.addWorkingDays(MON.plusDays(start), days)))
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("a submission on a weekend starts counting from the next working day")
        void weekendSubmission() {
            // Saturday + 2 = Tuesday: Monday is the first working day that counts.
            assertThat(WorkingDays.addWorkingDays(SAT, 2)).isEqualTo(LocalDate.of(2026, 9, 29));
            assertThat(WorkingDays.addWorkingDays(SUN, 2)).isEqualTo(LocalDate.of(2026, 9, 29));
        }

        @Test
        @DisplayName("refuses a window of zero days rather than returning a non-working day")
        void refusesZero() {
            assertThatThrownBy(() -> WorkingDays.addWorkingDays(SAT, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 1");
            assertThatThrownBy(() -> WorkingDays.addWorkingDays(MON, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("workingDaysLeft")
    class WorkingDaysLeft {

        /** Midday IST on the given date - unambiguously inside that day in {@link WorkingDays#ZONE}. */
        private Instant middayIst(LocalDate date) {
            return date.atTime(12, 0).atZone(WorkingDays.ZONE).toInstant();
        }

        @Test
        @DisplayName("counts the working days still to come, never today")
        void countsRemaining() {
            assertThat(WorkingDays.workingDaysLeft(middayIst(MON), THU)).isEqualTo(3);
            assertThat(WorkingDays.workingDaysLeft(middayIst(TUE), THU)).isEqualTo(2);
            assertThat(WorkingDays.workingDaysLeft(middayIst(WED), THU)).isEqualTo(1);
            // On the due date itself: nothing is "still to come" - the UI reads this as due today.
            assertThat(WorkingDays.workingDaysLeft(middayIst(THU), THU)).isZero();
        }

        @Test
        @DisplayName("weekends in the window are not counted as days left")
        void weekendsAreNotDaysLeft() {
            // Friday, due the following Wednesday: Mon, Tue, Wed = 3 working days, though five
            // calendar days separate them.
            assertThat(WorkingDays.workingDaysLeft(middayIst(FRI), NEXT_WED)).isEqualTo(3);
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(FRI, NEXT_WED)).isEqualTo(5);
        }

        @Test
        @DisplayName("never negative once past the due date")
        void neverNegative() {
            assertThat(WorkingDays.workingDaysLeft(middayIst(FRI), TUE)).isZero();
            assertThat(WorkingDays.workingDaysLeft(middayIst(NEXT_WED), MON)).isZero();
        }
    }

    @Test
    @DisplayName("endOfDay is the first instant of the next day in IST, so 23:59 is still inside")
    void endOfDayBoundary() {
        Instant deadline = WorkingDays.endOfDay(THU);
        Instant lastMinuteThursday = THU.atTime(23, 59).atZone(WorkingDays.ZONE).toInstant();
        Instant firstMinuteFriday = FRI.atTime(0, 1).atZone(WorkingDays.ZONE).toInstant();
        assertThat(lastMinuteThursday).isBefore(deadline);
        assertThat(firstMinuteFriday).isAfter(deadline);
        assertThat(WorkingDays.dateOf(deadline.minusMillis(1))).isEqualTo(THU);
    }

    @Test
    @DisplayName("day boundaries are IST, not the JVM's default zone")
    void zoneIsIst() {
        // 19:00 UTC on Wednesday is already Thursday 00:30 IST. A clock measured in UTC would
        // call this Wednesday and give the brand a whole extra day.
        Instant lateWednesdayUtc = Instant.parse("2026-09-23T19:00:00Z");
        assertThat(WorkingDays.dateOf(lateWednesdayUtc)).isEqualTo(THU);
    }
}
