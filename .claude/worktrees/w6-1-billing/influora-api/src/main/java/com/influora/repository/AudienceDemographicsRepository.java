package com.influora.repository;

import com.influora.domain.entity.AudienceDemographics;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage-abstraction repository for {@code audience_demographics} snapshots (V25) — Wave B task
 * B4, written weekly by {@code AudienceDemographicsJob}.
 *
 * <p>[CTO RULING — wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md, LOCKED] Same
 * storage-seam intent as {@link CreatorScoreRepository} — see its javadoc for the full
 * TimescaleDB-swap rationale. This is an ordinary MySQL JPA repository today.
 *
 * <p><b>Workspace isolation:</b> same discipline as {@link CreatorMetricsRepository}/{@link
 * CreatorScoreRepository} — {@code creator_profiles} has no direct {@code workspace_id}, so the
 * finder below is scoped only by {@code creatorProfileId}. Before calling it with a
 * caller-supplied {@code creatorProfileId} from a brand-facing request, callers MUST first call
 * {@code com.influora.service.MetricsAuthorizationService#resolveAuthorizedCreatorProfileId(String
 * workspaceId, String creatorProfileId)} and use only the id it returns. {@code
 * AudienceDemographicsJob} itself is exempt from this (system-wide scheduled job, not a
 * per-request caller — same reasoning as {@code MetricsPollingJob}/{@code ScoreCalculationJob}).
 */
public interface AudienceDemographicsRepository extends JpaRepository<AudienceDemographics, String> {

    /** Most recent demographics snapshot for a creator (dashboard "current" tile). */
    Optional<AudienceDemographics> findFirstByCreatorProfileIdOrderByTimeDesc(String creatorProfileId);
}
