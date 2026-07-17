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
}
