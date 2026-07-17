package com.influora.repository;

import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.MilestoneStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentMilestoneRepository extends JpaRepository<PaymentMilestone, String> {

    List<PaymentMilestone> findByContractIdOrderBySequenceNoAsc(String contractId);

    Optional<PaymentMilestone> findByIdAndCollaborationId(String id, String collaborationId);

    /**
     * [SEC: Wave-1 S5] Workspace-scoped milestone lookup — {@link PaymentMilestone} has no direct
     * {@code workspaceId} column, so tenancy is enforced by joining
     * {@code milestone -> collaboration -> campaign -> workspace}, same join shape as {@link
     * #sumAmountByWorkspaceAndStatus}. Used by {@code EscrowService#deriveFundAmount} so a
     * milestone id belonging to another brand's workspace can never be resolved (cross-tenant
     * amount/info disclosure) — it must 404 exactly like the sibling campaign-scoped branch.
     */
    @Query(
            "SELECT m FROM PaymentMilestone m WHERE m.id = :id AND m.collaborationId IN "
                    + "(SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
    Optional<PaymentMilestone> findByIdAndWorkspaceId(
            @Param("id") String id, @Param("workspaceId") String workspaceId);

    Optional<PaymentMilestone> findByIdempotencyKey(String idempotencyKey);

    List<PaymentMilestone> findByCollaborationIdIn(List<String> collaborationIds);

    List<PaymentMilestone> findByCollaborationId(String collaborationId);

    /**
     * Sum of milestone amounts in a given status across every collaboration in a workspace.
     * {@code COALESCE(...,0)} guarantees a non-null zero for a brand with no milestones yet, so
     * callers never have to null-guard. Used for the dashboard wallet summary's pendingPayouts.
     */
    @Query(
            "SELECT COALESCE(SUM(m.amount), 0) FROM PaymentMilestone m WHERE m.status = :status "
                    + "AND m.collaborationId IN (SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
    BigDecimal sumAmountByWorkspaceAndStatus(
            @Param("workspaceId") String workspaceId, @Param("status") MilestoneStatus status);

    /** Milestones in a given status across every collaboration in a workspace (dashboard actions). */
    @Query(
            "SELECT m FROM PaymentMilestone m WHERE m.status = :status "
                    + "AND m.collaborationId IN (SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
    List<PaymentMilestone> findByWorkspaceIdAndStatus(
            @Param("workspaceId") String workspaceId, @Param("status") MilestoneStatus status);

    /**
     * Sum of milestone amounts in a given status across every collaboration owned by a creator.
     * Powers the creator wallet summary's {@code pendingPayouts} (FUNDED, not-yet-released).
     */
    @Query(
            "SELECT COALESCE(SUM(m.amount), 0) FROM PaymentMilestone m WHERE m.status = :status "
                    + "AND m.collaborationId IN (SELECT c.id FROM Collaboration c WHERE c.creatorId = :creatorUserId)")
    BigDecimal sumAmountByCreatorIdAndStatus(
            @Param("creatorUserId") String creatorUserId, @Param("status") MilestoneStatus status);
}
