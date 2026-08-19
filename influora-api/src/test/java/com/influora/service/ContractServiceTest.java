package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.UserType;
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
import com.influora.service.notification.event.ContractReadyForEscrowEvent;
import com.influora.web.dto.money.MoneyDtos.ContractGenerateRequest;
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * [E2 audit finding #10, HIGH -- fixed] Unit tests for {@link ContractService#recordSignature}'s
 * already-signed guard. Priority: proving a retried sign call for a role that already signed is a
 * clean idempotent no-op -- it must NOT re-fire {@code generateAndDeliverContractPdf} (PDF
 * generation + email delivery) a second time, the exact regression the E2 audit flagged
 * ({@code Contract.recordBrandSignature()}/{@code recordCreatorSignature()} previously
 * unconditionally overwrote the signed-at timestamp on every call with no guard).
 */
@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HWORKSPACEOTHERB999";
    private static final String CONTRACT_ID = "01HCONTRACT1234567AB";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
    private static final String OTHER_CREATOR_USER_ID = "01HCREATOROTHER9999AB";

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

    /**
     * Mirrors {@code PayoutServiceTest#mockIdempotencyExecuteOnce}: run the supplier as the race
     * winner so {@code recordSignature}'s {@code executeOnce} wrapper (E2 LOW-3) doesn't just
     * return the Mockito default {@code null} for these unit tests.
     */
    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<ContractResponse> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private Contract unsignedContract() {
        return Contract.builder()
                .id(CONTRACT_ID)
                .collaborationId(COLLABORATION_ID)
                .workspaceId(WORKSPACE_ID)
                .totalAmount(BigDecimal.valueOf(5000))
                .termsJson("{}")
                .status(ContractStatus.DRAFT)
                .build();
    }

    private Collaboration collaborationForCampaign(String campaignId) {
        return Collaboration.invite(COLLABORATION_ID, campaignId, "01HCREATORUSER1234AB", null, "INR");
    }

    private ContractGenerateRequest generateRequest() {
        return new ContractGenerateRequest(
                COLLABORATION_ID,
                List.of(new MilestoneWriteRequest(1, "Deliverable 1", BigDecimal.valueOf(5000), LocalDate.now())));
    }

    /**
     * [SEC: Kabir, Wave E1 escalation — fixed] {@code generate} previously trusted
     * {@code req.collaborationId()} with no workspace-ownership check at all: {@code Collaboration}
     * has no {@code workspace_id} column, so a brand authenticated into WORKSPACE_ID could pass ANY
     * other brand's collaborationId and have a Contract + PaymentMilestones built against that
     * stranger's real creator relationship (see {@code wiki/errors/wave-e1-kabir-escalation-review.md}
     * for the full exploit trace). Proves the new {@code campaignRepository.findByIdAndWorkspaceId}
     * check (mirroring {@code CampaignLinkService#createTrackingLink}'s resolve-then-scope pattern)
     * closes the gap: the collaboration's campaign belongs to OTHER_WORKSPACE_ID, not the calling
     * WORKSPACE_ID, so the lookup must miss and no Contract/PaymentMilestone rows may be persisted.
     */
    @Test
    @DisplayName(
            "generate: brand A supplying brand B's collaborationId gets COLLABORATION_NOT_FOUND and"
                    + " persists NO Contract/PaymentMilestone rows")
    void testGenerateRejectsCrossWorkspaceCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        // The collaboration's campaign belongs to a DIFFERENT workspace than the caller's -- the
        // scoped lookup must come back empty.
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.generate(principal, WORKSPACE_ID, generateRequest()));

        assertEquals("COLLABORATION_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verifyNoInteractions(contractRepository, milestoneRepository);
    }

    @Test
    @DisplayName(
            "generate: legitimate same-workspace collaboration still creates the Contract and"
                    + " PaymentMilestone rows normally")
    void testGenerateSucceedsForSameWorkspaceCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));

        ContractResponse response = service.generate(principal, WORKSPACE_ID, generateRequest());

        assertNotNull(response);
        assertEquals(COLLABORATION_ID, response.collaborationId());
        assertEquals(WORKSPACE_ID, response.workspaceId());
        verify(contractRepository, times(1)).save(any(Contract.class));
        verify(milestoneRepository, times(1)).saveAll(any());
    }

    /** Persistent application-history requirement — wiring the 8 remaining event types. */
    @Test
    @DisplayName(
            "generate: records DEAL_ROOM_ACTIVATED and CONTRACT_GENERATED application-history events")
    void testGenerateRecordsApplicationHistoryEvents() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(principal.getUserId()).thenReturn("brand_user_1");
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));

        service.generate(principal, WORKSPACE_ID, generateRequest());

        verify(applicationHistoryService)
                .record(
                        eq(CAMPAIGN_ID),
                        eq(COLLABORATION_ID),
                        eq(COLLABORATION_ID),
                        eq(com.influora.domain.enums.ApplicationHistoryEventType.DEAL_ROOM_ACTIVATED),
                        eq(com.influora.domain.enums.CollaborationStatus.CONTRACT_PENDING),
                        eq(com.influora.domain.enums.ApplicationHistoryActorType.BRAND),
                        eq("brand_user_1"),
                        anyString(),
                        any(),
                        eq("/creator/chat?deal=" + COLLABORATION_ID),
                        eq(COLLABORATION_ID));
        verify(applicationHistoryService)
                .record(
                        eq(CAMPAIGN_ID),
                        eq(COLLABORATION_ID),
                        eq(COLLABORATION_ID),
                        eq(com.influora.domain.enums.ApplicationHistoryEventType.CONTRACT_GENERATED),
                        eq(com.influora.domain.enums.CollaborationStatus.CONTRACT_PENDING),
                        eq(com.influora.domain.enums.ApplicationHistoryActorType.BRAND),
                        eq("brand_user_1"),
                        anyString(),
                        // Sign-off review follow-on (#3) — metadata is null, no raw contract id.
                        org.mockito.ArgumentMatchers.isNull(),
                        eq("/creator/chat?deal=" + COLLABORATION_ID),
                        eq(COLLABORATION_ID));
    }

    /**
     * CR-22a, Kabir finding #1 — {@code generate} previously had zero {@code CollaborationStatus}
     * awareness: a collaboration cancelled via {@code DealService#reject} could still have a
     * Contract generated against it afterwards. Checked under the same row lock the duplicate-
     * contract guard below already takes, so no Contract/PaymentMilestone row is ever persisted
     * for a CANCELLED collaboration.
     */
    @Test
    @DisplayName("generate: CANCELLED collaboration returns 409 COLLABORATION_CANCELLED, persists nothing")
    void testGenerateRejectsCancelledCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        collaboration.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.generate(principal, WORKSPACE_ID, generateRequest()));

        assertEquals("COLLABORATION_CANCELLED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verifyNoInteractions(contractRepository, milestoneRepository);
    }

    /**
     * [BE-1: Vikram, contract-flow-architecture-2026-07-23 §6.4 — REAL GAP, now closed]
     * {@code generate} previously had no duplicate-contract guard at all: a second {@code POST
     * /contracts} for a collaboration that already had a non-CANCELLED contract would silently
     * create a second row (both defaulting to {@code version=1}), making {@code
     * ContractRepository#findByCollaborationIdOrderByVersionDesc} — the lookup {@code DealService}
     * uses to pick "the" contract for a collaboration — order ties unpredictably. Proves the new
     * {@code existsByCollaborationIdAndStatusNot} guard rejects the second create with a clean 409
     * BEFORE any Contract/PaymentMilestone row is persisted.
     */
    @Test
    @DisplayName(
            "generate: a second create for a collaboration that already has a non-CANCELLED contract"
                    + " is rejected with CONTRACT_ALREADY_EXISTS (409) and persists nothing")
    void testGenerateRejectsDuplicateContractForCollaboration() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));
        // A non-CANCELLED contract (e.g. the one created by a prior, legitimate call) already
        // exists for this collaboration.
        when(contractRepository.existsByCollaborationIdAndStatusNot(
                        COLLABORATION_ID, ContractStatus.CANCELLED))
                .thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.generate(principal, WORKSPACE_ID, generateRequest()));

        assertEquals("CONTRACT_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
        verify(milestoneRepository, never()).saveAll(any());
    }

    /**
     * [BE-1 edge case] A CANCELLED contract must NOT block a fresh one — the guard specifically
     * excludes CANCELLED via {@code existsByCollaborationIdAndStatusNot(..., CANCELLED)}. Proves a
     * collaboration whose only prior contract is CANCELLED can still get a new one.
     */
    @Test
    @DisplayName(
            "generate: a collaboration whose only prior contract is CANCELLED is NOT blocked — a new"
                    + " contract is created normally")
    void testGenerateAllowsNewContractWhenOnlyCancelledContractExists() {
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

        ContractResponse response = service.generate(principal, WORKSPACE_ID, generateRequest());

        assertNotNull(response);
        verify(contractRepository, times(1)).save(any(Contract.class));
    }

    /**
     * [F-0283, contract-terms-never-persisted] Before this fix, {@code ContractGenerateRequest}
     * had no {@code terms} field at all, and the {@code Contract} column NAMED {@code terms} held
     * only a SHA-256 tamper hash of the request -- not terms. Proves the caller-supplied terms
     * text actually survives to the PERSISTED entity (the {@link Contract} instance passed to
     * {@code contractRepository.save}, captured here, not just some local variable inside
     * {@code generate}) AND comes back out on the read model ({@link ContractResponse#terms()}).
     * A fix that adds the field but never threads the value through either hop (F-0292's own
     * first gate missed exactly that shape) would fail this test.
     */
    @Test
    @DisplayName("generate: supplied terms text survives to the persisted entity and the response")
    void testGenerateTermsSurviveToPersistedEntityAndResponse() {
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

        String suppliedTerms = "Brand and creator agree to the deliverables and payment schedule above.";
        ContractGenerateRequest req =
                new ContractGenerateRequest(
                        COLLABORATION_ID,
                        suppliedTerms,
                        List.of(
                                new MilestoneWriteRequest(
                                        1, "Deliverable 1", BigDecimal.valueOf(5000), LocalDate.now())));

        ContractResponse response = service.generate(principal, WORKSPACE_ID, req);

        ArgumentCaptor<Contract> savedContract = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).save(savedContract.capture());
        assertEquals(suppliedTerms, savedContract.getValue().getTermsText());
        assertEquals(suppliedTerms, response.terms());
    }

    /**
     * [F-0283] The mirror image of the test above: no terms supplied must read back as {@code
     * null} on both the persisted entity and the response -- never a fabricated/default value.
     * This is the boundary the record explicitly does not authorize: deciding what a contract's
     * terms should say by default is a product/legal decision, not this fix's to make.
     */
    @Test
    @DisplayName("generate: omitted terms persist and return as null, never fabricated")
    void testGenerateWithNoTermsSuppliedReturnsNullTermsNotFabricated() {
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

        // generateRequest() uses the pre-existing 2-arg constructor -- no terms supplied.
        ContractResponse response = service.generate(principal, WORKSPACE_ID, generateRequest());

        ArgumentCaptor<Contract> savedContract = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).save(savedContract.capture());
        assertNull(savedContract.getValue().getTermsText());
        assertNull(response.terms());
    }

    /**
     * [F-0283] Immutability leg 1/2 — {@code Contract} must expose no public post-construction
     * mutator for {@code termsText} at all. {@code Contract.Builder#termsText} (construction-time,
     * before the entity has ever been persisted or is signable) is deliberately not what this
     * checks; this looks for a {@code setTermsText}/{@code updateTermsText} method on the entity
     * itself, the shape an unguarded "let a brand edit the terms" fix would take. A contract whose
     * clauses can change after either party has e-signed under the IT Act 2000 binding notice is
     * worse than one with no clauses at all (record scope) — the strictest available enforcement is
     * that no such door exists in the first place, not a runtime check on a door that does. This is
     * a reflection check specifically so it fails at the class-shape level, the same moment a wrong
     * fix that adds such a method would be introduced — not only when some particular test happens
     * to exercise it.
     */
    @Test
    @DisplayName(
            "Contract entity: termsText has no public post-construction mutator — write-once via"
                    + " Builder, immutable for the life of the entity, a fortiori once signed")
    void testContractEntityExposesNoTermsTextMutator() {
        boolean hasEntityLevelMutator =
                java.util.Arrays.stream(Contract.class.getDeclaredMethods())
                        .anyMatch(
                                m ->
                                        java.lang.reflect.Modifier.isPublic(m.getModifiers())
                                                && (m.getName().equals("setTermsText")
                                                        || m.getName().equals("updateTermsText")));
        org.junit.jupiter.api.Assertions.assertFalse(
                hasEntityLevelMutator,
                "Contract must not expose a public setTermsText/updateTermsText method -- terms text"
                        + " is write-once via Contract.Builder at construction time and must stay that"
                        + " way for the life of the entity, including after either party signs"
                        + " (F-0283)");
    }

    /**
     * [F-0283] Immutability leg 2/2 — even though no mutation path exists (leg 1 above), this
     * proves the specific scenario the record calls out by name: recording a signature must never,
     * as a side effect, alter the terms text that was captured at generation time. Signs BRAND
     * first (the contract is not yet fully executed, so no PDF/email side-effect chain fires and
     * this stays a narrow unit test of {@code doRecordSignature} alone), then asserts the SAME
     * {@link Contract} instance's {@code termsText} — and the response's {@code terms()} — are
     * byte-for-byte the value supplied at generation, not null, not altered, not re-derived.
     */
    @Test
    @DisplayName("recordSignature: signing a contract leaves its previously-captured terms text untouched")
    void testSignatureDoesNotAlterTermsText() {
        String suppliedTerms = "Usage rights: 90 days paid social. 2 rounds of revisions included.";
        Contract contract =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLABORATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .termsJson("{}")
                        .termsText(suppliedTerms)
                        .status(ContractStatus.DRAFT)
                        .build();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Test Brand Owner");

        assertEquals(suppliedTerms, contract.getTermsText());
        assertEquals(suppliedTerms, response.terms());
    }

    /**
     * [SEC: Kabir MEDIUM-1, contract-flow-architecture-2026-07-23 review — race close] Proves the
     * new pessimistic row lock ({@code CollaborationRepository#findByIdForUpdate}) is acquired
     * BEFORE the duplicate-contract exists-check and the insert it is meant to serialize — the
     * ordering that actually closes the race, not just that the lock method is called somewhere.
     */
    @Test
    @DisplayName(
            "generate: acquires the collaboration row lock BEFORE the duplicate-contract exists-check"
                    + " and the save (ordering that makes the guard race-safe)")
    void testGenerateAcquiresRowLockBeforeExistsCheckAndSave() {
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

        service.generate(principal, WORKSPACE_ID, generateRequest());

        InOrder order = inOrder(collaborationRepository, contractRepository);
        order.verify(collaborationRepository).findByIdForUpdate(COLLABORATION_ID);
        order.verify(contractRepository)
                .existsByCollaborationIdAndStatusNot(COLLABORATION_ID, ContractStatus.CANCELLED);
        order.verify(contractRepository).save(any(Contract.class));
    }

    /**
     * [SEC: Kabir MEDIUM-1, contract-flow-architecture-2026-07-23 review — race close] Simulates
     * two concurrent {@code generate} calls for the SAME collaboration and proves the second is
     * rejected rather than both succeeding. Mockito mocks cannot reproduce real MySQL row-lock
     * blocking, so this test models what the lock guarantees: {@code findByIdForUpdate} for the
     * SECOND caller does not return until the FIRST caller's {@code contractRepository.save} has
     * happened (exactly what a real {@code PESSIMISTIC_WRITE} lock held for the duration of the
     * transaction enforces — the second caller's row-lock acquisition blocks until the first
     * transaction commits). {@code existsByCollaborationIdAndStatusNot} reads a shared flag that
     * only flips true once the first {@code save} has run, so the second caller's exists-check
     * correctly observes the first caller's just-"committed" row. Mirrors the exact
     * latch-based simulation pattern already used for the escrow row lock
     * ({@code DisputeEscrowConcurrencyTest#concurrentFreezeAndRelease_freezeWins}).
     */
    @Test
    @DisplayName(
            "generate: simulated concurrent creates for the same collaboration — the lock serializes"
                    + " them so only ONE contract is ever saved, the other is rejected")
    void testConcurrentGenerateCallsAreSerializedByCollaborationLock() throws Exception {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));

        AtomicBoolean firstInsertCommitted = new AtomicBoolean(false);
        CountDownLatch firstSaveDone = new CountDownLatch(1);
        // Atomic winner-takes-n==1 assignment -- avoids a racy check-then-act on which caller
        // "is" first. Whichever of the two concurrent threads reaches findByIdForUpdate first
        // (genuinely racing, same as two real transactions) wins the lock immediately; the other
        // blocks until the winner's save() -- the stand-in for that transaction's COMMIT.
        java.util.concurrent.atomic.AtomicInteger lockCallOrder =
                new java.util.concurrent.atomic.AtomicInteger(0);
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenAnswer(
                        inv -> {
                            int order = lockCallOrder.incrementAndGet();
                            if (order > 1) {
                                firstSaveDone.await(5, TimeUnit.SECONDS);
                            }
                            return Optional.of(collaboration);
                        });
        when(contractRepository.existsByCollaborationIdAndStatusNot(
                        COLLABORATION_ID, ContractStatus.CANCELLED))
                .thenAnswer(inv -> firstInsertCommitted.get());
        // save() is the point a real INSERT would commit -- flip the shared flag and release the
        // blocked second caller's lock wait, exactly as a real COMMIT would release the row lock.
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            firstInsertCommitted.set(true);
                            firstSaveDone.countDown();
                            return null;
                        })
                .when(contractRepository)
                .save(any(Contract.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        try {
            Future<?> callA =
                    pool.submit(
                            () -> {
                                try {
                                    service.generate(principal, WORKSPACE_ID, generateRequest());
                                } catch (Throwable t) {
                                    errorA.set(t);
                                }
                            });
            Future<?> callB =
                    pool.submit(
                            () -> {
                                try {
                                    service.generate(principal, WORKSPACE_ID, generateRequest());
                                } catch (Throwable t) {
                                    errorB.set(t);
                                }
                            });

            callA.get(10, TimeUnit.SECONDS);
            callB.get(10, TimeUnit.SECONDS);

            // Exactly one of the two concurrent calls must have failed with
            // CONTRACT_ALREADY_EXISTS -- which one (A or B) genuinely races and is not
            // asserted; what matters is that not both succeeded and not both failed.
            List<Throwable> errors = new java.util.ArrayList<>();
            if (errorA.get() != null) errors.add(errorA.get());
            if (errorB.get() != null) errors.add(errorB.get());
            assertEquals(1, errors.size(), "exactly one concurrent create must be rejected");
            assertEquals(ApiException.class, errors.get(0).getClass());
            assertEquals("CONTRACT_ALREADY_EXISTS", ((ApiException) errors.get(0)).getCode());
            // Exactly one contract was ever persisted -- the race did NOT produce two rows.
            verify(contractRepository, times(1)).save(any(Contract.class));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * [SEC: Kabir MEDIUM-2, contract-flow-architecture-2026-07-23 review] A negative milestone
     * (net total still positive) must be rejected — {@code generate} previously only checked the
     * SUMMED total, so {@code [500, -200]} (net 300) slipped through.
     */
    @Test
    @DisplayName(
            "generate: rejects a negative milestone amount even when the net total is positive —"
                    + " INVALID_MILESTONE_AMOUNT (400), nothing persisted")
    void testGenerateRejectsNegativeMilestoneAmount() {
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
                        List.of(
                                new MilestoneWriteRequest(1, "Deliverable 1", BigDecimal.valueOf(500), LocalDate.now()),
                                new MilestoneWriteRequest(
                                        2, "Refund adjustment", BigDecimal.valueOf(-200), LocalDate.now())));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.generate(principal, WORKSPACE_ID, req));

        assertEquals("INVALID_MILESTONE_AMOUNT", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
        verify(milestoneRepository, never()).saveAll(any());
    }

    /**
     * [SEC: Kabir MEDIUM-2] The milestone total must not exceed the collaboration's negotiated
     * {@code agreedRate} — previously nothing bound the contract amount to the agreed deal value
     * at all.
     */
    @Test
    @DisplayName(
            "generate: rejects a milestone total that exceeds the collaboration's agreedRate —"
                    + " CONTRACT_TOTAL_EXCEEDS_AGREED_RATE (400), nothing persisted")
    void testGenerateRejectsTotalExceedingAgreedRate() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        collaboration.updateAgreedRate(BigDecimal.valueOf(3000)); // agreed rate = 3000
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));
        when(contractRepository.existsByCollaborationIdAndStatusNot(
                        COLLABORATION_ID, ContractStatus.CANCELLED))
                .thenReturn(false);
        // Milestone total (5000) exceeds the agreed rate (3000).
        ContractGenerateRequest req = generateRequest();

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.generate(principal, WORKSPACE_ID, req));

        assertEquals("CONTRACT_TOTAL_EXCEEDS_AGREED_RATE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
        verify(milestoneRepository, never()).saveAll(any());
    }

    /**
     * [SEC: Kabir MEDIUM-2 edge case] A milestone total that is LESS than the agreed rate (a
     * partial/rounder installment schedule) must still be allowed — the bound is "does not
     * exceed", not "must equal exactly".
     */
    @Test
    @DisplayName(
            "generate: a milestone total below the collaboration's agreedRate is allowed (bound is"
                    + " \"does not exceed\", not \"must equal\")")
    void testGenerateAllowsTotalBelowAgreedRate() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Collaboration collaboration = collaborationForCampaign(CAMPAIGN_ID);
        collaboration.updateAgreedRate(BigDecimal.valueOf(10000)); // agreed rate = 10000, well above 5000
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        Campaign campaign = Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).build();
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(campaign));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration));
        when(contractRepository.existsByCollaborationIdAndStatusNot(
                        COLLABORATION_ID, ContractStatus.CANCELLED))
                .thenReturn(false);

        ContractResponse response = service.generate(principal, WORKSPACE_ID, generateRequest());

        assertNotNull(response);
        verify(contractRepository, times(1)).save(any(Contract.class));
    }

    /**
     * [BE-1 (c): tenant isolation on sign] A brand principal authenticated into WORKSPACE_ID must
     * never be able to sign a contract that belongs to a DIFFERENT workspace — {@code
     * requireContract} scopes the lookup via {@code findByIdAndWorkspaceId(contractId,
     * workspaceId)}, so a contract that exists but belongs to OTHER_WORKSPACE_ID must come back as
     * CONTRACT_NOT_FOUND (never leaking its existence or letting the sign proceed).
     */
    @Test
    @DisplayName(
            "recordSignature: a user from another workspace cannot sign this collaboration's"
                    + " contract — CONTRACT_NOT_FOUND (404), no signature recorded")
    void testRecordSignatureRejectsContractFromAnotherWorkspace() {
        when(brandContext.requireMember(principal, OTHER_WORKSPACE_ID)).thenReturn(member);
        // The contract belongs to WORKSPACE_ID, not OTHER_WORKSPACE_ID -- the workspace-scoped
        // lookup must miss entirely.
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, OTHER_WORKSPACE_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.recordSignature(
                                        principal, OTHER_WORKSPACE_ID, CONTRACT_ID, "BRAND", "Test Signer"));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordSignature: first BRAND signature is recorded normally (not fully signed yet, no PDF)")
    void testFirstBrandSignatureRecorded() {
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Test Brand Owner");

        assertNotNull(response.brandSignedAt());
        assertEquals(null, response.creatorSignedAt());
        // [F-0292, signature-name-discarded-server-side] the typed signer name from the request
        // must survive onto the persisted entity, not just the timestamp.
        assertEquals("Test Brand Owner", response.brandSignerName());
        assertEquals("Test Brand Owner", contract.getBrandSignerName());
        verify(contractRepository, times(1)).save(contract);
        verifyNoInteractions(contractPdfService);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * [F-0292, signature-name-discarded-server-side] The brand e-sign UI
     * (contracts-and-deliverables.tsx) tells the user typing their full name and clicking "Sign
     * Contract" is the legally binding act under the IT Act 2000, and sends {@code {name,
     * agreedAt}} on the wire. This proves that name actually reaches the persisted {@link
     * Contract} entity via {@link ContractService#recordSignature} -- not merely the response DTO
     * (a mocked repository would let a response-only fix through undetected) -- by asserting
     * directly on the entity object passed to {@code contractRepository.save}.
     */
    @Test
    @DisplayName(
            "F-0292: the signer's typed full name survives from the sign request onto the"
                    + " persisted Contract entity, not just the response")
    void testSignerNameSurvivesToPersistedEntity() {
        Contract contract = unsignedContract();
        assertEquals(null, contract.getBrandSignerName());
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        mockIdempotencyExecuteOnce();

        service.recordSignature(
                principal, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Priya Sharma, Founder");

        ArgumentCaptor<Contract> saved = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository, times(1)).save(saved.capture());
        assertEquals("Priya Sharma, Founder", saved.getValue().getBrandSignerName());
        // The server's own clock stamps brandSignedAt, never a client-supplied timestamp.
        assertNotNull(saved.getValue().getBrandSignedAt());
    }

    /**
     * CR-22a, Kabir finding #1 — a genuinely NEW signature must be blocked on a CANCELLED
     * collaboration (belt-and-suspenders: post-narrowing, {@code canReject()} can no longer reach
     * a collaboration that has a Contract row, but this is the same guard {@code generate}/
     * {@code initiateFund} carry, and defends against any future path that writes CANCELLED).
     */
    @Test
    @DisplayName(
            "recordSignature: a NEW (not-yet-signed) signature on a CANCELLED collaboration returns"
                    + " 409 COLLABORATION_CANCELLED, does not record the signature")
    void testRecordSignatureRejectsCancelledCollaboration() {
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        Collaboration cancelled = collaborationForCampaign(CAMPAIGN_ID);
        cancelled.transitionTo(com.influora.domain.enums.CollaborationStatus.CANCELLED);
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(cancelled));
        mockIdempotencyExecuteOnce();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Test Brand Owner"));

        assertEquals("COLLABORATION_CANCELLED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(null, contract.getBrandSignedAt());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "recordSignature: a retried BRAND sign call (BRAND already signed) is an idempotent"
                    + " no-op -- does NOT re-touch the entity, save, or re-fire PDF/email delivery")
    void testRetriedBrandSignatureIsNoOp() {
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner"); // simulate the first, already-succeeded call
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Test Brand Owner");

        assertNotNull(response.brandSignedAt());
        // The guard short-circuits BEFORE contractRepository.save -- never re-persisted.
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(contractPdfService, r2StorageService, eventPublisher);
    }

    /**
     * [Swapnil ruling 2026-08-20] This used to drive the second signature through {@code
     * recordSignature(.., "CREATOR", ..)} -- the brand relaying the creator's out-of-band assent.
     * That relay is deleted (a brand may no longer complete a contract on a creator's behalf), so
     * the only way a contract reaches fully-signed is each party signing as themselves: the brand
     * via {@code recordSignature} and the creator via {@link
     * ContractService#recordSignatureForCreator}, which derives the CREATOR role from the
     * authenticated principal and scopes the lookup to that creator's own user id ({@code
     * findByIdAndCreatorId}) instead of the caller's workspace. What this test proves is unchanged:
     * the ONE call that completes the pair fires PDF generation + delivery exactly once.
     */
    @Test
    @DisplayName(
            "recordSignatureForCreator: the FIRST call that completes both signatures (BRAND already"
                    + " signed, now CREATOR signs) generates and delivers the PDF exactly once")
    void testSecondRoleCompletesSignatureAndFiresPdfOnce() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner"); // brand already signed in a prior call
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id("01HMILESTONE1234567AB")
                        .contractId(CONTRACT_ID)
                        .collaborationId(COLLABORATION_ID)
                        .sequenceNo(1)
                        .amount(BigDecimal.valueOf(5000))
                        .build();
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of(milestone));
        // generateAndDeliverContractPdf's internals (collaboration/campaign/workspace/user/R2)
        // will throw NPE-safe nulls or ApiExceptions internally, but that method catches all
        // exceptions and logs them -- it must never propagate. Stubbing minimal happy-path lookups
        // isn't required to prove the "fires exactly once" guarantee; contractPdfService.render
        // being invoked (or at least attempted) once is what matters here.
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.empty());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(escrowHoldRepository.hasEscrowForCollaboration(
                        eq(COLLABORATION_ID), any()))
                .thenReturn(false);
        mockIdempotencyExecuteOnce();

        ContractResponse response = service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator");

        assertNotNull(response.brandSignedAt());
        assertNotNull(response.creatorSignedAt());
        verify(contractRepository, times(1)).save(contract);
        // generateAndDeliverContractPdf + promptEscrowFundingIfNeeded both look up collaboration.
        verify(collaborationRepository, times(2)).findById(COLLABORATION_ID);
    }

    /**
     * Persistent application-history requirement — wiring the 8 remaining event types.
     *
     * <p>[Swapnil ruling 2026-08-20] Driven through {@link
     * ContractService#recordSignatureForCreator} now that the brand-relays-the-creator path is
     * deleted. The event assertion is the same one it always was — the completing signature records
     * CONTRACT_SIGNED exactly once, with {@code actorType = CREATOR} narrating WHICH signature
     * closed the pair and {@code actorId} being the principal that actually performed the write.
     * The only thing that changed is that those two now agree: the writer IS the creator, so
     * {@code actorId} is the creator's own user id rather than the relaying brand member's.
     */
    @Test
    @DisplayName(
            "recordSignatureForCreator: the call that completes both signatures records a"
                    + " CONTRACT_SIGNED application-history event exactly once")
    void testFullySignedRecordsApplicationHistoryEvent() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner");
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID)).thenReturn(List.of());
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.empty());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(escrowHoldRepository.hasEscrowForCollaboration(eq(COLLABORATION_ID), any())).thenReturn(false);
        mockIdempotencyExecuteOnce();

        service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator");

        verify(applicationHistoryService, times(1))
                .record(
                        eq(CAMPAIGN_ID),
                        eq(COLLABORATION_ID),
                        eq(COLLABORATION_ID),
                        eq(com.influora.domain.enums.ApplicationHistoryEventType.CONTRACT_SIGNED),
                        eq(com.influora.domain.enums.CollaborationStatus.CONTRACTED),
                        // actorType narrates WHICH signature just completed the pair (CREATOR here);
                        // actorId is still the calling principal that actually performed the write,
                        // never a fabricated identity. Since the relay was deleted that principal
                        // is the creator themselves, so the two now agree by construction.
                        eq(com.influora.domain.enums.ApplicationHistoryActorType.CREATOR),
                        eq(CREATOR_USER_ID),
                        anyString(),
                        // Sign-off review follow-on (#3) — metadata is null, no raw contract id.
                        org.mockito.ArgumentMatchers.isNull(),
                        eq("/creator/chat?deal=" + COLLABORATION_ID),
                        eq(COLLABORATION_ID));
    }

    /**
     * Distinct from {@code testRetriedCreatorSignatureForCreatorIsNoOp} below: there the contract
     * is only creator-signed, here it is FULLY executed (ACTIVE). That is the case the {@code
     * verifyNoInteractions(.., collaborationRepository)} guards — an idempotent replay of an
     * already-executed signature must touch nothing beyond the contract itself, in particular it
     * must not take the CR-22a collaboration row lock (see {@code doRecordSignature}'s javadoc,
     * which names this test by name for exactly that reason).
     *
     * <p>[Swapnil ruling 2026-08-20] Retried through {@link
     * ContractService#recordSignatureForCreator} since the brand-relayed CREATOR role no longer
     * exists; the replay path being exercised ({@code doRecordSignature}'s already-signed
     * short-circuit) is the same one either entry point reaches.
     */
    @Test
    @DisplayName(
            "recordSignatureForCreator: a retried CREATOR sign call after BOTH parties already"
                    + " signed is an idempotent no-op -- PDF/email delivery is never re-fired")
    void testRetriedSignatureAfterFullyExecutedIsNoOp() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner");
        contract.recordCreatorSignature("Test Creator");
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        mockIdempotencyExecuteOnce();

        ContractResponse response = service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator");

        assertEquals(ContractStatus.ACTIVE, response.status());
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(contractPdfService, r2StorageService, eventPublisher, collaborationRepository);
    }

    @Test
    @DisplayName("recordSignature: INVALID_SIGNER_ROLE (400) for a role that is neither BRAND nor CREATOR")
    void testInvalidRoleRejected() {
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "MANAGER", "Test Signer"));

        assertEquals("INVALID_SIGNER_ROLE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordSignature: CONTRACT_NOT_FOUND (404) when the contract does not exist for this workspace")
    void testContractNotFound() {
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignature(principal, WORKSPACE_ID, CONTRACT_ID, "BRAND", "Test Brand Owner"));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    @DisplayName(
            "getForCreator: creator can read their own contract via findByIdAndCreatorId scoped lookup")
    void testGetForCreatorOwnContract() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());

        ContractResponse response = service.getForCreator(principal, CONTRACT_ID);

        assertNotNull(response);
        assertEquals(CONTRACT_ID, response.id());
        verify(creatorContext).requireCreator(principal);
        verify(contractRepository).findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID);
        verify(contractRepository, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    @DisplayName(
            "getForCreator: creator cannot read another creator's contract — CONTRACT_NOT_FOUND (404)")
    void testGetForCreatorRejectsOtherCreatorContract() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.getForCreator(principal, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(contractRepository, never()).findById(any());
    }

    @Test
    @DisplayName(
            "getPdfDownloadUrlForCreator: creator cannot mint PDF URL for another creator's contract")
    void testGetPdfDownloadUrlForCreatorRejectsCrossCreatorIdor() {
        when(principal.getUserId()).thenReturn(OTHER_CREATOR_USER_ID);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, OTHER_CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.getPdfDownloadUrlForCreator(principal, CONTRACT_ID));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verifyNoInteractions(r2StorageService);
    }

    @Test
    @DisplayName(
            "recordSignatureForCreator: creator signs own contract when brand already signed —"
                    + " ACTIVE + escrow funding prompt fired")
    void testCreatorSignsOwnContractAndPromptsEscrow() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner");
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.hasEscrowForCollaboration(
                        eq(COLLABORATION_ID), any()))
                .thenReturn(false);
        when(collaborationRepository.findById(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(Campaign.builder().id(CAMPAIGN_ID).title("Summer Drop").build()));
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        WORKSPACE_ID, com.influora.domain.enums.MemberRole.OWNER))
                .thenReturn(Optional.empty());
        mockIdempotencyExecuteOnce();

        ContractResponse response = service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator");

        assertNotNull(response.creatorSignedAt());
        assertEquals(ContractStatus.ACTIVE, response.status());
        // [F-0292] the creator's typed name is persisted too, not just the brand's.
        assertEquals("Test Creator", response.creatorSignerName());
        assertEquals("Test Creator", contract.getCreatorSignerName());
        verify(contractRepository, times(1)).save(contract);
        verify(escrowHoldRepository)
                .hasEscrowForCollaboration(eq(COLLABORATION_ID), any());
    }

    /**
     * [CR-49 regression, method renamed by CR-50] Before CR-49, {@code promptEscrowFundingIfNeeded}
     * used the direct-{@code collaboration_id}-column derived query -- NULL on every hold the
     * ordinary brand escrow flow creates (see {@code EscrowHoldRepository}'s javadoc, CR-35/CR-39).
     * A hold reachable only via its milestone link (collaborationId column null) would have been
     * invisible to that query, so an already-FUNDED deal would still fire a spurious "please fund
     * escrow" notification. CR-50 deleted that derived method entirely, so the old regression is now
     * structurally impossible to reintroduce at this call site -- there is nothing else to call.
     */
    @Test
    @DisplayName(
            "[CR-49] recordSignatureForCreator: milestone-linked FUNDED hold (collaboration_id column"
                    + " null) correctly suppresses the escrow funding prompt")
    void testCreatorSignSuppressesPromptForMilestoneLinkedFundedHold() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner");
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        // The milestone-aware query reports FUNDED (reached only via its milestone link).
        when(escrowHoldRepository.hasEscrowForCollaboration(
                        eq(COLLABORATION_ID), any()))
                .thenReturn(true);
        mockIdempotencyExecuteOnce();

        ContractResponse response = service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator");

        assertNotNull(response.creatorSignedAt());
        // The notification (owner lookup) path is never reached -- escrow is already funded.
        // (generateAndDeliverContractPdf ALSO calls collaborationRepository.findById for an
        // unrelated reason, so that call alone isn't a reliable signal for this test.)
        verify(workspaceMemberRepository, never())
                .findFirstByWorkspaceIdAndRoleAndActiveTrue(any(), any());
    }

    @Test
    @DisplayName(
            "recordSignatureForCreator: retried creator sign after already signed is idempotent no-op")
    void testRetriedCreatorSignatureForCreatorIsNoOp() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordCreatorSignature("Test Creator");
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        mockIdempotencyExecuteOnce();

        ContractResponse response = service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator");

        assertNotNull(response.creatorSignedAt());
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(escrowHoldRepository, contractPdfService, eventPublisher);
    }

    @Test
    @DisplayName(
            "recordSignatureForCreator: creator cannot sign another creator's contract —"
                    + " CONTRACT_NOT_FOUND (404)")
    void testCreatorSignRejectsCrossCreatorIdor() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator"));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
    }

    @Test
    @DisplayName("listUnsignedForCreator: returns only contracts awaiting creator signature")
    void testListUnsignedForCreator() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract awaiting = unsignedContract();
        when(contractRepository.findUnsignedByCreatorId(CREATOR_USER_ID))
                .thenReturn(List.of(awaiting));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());

        List<ContractResponse> responses = service.listUnsignedForCreator(principal);

        assertEquals(1, responses.size());
        assertEquals(CONTRACT_ID, responses.get(0).id());
        assertEquals(null, responses.get(0).creatorSignedAt());
        verify(creatorContext).requireCreator(principal);
    }

    /**
     * Brand tracker P1-#1: {@code GET /contracts} for a brand caller was previously hard-403'd
     * (creator-only). {@code listForBrand} is the new brand-scoped list, wired the same way as
     * every other brand read on this service: {@code brandContext.requireMember} first, then
     * {@code ContractRepository#findByWorkspaceId} -- never a raw unscoped query.
     */
    @Test
    @DisplayName("listForBrand: returns contracts for the caller's own workspace only")
    void testListForBrandReturnsOwnWorkspaceContracts() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        Contract contract = unsignedContract();
        when(contractRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());

        List<ContractResponse> responses = service.listForBrand(principal, WORKSPACE_ID);

        assertEquals(1, responses.size());
        assertEquals(CONTRACT_ID, responses.get(0).id());
        assertEquals(WORKSPACE_ID, responses.get(0).workspaceId());
        verify(brandContext).requireMember(principal, WORKSPACE_ID);
        verify(contractRepository).findByWorkspaceId(WORKSPACE_ID);
        // Never falls back to an unscoped/global query.
        verify(contractRepository, never()).findAll();
    }

    /**
     * IDOR guard: a brand user who is NOT an active member of OTHER_WORKSPACE_ID must be rejected
     * by {@code brandContext.requireMember} before {@code ContractRepository#findByWorkspaceId} is
     * ever reached -- mirrors {@code testGenerateRejectsCrossWorkspaceCollaboration}'s
     * resolve-then-scope discipline so one brand can never enumerate another workspace's contracts.
     */
    @Test
    @DisplayName(
            "listForBrand: caller with no membership in OTHER_WORKSPACE_ID is rejected and never"
                    + " reaches the repository")
    void testListForBrandRejectsNonMemberOfOtherWorkspace() {
        when(brandContext.requireMember(principal, OTHER_WORKSPACE_ID))
                .thenThrow(
                        new ApiException(
                                "FORBIDDEN",
                                "You are not a member of this workspace",
                                org.springframework.http.HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.listForBrand(principal, OTHER_WORKSPACE_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verifyNoInteractions(contractRepository);
    }

    /**
     * Complements {@code testCreatorSignsOwnContractAndPromptsEscrow}, which stubs the workspace
     * OWNER lookup empty and therefore never reaches the publish: here a real OWNER is on file, so
     * this is the test that proves the {@link ContractReadyForEscrowEvent} is actually published to
     * someone once both signatures land. [Swapnil ruling 2026-08-20] The completing signature is
     * now the creator's own ({@link ContractService#recordSignatureForCreator}) rather than a brand
     * relay, so there is no {@code brandContext.requireMember} on this path at all.
     */
    @Test
    @DisplayName(
            "recordSignatureForCreator: dual signature publishes escrow funding prompt when no"
                    + " FUNDED hold exists")
    void testDualSignaturePublishesEscrowFundingPrompt() {
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner");
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, CREATOR_USER_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID))
                .thenReturn(List.of());
        when(escrowHoldRepository.hasEscrowForCollaboration(
                        eq(COLLABORATION_ID), any()))
                .thenReturn(false);
        when(collaborationRepository.findById(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaborationForCampaign(CAMPAIGN_ID)));
        when(campaignRepository.findByIdAndWorkspaceId(CAMPAIGN_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(Campaign.builder().id(CAMPAIGN_ID).title("Drop").build()));
        WorkspaceMember owner = org.mockito.Mockito.mock(WorkspaceMember.class);
        when(owner.getUserId()).thenReturn("01HBRANDOWNER123456");
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        WORKSPACE_ID, com.influora.domain.enums.MemberRole.OWNER))
                .thenReturn(Optional.of(owner));
        mockIdempotencyExecuteOnce();

        service.recordSignatureForCreator(principal, CONTRACT_ID, "Test Creator");

        verify(eventPublisher).publishEvent(any(ContractReadyForEscrowEvent.class));
    }

    /**
     * [Swapnil ruling 2026-08-20 — the guard this whole file was reshaped around] {@code
     * recordSignature} records the BRAND signature and nothing else. The brand-relays-the-creator's
     * -assent path it used to carry let a workspace member attribute a signature to a creator who
     * never made one, on a document the e-sign UI calls binding under the IT Act 2000. That relay
     * was previously tolerated only because it was the sole route to a fully-executed contract;
     * {@code recordSignatureForCreator} removed that excuse, so the relay is deleted outright.
     *
     * <p>Two things are asserted, and both matter. First, that {@code role=CREATOR} is REJECTED
     * (403 {@code CREATOR_SIGNATURE_NOT_RELAYABLE}) rather than silently downgraded to BRAND — a
     * quiet downgrade would file a real signature under the wrong party's name, which is worse than
     * the forgery it was meant to prevent. Second, and this is the part a bare {@code assertThrows}
     * would miss, that the contract is left completely untouched: no creator signature stamped on
     * the in-memory entity, still exactly half-signed (PENDING_SIGNATURES, never ACTIVE), nothing
     * persisted, and the idempotency/PDF/event machinery never entered — proving the rejection
     * happens before any write rather than after a partial one.
     */
    @Test
    @DisplayName(
            "recordSignature: role=CREATOR is rejected with CREATOR_SIGNATURE_NOT_RELAYABLE (403)"
                    + " and the contract is left untouched -- a brand cannot sign for a creator")
    void testCreatorSignatureCannotBeRelayedByBrand() {
        Contract contract = unsignedContract();
        contract.recordBrandSignature("Test Brand Owner"); // brand side is legitimately done
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.recordSignature(
                                        principal, WORKSPACE_ID, CONTRACT_ID, "CREATOR", "Test Creator"));

        assertEquals("CREATOR_SIGNATURE_NOT_RELAYABLE", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // The contract is NOT modified: no creator signature, no promotion to ACTIVE.
        assertNull(contract.getCreatorSignedAt());
        assertNull(contract.getCreatorSignerName());
        assertEquals(ContractStatus.PENDING_SIGNATURES, contract.getStatus());
        assertNotNull(contract.getBrandSignedAt()); // the pre-existing brand signature survives
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(idempotencyService, contractPdfService, r2StorageService, eventPublisher);
    }
}
