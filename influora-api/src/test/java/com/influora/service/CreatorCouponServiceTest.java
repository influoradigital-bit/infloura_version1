package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.UtmCampaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorcoupon.CreatorCouponDtos.CreatorCouponListItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Task #28 (P0-V3) — creator coupon read isolation + enrichment mapping. */
@ExtendWith(MockitoExtension.class)
class CreatorCouponServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String CREATOR_PROFILE_A = "01HCREATORPROFILEA12";
    private static final String CREATOR_PROFILE_B = "01HCREATORPROFILEB12";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String COUPON_ID = "01HCOUPON1234567890AB";

    @Mock private CreatorContextService creatorContext;
    @Mock private CouponCodeRepository couponCodeRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private AuthPrincipal principal;

    private static final String API_PUBLIC_URL = "http://localhost:8080/api/v1";
    private static final String UTM_ID = "01HUTM12345678901234A";

    private CreatorCouponService service;
    private CreatorProfile creatorA;

    @BeforeEach
    void setUp() {
        service =
                new CreatorCouponService(
                        creatorContext,
                        couponCodeRepository,
                        campaignRepository,
                        workspaceRepository,
                        utmCampaignRepository,
                        API_PUBLIC_URL);
        creatorA = CreatorProfile.newForUser(CREATOR_PROFILE_A, CREATOR_USER_ID, "Creator A");
    }

    @Test
    @DisplayName("list: scopes query to authenticated creator profile id — cross-creator isolation")
    void testCrossCreatorIsolation() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);

        CouponCode couponA = couponForCreator(CREATOR_PROFILE_A, "CODE-A");

        when(couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_A))
                .thenReturn(List.of(couponA));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(campaign()));
        when(workspaceRepository.findAllById(any())).thenReturn(List.of(workspace()));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_A))
                .thenReturn(Optional.empty());

        List<CreatorCouponListItem> result = service.list(principal);

        assertEquals(1, result.size());
        assertEquals("CODE-A", result.get(0).code());
        verify(couponCodeRepository).findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_A);
        verify(couponCodeRepository, never()).findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_B);
    }

    @Test
    @DisplayName("list: happy path enriches campaign name, brand name, and tracking URL")
    void testListHappyPathEnrichment() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);

        CouponCode coupon = couponForCreator(CREATOR_PROFILE_A, "PRIYA20");
        when(couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_A))
                .thenReturn(List.of(coupon));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(campaign()));
        when(workspaceRepository.findAllById(any())).thenReturn(List.of(workspace()));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_A))
                .thenReturn(Optional.of(utmCampaign()));

        List<CreatorCouponListItem> result = service.list(principal);

        assertEquals(1, result.size());
        CreatorCouponListItem item = result.get(0);
        assertEquals(COUPON_ID, item.id());
        assertEquals(CAMPAIGN_ID, item.campaignId());
        assertEquals("Summer Collection Launch", item.campaignName());
        assertEquals("Nykaa Fashion", item.brandName());
        assertEquals("PRIYA20", item.code());
        assertEquals("percentage", item.discountType());
        assertEquals(BigDecimal.valueOf(20), item.discountValue());
        assertEquals(500, item.usageLimit());
        assertEquals("https://nykaa.com/summer?utm_source=influora", item.trackingUrl());
        assertEquals(API_PUBLIC_URL + "/track/click/" + UTM_ID, item.redirectUrl());
    }

    @Test
    @DisplayName("list: returns empty list when creator has no coupons")
    void testListEmpty() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);
        when(couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_A))
                .thenReturn(List.of());

        List<CreatorCouponListItem> result = service.list(principal);

        assertTrue(result.isEmpty());
        verify(campaignRepository).findAllById(java.util.Set.of());
        verify(workspaceRepository).findAllById(java.util.Set.of());
    }

    @Test
    @DisplayName("list: omits trackingUrl and redirectUrl when no UTM row exists for the campaign/creator pair")
    void testListWithoutTrackingUrl() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);
        when(couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_A))
                .thenReturn(List.of(couponForCreator(CREATOR_PROFILE_A, "PRIYA20")));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(campaign()));
        when(workspaceRepository.findAllById(any())).thenReturn(List.of(workspace()));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_A))
                .thenReturn(Optional.empty());

        List<CreatorCouponListItem> result = service.list(principal);

        assertEquals(1, result.size());
        assertNull(result.get(0).trackingUrl());
        assertNull(result.get(0).redirectUrl());
    }

    @Test
    @DisplayName(
            "list: redirectUrl is the real /track/click/{utmCampaignId} click-counting link (TRACK-3,"
                    + " Priya ruling Q3 / GAP #1) — distinct from the raw-brand-URL trackingUrl")
    void testListRedirectUrlIsClickTrackingLink() {
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creatorA);
        when(couponCodeRepository.findByCreatorIdOrderByCreatedAtDesc(CREATOR_PROFILE_A))
                .thenReturn(List.of(couponForCreator(CREATOR_PROFILE_A, "PRIYA20")));
        when(campaignRepository.findAllById(any())).thenReturn(List.of(campaign()));
        when(workspaceRepository.findAllById(any())).thenReturn(List.of(workspace()));
        when(utmCampaignRepository.findByCampaignIdAndCreatorProfileId(CAMPAIGN_ID, CREATOR_PROFILE_A))
                .thenReturn(Optional.of(utmCampaign()));

        List<CreatorCouponListItem> result = service.list(principal);

        assertEquals(1, result.size());
        CreatorCouponListItem item = result.get(0);
        assertEquals(API_PUBLIC_URL + "/track/click/" + UTM_ID, item.redirectUrl());
        assertTrue(item.redirectUrl().startsWith(API_PUBLIC_URL));
        // Sanity: redirectUrl must never equal the raw brand tracking URL — that's the whole bug.
        assertTrue(!item.redirectUrl().equals(item.trackingUrl()));
    }

    private static CouponCode couponForCreator(String creatorProfileId, String code) {
        return CouponCode.builder()
                .id(COUPON_ID)
                .workspaceId(WORKSPACE_ID)
                .campaignId(CAMPAIGN_ID)
                .creatorId(creatorProfileId)
                .code(code)
                .discountType("percentage")
                .discountValue(BigDecimal.valueOf(20))
                .usageLimit(500)
                .expiresAt(Instant.parse("2026-12-31T00:00:00Z"))
                .build();
    }

    private static Campaign campaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Collection Launch")
                .status(CampaignStatus.ACTIVE)
                .currency("INR")
                .createdBy("brand_user")
                .build();
    }

    private static Workspace workspace() {
        return Workspace.newBrand(WORKSPACE_ID, "Nykaa Fashion", "nykaa-fashion", "Fashion", "SMB");
    }

    private static UtmCampaign utmCampaign() {
        return UtmCampaign.builder()
                .id("01HUTM12345678901234A")
                .campaignId(CAMPAIGN_ID)
                .collaborationId("01HCOLLAB12345678901")
                .creatorProfileId(CREATOR_PROFILE_A)
                .baseUrl("https://nykaa.com/summer")
                .fullTrackingUrl("https://nykaa.com/summer?utm_source=influora")
                .build();
    }
}
