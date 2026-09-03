package com.influora.repository;

import com.influora.domain.entity.ExternalCreator;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalCreatorRepository
        extends JpaRepository<ExternalCreator, String>, JpaSpecificationExecutor<ExternalCreator> {

    /**
     * CR-111-style explicit {@code IS NOT NULL} guard (same discipline as {@code
     * MetaOAuthTokenRepository}): a blank/null argument must never mass-match every
     * not-yet-resolved row (most Business Discovery/admin-import rows have no {@code
     * ig_account_id} until enriched).
     */
    @Query(
            "SELECT e FROM ExternalCreator e WHERE e.igAccountId IS NOT NULL AND e.igAccountId ="
                    + " :igAccountId")
    Optional<ExternalCreator> findByIgAccountId(@Param("igAccountId") String igAccountId);

    /**
     * Q7.5 (Priya rejection, 2026-09-03) — this used to be a derived query method (bare {@code
     * findByIgUsernameIgnoreCase(String)}, no {@code @Query}), which Spring Data compiles to
     * {@code WHERE LOWER(e.igUsername) = LOWER(?1)}. MySQL cannot serve a function-wrapped column
     * from {@code uk_external_creators_username} (no matching functional/generated-column index
     * exists), so both of this method's live callers — {@code ExternalCreatorLinkService}'s JOINED
     * hook and {@code AdminCreatorConnectionService}'s bulk-import loop (up to 50 handles/request)
     * — forced a full table scan on every call. Both callers are OUTSIDE this fixer's file-ownership
     * for T-CREATORCONNECT-0902 pkg-lookup-ui-config, so they cannot be repointed at {@link
     * #findByIgUsername} here; instead this method's body is now IDENTICAL to that one (plain
     * equality, no {@code LOWER()}) — safe because both callers already normalize (trim + lower +
     * strip leading {@code @}) via their own {@code normalizeUsername} before calling this (see
     * {@code AdminCreatorConnectionService#normalizeUsername},
     * {@code ExternalCreatorLinkService#normalizeUsername}), and {@code ig_username}'s column
     * collation is already case-insensitive. This closes the index-defeating scan on both hot
     * paths without touching either caller. TODO(next fixer touching those two files): repoint
     * both call sites at {@link #findByIgUsername} and delete this method so the {@code LOWER()}-
     * wrapped derived form can never be reintroduced by accident.
     */
    @Query("SELECT e FROM ExternalCreator e WHERE e.igUsername = :igUsername")
    Optional<ExternalCreator> findByIgUsernameIgnoreCase(@Param("igUsername") String igUsername);

    /**
     * Q7.5 — {@code findByIgUsernameIgnoreCase} forces {@code LOWER(ig_username)} at query time,
     * which defeats {@code uk_external_creators_username}'s ability to serve the lookup as an
     * index seek (MySQL cannot use a plain b-tree index for a function-wrapped column without a
     * matching functional/generated-column index, which this schema does not have). Every writer
     * already lower-cases {@code ig_username} before it is ever persisted (see
     * ExternalCreatorService#normalizeUsername and AdminCreatorConnectionService#normalizeUsername),
     * and the column collation is case-insensitive, so a plain equality lookup on an
     * already-normalized argument returns the identical row without the {@code LOWER()} wrapper.
     * Callers MUST normalize (trim + lower-case, strip a leading {@code @}) before calling this.
     */
    Optional<ExternalCreator> findByIgUsername(String igUsername);

    /**
     * Q1.4 — rename reconciliation: when a Business Discovery/Marketplace row is matched by
     * {@code ig_account_id} but Meta now reports a different username (the creator renamed their
     * handle), the new username is written via a bulk update rather than through the
     * unique-constrained entity save that resolved the row in the first place — doing it through
     * {@code save()} would immediately re-trigger the same {@code uk_external_creators_username}
     * conflict the caller is trying to get out of. {@code clearAutomatically} evicts the
     * persistence context so a subsequent read by id sees the new value instead of the stale
     * cached entity.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ExternalCreator e SET e.igUsername = :igUsername, e.updatedAt = CURRENT_TIMESTAMP WHERE e.id = :id")
    int renameIgUsername(@Param("id") String id, @Param("igUsername") String igUsername);
}
