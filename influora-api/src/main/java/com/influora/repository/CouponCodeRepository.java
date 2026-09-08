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

    /**
     * The campaign's brand-level ("page-exclusive") coupon, if one exists (T-FESTIVALBOX-0905
     * phase 4). At most one row can ever match, per the {@code
     * UNIQUE(campaign_id, brand_level_marker)} generated-column constraint added by
     * V20260905160000 -- see that migration and {@code CouponCodeService#addBrandLevelCoupon}.
     */
    Optional<CouponCode> findByCampaignIdAndCreatorIdIsNull(String campaignId);

    /** Workspace-scoped uniqueness check backing {@code UNIQUE(workspace_id, code)}. */
    boolean existsByWorkspaceIdAndCode(String workspaceId, String code);

    /** All coupon codes for a campaign, scoped to the owning workspace directly. */
    List<CouponCode> findByWorkspaceIdAndCampaignId(String workspaceId, String campaignId);

    /** Workspace-scoped single-code lookup (e.g. for a brand-facing coupon detail view). */
    Optional<CouponCode> findByWorkspaceIdAndId(String workspaceId, String id);

    /**
     * Workspace-scoped code lookup -- the correct finder for every caller that already knows which
     * workspace the code must belong to. {@code UNIQUE(workspace_id, code)} (V24) makes the result
     * unambiguous by construction: at most one row can match, so no "which one did we get" question
     * exists here at all.
     *
     * <p>[F-0728] Both store webhooks resolve their workspace from a signed delivery before
     * redeeming, so they are exactly these callers. Previously they went through the global finder
     * below and were then rejected AFTER the fact by {@code RedemptionWriter#validateCode}'s
     * cross-workspace check, which meant a second workspace registering the same code string
     * shadowed the first into {@code INVALID_CODE} -- Brand B creating {@code SUMMER20} made Brand
     * A's own {@code SUMMER20} permanently unredeemable.
     */
    Optional<CouponCode> findByWorkspaceIdAndCode(String workspaceId, String code);

    /**
     * Global code lookup for the ONE caller that genuinely has no workspace to scope by: {@code
     * ConversionWebhookController}, where the code itself is what resolves the workspace whose
     * secret then verifies the signature. There is no workspace principal at that point (same shape
     * of exception as {@code CampaignLinkService#recordClick} -- see that class's javadoc). Every
     * other caller must use {@link #findByWorkspaceIdAndCode}, {@link #findByWorkspaceIdAndId} or
     * {@link #findByWorkspaceIdAndCampaignId}.
     *
     * <p>[F-0728] Returns a LIST, and that is the fix rather than an inconvenience. This was {@code
     * Optional<CouponCode> findByCode}, whose javadoc claimed it "returns whichever row matches
     * first". That was wrong: Spring Data raises {@code IncorrectResultSizeDataAccessException} when
     * an {@code Optional} query matches more than one row, and {@code GlobalExceptionHandler} has no
     * handler for it -- so the moment two workspaces shared a code string, every delivery carrying
     * it became a bare 500. The uniqueness constraint is {@code UNIQUE(workspace_id, code)}, so two
     * such rows are entirely legal to create.
     *
     * <p>A caller with no workspace context genuinely cannot choose between two matches, and
     * guessing would attribute a real sale to the wrong brand. Callers must therefore treat "more
     * than one" as an explicit, reportable ambiguity -- never take {@code get(0)}.
     */
    List<CouponCode> findAllByCode(String code);

    /** Creator-scoped coupon list backing {@code CreatorCouponService}. */
    List<CouponCode> findByCreatorIdOrderByCreatedAtDesc(String creatorId);
}
