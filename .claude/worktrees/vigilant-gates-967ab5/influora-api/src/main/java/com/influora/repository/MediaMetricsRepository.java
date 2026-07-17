package com.influora.repository;

import com.influora.domain.entity.MediaMetric;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage-abstraction repository for per-post metrics (V21 {@code media_metrics}).
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same
 * storage-seam intent as {@link CreatorMetricsRepository} — see its javadoc for the full
 * TimescaleDB-swap rationale. This is an ordinary MySQL JPA repository today.
 *
 * <p><b>Workspace isolation:</b> same discipline as {@link CreatorMetricsRepository} — {@code
 * creator_profiles} has no direct {@code workspace_id}, so every finder here is scoped only by
 * {@code creatorProfileId} / {@code mediaId}. Before calling ANY finder below with a
 * caller-supplied {@code creatorProfileId}, callers MUST first call {@code
 * com.influora.service.MetricsAuthorizationService#resolveAuthorizedCreatorProfileId(String
 * workspaceId, String creatorProfileId)} and use only the id it returns — see {@link
 * CreatorMetricsRepository}'s javadoc for the full rationale and precedent this mirrors.
 */
public interface MediaMetricsRepository extends JpaRepository<MediaMetric, String> {

    /** Most recent snapshot for a specific media item (a post can be polled multiple times). */
    Optional<MediaMetric> findFirstByMediaIdOrderByTimeDesc(String mediaId);

    /** Recent per-post metrics for a creator, newest first — used by scoring jobs (spec §4). */
    List<MediaMetric> findByCreatorProfileIdOrderByTimeDesc(String creatorProfileId, Pageable pageable);

    /** Recent per-post metrics for a creator on one platform, newest first. */
    List<MediaMetric> findByCreatorProfileIdAndPlatformOrderByTimeDesc(
            String creatorProfileId, String platform, Pageable pageable);

    /** Time-range query for a creator's media metrics (trend charts / analytics API). */
    List<MediaMetric> findByCreatorProfileIdAndTimeBetweenOrderByTimeAsc(
            String creatorProfileId, Instant from, Instant to);

    /** All poll snapshots recorded for one media item, oldest first (per-post history). */
    List<MediaMetric> findByMediaIdOrderByTimeAsc(String mediaId);
}
