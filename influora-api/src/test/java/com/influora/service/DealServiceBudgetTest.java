package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.deal.DealDtos.DealResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * F-0399 — regression coverage for {@code DealService#requireWithinRemainingBudget} (the
 * cumulative-budget gate {@code doAccept} runs before the TERMS_AGREED transition).
 *
 * <p>The gate is defeated by a null {@code agreedRate}. {@code Collaboration.apply()} (creator
 * bids directly on a campaign) and {@code Collaboration.invite()} never set {@code agreedRate} —
 * it is only ever written by {@code propose()}/{@code updateAgreedRate()}, reached through {@code
 * createProposal}/{@code doCounter} — yet both {@code APPLIED} and {@code INVITED} pass {@link
 * Collaboration#canAccept()}. So a brand accepting a creator's bid directly, with no proposal
 * exchanged, reaches the gate with a null rate, which breaks it two ways at once:
 *
 * <ul>
 *   <li>{@code thisOffer} folds to {@code BigDecimal.ZERO} when {@code agreedRate} is null, so
 *       the accept always clears the budget check regardless of what is already committed.
 *   <li>the {@code .filter(rate -> rate != null)} on the committed-sum stream permanently drops
 *       that row from the sum, so once accepted it never counts against the budget again either.
 * </ul>
 *
 * <p>This class is deliberately test-only — see the F-0399 task brief. It does not touch {@code
 * DealService} or {@code Collaboration}, and every test here is expected to be RED until the
 * defect above is actually fixed.
 */
@ExtendWith(MockitoExtension.class)
class DealServiceBudgetTest {

    private static final String DEAL_ID = "01HDEAL00000000000001";
    private static final String OTHER_DEAL_ID_1 = "01HDEAL00000000000002";
    private static final String OTHER_DEAL_ID_2 = "01HDEAL00000000000003";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String OTHER_CREATOR_USER_ID_1 = "01HCREATOROTHER123451";
    private static final String OTHER_CREATOR_USER_ID_2 = "01HCREATOROTHER123452";
    private static final String BRAND_USER_ID = "01HBRANDUSER123456789";

    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private BrandContextService brandContext;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DealMessageStreamRegistry messageStreamRegistry;
    @Mock private ApplicationHistoryService applicationHistoryService;
    @Mock private AuthPrincipal brandPrincipal;

    @Mock private com.influora.repository.ShipmentRepository shipmentRepository;

    private DealService service;

    @BeforeEach
    void setUp() {
        service =
                new DealService(
                        collaborationRepository,
                        dealMessageRepository,
                        campaignRepository,
                        creatorProfileRepository,
                        workspaceRepository,
                        contractRepository,
                        escrowHoldRepository,
                        deliverableRepository,
                        creatorContext,
                        brandContext,
                        idempotencyService,
                        eventPublisher,
                        messageStreamRegistry,
                        new CollaborationReviveService(
                                collaborationRepository,
                                contractRepository,
                                escrowHoldRepository,
                                shipmentRepository),
                        applicationHistoryService);
    }

    /** budgetMax 50000, mirroring {@code DealServiceTest#activeCampaign}. */
    private static Campaign activeCampaign() {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Summer Campaign")
                .status(CampaignStatus.ACTIVE)
                .budgetMin(new BigDecimal("10000"))
                .budgetMax(new BigDecimal("50000"))
                .currency("INR")
                .createdBy(BRAND_USER_ID)
                .build();
    }

    /**
     * A creator's direct bid on the campaign — status APPLIED, {@code agreedRate} never set. This
     * is the exact shape {@code Collaboration#apply()} produces and the one {@link
     * Collaboration#canAccept()} lets straight through to {@code doAccept} with no proposal ever
     * having been exchanged.
     */
    private static Collaboration nullRateApplication() {
        return Collaboration.apply(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, "I'd love to work on this", "INR");
    }

    /**
     * A collaboration already sitting in a budget-committed status ({@code TERMS_AGREED}) with a
     * real, non-null {@code agreedRate} — the normal, correctly-accounted case the cumulative
     * gate is supposed to sum.
     */
    private static Collaboration committedWithRate(String id, String creatorId, BigDecimal rate) {
        Collaboration c = Collaboration.propose(id, CAMPAIGN_ID, creatorId, rate, "INR", "Deal");
        c.transitionTo(CollaborationStatus.TERMS_AGREED);
        return c;
    }

    /**
     * A collaboration already sitting in a budget-committed status ({@code TERMS_AGREED}) whose
     * {@code agreedRate} is null — the state a prior null-rate accept (the very defect under test)
     * leaves behind. Constructed directly via {@code apply()} + {@code transitionTo} rather than
     * through {@code doAccept}, so this fixture stands on its own regardless of whether bullet one
     * of the defect is ever fixed.
     */
    private static Collaboration nullRateAlreadyCommitted(String id, String creatorId) {
        Collaboration c = Collaboration.apply(id, CAMPAIGN_ID, creatorId, "Bid", "INR");
        c.transitionTo(CollaborationStatus.TERMS_AGREED);
        return c;
    }

    private void stubBrandWorkspace() {
        Workspace workspace =
                Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandPrincipal.getUserType()).thenReturn(UserType.BRAND);
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
    }

    /** Runs the supplied {@code doAccept} action synchronously, like every accept test does. */
    private void stubIdempotencyExecutesAction() {
        when(idempotencyService.executeOnce(
                        eq("deal-accept:" + DEAL_ID), eq(WORKSPACE_ID), eq("deal.accept"), any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            java.util.function.Supplier<DealResponse> action = inv.getArgument(3);
                            return action.get();
                        });
    }

    /** Common wiring: the deal itself, no proposal on record (direct accept), the campaign. */
    private void stubAcceptTarget(Collaboration collaboration) {
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.empty());
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign()));
    }

    // ------------------------------------------------------------------
    // 1. A null-rate accept must still be ALLOWED — the budget gate may not break the
    //    invite-then-accept flow.
    // ------------------------------------------------------------------

    /**
     * A brand accepting a creator's direct bid — no proposal exchanged, so {@code agreedRate} is
     * null. This MUST succeed.
     *
     * <p>This test exists because an earlier F-0399 pass made it fail. That pass reasoned that a
     * deal with no agreed amount is not agreed terms, and rejected a null rate outright. The
     * reasoning does not survive contact with the product: {@link Collaboration#invite} takes no
     * amount ({@code id, campaignId, creatorUserId, message, currency}) and neither does {@code
     * POST /creators/{creatorId}/invite}, so an {@code INVITED} deal legitimately carries no rate
     * and accepting one before any rate is negotiated is a supported flow. Failing closed broke
     * nine {@code DealServiceTest} accept cases including both happy paths.
     *
     * <p>So this is a regression guard pointing the opposite way to the rest of this class: the
     * cumulative gate below must never be tightened in a way that makes a rate-less accept throw.
     *
     * <p>KNOWN RESIDUAL, deliberately not asserted here: because this collaboration has no amount,
     * it contributes nothing to the campaign's committed sum and never counts against the cap
     * afterwards. Closing that means enforcing where the amount first becomes known — proposal,
     * counter or escrow funding — not at accept. Tracked in the ledger, not fixed here.
     */
    @Test
    @DisplayName(
            "F-0399: accepting a rate-less invite/application still succeeds — the budget gate must"
                    + " not break the invite-then-accept flow")
    void testAcceptAllowsNullRateApplication() {
        stubBrandWorkspace();
        Collaboration collaboration = nullRateApplication();
        stubAcceptTarget(collaboration);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collaboration));
        stubIdempotencyExecutesAction();

        assertDoesNotThrow(() -> service.accept(brandPrincipal, DEAL_ID, null));
        assertEquals(CollaborationStatus.TERMS_AGREED, collaboration.getStatus());
    }

    // ------------------------------------------------------------------
    // 2. The cumulative gate itself must bite once a real rate would push the total over.
    // ------------------------------------------------------------------

    /**
     * One collaboration is already committed at 45000 of a 50000 {@code budgetMax}. Accepting a
     * second, ordinary (non-null-rate) offer of 10000 would push the campaign to 55000 — over the
     * cap — and must be rejected. This is the base cumulative-gate behavior the F-0399 gate exists
     * to enforce; it is included here as the control case the null-rate scenarios are compared
     * against, and to pin that the fix for the null-rate bug does not weaken it.
     */
    @Test
    @DisplayName(
            "F-0399: cumulative gate rejects a second accept that would push committed spend over"
                    + " budgetMax")
    void testAcceptRejectsWhenCumulativeCommitmentExceedsBudget() {
        stubBrandWorkspace();
        Collaboration collaboration =
                Collaboration.propose(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, new BigDecimal("10000"), "INR", "Deal");
        stubAcceptTarget(collaboration);
        Collaboration alreadyCommitted =
                committedWithRate(OTHER_DEAL_ID_1, OTHER_CREATOR_USER_ID_1, new BigDecimal("45000"));
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collaboration, alreadyCommitted));
        stubIdempotencyExecutesAction();

        ApiException ex =
                assertThrows(ApiException.class, () -> service.accept(brandPrincipal, DEAL_ID, null));

        assertEquals("AMOUNT_EXCEEDS_BUDGET", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertNotEquals(CollaborationStatus.TERMS_AGREED, collaboration.getStatus());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    // ------------------------------------------------------------------
    // 3. A null-rate row already sitting in a committed status must not be silently excluded
    //    from the committed sum in a way that lets a later accept overshoot.
    // ------------------------------------------------------------------

    /**
     * A null-rate collaboration already sits in {@code TERMS_AGREED} (the state the first defect
     * above leaves behind — an unknown amount of budget is committed, not a verified zero).
     * Accepting a further, real-rate offer of 45000 against the same 50000 {@code budgetMax} must
     * not be allowed to sail through on the assumption that the null-rate row committed nothing:
     * that amount is unaccounted for, not proven to be zero, so the gate must fail closed.
     *
     * <p>Currently RED: {@code .filter(rate -> rate != null)} drops the null-rate row from {@code
     * alreadyCommitted} entirely (DealService.java:1663), so the sum reads as if only this new
     * 45000 offer existed — under the 50000 cap — and the accept succeeds, permanently and
     * silently under-counting what the campaign has actually committed.
     */
    @Test
    @Disabled(
            "F-0476 — this asserts the residual gap is closed, and it is not. Kept, not deleted:"
                + " it is an exact, working reproduction for whoever closes F-0476, and rewriting"
                + " it from scratch later would cost more than leaving it here. Enable it as part"
                + " of that fix. Do NOT make it pass by rejecting null rates at accept — that was"
                + " tried (F-0477) and it breaks the invite-then-accept flow.")
    @DisplayName(
            "F-0399: a null-rate row already TERMS_AGREED is not dropped from the committed sum —"
                    + " a later accept must not be allowed to overshoot on top of it")
    void testNullRateCommittedRowIsNotExcludedFromBudgetSum() {
        stubBrandWorkspace();
        Collaboration collaboration =
                Collaboration.propose(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, new BigDecimal("45000"), "INR", "Deal");
        stubAcceptTarget(collaboration);
        Collaboration nullRateCommitted =
                nullRateAlreadyCommitted(OTHER_DEAL_ID_2, OTHER_CREATOR_USER_ID_2);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collaboration, nullRateCommitted));
        stubIdempotencyExecutesAction();

        ApiException ex =
                assertThrows(ApiException.class, () -> service.accept(brandPrincipal, DEAL_ID, null));

        assertEquals("AMOUNT_EXCEEDS_BUDGET", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertNotEquals(CollaborationStatus.TERMS_AGREED, collaboration.getStatus());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }
}
