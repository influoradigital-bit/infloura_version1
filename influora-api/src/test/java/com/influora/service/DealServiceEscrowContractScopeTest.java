package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.ShipmentRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * [F-0656 investigation, collaboration-scoped-not-contract-scoped] Pins the CONFIRMED gap in
 * {@code DealService#toDealResponse}'s {@code escrowFunded} flag (around DealService.java:2101):
 * it is derived purely from {@link EscrowHoldRepository#hasEscrowForCollaboration}, a
 * collaboration/milestone-linkage check that has ZERO awareness of {@code Contract} identity, and
 * is never cross-checked against {@link ContractService#resolveCurrentContract}'s pick (the
 * {@code latest} contract this same method surfaces as {@code contractId}/{@code contractStatus}
 * on the very same {@link DealResponse} row).
 *
 * <p><b>Why this is a real gap, not a misread of the CR-49/CR-50 comment already at that site.</b>
 * That comment explains why the milestone-linkage UNION query is used instead of a bare {@code
 * collaboration_id}-column check -- the direct column is null on every ordinary brand-funded hold,
 * so a naive check would false-negative on a genuinely funded deal. That reasoning is about
 * finding a hold that legitimately exists; it says nothing about WHICH contract version the hold's
 * milestone belongs to, because {@code EscrowHoldRepository#hasEscrowForCollaboration}'s own JPQL
 * only ever filters {@code PaymentMilestone.collaborationId}, never {@code
 * PaymentMilestone.contractId} -- so it is satisfied by a milestone under ANY contract version
 * ever created for the collaboration, current or superseded. Concretely: {@code
 * ContractService#amend} creates brand-new {@code PaymentMilestone} rows (fresh {@code
 * escrowHoldId = null}) under the new contract version, while the ORIGINAL, still-{@code FUNDED}
 * hold stays bound to the superseded version's milestone -- nothing refunds/re-links it. Once
 * {@code ContractService#retirePredecessorIfSuperseded} promotes the amendment to {@code ACTIVE}
 * (and the predecessor to {@code COMPLETED}), {@code resolveCurrentContract} correctly starts
 * returning the amendment as "current" -- but {@code escrowFunded} keeps reporting {@code true}
 * off the predecessor's untouched hold, even though the CURRENT contract's own payment plan was
 * never funded.
 *
 * <p><b>Why this test pins current behaviour instead of changing it.</b> A correct fix needs to
 * know which {@code Contract} a {@code FUNDED} hold's milestone belongs to -- {@code EscrowHold}
 * carries no {@code contractId} column at all (only {@code collaborationId}/{@code campaignId}/
 * {@code milestoneId}), so answering that question requires either a new contract-scoped query on
 * {@code EscrowHoldRepository} (joining {@code PaymentMilestone.contractId}) or a {@code
 * PaymentMilestoneRepository} dependency added to {@code DealService} to resolve the current
 * contract's milestone-id set in Java. Both changes live outside this pass's file boundary ({@code
 * DealService.java} + this test file): {@code EscrowHoldRepository.java} is a different file, and
 * {@code DealService}'s constructor is a single positional constructor shared verbatim by six-plus
 * other test files ({@code DealServiceTest}, {@code DealServiceBudgetTest}, {@code
 * DealServiceCollaboratorCapVerificationTest}, {@code DealServiceCreatorDraftExclusionTest}, {@code
 * DealTrailCoverageTest}, {@code DealControllerTest}) this pass is not permitted to touch, so
 * adding a parameter here would not compile. No heuristic reachable from {@code DealService}'s
 * EXISTING dependencies (just {@code Contract} rows with no milestone data) can distinguish "the
 * current contract's own funding" from "a stale predecessor's funding still sitting in escrow"
 * without that missing join -- any such heuristic would just trade today's false positive for a
 * new false negative on the legitimate case where a brand funds an amendment separately (a real
 * flow: {@code ContractService#amend} calls {@code promptEscrowFundingIfNeeded} on the new
 * version). So this class intentionally does not change production code; it exists to make the
 * gap executable and visible (F-0656 tracked as a follow-up needing the repository-level fix
 * above) and to catch any further drift in the mechanism described here.
 */
@ExtendWith(MockitoExtension.class)
class DealServiceEscrowContractScopeTest {

    private static final String DEAL_ID = "01HDEAL00000000000002";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String ORIGINAL_CONTRACT_ID = "01HCONTRACTORIGINAL02";
    private static final String AMENDMENT_CONTRACT_ID = "01HCONTRACTAMENDED02";
    private static final String WORKSPACE_ID = "01HWORKSPACE12345678B";

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
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private AuthPrincipal creatorPrincipal;

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

        when(creatorPrincipal.getUserType()).thenReturn(UserType.CREATOR);
        when(creatorPrincipal.getUserId()).thenReturn(CREATOR_USER_ID);

        Collaboration collaboration =
                Collaboration.invite(DEAL_ID, "01HCAMPAIGN00000002A", CREATOR_USER_ID, null, "INR");
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(collaboration));
        // No campaign row needed for this flag's mechanism -- resolveCounterparty degrades to
        // "unknown workspace" cleanly when the campaign lookup misses, per its own null-safe path.
        when(campaignRepository.findById(anyString())).thenReturn(Optional.empty());
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(DEAL_ID))
                .thenReturn(List.of());
        when(dealMessageRepository.findFirstByCollaborationIdOrderByCreatedAtDesc(DEAL_ID))
                .thenReturn(Optional.empty());
        when(dealMessageRepository.findByCollaborationIdOrderByCreatedAtAsc(DEAL_ID))
                .thenReturn(List.of());

        // The predecessor v1 was fully signed and funded, then amended: v2 exists, is ACTIVE, and
        // per ContractService#retirePredecessorIfSuperseded's own javadoc, COMPLETED is the ONLY
        // status a predecessor reaches via supersession (not "the deal genuinely finished").
        Contract original =
                Contract.builder()
                        .id(ORIGINAL_CONTRACT_ID)
                        .collaborationId(DEAL_ID)
                        .workspaceId(WORKSPACE_ID)
                        .version(1)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .status(ContractStatus.COMPLETED)
                        .build();
        Contract amendment =
                Contract.builder()
                        .id(AMENDMENT_CONTRACT_ID)
                        .collaborationId(DEAL_ID)
                        .workspaceId(WORKSPACE_ID)
                        .version(2)
                        .totalAmount(BigDecimal.valueOf(6000))
                        .status(ContractStatus.ACTIVE)
                        .build();
        // Newest-(version,createdAt)-first, matching
        // ContractRepository#findByCollaborationIdOrderByVersionDescCreatedAtDesc's own contract.
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(DEAL_ID))
                .thenReturn(List.of(amendment, original));
    }

    /**
     * [F-0656 fix] The predecessor v1's FUNDED hold is bound, via {@code milestoneId}, to a
     * milestone whose {@code contractId} is v1. The current contract is the amendment v2, whose
     * own $6000 payment plan was never funded. The contract-scoped query therefore answers
     * {@code false} for v2, and the deal room now says so — where it previously reported
     * {@code true} off that untouched predecessor hold and told both parties money was secured
     * when it was not.
     *
     * <p>This test asserted the OPPOSITE before the fix. It was a deliberate characterization test
     * pinning a confirmed-but-unfixed gap, which is legitimate — but it meant the suite was green
     * BECAUSE the defect existed, so inverting it is part of the fix, not collateral.
     */
    @Test
    @DisplayName(
            "[F-0656] escrowFunded is FALSE for the current amendment when only the superseded"
                    + " predecessor's hold is funded")
    void testEscrowFundedFalseWhenOnlyTheSupersededPredecessorsHoldIsFunded() {
        when(escrowHoldRepository.hasEscrowForContract(anyString(), anyString(), any()))
                .thenReturn(false);

        DealResponse response = service.get(creatorPrincipal, DEAL_ID);

        assertEquals(AMENDMENT_CONTRACT_ID, response.contractId());
        assertEquals(ContractStatus.ACTIVE, response.contractStatus());
        assertFalse(
                response.escrowFunded(),
                "F-0656: a hold bound to the SUPERSEDED contract's milestone must not report the"
                        + " current amendment as funded");
    }

    /**
     * [F-0656 fix — the other direction] A genuinely funded current contract must still report
     * true. Without this, "always false" would pass the test above while breaking every real
     * funded deal — the opposite failure, and the more damaging one.
     */
    @Test
    @DisplayName("[F-0656] escrowFunded is TRUE when the CURRENT contract's own milestone is funded")
    void testEscrowFundedTrueWhenCurrentContractsOwnMilestoneIsFunded() {
        when(escrowHoldRepository.hasEscrowForContract(anyString(), anyString(), any()))
                .thenReturn(true);

        DealResponse response = service.get(creatorPrincipal, DEAL_ID);

        assertEquals(AMENDMENT_CONTRACT_ID, response.contractId());
        assertTrue(response.escrowFunded());
    }

    /**
     * [F-0656 fix — the assertion that actually pins SCOPING] Both tests above would still pass if
     * the code called a contract-scoped query with the WRONG contract id (the superseded v1), or
     * kept calling the collaboration-scoped one. This captures the id actually passed and requires
     * it to be the resolved CURRENT contract, and requires the old collaboration-scoped method not
     * to be consulted at all while a contract exists.
     */
    @Test
    @DisplayName("[F-0656] the escrow check is scoped to the CURRENT contract id, not any version")
    void testEscrowScopeQueryUsesTheResolvedCurrentContractId() {
        when(escrowHoldRepository.hasEscrowForContract(anyString(), anyString(), any()))
                .thenReturn(false);

        service.get(creatorPrincipal, DEAL_ID);

        ArgumentCaptor<String> contractId = ArgumentCaptor.forClass(String.class);
        verify(escrowHoldRepository)
                .hasEscrowForContract(eq(DEAL_ID), contractId.capture(), any());
        assertEquals(
                AMENDMENT_CONTRACT_ID,
                contractId.getValue(),
                "F-0656: the escrow check must be scoped to the CURRENT contract, not the"
                        + " superseded predecessor");
        verify(escrowHoldRepository, never()).hasEscrowForCollaboration(anyString(), any());
    }
}
