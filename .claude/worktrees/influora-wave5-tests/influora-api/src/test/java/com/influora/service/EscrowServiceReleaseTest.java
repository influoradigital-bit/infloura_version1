package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Task #26 V1 — escrow release path wires platform fee before creator credit. */
@ExtendWith(MockitoExtension.class)
class EscrowServiceReleaseTest {

  private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
  private static final String MILESTONE_ID = "01HMILESTONE123456789";
  private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
  private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
  private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
  private static final String CLEARING_WALLET_ID = "01HCLEARING1234567890";
  private static final String PAYEE_WALLET_ID = "01HPAYEE123456789012";

  @Mock private EscrowHoldRepository escrowHoldRepository;
  @Mock private PaymentMilestoneRepository milestoneRepository;
  @Mock private CampaignRepository campaignRepository;
  @Mock private CollaborationRepository collaborationRepository;
  @Mock private DisputeRepository disputeRepository;
  @Mock private WalletLedgerService ledgerService;
  @Mock private PlatformWalletService platformWalletService;
  @Mock private PlatformFeeService platformFeeService;
  @Mock private WalletService walletService;
  @Mock private BrandContextService brandContext;
  @Mock private CreatorContextService creatorContext;
  @Mock private RazorpayClient razorpayClient;
  @Mock private AuthPrincipal principal;
  @Mock private WorkspaceMember member;

  private EscrowService service;

  @BeforeEach
  void setUp() {
    service =
        new EscrowService(
            escrowHoldRepository,
            milestoneRepository,
            campaignRepository,
            collaborationRepository,
            disputeRepository,
            ledgerService,
            platformWalletService,
            platformFeeService,
            walletService,
            brandContext,
            creatorContext,
            razorpayClient);
  }

  @Test
  @DisplayName("release: deducts platform fee then credits creator net amount")
  void testReleaseDeductsFeeBeforeCreatorCredit() {
    when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);

    PaymentMilestone milestone =
        PaymentMilestone.builder()
            .id(MILESTONE_ID)
            .contractId("01HCONTRACT123456789")
            .collaborationId(COLLABORATION_ID)
            .sequenceNo(1)
            .amount(new BigDecimal("10000.00"))
            .status(MilestoneStatus.FUNDED)
            .build();
    milestone.markFunded(ESCROW_HOLD_ID);

    Collaboration collaboration =
        Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");

    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .milestoneId(MILESTONE_ID)
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.FUNDED)
            .idempotencyKey("fund-idem")
            .build();

    when(milestoneRepository.findById(MILESTONE_ID)).thenReturn(Optional.of(milestone));
    when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
    when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);
    when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));

    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);

    PlatformFeeService.FeeDeductionResult feeDeduction =
        new PlatformFeeService.FeeDeductionResult(
            new BigDecimal("10000.00"),
            1500,
            new BigDecimal("1500.00"),
            new BigDecimal("8500.00"),
            null);
    when(platformFeeService.deductAtRelease(
            eq(clearingWallet),
            eq(MILESTONE_ID),
            eq(CREATOR_USER_ID),
            eq(new BigDecimal("10000.00")),
            eq("INR"),
            eq(ESCROW_HOLD_ID)))
        .thenReturn(feeDeduction);

    WalletTransaction releaseCredit = ledgerTxn("release-credit");
    when(ledgerService.post(
            eq(CLEARING_WALLET_ID),
            eq(PAYEE_WALLET_ID),
            eq(new BigDecimal("8500.00")),
            eq("INR"),
            eq(WalletTransactionType.ESCROW_RELEASE),
            eq(TxnReferenceType.MILESTONE),
            eq(MILESTONE_ID),
            any(),
            eq("release:" + ESCROW_HOLD_ID),
            eq(null)))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(ledgerTxn("release-debit"), releaseCredit));

    service.release(principal, WORKSPACE_ID, MILESTONE_ID);

    verify(platformFeeService)
        .deductAtRelease(
            clearingWallet,
            MILESTONE_ID,
            CREATOR_USER_ID,
            new BigDecimal("10000.00"),
            "INR",
            ESCROW_HOLD_ID);
    verify(ledgerService)
        .post(
            CLEARING_WALLET_ID,
            PAYEE_WALLET_ID,
            new BigDecimal("8500.00"),
            "INR",
            WalletTransactionType.ESCROW_RELEASE,
            TxnReferenceType.MILESTONE,
            MILESTONE_ID,
            "Milestone release for contract " + milestone.getContractId(),
            "release:" + ESCROW_HOLD_ID,
            null);
    verify(milestoneRepository).save(milestone);
    assertEquals(MilestoneStatus.RELEASED, milestone.getStatus());
  }

  @Test
  @DisplayName("confirmFunded: does not deduct platform fee at funding time")
  void testConfirmFundedDoesNotDeductPlatformFee() {
    EscrowHold hold =
        EscrowHold.builder()
            .id(ESCROW_HOLD_ID)
            .workspaceId(WORKSPACE_ID)
            .campaignId("01HCAMPAIGN1234567AB")
            .amount(new BigDecimal("10000.00"))
            .currency("INR")
            .status(EscrowStatus.PENDING)
            .idempotencyKey("fund-idem")
            .build();

    when(escrowHoldRepository.findById(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
    Wallet brandWallet = Wallet.forWorkspace("01HBRANDWALLET123456", WORKSPACE_ID);
    Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
    when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(brandWallet);
    when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
    when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new WalletLedgerService.LedgerPostingResult(ledgerTxn("fund-debit"), ledgerTxn("fund-credit")));

    service.confirmFunded(ESCROW_HOLD_ID, "gw-ref", 1_000_000L, "INR");

    verify(platformFeeService, never())
        .deductAtRelease(any(), any(), any(), any(), any(), any());
  }

  private static WalletTransaction ledgerTxn(String id) {
    return WalletTransaction.builder()
        .id(id)
        .walletId(CLEARING_WALLET_ID)
        .groupId("grp")
        .direction(TxnDirection.CREDIT)
        .type(WalletTransactionType.ESCROW_RELEASE)
        .amount(BigDecimal.ONE)
        .currency("INR")
        .balanceAfter(BigDecimal.ZERO)
        .build();
  }
}
