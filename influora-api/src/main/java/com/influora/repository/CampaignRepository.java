package com.influora.repository;

import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository
        extends JpaRepository<Campaign, String>, JpaSpecificationExecutor<Campaign> {

    Optional<Campaign> findByIdAndWorkspaceId(String id, String workspaceId);

    /** Platform-wide count by status — powers AdminDashboardController's CEO Pulse/operations reads. */
    long countByStatus(CampaignStatus status);

    /** All campaigns for a workspace — powers AdminBrandController's brand-detail campaign list. */
    List<Campaign> findByWorkspaceId(String workspaceId);

    List<Campaign> findByWorkspaceIdIn(List<String> workspaceIds);

    /**
     * All campaigns in one status — powers the admin at-risk view ({@code
     * GET /admin/campaigns/at-risk}, {@code AdminCampaignService.atRisk}, filtered to {@code ACTIVE}).
     * Derived query on the {@code status} column.
     */
    List<Campaign> findByStatus(CampaignStatus status);

    /**
     * Campaigns of one intent type in one status — powers the admin hype-ops snapshot ({@code
     * GET /admin/campaigns/hype/ops}, {@code AdminCampaignService.hypeOps}), called with {@code
     * (HYPE, ACTIVE)}. Derived query on the {@code campaign_type} + {@code status} columns.
     */
    List<Campaign> findByCampaignTypeAndStatus(CampaignIntentType campaignType, CampaignStatus status);

    /**
     * Row lock for the status-transition-plus-side-effects sequence around campaign activation
     * (Kabir red-team finding: unlike {@code WalletRepository}/{@code EscrowHoldRepository}, no
     * lock previously existed here, so two concurrent activation requests could both pass the
     * pre-ACTIVE check unlocked). Matches the exact {@code @Lock(PESSIMISTIC_WRITE)} +
     * {@code @Query} pattern used by those repositories. Callers are responsible for their own
     * tenant (workspaceId) check after loading, same as the unlocked {@code findByIdAndWorkspaceId}
     * 404-for-both-cases discipline elsewhere in this codebase.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") String id);

    /**
     * Distinct BRAND-type workspaces with at least one campaign — powers {@code
     * GET /admin/marketing/growth}'s {@code funnel.firstCampaign} / {@code
     * conversionRates.brandSignupToFirstCampaign} numerator ({@code
     * AdminMarketingService.getGrowth}). Native query, joined to {@code workspaces} and filtered to
     * {@code type = 'BRAND'} — agency-owned campaigns (if any) are excluded, since this funnel stage
     * is specifically about brand accounts.
     */
    @Query(
            value =
                    "SELECT COUNT(DISTINCT c.workspace_id) FROM campaigns c "
                            + "JOIN workspaces w ON c.workspace_id = w.id WHERE w.type = 'BRAND'",
            nativeQuery = true)
    long countBrandWorkspacesWithCampaign();

    /**
     * BRAND-type workspaces with 2 or more campaigns — powers {@code GET /admin/marketing/growth}'s
     * {@code funnel.repeatCampaign} ({@code AdminMarketingService.getGrowth}). Native query (a
     * grouped {@code HAVING COUNT(*) >= 2} over a derived table) — plain JPQL derived-table grouping
     * isn't portable here, same rationale as {@link DisputeRepository#avgResolutionSeconds}'s native
     * query.
     */
    @Query(
            value =
                    "SELECT COUNT(*) FROM ("
                            + "SELECT c.workspace_id FROM campaigns c "
                            + "JOIN workspaces w ON c.workspace_id = w.id WHERE w.type = 'BRAND' "
                            + "GROUP BY c.workspace_id HAVING COUNT(*) >= 2) repeat_brands",
            nativeQuery = true)
    long countBrandWorkspacesWithRepeatCampaigns();
}
