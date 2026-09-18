package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import java.util.EnumSet;
import java.util.Set;
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
 * <p>This service is the equivalent seam for creator metrics. Callers must call {@link
 * #resolveAuthorizedCreatorProfileId(String, String)} and use ONLY the ID it returns before calling
 * any finder on {@code CreatorMetricsRepository} or {@code MediaMetricsRepository} — never pass a
 * bare, caller-supplied {@code creatorProfileId} straight through. A workspace is entitled to a
 * creator's metrics through either of two relationships:
 *
 * <ol>
 *   <li>an active (non-revoked) {@code meta_oauth_tokens} row pairing the workspace with the
 *       creator ({@code V20__meta_oauth_tokens.sql}'s {@code uq_meta_oauth_workspace_creator}
 *       unique key on {@code (workspace_id, creator_profile_id)} is exactly this pairing); or
 *   <li>a collaboration with that creator that the CREATOR agreed to: the creator applied ({@code
 *       source = APPLICATION}) and it is not {@code CANCELLED}, or the terms were agreed ({@code
 *       TERMS_AGREED} or any later state, including {@code DISPUTED}).
 * </ol>
 *
 * <p>The second grant exists because the first alone could never open for a marketplace creator:
 * {@code MetaTokenStorage.storeCreatorToken} writes a creator's own connection with {@code
 * workspace_id} NULL, so no brand workspace ever held a row for them and every brand analytics read
 * 403'd. It follows the LOCKED ruling 2026-09-15-brand-preconsent-visibility (full detail once the
 * creator has agreed to work with the brand). A brand's own invitation or offer the creator has not
 * agreed to ({@code INVITED}, or an INVITATION still {@code SHORTLISTED}/{@code IN_NEGOTIATION})
 * and anything {@code CANCELLED} grant nothing — sending an invite must not unlock a creator's
 * private metrics.
 *
 * <p>Not wired into {@code MetricsPollingJob}: that job is a system-wide scheduled sweep over its
 * own token table ({@code MetaOAuthTokenRepository.findByRevokedFalseAndExpiresAtAfter}), not a
 * per-request caller acting on behalf of one workspace, so this per-request check doesn't apply to
 * it (see Kabir's review, Item #3).
 */
@Service
public class MetricsAuthorizationService {

    /** States that exist only after the creator accepted the terms. CANCELLED is deliberately absent. */
    static final Set<CollaborationStatus> AGREED_STATUSES =
            EnumSet.of(
                    CollaborationStatus.TERMS_AGREED,
                    CollaborationStatus.CONTRACT_PENDING,
                    CollaborationStatus.CONTRACTED,
                    CollaborationStatus.IN_PROGRESS,
                    CollaborationStatus.REVIEW_PENDING,
                    CollaborationStatus.REVISION_REQUESTED,
                    CollaborationStatus.COMPLETED,
                    CollaborationStatus.DISPUTED);

    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CollaborationRepository collaborationRepository;

    public MetricsAuthorizationService(
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            CreatorProfileRepository creatorProfileRepository,
            CollaborationRepository collaborationRepository) {
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.collaborationRepository = collaborationRepository;
    }

    /**
     * Verifies that {@code workspaceId} may read {@code creatorProfileId}'s metrics, and returns the
     * creator profile id to use for the subsequent {@code CreatorMetricsRepository}/{@code
     * MediaMetricsRepository} finder call.
     *
     * <p>Mirrors the "resolve-then-scope" shape {@link
     * DeliverableMetricService#getCampaignAnalytics} uses for campaigns: never trust a
     * caller-supplied id directly, instead resolve it through a query that is inherently
     * workspace-scoped, and only pass along the id once that resolution has succeeded.
     *
     * @throws ApiException with code {@code FORBIDDEN} if the workspace has neither an active Meta
     *     pairing with this creator nor a collaboration the creator agreed to
     */
    @Transactional(readOnly = true)
    public String resolveAuthorizedCreatorProfileId(String workspaceId, String creatorProfileId) {
        if (metaOAuthTokenRepository
                        .findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(workspaceId, creatorProfileId)
                        .isPresent()
                || hasCreatorAgreedCollaboration(workspaceId, creatorProfileId)) {
            return creatorProfileId;
        }
        throw new ApiException(
                "FORBIDDEN",
                "This workspace is not authorized to view metrics for that creator",
                HttpStatus.FORBIDDEN);
    }

    private boolean hasCreatorAgreedCollaboration(String workspaceId, String creatorProfileId) {
        if (workspaceId == null || creatorProfileId == null) {
            return false;
        }
        return creatorProfileRepository
                .findById(creatorProfileId)
                .map(CreatorProfile::getUserId)
                .map(
                        creatorUserId ->
                                collaborationRepository
                                        .findByWorkspaceIdAndCreatorId(workspaceId, creatorUserId)
                                        .stream()
                                        .anyMatch(MetricsAuthorizationService::creatorAgreed))
                .orElse(false);
    }

    static boolean creatorAgreed(Collaboration collaboration) {
        CollaborationStatus status = collaboration.getStatus();
        if (status == null || status == CollaborationStatus.CANCELLED) {
            return false;
        }
        return collaboration.getSource() == CollaborationSource.APPLICATION
                || AGREED_STATUSES.contains(status);
    }
}
