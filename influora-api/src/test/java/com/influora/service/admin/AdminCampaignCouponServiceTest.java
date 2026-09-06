package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.tracking.CampaignTrackingService;
import com.influora.web.dto.tracking.TrackingDtos.CouponResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests for the admin-scoped coupon issuing path (T-FESTIVALBOX-0905 phase 11).
 *
 * <p><b>Why this endpoint exists at all</b>, which is what most of these tests are really pinning:
 * the brand-facing {@code CampaignTrackingController} resolves its workspace through {@code
 * BrandContextService#requireBrandWorkspace}, which rejects any principal whose {@code userType} is
 * not {@code BRAND}. Admin JWTs are {@code ADMIN} and this codebase has no impersonation, so before
 * {@link AdminCampaignCouponService} an admin could not register a sponsor's coupons at all — and
 * with no coupon registered, no redemption can ever attribute, so the whole Festival Box tracking
 * chain was inert in practice.
 *
 * <p>{@code adminContext} is stubbed to pass RBAC in the tests that get that far; these are about
 * the delegation and scoping logic, not about {@code AdminContextService} (which has its own
 * tests). The one exception is {@link #rbacIsCheckedBeforeAnythingElse}, which is specifically
 * about the gate.
 */
@ExtendWith(MockitoExtension.class)
class AdminCampaignCouponServiceTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CREATOR_ID = "01HCREATORPROFILE1234";
    private static final String COUPON_ID = "01HCOUPON1234567890AB";

    @Mock private AdminContextService adminContext;
    @Mock private AdminAuditLogService adminAuditLogService;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignTrackingService campaignTrackingService;

    private AdminCampaignCouponService service;
    private AuthPrincipal principal;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        service =
                new AdminCampaignCouponService(
                        adminContext, adminAuditLogService, campaignRepository, campaignTrackingService);
        principal = new AuthPrincipal("01HADMIN00000000000001", "admin@influora.in", UserType.ADMIN, null);
        httpRequest = new MockHttpServletRequest();
    }

    // ------------------------------------------------------------------------------------------
    // Scoping — the whole point of this service
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "issue: resolves the workspace FROM THE CAMPAIGN ROW, so an admin cannot supply a"
                    + " mismatched (campaign, workspace) pair — there is no such parameter")
    void workspaceIsResolvedFromTheCampaignNotTheCaller() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignTrackingService.createCoupon(
                        anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(couponResponse(null, "FEST10"));

        service.issue(
                principal, httpRequest, CAMPAIGN_ID, null, "percentage", BigDecimal.TEN, null, null);

        // The workspace handed to the shared service is the one ON the campaign row. Nothing the
        // admin sent could have influenced it.
        verify(campaignTrackingService)
                .createCoupon(
                        eq(WORKSPACE_ID), eq(CAMPAIGN_ID), isNull(), eq("percentage"), eq(BigDecimal.TEN),
                        isNull(), isNull());
    }

    @Test
    @DisplayName("issue: an unknown campaign is CAMPAIGN_NOT_FOUND and issues nothing")
    void unknownCampaignIsRejected() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.issue(
                                        principal, httpRequest, CAMPAIGN_ID, null, "percentage",
                                        BigDecimal.TEN, null, null));

        assertEquals("CAMPAIGN_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(campaignTrackingService);
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    // ------------------------------------------------------------------------------------------
    // Brand-level vs per-creator — the branch that decides what can be attributed
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("issue: a blank creatorProfileId is passed through as-is — the brand-level branch"
            + " belongs to CampaignTrackingService, and is not re-implemented here")
    void blankCreatorIsPassedThroughUntouched() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignTrackingService.createCoupon(
                        anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(couponResponse(null, "FEST10"));

        service.issue(
                principal, httpRequest, CAMPAIGN_ID, "   ", "percentage", BigDecimal.TEN, null, null);

        // Deliberately NOT normalised to null here. One owner for that rule; duplicating it would
        // create a second place for the brand-level decision to drift.
        verify(campaignTrackingService)
                .createCoupon(anyString(), anyString(), eq("   "), any(), any(), any(), any());
    }

    @Test
    @DisplayName("issue: a per-creator coupon delegates with the creator id intact")
    void perCreatorCouponDelegatesWithCreator() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignTrackingService.createCoupon(
                        anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(couponResponse(CREATOR_ID, "PRIYA15"));

        CouponResponse response =
                service.issue(
                        principal, httpRequest, CAMPAIGN_ID, CREATOR_ID, "percentage",
                        BigDecimal.valueOf(15), 100, Instant.parse("2026-12-31T00:00:00Z"));

        assertEquals(CREATOR_ID, response.creatorProfileId());
        verify(campaignTrackingService)
                .createCoupon(
                        eq(WORKSPACE_ID), eq(CAMPAIGN_ID), eq(CREATOR_ID), eq("percentage"),
                        eq(BigDecimal.valueOf(15)), eq(100), eq(Instant.parse("2026-12-31T00:00:00Z")));
    }

    @Test
    @DisplayName("issue: BRAND_CODE_EXISTS from the shared service propagates unchanged — the admin"
            + " must learn the campaign already has a brand-level code, not see it softened")
    void brandCodeExistsPropagates() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignTrackingService.createCoupon(
                        anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(
                        new ApiException(
                                "BRAND_CODE_EXISTS",
                                "This campaign already has a brand-level coupon",
                                HttpStatus.CONFLICT));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.issue(
                                        principal, httpRequest, CAMPAIGN_ID, null, "percentage",
                                        BigDecimal.TEN, null, null));

        assertEquals("BRAND_CODE_EXISTS", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    // ------------------------------------------------------------------------------------------
    // Audit
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("issue: audit-logs the issued CODE and the brandLevel flag — a coupon is a"
            + " money-bearing artifact and the trail must answer 'which code, issued by whom'")
    void auditRecordsTheCodeAndScope() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignTrackingService.createCoupon(
                        anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(couponResponse(null, "FEST10"));

        service.issue(
                principal, httpRequest, CAMPAIGN_ID, null, "percentage", BigDecimal.TEN, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(adminAuditLogService)
                .record(
                        eq(principal), eq(httpRequest), eq("CREATE"), eq("CAMPAIGN_COUPON"),
                        eq(COUPON_ID), isNull(), after.capture(), isNull());

        Map<String, Object> detail = after.getValue();
        assertEquals("FEST10", detail.get("code"));
        assertEquals(CAMPAIGN_ID, detail.get("campaignId"));
        assertEquals(WORKSPACE_ID, detail.get("workspaceId"));
        assertEquals(Boolean.TRUE, detail.get("brandLevel"));
    }

    @Test
    @DisplayName("issue: a per-creator coupon is audited with brandLevel=false")
    void auditMarksPerCreatorAsNotBrandLevel() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignTrackingService.createCoupon(
                        anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(couponResponse(CREATOR_ID, "PRIYA15"));

        service.issue(
                principal, httpRequest, CAMPAIGN_ID, CREATOR_ID, "percentage", BigDecimal.TEN, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(adminAuditLogService)
                .record(any(), any(), anyString(), anyString(), anyString(), isNull(), after.capture(), isNull());

        assertEquals(Boolean.FALSE, after.getValue().get("brandLevel"));
    }

    // ------------------------------------------------------------------------------------------
    // RBAC
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("issue: the role gate runs BEFORE the campaign is even looked up — a non-admin"
            + " cannot use this endpoint to probe which campaign ids exist")
    void rbacIsCheckedBeforeAnythingElse() {
        org.mockito.Mockito.doThrow(
                        new ApiException("FORBIDDEN", "Admin role required", HttpStatus.FORBIDDEN))
                .when(adminContext)
                .requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        assertThrows(
                ApiException.class,
                () ->
                        service.issue(
                                principal, httpRequest, CAMPAIGN_ID, null, "percentage",
                                BigDecimal.TEN, null, null));

        verifyNoInteractions(campaignRepository, campaignTrackingService, adminAuditLogService);
    }

    @Test
    @DisplayName("list: also role-gated, and scoped to the campaign's own workspace")
    void listIsGatedAndScoped() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignTrackingService.listCoupons(WORKSPACE_ID, CAMPAIGN_ID))
                .thenReturn(List.of(couponResponse(CREATOR_ID, "PRIYA15")));

        List<CouponResponse> rows = service.list(principal, CAMPAIGN_ID);

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).code().equals("PRIYA15"));
        verify(adminContext).requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        verify(campaignTrackingService).listCoupons(WORKSPACE_ID, CAMPAIGN_ID);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private static Campaign campaign() {
        return Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).title("Festival Box").build();
    }

    private static CouponResponse couponResponse(String creatorProfileId, String code) {
        return new CouponResponse(
                COUPON_ID,
                CAMPAIGN_ID,
                creatorProfileId,
                code,
                "percentage",
                BigDecimal.TEN,
                null,
                0,
                null,
                Instant.now());
    }
}
