package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignIntent;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.CampaignIntentRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.meera.AICreditService;
import com.influora.service.meera.MeeraInteractionLogService;
import com.influora.service.meera.tool.ConfirmLaunchExecutor;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import com.influora.web.dto.meera.MeeraToolDtos.ConfirmLaunchResult;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-0848 (Priya ruling c) — {@link CampaignActivationGuard} unit behaviour, plus T3 "no double
 * charge" across BOTH real callers wired to ONE real guard over ONE mocked fee service.
 *
 * <p>Every T3 case asserts the {@code chargeOnPublish} CALL COUNT on the fee service, never just the
 * resulting status (a status assertion is green whether the fee was charged once or twice). What
 * the call count cannot prove is the ledger: a PAUSED -&gt; ACTIVE resume calls {@code
 * chargeOnPublish} a second time and relies on its one-time rule (nothing posted once the campaign
 * has paid) to post nothing new. That is proven in postings by {@code CampaignPublishFeeOnceTest}
 * and against MySQL by {@code CampaignActivationLedgerIntegrationTest} (skipped without Docker).
 *
 * <p>Lenient strictness: the orchestration cases share one wide stub set across two services.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignActivationGuardTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String INTENT_ID = "01HINTENT1234567890123";
    private static final String CONVERSATION_ID = "01HCONVO123456789012A";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private BrandContextService brandContext;
    @Mock private IntegrationHealthService integrationHealthService;
    @Mock private BrandCampaignFeeService brandCampaignFeeService;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;

    @Mock private CampaignIntentRepository campaignIntentRepository;
    @Mock private MeeraToolCallRepository toolCallRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private AICreditService aiCreditService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private MeeraInteractionLogService meeraInteractionLogService;

    private CampaignActivationGuard guard;
    private CampaignService campaignService;
    private ConfirmLaunchExecutor executor;

    /** Campaign id of every chargeOnPublish call, in order, plus the status it saw at call time. */
    private final List<String> chargedCampaignIds = new ArrayList<>();
    private final List<CampaignStatus> statusAtCharge = new ArrayList<>();

    @BeforeEach
    void setUp() {
        guard = new CampaignActivationGuard(escrowHoldRepository, brandCampaignFeeService);
        campaignService =
                new CampaignService(
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        brandContext,
                        new CampaignValidator(),
                        integrationHealthService,
                        guard);
        executor =
                new ConfirmLaunchExecutor(
                        campaignIntentRepository,
                        campaignRepository,
                        escrowHoldRepository,
                        toolCallRepository,
                        collaborationRepository,
                        creatorProfileRepository,
                        auditLogService,
                        aiCreditService,
                        idempotencyService,
                        guard,
                        meeraInteractionLogService,
                        null);

        when(brandCampaignFeeService.chargeOnPublish(any(Campaign.class), anyString()))
                .thenAnswer(
                        inv -> {
                            Campaign c = inv.getArgument(0);
                            chargedCampaignIds.add(c.getId());
                            statusAtCharge.add(c.getStatus());
                            return null;
                        });

        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------- guard unit behaviour

    @Test
    @DisplayName("guard: DRAFT with a FUNDED hold -> fee charged once BEFORE the status flips, then ACTIVE")
    void activateChargesOnceThenFlips() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        stubHolds(EscrowStatus.FUNDED);

        guard.activate(campaign, WORKSPACE_ID);

        verify(brandCampaignFeeService, times(1)).chargeOnPublish(campaign, WORKSPACE_ID);
        assertEquals(List.of(CampaignStatus.DRAFT), statusAtCharge);
        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
    }

    @Test
    @DisplayName("guard: no FUNDED hold -> 409 ESCROW_NOT_FUNDED, fee never charged, status unchanged")
    void activateRefusesWithoutFundedHold() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        stubHolds(EscrowStatus.PENDING);

        ApiException ex = assertThrows(ApiException.class, () -> guard.activate(campaign, WORKSPACE_ID));

        assertEquals("ESCROW_NOT_FUNDED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertTrue(!ex.getMessage().toLowerCase().contains("escrow"), ex.getMessage());
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
        assertEquals(CampaignStatus.DRAFT, campaign.getStatus());
    }

    @Test
    @DisplayName("guard: fee charge throws -> status is NOT flipped (caller's transaction rolls back)")
    void activateLeavesStatusWhenFeeFails() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        stubHolds(EscrowStatus.FUNDED);
        when(brandCampaignFeeService.chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID)))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH", "top up", HttpStatus.PAYMENT_REQUIRED));

        assertThrows(ApiException.class, () -> guard.activate(campaign, WORKSPACE_ID));

        assertEquals(CampaignStatus.DRAFT, campaign.getStatus());
    }

    @Test
    @DisplayName("guard: an already-ACTIVE campaign -> 409 CAMPAIGN_ALREADY_ACTIVE, fee never charged")
    void activateRefusesAlreadyActive() {
        Campaign campaign = campaign(CampaignStatus.ACTIVE);
        stubHolds(EscrowStatus.FUNDED);

        ApiException ex = assertThrows(ApiException.class, () -> guard.activate(campaign, WORKSPACE_ID));

        assertEquals("CAMPAIGN_ALREADY_ACTIVE", ex.getCode());
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName("guard: activate() is @Transactional(propagation = MANDATORY) — never runs outside the caller's transaction")
    void activateRequiresCallerTransaction() throws Exception {
        Method activate = CampaignActivationGuard.class.getMethod("activate", Campaign.class, String.class);
        Transactional tx = activate.getAnnotation(Transactional.class);
        assertTrue(tx != null, "activate() must be @Transactional");
        assertEquals(Propagation.MANDATORY, tx.propagation());
    }

    // ---------------------------------------------------------------- T3: no double charge

    @Test
    @DisplayName("T3(i) update DRAFT->ACTIVE then a no-op PATCH ACTIVE->ACTIVE -> chargeOnPublish called exactly once")
    void updateThenNoOpPatchChargesOnce() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        stubHolds(EscrowStatus.FUNDED);

        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.ACTIVE));
        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.ACTIVE));

        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), anyString());
        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
    }

    @Test
    @DisplayName(
            "T3(ii) update-activated campaign, then confirm_launch -> 409"
                    + " CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH and still exactly one chargeOnPublish")
    void updateThenConfirmLaunchDoesNotChargeAgain() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        stubHolds(EscrowStatus.FUNDED);
        stubIntent(campaign);
        when(toolCallRepository.existsByToolNameAndResultRefIdAndStatus(
                        MeeraToolName.confirm_launch, CAMPAIGN_ID, ToolCallStatus.EXECUTED))
                .thenReturn(false);

        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.ACTIVE));
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                executor.doExecute(
                                        WORKSPACE_ID,
                                        CONVERSATION_ID,
                                        "meera.tool.after-update",
                                        Map.of("campaign_intent_id", INTENT_ID)));

        assertEquals("CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH", ex.getCode());
        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName(
            "T3(iii) confirm_launch, then a replay under a different key -> clean no-op, exactly one"
                    + " chargeOnPublish")
    void confirmLaunchReplayDoesNotChargeAgain() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        stubHolds(EscrowStatus.FUNDED);
        stubIntent(campaign);

        ConfirmLaunchResult first =
                executor.doExecute(
                        WORKSPACE_ID, CONVERSATION_ID, "meera.tool.first", Map.of("campaign_intent_id", INTENT_ID));
        // The first launch wrote its EXECUTED row; the replay sees it.
        when(toolCallRepository.existsByToolNameAndResultRefIdAndStatus(
                        MeeraToolName.confirm_launch, CAMPAIGN_ID, ToolCallStatus.EXECUTED))
                .thenReturn(true);
        ConfirmLaunchResult replay =
                executor.doExecute(
                        WORKSPACE_ID, CONVERSATION_ID, "meera.tool.replay", Map.of("campaign_intent_id", INTENT_ID));

        assertEquals(false, first.replay());
        assertEquals(true, replay.replay());
        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName(
            "T3(iii-b) confirm_launch replayed with the SAME idempotency key via execute() -> answered"
                    + " from the tool-call ledger, chargeOnPublish never called")
    void confirmLaunchSameKeyReplayNeverCharges() {
        com.influora.domain.entity.MeeraToolCall prior =
                com.influora.domain.entity.MeeraToolCall.builder()
                        .id("01HTOOLCALL123456789A")
                        .workspaceId(WORKSPACE_ID)
                        .toolName(MeeraToolName.confirm_launch)
                        .idempotencyKey("meera.tool.same")
                        .status(ToolCallStatus.EXECUTED)
                        .resultRefType(com.influora.domain.enums.ToolResultRefType.CAMPAIGN)
                        .resultRefId(CAMPAIGN_ID)
                        .build();
        when(toolCallRepository.findByIdempotencyKey("meera.tool.same")).thenReturn(Optional.of(prior));

        ConfirmLaunchResult replay =
                executor.execute(
                        WORKSPACE_ID, CONVERSATION_ID, "meera.tool.same", Map.of("campaign_intent_id", INTENT_ID));

        assertEquals(true, replay.replay());
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName(
            "T3(iv) DRAFT->ACTIVE, PAUSE, resume PAUSED->ACTIVE -> chargeOnPublish called exactly twice,"
                    + " both for the same campaign id (the fee service's one-time rule posts nothing on the"
                    + " resume — proven in postings by CampaignPublishFeeOnceTest and the Testcontainers IT)")
    void pauseResumeCallCountIsUnchanged() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        stubHolds(EscrowStatus.FUNDED);

        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.ACTIVE));
        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.PAUSED));
        assertEquals(CampaignStatus.PAUSED, campaign.getStatus());
        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.ACTIVE));
        // A second no-op re-send after the resume must still not charge.
        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.ACTIVE));

        verify(brandCampaignFeeService, times(2)).chargeOnPublish(any(Campaign.class), anyString());
        assertEquals(List.of(CAMPAIGN_ID, CAMPAIGN_ID), chargedCampaignIds);
        assertEquals(List.of(CampaignStatus.DRAFT, CampaignStatus.PAUSED), statusAtCharge);
        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
    }

    @Test
    @DisplayName("update(): a no-op PATCH never reaches the guard, even with NO funded hold (it is not an activation)")
    void noOpPatchOnActiveNeedsNoFundsCheck() {
        Campaign campaign = campaign(CampaignStatus.ACTIVE);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        stubHolds(EscrowStatus.PENDING);

        campaignService.update(principal, CAMPAIGN_ID, statusPatch(CampaignStatus.ACTIVE));

        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
        verify(escrowHoldRepository, never()).findByCampaignId(anyString());
        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
        assertNotEquals(null, campaign.getStatus());
    }

    // ---------------------------------------------------------------- helpers

    private void stubHolds(EscrowStatus status) {
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(
                        List.of(
                                EscrowHold.builder()
                                        .id("01HESCROW1234567890AB")
                                        .workspaceId(WORKSPACE_ID)
                                        .campaignId(CAMPAIGN_ID)
                                        .amount(BigDecimal.valueOf(1000))
                                        .currency("INR")
                                        .status(status)
                                        .idempotencyKey("fund:" + CAMPAIGN_ID)
                                        .build()));
    }

    private void stubIntent(Campaign campaign) {
        CampaignIntent intent =
                CampaignIntent.builder()
                        .id(INTENT_ID)
                        .conversationId(CONVERSATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignType(CampaignIntentType.STANDARD)
                        .creatorCount(0)
                        .build();
        intent.confirm(CAMPAIGN_ID);
        when(campaignIntentRepository.findByIdAndWorkspaceId(INTENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(intent));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
    }

    private static Campaign campaign(CampaignStatus status) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Test Campaign")
                .status(status)
                .budgetMax(BigDecimal.valueOf(10000))
                .build();
    }

    private static CampaignPatchRequest statusPatch(CampaignStatus status) {
        return new CampaignPatchRequest(
                null, null, null, status, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
