package com.influora.repository;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.enums.CollaborationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollaborationRepository extends JpaRepository<Collaboration, String> {

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

    /** Batch lookup powering the creator campaign browse list's per-row applicationStatus. */
    List<Collaboration> findByCreatorIdAndCampaignIdIn(String creatorId, List<String> campaignIds);

    Optional<Collaboration> findByIdAndCreatorId(String id, String creatorId);

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
}
