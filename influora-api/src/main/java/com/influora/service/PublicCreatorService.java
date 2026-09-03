package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.web.dto.creator.PublicCreatorDtos.VerifiedMetrics;
import com.influora.web.dto.creator.PublicCreatorDtos.VerifiedProfileResponse;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.8, A9) — the public, unauthenticated verified-metrics
 * snapshot. Deliberately narrow: NO rates, NO floors (those live behind {@code
 * CreatorAgentPreferencesRepository}, never touched here), NO PAN/GSTIN/tax identity. Only
 * discoverable + Meta-connected creators are served — see {@link #getVerifiedMetrics}.
 */
@Service
public class PublicCreatorService {

    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;
    private final CollaborationRepository collaborationRepository;

    public PublicCreatorService(
            CreatorProfileRepository creatorProfileRepository,
            CreatorMetricsRepository creatorMetricsRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository,
            CollaborationRepository collaborationRepository) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
        this.collaborationRepository = collaborationRepository;
    }

    @Transactional(readOnly = true)
    public VerifiedProfileResponse getVerifiedMetrics(String username) {
        CreatorProfile profile =
                creatorProfileRepository
                        .findByUsernameIgnoreCase(username)
                        .orElseThrow(
                                () -> new ApiException("CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));

        boolean metaConnected = !metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalse(profile.getId()).isEmpty();
        // Fix round 2, item 4 (Priya Q10) — profile.isSuspended() was never consulted here, so an
        // admin-suspended creator's public page kept serving live verified metrics with a plain
        // 200. Folded into the same 404 as "not discoverable"/"not connected" (never a distinct
        // status) so this route still can't be used to distinguish "suspended" from "never
        // existed" from "not Meta-connected" — same non-enumeration property the existing check
        // already has.
        if (!profile.isDiscoverable() || !metaConnected || profile.isSuspended()) {
            throw new ApiException("CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND);
        }

        Optional<CreatorMetric> latest =
                creatorMetricsRepository
                        .findByCreatorProfileIdOrderByTimeDesc(profile.getId(), PageRequest.of(0, 1))
                        .stream()
                        .findFirst();

        // A snapshot's own poll/fetch time stands in for "verified on" — CreatorProfile carries no
        // dedicated verifiedAt timestamp, only the boolean `verified` flag; the metric row IS the
        // platform-API evidence behind this snapshot.
        VerifiedMetrics metrics =
                latest
                        .map(
                                m ->
                                        new VerifiedMetrics(
                                                m.getFollowers(), m.getAvgReachPerPost(), m.getAvgEngagementRate(), m.getFetchedAt()))
                        .orElseGet(() -> new VerifiedMetrics(profile.getTotalFollowers(), null, profile.getEngagementRate(), null));

        long completedDeals =
                collaborationRepository.findByCreatorIdAndStatus(profile.getUserId(), CollaborationStatus.COMPLETED).size();

        return new VerifiedProfileResponse(
                profile.getUsername(),
                profile.getDisplayName(),
                profile.getCity(),
                JsonLists.stringListFromJson(profile.getCategoriesJson()),
                metrics,
                completedDeals,
                Instant.now());
    }
}
