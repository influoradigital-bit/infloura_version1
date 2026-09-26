package com.influora.repository;

import com.influora.domain.entity.CreatorChallengeDay;
import com.influora.domain.entity.CreatorChallengeDayId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link CreatorChallengeDay} storage-abstraction repository (CHALLENGE-SPEC.md). */
public interface CreatorChallengeDayRepository
        extends JpaRepository<CreatorChallengeDay, CreatorChallengeDayId> {

    /** All 7 planned days of a challenge, in order -- the whole strip. */
    List<CreatorChallengeDay> findByIdChallengeIdOrderByIdDayIndexAsc(String challengeId);
}
