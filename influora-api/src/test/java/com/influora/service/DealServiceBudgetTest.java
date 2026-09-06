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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * F-0399 / F-0476 / F-0643 — regression coverage for {@code DealService#requireWithinRemainingBudget}
 * and {@code DealService#requireAgreedRateForCommitment} (the price-before-commitment and
 * cumulative-budget gates {@code doAccept} runs before the TERMS_AGREED transition).
 *
 * <p>F-0399 fixed the base defect: each offer used to be checked against {@code
 * campaign.budgetMax} in isolation, so N creators could each be accepted at up to {@code
 * budgetMax} and the campaign would commit N times its own cap. The gate now sums {@code
 * agreedRate} across every OTHER committed collaboration on the campaign plus this offer. {@link
 * #testAcceptRejectsWhenCumulativeCommitmentExceedsBudget} is the control case for this half —
 * unchanged by everything below.
 *
 * <p>F-0476 was a residual that fix left open, since fixed differently by F-0643 below: {@code
 * Collaboration.apply()} (creator bids directly on a campaign) and {@code Collaboration.invite()}
 * never set {@code agreedRate} — it is only ever written by {@code propose()}/{@code
 * updateAgreedRate()}, reached through {@code createProposal}/{@code doCounter} — yet both {@code
 * APPLIED} and {@code INVITED} pass {@link Collaboration#canAccept()}. A null rate used to fold to
 * {@code BigDecimal.ZERO} for the offer being accepted (clearing the check regardless of what was
 * already committed) and to be dropped entirely from the committed-sum stream (so it never
 * counted against the cap afterwards either). F-0476 "fixed" this by valuing a null {@code
 * agreedRate} at {@code budgetMax} — its worst-case draw once {@code
 * EscrowService#deriveFundAmount} funds it on the pool path — instead of zero.
 *
 * <p><b>F-0643 (CEO ruling, worst-case-valuation-blocks-legitimate-flow)</b> — F-0476's own fix was
 * the actual bug this class now pins the correction for: valuing a rate-less accept at {@code
 * budgetMax} meant a single invite-then-accept with no negotiated rate consumed the campaign's
 * ENTIRE budget on its own, permanently blocking every other collaborator. The ruling removes the
 * liability instead of pricing it: {@code DealService#requireAgreedRateForCommitment} now rejects
 * the TERMS_AGREED transition outright when {@code agreedRate} is absent, so a rate-less
 * collaboration can no longer reach a committed status at all and — going forward — never needs a
 * worst-case valuation. {@link #testAcceptRejectsNullRateApplication} pins the new rejection
 * (inverts what this class used to assert as the correct behavior); {@link
 * #testAcceptAllowsSecondCollaboratorDespiteLegacyNullRateCommittedRow} pins the accounting
 * consequence — a null-rate row already sitting in a committed status (this ruling's only route
 * there is a row predating it) is now valued at {@code ZERO}, not {@code budgetMax}, so it no
 * longer blocks every future accept on the campaign.
 *
 * <p>This class is deliberately test-only — see the F-0399/F-0476/F-0643 task briefs. It does not
 * touch {@code Collaboration}. Every test in this class is expected to be GREEN against current
 * {@code DealService}.
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
     * {@code agreedRate} is null. Under F-0643, {@code doAccept} can no longer produce this state
     * itself ({@code requireAgreedRateForCommitment} rejects the transition first), so this
     * fixture stands for a row that predates the ruling (or was written outside {@code doAccept}).
     * Constructed directly via {@code apply()} + {@code transitionTo} rather than through {@code
     * doAccept}, so it exists independent of whatever {@code doAccept} currently does.
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
    // 1. A null-rate accept must now be REJECTED — F-0643 replaces the F-0399/F-0476
    //    "let it through, value it at budgetMax" approach with "it is not a commitment yet".
    // ------------------------------------------------------------------

    /**
     * A brand accepting a creator's direct bid — no proposal exchanged, so {@code agreedRate} is
     * null. This MUST now be rejected with a typed error, and MUST NOT reach the budget gate at
     * all (proved by the {@code campaignRepository} verify below): {@code
     * DealService#requireAgreedRateForCommitment} runs before {@code
     * DealService#requireWithinRemainingBudget} in {@code doAccept} and short-circuits first.
     *
     * <p>This inverts what this same test used to assert. Before F-0643, an earlier F-0399 pass
     * had rejected a null rate outright, reasoning that a deal with no agreed amount is not agreed
     * terms; that broke nine {@code DealServiceTest} accept cases (including both happy paths)
     * because {@link Collaboration#invite}/{@code #apply} legitimately carry no rate, so F-0476
     * let the accept through instead and valued the liability at {@code budgetMax}. The CEO ruling
     * (worst-case-valuation-blocks-legitimate-flow) found THAT consequence worse — a single such
     * accept could consume a campaign's entire budget alone — and restores the rejection, but this
     * time as a first-class typed error the caller can act on (negotiate a rate, then accept),
     * rather than accepting blind and hoping. {@code DealServiceTest}'s former happy-path fixtures
     * were updated alongside this to set a real {@code agreedRate} before calling accept.
     */
    @Test
    @DisplayName(
            "F-0643: accepting a rate-less invite/application is rejected — an invite is not a"
                    + " commitment; it stays in INVITED/APPLIED until a rate is negotiated")
    void testAcceptRejectsNullRateApplication() {
        stubBrandWorkspace();
        Collaboration collaboration = nullRateApplication();
        when(collaborationRepository.findByIdAndWorkspaceId(DEAL_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(collaboration));
        when(dealMessageRepository.findFirstByCollaborationIdAndKindOrderByCreatedAtDesc(
                        DEAL_ID, DealMessageKind.proposal))
                .thenReturn(Optional.empty());
        stubIdempotencyExecutesAction();

        ApiException ex =
                assertThrows(ApiException.class, () -> service.accept(brandPrincipal, DEAL_ID, null));

        assertEquals("AGREED_RATE_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertNotEquals(CollaborationStatus.TERMS_AGREED, collaboration.getStatus());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
        // Proves this is rejected BEFORE the budget gate even looks at the campaign — the
        // rate-less collaboration never becomes a priced liability to value in the first place.
        verify(campaignRepository, never()).findById(any());
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
    // 3. F-0643's whole point: a legacy null-rate committed row must NOT be valued at budgetMax
    //    any more — doing so is exactly the bug that let one rate-less accept block every other
    //    collaborator forever. It must now be valued at ZERO, so a second, real-rate collaborator
    //    CAN be accepted where the old worst-case valuation would have blocked them.
    // ------------------------------------------------------------------

    /**
     * A null-rate collaboration already sits in {@code TERMS_AGREED} — under this ruling, {@code
     * doAccept} can no longer produce such a row itself ({@code
     * DealService#requireAgreedRateForCommitment} rejects a rate-less accept before it gets there),
     * so the only way one exists here is a row predating F-0643 (or written outside {@code
     * doAccept}). It represents exactly the failure mode the ruling was written to fix: a
     * rate-less "collaborator #1" that, under the old F-0476 worst-case valuation, would have been
     * priced at the full {@code budgetMax} and permanently blocked every subsequent collaborator.
     *
     * <p>Accepting a further, real-rate offer of 45000 ("collaborator #2") against the same 50000
     * {@code budgetMax} MUST now succeed: {@code DealService#committedValue} values the legacy
     * null-rate row at {@code ZERO}, so the sum is 0 (null-rate row) + 45000 (this offer) = 45000,
     * under the 50000 cap.
     *
     * <p>This is the direct behavioral inverse of what this same test used to assert (95000, over
     * cap, rejected) before F-0643 — pinned here specifically because falsifying it (reverting
     * {@code committedValue} to the F-0476 {@code budgetMax}-on-null behavior) must turn this RED
     * for the AMOUNT_EXCEEDS_BUDGET reason the old code gave, not some unrelated failure.
     */
    @Test
    @DisplayName(
            "F-0643: a second, real-rate collaborator CAN now be accepted despite a legacy"
                    + " null-rate row already TERMS_AGREED — the old worst-case valuation would have"
                    + " blocked them")
    void testAcceptAllowsSecondCollaboratorDespiteLegacyNullRateCommittedRow() {
        stubBrandWorkspace();
        Collaboration collaboration =
                Collaboration.propose(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, new BigDecimal("45000"), "INR", "Deal");
        stubAcceptTarget(collaboration);
        Collaboration nullRateCommitted =
                nullRateAlreadyCommitted(OTHER_DEAL_ID_2, OTHER_CREATOR_USER_ID_2);
        when(collaborationRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(collaboration, nullRateCommitted));
        stubIdempotencyExecutesAction();

        assertDoesNotThrow(() -> service.accept(brandPrincipal, DEAL_ID, null));

        assertEquals(CollaborationStatus.TERMS_AGREED, collaboration.getStatus());
    }
}
