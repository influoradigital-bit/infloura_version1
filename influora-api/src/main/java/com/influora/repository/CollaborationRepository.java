package com.influora.repository;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollaborationRepository extends JpaRepository<Collaboration, String> {

    /**
     * [SEC: Kabir MEDIUM-1, contract-flow-architecture-2026-07-23 review — race-close for BE-1]
     * Row lock used by {@code ContractService#generate} to serialize concurrent
     * {@code POST /contracts} calls for the SAME collaboration. Without this, the existing
     * {@code existsByCollaborationIdAndStatusNot} SELECT then {@code save} INSERT has no DB-level
     * guarantee — two concurrent creates can both pass the exists-check before either commits and
     * both insert, producing two non-CANCELLED contracts for one collaboration. Same
     * {@code PESSIMISTIC_WRITE} pattern already used for {@code EscrowHold} row locks
     * ({@code EscrowHoldRepository#findByIdForUpdate}, H-T34-1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Collaboration c WHERE c.id = :id")
    Optional<Collaboration> findByIdForUpdate(@Param("id") String id);

    /**
     * One row of the {@code niche_rate_band} candidate pool (Phase 2 item 2.1) — a
     * platform-wide, cross-tenant projection over real {@code agreed_rate} data, never widened
     * beyond these scalar columns. {@link com.influora.service.meera.BrandContextAssembler}
     * aggregates this into a single min/median/max {@code RateBand} behind the k-anonymity floor
     * (n&gt;=5 on both {@code creatorId} and {@code workspaceId}); no individual row from this
     * projection is ever serialized into a response.
     */
    interface RateBandCandidateRow {
        BigDecimal getAgreedRate();

        String getCurrency();

        String getWorkspaceId();

        String getCreatorId();

        Long getTotalFollowers();
    }

    /**
     * Real, completed-collaboration rate data for one niche — the ground truth behind {@code
     * niche_rate_band} (Phase 2 item 2.1). {@code status = 'COMPLETED'} only (not "&gt;=
     * COMPLETED" — {@link CollaborationStatus} has no ordinal ordering; {@code DISPUTED}/{@code
     * CANCELLED} rows are excluded so a disputed rate never counts as a clean market signal).
     * {@code JSON_CONTAINS} requires MySQL 8.0.17+, already assumed elsewhere in this schema (see
     * {@code V20260721140000__creator_nudge_log.sql}'s own MySQL-8.0.13+ note). Callers MUST
     * aggregate this into a single {@code RateBand} behind the k-anonymity floor before any of it
     * reaches a response — no row from this query may be serialized directly (Kabir's mandatory
     * Phase-2 gate).
     */
    @Query(
            value =
                    "SELECT co.agreed_rate AS agreedRate, co.currency AS currency, "
                            + "ca.workspace_id AS workspaceId, co.creator_id AS creatorId, "
                            + "cp.total_followers AS totalFollowers "
                            + "FROM collaborations co "
                            + "JOIN campaigns ca ON ca.id = co.campaign_id "
                            + "JOIN creator_profiles cp ON cp.user_id = co.creator_id "
                            + "WHERE co.status = 'COMPLETED' "
                            + "AND co.agreed_rate IS NOT NULL "
                            + "AND JSON_CONTAINS(cp.categories, JSON_QUOTE(:niche))",
            nativeQuery = true)
    List<RateBandCandidateRow> findRateBandCandidates(@Param("niche") String niche);

    boolean existsByCampaignIdAndCreatorId(String campaignId, String creatorId);

    Optional<Collaboration> findByCampaignIdAndCreatorId(String campaignId, String creatorId);

    List<Collaboration> findByCampaignId(String campaignId);

    /**
     * Pre-existing gap fix (Vikram, D14 verification pass, 2026-07-15) — unrelated to D14
     * invoicing, but {@code AdminCampaignService} (Wave 4 admin-panel work) already called this
     * exact method without it existing, breaking `mvn compile` for the whole module. Batched
     * lookup for a page of campaign ids, mirrors {@code EscrowHoldRepository.findByCampaignIdIn}.
     */
    List<Collaboration> findByCampaignIdIn(List<String> campaignIds);

    /**
     * All collaborations belonging to a workspace, resolved through the campaign each collaboration
     * hangs off (collaborations carry no {@code workspace_id} of their own — the trust boundary is
     * {@code campaign.workspace_id}). Powers the brand dashboard pipeline/actions aggregations.
     */
    @Query(
            "SELECT c FROM Collaboration c WHERE c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId)")
    List<Collaboration> findByWorkspaceId(@Param("workspaceId") String workspaceId);

    List<Collaboration> findByCreatorId(String creatorId);

    List<Collaboration> findByCreatorIdAndStatus(String creatorId, CollaborationStatus status);

    /**
     * CR-80 — real WHERE NOT IN for the creator's dispute-eligible-deal dropdown. Previously the
     * client called {@code GET /deals?status=all} (which loads via {@link #findByCreatorId}, i.e.
     * the creator's entire deal history) and filtered out DISPUTED/COMPLETED/CANCELLED rows in
     * JS. This pushes that exclusion to SQL so terminal-state deals never leave the database, let
     * alone reach {@code DealService#toDealResponse}'s per-row enrichment (campaign/contract/
     * escrow/deliverable lookups). See {@code DealService#listEligibleForDispute}.
     */
    List<Collaboration> findByCreatorIdAndStatusNotIn(
            String creatorId, Collection<CollaborationStatus> statuses);

    /**
     * "My Applications" page (my-applications-plan-2026-07-24.md) — source of truth is {@code
     * source = APPLICATION}, never the loose {@code applicationStatus} mapping used by the browse
     * path. {@code source} never mutates after creation, so this still returns a row after it
     * progresses into a deal (SHORTLISTED/CONTRACTED/etc). Scoped by {@code creatorId} at the SQL
     * level (derived query, not an in-memory filter over a broader finder) — Kabir R1.
     */
    List<Collaboration> findByCreatorIdAndSource(String creatorId, CollaborationSource source);

    /** Batch lookup powering the creator campaign browse list's per-row applicationStatus. */
    List<Collaboration> findByCreatorIdAndCampaignIdIn(String creatorId, List<String> campaignIds);

    Optional<Collaboration> findByIdAndCreatorId(String id, String creatorId);

    /**
     * CR-51 — batched ownership check for {@code CreatorDeliverableService#listForCollaborations}.
     * One IN-clause query replacing what was previously N sequential {@code
     * findByIdAndCreatorId} calls (one per deal) from the creator dashboard's pending-deliverable
     * rollup. Mirrors the existing {@code findByCreatorIdAndCampaignIdIn} batch-lookup pattern
     * already used elsewhere in this repository.
     */
    List<Collaboration> findByCreatorIdAndIdIn(String creatorId, List<String> ids);

    @Query(
            "SELECT c FROM Collaboration c WHERE c.id = :id AND c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId)")
    Optional<Collaboration> findByIdAndWorkspaceId(
            @Param("id") String id, @Param("workspaceId") String workspaceId);

    /**
     * [Priya §6h] {@code collaborations.creator_id} is an FK to {@code users.id}, matching every
     * other finder in this repository. {@code PortfolioService} currently calls these with a
     * {@code creator_profiles.id} instead — that is a call-site bug (returns 0 silently), not a
     * signature problem; flagged for follow-up, not fixed here.
     */
    long countByCreatorIdAndStatus(String creatorId, CollaborationStatus status);

    long countByCreatorId(String creatorId);

    /**
     * One row per (campaignId, status) pair actually present among {@code campaignIds} — the
     * batched counterpart to {@link #findByCampaignId} used by {@code CampaignService.list()} to
     * populate {@code CampaignMapper.CampaignMetrics} for a whole page of campaigns without an
     * N+1 query per row (D-5, BrandF.md Section 19/20 — every campaign card previously showed
     * "0/N creators" because {@code CampaignMetrics.empty()} was the only value ever produced).
     * A campaign/status combination with zero collaborations produces no row; callers MUST treat
     * a missing combination as zero, the same convention {@code EscrowHoldRepository}'s SUM
     * queries use (there {@code COALESCE} does it in SQL; here there is nothing to coalesce since
     * {@code COUNT} rows only exist for pairs that occurred, so the zero-default is the caller's
     * responsibility instead).
     */
    interface CampaignStatusCount {
        String getCampaignId();

        CollaborationStatus getStatus();

        long getTotal();
    }

    @Query(
            "SELECT c.campaignId AS campaignId, c.status AS status, COUNT(c) AS total "
                    + "FROM Collaboration c WHERE c.campaignId IN :campaignIds "
                    + "GROUP BY c.campaignId, c.status")
    List<CampaignStatusCount> countByCampaignIdInGroupByStatus(
            @Param("campaignIds") List<String> campaignIds);
}
