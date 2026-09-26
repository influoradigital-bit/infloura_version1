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

    /**
     * The same reads, narrowed to ONE connected Instagram account. A creator profile can connect a
     * different account later (seen live: three accounts under one profile), and the unnarrowed
     * methods above mix them, so Meera's posts, her quality score, the posting pattern and the
     * brand-facing performance panel were all built from several accounts at once.
     *
     * <p>A row whose {@code igAccountId} is NULL predates V20260924120000 and cannot be attributed
     * to an account, so it is INCLUDED rather than hidden — otherwise every creator would lose
     * their history the moment this shipped, to fix a problem only account-switchers have. Those
     * rows are cleaned up per creator, separately.
     */
    @Query(
            "select m from CreatorMetric m where m.creatorProfileId = :creatorProfileId"
                    + " and (m.igAccountId is null or m.igAccountId = :igAccountId)"
                    + " order by m.time desc")
    List<CreatorMetric> findForAccountOrderByTimeDesc(
            @Param("creatorProfileId") String creatorProfileId,
            @Param("igAccountId") String igAccountId,
            Pageable pageable);

    /**
     * {@link #findForAccountOrderByTimeDesc} narrowed to ONE data source IN THE QUERY (the F-0961
     * rule): Meera's connected-account username reads only Meta-synced rows, and filtering after
     * the LIMIT would let a burst of newer creator-reported rows push every Meta row off the page.
     */
    @Query(
            "select m from CreatorMetric m where m.creatorProfileId = :creatorProfileId"
                    + " and m.dataSource = :dataSource"
                    + " and (m.igAccountId is null or m.igAccountId = :igAccountId)"
                    + " order by m.time desc")
    List<CreatorMetric> findForAccountAndDataSourceOrderByTimeDesc(
            @Param("creatorProfileId") String creatorProfileId,
            @Param("igAccountId") String igAccountId,
            @Param("dataSource") String dataSource,
            Pageable pageable);

    /**
     * F-0961 — newest rows of ONE data source. Filtering in the query (not after a LIMIT) means a
     * burst of newer CREATOR_REPORTED rows can never push every Meta-synced row out of the page.
     */
    List<CreatorMetric> findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
            String creatorProfileId, String dataSource, Pageable pageable);

    /** F-0965 — newest row of one platform AND one data source (e.g. Meta-synced Instagram). */
    Optional<CreatorMetric> findFirstByCreatorProfileIdAndPlatformAndDataSourceOrderByTimeDesc(
            String creatorProfileId, String platform, String dataSource);

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
