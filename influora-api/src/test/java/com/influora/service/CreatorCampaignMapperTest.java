package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignDetailResponse;
import com.influora.web.dto.creatorcampaign.CreatorCampaignDtos.CreatorCampaignListItem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * PR-2 (BrandF.md §83c / §105 VER-1) — {@code BrandSummary.verificationStatus} must be the real
 * {@code Workspace.verificationStatus}, not hardcoded/defaulted, and must reflect every possible
 * enum value a creator could actually see (including a brand that has never started KYC).
 */
class CreatorCampaignMapperTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";

    private static Campaign campaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Campaign")
                .description("desc")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("10000"))
                .budgetMax(new BigDecimal("50000"))
                .currency("INR")
                .createdBy("brandUser1")
                .build();
    }

    private static Workspace workspaceWith(VerificationStatus status) {
        Workspace w = Workspace.newBrand(WORKSPACE_ID, "HealthKart", "healthkart", "Health", "50-200");
        w.setVerificationStatus(status);
        return w;
    }

    @Test
    void toListItem_surfacesVerifiedWorkspace() {
        CreatorCampaignListItem item =
                CreatorCampaignMapper.toListItem(campaign(), workspaceWith(VerificationStatus.VERIFIED), null);

        assertEquals("VERIFIED", item.brand().verificationStatus());
    }

    @Test
    void toListItem_surfacesPendingWorkspace_notHardcodedVerified() {
        CreatorCampaignListItem item =
                CreatorCampaignMapper.toListItem(campaign(), workspaceWith(VerificationStatus.PENDING), null);

        assertEquals("PENDING", item.brand().verificationStatus());
    }

    @Test
    void toListItem_surfacesUnverifiedWorkspace() {
        CreatorCampaignListItem item =
                CreatorCampaignMapper.toListItem(campaign(), workspaceWith(VerificationStatus.UNVERIFIED), null);

        assertEquals("UNVERIFIED", item.brand().verificationStatus());
    }

    @Test
    void toListItem_surfacesRejectedWorkspace() {
        CreatorCampaignListItem item =
                CreatorCampaignMapper.toListItem(campaign(), workspaceWith(VerificationStatus.REJECTED), null);

        assertEquals("REJECTED", item.brand().verificationStatus());
    }

    @Test
    void toDetail_surfacesVerifiedWorkspace_sameSignalAsListItem() {
        CreatorCampaignDetailResponse detail =
                CreatorCampaignMapper.toDetail(campaign(), workspaceWith(VerificationStatus.VERIFIED), null);

        assertEquals("VERIFIED", detail.brand().verificationStatus());
    }

    @Test
    void toBrand_nullWorkspace_returnsNullBrandNotNullPointer() {
        CreatorCampaignListItem item = CreatorCampaignMapper.toListItem(campaign(), null, null);

        assertNull(item.brand());
    }
}
