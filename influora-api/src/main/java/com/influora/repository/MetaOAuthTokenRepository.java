package com.influora.repository;

import com.influora.domain.entity.MetaOAuthToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaOAuthTokenRepository extends JpaRepository<MetaOAuthToken, String> {

    /** Workspace-scoped lookup — every read path must confirm the token belongs to the caller's workspace. */
    Optional<MetaOAuthToken> findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
            String workspaceId, String creatorProfileId);

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
     * this query never returns a brand row (those always have a non-null workspaceId), and the
     * brand-scoped {@link #findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse} above never returns
     * a creator-owned row. The two key-spaces are disjoint by construction (Kabir gate, IDOR
     * threat-1: PASS).
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
