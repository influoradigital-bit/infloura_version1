package com.influora.service.creatorcopilot;

import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.enums.CreatorRecommendationStatus;
import com.influora.repository.CreatorRecommendationRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes ONE recommendation's decided outcome in its own transaction (Meera intelligence v1, slice
 * 2; Kabir M-2 per-row isolation, L-1 frozen rows).
 *
 * <p><b>Why a separate bean with {@code REQUIRES_NEW}, and not a catch inside one transaction.</b>
 * When Hibernate's flush fails (a unique-key race on {@code uk_creator_rec_media}, an optimistic
 * lock, a dead connection) the transaction is marked rollback-only and the session is unusable:
 * catching the exception inside the same transaction and moving on to the next row does not help,
 * because the commit then throws {@code UnexpectedRollbackException} and takes every other row's
 * write with it (and the next flush on that session fails again). So {@link
 * CreatorRecommendationOutcomeService#evaluate} runs outside any transaction and calls {@link
 * #apply} once per row; each call goes through this bean's proxy (a call on the service itself
 * would be self-invocation, with a silently inert annotation), opens a fresh transaction with a
 * fresh persistence context ({@code spring.jpa.open-in-view=false}), and commits or rolls back on
 * its own. {@code REQUIRES_NEW} rather than {@code REQUIRED} so the isolation still holds if a
 * future caller wraps {@code evaluate} in a transaction.
 *
 * <p><b>Frozen rows stay frozen.</b> The row is re-read inside the transaction; the write is
 * skipped when it is frozen, or when its status or {@code version} is no longer what the evaluation
 * decided from (another evaluation got there first). Between that re-read and the commit, the
 * {@code @Version} column makes the UPDATE conditional ({@code WHERE version = ?}), so a racing
 * write fails with an optimistic-lock exception (Spring's {@code
 * ObjectOptimisticLockingFailureException}, a {@code RuntimeException}) instead of overwriting.
 * {@code saveAndFlush} makes that failure surface inside this call; either way the caller's
 * per-row catch owns it.
 */
@Component
public class CreatorRecommendationOutcomeWriter {

    /** The post that fills an OPEN row. */
    record Match(String mediaId, boolean typeMatched, Boolean windowMatched) {}

    /** A MATCHED row's settled outcome. {@code baselineMedianReach} and {@code pct} may be null. */
    record Settlement(
            Long reach, Long engagement, Long baselineMedianReach, int baselineSampleSize, Integer pct, Instant settledAt) {}

    /**
     * What one evaluation decided for one row, applied in order: an optional match, then an
     * optional settlement, then an optional terminal status (MISSED or NO_OUTCOME).
     */
    record Outcome(Match match, Settlement settlement, CreatorRecommendationStatus terminal) {

        void applyTo(CreatorRecommendation rec, String igAccountId) {
            if (match != null) {
                rec.matchTo(match.mediaId(), match.typeMatched(), match.windowMatched(), igAccountId);
            }
            if (settlement != null) {
                rec.settle(
                        settlement.reach(),
                        settlement.engagement(),
                        settlement.baselineMedianReach(),
                        settlement.baselineSampleSize(),
                        settlement.pct(),
                        settlement.settledAt());
            }
            if (terminal == CreatorRecommendationStatus.MISSED) {
                rec.markMissed(igAccountId);
            } else if (terminal == CreatorRecommendationStatus.NO_OUTCOME) {
                rec.markNoOutcome(igAccountId);
            }
        }
    }

    private final CreatorRecommendationRepository repository;

    public CreatorRecommendationOutcomeWriter(CreatorRecommendationRepository repository) {
        this.repository = repository;
    }

    /**
     * Applies {@code outcome} to row {@code id} if it is still in the state the evaluation saw.
     *
     * @return true when written; false when the row is gone (deleted with its conversation or
     *     account), frozen, or changed since it was read
     * @throws RuntimeException on a database failure, the transaction already rolled back
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean apply(
            String id,
            CreatorRecommendationStatus expectedStatus,
            Long expectedVersion,
            Outcome outcome,
            String igAccountId) {
        Optional<CreatorRecommendation> found = repository.findById(id);
        if (found.isEmpty()) {
            return false;
        }
        CreatorRecommendation rec = found.get();
        if (rec.isFrozen()
                || rec.getStatus() != expectedStatus
                || !Objects.equals(rec.getVersion(), expectedVersion)) {
            return false;
        }
        outcome.applyTo(rec, igAccountId);
        repository.saveAndFlush(rec);
        return true;
    }
}
