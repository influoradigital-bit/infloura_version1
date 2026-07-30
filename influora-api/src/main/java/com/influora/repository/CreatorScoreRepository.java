package com.influora.repository;

import com.influora.domain.entity.CreatorScore;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage-abstraction repository for computed creator scores (V22 {@code creator_scores}) — Phase
 * 3 Scoring Algorithms, written daily by {@code ScoreCalculationJob}.
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same
 * storage-seam intent as {@link CreatorMetricsRepository} — see its javadoc for the full
 * TimescaleDB-swap rationale. This is an ordinary MySQL JPA repository today.
 *
 * <p><b>Workspace isolation:</b> same discipline as {@link CreatorMetricsRepository} — {@code
 * creator_profiles} has no direct {@code workspace_id}, so every finder here is scoped only by
 * {@code creatorProfileId}. Before calling any finder below with a caller-supplied {@code
 * creatorProfileId} from a brand-facing request, callers MUST first call {@code
 * com.influora.service.MetricsAuthorizationService#resolveAuthorizedCreatorProfileId(String
 * workspaceId, String creatorProfileId)} and use only the id it returns. {@link
 * com.influora.job.ScoreCalculationJob} itself is exempt from this (system-wide scheduled job, not
 * a per-request caller — same reasoning as {@code MetricsPollingJob}).
 */
public interface CreatorScoreRepository extends JpaRepository<CreatorScore, String> {

    /** Most recent computed score for a creator (dashboard "current" tile). */
    Optional<CreatorScore> findFirstByCreatorProfileIdOrderByTimeDesc(String creatorProfileId);

    /**
     * BR-18 — batch greatest-n-per-group read: the single latest score row per creator, for a set
     * of creator ids, in one query. Required by any discovery/list endpoint that renders a page of
     * 20-100 creators ({@code CreatorDiscoveryService}) — calling {@link
     * #findFirstByCreatorProfileIdOrderByTimeDesc} per row in a {@code .map()} would be N+1 and is
     * an automatic QA reject per TECH-STACK.md "Score Exposure".
     *
     * <p>The {@code NOT EXISTS} anti-join is the standard MySQL greatest-n-per-group idiom: for
     * each candidate row, reject it if a newer row exists for the same creator. Both the outer
     * {@code WHERE creator_profile_id IN (...)} and the inner correlated subquery hit {@code
     * idx_creator_scores_creator_time (creator_profile_id, time)} — no full scan.
     */
    @Query(
            value =
                    "SELECT cs.* FROM creator_scores cs "
                            + "WHERE cs.creator_profile_id IN (:creatorProfileIds) "
                            + "AND NOT EXISTS ("
                            + "SELECT 1 FROM creator_scores newer "
                            + "WHERE newer.creator_profile_id = cs.creator_profile_id "
                            + "AND newer.time > cs.time)",
            nativeQuery = true)
    List<CreatorScore> findLatestByCreatorProfileIdIn(
            @Param("creatorProfileIds") Collection<String> creatorProfileIds);
}
