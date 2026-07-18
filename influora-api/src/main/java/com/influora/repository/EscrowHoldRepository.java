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

    List<EscrowHold> findByCollaborationIdAndStatus(String collaborationId, EscrowStatus status);

    /** Row lock for FUNDED → {FROZEN|RELEASED|REFUNDED} transitions (H-T34-1). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EscrowHold e WHERE e.id = :id")
    Optional<EscrowHold> findByIdForUpdate(@Param("id") String id);
}
