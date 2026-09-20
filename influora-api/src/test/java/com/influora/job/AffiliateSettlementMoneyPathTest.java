package com.influora.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.service.PlatformWalletService;
import com.influora.service.WalletLedgerService;
import com.influora.service.WalletService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [EV-025 / EV-033] The affiliate settlement money leg, exercised against a <b>real</b> {@link
 * WalletLedgerService} rather than a mocked one.
 *
 * <h2>Why a real ledger and not a stub</h2>
 *
 * The two defects this class covers both live INSIDE {@code WalletLedgerService.post}'s behaviour,
 * not in whether it was called:
 *
 * <ul>
 *   <li>EV-033 is "the debit leg was the platform clearing wallet, which {@code post} deliberately
 *       EXEMPTS from the non-negative balance check". A stubbed {@code post} has no exemption list
 *       and no balance check, so a stub cannot tell a brand-funded debit from a
 *       platform-subsidised one and cannot produce the {@code INSUFFICIENT_BALANCE} refusal at all.
 *   <li>The idempotency guarantee is "a replay with the same key returns the already-applied
 *       posting instead of moving money twice" — again a property of {@code post}, backed by
 *       {@code uq_wtx_idem}. A stub that models it is modelling the thing under test.
 * </ul>
 *
 * <p>So the ledger here is the production class, wired to in-memory stand-ins for its two
 * repositories: {@code findByIdForUpdate}/{@code save} over a {@link Map} of real {@link Wallet}
 * entities (so balances are genuine arithmetic, and {@code applyBalanceDelta} is production's own),
 * and {@code findByIdempotencyKey}/{@code save} over a {@link Map} of real {@link WalletTransaction}
 * rows (so replay detection is production's own). Nothing here re-implements a rule the fix is
 * supposed to enforce.
 *
 * <p>What this deliberately CANNOT prove is EV-011 itself: the enum widening only matters against
 * MySQL, and no in-JVM test can reproduce "Data truncated for column 'type'". That is
 * {@code WalletTransactionEnumConformanceTest}'s job (it reads the migration chain) plus a Meera
 * run against real MySQL.
 */
class AffiliateSettlementMoneyPathTest {

    /** A {@code creator_profiles.id} -- what {@code AffiliateEarning.creatorId} holds. */
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";

    /** The {@code users.id} behind it -- what {@code wallets.owner_id} holds for that creator. */
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String BRAND_WALLET_ID = "01HWALLETBRAND1234567";
    private static final String CREATOR_WALLET_ID = "01HWALLETCREATOR12345";

    private final Map<String, Wallet> wallets = new HashMap<>();
    private final Map<String, WalletTransaction> ledgerRows = new HashMap<>();

    /**
     * [EV-025] Mirrors production's {@code WalletService#requireOrCreateUserWallet}, which is
     * {@code findByOwnerId(...).orElseGet(() -> save(Wallet.forUser(newUlid(), ownerId)))} -- it
     * CREATES the wallet it cannot find. Stubbing it to return null for an unexpected owner id
     * would make the pre-EV-025 code fail here with a NullPointerException, i.e. for a reason
     * production does not have; the real defect is that production quietly MINTS a wallet owned by
     * a creator_profiles.id and credits it. This map reproduces that, so the EV-025 assertions fail
     * on the money landing in the wrong wallet rather than on a mock artefact.
     */
    private final Map<String, Wallet> walletsByOwner = new HashMap<>();

    private AffiliateEarningRepository affiliateEarningRepository;
    private CreatorProfileRepository creatorProfileRepository;
    private WalletRepository walletRepository;
    private WalletService walletService;
    private WalletLedgerService walletLedgerService;
    private AffiliateSettlementWriter writer;

    private Wallet brandWallet;
    private Wallet creatorWallet;

    @BeforeEach
    void setUp() {
        affiliateEarningRepository = mock(AffiliateEarningRepository.class);
        creatorProfileRepository = mock(CreatorProfileRepository.class);
        walletRepository = mock(WalletRepository.class);
        WalletTransactionRepository walletTransactionRepository =
                mock(WalletTransactionRepository.class);
        walletService = mock(WalletService.class);

        brandWallet = Wallet.forWorkspace(BRAND_WALLET_ID, WORKSPACE_ID);
        creatorWallet = Wallet.forUser(CREATOR_WALLET_ID, CREATOR_USER_ID);
        wallets.put(brandWallet.getId(), brandWallet);
        wallets.put(creatorWallet.getId(), creatorWallet);

        when(walletRepository.findByIdForUpdate(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(wallets.get((String) inv.getArgument(0))));
        when(walletRepository.save(any(Wallet.class)))
                .thenAnswer(
                        inv -> {
                            Wallet w = inv.getArgument(0);
                            wallets.put(w.getId(), w);
                            return w;
                        });
        when(walletTransactionRepository.findByIdempotencyKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(ledgerRows.get((String) inv.getArgument(0))));
        when(walletTransactionRepository.save(any(WalletTransaction.class)))
                .thenAnswer(
                        inv -> {
                            WalletTransaction tx = inv.getArgument(0);
                            ledgerRows.put(tx.getIdempotencyKey(), tx);
                            return tx;
                        });

        // Production ledger, production rules.
        walletLedgerService =
                new WalletLedgerService(walletRepository, walletTransactionRepository);

        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID))
                .thenReturn(
                        Optional.of(
                                CreatorProfile.newForUser(
                                        CREATOR_PROFILE_ID, CREATOR_USER_ID, "Test Creator")));
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(brandWallet);
        walletsByOwner.put(CREATOR_USER_ID, creatorWallet);
        when(walletService.requireOrCreateUserWallet(anyString()))
                .thenAnswer(
                        inv -> {
                            String ownerId = inv.getArgument(0);
                            return walletsByOwner.computeIfAbsent(
                                    ownerId,
                                    o -> {
                                        Wallet minted =
                                                Wallet.forUser(
                                                        "01HWALLETMINTED" + wallets.size(), o);
                                        wallets.put(minted.getId(), minted);
                                        return minted;
                                    });
                        });

        writer =
                new AffiliateSettlementWriter(
                        affiliateEarningRepository,
                        walletLedgerService,
                        walletService,
                        creatorProfileRepository);
    }

    private static AffiliateEarning earning(String id, BigDecimal commission) {
        return AffiliateEarning.builder()
                .id(id)
                .workspaceId(WORKSPACE_ID)
                .campaignId("01HCAMPAIGN123456789A")
                .creatorId(CREATOR_PROFILE_ID)
                .redemptionId("01HREDEMPTION" + id)
                .commissionAmount(commission)
                .currency("INR")
                .idempotencyKey("affearn:redemption-" + id)
                .build();
    }

    private static AffiliateSettlementBatch batch(String id) {
        return AffiliateSettlementBatch.builder().id(id).periodYearMonth("2026-08").build();
    }

    private void fundBrand(String amount) {
        brandWallet.applyBalanceDelta(new BigDecimal(amount));
    }

    private List<WalletTransaction> legsFor(AffiliateEarning e) {
        List<WalletTransaction> out = new ArrayList<>();
        WalletTransaction debit = ledgerRows.get(e.getIdempotencyKey() + ":D");
        WalletTransaction credit = ledgerRows.get(e.getIdempotencyKey() + ":C");
        if (debit != null) {
            out.add(debit);
        }
        if (credit != null) {
            out.add(credit);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // EV-025 -- the credit lands in the wallet the creator can actually see
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "EV-025: a settled commission credits the wallet owned by the creator's users.id, and"
                    + " never touches (or creates) one owned by the creator_profiles.id")
    void creditsTheWalletKeyedByUserIdNotProfileId() {
        fundBrand("1000.00");
        AffiliateEarning e = earning("e1", new BigDecimal("500.00"));

        writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000001"));

        assertThat(creatorWallet.getOwnerId())
                .as("the credited wallet's owner must be the users.id, not the creator_profiles.id")
                .isEqualTo(CREATOR_USER_ID)
                .isNotEqualTo(CREATOR_PROFILE_ID);
        assertThat(creatorWallet.getBalance()).isEqualByComparingTo("500.00");

        // The profile id must never even be offered to requireOrCreateUserWallet -- that method
        // CREATES the wallet it cannot find, so passing the profile id there is what would mint the
        // permanently invisible orphan row. This is the assertion that fails against the old code.
        verify(walletService, never()).requireOrCreateUserWallet(CREATOR_PROFILE_ID);
        verify(walletService).requireOrCreateUserWallet(CREATOR_USER_ID);

        // And no orphan wallet was brought into existence anywhere: the harness mints on demand
        // exactly like production does, so a profile-id lookup WOULD have left a row behind here.
        assertThat(wallets.values())
                .as(
                        "no wallet may exist owned by a creator_profiles.id -- that is the"
                            + " permanently invisible orphan EV-025 is about")
                .noneMatch(w -> CREATOR_PROFILE_ID.equals(w.getOwnerId()));
        assertThat(walletsByOwner.keySet()).containsExactly(CREATOR_USER_ID);
    }

    @Test
    @DisplayName(
            "EV-025: an earning whose creator_profiles row does not resolve is refused -- no wallet"
                    + " is looked up or created and no money moves")
    void unresolvableCreatorProfileIsRefusedRatherThanMintingAnOrphanWallet() {
        fundBrand("1000.00");
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.empty());
        AffiliateEarning e = earning("e2", new BigDecimal("120.00"));

        assertThatThrownBy(() -> writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000002")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CREATOR_PROFILE_ID);

        verify(walletService, never()).requireOrCreateUserWallet(anyString());
        assertThat(creatorWallet.getBalance()).isEqualByComparingTo("0.00");
        assertThat(brandWallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(ledgerRows).as("nothing was posted").isEmpty();
    }

    // ------------------------------------------------------------------
    // EV-033 -- the brand funds it, and an underfunded brand is refused loudly
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "EV-033: the commission is DEBITED from the brand workspace wallet that owes it -- a"
                    + " balanced pair, not a platform subsidy")
    void brandWorkspaceWalletFundsTheCommission() {
        fundBrand("1000.00");
        AffiliateEarning e = earning("e3", new BigDecimal("250.00"));

        writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000003"));

        assertThat(brandWallet.getBalance())
                .as("the brand that owes the commission paid for it")
                .isEqualByComparingTo("750.00");
        assertThat(creatorWallet.getBalance()).isEqualByComparingTo("250.00");

        List<WalletTransaction> legs = legsFor(e);
        assertThat(legs).hasSize(2);
        WalletTransaction debit = legs.get(0);
        WalletTransaction credit = legs.get(1);
        assertThat(debit.getDirection()).isEqualTo(TxnDirection.DEBIT);
        assertThat(debit.getWalletId())
                .as(
                        "the debit leg must be the brand's wallet. Against the old code this is the"
                            + " platform clearing wallet, which WalletLedgerService exempts from the"
                            + " non-negative check -- the platform silently funding every"
                            + " commission.")
                .isEqualTo(BRAND_WALLET_ID);
        assertThat(credit.getDirection()).isEqualTo(TxnDirection.CREDIT);
        assertThat(credit.getWalletId()).isEqualTo(CREATOR_WALLET_ID);
        assertThat(debit.getGroupId())
                .as("both legs of one movement share a group_id")
                .isEqualTo(credit.getGroupId());

        // Audit trail: the row points back at the earning it settled.
        assertThat(credit.getType()).isEqualTo(WalletTransactionType.AFFILIATE_COMMISSION);
        assertThat(credit.getReferenceType()).isEqualTo(TxnReferenceType.AFFILIATE_EARNING);
        assertThat(credit.getReferenceId()).isEqualTo(e.getId());
        assertThat(credit.getDescription()).contains(e.getRedemptionId());
    }

    @Test
    @DisplayName(
            "EV-033: the debit leg is never the platform clearing wallet -- that wallet is exempt"
                    + " from the balance check, so using it is exactly the silent-subsidy defect")
    void theDebitLegIsNeverThePlatformClearingWallet() {
        fundBrand("1000.00");
        AffiliateEarning e = earning("e4", new BigDecimal("10.00"));

        writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000004"));

        WalletTransaction debit = ledgerRows.get(e.getIdempotencyKey() + ":D");
        Wallet debited = wallets.get(debit.getWalletId());
        assertThat(debited.getOwnerId())
                .isNotEqualTo(PlatformWalletService.PLATFORM_CLEARING_WALLET_OWNER_ID);
        assertThat(debited.getOwnerId()).isEqualTo(WORKSPACE_ID);
    }

    @Test
    @DisplayName(
            "EV-033: a brand that cannot fund the commission is refused LOUDLY -- nothing is"
                    + " credited, both balances are untouched, and the exception propagates so the"
                    + " @Transactional status flip rolls back")
    void insufficientBrandBalanceIsRefusedAndCreditsNothing() {
        fundBrand("100.00");
        AffiliateEarning e = earning("e5", new BigDecimal("500.00"));

        assertThatThrownBy(() -> writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000005")))
                .isInstanceOf(AffiliateSettlementWriter.AffiliateCommissionUnfundedException.class)
                .hasMessageContaining(e.getId())
                .hasRootCauseInstanceOf(ApiException.class);

        assertThat(creatorWallet.getBalance())
                .as("no commission may be credited from money nobody was debited for")
                .isEqualByComparingTo("0.00");
        assertThat(brandWallet.getBalance()).isEqualByComparingTo("100.00");
        assertThat(ledgerRows).isEmpty();
    }

    @Test
    @DisplayName(
            "EV-033: a brand workspace with no wallet at all is refused the same way -- never"
                    + " papered over with a zero-balance wallet")
    void missingBrandWalletIsRefused() {
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID))
                .thenThrow(
                        new ApiException(
                                "WALLET_NOT_FOUND",
                                "Wallet not found for workspace",
                                org.springframework.http.HttpStatus.NOT_FOUND));
        AffiliateEarning e = earning("e6", new BigDecimal("60.00"));

        assertThatThrownBy(() -> writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000006")))
                .isInstanceOf(AffiliateSettlementWriter.AffiliateCommissionUnfundedException.class);

        assertThat(creatorWallet.getBalance()).isEqualByComparingTo("0.00");
        assertThat(ledgerRows).isEmpty();
    }

    @Test
    @DisplayName(
            "EV-033: an underfunded earning raises OUT of doSettleCreator rather than being skipped"
                    + " -- that escape is what makes @Transactional roll the whole creator batch back")
    void oneUnfundedEarningAbortsTheWholeCreatorBatch() {
        fundBrand("300.00");
        AffiliateEarning affordable = earning("e7a", new BigDecimal("200.00"));
        AffiliateEarning unaffordable = earning("e7b", new BigDecimal("200.00"));

        assertThatThrownBy(
                        () ->
                                writer.doSettleCreator(
                                        List.of(affordable, unaffordable),
                                        batch("01HBATCH0000000000007")))
                .isInstanceOf(AffiliateSettlementWriter.AffiliateCommissionUnfundedException.class)
                .hasMessageContaining(unaffordable.getId());

        // The first posting DID land in this in-JVM harness because nothing here rolls a database
        // transaction back -- that is @Transactional's job in production, and the exception
        // escaping doSettleCreator is what triggers it. What this asserts is the part the test CAN
        // observe: the second, unfunded earning credited nothing, and the exception was not
        // swallowed into a partially-successful "success".
        assertThat(ledgerRows.get(unaffordable.getIdempotencyKey() + ":C")).isNull();
        assertThat(creatorWallet.getBalance())
                .as("only the funded earning moved, and the caller was told the batch failed")
                .isEqualByComparingTo("200.00");
    }

    // ------------------------------------------------------------------
    // Idempotency -- a re-run credits nothing more
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "re-running settlement for the same earning credits the wallet exactly once -- the"
                    + " ledger's own idempotency key guard, not this job's control flow")
    void reRunningTheSameEarningCreditsNothingMore() {
        fundBrand("1000.00");
        AffiliateEarning e = earning("e8", new BigDecimal("300.00"));

        writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000008"));
        assertThat(creatorWallet.getBalance()).isEqualByComparingTo("300.00");
        assertThat(brandWallet.getBalance()).isEqualByComparingTo("700.00");

        // Same earning handed in again under a DIFFERENT batch -- the shape a duplicate/backfill
        // run that got past the job's own (creatorId, period) key would take.
        writer.doSettleCreator(List.of(e), batch("01HBATCH0000000000009"));

        assertThat(creatorWallet.getBalance())
                .as("a second settlement of the same earning must move no further money")
                .isEqualByComparingTo("300.00");
        assertThat(brandWallet.getBalance()).isEqualByComparingTo("700.00");
        assertThat(ledgerRows)
                .as("exactly two ledger rows -- one DEBIT leg and one CREDIT leg, once")
                .hasSize(2);
    }

    // ------------------------------------------------------------------
    // The writer can no longer be built in a shape that flips status without paying
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "F-0641 follow-through: the writer cannot be constructed without its money"
                    + " collaborators, so the 'mark SETTLED but credit nothing' shape is gone")
    void writerCannotBeConstructedWithoutItsMoneyCollaborators() {
        assertThatThrownBy(
                        () ->
                                new AffiliateSettlementWriter(
                                        affiliateEarningRepository, null, walletService,
                                        creatorProfileRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("walletLedgerService");
        assertThatThrownBy(
                        () ->
                                new AffiliateSettlementWriter(
                                        affiliateEarningRepository, walletLedgerService, null,
                                        creatorProfileRepository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("walletService");
        assertThatThrownBy(
                        () ->
                                new AffiliateSettlementWriter(
                                        affiliateEarningRepository, walletLedgerService,
                                        walletService, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("creatorProfileRepository");
    }
}
