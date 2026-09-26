package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link CreatorChallengeDay} -- a day is only ever addressed through its owning
 * challenge, so {@code (challenge_id, day_index)} is the primary key rather than a synthetic id
 * (CHALLENGE-SPEC.md Backend &sect;1). */
@Embeddable
public class CreatorChallengeDayId implements Serializable {

    @Column(name = "challenge_id", length = 26)
    private String challengeId;

    @Column(name = "day_index")
    private int dayIndex;

    protected CreatorChallengeDayId() {}

    public CreatorChallengeDayId(String challengeId, int dayIndex) {
        this.challengeId = challengeId;
        this.dayIndex = dayIndex;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public int getDayIndex() {
        return dayIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CreatorChallengeDayId that)) {
            return false;
        }
        return dayIndex == that.dayIndex && Objects.equals(challengeId, that.challengeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(challengeId, dayIndex);
    }
}
