package com.influora.repository;

import com.influora.domain.entity.CreatorMetric;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage-abstraction repository for creator-level metrics (V21 {@code creator_metrics}).
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] This
 * interface is the seam the ADR requires: today it is a plain Spring Data JPA repository over a
 * MySQL InnoDB table, but every metrics read/write in the app goes through this interface (never
 * direct SQL/JPQL elsewhere) so a TimescaleDB-backed implementation can be swapped in later with no
 * service-layer changes.
 *
 * <p><b>Workspace isolation:</b> {@code creator_profiles} (like {@code meta_oauth_tokens}) has no
 * direct {@code workspace_id} — a creator's metrics are creator-owned facts, not workspace-owned.
 * Every finder here is scoped only by {@code creatorProfileId}, so before calling ANY finder below
 * with a caller-supplied {@code creatorProfileId}, callers MUST first call {@code
 * com.influora.service.MetricsAuthorizationService#resolveAuthorizedCreatorProfileId(String
 * workspaceId, String creatorProfileId)} and use only the id it returns. That method is the actual
 * enforcement — it checks for an active (non-revoked) {@code meta_oauth_tokens} row linking the
 * workspace to the creator — mirroring how {@code DeliverableMetricService.getCampaignAnalytics}
 * resolves a workspace-scoped {@code Campaign} before ever deriving IDs to pass into {@code
 * DeliverableMetricRepository}. No finder in this interface may be called with a bare
 * caller-supplied {@code creatorProfileId} that hasn't passed through that helper first.
 */
public interface CreatorMetricsRepository extends JpaRepository<CreatorMetric, String> {

    /** Most recent metric row for a creator on a given platform (dashboard "current" tile). */
    Optional<CreatorMetric> findFirstByCreatorProfileIdAndPlatformOrderByTimeDesc(
            String creatorProfileId, String platform);

    /** Most recent metric row for a creator across all platforms it has been polled on. */
    List<CreatorMetric> findByCreatorProfileIdOrderByTimeDesc(String creatorProfileId, Pageable pageable);

    /** Time-range query for a single creator/platform (trend charts). */
    List<CreatorMetric> findByCreatorProfileIdAndPlatformAndTimeBetweenOrderByTimeAsc(
            String creatorProfileId, String platform, Instant from, Instant to);

    /** Time-range query across all platforms for a creator (e.g. for scoring jobs). */
    List<CreatorMetric> findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(
            String creatorProfileId, Instant from, Instant to);

    /**
     * Latest metric row per (creator, platform) across a batch of creators — used by the polling
     * job / dashboards to avoid N+1 lookups. Plain JPQL correlated subquery; revisit if/when this
     * needs to run against a TimescaleDB continuous aggregate instead.
     */
    @Query(
            "SELECT cm FROM CreatorMetric cm WHERE cm.creatorProfileId IN :creatorProfileIds "
                    + "AND cm.time = (SELECT MAX(cm2.time) FROM CreatorMetric cm2 "
                    + "WHERE cm2.creatorProfileId = cm.creatorProfileId AND cm2.platform = cm.platform)")
    List<CreatorMetric> findLatestPerCreatorAndPlatform(
            @Param("creatorProfileIds") List<String> creatorProfileIds);
}
