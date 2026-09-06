package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-0503 (CampaignService.java assignment) — real defect, fixed here.
 *
 * <p>The funded-escrow precondition for going ACTIVE used to be enforced ONLY on Meera's
 * {@code confirm_launch} tool ({@code ConfirmLaunchExecutor.doExecute}, which reads {@code
 * EscrowHold} rows fresh from the DB and requires >=1 {@code FUNDED} hold before flipping
 * status). A human {@code PATCH .../campaigns/{id}} setting {@code status=ACTIVE} went straight
 * through {@code CampaignService.update()} with no equivalent check — a brand could publish a
 * campaign with zero money secured. Fixed in {@code CampaignService.requireFundedEscrow}, called
 * at the same {@code transitioningToActive} edge the brand-publish-fee charge already uses.
 *
 * <p>See {@link DealServiceCollaboratorCapVerificationTest} in this same package for F-0400 — that
 * finding is ALREADY FIXED on this branch (both the write-edge validation in
 * {@code CampaignService.validateMaxCollaborators} and the read/accept-edge gate in {@code
 * DealService.requireWithinCollaboratorCap}), so no production change was made for it here; that
 * test class only verifies the existing accept-edge gate actually rejects the N+1th creator.
 */
@ExtendWith(MockitoExtension.class)
class CampaignActivationGatesTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private BrandContextService brandContext;
    @Mock private IntegrationHealthService integrationHealthService;
    @Mock private BrandCampaignFeeService brandCampaignFeeService;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;

    private CampaignService campaignService;

    @BeforeEach
    void setUp() {
        campaignService =
                new CampaignService(
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        brandContext,
                        new CampaignValidator(),
                        integrationHealthService,
                        brandCampaignFeeService);
    }

    @Test
    @DisplayName(
            "[F-0503] update(): human PATCH status=ACTIVE on a campaign with NO funded escrow -> 409"
                    + " ESCROW_NOT_FUNDED, nothing persisted, fee never charged (same contract Meera's"
                    + " confirm_launch already enforces)")
    void testHumanPatchToActiveRejectedWithoutFundedEscrow() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        // No escrow holds at all for this campaign.
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> campaignService.update(principal, CAMPAIGN_ID, activateRequest()));

        assertEquals("ESCROW_NOT_FUNDED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName(
            "[F-0503] update(): human PATCH status=ACTIVE on a campaign whose only escrow hold is"
                    + " PENDING (not yet FUNDED) -> 409 ESCROW_NOT_FUNDED — a non-FUNDED hold does not"
                    + " count")
    void testHumanPatchToActiveRejectedWithOnlyPendingEscrow() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        EscrowHold pending = escrowHold(EscrowStatus.PENDING);
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(pending));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> campaignService.update(principal, CAMPAIGN_ID, activateRequest()));

        assertEquals("ESCROW_NOT_FUNDED", ex.getCode());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName(
            "[F-0503] update(): human PATCH status=ACTIVE succeeds once a real FUNDED escrow hold"
                    + " exists for the campaign — the gate does not block legitimate activation")
    void testHumanPatchToActiveSucceedsWithFundedEscrow() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));
        EscrowHold funded = escrowHold(EscrowStatus.FUNDED);
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(funded));

        CampaignResponse response = campaignService.update(principal, CAMPAIGN_ID, activateRequest());

        assertEquals(CampaignStatus.ACTIVE, response.status());
        verify(campaignRepository).save(any(Campaign.class));
        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID));
    }

    private static Campaign draftCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Test Campaign")
                .status(CampaignStatus.DRAFT)
                .build();
    }

    private static CampaignPatchRequest activateRequest() {
        return new CampaignPatchRequest(
                null, null, null, CampaignStatus.ACTIVE, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static EscrowHold escrowHold(EscrowStatus status) {
        return EscrowHold.builder()
                .id("01HESCROW1234567890AB")
                .campaignId(CAMPAIGN_ID)
                .status(status)
                .build();
    }
}
