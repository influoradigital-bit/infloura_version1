package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.CreatorApplicationStatus;
import com.influora.domain.enums.ReviewerType;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminMarketingDtos.GrowthMetricsDto;
import com.influora.web.dto.admin.AdminMarketingDtos.PlatformReputationScoreDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link AdminMarketingService#getReputation} and {@link
 * AdminMarketingService#getGrowth} — the admin marketing/analytics console ({@code
 * GET /admin/marketing/reputation}, {@code GET /admin/marketing/growth}). Mocks every repository,
 * so this proves the service's mapping contract (role gate genuinely invoked, null/zero aggregates
 * coalesce honestly, live figures map to the right DTO field) rather than the repositories' own
 * SQL/JPQL.
 */
@ExtendWith(MockitoExtension.class)
class AdminMarketingServiceTest {

    @Mock private AdminContextService adminContext;
    @Mock private ReviewRepository reviewRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private AuthPrincipal principal;

    private AdminMarketingService service() {
        return new AdminMarketingService(
                adminContext,
                reviewRepository,
                disputeRepository,
                userRepository,
                workspaceRepository,
                campaignRepository,
                creatorProfileRepository);
    }

    @Test
    @DisplayName("reputation: gates on SUPER_ADMIN+ADMIN and maps live review/dispute aggregates")
    void getReputation_gatesAndMapsLiveFigures() {
        when(reviewRepository.avgStarsByReviewerType(ReviewerType.BRAND)).thenReturn(4.5);
        when(reviewRepository.avgStarsByReviewerType(ReviewerType.CREATOR)).thenReturn(4.2);
        when(reviewRepository.avgAllStars()).thenReturn(4.35);
        when(disputeRepository.avgResolutionSeconds()).thenReturn(7200.0); // 2h

        PlatformReputationScoreDto dto = service().getReputation(principal);

        verify(adminContext)
                .requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        assertEquals(4.35, dto.overall(), 1e-9);
        assertEquals(4.5, dto.creatorQualityAvg(), 1e-9);
        assertEquals(4.2, dto.brandSatisfactionAvg(), 1e-9);
        assertEquals(2.0, dto.disputeResolutionSpeed(), 1e-9);
    }

    @Test
    @DisplayName("reputation: null aggregates (no rows yet) coalesce to 0.0, never NPE or fabricated")
    void getReputation_nullAggregatesDefaultToZero() {
        when(reviewRepository.avgStarsByReviewerType(any())).thenReturn(null);
        when(reviewRepository.avgAllStars()).thenReturn(null);
        when(disputeRepository.avgResolutionSeconds()).thenReturn(null);

        PlatformReputationScoreDto dto = service().getReputation(principal);

        assertEquals(0.0, dto.overall(), 1e-9);
        assertEquals(0.0, dto.creatorQualityAvg(), 1e-9);
        assertEquals(0.0, dto.brandSatisfactionAvg(), 1e-9);
        assertEquals(0.0, dto.disputeResolutionSpeed(), 1e-9);
    }

    @Test
    @DisplayName("growth: gates on SUPER_ADMIN+ADMIN and maps live funnel + conversion figures")
    void getGrowth_gatesAndMapsLiveFigures() {
        when(userRepository.count()).thenReturn(500L);
        when(campaignRepository.countBrandWorkspacesWithCampaign()).thenReturn(80L);
        when(campaignRepository.countBrandWorkspacesWithRepeatCampaigns()).thenReturn(30L);
        when(creatorProfileRepository.count()).thenReturn(200L);
        when(creatorProfileRepository.countByApplicationStatus(CreatorApplicationStatus.APPROVED))
                .thenReturn(150L);
        when(workspaceRepository.countByType(WorkspaceType.BRAND)).thenReturn(100L);

        GrowthMetricsDto dto = service().getGrowth(principal);

        verify(adminContext)
                .requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        assertEquals(500L, dto.funnel().signups());
        assertEquals(80L, dto.funnel().firstCampaign());
        assertEquals(30L, dto.funnel().repeatCampaign());
        assertEquals(0.75, dto.conversionRates().creatorApplicationToApproval(), 1e-9); // 150/200
        assertEquals(0.8, dto.conversionRates().brandSignupToFirstCampaign(), 1e-9); // 80/100
    }

    @Test
    @DisplayName("growth: zero denominators (no applications, no brands) coalesce to 0.0, never divide-by-zero")
    void getGrowth_zeroDenominatorsDefaultToZero() {
        when(userRepository.count()).thenReturn(0L);
        when(campaignRepository.countBrandWorkspacesWithCampaign()).thenReturn(0L);
        when(campaignRepository.countBrandWorkspacesWithRepeatCampaigns()).thenReturn(0L);
        when(creatorProfileRepository.count()).thenReturn(0L);
        when(workspaceRepository.countByType(WorkspaceType.BRAND)).thenReturn(0L);

        GrowthMetricsDto dto = service().getGrowth(principal);

        assertEquals(0L, dto.funnel().signups());
        assertEquals(0.0, dto.conversionRates().creatorApplicationToApproval(), 1e-9);
        assertEquals(0.0, dto.conversionRates().brandSignupToFirstCampaign(), 1e-9);
        verify(creatorProfileRepository, never())
                .countByApplicationStatus(CreatorApplicationStatus.APPROVED);
    }

    @Test
    @DisplayName("growth: an unauthorized (e.g. SUPPORT) caller is blocked BEFORE any repository read")
    void getGrowth_unauthorizedCallerReadsNothing() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_ROLE",
                                "SUPPORT may not view marketing growth",
                                HttpStatus.FORBIDDEN));

        assertThrows(ApiException.class, () -> service().getGrowth(principal));

        verify(userRepository, never()).count();
        verify(campaignRepository, never()).countBrandWorkspacesWithCampaign();
        verify(workspaceRepository, never()).countByType(any());
    }
}
