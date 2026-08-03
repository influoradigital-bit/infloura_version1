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

    /**
     * Does this collaboration have ANY hold in the given statuses, reached by EITHER linkage?
     *
     * <p>[SEC: CR-35 follow-on, consolidated CR-50] Before CR-50 this repository exposed THREE
     * overlapping exists-methods answering the same question: two derived queries
     * ({@code existsByCollaborationIdAndStatus} / {@code existsByCollaborationIdAndStatusIn})
     * matching ONLY the direct {@code collaboration_id} column — which is NULL on every hold
     * created by the ordinary brand escrow flow, because only {@code ConfirmLaunchExecutor} ever
     * called {@code bindCollaboration} — and this one. Any caller using a direct-column method as a
     * safety guard therefore silently concluded "no escrow" for exactly the holds that matter.
     * {@code DeliverableCleanupJob.canDelete} was such a caller (CR-35), and it deletes creator
     * media; {@code ContractService#promptEscrowFundingIfNeeded} and {@code DealService}'s
     * deal-response escrow flag were two more (CR-49). CR-50 deleted both derived methods outright
     * — there is now exactly ONE collaboration-scoped escrow-existence query in this repository, so
     * a future caller cannot pick the wrong one by name; the drift class itself is gone, not just
     * documented.
     *
     * <p>This query adds the milestone linkage as a second path, the same union
     * {@code EscrowService.resolveHoldsForCollaboration} performs in Java, so the answer no longer
     * depends on whether the denormalised column happens to be populated. CR-35's backfill and its
     * bind-at-creation fix both narrow how often the column is null; **this makes the guard correct
     * even when it still is** — including campaign-level funding, which has no milestone to bind
     * from and is therefore permanently null by design.
     *
     * <p><b>Known limitation carried forward, not introduced by CR-50:</b> this repository has no
     * {@code @DataJpaTest}/real-database test harness (see the same disclaimer on
     * {@code CreatorMetricsRepositoryTest}, {@code MeeraInteractionLogRepositoryQueryTest} et al.),
     * so the milestone-linkage JPQL above is exercised by production traffic and by
     * {@code DeliverableCleanupJobTest}'s Mockito-stubbed behavioral tests, never by an execution of
     * the actual query against a real schema. A regression to this method's OWN query body (as
     * opposed to a caller picking a different, wrong method — now structurally impossible, see
     * above) would not be caught by any test in this module today.
     */
    @Query(
            "SELECT CASE WHEN COUNT(e) > 0 THEN TRUE ELSE FALSE END FROM EscrowHold e "
                    + "WHERE e.status IN :statuses AND ("
                    + "  e.collaborationId = :collaborationId "
                    + "  OR e.milestoneId IN ("
                    + "       SELECT m.id FROM PaymentMilestone m WHERE m.collaborationId = :collaborationId"
                    + "  )"
                    + ")")
    boolean hasEscrowForCollaboration(
            @Param("collaborationId") String collaborationId,
            @Param("statuses") Collection<EscrowStatus> statuses);

    List<EscrowHold> findByCollaborationIdAndStatus(String collaborationId, EscrowStatus status);

    /** Row lock for FUNDED → {FROZEN|RELEASED|REFUNDED} transitions (H-T34-1). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EscrowHold e WHERE e.id = :id")
    Optional<EscrowHold> findByIdForUpdate(@Param("id") String id);

    /**
     * Platform-wide COUNT of holds in one status — powers the admin Finance/Escrow summary
     * ({@code GET /admin/finance/escrow}): {@code pendingRelease} passes {@link EscrowStatus#FUNDED}
     * (holds awaiting release), {@code flaggedTransactions} passes {@link EscrowStatus#FROZEN} (the
     * held-for-review state — {@code EscrowStatus} has no {@code FLAGGED} value). Derived query;
     * {@code status} is {@code @Enumerated(STRING)}.
     */
    long countByStatus(EscrowStatus status);

    /**
     * AVERAGE release latency in SECONDS across {@code RELEASED} holds — {@code AVG(released_at −
     * funded_at)} — for the admin escrow summary's {@code averageReleaseTime} (the service divides
     * by 3600 to hours). Returns {@code null} when no hold has been released yet (SQL {@code AVG} of
     * an empty set), which the service maps to 0. Native ({@code TIMESTAMPDIFF}, MySQL — the app's
     * dialect); filters {@code RELEASED} only so REFUNDED holds (which also set {@code released_at})
     * do not skew the figure.
     */
    @Query(
            value = "SELECT AVG(TIMESTAMPDIFF(SECOND, funded_at, released_at)) FROM escrow_holds "
                    + "WHERE status = 'RELEASED' AND funded_at IS NOT NULL AND released_at IS NOT NULL",
            nativeQuery = true)
    Double avgReleaseSeconds();
}
