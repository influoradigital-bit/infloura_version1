package com.influora.repository;

import com.influora.domain.entity.CreatorCreditGrant;
import com.influora.domain.enums.CreditBucket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §4). All the mutating methods here are only ever called from
 * {@code CreatorCreditService} while it holds the parent {@code CreatorCreditAccount} row lock —
 * this repository does no locking of its own.
 */
public interface CreatorCreditGrantRepository extends JpaRepository<CreatorCreditGrant, String> {

    /**
     * Spendable grants for this creator — {@code credits_remaining > 0 AND (expires_at IS NULL OR
     * expires_at > :now)}. {@code :now} is always bound explicitly, never {@code CURRENT_TIMESTAMP}
     * (matches the existing {@code BrandAiCreditRepository} convention — the same deviation lets
     * this run for real against H2 in a test, not merely be asserted by reflection).
     */
    @Query(
            "SELECT g FROM CreatorCreditGrant g WHERE g.creatorUserId = :creatorUserId "
                    + "AND g.creditsRemaining > 0 AND (g.expiresAt IS NULL OR g.expiresAt > :now)")
    List<CreatorCreditGrant> findSpendable(@Param("creatorUserId") String creatorUserId, @Param("now") Instant now);

    Optional<CreatorCreditGrant> findByCreatorUserIdAndBucketAndSourceRef(
            String creatorUserId, CreditBucket bucket, String sourceRef);

    /** Every grant of {@code bucket} for this creator that still has credit left — the monthly "expire leftovers" scan. */
    @Query(
            "SELECT g FROM CreatorCreditGrant g WHERE g.creatorUserId = :creatorUserId "
                    + "AND g.bucket = :bucket AND g.creditsRemaining > 0")
    List<CreatorCreditGrant> findWithRemainingByCreatorUserIdAndBucket(
            @Param("creatorUserId") String creatorUserId, @Param("bucket") CreditBucket bucket);

    // Locking variants (T-CREATOR-CREDITS-V2, found by CreatorCreditConcurrencyIntegrationTest on
    // real MySQL): under REPEATABLE READ a PLAIN read inside a transaction that already read
    // anything (MeeraSessionService#doSendTurn reads the conversation first) sees that earlier
    // snapshot even after the account row lock is taken, so 20 parallel charges all saw the same
    // balance and overspent. A locking read always sees the latest committed row. Use these, not
    // the plain finders, for every read that decides a write after lockAccount.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT g FROM CreatorCreditGrant g WHERE g.creatorUserId = :creatorUserId "
                    + "AND g.creditsRemaining > 0 AND (g.expiresAt IS NULL OR g.expiresAt > :now)")
    List<CreatorCreditGrant> lockSpendable(@Param("creatorUserId") String creatorUserId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM CreatorCreditGrant g WHERE g.id = :id")
    Optional<CreatorCreditGrant> lockById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT g FROM CreatorCreditGrant g WHERE g.creatorUserId = :creatorUserId "
                    + "AND g.bucket = :bucket AND g.creditsRemaining > 0")
    List<CreatorCreditGrant> lockWithRemainingByCreatorUserIdAndBucket(
            @Param("creatorUserId") String creatorUserId, @Param("bucket") CreditBucket bucket);
}
