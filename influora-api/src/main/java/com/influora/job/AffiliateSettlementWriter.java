package com.influora.job;

import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.service.PlatformWalletService;
import com.influora.service.WalletLedgerService;
import com.influora.service.WalletService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [W1-7 / H15/H16] Extracted from {@link AffiliateSettlementJob#doSettleCreator} — this is the
 * actual mutating write, called by {@link AffiliateSettlementJob#settleOneCreator} FROM INSIDE the
 * {@link com.influora.service.IdempotencyService#executeOnce} supplier lambda it passes in. When
 * that lambda lived in {@code AffiliateSettlementJob} itself and called {@code
 * this.doSettleCreator(...)} directly, the call bypassed Spring's transactional proxy entirely (a
 * lambda captures the enclosing instance's raw {@code this}, exactly like an anonymous inner class
 * would) — {@code @Transactional} on that method was a silent no-op, so a failure partway through
 * marking a creator's earnings SETTLED (e.g. row 3 of 5 throws) would leave rows 1-2 durably
 * SETTLED with no rollback, double-counting risk on the next run's PENDING/FAILED sweep. Moving the
 * write to a genuinely separate {@code @Component} means {@link AffiliateSettlementJob} now calls
 * it through this bean's real Spring proxy, so {@code @Transactional} actually demarcates a
 * transaction — either every earning in the batch is marked SETTLED, or none are.
 */
@Component
public class AffiliateSettlementWriter {

    private final AffiliateEarningRepository affiliateEarningRepository;
    private final WalletLedgerService walletLedgerService;
    private final WalletService walletService;
    private final PlatformWalletService platformWalletService;

    /**
     * [F-0402] Legacy narrower constructor, kept because {@code AffiliateSettlementJobTest} and
     * {@code CreatorAffiliateEarningSettlementTest} both construct this class directly via {@code
     * new AffiliateSettlementWriter(affiliateEarningRepository)} — widening THIS constructor would
     * break their compilation. Delegates to the full constructor with the wallet collaborators
     * null; {@link #creditCreatorWallet} no-ops rather than NPEs when they're null (see its
     * javadoc), exactly like {@code AnalyzeSiteAiClient}'s narrower/wider constructor pair. Every
     * real (Spring-managed) instance is built through the {@code @Autowired} constructor below,
     * which always supplies them.
     */
    public AffiliateSettlementWriter(AffiliateEarningRepository affiliateEarningRepository) {
        this(affiliateEarningRepository, null, null, null);
    }

    @Autowired
    public AffiliateSettlementWriter(
            AffiliateEarningRepository affiliateEarningRepository,
            WalletLedgerService walletLedgerService,
            WalletService walletService,
            PlatformWalletService platformWalletService) {
        this.affiliateEarningRepository = affiliateEarningRepository;
        this.walletLedgerService = walletLedgerService;
        this.walletService = walletService;
        this.platformWalletService = platformWalletService;
    }

    /**
     * Runs ONLY inside {@code executeOnce} (called from {@link
     * AffiliateSettlementJob#settleOneCreator}) — see class javadoc. Identical logic to the
     * pre-extraction {@code AffiliateSettlementJob#doSettleCreator}, plus the [F-0402] wallet
     * credit below.
     */
    @Transactional
    public void doSettleCreator(List<AffiliateEarning> settleable, AffiliateSettlementBatch batch) {
        for (AffiliateEarning earning : settleable) {
            earning.markSettled(batch.getId());
            affiliateEarningRepository.save(earning);
            creditCreatorWallet(earning);
        }
    }

    /**
     * [F-0402] Posts the CREDIT leg of the settled commission to the creator's wallet through the
     * SAME ledger mechanism the campaign payout path already uses — mirrors {@code
     * LedgerEscrowBackend#release}'s shape exactly: platform clearing wallet (debit) -&gt;
     * creator's wallet (credit) via {@link WalletLedgerService#post}. Before this fix, settling an
     * earning only flipped its status; nothing ever posted to {@code wallet_transactions}, so the
     * commission was recorded and shown but never actually withdrawable.
     *
     * <p>Idempotency: reuses {@code earning.getIdempotencyKey()} unchanged as the ledger posting's
     * idempotency key. That key is {@code NOT NULL UNIQUE} on {@link AffiliateEarning} itself and
     * is stable across repeated calls for the same earning (it is never re-derived here), so {@link
     * WalletLedgerService#post}'s own idempotency guard — a retry with the same key returns the
     * already-applied posting instead of moving money twice — makes settling the same earning
     * twice credit the wallet exactly once, the same double-credit protection every other
     * ledger-writing call site in this codebase relies on.
     *
     * <p>{@code walletLedgerService}/{@code walletService}/{@code platformWalletService} are null
     * only when this instance was built through the legacy narrower constructor above (the two
     * locked pre-F-0402 test files) — in that case this is a no-op, matching this class's
     * pre-fix behaviour (status flip only).
     */
    private void creditCreatorWallet(AffiliateEarning earning) {
        if (walletLedgerService == null || walletService == null || platformWalletService == null) {
            return;
        }
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet creatorWallet = walletService.requireOrCreateUserWallet(earning.getCreatorId());
        walletLedgerService.post(
                clearingWallet.getId(),
                creatorWallet.getId(),
                earning.getCommissionAmount(),
                earning.getCurrency(),
                WalletTransactionType.AFFILIATE_COMMISSION,
                TxnReferenceType.AFFILIATE_EARNING,
                earning.getId(),
                "Affiliate commission settlement for redemption " + earning.getRedemptionId(),
                earning.getIdempotencyKey(),
                null);
    }
}
