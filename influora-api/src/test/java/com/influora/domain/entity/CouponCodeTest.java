package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-FESTIVALBOX-0905 phase 4: unit tests for {@link CouponCode#isBrandLevel()} and the two builder
 * paths ({@link CouponCode.Builder} for per-creator, {@link CouponCode.BrandLevelBuilder} for
 * brand-level) now that {@code creatorId} is nullable (V20260905160000).
 */
class CouponCodeTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String CREATOR_ID = "01HCREATORPROFILE1234";

    @Test
    @DisplayName("isBrandLevel: false for a coupon built with a creatorId (per-creator, unchanged default)")
    void testIsBrandLevelFalseForPerCreatorCoupon() {
        CouponCode coupon =
                CouponCode.builder()
                        .id("01HCOUPON1234567890AB")
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .creatorId(CREATOR_ID)
                        .code("PRIYA_SUMMER25")
                        .discountType("percentage")
                        .discountValue(BigDecimal.valueOf(15))
                        .build();

        assertFalse(coupon.isBrandLevel());
        assertTrue(CREATOR_ID.equals(coupon.getCreatorId()));
    }

    @Test
    @DisplayName("isBrandLevel: true for a coupon built via brandLevelBuilder() (no creatorId setter exists at all)")
    void testIsBrandLevelTrueForBrandLevelCoupon() {
        CouponCode coupon =
                CouponCode.brandLevelBuilder()
                        .id("01HCOUPON1234567890AB")
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .code("SUMMER-SALE-2026_EXCLUSIVE")
                        .discountType("percentage")
                        .discountValue(BigDecimal.valueOf(15))
                        .build();

        assertTrue(coupon.isBrandLevel());
        assertNull(coupon.getCreatorId());
    }

    @Test
    @DisplayName(
            "isBrandLevel: true whenever creatorId is null regardless of which builder path was"
                    + " used -- isBrandLevel() is a pure function of the field, not of the builder type")
    void testIsBrandLevelIsPureFunctionOfCreatorIdField() {
        // The plain Builder technically permits omitting .creatorId(...) entirely (nothing in the
        // Builder class enforces it must be called) -- isBrandLevel() must still correctly report
        // true in that case, since the DB-level contract (V20260905160000) is "creatorId IS NULL",
        // not "which builder was used."
        CouponCode viaPlainBuilderWithoutCreatorId =
                CouponCode.builder()
                        .id("01HCOUPON1234567890AB")
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .code("SUMMER-SALE-2026_EXCLUSIVE")
                        .discountType("percentage")
                        .discountValue(BigDecimal.valueOf(15))
                        .build();

        assertTrue(viaPlainBuilderWithoutCreatorId.isBrandLevel());
    }
}
