package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.InsufficientFundsException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.escrow.EscrowBackend;
import com.influora.service.escrow.LedgerEscrowBackend;
import com.influora.web.dto.money.MoneyDtos.EscrowFundResponse;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * EV-002 — a milestone must be funded (and the brand wallet debited) at most once, whatever
 * Idempotency-Key each attempt carries.
 *
 * <p>Before the fix, {@code EscrowService#initiateFund}'s only dedupe was the client key. The
 * frontend keeps that key in a per-mount ref, so a reload, a second tab or a click after a lost
 * response sent a fresh key: a second hold was created, the wallet was debited a second time
 * ({@code "escrow-fund:" + holdId} is a new ledger key per hold), and {@code
 * PaymentMilestone#markFunded} overwrote {@code escrowHoldId}, orphaning the first hold.
 *
 * <p>Runs the real {@link LedgerEscrowBackend} over a mocked {@link WalletLedgerService}, so
 * "no ledger post" below means no wallet debit was attempted. Mockito cannot prove row locking;
 * {@code PaymentMilestoneRepositoryForUpdateTest} runs the locking query on H2.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscrowServiceMilestoneDoubleFundTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String OTHER_CAMPAIGN_ID = "01HCAMPAIGNOTHER999AB";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
    private static final String CONTRACT_ID = "01HCONTRACT1234567AB";
    private static final String MILESTONE_ID = "01HMILESTONE1234567AB";
    private static final String MILESTONE_2_ID = "01HMILESTONE2234567AB";
    private static final String FIRST_HOLD_ID = "01HFIRSTHOLD1234567AB";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(5000);

    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private WalletLedgerService ledgerService;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private PlatformFeeService platformFeeService;
    @Mock private WalletService walletService;
    @Mock private BrandContextService brandContext;
    @Mock private CreatorContextService creatorContext;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember workspaceMember;
    @Mock private CampaignServiceInvoiceService campaignServiceInvoiceService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private EscrowService service;
    private int ledgerPostCount;

    @BeforeEach
    void setUp() {
        EscrowBackend escrowBackend =
                new LedgerEscrowBackend(ledgerService, platformWalletService, platformFeeService, walletService);
        service =
                new EscrowService(
                        escrowHoldRepository,
                        milestoneRepository,
                        campaignRepository,
                        collaborationRepository,
                        contractRepository,
                        disputeRepository,
                        walletService,
                        brandContext,
                        creatorContext,
                        campaignServiceInvoiceService,
                        deliverableRepository,
                        workspaceRepository,
                        eventPublisher,
                        collaborationLifecycleService,
                        escrowBackend,
                        applicationHistoryService);

        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
        Contract fullySigned =
                Contract.builder()
                        .id(CONTRACT_ID)
                        .collaborationId(COLLAB_ID)
                        .workspaceId(WORKSPACE_ID)
                        .totalAmount(AMOUNT)
                        .build();
        fullySigned.recordBrandSignature("Brand Owner");
        fullySigned.recordCreatorSignature("Creator");
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(fullySigned));
        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        when(collaborationRepository.findByIdForUpdate(COLLAB_ID)).thenReturn(Optional.of(collaboration));
        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration));
        // Lenient on purpose: not read on this base, but the pending F-0873 change locks the campaign
        // row in initiateFund. Stubbing it keeps this class valid before and after that merge.
        when(campaignRepository.findByIdForUpdate(any()))
                .thenReturn(
                        Optional.of(
                                Campaign.builder()
                                        .id(CAMPAIGN_ID)
                                        .workspaceId(WORKSPACE_ID)
                                        .title("EV-002 campaign")
                                        .currency("INR")
                                        .createdBy("brand_user_1")
                                        .build()));
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(wallet("01HBRANDWALLET12345AB", AMOUNT.multiply(BigDecimal.TEN)));
        when(platformWalletService.requireClearingWallet())
                .thenReturn(wallet("01HCLEARING1234567890", BigDecimal.ZERO));
        ledgerPostCount = 0;
        when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            ledgerPostCount++;
                            return new WalletLedgerService.LedgerPostingResult(
                                    WalletTransaction.builder().id("wtx-debit-" + ledgerPostCount).build(),
                                    WalletTransaction.builder().id("wtx-credit-" + ledgerPostCount).build());
                        });
    }

    private static Wallet wallet(String id, BigDecimal balance) {
        return new Wallet() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public BigDecimal getBalance() {
                return balance;
            }

            @Override
            public String getCurrency() {
                return "INR";
            }
        };
    }

    private static PaymentMilestone pendingMilestone(String id) {
        return PaymentMilestone.builder()
                .id(id)
                .contractId(CONTRACT_ID)
                .collaborationId(COLLAB_ID)
                .amount(AMOUNT)
                .build();
    }

    /** Registers the milestone for both the locked gate lookup and applyFunding's findById. */
    private PaymentMilestone register(PaymentMilestone milestone) {
        when(milestoneRepository.findByIdAndWorkspaceIdForUpdate(milestone.getId(), WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        return milestone;
    }

    private EscrowFundResponse fund(String milestoneId, String campaignId, String key) {
        return service.initiateFund(principal, WORKSPACE_ID, campaignId, milestoneId, AMOUNT, "INR", key);
    }

    /** A milestone already funded by FIRST_HOLD_ID, then moved on to {@code status}. */
    private static PaymentMilestone milestoneAlreadyIn(MilestoneStatus status) {
        PaymentMilestone m = pendingMilestone(MILESTONE_ID);
        m.markFunded(FIRST_HOLD_ID);
        Consumer<PaymentMilestone> advance =
                switch (status) {
                    case FUNDED -> x -> {};
                    case FROZEN -> PaymentMilestone::markFrozen;
                    case RELEASED -> x -> x.markReleased("wtx-release-1", "release:" + FIRST_HOLD_ID);
                    case REFUNDED -> x -> x.markRefunded("wtx-refund-1", "refund:" + FIRST_HOLD_ID);
                    case PENDING -> throw new IllegalArgumentException("not an already-funded status");
                };
        advance.accept(m);
        assertEquals(status, m.getStatus());
        return m;
    }

    @Test
    @DisplayName("EV-002: a second fund with a NEW key on the same milestone is refused 409 and debits once")
    void secondFundWithNewKeyIsRefusedAndDebitsOnce() {
        PaymentMilestone milestone = register(pendingMilestone(MILESTONE_ID));

        EscrowFundResponse first = fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-tab-1");
        assertEquals(EscrowStatus.FUNDED, first.status());
        assertEquals(first.escrowHoldId(), milestone.getEscrowHoldId());

        ApiException ex =
                assertThrows(ApiException.class, () -> fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-tab-2"));

        assertEquals("MILESTONE_ALREADY_FUNDED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(1, ledgerPostCount, "the brand wallet must be debited exactly once");
        assertEquals(first.escrowHoldId(), milestone.getEscrowHoldId(), "milestone must keep its first hold");
        assertEquals(MilestoneStatus.FUNDED, milestone.getStatus());
        // One hold only: created PENDING then saved FUNDED, both inside the first call.
        verify(escrowHoldRepository, times(2)).save(any(EscrowHold.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MilestoneStatus.class, names = {"FUNDED", "FROZEN", "RELEASED", "REFUNDED"})
    @DisplayName("EV-002: a new-key fund on a FUNDED/FROZEN/RELEASED/REFUNDED milestone is refused before any debit")
    void newKeyOnAlreadyFundedMilestoneIsRefusedBeforeAnyDebit(MilestoneStatus status) {
        PaymentMilestone milestone = register(milestoneAlreadyIn(status));

        ApiException ex =
                assertThrows(ApiException.class, () -> fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-fresh"));

        assertEquals("MILESTONE_ALREADY_FUNDED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(0, ledgerPostCount);
        // Refused before the balance read, so a funded milestone can never prompt a top-up either.
        verify(walletService, never()).requireWorkspaceWallet(any());
        verify(escrowHoldRepository, never()).save(any());
        assertEquals(FIRST_HOLD_ID, milestone.getEscrowHoldId());
        assertEquals(status, milestone.getStatus());
        // The refusal must not leak the regulated-sounding word into user copy.
        assertEquals(-1, ex.getMessage().toLowerCase().indexOf("escrow"), ex.getMessage());
    }

    @Test
    @DisplayName("EV-002: a same-key replay still returns the original hold (no 409, no second debit)")
    void sameKeyReplayStillReturnsOriginalHold() {
        register(milestoneAlreadyIn(MilestoneStatus.FUNDED));
        EscrowHold original =
                EscrowHold.builder()
                        .id(FIRST_HOLD_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .milestoneId(MILESTONE_ID)
                        .amount(AMOUNT)
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("escrow-key-tab-1")
                        .build();
        when(escrowHoldRepository.findByIdempotencyKey("escrow-key-tab-1")).thenReturn(Optional.of(original));

        EscrowFundResponse replay = fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-tab-1");

        assertEquals(FIRST_HOLD_ID, replay.escrowHoldId());
        assertEquals(EscrowStatus.FUNDED, replay.status());
        assertNull(replay.razorpayOrderId());
        assertEquals(0, ledgerPostCount);
        verify(escrowHoldRepository, never()).save(any());
    }

    @Test
    @DisplayName("EV-002: a PENDING milestone still funds normally and is bound to its new hold")
    void pendingMilestoneStillFunds() {
        PaymentMilestone milestone = register(pendingMilestone(MILESTONE_ID));

        EscrowFundResponse response = fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-1");

        assertEquals(EscrowStatus.FUNDED, response.status());
        assertNotNull(response.escrowHoldId());
        assertEquals(1, ledgerPostCount);
        assertEquals(MilestoneStatus.FUNDED, milestone.getStatus());
        assertEquals(response.escrowHoldId(), milestone.getEscrowHoldId());
        verify(milestoneRepository).save(milestone);
    }

    @Test
    @DisplayName("EV-002: the milestone is loaded under the row lock, never by the plain lookup, in initiateFund")
    void initiateFundLoadsMilestoneUnderLock() {
        register(pendingMilestone(MILESTONE_ID));

        fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-1");

        verify(milestoneRepository).findByIdAndWorkspaceIdForUpdate(MILESTONE_ID, WORKSPACE_ID);
        // A plain read first would cache a possibly stale instance that the locking read then
        // returns unrefreshed, so the lock must be the first (and only) gate lookup.
        verify(milestoneRepository, never()).findByIdAndWorkspaceId(any(), any());
    }

    @Test
    @DisplayName("EV-002-d: the same milestone sent with a different campaignId is refused, no debit")
    void mismatchedCampaignIdIsRefused() {
        PaymentMilestone milestone = register(pendingMilestone(MILESTONE_ID));

        ApiException ex =
                assertThrows(ApiException.class, () -> fund(MILESTONE_ID, OTHER_CAMPAIGN_ID, "escrow-key-1"));

        assertEquals("MILESTONE_CAMPAIGN_MISMATCH", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(0, ledgerPostCount);
        verify(escrowHoldRepository, never()).save(any());
        assertEquals(MilestoneStatus.PENDING, milestone.getStatus());
    }

    @Test
    @DisplayName("EV-002-d: after funding, a different campaignId with a new key still cannot fund it again")
    void mismatchedCampaignIdCannotBypassAfterFunding() {
        register(pendingMilestone(MILESTONE_ID));
        fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-1");

        ApiException ex =
                assertThrows(ApiException.class, () -> fund(MILESTONE_ID, OTHER_CAMPAIGN_ID, "escrow-key-2"));

        assertNotEquals("", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        assertEquals(1, ledgerPostCount);
    }

    @Test
    @DisplayName("EV-002: two milestones on the same campaign stay independently fundable")
    void twoMilestonesStayIndependentlyFundable() {
        PaymentMilestone m1 = register(pendingMilestone(MILESTONE_ID));
        PaymentMilestone m2 = register(pendingMilestone(MILESTONE_2_ID));

        EscrowFundResponse r1 = fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-m1");
        EscrowFundResponse r2 = fund(MILESTONE_2_ID, CAMPAIGN_ID, "escrow-key-m2");

        assertEquals(2, ledgerPostCount);
        assertNotEquals(r1.escrowHoldId(), r2.escrowHoldId());
        assertEquals(r1.escrowHoldId(), m1.getEscrowHoldId());
        assertEquals(r2.escrowHoldId(), m2.getEscrowHoldId());
    }

    @Test
    @DisplayName("EV-002: a failed attempt (402, insufficient balance) leaves the milestone fundable on retry")
    void failedAttemptCanBeRetried() {
        PaymentMilestone milestone = register(pendingMilestone(MILESTONE_ID));
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID))
                .thenReturn(
                        wallet("01HBRANDWALLET12345AB", BigDecimal.valueOf(1000)),
                        wallet("01HBRANDWALLET12345AB", AMOUNT));

        assertThrows(InsufficientFundsException.class, () -> fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-1"));
        assertEquals(MilestoneStatus.PENDING, milestone.getStatus());
        assertEquals(0, ledgerPostCount);

        EscrowFundResponse retry = fund(MILESTONE_ID, CAMPAIGN_ID, "escrow-key-2");

        assertEquals(EscrowStatus.FUNDED, retry.status());
        assertEquals(1, ledgerPostCount);
        assertEquals(retry.escrowHoldId(), milestone.getEscrowHoldId());
    }

    @Test
    @DisplayName("EV-002: the webhook confirm path cannot fund an already-funded milestone a second time")
    void confirmFundedRefusesAlreadyFundedMilestoneBeforeLedgerPost() {
        PaymentMilestone milestone = register(milestoneAlreadyIn(MilestoneStatus.FUNDED));
        EscrowHold staleSecondHold =
                EscrowHold.builder()
                        .id("01HSECONDHOLD123456AB")
                        .workspaceId(WORKSPACE_ID)
                        .campaignId(CAMPAIGN_ID)
                        .milestoneId(MILESTONE_ID)
                        .amount(AMOUNT)
                        .currency("INR")
                        .status(EscrowStatus.PENDING)
                        .idempotencyKey("escrow-key-legacy")
                        .build();
        when(escrowHoldRepository.findById("01HSECONDHOLD123456AB")).thenReturn(Optional.of(staleSecondHold));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.confirmFunded("01HSECONDHOLD123456AB", "pay_test_1", 500000L, "INR"));

        assertEquals("MILESTONE_ALREADY_FUNDED", ex.getCode());
        assertEquals(0, ledgerPostCount);
        assertEquals(FIRST_HOLD_ID, milestone.getEscrowHoldId());
        assertEquals(EscrowStatus.PENDING, staleSecondHold.getStatus());
    }
}
