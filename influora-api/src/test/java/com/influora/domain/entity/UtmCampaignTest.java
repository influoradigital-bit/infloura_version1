package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-FESTIVALBOX-0905 phase 7: unit tests for {@link UtmCampaign#isPageLevel()} and {@link
 * UtmCampaign#pageLevelBuilder()} -- the entity-level half of the nullable creator/collaboration
 * change (migration V20260905180000). Mirrors {@code CouponCode}'s {@code isBrandLevel()}/{@code
 * brandLevelBuilder()} test coverage shape.
 */
class UtmCampaignTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";

    @Test
    @DisplayName("isPageLevel: false for an ordinary per-creator-per-collaboration link")
    void testIsPageLevelFalseForOrdinaryLink() {
        UtmCampaign utm =
                UtmCampaign.builder()
                        .id("01HUTM1234567890ABCDE")
                        .campaignId(CAMPAIGN_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .baseUrl("https://brand.example.com")
                        .build();

        assertFalse(utm.isPageLevel());
    }

    @Test
    @DisplayName(
            "isPageLevel: false for a per-creator Festival Box link with a creator but no"
                    + " collaboration -- keyed on creatorProfileId alone, not collaborationId")
    void testIsPageLevelFalseForCreatorLinkWithoutCollaboration() {
        UtmCampaign utm =
                UtmCampaign.builder()
                        .id("01HUTM1234567890ABCDE")
                        .campaignId(CAMPAIGN_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        // collaborationId deliberately never set -- roster creator, no Collaboration row.
                        .baseUrl("https://brand.example.com")
                        .build();

        assertNull(utm.getCollaborationId());
        assertFalse(utm.isPageLevel());
    }

    @Test
    @DisplayName("isPageLevel: true for a page-level link built via pageLevelBuilder")
    void testIsPageLevelTrueForPageLevelLink() {
        UtmCampaign utm =
                UtmCampaign.pageLevelBuilder()
                        .id("01HUTM1234567890ABCDE")
                        .campaignId(CAMPAIGN_ID)
                        .baseUrl("https://brand.example.com/shop")
                        .utmSource("web")
                        .utmMedium("shop")
                        .utmCampaign("festival-box")
                        .fullTrackingUrl("https://brand.example.com/shop?utm_source=web")
                        .build();

        assertTrue(utm.isPageLevel());
        assertNull(utm.getCreatorProfileId());
        assertNull(utm.getCollaborationId());
    }

    @Test
    @DisplayName("pageLevelBuilder: has no creatorProfileId/collaborationId setters -- structurally cannot carry either")
    void testPageLevelBuilderCannotBeGivenACreatorOrCollaboration() {
        // Compile-time proof by omission: UtmCampaign.PageLevelBuilder simply has no
        // .creatorProfileId(...)/.collaborationId(...) methods to call here at all. This test
        // documents that guarantee and asserts the runtime consequence -- both fields are null
        // no matter what else the builder is given.
        UtmCampaign utm =
                UtmCampaign.pageLevelBuilder()
                        .id("01HUTM1234567890ABCDE")
                        .campaignId(CAMPAIGN_ID)
                        .baseUrl("https://brand.example.com/shop")
                        .build();

        assertNull(utm.getCreatorProfileId());
        assertNull(utm.getCollaborationId());
        assertTrue(utm.isPageLevel());
    }

    @Test
    @DisplayName("pageLevelBuilder: build() initializes counters to zero, same as the ordinary Builder")
    void testPageLevelBuilderInitializesCountersToZero() {
        UtmCampaign utm =
                UtmCampaign.pageLevelBuilder()
                        .id("01HUTM1234567890ABCDE")
                        .campaignId(CAMPAIGN_ID)
                        .baseUrl("https://brand.example.com/shop")
                        .build();

        assertTrue(utm.getClickCount() == 0L);
        assertTrue(utm.getUniqueVisitors() == 0L);
        assertTrue(utm.getConversionCount() == 0L);
        assertTrue(java.math.BigDecimal.ZERO.compareTo(utm.getRevenueAttributed()) == 0);
    }
}
