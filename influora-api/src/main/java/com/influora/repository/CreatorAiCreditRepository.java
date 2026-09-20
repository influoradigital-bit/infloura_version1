package com.influora.repository;

import com.influora.domain.entity.CreatorAiCredit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- CREDITS-SPEC.md §2.6.
 *
 * <p>Every {@code @Modifying} query here is annotated {@code clearAutomatically = true,
 * flushAutomatically = true}. This is Priya's fix to the spec's originally-copied {@code
 * BrandAiCreditRepository} shape (which predates the {@code clearAutomatically}/{@code
 * flushAutomatically} pattern this file already uses on every one of ITS {@code @Modifying}
 * queries): a JPQL bulk UPDATE bypasses the persistence context entirely, so a caller's later
 * {@code findById} inside the SAME transaction would otherwise return the SAME stale MANAGED
 * instance with pre-debit values -- making a retry-on-zero-rows loop retry with the identical
 * (already-failing) arguments forever, and worse, making the ledger's {@code monthly_after} /
 * {@code purchased_after} columns record WRONG post-write balances (read off the stale instance
 * instead of the real row).
 *
 * <p><b>Deviation from CREDITS-SPEC.md §2.6's printed JPQL:</b> the spec's literal text sets
 * {@code c.updatedAt = CURRENT_TIMESTAMP} on every method below, copied from {@code
 * BrandAiCreditRepository}'s ORIGINAL (pre-T-CREDITCLOCK-0918) shape. But that same interface's
 * CURRENT code (the shape §2.6 says to mirror -- "Same shape as {@code
 * BrandAiCreditRepository.tryDecrement}/{@code refundCredits}") no longer does that: {@code
 * BrandAiCreditRepository.java} L15-28 explains why it was changed to bind {@code updatedAt} to a
 * passed-in {@code :now Instant} parameter instead -- H2Dialect's {@code current_timestamp}
 * function contributor types the JPQL literal as {@code java.sql.Timestamp}, which Hibernate 6's
 * semantic validator refuses to assign to an {@code Instant}-typed column, breaking H2-backed
 * testability (and eagerly failing EVERY {@code @Query} method in a repository the moment any ONE
 * of them is registered under {@code @EnableJpaRepositories}, per that same javadoc). Following
 * the spec's literal text here would silently reintroduce the exact defect that commit fixed, for
 * zero production benefit (MySQL evaluates {@code :now} and {@code CURRENT_TIMESTAMP} to the same
 * wall-clock instant for this codebase's non-transaction-straddling callers). Every method below
 * therefore takes {@code @Param("now") Instant now} and binds {@code c.updatedAt = :now}, exactly
 * like the current {@code BrandAiCreditRepository}, which is also what makes {@code
 * CreatorAiCreditRepositoryH2Test} able to execute these queries for real against H2 instead of
 * only pinning their JPQL text by reflection.
 */
public interface CreatorAiCreditRepository extends JpaRepository<CreatorAiCredit, String> {

    @Query("SELECT c.creatorUserId FROM CreatorAiCredit c")
    List<String> findAllCreatorUserIds();

    /**
     * The atomic "enough in both buckets" debit. The single conditional UPDATE is the whole
     * point -- CREDITS-SPEC calls out {@code AICreditService#tryConsume} (brand path) as a
     * read-then-decide that is fine for a paid cap and WRONG for a free allowance; this method
     * must never be replaced by a Java read-then-decide. The caller computes the (monthlyPart,
     * purchasedPart) split from a fresh read and retries once on 0 rows (a concurrent debit
     * changed the split) -- that retry is only meaningful because of {@code
     * clearAutomatically}/{@code flushAutomatically} above.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE CreatorAiCredit c SET c.monthlyRemaining = c.monthlyRemaining - :m, "
                    + "c.purchasedBalance = c.purchasedBalance - :p, c.updatedAt = :now "
                    + "WHERE c.creatorUserId = :id AND c.monthlyRemaining >= :m AND c.purchasedBalance >= :p")
    int tryDebit(
            @Param("id") String id,
            @Param("m") int monthlyPart,
            @Param("p") int purchasedPart,
            @Param("now") Instant now);

    /**
     * Refund counterpart to {@link #tryDebit}. The monthly part is clamped to {@code
     * monthlyAllotment} (CASE WHEN, matching {@code BrandAiCreditRepository#refundCredits}'s
     * clamp) -- a monthly reset landing between a turn's send-time charge and its later release
     * must never let this add push {@code monthlyRemaining} above the allotment. The purchased
     * part never expires and is added unconditionally (R2).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE CreatorAiCredit c SET c.monthlyRemaining = "
                    + "CASE WHEN c.monthlyRemaining + :m > c.monthlyAllotment THEN c.monthlyAllotment ELSE c.monthlyRemaining + :m END, "
                    + "c.purchasedBalance = c.purchasedBalance + :p, c.updatedAt = :now "
                    + "WHERE c.creatorUserId = :id")
    int refund(
            @Param("id") String id,
            @Param("m") int monthlyPart,
            @Param("p") int purchasedPart,
            @Param("now") Instant now);

    /** Adds to purchasedBalance only -- packs, admin grants, and the signup grant land here (R2). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE CreatorAiCredit c SET c.purchasedBalance = c.purchasedBalance + :n, c.updatedAt = :now "
                    + "WHERE c.creatorUserId = :id")
    int addPurchased(@Param("id") String id, @Param("n") int credits, @Param("now") Instant now);

    /**
     * Refund counterpart to the R7 daily-action-counter bump. Floors at 0 and only applies if
     * {@code dailyActionsDate} is still the SAME day the charge was recorded against -- a day
     * rollover already reset the counter for real, so refunding a stale day's counter would be
     * meaningless.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE CreatorAiCredit c SET c.dailyActionsUsed = "
                    + "CASE WHEN c.dailyActionsUsed > :n THEN c.dailyActionsUsed - :n ELSE 0 END, c.updatedAt = :now "
                    + "WHERE c.creatorUserId = :id AND c.dailyActionsDate = :today")
    int refundDailyActions(
            @Param("id") String id,
            @Param("n") int n,
            @Param("today") LocalDate today,
            @Param("now") Instant now);

    /**
     * Amendment A3/A4 (§4A.3, §2.6) -- the atomic weekly free-search claim. Returns 1 when a free
     * search was claimed and 0 when the week's free searches are spent; the caller branches on
     * exactly that (debit 25 tenths instead when it returns 0). One statement, not a check
     * followed by a bump -- for a FREE allowance (unlike a paid daily cap) a read-then-decide
     * shape is a free-money leak: two parallel requests can both read "1 used" and both go free.
     *
     * <p>The {@code c.freeSearchWeekStart IS NULL} term is NOT redundant with {@code
     * c.freeSearchWeekStart <> :weekStart}: {@code freeSearchWeekStart} is NULL on every
     * freshly-created row, and in SQL {@code NULL <> :weekStart} evaluates to {@code NULL}, not
     * {@code TRUE}. Without the explicit {@code IS NULL} term, the very first free search of
     * every creator's life would match nothing, update zero rows, and be silently billed 25
     * tenths -- a defect that compiles and passes a Mockito test, and would only show up as a
     * support ticket.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            "UPDATE CreatorAiCredit c SET c.freeSearchWeekStart = :weekStart, "
                    + "c.freeSearchesUsed = CASE WHEN c.freeSearchWeekStart = :weekStart THEN c.freeSearchesUsed + 1 ELSE 1 END, "
                    + "c.updatedAt = :now "
                    + "WHERE c.creatorUserId = :id AND ("
                    + "  c.freeSearchWeekStart IS NULL OR c.freeSearchWeekStart <> :weekStart "
                    + "  OR c.freeSearchesUsed < :cap)")
    int tryClaimFreeSearch(
            @Param("id") String id,
            @Param("weekStart") LocalDate weekStart,
            @Param("cap") int cap,
            @Param("now") Instant now);
}
