package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import com.influora.web.dto.money.MoneyDtos.MilestoneWriteRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * [F-0653/F-0654, contract-lifecycle-consistency — coordinated fix] One owner, one coherent
 * model, all four findings from the same review together:
 *
 * <ul>
 *   <li>F-0644 (dropped-field) and F-0645 (stale-binding-after-amend) — the disagreement between
 *       {@code DealService#resolveCurrentContract} and {@code
 *       DeliverableMetricService#getCampaignAnalytics} that the previous, rejected attempt shipped
 *       (see {@code DeliverableMetricServiceTest}'s corrected draft-window test for that half).
 *   <li>F-0653 (conflicting-current-contract-definitions) — both services now call the ONE shared
 *       {@link ContractService#resolveCurrentContract}, proven here to agree with the real,
 *       end-to-end contract state this class produces.
 *   <li>F-0654 (terminal-status-never-reached) — THIS class's core assertion: signing an amendment
 *       to completion, via the real {@link ContractService#recordSignature}/{@link
 *       ContractService#recordSignatureForCreator} flow (not a hand-constructed {@code CANCELLED}
 *       predecessor — the exact shape the fresh-context CTO review rejected the previous attempt
 *       for), must leave EXACTLY ONE {@link ContractStatus#ACTIVE} contract for the collaboration.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ContractAmendmentSupersessionTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
    private static final String ORIGINAL_CONTRACT_ID = "01HCONTRACTORIGINAL01";

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
    @Mock private AuthPrincipal brandPrincipal;
    @Mock private AuthPrincipal creatorPrincipal;
    @Mock private WorkspaceMember member;

    private ContractService contractService;

    /**
     * Live view of "the database" for this collaboration's contracts — a single mutable list
     * both {@code ContractService} (via the mocked repository) and this test's own assertions
     * read from, so a status mutation the production code makes (via {@code
     * Contract#setStatus}/{@code #recordBrandSignature}/{@code #recordCreatorSignature}, all of
     * which mutate the SAME entity instances held in this list) is immediately visible everywhere,
     * exactly like a real re-read from the DB would show.
     */
    private final List<Contract> contractVersions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        contractService =
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

        // Newest-(version,createdAt)-first, mirroring
        // ContractRepository#findByCollaborationIdOrderByVersionDescCreatedAtDesc's own contract.
        // Re-sorted on every call so a newly-amended row (added at the end by #amend's capture,
        // below) is seen in the right position without the test manually re-ordering it.
        when(contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(COLLABORATION_ID))
                .thenAnswer(
                        inv -> {
                            List<Contract> sorted = new ArrayList<>(contractVersions);
                            sorted.sort(
                                    (a, b) -> {
                                        int byVersion = Integer.compare(b.getVersion(), a.getVersion());
                                        return byVersion != 0
                                                ? byVersion
                                                : b.getCreatedAt().compareTo(a.getCreatedAt());
                                    });
                            return sorted;
                        });
    }

    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<ContractResponse> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private Collaboration collaboration() {
        return Collaboration.invite(COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
    }

    /**
     * [F-0658 fix, misleading-test-javadoc] This javadoc previously claimed the ORIGINAL contract
     * (version 1) was drafted and fully signed via the real generate -> recordSignature ->
     * recordSignatureForCreator path. It is not: the code below hand-constructs {@code original}
     * directly via {@code Contract.builder()...status(ContractStatus.ACTIVE)}, bypassing the real
     * signature flow entirely -- the same shape of shortcut the class javadoc itself says the
     * fresh-context CTO review rejected the previous attempt for ("not a hand-constructed CANCELLED
     * predecessor"). What this method actually does: hand-constructs an already-{@code ACTIVE}
     * predecessor (v1) as a fixture, then drives {@link ContractService#amend} for real to produce
     * a genuinely {@code DRAFT} amendment (v2) on top of it -- exactly the post-condition {@link
     * ContractService#amend}'s own javadoc describes ("What happens to the row being superseded").
     * The REAL signature flow this method's name/old javadoc described is exercised by its caller,
     * {@link #testSigningAmendmentToCompletionRetiresActivePredecessor}, against the AMENDMENT (v2)
     * only -- v1 is never signed through that path in this test. Returns the amendment (version 2).
     */
    private Contract buildActivePredecessorWithDraftAmendment() {
        Contract original =
                Contract.builder()
                        .id(ORIGINAL_CONTRACT_ID)
                        .collaborationId(COLLABORATION_ID)
                        .workspaceId(WORKSPACE_ID)
                        .version(1)
                        .totalAmount(BigDecimal.valueOf(5000))
                        .status(ContractStatus.ACTIVE)
                        .build();
        contractVersions.add(original);

        when(brandContext.requireMember(brandPrincipal, WORKSPACE_ID)).thenReturn(member);
        when(contractRepository.findByIdAndWorkspaceId(ORIGINAL_CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(original));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration()));

        ContractAmendRequest req =
                new ContractAmendRequest(
                        "Revised terms",
                        List.of(
                                new MilestoneWriteRequest(
                                        1, "Revised deliverable", BigDecimal.valueOf(6000), LocalDate.now())));

        ContractResponse amendResponse =
                contractService.amend(brandPrincipal, WORKSPACE_ID, ORIGINAL_CONTRACT_ID, req);

        ArgumentCaptor<Contract> savedAmendment = ArgumentCaptor.forClass(Contract.class);
        // amend() saves exactly one row when superseding an ACTIVE predecessor -- the new draft.
        org.mockito.Mockito.verify(contractRepository, org.mockito.Mockito.atLeastOnce())
                .save(savedAmendment.capture());
        Contract amendment =
                savedAmendment.getAllValues().stream()
                        .filter(c -> c.getId().equals(amendResponse.id()))
                        .findFirst()
                        .orElseThrow();
        contractVersions.add(amendment);

        // The predecessor must still be ACTIVE and untouched -- F-0645's own guarantee, the
        // precondition this test's OWN scenario depends on.
        assertEquals(ContractStatus.ACTIVE, original.getStatus());
        assertEquals(ContractStatus.DRAFT, amendment.getStatus());
        return amendment;
    }

    /**
     * [F-0654 — THE core assertion] Signs the amendment to completion via the REAL signature flow
     * (brand then creator, exactly the two calls a real e-sign UI makes) and proves the terminal-
     * status gap is closed: the predecessor -- still {@code ACTIVE} the whole time the amendment
     * sat as a draft -- is retired to {@code COMPLETED} the moment the amendment itself becomes
     * fully executed, leaving EXACTLY ONE {@code ACTIVE} contract for the collaboration. Before
     * this fix, {@code Contract#setStatus} had zero call sites that could ever produce this
     * transition -- the predecessor would stay {@code ACTIVE} forever, alongside the now-also-
     * {@code ACTIVE} amendment.
     */
    @Test
    @DisplayName(
            "signing an amendment to completion via the real signature flow retires the ACTIVE"
                    + " predecessor to COMPLETED -- exactly one ACTIVE contract survives")
    void testSigningAmendmentToCompletionRetiresActivePredecessor() {
        Contract amendment = buildActivePredecessorWithDraftAmendment();
        Contract original = contractVersions.get(0); // added first, still the only element with this id
        assertEquals(ORIGINAL_CONTRACT_ID, original.getId());

        when(contractRepository.findByIdAndWorkspaceId(amendment.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(amendment));
        when(contractRepository.findByIdAndCreatorId(amendment.getId(), CREATOR_USER_ID))
                .thenReturn(Optional.of(amendment));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(amendment.getId()))
                .thenReturn(List.of());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration()));
        // Best-effort PDF/escrow-prompt side effects inside the fully-signed branch -- stubbed
        // minimally so they no-op cleanly, mirroring ContractServiceTest's own pattern for the
        // exact same "second signature completes the pair" scenario.
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.empty());
        when(escrowHoldRepository.hasEscrowForCollaboration(eq(COLLABORATION_ID), any()))
                .thenReturn(false);
        mockIdempotencyExecuteOnce();

        // Brand signs first -- not yet fully executed, predecessor must still be untouched.
        contractService.recordSignature(brandPrincipal, WORKSPACE_ID, amendment.getId(), "BRAND", "Brand Owner");
        assertEquals(ContractStatus.ACTIVE, original.getStatus(), "predecessor untouched by the FIRST signature");

        // Creator signs second -- completes the pair, must fire the retirement.
        when(creatorPrincipal.getUserId()).thenReturn(CREATOR_USER_ID);
        ContractResponse signedResponse =
                contractService.recordSignatureForCreator(creatorPrincipal, amendment.getId(), "Test Creator");

        assertEquals(ContractStatus.ACTIVE, signedResponse.status(), "the amendment itself is now ACTIVE");
        assertEquals(ContractStatus.ACTIVE, amendment.getStatus());
        assertEquals(
                ContractStatus.COMPLETED,
                original.getStatus(),
                "F-0654: the superseded predecessor must be retired out of ACTIVE once the"
                        + " amendment that replaces it is genuinely, fully signed");

        long activeCount =
                contractVersions.stream().filter(c -> c.getStatus() == ContractStatus.ACTIVE).count();
        assertEquals(1, activeCount, "exactly one ACTIVE contract must survive for the collaboration");

        // [F-0653 cross-check] The shared resolution DealService now delegates to must agree:
        // reading the same live contract list back returns the amendment, not the retired
        // predecessor and not an ambiguous pick between two ACTIVE rows.
        List<Contract> current =
                contractRepository.findByCollaborationIdOrderByVersionDescCreatedAtDesc(COLLABORATION_ID);
        Contract resolved = ContractService.resolveCurrentContract(current);
        assertNotNull(resolved);
        assertEquals(amendment.getId(), resolved.getId());
        assertEquals(ContractStatus.ACTIVE, resolved.getStatus());
    }

    /**
     * Mirror-image guard: while the amendment is still only HALF-signed (brand only), the
     * predecessor must remain untouched -- the retirement is scoped to the moment BOTH signatures
     * land, not fired eagerly on the first one. Reuses {@link #buildActivePredecessorWithDraftAmendment()}.
     */
    @Test
    @DisplayName("a half-signed amendment (brand only) does not retire the ACTIVE predecessor")
    void testHalfSignedAmendmentDoesNotRetirePredecessor() {
        Contract amendment = buildActivePredecessorWithDraftAmendment();
        Contract original = contractVersions.get(0);

        when(contractRepository.findByIdAndWorkspaceId(amendment.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(amendment));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(amendment.getId()))
                .thenReturn(List.of());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(Optional.of(collaboration()));
        mockIdempotencyExecuteOnce();

        contractService.recordSignature(brandPrincipal, WORKSPACE_ID, amendment.getId(), "BRAND", "Brand Owner");

        assertEquals(ContractStatus.PENDING_SIGNATURES, amendment.getStatus());
        assertEquals(ContractStatus.ACTIVE, original.getStatus());
        long activeCount =
                contractVersions.stream().filter(c -> c.getStatus() == ContractStatus.ACTIVE).count();
        assertEquals(1, activeCount);
    }
}
