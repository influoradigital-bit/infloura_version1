package com.influora.repository;

import com.influora.domain.entity.EscrowHold;
import com.influora.domain.enums.EscrowStatus;
import java.math.BigDecimal;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscrowHoldRepository extends JpaRepository<EscrowHold, String> {

    /**
     * Platform-wide sum of hold amounts in the given statuses. {@code COALESCE(...,0)} guarantees
     * a non-null zero pre-launch. Powers AdminDashboardController's CEO Pulse: {@code
     * escrowFloat} uses {@code [FUNDED]} (money currently locked); {@code gmv} uses
     * {@code [FUNDED, RELEASED]} as an interim "total committed campaign spend" proxy pending
     * Rohan's official GMV formula (TASK_ASSIGNMENTS.md — Rohan owns "Validate revenue dashboard"
     * / "TDS report requirements").
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM EscrowHold e WHERE e.status IN :statuses")
    BigDecimal sumAmountByStatusIn(@Param("statuses") Collection<EscrowStatus> statuses);

    /**
     * Workspace-scoped derive-on-read for the brand dashboard's {@code escrowLocked} figure
     * ({@code WalletService.getSummary} -> {@code WalletSummaryResponse.escrowLocked}).
     * {@code wallets.escrow_balance} is a dead column (never written by any service — funding an
     * escrow hold only ever moves {@code wallets.balance} via {@code WalletLedgerService.post()},
     * see {@code EscrowHold}'s class javadoc), so "locked" money is derived here as the live sum of
     * the workspace's holds currently in {@code FUNDED} status — money moved into escrow but not
     * yet {@code RELEASED}/{@code REFUNDED}. {@code COALESCE(...,0)} guarantees a non-null zero for
     * a workspace with no funded holds, matching {@link #sumAmountByStatusIn}'s convention.
     */
    @Query(
            "SELECT COALESCE(SUM(e.amount), 0) FROM EscrowHold e "
                    + "WHERE e.workspaceId = :workspaceId AND e.status = :status")
    BigDecimal sumAmountByWorkspaceIdAndStatus(
            @Param("workspaceId") String workspaceId, @Param("status") EscrowStatus status);

    /**
     * Campaign-scoped SUM of hold amounts strictly in one status — Phase 2 item 2.1/2.2's SR-1
     * ground-truth query (Meera: Label-to-Moat build plan §2.1). Callers computing "spend"/"funded"
     * for the outcome digest or {@code get_campaign_performance} MUST pass {@link
     * EscrowStatus#RELEASED} here — never the {@code FUNDED_STATUSES} campaign-status proxy {@code
     * MeeraContextService} uses for the unrelated Phase-1 {@code past_campaign_summary} field.
     * {@code COALESCE(...,0)} matches this repository's existing zero-for-no-rows convention (see
     * {@link #sumAmountByStatusIn}/{@link #sumAmountByWorkspaceIdAndStatus}).
     */
    @Query(
            "SELECT COALESCE(SUM(e.amount), 0) FROM EscrowHold e "
                    + "WHERE e.campaignId = :campaignId AND e.status = :status")
    BigDecimal sumAmountByCampaignIdAndStatus(
            @Param("campaignId") String campaignId, @Param("status") EscrowStatus status);

    Optional<EscrowHold> findByIdempotencyKey(String idempotencyKey);

    Optional<EscrowHold> findByIdAndWorkspaceId(String id, String workspaceId);

    /**
     * Creator-scoped escrow read — only holds bound to the creator's own collaborations are
     * visible (campaign-scoped holds with no {@code collaboration_id} yet are excluded).
     */
    @Query(
            "SELECT e FROM EscrowHold e WHERE e.id = :id AND e.collaborationId IN "
                    + "(SELECT co.id FROM Collaboration co WHERE co.creatorId = :creatorUserId)")
    Optional<EscrowHold> findByIdAndCreatorId(
            @Param("id") String id, @Param("creatorUserId") String creatorUserId);

    List<EscrowHold> findByWorkspaceIdAndStatus(String workspaceId, EscrowStatus status);

    /** Brand-scoped, paginated escrow hold list — GET /wallet/escrow (Vikram, N4). */
    Page<EscrowHold> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId, Pageable pageable);

    List<EscrowHold> findByCampaignId(String campaignId);

    List<EscrowHold> findByCampaignIdIn(List<String> campaignIds);

    List<EscrowHold> findByMilestoneId(String milestoneId);

    boolean existsByCollaborationIdAndStatus(String collaborationId, EscrowStatus status);

    boolean existsByCollaborationIdAndStatusIn(
            String collaborationId, Collection<EscrowStatus> statuses);

    /**
     * Does this collaboration have ANY hold in the given statuses, reached by EITHER linkage?
     *
     * <p>[SEC: CR-35 follow-on] {@link #existsByCollaborationIdAndStatusIn} above matches only the
     * direct {@code collaboration_id} column — which is NULL on every hold created by the ordinary
     * brand escrow flow, because only {@code ConfirmLaunchExecutor} ever called
     * {@code bindCollaboration}. Any caller using it as a safety guard therefore silently concluded
     * "no escrow" for exactly the holds that matter. {@code DeliverableCleanupJob.canDelete} was
     * such a caller, and it deletes creator media.
     *
     * <p>This query adds the milestone linkage as a second path, the same union
     * {@code EscrowService.resolveHoldsForCollaboration} performs in Java, so the answer no longer
     * depends on whether the denormalised column happens to be populated. CR-35's backfill and its
     * bind-at-creation fix both narrow how often the column is null; **this makes the guard correct
     * even when it still is** — including campaign-level funding, which has no milestone to bind
     * from and is therefore permanently null by design.
     *
     * <p>Prefer this over the derived query for anything that gates a destructive or money-moving
     * action. The derived one is fine for callers that genuinely mean "is the column set".
     */
    @Query(
            "SELECT CASE WHEN COUNT(e) > 0 THEN TRUE ELSE FALSE END FROM EscrowHold e "
                    + "WHERE e.status IN :statuses AND ("
                    + "  e.collaborationId = :collaborationId "
                    + "  OR e.milestoneId IN ("
                    + "       SELECT m.id FROM PaymentMilestone m WHERE m.collaborationId = :collaborationId"
                    + "  )"
                    + ")")
    boolean existsForCollaborationIncludingMilestoneLink(
            @Param("collaborationId") String collaborationId,
            @Param("statuses") Collection<EscrowStatus> statuses);

    List<EscrowHold> findByCollaborationIdAndStatus(String collaborationId, EscrowStatus status);

    /** Row lock for FUNDED → {FROZEN|RELEASED|REFUNDED} transitions (H-T34-1). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EscrowHold e WHERE e.id = :id")
    Optional<EscrowHold> findByIdForUpdate(@Param("id") String id);
}
