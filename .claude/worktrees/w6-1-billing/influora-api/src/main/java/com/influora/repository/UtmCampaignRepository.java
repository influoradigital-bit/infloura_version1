package com.influora.repository;

import com.influora.domain.entity.UtmCampaign;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage-abstraction repository for UTM tracking links (V23 {@code utm_campaigns}) — Phase 4
 * UTM/Coupon Tracking, written/read by {@code CampaignLinkService}.
 *
 * <p><b>Workspace isolation:</b> {@code utm_campaigns} has no direct {@code workspace_id} column
 * (by design — see V23 migration comment: a UTM link's authorization derives from its {@code
 * campaign_id}'s workspace, not a column on this table). Every finder below is scoped only by
 * {@code campaignId}/{@code id}. Before calling any finder here with a caller-supplied {@code
 * campaignId} from a brand-facing request, callers MUST first resolve and authorize the campaign
 * via {@code campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)} (the same
 * resolve-then-scope pattern {@code DeliverableMetricService#getCampaignAnalytics} and {@code
 * MetricsAuthorizationService} use) and only proceed once that lookup succeeds. {@code
 * CampaignLinkService} follows this discipline — see its javadoc.
 */
public interface UtmCampaignRepository extends JpaRepository<UtmCampaign, String> {

    Optional<UtmCampaign> findByCampaignIdAndCreatorProfileId(String campaignId, String creatorProfileId);

    List<UtmCampaign> findByCampaignId(String campaignId);
}
