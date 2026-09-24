package com.influora.domain.entity;

import com.influora.domain.enums.ChallengeDayType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One planned day of a {@link CreatorChallenge} (CHALLENGE-SPEC.md, Backend &sect;1/&sect;2/&sect;3).
 * Seven rows per challenge, {@code dayIndex} 0-6, written once at {@code start()} time and never
 * re-planned afterwards -- only the "done" fields below are ever updated later, by {@code
 * CreatorChallengeService}'s lazy tick-off on a GET.
 *
 * <p>{@link #windowLabel}/{@link #windowFrom}/{@link #windowTo}/{@link #windowSource} are all null
 * exactly when {@link #plannedType} is {@link ChallengeDayType#REST} -- there is no window to plan
 * for a day the creator is not asked to post.
 *
 * <p>{@link #doneMediaId}/{@link #postedType}/{@link #matchedType}/{@link #doneAt} stay null until
 * the tick-off finds a real post landing on this day's IST calendar date. {@code status} (DONE /
 * TODAY / UPCOMING / CHECKING / MISSED / REST) is deliberately NOT a column here -- it is derived
 * fresh on every read from {@code doneAt}, {@code plannedType} and the caller's {@code today}/{@code
 * now} (see {@code CreatorChallengeService#deriveStatus}), exactly the way {@code
 * CreatorPostingPatternService} never persists a pre-computed answer either.
 */
@Entity
@Table(name = "creator_challenge_days")
public class CreatorChallengeDay {

    @EmbeddedId private CreatorChallengeDayId id;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "planned_type", nullable = false, length = 12)
    private ChallengeDayType plannedType;

    @Column(name = "window_label", length = 12)
    private String windowLabel;

    @Column(name = "window_from")
    private LocalTime windowFrom;

    @Column(name = "window_to")
    private LocalTime windowTo;

    /** {@code your_posts} or {@code suggestion} -- see {@link CreatorChallenge} class javadoc. */
    @Column(name = "window_source", length = 12)
    private String windowSource;

    @Column(name = "done_media_id", length = 50)
    private String doneMediaId;

    @Column(name = "posted_type", length = 20)
    private String postedType;

    @Column(name = "matched_type")
    private Boolean matchedType;

    @Column(name = "done_at")
    private Instant doneAt;

    protected CreatorChallengeDay() {}

    public static CreatorChallengeDay plan(
            String challengeId,
            int dayIndex,
            LocalDate date,
            ChallengeDayType plannedType,
            String windowLabel,
            LocalTime windowFrom,
            LocalTime windowTo,
            String windowSource) {
        CreatorChallengeDay day = new CreatorChallengeDay();
        day.id = new CreatorChallengeDayId(challengeId, dayIndex);
        day.date = date;
        day.plannedType = plannedType;
        day.windowLabel = windowLabel;
        day.windowFrom = windowFrom;
        day.windowTo = windowTo;
        day.windowSource = windowSource;
        return day;
    }

    /** Ticks this day off with a real post -- CHALLENGE-SPEC.md Backend &sect;3. Any post counts
     * (consistency is the point); {@code matchedType} records whether its type matched the plan. */
    public void markDone(String mediaId, String postedType, boolean matchedType, Instant doneAt) {
        this.doneMediaId = mediaId;
        this.postedType = postedType;
        this.matchedType = matchedType;
        this.doneAt = doneAt;
    }

    public CreatorChallengeDayId getId() {
        return id;
    }

    public String getChallengeId() {
        return id.getChallengeId();
    }

    public int getDayIndex() {
        return id.getDayIndex();
    }

    public LocalDate getDate() {
        return date;
    }

    public ChallengeDayType getPlannedType() {
        return plannedType;
    }

    public String getWindowLabel() {
        return windowLabel;
    }

    public LocalTime getWindowFrom() {
        return windowFrom;
    }

    public LocalTime getWindowTo() {
        return windowTo;
    }

    public String getWindowSource() {
        return windowSource;
    }

    public String getDoneMediaId() {
        return doneMediaId;
    }

    public String getPostedType() {
        return postedType;
    }

    public Boolean getMatchedType() {
        return matchedType;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public boolean isDone() {
        return doneAt != null;
    }
}
