package com.influora.repository;

import com.influora.domain.entity.CouponCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code coupon_codes} (V24) is different in shape from {@code utm_campaigns}: it carries a direct
 * {@code workspace_id} column (see {@code CouponCode} javadoc / V24 migration), so -- unlike {@code
 * CampaignMetricsRepository}'s caller-must-verify-via-join pattern -- finders here CAN and SHOULD be
 * scoped by {@code workspaceId} directly. This is a simpler, more directly-enforceable isolation
 * story: every read that should be workspace-scoped can express that in the derived-query method
 * name itself (Spring Data then generates the `WHERE workspace_id = ?` clause), the same way {@code
 * MetaOAuthTokenRepository} scopes its finders by {@code workspaceId} directly rather than requiring
 * a join through another entity.
 *
 * <p>Callers MUST still resolve the campaign via {@code CampaignRepository#findByIdAndWorkspaceId}
 * FIRST when creating a coupon (see {@code CouponCodeService#addCreatorToCampaign}) -- these
 * finders being workspace-scoped does not by itself replace that resolve-then-scope check on
 * writes, it only means reads have a simpler, directly-enforceable isolation shape than {@code
 * UtmCampaignRepository} did.
 */
public interface CouponCodeRepository extends JpaRepository<CouponCode, String> {

    /** One coupon per creator per campaign (enforced by {@code UNIQUE(campaign_id, creator_id)}). */
    Optional<CouponCode> findByCampaignIdAndCreatorId(String campaignId, String creatorId);

    /** Workspace-scoped uniqueness check backing {@code UNIQUE(workspace_id, code)}. */
    boolean existsByWorkspaceIdAndCode(String workspaceId, String code);

    /** All coupon codes for a campaign, scoped to the owning workspace directly. */
    List<CouponCode> findByWorkspaceIdAndCampaignId(String workspaceId, String campaignId);

    /** Workspace-scoped single-code lookup (e.g. for a brand-facing coupon detail view). */
    Optional<CouponCode> findByWorkspaceIdAndId(String workspaceId, String id);

    /**
     * Global code lookup, deliberately NOT workspace-scoped -- used by {@code RedemptionService
     * #redeem}, which is called from an unauthenticated brand-webhook endpoint (the brand's own
     * commerce platform posting "this code was used at checkout"), not a workspace-authenticated
     * brand request. There is no workspace principal available at that call site to scope by (same
     * shape of exception as {@code CampaignLinkService#recordClick}, which is also intentionally not
     * workspace-scoped for the same reason -- see that class's javadoc). The uniqueness constraint
     * behind this lookup is {@code UNIQUE(workspace_id, code)}, not a bare {@code UNIQUE(code)}, so in
     * theory the same code string could exist in two different workspaces; this finder returns
     * whichever row matches first and is only safe to use where the caller has no workspace context
     * to disambiguate with (i.e. exactly the webhook-redemption case). A brand-authenticated read
     * path must use {@link #findByWorkspaceIdAndId} or {@link #findByWorkspaceIdAndCampaignId}
     * instead, never this method.
     */
    Optional<CouponCode> findByCode(String code);

    /** Creator-scoped coupon list backing {@code CreatorCouponService}. */
    List<CouponCode> findByCreatorIdOrderByCreatedAtDesc(String creatorId);
}
