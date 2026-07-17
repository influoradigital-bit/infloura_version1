package com.influora.service.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.UtmCampaign;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.web.dto.tracking.TrackingDtos.CouponResponse;
import com.influora.web.dto.tracking.TrackingDtos.TrackingLinkResponse;
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

/**
 * Unit tests for {@link CampaignTrackingService} — the REST-layer service backing {@code
 * CampaignTrackingController} (Wave A, task A1). {@code createTrackingLink}/{@code createCoupon}
 * are thin delegation + DTO-mapping over the already-tested {@code CampaignLinkService}/{@code
 * CouponCodeService}, so those tests focus on correct delegation and mapping. The two list methods
 * are NEW authorization surface added by this task — {@link #testListTrackingLinksRejectsOtherWorkspace}
 * and {@link #testListCouponsReturnsEmptyForOtherWorkspace} are the load-bearing proofs that a
 * brand cannot list another workspace's campaign links/coupons (Kabir's review focus per the task
 * brief).
 */
@ExtendWith(MockitoExtension.class)
class CampaignTrackingServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HOTHERWORKSPACE1234";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_ID = "01HCREATORPROFILE1234";
    private static final String UTM_ID = "01HUTM12345678901234A";
    private static final String COUPON_ID = "01HCOUPON1234567890AB";

    @Mock private CampaignLinkService campaignLinkService;
    @Mock private CouponCodeService couponCodeService;
    @Mock private CampaignRepository campaignRepository;
    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private CouponCodeRepository couponCodeRepository;

    private CampaignTrackingService service;

    @BeforeEach
    void setUp() {
        service =
                new CampaignTrackingService(
                        campaignLinkService,
                        couponCodeService,
                        campaignRepository,
                        utmCampaignRepository,
                        couponCodeRepository);
    }

    // ------------------------------------------------------------------
    // createTrackingLink: delegation + mapping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createTrackingLink: delegates to CampaignLinkService and maps the result to a DTO")
    void testCreateTrackingLinkDelegatesAndMaps() {
        UtmCampaign utm = utmCampaign();
        when(campaignLinkService.createTrackingLink(
                        WORKSPACE_ID, CAMPAIGN_ID, COLLAB_ID, CREATOR_ID, "https://example.com", "instagram"))
                .thenReturn(utm);

        TrackingLinkResponse response =
                service.createTrackingLink(
                        WORKSPACE_ID, CAMPAIGN_ID, COLLAB_ID, CREATOR_ID, "https://example.com", "instagram");

        assertEquals(UTM_ID, response.id());
        assertEquals(CAMPAIGN_ID, response.campaignId());
        assertEquals(COLLAB_ID, response.collaborationId());
        assertEquals(CREATOR_ID, response.creatorProfileId());
        assertEquals("https://example.com?utm_source=instagram", response.fullTrackingUrl());
        assertEquals(0L, response.clickCount());
        assertEquals(0L, response.uniqueVisitors());
        // Delegation means CampaignTrackingService itself never touches the repositories for this path
        // — all authorization already happened inside CampaignLinkService.
        verifyNoInteractions(campaignRepository, utmCampaignRepository);
    }

    @Test
    @DisplayName("createTrackingLink: propagates CampaignLinkService's workspace-ownership rejection unchanged")
    void testCreateTrackingLinkPropagatesRejection() {
        when(campaignLinkService.createTrackingLink(
                        eq(OTHER_WORKSPACE_ID), eq(CAMPAIGN_ID), any(), any(), any(), any()))
                .thenThrow(
                        new ApiException(
                                "CAMPAIGN_NOT_FOUND",
                                "Campaign not found",
                                org.springframework.http.HttpStatus.NOT_FOUND));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTrackingLink(
                                        OTHER_WORKSPACE_ID,
                                        CAMPAIGN_ID,
                                        COLLAB_ID,
                                        CREATOR_ID,
                                        "https://example.com",
                                        "instagram"));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
    }

    // ------------------------------------------------------------------
    // listTrackingLinks: workspace authorization (load-bearing)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTrackingLinks: resolves the campaign against the caller's workspace FIRST, then lists")
    void testListTrackingLinksHappyPath() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(utmCampaignRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(utmCampaign()));

        List<TrackingLinkResponse> links = service.listTrackingLinks(WORKSPACE_ID, CAMPAIGN_ID);

        assertEquals(1, links.size());
        assertEquals(UTM_ID, links.get(0).id());
    }

    @Test
    @DisplayName(
            "listTrackingLinks: a brand CANNOT list another workspace's campaign's tracking links —"
                    + " CAMPAIGN_NOT_FOUND, and the UTM table is never even queried")
    void testListTrackingLinksRejectsOtherWorkspace() {
        // Workspace A owns the campaign; workspace B (the caller here) does not.
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, OTHER_WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.listTrackingLinks(OTHER_WORKSPACE_ID, CAMPAIGN_ID));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        // The load-bearing proof: listing never even reaches the UTM repository once the
        // workspace-ownership check fails, so there is no path to leak another workspace's rows.
        verifyNoInteractions(utmCampaignRepository);
    }

    @Test
    @DisplayName("listTrackingLinks: returns an empty list, not an error, when the campaign has no tracking links yet")
    void testListTrackingLinksEmptyWhenNoLinks() {
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign()));
        when(utmCampaignRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

        List<TrackingLinkResponse> links = service.listTrackingLinks(WORKSPACE_ID, CAMPAIGN_ID);

        assertTrue(links.isEmpty());
    }

    // ------------------------------------------------------------------
    // createCoupon: delegation + mapping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createCoupon: delegates to CouponCodeService and maps the result to a DTO")
    void testCreateCouponDelegatesAndMaps() {
        CouponCode coupon = couponCode();
        when(couponCodeService.addCreatorToCampaign(
                        WORKSPACE_ID, CAMPAIGN_ID, CREATOR_ID, "percentage", BigDecimal.valueOf(15), 100, null))
                .thenReturn(coupon);

        CouponResponse response =
                service.createCoupon(
                        WORKSPACE_ID, CAMPAIGN_ID, CREATOR_ID, "percentage", BigDecimal.valueOf(15), 100, null);

        assertEquals(COUPON_ID, response.id());
        assertEquals(CAMPAIGN_ID, response.campaignId());
        assertEquals(CREATOR_ID, response.creatorProfileId());
        assertEquals("PRIYA-SHARMA_SUMMER25", response.code());
        assertEquals("percentage", response.discountType());
        assertEquals(BigDecimal.valueOf(15), response.discountValue());
        assertEquals(100, response.usageLimit());
        verifyNoInteractions(couponCodeRepository);
    }

    // ------------------------------------------------------------------
    // listCoupons: workspace authorization (load-bearing)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listCoupons: lists coupons scoped directly by workspaceId + campaignId")
    void testListCouponsHappyPath() {
        when(couponCodeRepository.findByWorkspaceIdAndCampaignId(WORKSPACE_ID, CAMPAIGN_ID))
                .thenReturn(List.of(couponCode()));

        List<CouponResponse> coupons = service.listCoupons(WORKSPACE_ID, CAMPAIGN_ID);

        assertEquals(1, coupons.size());
        assertEquals(COUPON_ID, coupons.get(0).id());
        verify(couponCodeRepository).findByWorkspaceIdAndCampaignId(WORKSPACE_ID, CAMPAIGN_ID);
    }

    @Test
    @DisplayName(
            "listCoupons: a brand CANNOT list another workspace's campaign's coupons — the"
                    + " workspace-scoped finder returns nothing for a workspace that doesn't own the campaign")
    void testListCouponsReturnsEmptyForOtherWorkspace() {
        // Coupons genuinely exist for CAMPAIGN_ID, but only under WORKSPACE_ID (the real owner) —
        // OTHER_WORKSPACE_ID querying the same campaignId must get nothing back.
        when(couponCodeRepository.findByWorkspaceIdAndCampaignId(OTHER_WORKSPACE_ID, CAMPAIGN_ID))
                .thenReturn(List.of());

        List<CouponResponse> coupons = service.listCoupons(OTHER_WORKSPACE_ID, CAMPAIGN_ID);

        assertTrue(coupons.isEmpty());
        // Proves the isolation is enforced via the query parameters themselves, not a coincidence —
        // the real workspace's coupon row is never returned to the wrong caller.
        verify(couponCodeRepository, never()).findByWorkspaceIdAndCampaignId(WORKSPACE_ID, CAMPAIGN_ID);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Campaign campaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Sale 2026")
                .createdBy("brandUser")
                .build();
    }

    private static UtmCampaign utmCampaign() {
        return UtmCampaign.builder()
                .id(UTM_ID)
                .campaignId(CAMPAIGN_ID)
                .collaborationId(COLLAB_ID)
                .creatorProfileId(CREATOR_ID)
                .baseUrl("https://example.com")
                .utmSource("instagram")
                .utmMedium("influencer")
                .utmCampaign("summer-sale-2026")
                .utmContent("priya-sharma")
                .fullTrackingUrl("https://example.com?utm_source=instagram")
                .build();
    }

    private static CouponCode couponCode() {
        return CouponCode.builder()
                .id(COUPON_ID)
                .workspaceId(WORKSPACE_ID)
                .campaignId(CAMPAIGN_ID)
                .creatorId(CREATOR_ID)
                .code("PRIYA-SHARMA_SUMMER25")
                .discountType("percentage")
                .discountValue(BigDecimal.valueOf(15))
                .usageLimit(100)
                .expiresAt(Instant.parse("2026-12-31T00:00:00Z"))
                .build();
    }
}
