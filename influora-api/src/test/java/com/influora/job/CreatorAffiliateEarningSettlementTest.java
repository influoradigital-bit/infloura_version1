package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.domain.entity.Wallet;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.AffiliateSettlementBatchRepository;
import com.influora.service.AuditLogService;
import com.influora.service.IdempotencyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-0402 -- REGRESSION test reproducing the defect: settling an {@link AffiliateEarning} (via
 * {@link AffiliateSettlementWriter#doSettleCreator}, called from {@link
 * AffiliateSettlementJob#runSettlementForPeriod}) flips the earning's status to {@code SETTLED} and
 * nothing else. Neither {@link AffiliateSettlementWriter} nor {@link AffiliateSettlementJob} holds
 * any reference to {@code WalletLedgerService}/{@code WalletRepository} -- so no {@code
 * wallet_transactions} row is ever posted and no {@link Wallet#getBalance()} is ever credited for
 * the creator. Per {@code WalletLedgerService}'s own class javadoc ("Sole writer of {@code
 * wallet_transactions}... {@code wallets.balance} is a denormalized projection kept in sync inside
 * the same transaction -- the ledger rows are the source of truth"), that means a settled affiliate
 * commission is recorded and shown (via {@code AffiliateEarningsService#listForCreator}'s summary)
 * but can never actually be withdrawn -- the money exists only as a status.
 *
 * <p>This test mirrors {@link AffiliateSettlementJobTest}'s fixture style (same mocks, same
 * {@code mockIdempotencyExecuteOnceRunsSupplier}/{@code mockBatchSaveReturnsArgument} helpers, same
 * {@code pendingEarning} shape) but adds a real {@link Wallet} entity standing in for "the creator's
 * wallet row in the DB before settlement runs" and asserts against its {@code balance} field --
 * genuine ledger/balance state, not an internal call-count/interaction check -- exactly like {@code
 * WalletLedgerServiceTest} asserts on {@code Wallet.getBalance()} after a real {@code
 * WalletLedgerService.post()}. Nothing in the production settlement path can reach this wallet (no
 * field, no constructor param), so its balance is unconditionally left at its starting value by
 * every test below -- that is the bug, made visible as a failing assertion instead of a silent gap.
 *
 * <p>Do NOT "fix" this test by having it call {@code WalletLedgerService.post(...)} itself -- that
 * would make the test pass by performing the very crediting step production code is missing,
 * defeating the point of a regression test. The fix belongs in {@code src/main}, wiring a real
 * {@code WalletLedgerService} (or {@code WalletService}) into {@link AffiliateSettlementWriter} (or
 * {@link AffiliateSettlementJob}) so {@code doSettleCreator} posts a CREDIT leg to the creator's
 * wallet for each earning it settles, in the SAME transaction as the status flip.
 */
@ExtendWith(MockitoExtension.class)
class CreatorAffiliateEarningSettlementTest {

    private static final String CREATOR_ID = "01HCREATORPROFILE1234";
    private static final String PERIOD = "2026-06";

    @Mock private AffiliateEarningRepository affiliateEarningRepository;
    @Mock private AffiliateSettlementBatchRepository settlementBatchRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IdempotencyService idempotencyService;

    private AffiliateSettlementJob job;
    private AffiliateSettlementWriter writer;

    @BeforeEach
    void setUp() {
        // Real AffiliateSettlementWriter (not mocked) so @Transactional's proxy concern aside, the
        // actual production doSettleCreator body runs -- exactly AffiliateSettlementJobTest's setup.
        writer = new AffiliateSettlementWriter(affiliateEarningRepository);
        job =
                new AffiliateSettlementJob(
                        affiliateEarningRepository,
                        settlementBatchRepository,
                        auditLogService,
                        idempotencyService,
                        writer);
    }

    private void mockBatchSaveReturnsArgument() {
        when(settlementBatchRepository.save(any(AffiliateSettlementBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void mockIdempotencyExecuteOnceRunsSupplier() {
        when(idempotencyService.executeOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<Void> supplier = invocation.getArgument(3);
                            return supplier.get();
                        });
    }

    private AffiliateEarning pendingEarning(String id, String creatorId, BigDecimal amount) {
        return AffiliateEarning.builder()
                .id(id)
                .workspaceId("01HWORKSPACE12345678A")
                .campaignId("01HCAMPAIGN123456789A")
                .creatorId(creatorId)
                .redemptionId("01HREDEMPTION" + id.substring(Math.max(0, id.length() - 8)))
                .commissionAmount(amount)
                .currency("INR")
                .idempotencyKey("affearn:redemption-" + id)
                .build();
    }

    /**
     * Stand-in for "the creator's real wallet row, as it exists in {@code wallets} before this
     * settlement run" -- {@code WalletService#requireOrCreateUserWallet} creates exactly this shape
     * ({@code Wallet.forUser}) the first time a creator needs one. Balance starts at {@link
     * BigDecimal#ZERO} per {@code Wallet.forUser}'s own factory, matching a creator who has never
     * been paid before this settlement.
     */
    private Wallet freshCreatorWallet() {
        return Wallet.forUser("01HWALLETCREATOR123456", CREATOR_ID);
    }

    // ------------------------------------------------------------------
    // THE DEFECT [F-0402]
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "F-0402 [SHOULD FAIL]: after a creator's affiliate earning is settled, their wallet"
                    + " balance must reflect the settled commission amount -- it does not, because"
                    + " nothing in the settlement path ever posts to the wallet ledger")
    void testSettledEarningMustCreditCreatorWalletBalance() {
        BigDecimal commission = new BigDecimal("500.00");
        AffiliateEarning earning = pendingEarning("e1", CREATOR_ID, commission);
        Wallet creatorWallet = freshCreatorWallet();

        when(affiliateEarningRepository.findDistinctCreatorIdByStatusIn(any())).thenReturn(List.of(CREATOR_ID));
        when(affiliateEarningRepository.findByCreatorIdAndStatusIn(eq(CREATOR_ID), any()))
                .thenReturn(List.of(earning));
        mockIdempotencyExecuteOnceRunsSupplier();
        mockBatchSaveReturnsArgument();

        job.runSettlementForPeriod(PERIOD);

        // Existing behaviour must not regress: the earning itself is still marked SETTLED.
        assertEquals(AffiliateEarning.Status.SETTLED, earning.getStatus());

        // THE DEFECT: the creator's wallet -- the actual source of a withdrawable balance per
        // WalletLedgerService's own javadoc -- was never touched. Expected it to hold the settled
        // commission; it is still at its starting balance of 0.00.
        assertEquals(
                0,
                commission.compareTo(creatorWallet.getBalance()),
                () ->
                        "creator wallet balance after settlement should be "
                                + commission
                                + " but was "
                                + creatorWallet.getBalance()
                                + " -- settlement never posted to the wallet ledger (F-0402)");
    }

    @Test
    @DisplayName(
            "F-0402 [SHOULD FAIL]: settling the same earning twice must credit the wallet exactly"
                    + " once, never twice -- fails today because it is credited zero times instead")
    void testSettlingSameEarningTwiceCreditsWalletExactlyOnce() {
        BigDecimal commission = new BigDecimal("240.00");
        AffiliateEarning earning = pendingEarning("e2", CREATOR_ID, commission);
        Wallet creatorWallet = freshCreatorWallet();

        AffiliateSettlementBatch firstBatch =
                AffiliateSettlementBatch.builder().id("01HBATCHFIRST12345678").periodYearMonth(PERIOD).build();
        AffiliateSettlementBatch secondBatch =
                AffiliateSettlementBatch.builder().id("01HBATCHSECOND1234567").periodYearMonth(PERIOD).build();

        // Drives AffiliateSettlementWriter#doSettleCreator directly, twice, for the SAME earning --
        // simulating a replay that reaches the writer (the job's own idempotency guard is covered
        // separately by AffiliateSettlementJobTest; this test isolates the wallet-crediting
        // behaviour of the write itself under a repeated call).
        writer.doSettleCreator(List.of(earning), firstBatch);
        writer.doSettleCreator(List.of(earning), secondBatch);

        assertEquals(AffiliateEarning.Status.SETTLED, earning.getStatus());

        // Must equal ONE commission's worth, never zero (not credited) and never two commissions'
        // worth (double-credited). Fails today at 0.00 -- confirms the same root defect, not a
        // double-credit, but the assertion documents the invariant a fix must satisfy either way.
        assertEquals(
                0,
                commission.compareTo(creatorWallet.getBalance()),
                () ->
                        "creator wallet balance after settling the same earning twice should be "
                                + commission
                                + " (exactly one credit) but was "
                                + creatorWallet.getBalance());
    }

    // ------------------------------------------------------------------
    // Non-regression: the status transition itself must keep working
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F-0402 [must keep passing]: settling an earning still flips its status to SETTLED and links the batch")
    void testSettlingEarningStillFlipsStatusToSettled() {
        AffiliateEarning earning = pendingEarning("e3", CREATOR_ID, new BigDecimal("75.00"));
        AffiliateSettlementBatch batch =
                AffiliateSettlementBatch.builder().id("01HBATCHSTATUS1234567").periodYearMonth(PERIOD).build();

        writer.doSettleCreator(List.of(earning), batch);

        assertEquals(AffiliateEarning.Status.SETTLED, earning.getStatus());
        assertEquals(batch.getId(), earning.getSettlementBatchId());
    }
}
