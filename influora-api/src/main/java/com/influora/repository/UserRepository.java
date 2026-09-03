package com.influora.repository;

import com.influora.domain.entity.User;
import com.influora.domain.enums.UserType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {

    /**
     * Active, email-verified creators who registered inside the given window and have no
     * un-revoked Meta token — i.e. an empty profile that cannot appear in brand search. Feeds
     * {@code CreatorConnectNudgeJob}.
     *
     * <p>{@code registeredAfter} is a floor, not decoration: without it the first run after
     * deployment would select every un-connected creator ever registered and email the entire back
     * catalogue at once. The window keeps the job to recent signups, which is the only cohort the
     * nudge copy is true for anyway.
     *
     * <p>Native rather than JPQL because the anti-join is over {@code meta_oauth_tokens}, which
     * {@link User} has no mapped association to — the link is {@code users.id → creator_profiles
     * .user_id → meta_oauth_tokens.creator_profile_id}. {@code revoked = 0} matches the same
     * predicate every other reader of that table uses ({@code MetricsPollingJob}, the refresh
     * sweep), so a soft-revoked token correctly reads as "not connected".
     *
     * <p><b>[SEC: Vikram, A1 fix, REVIEW-R3.md]</b> {@code AND u.deleted_at IS NULL} — same
     * blindness as {@link #countForCustomEmailAudience}/{@link #findForCustomEmailAudience} (see
     * those methods' javadoc): a soft-deleted creator is still {@code status = 'ACTIVE'}. This job
     * degrades more gracefully than the admin-custom-email path — {@code NotificationService} drops
     * a null/blank address with a warning rather than throwing — but a soft-deleted row is nonsense
     * to nudge regardless (there is no account left to connect), so it is excluded here too.
     */
    @Query(
            value =
                    """
                    SELECT u.* FROM users u
                    JOIN creator_profiles cp ON cp.user_id = u.id
                    WHERE u.user_type = 'CREATOR'
                      AND u.status = 'ACTIVE'
                      AND u.deleted_at IS NULL
                      AND u.email_verified = 1
                      AND u.created_at <= :registeredBefore
                      AND u.created_at >= :registeredAfter
                      AND NOT EXISTS (
                          SELECT 1 FROM meta_oauth_tokens t
                          WHERE t.creator_profile_id = cp.id
                            AND t.revoked = 0)
                    ORDER BY u.created_at
                    """,
            nativeQuery = true)
    List<User> findCreatorsWithoutConnectedAccount(
            @Param("registeredBefore") Instant registeredBefore,
            @Param("registeredAfter") Instant registeredAfter,
            Limit limit);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** PHONE-0829 — upfront duplicate check before {@code CreatorProfileService} sets a normalized
     * phone number, mirroring {@link #existsByEmailIgnoreCase}'s TOCTOU-aware pattern (see that
     * call site's javadoc in {@code AuthService}): this check narrows the common case, but the DB's
     * own {@code UNIQUE(phone_number)} constraint (V2__core_auth.sql) is what actually prevents a
     * race, so the write path still wraps the save in a try/catch for {@code
     * DataIntegrityViolationException}. */
    boolean existsByPhoneNumber(String phoneNumber);

    /**
     * Monthly-active-user proxy for AdminDashboardController's CEO Pulse ({@code mauBrands}/
     * {@code mauCreators}): users of the given type who have logged in since {@code since}. This
     * is a login-recency count, not a true engagement/activity MAU (no session/event tracking
     * table exists yet) — documented as an interim definition, same caveat as the GMV proxy in
     * {@code EscrowHoldRepository}.
     */
    long countByUserTypeAndLastLoginAtAfter(UserType userType, Instant since);

    /**
     * T-ADMINMAIL-0903 audience count for the admin custom email send — {@code
     * AdminCustomEmailService} calls this both for {@code preview}'s {@code recipientCount} and
     * again, fresh, at {@code send} time for the control-#3 409 {@code RECIPIENT_COUNT_CHANGED}
     * mismatch check (must recompute with IDENTICAL predicates to {@link
     * #findForCustomEmailAudience}, or the two would drift out of sync for reasons that have
     * nothing to do with the audience actually changing).
     *
     * <p>{@code userType == null} means "ALL" (no type filter) — {@code users.user_type} carries
     * an unused {@code ADMIN} value (see {@code AdminUser} javadoc: admin operators live in the
     * separate {@code admin_users} table, never here), so an unfiltered scan only ever returns
     * CREATOR/BRAND rows in practice. Scoped to {@code status = ACTIVE} deliberately — a marketing
     * send has no business reaching a suspended or deactivated account.
     *
     * <p><b>[SEC: Vikram, A1 fix, REVIEW-R3.md ship-blocker]</b> {@code AND u.deletedAt IS NULL} —
     * {@link com.influora.domain.entity.User#softDelete()} blanks {@code email} but deliberately
     * leaves {@code status} untouched (V61 keeps FKs resolving), so a soft-deleted account is still
     * {@code status = ACTIVE}. Without this predicate, one deleted account anywhere in the ALL
     * audience was counted here, then returned by {@link #findForCustomEmailAudience} with a
     * {@code null} email, then hit {@code EmailOutbox.to_email VARCHAR(255) NOT NULL} at flush —
     * rolling back the ENTIRE send, every recipient, with no replay possible (no campaign row was
     * ever committed). Must stay predicate-identical with {@link #findForCustomEmailAudience} — see
     * that method's javadoc.
     */
    @Query(
            "SELECT COUNT(u) FROM User u WHERE u.status = com.influora.domain.enums.UserStatus.ACTIVE "
                    + "AND u.deletedAt IS NULL "
                    + "AND (:userType IS NULL OR u.userType = :userType) "
                    + "AND (:onlyVerified = false OR u.emailVerified = true) "
                    + "AND (:registeredAfter IS NULL OR u.createdAt >= :registeredAfter)")
    long countForCustomEmailAudience(
            @Param("userType") UserType userType,
            @Param("onlyVerified") boolean onlyVerified,
            @Param("registeredAfter") Instant registeredAfter);

    /**
     * T-ADMINMAIL-0903 audience rows for the admin custom email send — same predicates as {@link
     * #countForCustomEmailAudience}, see that method's javadoc. {@code limit} is the caller's
     * recipient cap (control #2): the send path never asks for more rows than it is willing to
     * enqueue.
     *
     * <p><b>[SEC: Vikram, A1 fix, REVIEW-R3.md ship-blocker]</b> {@code AND u.deletedAt IS NULL} —
     * see {@link #countForCustomEmailAudience}'s javadoc for why. Kept predicate-identical with
     * that method deliberately.
     */
    @Query(
            "SELECT u FROM User u WHERE u.status = com.influora.domain.enums.UserStatus.ACTIVE "
                    + "AND u.deletedAt IS NULL "
                    + "AND (:userType IS NULL OR u.userType = :userType) "
                    + "AND (:onlyVerified = false OR u.emailVerified = true) "
                    + "AND (:registeredAfter IS NULL OR u.createdAt >= :registeredAfter) "
                    + "ORDER BY u.createdAt")
    List<User> findForCustomEmailAudience(
            @Param("userType") UserType userType,
            @Param("onlyVerified") boolean onlyVerified,
            @Param("registeredAfter") Instant registeredAfter,
            Limit limit);
}
