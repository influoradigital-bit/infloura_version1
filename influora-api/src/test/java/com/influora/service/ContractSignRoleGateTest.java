package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.User;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.ContractStatus;
import com.influora.domain.enums.MemberRole;
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
import com.influora.web.dto.money.MoneyDtos.ContractResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * [contractsign] {@code ContractService#recordSignature} resolved the caller's workspace membership
 * and then never checked their ROLE, while contract {@code generate} (ContractService.java:148),
 * {@code amend} (:444) and {@code cancel} (:1057) all follow {@code requireMember} with {@code
 * requireRole}. A VIEWER or MEMBER could therefore put the brand's signature on the document the
 * e-sign UI calls legally binding, committing the workspace to the contract's milestone amounts --
 * amounts that same VIEWER cannot fund, release or pay out (EscrowService, PayoutService and
 * WalletTopUpService are all {@code requireRole(OWNER, ADMIN)}).
 *
 * <p><b>Why a REAL {@link BrandContextService} (only its repositories mocked), the same choice
 * {@link CampaignAuthzTest} documents:</b> {@link ContractServiceTest} mocks {@code brandContext}
 * outright, so {@code requireRole} there is a no-op that neither passes nor fails on anything the
 * service does -- a gate deleted from {@code recordSignature} would leave every one of those tests
 * green. Running the real role check is what lets a VIEWER genuinely reach enforcement, and is what
 * makes the falsification meaningful: delete the {@code requireRole} line from {@code
 * recordSignature} and the VIEWER/MEMBER cases here go red.
 *
 * <p><b>Gate chosen, and the divergence it leaves:</b> OWNER/ADMIN, the money gate, not the
 * OWNER/ADMIN/MANAGER gate contract drafting uses. See {@code testManagerIsRefusedToday}.
 *
 * <p><b>One flow, AI included:</b> nothing here is AI-specific and nothing needs to be. Meera can
 * draft contract terms and explain them, but the signature goes through this same method behind
 * this same gate, so an assistant acting for a VIEWER is refused exactly as the VIEWER clicking
 * "Sign Contract" is.
 */
@ExtendWith(MockitoExtension.class)
class ContractSignRoleGateTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String BRAND_USER_ID = "01HBRANDUSER123456AB";
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
    @Mock private ContractPdfService contractPdfService;
    @Mock private R2StorageService r2StorageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private IdempotencyService idempotencyService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private ContractService service;

    @BeforeEach
    void setUp() {
        BrandContextService brandContext =
                new BrandContextService(workspaceRepository, workspaceMemberRepository, userRepository);
        CreatorContextService creatorContext =
                new CreatorContextService(creatorProfileRepository, userRepository);
        // requireBrand/requireCreator re-read the user row for deletedAt (F-0708 / Kabir H-1).
        // lenient: the WRONG_USER_TYPE case throws before the lookup, and each test uses only one.
        Mockito.lenient()
                .when(userRepository.findById(BRAND_USER_ID))
                .thenReturn(
                        Optional.of(
                                User.newBrand(
                                        BRAND_USER_ID, "brand@test.com", "hash", "Bee", "Rand", "Bee Rand")));
        Mockito.lenient()
                .when(userRepository.findById(OTHER_CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(
                                User.newCreator(
                                        OTHER_CREATOR_USER_ID, "other@test.com", "hash", "Oth", "Er", "Oth Er")));
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

    private AuthPrincipal brandPrincipal() {
        return new AuthPrincipal(BRAND_USER_ID, "brand@test.com", UserType.BRAND, WORKSPACE_ID);
    }

    private void memberWithRole(MemberRole role) {
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(
                        WORKSPACE_ID, BRAND_USER_ID))
                .thenReturn(
                        Optional.of(WorkspaceMember.fromInvite("mem1", WORKSPACE_ID, BRAND_USER_ID, role)));
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

    /** Mirrors {@code ContractServiceTest#mockIdempotencyExecuteOnce}: run the supplier inline. */
    private void mockIdempotencyExecuteOnce() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<ContractResponse> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    @ParameterizedTest
    @EnumSource(
            value = MemberRole.class,
            names = {"VIEWER", "MEMBER"})
    @DisplayName(
            "contractsign: a VIEWER/MEMBER workspace member cannot sign a contract -> FORBIDDEN"
                    + " (403), the contract is never even loaded and no signature row is written")
    void testLowPrivilegeRoleCannotSign(MemberRole role) {
        memberWithRole(role);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.recordSignature(
                                        brandPrincipal(), WORKSPACE_ID, CONTRACT_ID, "BRAND", "Vee Ewer"));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // The gate sits BEFORE requireContract, so a refused caller cannot even confirm the
        // contract exists, and nothing downstream runs: no save, no idempotency reservation, no
        // PDF, no notification.
        verifyNoInteractions(
                contractRepository,
                milestoneRepository,
                idempotencyService,
                contractPdfService,
                eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(
            value = MemberRole.class,
            names = {"OWNER", "ADMIN"})
    @DisplayName(
            "contractsign: OWNER/ADMIN still sign normally -- the brand signature is persisted onto"
                    + " the Contract entity")
    void testOwnerAndAdminCanStillSign(MemberRole role) {
        memberWithRole(role);
        Contract contract = unsignedContract();
        when(contractRepository.findByIdAndWorkspaceId(CONTRACT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(contract));
        when(milestoneRepository.findByContractIdOrderBySequenceNoAsc(CONTRACT_ID)).thenReturn(List.of());
        when(collaborationRepository.findByIdForUpdate(COLLABORATION_ID))
                .thenReturn(
                        Optional.of(
                                Collaboration.invite(
                                        COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));
        mockIdempotencyExecuteOnce();

        ContractResponse response =
                service.recordSignature(brandPrincipal(), WORKSPACE_ID, CONTRACT_ID, "BRAND", "Owen Ner");

        assertNotNull(response.brandSignedAt());
        assertEquals("Owen Ner", contract.getBrandSignerName());
        assertNotNull(contract.getBrandSignedAt());
        verify(contractRepository, times(1)).save(contract);
    }

    /**
     * Pins the one DIVERGENCE this change leaves, so it cannot be widened or narrowed by accident.
     * Contract {@code generate}/{@code amend}/{@code cancel} allow OWNER, ADMIN and MANAGER; this
     * gate matches the MONEY paths (EscrowService:236/:743/:775/:1106..:1217, PayoutService:219,
     * WalletTopUpService:103) and allows OWNER/ADMIN only -- so a MANAGER may draft, amend and
     * cancel a contract but not sign one, exactly as a MANAGER may not fund escrow or release a
     * payout. Deliberate, but UNRULED: if MANAGERs are meant to commit the workspace, widen the
     * gate in ContractService and this test goes red on purpose.
     */
    @Test
    @DisplayName(
            "contractsign (divergence, ruling pending): MANAGER -- allowed to generate/amend/cancel"
                    + " -- is refused at signing, matching the money gate not the drafting gate")
    void testManagerIsRefusedToday() {
        memberWithRole(MemberRole.MANAGER);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.recordSignature(
                                        brandPrincipal(), WORKSPACE_ID, CONTRACT_ID, "BRAND", "Man Ager"));

        assertEquals("FORBIDDEN", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verifyNoInteractions(contractRepository, idempotencyService);
    }

    // ------------------------------------------------------------------------------------------
    // Creator side of signing. There is no creator equivalent of the brand hole because there is
    // no creator equivalent of WorkspaceMember -- MemberRole is a brand-only concept, a creator
    // account is one human, and recordSignatureForCreator scopes the contract by
    // findByIdAndCreatorId(contractId, principal.getUserId()) rather than by a workspace the
    // caller merely belongs to. The two tests below are what that claim rests on: no other
    // creator can reach the contract, and a brand principal cannot enter the creator path at all.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "creator side: a creator cannot sign a contract that is not their own --"
                    + " CONTRACT_NOT_FOUND (404), no signature written (there is no delegate path)")
    void testAnotherCreatorCannotSign() {
        AuthPrincipal otherCreator =
                new AuthPrincipal(OTHER_CREATOR_USER_ID, "other@test.com", UserType.CREATOR, null);
        when(contractRepository.findByIdAndCreatorId(CONTRACT_ID, OTHER_CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignatureForCreator(otherCreator, CONTRACT_ID, "Oth Er"));

        assertEquals("CONTRACT_NOT_FOUND", ex.getCode());
        assertEquals(404, ex.getStatus().value());
        verify(contractRepository, never()).save(any());
        verifyNoInteractions(idempotencyService, contractPdfService, eventPublisher);
    }

    @Test
    @DisplayName(
            "creator side: a BRAND principal cannot enter the creator signing path --"
                    + " WRONG_USER_TYPE (403), so no brand role can relay a creator's signature")
    void testBrandPrincipalCannotUseCreatorSigningPath() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.recordSignatureForCreator(brandPrincipal(), CONTRACT_ID, "Bee Rand"));

        assertEquals("WRONG_USER_TYPE", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verifyNoInteractions(contractRepository, idempotencyService);
    }
}
