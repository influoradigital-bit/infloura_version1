package com.influora.repository;

import com.influora.domain.entity.CreatorChallenge;
import com.influora.domain.enums.CreatorChallengeStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@link CreatorChallenge} storage-abstraction repository (CHALLENGE-SPEC.md). */
public interface CreatorChallengeRepository extends JpaRepository<CreatorChallenge, String> {

    /**
     * The creator's current ACTIVE challenge, if any -- {@code active_key} is exactly the
     * creator's own user id while ACTIVE (see the entity/migration javadoc), so this is also the
     * pre-check {@code CreatorChallengeService#start} uses before relying on the real UNIQUE
     * constraint for the race-safe guarantee.
     */
    Optional<CreatorChallenge> findByActiveKey(String creatorUserId);

    /** Most recent non-active challenge (COMPLETED or ENDED) for "lastCompleted" in the contract. */
    Optional<CreatorChallenge> findFirstByCreatorUserIdAndStatusInOrderByStartedOnDesc(
            String creatorUserId, List<CreatorChallengeStatus> statuses);

    /** ACTIVE challenges due for the 08:00 IST morning-email job (no per-creator scan needed). */
    List<CreatorChallenge> findByStatus(CreatorChallengeStatus status);
}
