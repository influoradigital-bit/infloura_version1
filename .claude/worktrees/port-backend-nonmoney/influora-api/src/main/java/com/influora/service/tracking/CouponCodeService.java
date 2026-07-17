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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates and stores unique, per-creator coupon codes for a campaign (Phase 4 UTM/Coupon
 * Tracking, VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md §10 "Unique Coupons Per Creator (CRITICAL
 * FIX)"). Code generation + storage only -- {@code ConversionTrackingService}/{@code
 * RedemptionService} (processing a redemption against a code) are a deliberately deferred
 * follow-up, same scope cut discipline as {@code CampaignLinkService}'s UTM foundation slice.
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

        if (!couponCodeRepository.existsByWorkspaceIdAndCode(campaign.getWorkspaceId(), baseCode)) {
            return baseCode;
        }

        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            String candidate = baseCode + "_" + randomSuffix();
            if (!couponCodeRepository.existsByWorkspaceIdAndCode(campaign.getWorkspaceId(), candidate)) {
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

    private static String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return sb.toString();
    }
}
