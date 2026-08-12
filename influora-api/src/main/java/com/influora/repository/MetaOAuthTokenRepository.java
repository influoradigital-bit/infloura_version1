package com.influora.repository;

import com.influora.domain.entity.MetaOAuthToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MetaOAuthTokenRepository extends JpaRepository<MetaOAuthToken, String> {

    /**
     * Workspace-scoped lookup — every read path must confirm the token belongs to the caller's
     * workspace. Fail-closed by construction: {@code t.workspaceId IS NOT NULL} is an explicit
     * predicate here, NOT left implicit. This is deliberate — Spring Data JPA's method-name query
     * derivation (what this method used to be, a plain {@code findByWorkspaceIdAnd...} derived
     * query) resolves a {@code null} bound parameter for a {@code SIMPLE_PROPERTY} part into a SQL
     * {@code IS NULL} check instead of a literal {@code = NULL} comparison
     * (`ParameterMetadataProvider`/`PredicateBuilder`'s null-to-isNull rewrite). Since creator-owned
     * rows are exactly the ones with {@code workspace_id IS NULL} (see {@link MetaOAuthToken}'s
     * javadoc), a caller that ever passed {@code workspaceId == null} into the OLD derived-query
     * form of this method would have matched a creator-owned row through the brand-scoped method —
     * silently crossing the two key-spaces the class-level javadoc claims are disjoint. This
     * hand-written JPQL does not get that derived-query rewrite: {@code t.workspaceId = :workspaceId}
     * with a null-bound {@code :workspaceId} follows ordinary SQL three-valued-logic (never matches
     * anything), and the explicit {@code IS NOT NULL} makes that guarantee textually load-bearing
     * rather than an accident of which query-authoring style happened to be used (CR-111, Kabir
     * red-team: the prior javadoc's "disjoint by construction" claim was accurate for every
     * currently-shipped caller but not enforced by the query itself — see {@code
     * MetaOAuthTokenRepositoryNullWorkspaceIdTest} for the regression proof). Actual guarantee this
     * method now enforces: a {@code null} {@code workspaceId} argument can NEVER match any row,
     * brand-owned or creator-owned — it always returns {@link Optional#empty()}.
     */
    @Query(
            "SELECT t FROM MetaOAuthToken t WHERE t.workspaceId IS NOT NULL "
                    + "AND t.workspaceId = :workspaceId AND t.creatorProfileId = :creatorProfileId "
                    + "AND t.revoked = false")
    Optional<MetaOAuthToken> findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
            @Param("workspaceId") String workspaceId, @Param("creatorProfileId") String creatorProfileId);

    List<MetaOAuthToken> findByWorkspaceIdAndRevokedFalse(String workspaceId);

    /** For the background refresh sweep (spec §1.5) — not workspace-scoped by design, runs system-wide. */
    List<MetaOAuthToken> findByExpiresAtBeforeAndRevokedFalse(Instant threshold);

    /**
     * All non-revoked tokens not yet expired, for {@code MetricsPollingJob} (spec §3.1) — not
     * workspace-scoped by design, runs system-wide across every connected creator, same as the
     * refresh sweep above.
     */
    List<MetaOAuthToken> findByRevokedFalseAndExpiresAtAfter(Instant now);

    List<MetaOAuthToken> findByCreatorProfileIdAndRevokedFalse(String creatorProfileId);

    Optional<MetaOAuthToken> findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
            String creatorProfileId);

    /**
     * Creator-owned key-space (Creator AI Co-pilot Tier-1 OAuth flip, be-services-plan §3) —
     * {@code workspaceId IS NULL} is what distinguishes a creator-owned row from a brand-owned one;
     * this query never returns a brand row (those always have a non-null workspaceId). The
     * brand-scoped {@link #findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse} above never returns
     * a creator-owned row EITHER — as of CR-111 that method's JPQL carries an explicit {@code
     * workspaceId IS NOT NULL} predicate, so it stays fail-closed even if a future caller ever
     * passes {@code workspaceId == null} (previously only true by accident of every current
     * caller's behavior, not enforced by the query). The two key-spaces are disjoint both by
     * construction and by query-level enforcement (Kabir gate, IDOR threat-1: PASS; CR-111 hardened
     * the enforcement).
     */
    Optional<MetaOAuthToken> findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(
            String creatorProfileId);

    /**
     * All non-revoked, non-expired creator-owned tokens (workspace_id IS NULL) — system-wide sweep
     * for {@code CreatorCaptionSyncJob}, same "not workspace-scoped by design" convention as {@link
     * #findByRevokedFalseAndExpiresAtAfter(Instant)} above, restricted to the creator key-space only.
     */
    List<MetaOAuthToken> findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(Instant now);
}
