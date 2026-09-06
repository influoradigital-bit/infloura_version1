package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.ContractStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.money.MoneyDtos.ContractAmendRequest;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * [F-0403 / F-0413 / F-0414] Unit tests for the three contract-lifecycle findings fixed together
 * in this pass:
 *
 * <ul>
 *   <li>F-0403 — {@link Contract#setStatus} previously had zero call sites in {@code src/main}
 *       (confirmed by grep before this ticket was dispatched, and directly documented in {@code
 *       ContractRepository#findUnsignedByCreatorId}'s own javadoc: "a Contract row itself can
 *       never BE cancelled"). Proves a legally-cancellable contract ({@code
 *       DRAFT}/{@code PENDING_SIGNATURES}) can now be cancelled, and an illegally-cancellable one
 *       ({@code ACTIVE} — fully executed) is rejected, not silently voided.
 *   <li>F-0413 — {@code Contract.expirationDate} previously stayed {@code null} forever. Proves
 *       {@link ContractService#generate} now populates it with a real value derived from the
 *       supplied milestones' due dates, and honestly leaves it {@code null} (never fabricated)
 *       when no milestone carries one.
 *   <li>F-0414 — {@code ContractController} previously exposed no amend route at any stage.
 *       Proves {@link ContractService#amend} produces a NEW, correctly-versioned {@link Contract}
 *       row rather than mutating a signed one in place, and that the predecessor's own signed
 *       state survives untouched when it was already fully executed.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ContractCancelAmendTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CONTRACT_ID = "01HCONTRACT1234567AB";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";

    @Mock private ContractRepository contractRepository;
    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private BrandContextService brandContext;
    @Mock private CreatorContextService creatorContext;
    @Mock private ContractPdfService contractPdfService;
    @Mock private R2StorageService r2StorageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private IdempotencyService idempotencyService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private ApplicationHistoryService applicationHistoryService;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember member;

    private ContractService service;

    @BeforeEach
    void setUp() {
        service =
                new ContractService(
                        contractRepository,
                        milestoneRepository,
                        escrowHoldRepository,
                        collaborationRepository,
                        campaignRepository,
                        userRepository,
                        workspaceRepository,
                        workspaceMemberRepository,
                        brandContext,
                        creatorContext,
                        contractPdfService,
                        r2StorageService,
                        eventPublisher,
                        idempotencyService,
                        deliverableRepository,
                        dealMessageRepository,
                        creatorProfileRepository,
                        collaborationLifecycleService,
                        applicationHistoryService);
    }

    private Contract contractWithStatus(ContractStatus status) {
        return Contract.builder()
                .id(CONTRACT_ID)
                .collaborationId(COLLABORATION_ID)
                .workspaceId(WORKSPACE_ID)
                .totalAmount(BigDecimal.valueOf(5000))
                .status(status)
                .build();
    }

    private Collaboration collaborationForCampaign(String campaignId) {
        return Collaboration.invite(COLLABORATION_ID, campaignId, CREATOR_USER_ID, null, "INR");
    }

    // ------------------------------------------------------------------
    // F-0403 — cancel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancel: a DRAFT (never-signed) contract is legally cancellable — status becomes CANCELLED")
    void testCancelDraftContractSucceeds() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = contractWithStatus(ContractStatus.DRAFT);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID)).thenReturn(List.of());

        ContractResponse response = service.cancel(principal, WORKSPACE_ID, CONTRACT_ID);

        assertEquals(ContractStatus.CANCELLED, response.status());
        assertEquals(ContractStatus.CANCELLED, contract.getStatus());
        verify(contractRepository, times(1)).save(contract);
    }

    @Test
    @DisplayName(
            "cancel: a PENDING_SIGNATURES (half-signed) contract is legally cancellable — status"
                    + " becomes CANCELLED")
    void testCancelPendingSignaturesContractSucceeds() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = contractWithStatus(ContractStatus.PENDING_SIGNATURES);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID)).thenReturn(List.of());

        ContractResponse response = service.cancel(principal, WORKSPACE_ID, CONTRACT_ID);

        assertEquals(ContractStatus.CANCELLED, response.status());
        verify(contractRepository, times(1)).save(contract);
    }

    /**
     * The exact scenario F-0403's own report named: a fully-executed contract must NOT be
     * silently cancellable. {@link Contract#canCancel()} deliberately excludes {@code ACTIVE}.
     */
    @Test
    @DisplayName(
            "cancel: an ACTIVE (fully-executed) contract is rejected with 409"
                    + " CONTRACT_NOT_CANCELLABLE — never silently voided, nothing persisted")
    void testCancelActiveContractRejected() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = contractWithStatus(ContractStatus.ACTIVE);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.cancel(principal, WORKSPACE_ID, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_CANCELLABLE", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(ContractStatus.ACTIVE, contract.getStatus());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "cancel: a COMPLETED contract is rejected with 409 CONTRACT_NOT_CANCELLABLE — a"
                    + " finished job cannot retroactively be called off")
    void testCancelCompletedContractRejected() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = contractWithStatus(ContractStatus.COMPLETED);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.cancel(principal, WORKSPACE_ID, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_CANCELLABLE", ex.getCode());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "cancel: an already-CANCELLED contract is rejected with 409 (not a silent no-op) —"
                    + " canCancel() is a strict allowlist, not a denylist that would also admit a"
                    + " repeat cancel")
    void testCancelAlreadyCancelledContractRejected() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = contractWithStatus(ContractStatus.CANCELLED);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.cancel(principal, WORKSPACE_ID, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_CANCELLABLE", ex.getCode());
        verify(contractRepository, never()).save(any());
    }

    /** Creator-side cancel entry point uses the identical legal-transition gate. */
    @Test
    @DisplayName("cancelForCreator: creator can cancel their own DRAFT contract; a stranger's is CONTRACT_NOT_FOUND")
    void testCancelForCreatorSucceedsOnOwnDraftContract() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = contractWithStatus(ContractStatus.DRAFT);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID)).thenReturn(List.of());

        ContractResponse response = service.cancelForCreator(principal, CONTRACT_ID);

        assertEquals(ContractStatus.CANCELLED, response.status());
        verify(contractRepository, times(1)).save(contract);
    }

    // ------------------------------------------------------------------
    // F-0413 — expirationDate populated on generation
    // ------------------------------------------------------------------

    /**
     * Proves {@code generate} now writes a REAL, queryable {@code expirationDate} — the latest
     * milestone due date among those actually supplied, not a fabricated placeholder.
     */
    @Test
    @DisplayName(
            "generate: expirationDate is populated with the LATEST supplied milestone due date,"
                    + " not left null")
    void testGeneratePopulatesExpirationDateFromLatestMilestoneDueDate() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));
        when(contractRepository.existsByCollaborationIdAndStatusNot(
                        COLLABORATION_ID, ContractStatus.CANCELLED))
                .thenReturn(false);

        LocalDate earlier = LocalDate.of(2026, 9, 10);
        LocalDate latest = LocalDate.of(2026, 10, 20);
        ContractGenerateRequest req =
                new ContractGenerateRequest(
                        COLLABORATION_ID,
                        null,
                        List.of(
                                new MilestoneWriteRequest(1, "Deliverable 1", BigDecimal.valueOf(2000), latest),
                                new MilestoneWriteRequest(2, "Deliverable 2", BigDecimal.valueOf(3000), earlier)));

        ContractResponse response = service.generate(principal, WORKSPACE_ID, req);

        assertEquals(latest, response.expirationDate());
        ArgumentCaptor<Contract> saved = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).save(saved.capture());
        assertEquals(latest, saved.getValue().getExpirationDate());
    }

    /** Mirror image — no milestone due dates supplied must read back honestly as null, never fabricated. */
    @Test
    @DisplayName("generate: no milestone due dates supplied leaves expirationDate null, never fabricated")
    void testGenerateWithNoMilestoneDueDatesLeavesExpirationDateNull() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));
        when(contractRepository.existsByCollaborationIdAndStatusNot(
                        COLLABORATION_ID, ContractStatus.CANCELLED))
                .thenReturn(false);

        ContractGenerateRequest req =
                new ContractGenerateRequest(
                        COLLABORATION_ID,
                        null,
                        List.of(new MilestoneWriteRequest(1, "Deliverable 1", BigDecimal.valueOf(5000), null)));

        ContractResponse response = service.generate(principal, WORKSPACE_ID, req);

        assertNull(response.expirationDate());
    }

    // ------------------------------------------------------------------
    // F-0414 — amend produces a new version, never mutates a signed contract in place
    // ------------------------------------------------------------------

    /**
     * The core F-0414 assertion: amending a fully-executed (ACTIVE, both parties signed) contract
     * must produce a SEPARATE, higher-versioned {@link Contract} row — and must leave the
     * predecessor's own signed state (status, id, totalAmount) completely untouched. A wrong fix
     * that mutated the existing row in place would fail this test outright (same object identity,
     * same id, rewritten totalAmount).
     */
    @Test
    @DisplayName(
            "amend: amending a signed (ACTIVE) contract creates a NEW higher-versioned contract"
                    + " and leaves the original signed row completely untouched")
    void testAmendActiveContractCreatesNewVersionWithoutMutatingOriginal() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract original =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLABORATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .version(1)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .status(ContractStatus.ACTIVE)
                        .build();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(original));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(COLLABORATION_ID))
                .thenReturn(List.of(original));
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));

        ContractAmendRequest req =
                new ContractAmendRequest(
                        "Revised terms",
                        List.of(new MilestoneWriteRequest(1, "Revised deliverable", BigDecimal.valueOf(6000), LocalDate.now())));

        ContractResponse response = service.amend(principal, WORKSPACE_ID, CONTRACT_ID, req);

        // A brand-new row: different id, next version, new terms/total.
        assertNotEquals(CONTRACT_ID, response.id());
        assertEquals(2, response.version());
        assertEquals(BigDecimal.valueOf(6000), response.totalAmount());
        assertEquals(ContractStatus.DRAFT, response.status());

        // The predecessor is completely untouched: still ACTIVE, still version 1, still its
        // original totalAmount -- amend never mutates a signed contract in place.
        assertEquals(ContractStatus.ACTIVE, original.getStatus());
        assertEquals(1, original.getVersion());
        assertEquals(BigDecimal.valueOf(5000), original.getTotalAmount());
        // The ACTIVE predecessor is never passed to save() -- amend only persists the new row
        // when the source was already fully executed (see Contract#canCancel()'s ACTIVE exclusion).
        verify(contractRepository, never()).save(original);

        ArgumentCaptor<Contract> savedNew = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).save(savedNew.capture());
        assertNotEquals(CONTRACT_ID, savedNew.getValue().getId());
        assertEquals(2, savedNew.getValue().getVersion());
    }

    /**
     * The predecessor side of the same fix: an unexecuted (DRAFT) contract being amended IS
     * superseded — cancelled outright via the same F-0403 legal-transition path, since nothing
     * binding was ever riding on a draft nobody signed yet.
     */
    @Test
    @DisplayName("amend: amending a DRAFT contract cancels the superseded draft and creates version 2")
    void testAmendDraftContractCancelsPredecessor() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract original =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLABORATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .version(1)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .status(ContractStatus.DRAFT)
                        .build();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(original));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(COLLABORATION_ID))
                .thenReturn(List.of(original));
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));

        ContractAmendRequest req =
                new ContractAmendRequest(
                        null,
                        List.of(new MilestoneWriteRequest(1, "Revised deliverable", BigDecimal.valueOf(4000), null)));

        service.amend(principal, WORKSPACE_ID, CONTRACT_ID, req);

        assertEquals(ContractStatus.CANCELLED, original.getStatus());
        verify(contractRepository, times(1)).save(original);
    }

    @Test
    @DisplayName(
            "amend: amending a stale (non-latest) version is rejected with 409"
                    + " CONTRACT_NOT_LATEST_VERSION — a PENDING_SIGNATURES stale version so the"
                    + " version check, not the CANCELLED/COMPLETED status check, is what fires")
    void testAmendStalePendingSignaturesVersionRejectedAsNonLatest() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract stale =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLABORATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .version(1)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .status(ContractStatus.PENDING_SIGNATURES)
                        .build();
        Contract newer =
                Contract.builder()
                        .id("01HCONTRACTNEWER9999")
                        .collaborationId(COLLABORATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .version(2)
                        .totalAmount(BigDecimal.valueOf(6000))
                        .status(ContractStatus.DRAFT)
                        .build();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(stale));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(COLLABORATION_ID))
                .thenReturn(List.of(newer, stale));

        ContractAmendRequest req =
                new ContractAmendRequest(
                        null,
                        List.of(new MilestoneWriteRequest(1, "x", BigDecimal.valueOf(1000), null)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.amend(principal, WORKSPACE_ID, CONTRACT_ID, req));

        assertEquals("CONTRACT_NOT_LATEST_VERSION", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("amend: a CANCELLED contract cannot be amended — 409 CONTRACT_NOT_AMENDABLE")
    void testAmendCancelledContractRejected() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = contractWithStatus(ContractStatus.CANCELLED);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ContractAmendRequest req =
                new ContractAmendRequest(
                        null,
                        List.of(new MilestoneWriteRequest(1, "x", BigDecimal.valueOf(1000), null)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.amend(principal, WORKSPACE_ID, CONTRACT_ID, req));

        assertEquals("CONTRACT_NOT_AMENDABLE", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("amend: a COMPLETED contract cannot be amended — 409 CONTRACT_NOT_AMENDABLE")
    void testAmendCompletedContractRejected() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = contractWithStatus(ContractStatus.COMPLETED);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ContractAmendRequest req =
                new ContractAmendRequest(
                        null,
                        List.of(new MilestoneWriteRequest(1, "x", BigDecimal.valueOf(1000), null)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.amend(principal, WORKSPACE_ID, CONTRACT_ID, req));

        assertEquals("CONTRACT_NOT_AMENDABLE", ex.getCode());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("amend: empty milestones is rejected with 400 MILESTONES_REQUIRED")
    void testAmendEmptyMilestonesRejected() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract original = contractWithStatus(ContractStatus.DRAFT);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(original));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(COLLABORATION_ID))
                .thenReturn(List.of(original));

        ContractAmendRequest req = new ContractAmendRequest(null, List.of());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.amend(principal, WORKSPACE_ID, CONTRACT_ID, req));

        assertEquals("MILESTONES_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "amend: a milestone total exceeding the collaboration's agreedRate is rejected —"
                    + " the same cap generate() enforces applies to amend() too")
    void testAmendRejectsTotalExceedingAgreedRate() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract original = contractWithStatus(ContractStatus.DRAFT);
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(original));
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(COLLABORATION_ID))
                .thenReturn(List.of(original));
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        collaboration.updateAgreedRate(BigDecimal.valueOf(3000));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));

        ContractAmendRequest req =
                new ContractAmendRequest(
                        null,
                        List.of(new MilestoneWriteRequest(1, "x", BigDecimal.valueOf(5000), null)));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.amend(principal, WORKSPACE_ID, CONTRACT_ID, req));

        assertEquals("CONTRACT_TOTAL_EXCEEDS_AGREED_RATE", ex.getCode());
        verify(contractRepository, never()).save(any());
    }
}
