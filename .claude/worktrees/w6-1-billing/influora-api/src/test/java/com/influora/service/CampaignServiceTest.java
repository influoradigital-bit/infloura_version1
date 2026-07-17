package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import com.influora.web.dto.campaign.CampaignDtos.CampaignWriteRequest;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Wave D task D3 follow-up (wiki/decisions/2026-07-07-d3-campaign-gating-scope.md): {@code
 * CampaignService.create} — the human REST campaign-creation path — must reject a {@code DIRECT}
 * ("sale"/conversion-shaped) campaign when the workspace has no active store integration, mirroring
 * {@code CreateCampaignExecutorTest}'s coverage of the AI-drafted path. Both paths share the same
 * {@link IntegrationHealthService#requiresStoreIntegration} predicate and the identical typed
 * {@code 409 NO_STORE_INTEGRATION} error.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String USER_ID = "01HUSER123456789012AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";

    @Mock private CampaignRepository campaignRepository;
    @Mock private BrandContextService brandContext;
    @Mock private IntegrationHealthService integrationHealthService;
    @Mock private BrandCampaignFeeService brandCampaignFeeService;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;

    private CampaignService service;

    @BeforeEach
    void setUp() {
        // Real CampaignValidator: no dependencies of its own, and default DRAFT status never
        // trips its ACTIVE-only verification check, so a mock would add nothing but noise.
        service =
                new CampaignService(
                        campaignRepository,
                        brandContext,
                        new CampaignValidator(),
                        integrationHealthService,
                        brandCampaignFeeService);
    }

    @Test
    @DisplayName("DIRECT (sale) campaign with no active store integration -> 409 NO_STORE_INTEGRATION, nothing persisted")
    void testDirectCampaignRejectedWithoutStoreIntegration() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(integrationHealthService.hasActiveStoreIntegration(WORKSPACE_ID)).thenReturn(false);

        CampaignWriteRequest req = writeRequest(CampaignIntentType.DIRECT);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.create(principal, req));

        assertEquals("NO_STORE_INTEGRATION", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("DIRECT (sale) campaign with an active store integration -> succeeds, campaign created")
    void testDirectCampaignSucceedsWithStoreIntegration() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(integrationHealthService.hasActiveStoreIntegration(WORKSPACE_ID)).thenReturn(true);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignWriteRequest req = writeRequest(CampaignIntentType.DIRECT);

        var response = service.create(principal, req);

        assertNotNull(response);
        verify(campaignRepository).save(any(Campaign.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HYPE", "REVIEW", "STANDARD"})
    @DisplayName("Non-store-dependent campaign types succeed even with zero integrations connected")
    void testNonStoreDependentTypesIgnoreIntegrationStatus(String campaignType) {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignWriteRequest req = writeRequest(CampaignIntentType.valueOf(campaignType));

        var response = service.create(principal, req);

        assertNotNull(response);
        // The integration health check must never even be consulted for a non-DIRECT type.
        verify(integrationHealthService, never()).hasActiveStoreIntegration(WORKSPACE_ID);
        verify(campaignRepository).save(any(Campaign.class));
    }

    @Test
    @DisplayName("Null/unspecified campaign type (legacy/untyped request) -> not gated, succeeds regardless of integration status")
    void testNullCampaignTypeIsNotGated() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignWriteRequest req = writeRequest(null);

        var response = service.create(principal, req);

        assertNotNull(response);
        verify(integrationHealthService, never()).hasActiveStoreIntegration(WORKSPACE_ID);
        verify(campaignRepository).save(any(Campaign.class));
    }

    // --- update() / B1 brand-fee-on-publish gating (Kabir fix 1 + Kavya blocking-test-coverage) ---

    @Test
    @DisplayName("update(): DRAFT -> ACTIVE charges the brand publish fee exactly once")
    void testUpdateDraftToActiveChargesFeeOnce() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(principal, CAMPAIGN_ID, activateRequest());

        assertNotNull(response);
        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID));
        verify(campaignRepository).save(any(Campaign.class));
    }

    @Test
    @DisplayName("update(): PAUSED -> ACTIVE (resume) also charges the fee")
    void testUpdatePausedToActiveChargesFee() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.PAUSED);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(principal, CAMPAIGN_ID, activateRequest());

        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID));
    }

    @Test
    @DisplayName("update(): ACTIVE -> ACTIVE (no real transition) is a no-op — fee not re-charged")
    void testUpdateActiveToActiveDoesNotRechargeFee() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.ACTIVE);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(principal, CAMPAIGN_ID, activateRequest());

        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName("update(): insufficient wallet balance throws 402 and the status change is never persisted")
    void testUpdateInsufficientBalanceRollsBack() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(brandCampaignFeeService.chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID)))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH",
                                "Insufficient wallet balance",
                                HttpStatus.PAYMENT_REQUIRED));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.update(principal, CAMPAIGN_ID, activateRequest()));

        assertEquals("INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH", ex.getCode());
        assertEquals(402, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("update(): any exception from the fee charge rolls back the whole PATCH")
    void testUpdateGenericFeeChargeExceptionRollsBack() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(brandCampaignFeeService.chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID)))
                .thenThrow(new RuntimeException("ledger unavailable"));

        assertThrows(
                RuntimeException.class, () -> service.update(principal, CAMPAIGN_ID, activateRequest()));
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    private static Campaign activatableCampaign(CampaignStatus status) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Test Campaign")
                .status(status)
                .build();
    }

    private static CampaignPatchRequest activateRequest() {
        return new CampaignPatchRequest(
                null, null, null, CampaignStatus.ACTIVE, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static CampaignWriteRequest writeRequest(CampaignIntentType campaignType) {
        return new CampaignWriteRequest(
                "Test Campaign Title",
                "description",
                null,
                null,
                campaignType,
                new BudgetDto(BigDecimal.TEN, BigDecimal.valueOf(100), "INR"),
                new TimelineDto(LocalDate.now().plusDays(1), LocalDate.now().plusDays(30)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
