package com.influora.service.meera.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignIntent;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MeeraToolName;
import com.influora.domain.enums.ToolCallStatus;
import com.influora.repository.CampaignIntentRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.MeeraToolCallRepository;
import com.influora.service.AuditLogService;
import com.influora.service.BrandCampaignFeeService;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.AICreditService;
import com.influora.web.dto.meera.MeeraToolDtos.ConfirmLaunchResult;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/**
 * Kabir red-team fix 1 (CRITICAL, 2026-07-13) + Kavya blocking-test-coverage: {@code
 * ConfirmLaunchExecutor.doExecute} must gate invite/bind/credit-reset on the SAME real-transition
 * check that gates the brand publish fee — a repeat confirm_launch against an already-ACTIVE
 * campaign (a different tool_use.id, so {@code IdempotencyService.executeOnce}'s own dedupe never
 * sees it) must be a clean no-op, not a partial re-run of invites (risking exceeding {@code
 * creator_count}), an AI-credit reset (free unlimited-AI-credits abuse), or a second fee charge.
 *
 * <p><b>Kabir red-team fix CRITICAL-1 (2026-07-14):</b> {@code BrandCampaignFeeService} was
 * previously removed from this class's constructor entirely ("P3-20") on the mistaken assumption
 * that {@code CampaignService} covered the AI-launch path too — it does not, since {@code
 * ConfirmLaunchExecutor.doExecute} never calls into {@code CampaignService}. The result was a
 * complete brand-fee bypass on every campaign launched via Meera's {@code confirm_launch} tool.
 * {@code BrandCampaignFeeService} is now reinjected and {@code doExecute} charges the fee at the
 * real DRAFT/PAUSED/PENDING_APPROVAL -> ACTIVE transition, mirroring {@code
 * CampaignService.update()}'s charge-then-save pattern exactly (see {@code
 * testRealTransitionRunsAllSideEffectsExactlyOnce} and {@code
 * testFeeChargeFailureRollsBackWholeLaunch} below).
 *
 * <p>Tests exercise {@code doExecute} directly (bypassing {@code execute}'s
 * idempotency-service/self-proxy wrapper, which is not this class's concern) since all of the
 * business logic under test lives there.
 */
@ExtendWith(MockitoExtension.class)
class ConfirmLaunchExecutorTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CONVERSATION_ID = "01HCONVO123456789012A";
    private static final String IDEMPOTENCY_KEY = "meera.tool.01HTOOLUSE1234567890A";
    private static final String INTENT_ID = "01HINTENT1234567890123";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";

    @Mock private CampaignIntentRepository campaignIntentRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private MeeraToolCallRepository toolCallRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private AICreditService aiCreditService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private BrandCampaignFeeService brandCampaignFeeService;

    private ConfirmLaunchExecutor executor;

    @BeforeEach
    void setUp() {
        // `self` (the @Lazy AOP self-proxy) is only dereferenced inside execute(); doExecute() —
        // the method under test here — never touches it, so null is safe.
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
                        brandCampaignFeeService,
                        null);
    }

    @Test
    @DisplayName(
            "Real DRAFT -> ACTIVE transition: charges the fee once, invites creators, binds funded"
                    + " holds, resets AI credits once, and reads via the locked campaign load")
    void testRealTransitionRunsAllSideEffectsExactlyOnce() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        CampaignIntent intent = confirmedIntent(2);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();

        CreatorProfile creator1 = mockDiscoverableCreator("creator-1");
        CreatorProfile creator2 = mockDiscoverableCreator("creator-2");
        when(creatorProfileRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(creator1, creator2));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(eq(CAMPAIGN_ID), anyString()))
                .thenReturn(false);
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ConfirmLaunchResult result =
                executor.doExecute(
                        WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, Map.of("campaign_intent_id", INTENT_ID));

        assertNotNull(result);
        assertEquals(CampaignStatus.ACTIVE.name(), result.status());
        assertEquals(2, result.creatorsInvited());
        assertFalse(result.replay());

        // [Kabir red-team CRITICAL-1 fix] the brand publish fee is charged exactly once, for this
        // campaign/workspace, on the real DRAFT -> ACTIVE transition.
        verify(brandCampaignFeeService, times(1)).chargeOnPublish(campaign, WORKSPACE_ID);
        verify(aiCreditService, times(1)).applyEscrowFundedReset(eq(WORKSPACE_ID), any(Instant.class));
        verify(collaborationRepository, times(2)).save(any(Collaboration.class));

        // [SEC: Kabir fix 2a] the workspace-scoped load must be used, not an unscoped lookup.
        verify(campaignRepository).findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID);
        verify(campaignRepository, never()).findByIdForUpdate(anyString());
    }

    @Test
    @DisplayName(
            "[Kabir red-team CRITICAL-1 fix] Real DRAFT -> ACTIVE transition charges the brand"
                    + " publish fee BEFORE the campaign save and BEFORE any invite/bind/credit-reset"
                    + " side effect — charge-then-save, mirroring CampaignService.update() exactly")
    void testRealTransitionChargesFeeBeforeSaveAndDownstreamSideEffects() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        CampaignIntent intent = confirmedIntent(1);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();

        CreatorProfile creator = mockDiscoverableCreator("creator-1");
        when(creatorProfileRepository.findAll(any(Pageable.class))).thenReturn(pageOf(creator));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(eq(CAMPAIGN_ID), anyString()))
                .thenReturn(false);
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        executor.doExecute(
                WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, Map.of("campaign_intent_id", INTENT_ID));

        InOrder order = inOrder(brandCampaignFeeService, campaignRepository, collaborationRepository, aiCreditService);
        order.verify(brandCampaignFeeService).chargeOnPublish(campaign, WORKSPACE_ID);
        order.verify(campaignRepository).save(campaign);
        order.verify(collaborationRepository).save(any(Collaboration.class));
        order.verify(aiCreditService).applyEscrowFundedReset(eq(WORKSPACE_ID), any(Instant.class));
    }

    @Test
    @DisplayName(
            "[Kabir red-team CRITICAL-1 fix] A fee-charge failure (e.g. insufficient wallet"
                    + " balance) rolls back the whole launch — no campaign save, no invites, no"
                    + " escrow-hold binding, no AI-credit reset")
    void testFeeChargeFailureRollsBackWholeLaunch() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        CampaignIntent intent = confirmedIntent(2);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();
        when(brandCampaignFeeService.chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID)))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH",
                                "Insufficient wallet balance — top up Rs. 100.00 to publish this campaign",
                                HttpStatus.PAYMENT_REQUIRED));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                executor.doExecute(
                                        WORKSPACE_ID,
                                        CONVERSATION_ID,
                                        IDEMPOTENCY_KEY,
                                        Map.of("campaign_intent_id", INTENT_ID)));

        assertEquals("INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH", ex.getCode());
        verify(campaignRepository, never()).save(any(Campaign.class));
        verify(collaborationRepository, never()).save(any(Collaboration.class));
        verify(escrowHoldRepository, never()).save(any(EscrowHold.class));
        verify(aiCreditService, never()).applyEscrowFundedReset(anyString(), any(Instant.class));
        verify(toolCallRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "[SEC: Kabir fix 1 — CRITICAL] Repeat confirm_launch against an already-ACTIVE campaign"
                    + " IS a genuine prior confirm_launch replay (meera_tool_calls has an EXECUTED"
                    + " row): clean no-op, no re-invite, no re-bind, no AI-credit reset, no re-charge")
    void testAlreadyActiveCampaignWithPriorConfirmLaunchIsCleanNoOp() {
        Campaign campaign = campaign(CampaignStatus.ACTIVE);
        CampaignIntent intent = confirmedIntent(2);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();
        when(toolCallRepository.existsByToolNameAndResultRefIdAndStatus(
                        MeeraToolName.confirm_launch, CAMPAIGN_ID, ToolCallStatus.EXECUTED))
                .thenReturn(true);

        ConfirmLaunchResult result =
                executor.doExecute(
                        WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, Map.of("campaign_intent_id", INTENT_ID));

        assertNotNull(result);
        assertEquals(CampaignStatus.ACTIVE.name(), result.status());
        assertEquals(0, result.creatorsInvited());
        assertTrue(result.replay());

        // [Kabir red-team CRITICAL-1 fix] a clean no-op never re-charges the fee either — the
        // ledger's own idempotency key would no-op a genuine double-call, but this executor should
        // not even attempt it on an already-ACTIVE campaign.
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
        verify(aiCreditService, never()).applyEscrowFundedReset(anyString(), any(Instant.class));
        verify(collaborationRepository, never()).save(any(Collaboration.class));
        verify(creatorProfileRepository, never()).findAll(any(Pageable.class));
        verify(escrowHoldRepository, never()).save(any(EscrowHold.class));

        // Still records the ledger row for THIS idempotency key so a later replay of it is clean.
        verify(toolCallRepository).save(any());
        verify(auditLogService)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("confirm_launch"),
                        eq("C"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq("ALREADY_ACTIVE_NOOP"),
                        eq(IDEMPOTENCY_KEY),
                        eq(null),
                        any());
    }

    @Test
    @DisplayName(
            "[SEC: Kavya B1-REGRESSION-1] Campaign activated via a DIFFERENT path (e.g."
                    + " CampaignService.update() / PATCH /campaigns/{id}) with NO prior EXECUTED"
                    + " confirm_launch row -> 409 CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH, not a silent"
                    + " no-op that skips invite/bind/credit-reset")
    void testActiveWithoutPriorConfirmLaunchThrowsInsteadOfSilentNoOp() {
        Campaign campaign = campaign(CampaignStatus.ACTIVE);
        CampaignIntent intent = confirmedIntent(2);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();
        when(toolCallRepository.existsByToolNameAndResultRefIdAndStatus(
                        MeeraToolName.confirm_launch, CAMPAIGN_ID, ToolCallStatus.EXECUTED))
                .thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                executor.doExecute(
                                        WORKSPACE_ID,
                                        CONVERSATION_ID,
                                        IDEMPOTENCY_KEY,
                                        Map.of("campaign_intent_id", INTENT_ID)));

        assertEquals("CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        // [Kabir red-team CRITICAL-1 fix] a campaign already ACTIVE via some other path is
        // rejected outright — never charged (it was never a real transition through this class).
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
        verify(aiCreditService, never()).applyEscrowFundedReset(anyString(), any(Instant.class));
        verify(collaborationRepository, never()).save(any(Collaboration.class));
        verify(escrowHoldRepository, never()).save(any(EscrowHold.class));
        // No ledger row written for this rejected call — only a genuinely EXECUTED confirm_launch
        // should ever be recorded as such.
        verify(toolCallRepository, never()).save(any());
        verify(auditLogService)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("confirm_launch"),
                        eq("C"),
                        eq(AuditLogService.OUTCOME_REJECTED),
                        eq("CAMPAIGN_ACTIVATED_WITHOUT_LAUNCH"),
                        eq(IDEMPOTENCY_KEY),
                        eq(null),
                        any());
    }

    @Test
    @DisplayName(
            "[SEC: Kabir fix 2b] A genuine uq_campaign_creator constraint violation on invite is"
                    + " translated to a clean 409, not an unhandled 500")
    void testDuplicateCollaborationConstraintViolationTranslatedTo409() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        CampaignIntent intent = confirmedIntent(1);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();

        CreatorProfile creator = mockDiscoverableCreator("creator-1");
        when(creatorProfileRepository.findAll(any(Pageable.class))).thenReturn(pageOf(creator));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(eq(CAMPAIGN_ID), anyString()))
                .thenReturn(false);
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenThrow(new DataIntegrityViolationException("uq_campaign_creator"));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                executor.doExecute(
                                        WORKSPACE_ID,
                                        CONVERSATION_ID,
                                        IDEMPOTENCY_KEY,
                                        Map.of("campaign_intent_id", INTENT_ID)));

        assertEquals("COLLABORATION_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        // The fee is charged before invites (charge-then-save) — this test reaches the invite
        // step, so the fee charge must have already happened once, successfully.
        verify(brandCampaignFeeService, times(1)).chargeOnPublish(campaign, WORKSPACE_ID);
        // The launch must not appear partially completed: no credit reset past the failed invite.
        verify(aiCreditService, never()).applyEscrowFundedReset(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName(
            "[SEC: Kabir red-team LOW fix] creator_count above MAX_INVITE_CREATOR_COUNT (50) is"
                    + " clamped server-side — never trusts the AI/intent-supplied count outright —"
                    + " and records an INVITE_COUNT_CLAMPED audit entry")
    void testCreatorCountAboveCapIsClampedAndAudited() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        CampaignIntent intent = confirmedIntent(500);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();

        when(creatorProfileRepository.findAll(any(Pageable.class))).thenReturn(pageOf());

        executor.doExecute(
                WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, Map.of("campaign_intent_id", INTENT_ID));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(creatorProfileRepository).findAll(pageableCaptor.capture());
        assertEquals(50, pageableCaptor.getValue().getPageSize());

        verify(auditLogService)
                .recordToolCall(
                        eq(WORKSPACE_ID),
                        eq("confirm_launch"),
                        eq("C"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq("INVITE_COUNT_CLAMPED"),
                        eq(IDEMPOTENCY_KEY),
                        eq(null),
                        eq(Map.of("campaignId", CAMPAIGN_ID, "requestedCount", 500, "clampedTo", 50)));
    }

    @Test
    @DisplayName(
            "creator_count within the cap is used as-is — no clamp, no INVITE_COUNT_CLAMPED audit"
                    + " entry")
    void testCreatorCountWithinCapIsNotClamped() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        CampaignIntent intent = confirmedIntent(2);
        stubIntentAndCampaign(intent, campaign);
        stubFundedHold();

        CreatorProfile creator1 = mockDiscoverableCreator("creator-1");
        CreatorProfile creator2 = mockDiscoverableCreator("creator-2");
        when(creatorProfileRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(creator1, creator2));
        when(collaborationRepository.existsByCampaignIdAndCreatorId(eq(CAMPAIGN_ID), anyString()))
                .thenReturn(false);
        when(collaborationRepository.save(any(Collaboration.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ConfirmLaunchResult result =
                executor.doExecute(
                        WORKSPACE_ID, CONVERSATION_ID, IDEMPOTENCY_KEY, Map.of("campaign_intent_id", INTENT_ID));

        assertEquals(2, result.creatorsInvited());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(creatorProfileRepository).findAll(pageableCaptor.capture());
        assertEquals(2, pageableCaptor.getValue().getPageSize());
        verify(auditLogService, never())
                .recordToolCall(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("INVITE_COUNT_CLAMPED"),
                        any(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("No FUNDED escrow hold -> 409 ESCROW_NOT_FUNDED, nothing charged or persisted")
    void testEscrowNotFundedRejectsBeforeAnySideEffect() {
        Campaign campaign = campaign(CampaignStatus.DRAFT);
        CampaignIntent intent = confirmedIntent(2);
        stubIntentAndCampaign(intent, campaign);
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                executor.doExecute(
                                        WORKSPACE_ID,
                                        CONVERSATION_ID,
                                        IDEMPOTENCY_KEY,
                                        Map.of("campaign_intent_id", INTENT_ID)));

        assertEquals("ESCROW_NOT_FUNDED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        // Rejected before the DRAFT -> ACTIVE transition is ever reached, so the fee is never
        // attempted either.
        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    // [Kabir red-team CRITICAL-1 fix, 2026-07-14] The "P3-20" comment that used to live here
    // claimed the wallet-balance publish-fee charge was correctly relocated to
    // CampaignService.update() and that this executor could never throw
    // INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH. That was false: ConfirmLaunchExecutor.doExecute
    // never calls into CampaignService, so the AI-launch path silently charged a 0% fee on every
    // campaign launched via Meera's confirm_launch tool. BrandCampaignFeeService is reinjected
    // above and the equivalent coverage now lives directly in this class — see
    // testFeeChargeFailureRollsBackWholeLaunch above.

    private void stubIntentAndCampaign(CampaignIntent intent, Campaign campaign) {
        when(campaignIntentRepository.findByIdAndWorkspaceId(INTENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(intent));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
    }

    private void stubFundedHold() {
        EscrowHold fundedHold =
                EscrowHold.builder()
                        .id("01HHOLD1234567890123A")
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .amount(BigDecimal.valueOf(1000))
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("fund:" + CAMPAIGN_ID)
                        .build();
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(fundedHold));
    }

    private static Campaign campaign(CampaignStatus status) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Test Campaign")
                .status(status)
                .build();
    }

    private static CampaignIntent confirmedIntent(int creatorCount) {
        CampaignIntent intent =
                CampaignIntent.builder()
                        .id(INTENT_ID)
                        .conversationId(CONVERSATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignType(CampaignIntentType.STANDARD)
                        .creatorCount(creatorCount)
                        .build();
        intent.confirm(CAMPAIGN_ID);
        return intent;
    }

    private static CreatorProfile mockDiscoverableCreator(String userId) {
        CreatorProfile creator = mock(CreatorProfile.class);
        when(creator.isDiscoverable()).thenReturn(true);
        when(creator.getUserId()).thenReturn(userId);
        return creator;
    }

    private static Page<CreatorProfile> pageOf(CreatorProfile... creators) {
        return new PageImpl<>(List.of(creators));
    }
}
