package com.influora.repository;

import com.influora.domain.entity.FestivalEnquiry;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link FestivalEnquiry} (T-FESTIVALBOX-0905).
 *
 * <p>Extends {@link JpaSpecificationExecutor} because the admin list is filtered on an open
 * combination of type/status/search — the same reason {@code ExternalCreatorRepository} does.
 * Filters are composed in {@link FestivalEnquirySpecs}.
 */
@Repository
public interface FestivalEnquiryRepository
        extends JpaRepository<FestivalEnquiry, String>, JpaSpecificationExecutor<FestivalEnquiry> {

    /**
     * Submissions from one origin since {@code since}. Uses the
     * {@code idx_festival_enquiries_ip_created} composite index.
     *
     * <p><b>No longer backs the live per-IP throttle</b> (T-FESTIVALBOX-0905 phase 9) — that moved
     * to {@link com.influora.service.security.AbuseThrottleService}'s atomic upsert counter, because
     * a {@code SELECT COUNT} taken before any of N concurrent inserts commit is a TOCTOU gap under
     * REPEATABLE READ: N parallel requests all read "under the cap" and all N pass. See
     * {@code FestivalEnquiryService#enforceThrottle}. Kept here — still exercised by {@code
     * FestivalEnquiryRepositoryQueryTest} — as a general-purpose per-origin read, e.g. for a future
     * admin abuse-report view; it is a correct COUNT query, just not a safe THROTTLE on its own.
     *
     * <p>Counts by the salted hash, never a raw address, so it works without the table ever holding
     * something that re-identifies a visitor.
     */
    long countBySourceIpHashAndCreatedAtAfter(String sourceIpHash, Instant since);

    /**
     * Recent submissions from one email address. Uses {@code idx_festival_enquiries_email}.
     *
     * <p><b>No longer backs the live per-email throttle</b> — same TOCTOU reasoning and same fix as
     * {@link #countBySourceIpHashAndCreatedAtAfter}'s javadoc above; see
     * {@code FestivalEnquiryService#enforceThrottle}. Also note: this counts the STORED (verbatim)
     * address, so it does NOT collapse plus-addressed variants ({@code attacker+1@x.com} vs {@code
     * attacker+2@x.com}) the way the live throttle's {@code AbuseThrottleService} key does — see
     * {@code FestivalEnquiryService#canonicalizeEmailForThrottle}. Kept as a general-purpose
     * per-address read for the same reason as above.
     *
     * <p>Deliberately NOT a uniqueness check — see the migration's note on why {@code email} has no
     * unique key. A brand legitimately enquiring twice for two editions must succeed; only a burst
     * inside the throttle window is refused.
     *
     * <p><b>Case is handled by the CALLER, not by {@code IgnoreCase} — do not add it back.</b>
     * [SEC: Kabir F-6] This was {@code countByEmailIgnoreCaseAndCreatedAtAfter}, which Spring Data
     * compiles to {@code WHERE upper(email) = upper(?)}. MySQL 8 cannot satisfy that from the plain
     * B-tree {@code idx_festival_enquiries_email} (no functional index is declared), so every
     * unauthenticated POST triggered a FULL TABLE SCAN — on a table whose size an anonymous
     * attacker controls, making the endpoint progressively more expensive as it was abused.
     * {@code FestivalEnquiryService#submit} already lower-cases the address before both the insert
     * and this lookup, so every stored value is lower-case and plain equality is exactly equivalent
     * — and index-usable.
     */
    long countByEmailAndCreatedAtAfter(@Param("email") String email, @Param("since") Instant since);

    /**
     * Row count per status across the whole table, for the admin inbox header.
     *
     * <p>A group-by aggregate rather than {@code findAll()} + count in Java: the header total is
     * deliberately unfiltered and unpaged, so the naive version would load every enquiry ever
     * submitted into memory on each render of page 1 — fine at fifty rows, a growing table scan
     * hydrated into entities at fifty thousand. This returns one small row per status instead, and
     * the {@code idx_festival_enquiries_status} index covers the grouping.
     *
     * @return one {@code [FestivalEnquiryStatus, Long]} pair per status that has at least one row.
     *     Statuses with no rows are absent — the caller seeds zeros for the full enum.
     */
    @Query("SELECT e.status, COUNT(e) FROM FestivalEnquiry e GROUP BY e.status")
    List<Object[]> countGroupedByStatus();

    /**
     * Row-locking finder for {@code FestivalSponsorProvisioningService#provision}
     * (T-FESTIVALBOX-0905 phase 2) — same {@code PESSIMISTIC_WRITE} + {@code findByIdForUpdate}
     * shape as {@link WalletRepository#findByIdForUpdate}.
     *
     * <p>This is the ENTIRE concurrency fix for double-clicking "Provision": the lock is acquired
     * here and held by the surrounding {@code @Transactional} until commit (or rollback), so there
     * is no window between "check {@code provisionedWorkspaceId == null}" and "insert the new
     * rows" for a second call to slip through — it simply blocks on this query until the first
     * transaction finishes, then sees the field already set. See F-0650
     * ({@code .proof-os/gates/F-0650-provisioning-lock-commit-order.sh}) for the two shapes this
     * deliberately does NOT use: an unlocked check-then-insert, and a lock released before the
     * inserting transaction actually commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM FestivalEnquiry e WHERE e.id = :id")
    Optional<FestivalEnquiry> findByIdForUpdate(@Param("id") String id);
}
