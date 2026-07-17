package com.influora.service;

import com.influora.common.ApiException;
import com.influora.repository.MetaOAuthTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization seam for {@code CreatorMetricsRepository} / {@code MediaMetricsRepository}
 * (Phase 2 metrics storage — see their javadoc).
 *
 * <p>[SEC: Kabir workspace-isolation review, SHARED_CONTEXT.md "KABIR -> ARJUN | Workspace-isolation
 * review: CreatorMetricsRepository/MediaMetricsRepository (Phase 2)"] Those two repositories are
 * scoped only by {@code creatorProfileId} — {@code creator_profiles} has no {@code workspace_id}
 * column, by ADR design, since a creator's metrics are creator-owned facts, not workspace-owned.
 * That means nothing at the repository/query level stops a caller from reading one workspace's
 * connected creator's private metrics with another workspace's {@code creatorProfileId}. Kabir's
 * finding: the javadoc on those repositories asserted "callers MUST verify authorization" but no
 * code anywhere actually did it, unlike {@code DeliverableMetricRepository}, whose only caller
 * ({@link DeliverableMetricService#getCampaignAnalytics}) enforces authorization for real —
 * {@code brandContext.requireMember(principal, workspaceId)} then a workspace-scoped {@code
 * campaignRepository.findByIdAndWorkspaceId(...)} lookup — before ever deriving an ID to hand to
 * the repository.
 *
 * <p>This service is the equivalent seam for creator metrics: it resolves the
 * workspace-to-creator relationship the same way Phase 1 already established it for Meta-connected
 * creators — an active (non-revoked) {@code meta_oauth_tokens} row is the DB-enforced proof that a
 * given workspace is entitled to see a given creator's metrics ({@code
 * V20__meta_oauth_tokens.sql}'s {@code uq_meta_oauth_workspace_creator} unique key on {@code
 * (workspace_id, creator_profile_id)} is exactly this pairing). Callers must call {@link
 * #resolveAuthorizedCreatorProfileId(String, String)} and use ONLY the ID it returns before calling
 * any finder on {@code CreatorMetricsRepository} or {@code MediaMetricsRepository} — never pass a
 * bare, caller-supplied {@code creatorProfileId} straight through.
 *
 * <p>Not wired into {@code MetricsPollingJob}: that job is a system-wide scheduled sweep over its
 * own token table ({@code MetaOAuthTokenRepository.findByRevokedFalseAndExpiresAtAfter}), not a
 * per-request caller acting on behalf of one workspace, so this per-request check doesn't apply to
 * it (see Kabir's review, Item #3).
 */
@Service
public class MetricsAuthorizationService {

    private final MetaOAuthTokenRepository metaOAuthTokenRepository;

    public MetricsAuthorizationService(MetaOAuthTokenRepository metaOAuthTokenRepository) {
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
    }

    /**
     * Verifies that {@code workspaceId} has an active (non-revoked) Meta OAuth connection to
     * {@code creatorProfileId}, and returns the creator profile id to use for the subsequent
     * {@code CreatorMetricsRepository}/{@code MediaMetricsRepository} finder call.
     *
     * <p>Mirrors the "resolve-then-scope" shape {@link
     * DeliverableMetricService#getCampaignAnalytics} uses for campaigns: never trust a
     * caller-supplied id directly, instead resolve it through a query that is inherently
     * workspace-scoped, and only pass along the id once that resolution has succeeded.
     *
     * @throws ApiException with code {@code FORBIDDEN} if no active token links this workspace to
     *     this creator (i.e. the workspace has never connected, or has since revoked, that
     *     creator's Meta account)
     */
    @Transactional(readOnly = true)
    public String resolveAuthorizedCreatorProfileId(String workspaceId, String creatorProfileId) {
        metaOAuthTokenRepository
                .findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, creatorProfileId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "FORBIDDEN",
                                        "This workspace is not authorized to view metrics for that creator",
                                        HttpStatus.FORBIDDEN));
        return creatorProfileId;
    }
}
