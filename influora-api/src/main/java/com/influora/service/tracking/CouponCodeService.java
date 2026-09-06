package com.influora.service.tracking;

import com.influora.common.ApiException;
import com.influora.common.SlugUtils;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.CreatorProfileRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates and stores unique coupon codes for a campaign (Phase 4 UTM/Coupon Tracking,
 * VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §10 "Unique Coupons Per Creator (CRITICAL FIX)") -- either
 * per-creator ({@link #addCreatorToCampaign}, §10's original shape) or, as of T-FESTIVALBOX-0905
 * phase 4, a single brand-level "page-exclusive" code per campaign ({@link #addBrandLevelCoupon},
 * {@code creator_id IS NULL} -- see {@code CouponCode} javadoc and the V20260905160000 migration
 * for why this does not reopen the ambiguity §10 closed). Code generation + storage only -- {@code
 * ConversionTrackingService}/{@code RedemptionService} (processing a redemption against a code) are
 * a deliberately deferred follow-up, same scope cut discipline as {@code CampaignLinkService}'s UTM
 * foundation slice.
 *
 * <p><b>Adapted from spec pseudocode to real fields.</b> §10's pseudocode calls {@code
 * campaign.getCouponPrefix()} and {@code creator.getSlug()} -- neither field exists on {@code
 * Campaign}/{@code CreatorProfile} in this codebase (confirmed by reading both entities), and
 * inventing new columns for this task was explicitly out of scope. Mirrors the exact pattern
 * {@code CampaignLinkService#buildAndSaveTrackingLink} already established: {@code
 * SlugUtils.slugify(campaign.getTitle())} in place of a dedicated prefix field, {@code
 * SlugUtils.slugify(creator.getDisplayName())} in place of a dedicated slug field. Similarly, {@code
 * Campaign} has no {@code discountType}/{@code discountValue}/{@code endsAt} fields for §10's
 * pseudocode to read off the campaign -- those are accepted as explicit parameters to {@link
 * #addCreatorToCampaign} instead of being invented on the entity.
 *
 * <p><b>Workspace authorization</b> -- same resolve-then-scope discipline as {@code
 * CampaignLinkService}: {@link #addCreatorToCampaign} resolves the campaign via {@code
 * campaignRepository.findByIdAndWorkspaceId(campaignId, workspaceId)} FIRST; a workspace that does
 * not own the campaign gets {@code CAMPAIGN_NOT_FOUND} (404) before the creator profile is ever
 * looked up and before any coupon row is written.
 *
 * <p><b>Idempotency</b> -- {@code UNIQUE(campaign_id, creator_id)} means a creator can only ever
 * have one coupon per campaign. {@link #addCreatorToCampaign} is deliberately idempotent, matching
 * {@code CampaignLinkService#createTrackingLink}'s existing-row-return convention for consistency:
 * calling it twice for the same (campaign, creator) pair returns the already-existing coupon rather
 * than throwing a constraint-violation exception. There is no product reason for a second call to
 * be an error (e.g. a retried request, or a UI that re-submits "add creator to campaign") -- the
 * caller wants "this creator has a coupon for this campaign," and idempotent-return gives them that
 * without needing to catch a `DataIntegrityViolationException`.
 */
@Service
public class CouponCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SUFFIX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int SUFFIX_LENGTH = 4;
    private static final int MAX_COLLISION_RETRIES = 5;

    private final CouponCodeRepository couponCodeRepository;
    private final CampaignRepository campaignRepository;
    private final CreatorProfileRepository creatorProfileRepository;

    public CouponCodeService(
            CouponCodeRepository couponCodeRepository,
            CampaignRepository campaignRepository,
            CreatorProfileRepository creatorProfileRepository) {
        this.couponCodeRepository = couponCodeRepository;
        this.campaignRepository = campaignRepository;
        this.creatorProfileRepository = creatorProfileRepository;
    }

    /**
     * Generates a unique coupon code string for {@code creator}'s participation in {@code
     * campaign}, in the given workspace. Pattern: {@code CREATOR_CAMPAIGN} (e.g. {@code
     * "PRIYA-SHARMA_SUMMER-SALE-2026"}), both halves slugified per {@link SlugUtils#slugify}
     * (mirroring {@code CampaignLinkService}'s UTM slug generation) and upper-cased. On a collision
     * against the workspace-scoped uniqueness constraint, appends a random 4-character alphanumeric
     * suffix and re-checks, per spec §10.
     *
     * <p>Uses {@link CouponCodeRepository#existsByWorkspaceIdAndCode} rather than a global
     * existence check -- the uniqueness constraint is {@code UNIQUE(workspace_id, code)}, not a bare
     * {@code UNIQUE(code)}, so the same code string is only a collision within the same workspace.
     *
     * <p><b>Collision retry loop</b> -- a single suffix-and-append (without re-verifying the
     * suffixed code) is not a correctness guarantee: at {@link #SUFFIX_LENGTH} characters over
     * {@link #SUFFIX_ALPHABET} there are 1.68M combinations, so a second collision is very unlikely,
     * but "very unlikely" would otherwise mean the caller finds out via a raw, unhandled {@code
     * DataIntegrityViolationException} out of {@code couponCodeRepository.save(...)} in {@link
     * #buildAndSaveCoupon}. Instead, each suffixed attempt is itself re-checked for uniqueness, up to
     * {@link #MAX_COLLISION_RETRIES} attempts; exhausting the retry budget throws a predictable
     * {@code ApiException} ({@code COUPON_CODE_GENERATION_FAILED}, 500) rather than crashing on the
     * constraint violation. This path should be effectively unreachable in practice.
     */
    public String generateCreatorCoupon(Campaign campaign, CreatorProfile creator) {
        String creatorSlug = SlugUtils.slugify(creator.getDisplayName()).toUpperCase(Locale.ROOT);
        String campaignSlug = SlugUtils.slugify(campaign.getTitle()).toUpperCase(Locale.ROOT);

        String baseCode = creatorSlug + "_" + campaignSlug;

        return generateUniqueCode(campaign.getWorkspaceId(), baseCode);
    }

    /**
     * Generates a unique coupon code string for {@code campaign}'s brand-level ("page-exclusive")
     * coupon (T-FESTIVALBOX-0905 phase 4). Pattern: {@code CAMPAIGN_EXCLUSIVE} (e.g. {@code
     * "SUMMER-SALE-2026_EXCLUSIVE"}) -- mirrors {@link #generateCreatorCoupon}'s shape but with a
     * fixed {@code "EXCLUSIVE"} suffix in place of a creator slug, since there is no creator to
     * derive one from. Same collision-retry discipline via {@link #generateUniqueCode}.
     */
    public String generateBrandCoupon(Campaign campaign) {
        String campaignSlug = SlugUtils.slugify(campaign.getTitle()).toUpperCase(Locale.ROOT);
        String baseCode = campaignSlug + "_EXCLUSIVE";

        return generateUniqueCode(campaign.getWorkspaceId(), baseCode);
    }

    /**
     * Shared collision-retry loop behind both {@link #generateCreatorCoupon} and {@link
     * #generateBrandCoupon} -- see {@link #generateCreatorCoupon} javadoc for the retry-loop
     * reasoning, unchanged here.
     */
    private String generateUniqueCode(String workspaceId, String baseCode) {
        if (!couponCodeRepository.existsByWorkspaceIdAndCode(workspaceId, baseCode)) {
            return baseCode;
        }

        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            String candidate = baseCode + "_" + randomSuffix();
            if (!couponCodeRepository.existsByWorkspaceIdAndCode(workspaceId, candidate)) {
                return candidate;
            }
        }

        throw new ApiException(
                "COUPON_CODE_GENERATION_FAILED",
                "Could not generate a unique coupon code after " + MAX_COLLISION_RETRIES + " attempts",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Adds a creator to a campaign's coupon program, generating and persisting a unique coupon code
     * for them. Idempotent: if this creator already has a coupon for this campaign, returns the
     * existing row instead of creating a duplicate (see class javadoc).
     *
     * @param workspaceId the calling brand's workspace -- MUST actually own {@code campaignId}
     * @param campaignId the campaign to add the creator's coupon to
     * @param creatorId the creator profile id the coupon is being generated for
     * @param discountType e.g. {@code "percentage"} or {@code "fixed"} -- not a {@code Campaign}
     *     field (doesn't exist on the entity), supplied by the caller
     * @param discountValue the discount amount (15 for 15%, or 500 for a fixed INR 500) -- likewise
     *     supplied by the caller, not read off {@code Campaign}
     * @param usageLimit optional max total redemptions; {@code null} = unlimited
     * @param expiresAt optional expiry instant; {@code null} = does not expire
     * @throws ApiException {@code CAMPAIGN_NOT_FOUND} (404) if {@code campaignId} does not exist or
     *     does not belong to {@code workspaceId} -- deliberately not distinguished from a genuinely
     *     missing campaign, matching {@code CampaignLinkService}'s discretion
     * @throws ApiException {@code CREATOR_NOT_FOUND} (404) if {@code creatorId} does not exist
     */
    @Transactional
    public CouponCode addCreatorToCampaign(
            String workspaceId,
            String campaignId,
            String creatorId,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            Instant expiresAt) {

        validateDiscountTerms(discountType, discountValue);

        // Workspace-ownership check FIRST -- resolve-then-scope, never trust the caller-supplied
        // campaignId on its own (same discipline as CampaignLinkService#createTrackingLink).
        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        CreatorProfile creator =
                creatorProfileRepository
                        .findById(creatorId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_NOT_FOUND", "Creator not found", HttpStatus.NOT_FOUND));

        return couponCodeRepository
                .findByCampaignIdAndCreatorId(campaign.getId(), creator.getId())
                .orElseGet(
                        () ->
                                buildAndSaveCoupon(
                                        campaign, creator, discountType, discountValue, usageLimit, expiresAt));
    }

    private CouponCode buildAndSaveCoupon(
            Campaign campaign,
            CreatorProfile creator,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            Instant expiresAt) {
        String code = generateCreatorCoupon(campaign, creator);

        CouponCode entity =
                CouponCode.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(campaign.getWorkspaceId())
                        .campaignId(campaign.getId())
                        .creatorId(creator.getId())
                        .code(code)
                        .discountType(discountType)
                        .discountValue(discountValue)
                        .usageLimit(usageLimit)
                        .expiresAt(expiresAt)
                        .build();

        return couponCodeRepository.save(entity);
    }

    /**
     * Creates (or returns the existing) brand-level, page-exclusive coupon for {@code campaignId}
     * (T-FESTIVALBOX-0905 phase 4) -- the "ONE brand-level code per campaign" counterpart to {@link
     * #addCreatorToCampaign}. {@code creator_id} is left NULL via {@link
     * CouponCode#brandLevelBuilder()}; the sale attributes to the brand only and earns no creator
     * affiliate commission (see {@code AffiliateEarningsService#recordEarning}).
     *
     * <p><b>Idempotent, like {@link #addCreatorToCampaign}</b> -- a second call for a campaign that
     * already has a brand-level coupon returns the existing row rather than attempting a duplicate
     * insert.
     *
     * <p><b>Refused, not 500'd, on a genuine second attempt</b> -- the pre-check above is an
     * ordinary read-then-write (not itself race-proof), so a concurrent double-submit can still
     * reach {@code couponCodeRepository.save(...)} twice. The actual backstop is the schema's {@code
     * UNIQUE(campaign_id, brand_level_marker)} (V20260905160000); a {@link
     * DataIntegrityViolationException} from that constraint is caught here and translated into the
     * same {@code BRAND_CODE_EXISTS} (409) the pre-check throws, so a caller never sees a raw 500
     * for what is, from the outside, an ordinary "already exists" conflict.
     *
     * @param workspaceId the calling brand's workspace -- MUST actually own {@code campaignId}
     * @param campaignId the campaign to create the brand-level coupon for
     * @param discountType e.g. {@code "percentage"} or {@code "fixed"}
     * @param discountValue the discount amount (15 for 15%, or 500 for a fixed INR 500)
     * @param usageLimit optional max total redemptions; {@code null} = unlimited
     * @param expiresAt optional expiry instant; {@code null} = does not expire
     * @throws ApiException {@code CAMPAIGN_NOT_FOUND} (404) if {@code campaignId} does not exist or
     *     does not belong to {@code workspaceId}
     * @throws ApiException {@code BRAND_CODE_EXISTS} (409) if this campaign already has a
     *     brand-level coupon (whether observed via the pre-check or via the DB constraint)
     */
    @Transactional
    public CouponCode addBrandLevelCoupon(
            String workspaceId,
            String campaignId,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            Instant expiresAt) {

        validateDiscountTerms(discountType, discountValue);

        // Workspace-ownership check FIRST -- same resolve-then-scope discipline as
        // addCreatorToCampaign.
        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));

        Optional<CouponCode> existing = couponCodeRepository.findByCampaignIdAndCreatorIdIsNull(campaign.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return buildAndSaveBrandLevelCoupon(campaign, discountType, discountValue, usageLimit, expiresAt);
        } catch (DataIntegrityViolationException raced) {
            // Lost a concurrent race against another request creating the same campaign's
            // brand-level coupon -- see javadoc above; the pre-check above is not race-proof on its
            // own, the schema's UNIQUE(campaign_id, brand_level_marker) is the real backstop.
            // Deliberately NOT re-queried-and-returned-as-if-successful: the racing request may have
            // used different discountType/discountValue/usageLimit/expiresAt than this caller asked
            // for, so silently handing back "some" brand-level coupon under this caller's own
            // request would misrepresent what was actually created. Refused as the same
            // BRAND_CODE_EXISTS (409) the pre-check above throws -- not a raw 500.
            throw new ApiException(
                    "BRAND_CODE_EXISTS",
                    "A brand-level coupon already exists for this campaign",
                    HttpStatus.CONFLICT);
        }
    }

    private CouponCode buildAndSaveBrandLevelCoupon(
            Campaign campaign,
            String discountType,
            BigDecimal discountValue,
            Integer usageLimit,
            Instant expiresAt) {
        String code = generateBrandCoupon(campaign);

        CouponCode entity =
                CouponCode.brandLevelBuilder()
                        .id(Ulids.newUlid())
                        .workspaceId(campaign.getWorkspaceId())
                        .campaignId(campaign.getId())
                        .code(code)
                        .discountType(discountType)
                        .discountValue(discountValue)
                        .usageLimit(usageLimit)
                        .expiresAt(expiresAt)
                        .build();

        return couponCodeRepository.save(entity);
    }

    private static String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Rejects discount terms that would mint a coupon nobody can redeem, or one that refunds more
     * than the order [Kabir H-3].
     *
     * <p>WHY HERE AND NOT ONLY IN BEAN VALIDATION: this service has TWO doors — {@code
     * CampaignTrackingController} (brand) and {@code AdminCampaignCouponController} (admin). The
     * brand door was missing {@code @Valid} entirely, so its constraints never fired at all. A rule
     * this consequential should not depend on every present and future caller remembering an
     * annotation, so it is enforced at the one place both doors pass through.
     *
     * <p>The specific failure this prevents: {@code discountType} was previously unchecked, so
     * {@code "percent"} — one letter from the real value — saved a valid-looking row that then threw
     * {@code UNSUPPORTED_DISCOUNT_TYPE} as a hardcoded 500 on EVERY redemption forever
     * ({@code RedemptionWriter#calculateDiscount}'s {@code default} branch). No sale, no commission,
     * and a dead code printed on a public Festival Box page. The percentage ceiling is the
     * cross-field rule bean validation cannot express: 100 is only meaningful for percentages, and a
     * fixed amount is separately clamped to the order total at redemption.
     */
    private static void validateDiscountTerms(String discountType, BigDecimal discountValue) {
        String normalized = discountType == null ? "" : discountType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("percentage") && !normalized.equals("fixed")) {
            throw new ApiException(
                    "INVALID_DISCOUNT_TYPE",
                    "discountType must be 'percentage' or 'fixed'",
                    HttpStatus.BAD_REQUEST);
        }
        if (discountValue == null || discountValue.signum() <= 0) {
            throw new ApiException(
                    "INVALID_DISCOUNT_VALUE",
                    "discountValue must be greater than zero",
                    HttpStatus.BAD_REQUEST);
        }
        if (normalized.equals("percentage") && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ApiException(
                    "INVALID_DISCOUNT_VALUE",
                    "a percentage discount cannot exceed 100",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
