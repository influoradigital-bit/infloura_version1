package com.influora.repository;

import com.influora.domain.entity.FestivalCouponCopy;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link FestivalCouponCopy} (T-FESTIVALBOX-0905 phase 6).
 */
@Repository
public interface FestivalCouponCopyRepository extends JpaRepository<FestivalCouponCopy, String> {

    /**
     * Atomically increments the (edition, sponsor_slug, bucket_day) bucket, creating it on the first copy
     * of the day and bumping {@code copy_count} on every one after that — in ONE statement, with no
     * read step in application code.
     *
     * <p><b>Why a native {@code INSERT ... ON DUPLICATE KEY UPDATE} and not a
     * find-then-increment-then-save, and not {@code
     * FestivalSponsorProvisioningService}'s {@code PESSIMISTIC_WRITE} row lock:</b> that lock
     * pattern exists to protect a multi-step read-modify-write against a second concurrent caller
     * racing between the read and the write (provisioning reads {@code provisionedWorkspaceId},
     * decides, then inserts several rows). A counter has no such window to protect — there is
     * nothing to decide between the read and the write, only "add one" — so MySQL's own atomic
     * upsert does the whole job in the same statement that locates the row, with no lock held
     * across a round trip to application code and no TOCTOU gap for a second concurrent copy to
     * fall into. A find-then-increment-then-save version of this method would lose updates under
     * exactly the concurrent-copy scenario {@code FestivalCouponCopyServiceTest} exercises — that
     * is the shape this method exists to rule out. {@code coupon_code} is intentionally NOT part of
     * the unique key (see the migration header): {@code VALUES(coupon_code)} lets it track
     * whichever code was copied most recently for that sponsor/day without affecting the count.
     *
     * <p>{@code id} is only used on the INSERT branch; a concurrent call that instead matches the
     * unique key takes the UPDATE branch and the freshly-generated id it was called with is simply
     * discarded by MySQL — the caller does not need to know which branch fired.
     *
     * @return the number of rows MySQL reports affected — 1 for a fresh insert, 2 for an update (
     *     MySQL's {@code ON DUPLICATE KEY UPDATE} convention, since the row is both matched and
     *     changed); never used by callers for anything other than diagnostics, since it does not
     *     distinguish "created" from "incremented" reliably across engines.
     */
    @Modifying
    @Transactional
    @Query(
            value =
                    "INSERT INTO festival_coupon_copies "
                            + "(id, edition, sponsor_slug, coupon_code, bucket_day, copy_count, created_at, updated_at) "
                            + "VALUES (:id, :edition, :sponsorSlug, :couponCode, :day, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
                            + "ON DUPLICATE KEY UPDATE "
                            + "copy_count = copy_count + 1, "
                            + "coupon_code = VALUES(coupon_code), "
                            + "updated_at = CURRENT_TIMESTAMP",
            nativeQuery = true)
    int recordCopy(
            @Param("id") String id,
            @Param("edition") String edition,
            @Param("sponsorSlug") String sponsorSlug,
            @Param("couponCode") String couponCode,
            @Param("day") LocalDate day);

    /**
     * Per-sponsor totals for one edition, highest-copy sponsor first — backs {@code
     * AdminFestivalMetricsService}'s {@code sponsorTotals}.
     */
    @Query(
            "SELECT c.sponsorSlug, SUM(c.copyCount) FROM FestivalCouponCopy c "
                    + "WHERE c.edition = :edition GROUP BY c.sponsorSlug ORDER BY SUM(c.copyCount) DESC")
    List<Object[]> sumBySponsorForEdition(@Param("edition") String edition);

    /**
     * The full daily series for one edition (every sponsor, every day it has a row), for {@code
     * AdminFestivalMetricsService}'s time-series chart. Small by construction — bounded by
     * sponsors x days, per the migration header.
     */
    List<FestivalCouponCopy> findByEditionOrderByDayAscSponsorSlugAsc(String edition);
}
