package com.influora.repository;

import com.influora.domain.entity.CreatorScore;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
