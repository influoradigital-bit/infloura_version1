package com.influora.repository;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Dispute;
import com.influora.domain.enums.DisputeStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisputeRepository extends JpaRepository<Dispute, String> {

    boolean existsByCollaborationIdAndStatusIn(String collaborationId, Collection<DisputeStatus> statuses);

    /**
     * Disputes for a set of collaborations — used by the admin escrow console ({@code GET
     * /admin/escrow/flagged}) to attach each FROZEN hold's flag reason (a hold is frozen precisely
     * because a dispute was opened on its collaboration). Caller de-dupes to the most-recent dispute
     * per collaboration. Returns an empty list for an empty input.
     */
    List<Dispute> findByCollaborationIdIn(Collection<String> collaborationIds);

    /**
     * Brand-scoped dispute list (B7). Disputes carry no {@code workspace_id} of their own — the
     * trust boundary is resolved through {@code collaboration.campaign_id -> campaign.workspace_id},
     * same discipline as {@code CollaborationRepository.findByWorkspaceId}.
     */
    @Query(
            "SELECT d FROM Dispute d WHERE d.collaborationId IN "
                    + "(SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId))")
    Page<Dispute> findByWorkspaceId(@Param("workspaceId") String workspaceId, Pageable pageable);

    /**
     * P2-14 — brand-scoped disputes with {@code Collaboration} joined (for display fields). Scoped via
     * {@code collaboration→campaign→workspace}.
     */
    @Query(
            "SELECT d, c FROM Dispute d JOIN Collaboration c ON d.collaborationId = c.id "
                    + "WHERE c.campaignId IN (SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :workspaceId) "
                    + "ORDER BY d.createdAt DESC")
    List<Object[]> findWithCollaborationByWorkspaceId(@Param("workspaceId") String workspaceId);

    /**
     * P2-14 — creator-scoped disputes with {@code Collaboration} joined (for display fields). Scoped via
     * {@code collaboration.creatorId}.
     */
    @Query(
            "SELECT d, c FROM Dispute d JOIN Collaboration c ON d.collaborationId = c.id "
                    + "WHERE c.creatorId = :creatorUserId "
                    + "ORDER BY d.createdAt DESC")
    List<Object[]> findWithCollaborationByCreatorUserId(@Param("creatorUserId") String creatorUserId);

    /**
     * Task #4 — admin disputes list with optional filtering by status, campaignId, brandId
     * (workspace), or creatorId. Joins through Collaboration and Campaign to resolve brand scope.
     */
    @Query(
            "SELECT d FROM Dispute d "
                    + "WHERE (:status IS NULL OR d.status = :status) "
                    + "AND (:campaignId IS NULL OR d.collaborationId IN "
                    + "(SELECT c.id FROM Collaboration c WHERE c.campaignId = :campaignId)) "
                    + "AND (:brandId IS NULL OR d.collaborationId IN "
                    + "(SELECT c.id FROM Collaboration c WHERE c.campaignId IN "
                    + "(SELECT ca.id FROM Campaign ca WHERE ca.workspaceId = :brandId))) "
                    + "AND (:creatorId IS NULL OR d.collaborationId IN "
                    + "(SELECT c.id FROM Collaboration c WHERE c.creatorId = :creatorId))")
    Page<Dispute> findFiltered(
            @Param("status") DisputeStatus status,
            @Param("campaignId") String campaignId,
            @Param("brandId") String brandId,
            @Param("creatorId") String creatorId,
            Pageable pageable);

    /**
     * Average dispute resolution time in SECONDS across every resolved dispute (has a {@code
     * resolved_at}) — powers {@code disputeResolutionSpeed} on the admin reputation score ({@code
     * GET /admin/marketing/reputation}, converted to hours in the service). Returns {@code null}
     * when no dispute has been resolved yet, never a fabricated 0.
     */
    @Query(
            value =
                    "SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, resolved_at)) FROM disputes "
                            + "WHERE resolved_at IS NOT NULL",
            nativeQuery = true)
    Double avgResolutionSeconds();
}
