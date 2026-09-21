package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.WalletTransactionType;
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
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * The Doc#2 service invoice written at release has the same shape as the application-history
 * stall: {@code CampaignServiceInvoiceService#createAtRelease} is a {@code REQUIRES_NEW} INSERT
 * into {@code campaign_service_invoices}, whose FK {@code fk_csi_escrow_hold} points at the {@code
 * escrow_holds} row the release has already locked {@code FOR UPDATE}. It must therefore run only
 * after the release commits.
 *
 * <p>Plain Mockito with a synchronization registry driven the way {@code
 * AbstractPlatformTransactionManager} drives it. With no synchronization active the invoice still
 * runs inline, which is why {@code EscrowServiceReleaseTest} is unchanged.
 */
@ExtendWith(MockitoExtension.class)
class EscrowServiceInvoiceAfterCommitTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
    private static final String CLEARING_WALLET_ID = "01HCLEARING1234567890";
    private static final String PAYEE_WALLET_ID = "01HPAYEE123456789012";

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
    @Mock private WorkspaceMember member;
    @Mock private CampaignServiceInvoiceService campaignServiceInvoiceService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private EscrowService service;

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
        ReflectionTestUtils.setField(service, "releaseGateCutoverInstantRaw", "2020-01-01T00:00:00Z");
        ReflectionTestUtils.invokeMethod(service, "initReleaseGateCutoverInstant");
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void stubAMilestonelessRelease() {
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        EscrowHold hold =
                EscrowHold.builder()
                        .id(ESCROW_HOLD_ID)
                        .workspaceId(WORKSPACE_ID)
                        .campaignId("01HCAMPAIGN1234567AB")
                        .amount(new BigDecimal("10000.00"))
                        .currency("INR")
                        .status(EscrowStatus.FUNDED)
                        .idempotencyKey("fund-idem")
                        .build();
        hold.bindCollaboration(COLLABORATION_ID);
        Collaboration collaboration =
                Collaboration.invite(COLLABORATION_ID, "01HCAMPAIGN1234567AB", CREATOR_USER_ID, null, "INR");
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);
        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);
        when(platformFeeService.deductAtRelease(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new PlatformFeeService.FeeDeductionResult(
                                new BigDecimal("10000.00"),
                                1500,
                                new BigDecimal("1500.00"),
                                new BigDecimal("8500.00"),
                                null));
        when(ledgerService.post(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new WalletLedgerService.LedgerPostingResult(
                                ledgerTxn("release-debit"), ledgerTxn("release-credit")));
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

    private static void commit() {
        TransactionSynchronizationUtils.invokeAfterCommit(
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations()));
        complete(TransactionSynchronization.STATUS_COMMITTED);
    }

    private static void complete(int status) {
        List<TransactionSynchronization> forCompletion =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCompletion(forCompletion, status);
    }

    @Test
    @DisplayName(
            "release inside a transaction: the invoice is NOT created while the hold row is still"
                    + " locked, and is created exactly once after the commit")
    void invoiceIsCreatedOnlyAfterTheReleaseCommits() {
        stubAMilestonelessRelease();
        TransactionSynchronizationManager.initSynchronization();

        service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID);

        verify(campaignServiceInvoiceService, never()).createAtRelease(any(), any(), any());

        commit();

        verify(campaignServiceInvoiceService, times(1))
                .createAtRelease(any(EscrowHold.class), any(Collaboration.class), eq("release-credit"));
        verify(campaignServiceInvoiceService, never())
                .recordInvoiceCreationFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a release that rolls back produces no invoice and no failure marker")
    void rolledBackReleaseProducesNoInvoice() {
        stubAMilestonelessRelease();
        TransactionSynchronizationManager.initSynchronization();

        service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(campaignServiceInvoiceService, never()).createAtRelease(any(), any(), any());
        verify(campaignServiceInvoiceService, never())
                .recordInvoiceCreationFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "an invoice failure after the commit records the failure marker and never escapes the"
                    + " commit callback")
    void invoiceFailureAfterCommitRecordsTheMarker() {
        stubAMilestonelessRelease();
        TransactionSynchronizationManager.initSynchronization();
        when(campaignServiceInvoiceService.createAtRelease(any(), any(), any()))
                .thenThrow(
                        new ApiException(
                                "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.CONFLICT));

        service.releaseByHoldId(principal, WORKSPACE_ID, ESCROW_HOLD_ID);
        assertDoesNotThrow(EscrowServiceInvoiceAfterCommitTest::commit);

        verify(campaignServiceInvoiceService, times(1))
                .recordInvoiceCreationFailure(
                        eq(ESCROW_HOLD_ID), eq(COLLABORATION_ID), eq("release-credit"), any());
    }

    /**
     * The marker is now written from an after-commit callback. A {@code REQUIRED} method there
     * would join the already-committed transaction and its INSERT would never be committed, so it
     * must open its own.
     */
    @Test
    @DisplayName("recordInvoiceCreationFailure runs in its own REQUIRES_NEW transaction")
    void failureMarkerOpensItsOwnTransaction() throws NoSuchMethodException {
        Method method =
                CampaignServiceInvoiceService.class.getMethod(
                        "recordInvoiceCreationFailure", String.class, String.class, String.class, String.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, tx == null ? null : tx.propagation());
    }
}
