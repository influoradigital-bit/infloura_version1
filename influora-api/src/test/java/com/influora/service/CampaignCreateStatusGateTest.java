package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import com.influora.web.dto.campaign.CampaignDtos.CampaignWriteRequest;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-0848 T1/T2 (wiki/tech/BUILD-PLAN-F0848-MEMORY-0917.md, lane F1). {@code POST /campaigns} with
 * {@code status=ACTIVE} used to save a live campaign that had paid no platform fee and secured no
 * funds. {@code create()} now accepts only DRAFT (or no status).
 *
 * <p>The workspace is stubbed VERIFIED and a FUNDED hold is stubbed on purpose: on the old code
 * those were the only things standing between an ACTIVE create and a saved ACTIVE row, so this
 * test goes RED there on the error code and on {@code save} being called — not on some unrelated
 * verification refusal. The fee service sits behind a REAL {@link CampaignActivationGuard}, so
 * "fee never charged" is asserted against the real call path.
 */
@ExtendWith(MockitoExtension.class)
class CampaignCreateStatusGateTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String USER_ID = "01HUSER1234567890123A";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private BrandContextService brandContext;
    @Mock private IntegrationHealthService integrationHealthService;
    @Mock private BrandCampaignFeeService brandCampaignFeeService;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;

    private CampaignService service;

    @BeforeEach
    void setUp() {
        service =
                new CampaignService(
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        brandContext,
                        new CampaignValidator(),
                        integrationHealthService,
                        new CampaignActivationGuard(escrowHoldRepository, brandCampaignFeeService));
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        // Lenient: only the old (vulnerable) code path reads these. They exist so the old code
        // would have happily saved an ACTIVE row, which is exactly what T1 must catch.
        lenient().when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);
        lenient().when(principal.getUserId()).thenReturn(USER_ID);
        lenient().when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient()
                .when(escrowHoldRepository.findByCampaignId(anyString()))
                .thenReturn(
                        List.of(
                                EscrowHold.builder()
                                        .id("01HESCROW1234567890AB")
                                        .status(EscrowStatus.FUNDED)
                                        .build()));
    }

    @Test
    @DisplayName(
            "T1 create(status=ACTIVE) -> 400 CAMPAIGN_CREATE_STATUS_NOT_ALLOWED, nothing saved, fee"
                    + " never charged")
    void createActiveIsRefused() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.create(principal, writeRequest(CampaignStatus.ACTIVE)));

        assertEquals("CAMPAIGN_CREATE_STATUS_NOT_ALLOWED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
        assertFalse(
                ex.getMessage().toLowerCase().contains("escrow"),
                "brand-facing text must not say escrow: " + ex.getMessage());
    }

    @ParameterizedTest
    @EnumSource(
            value = CampaignStatus.class,
            names = {"PENDING_APPROVAL", "PAUSED", "COMPLETED", "CANCELLED"})
    @DisplayName("T1 create() refuses every non-DRAFT status with the same code, nothing saved")
    void createAnyNonDraftStatusIsRefused(CampaignStatus status) {
        ApiException ex =
                assertThrows(ApiException.class, () -> service.create(principal, writeRequest(status)));

        assertEquals("CAMPAIGN_CREATE_STATUS_NOT_ALLOWED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName("T2 create() with no status -> saved as DRAFT, fee never charged")
    void createWithoutStatusSavesDraft() {
        CampaignResponse response = service.create(principal, writeRequest(null));

        ArgumentCaptor<Campaign> saved = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository, times(1)).save(saved.capture());
        assertEquals(CampaignStatus.DRAFT, saved.getValue().getStatus());
        assertEquals(CampaignStatus.DRAFT, response.status());
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName("T2 create() with explicit DRAFT -> saved as DRAFT, fee never charged")
    void createExplicitDraftSavesDraft() {
        service.create(principal, writeRequest(CampaignStatus.DRAFT));

        ArgumentCaptor<Campaign> saved = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository, times(1)).save(saved.capture());
        assertEquals(CampaignStatus.DRAFT, saved.getValue().getStatus());
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    private static CampaignWriteRequest writeRequest(CampaignStatus status) {
        return new CampaignWriteRequest(
                "Test Campaign Title",
                "description",
                null,
                status,
                CampaignIntentType.STANDARD,
                null,
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
                null,
                "Test Brand",
                "Test Category");
    }
}
