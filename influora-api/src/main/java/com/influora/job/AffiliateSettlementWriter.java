package com.influora.job;

import com.influora.common.ApiException;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.AffiliateSettlementBatch;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.WalletLedgerService;
import com.influora.service.WalletService;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [W1-7 / H15/H16] Extracted from {@link AffiliateSettlementJob}'s old {@code doSettleCreator} —
 * this is the actual mutating write, called by {@code AffiliateSettlementJob#settleOneCreator} FROM
 * INSIDE the {@link com.influora.service.IdempotencyService#executeOnce} supplier lambda it passes
 * in. When that lambda lived in {@code AffiliateSettlementJob} itself and called {@code
 * this.doSettleCreator(...)} directly, the call bypassed Spring's transactional proxy entirely (a
 * lambda captures the enclosing instance's raw {@code this}, exactly like an anonymous inner class
 * would) — {@code @Transactional} on that method was a silent no-op, so a failure partway through
 * marking a creator's earnings SETTLED (e.g. row 3 of 5 throws) would leave rows 1-2 durably
 * SETTLED with no rollback, double-counting risk on the next run's PENDING/FAILED sweep. Moving the
 * write to a genuinely separate {@code @Component} means {@link AffiliateSettlementJob} now calls
 * it through this bean's real Spring proxy, so {@code @Transactional} actually demarcates a
 * transaction — either every earning in the batch is marked SETTLED, or none are.
 *
 * <h2>[EV-011 / EV-025 / EV-033] The three defects that made this class's money movement wrong</h2>
 *
 * <ul>
 *   <li><b>EV-011 — the posting could not be written at all.</b> {@code
 *       V8__wallet_transactions.sql:6-12} pins {@code type} and {@code reference_type} as MySQL
 *       ENUMs; neither was ever widened for the {@code AFFILIATE_COMMISSION} / {@code
 *       AFFILIATE_EARNING} members [F-0402] added on the Java side. Every posting from here threw
 *       on MySQL and rolled the whole creator's settlement back, so commission has never been
 *       credited. Fixed by {@code V20260920110011__wallet_transactions_affiliate_enums.sql} and
 *       guarded against recurrence by {@code WalletTransactionEnumConformanceTest}.
 *   <li><b>EV-025 — it credited a wallet keyed by the wrong id.</b> {@link
 *       AffiliateEarning#getCreatorId()} is a {@code creator_profiles.id} ({@code
 *       V28__affiliate_earnings_settlement.sql:53,74} — {@code FOREIGN KEY (creator_id) REFERENCES
 *       creator_profiles(id)}), but every creator-facing wallet read resolves by {@code users.id}
 *       ({@code WalletService#getBalanceForUser}, {@code wallets.owner_id}). Passing the profile id
 *       to {@code requireOrCreateUserWallet} would have MINTED a brand-new wallet row owned by a
 *       profile id — money credited into a wallet the creator can never see or withdraw from. The
 *       profile is now resolved to its {@code users.id} via {@link CreatorProfileRepository} and a
 *       missing profile fails loudly rather than minting an orphan.
 *   <li><b>EV-033 — nobody was debited.</b> The debit leg used to be the platform clearing wallet,
 *       which {@code WalletLedgerService#post} deliberately exempts from the non-negative balance
 *       check (see the {@code debitWalletIsExemptPlatformAccount} block there), so the platform
 *       silently funded 100% of every affiliate commission with no brand-side debit anywhere in the
 *       chain — {@code AffiliateEarningsService} only ever computes and records an accrual. The
 *       debit leg is now the earning's OWN brand workspace wallet ({@link
 *       AffiliateEarning#getWorkspaceId()}), which is not exempt, so an underfunded brand is
 *       refused by the ledger's own {@code INSUFFICIENT_BALANCE} guard instead of drawing on the
 *       platform. See {@link #creditCreatorWallet} for the funding policy and its open product
 *       question.
 * </ul>
 */
@Component
public class AffiliateSettlementWriter {

    private static final Logger log = LoggerFactory.getLogger(AffiliateSettlementWriter.class);

    private final AffiliateEarningRepository affiliateEarningRepository;
    private final WalletLedgerService walletLedgerService;
    private final WalletService walletService;
    private final CreatorProfileRepository creatorProfileRepository;

    /**
     * [EV-033 / F-0641] There is deliberately NO narrower constructor any more. The legacy {@code
     * AffiliateSettlementWriter(AffiliateEarningRepository)} left the wallet collaborators null and
     * {@link #creditCreatorWallet} degraded to a log-and-return — which meant {@link
     * #doSettleCreator} could mark an earning durably {@code SETTLED} while no money moved. F-0641
     * made that gap loud instead of silent but left it reachable; now that settlement debits a real
     * brand wallet, a writer that can flip status without moving money is not a shape this class
     * should be constructible in at all. Every collaborator is mandatory and null-checked here, so
     * a miswired context fails at construction rather than at the first unpaid commission.
     */
    @Autowired
    public AffiliateSettlementWriter(
            AffiliateEarningRepository affiliateEarningRepository,
            WalletLedgerService walletLedgerService,
            WalletService walletService,
            CreatorProfileRepository creatorProfileRepository) {
        this.affiliateEarningRepository =
                Objects.requireNonNull(affiliateEarningRepository, "affiliateEarningRepository");
        this.walletLedgerService = Objects.requireNonNull(walletLedgerService, "walletLedgerService");
        this.walletService = Objects.requireNonNull(walletService, "walletService");
        this.creatorProfileRepository =
                Objects.requireNonNull(creatorProfileRepository, "creatorProfileRepository");
    }

    /**
     * Raised when a settled commission cannot be funded by the brand that owes it. Distinct from
     * the raw {@link ApiException} the ledger throws so {@link AffiliateSettlementJob}'s per-creator
     * catch, and any operator reading the log, can tell "this brand is short" apart from a genuine
     * bug. Deliberately unchecked and deliberately NOT swallowed: it propagates out of {@link
     * #doSettleCreator}, whose {@code @Transactional} rolls back every status flip in the batch, so
     * a creator is never left recorded as paid for money that was never funded.
     */
    public static class AffiliateCommissionUnfundedException extends RuntimeException {
        public AffiliateCommissionUnfundedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Runs ONLY inside {@code executeOnce} (called from {@code
     * AffiliateSettlementJob#settleOneCreator}) — see class javadoc.
     *
     * <p><b>Idempotency on re-run</b> holds at three independent layers, none of which relies on
     * this method's own control flow: (1) {@link AffiliateSettlementJob} only ever passes earnings
     * in {@code PENDING}/{@code FAILED}, so a {@code SETTLED} earning is never handed here again;
     * (2) the {@code (creatorId, periodYearMonth)} key reserved by {@code
     * IdempotencyService#executeOnce} makes a second attempt for the same period a clean no-op; and
     * (3) {@link WalletLedgerService#post} is itself idempotent on {@code
     * earning.getIdempotencyKey()}, which is {@code NOT NULL UNIQUE} on {@link AffiliateEarning}
     * and never re-derived here — so even if (1) and (2) were both bypassed, the ledger returns the
     * already-applied posting rather than moving money twice, and {@code assertReplayMatches} there
     * makes a replay that does not match the original wallets/amount/type a hard conflict.
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
     * Posts the settled commission as ONE balanced double-entry movement: the owing brand's
     * workspace wallet is DEBITED and the creator's own user wallet is CREDITED, through the same
     * {@link WalletLedgerService#post} every other money movement in this codebase uses.
     *
     * <h2>[EV-033] Funding source and insufficient-balance policy</h2>
     *
     * The debit leg is the earning's own {@code workspace_id} wallet — the brand whose campaign and
     * whose coupon produced the conversion ({@code V28__affiliate_earnings_settlement.sql:51}).
     * That is the only party in the recorded chain who owes this money: the commission is a
     * percentage of the brand's OWN off-platform order ({@code
     * AffiliateEarningsService#validateAndCompute} — {@code redemption.getOrderAmount()} times
     * {@code campaign.commissionRate}), and nothing anywhere in the chain ever charged them for it.
     *
     * <p><b>Insufficient balance is refused, loudly, and nothing is credited.</b> Brand wallets are
     * NOT on {@code WalletLedgerService}'s clearing-wallet exemption list, so a brand whose balance
     * is below the commission gets {@code INSUFFICIENT_BALANCE} straight from the ledger. That is
     * caught here only to log the full context and re-raise it as {@link
     * AffiliateCommissionUnfundedException}; it is never swallowed, so {@link #doSettleCreator}'s
     * transaction rolls back, the earning stays {@code PENDING}/{@code FAILED} (both retryable —
     * see {@link AffiliateSettlementJob}'s class javadoc), the creator's key is marked {@code
     * FAILED} and therefore reclaimable, and {@link AffiliateSettlementJob} marks the whole batch
     * {@code FAILED} with an {@code OUTCOME_FAILED} audit event. Deferring an unfunded commission
     * to the next run is recoverable; crediting it is not.
     *
     * <p><b>OPEN PRODUCT QUESTION — deliberately not invented here.</b> Charging the brand at
     * SETTLEMENT time (this implementation) is the safest option that does not credit unfunded
     * money, but it is not self-evidently the intended commercial design; two other shapes are
     * defensible and both need a Rohan/Priya ruling rather than a developer's guess: (a) debit the
     * brand at EARNING time so the commission is reserved the moment the conversion is recorded
     * (removes the "brand spent the balance before the 1st of the month" race entirely, but turns
     * an accrual into a charge and needs its own insufficient-funds story at redemption), or (b) a
     * dedicated brand-funded affiliate pool topped up ahead of a campaign, like escrow. Until that
     * ruling lands, this implementation's rule is the conservative one: <b>no commission is ever
     * credited from money nobody was debited for.</b>
     *
     * <h2>[EV-025] Which wallet gets credited</h2>
     *
     * {@code earning.getCreatorId()} is a {@code creator_profiles.id}, not a {@code users.id} — see
     * class javadoc. It is resolved to the profile's {@code userId} here BEFORE any wallet lookup,
     * and a profile that does not resolve throws rather than falling through to {@code
     * requireOrCreateUserWallet}, whose {@code orElseGet(...save(Wallet.forUser(...)))} would
     * otherwise mint a brand-new, permanently invisible wallet row keyed by the profile id. That
     * ordering is the whole guard: this path can no longer CREATE an orphan wallet, because the
     * only value it ever hands to {@code requireOrCreateUserWallet} is one it just read out of
     * {@code creator_profiles.user_id}.
     */
    private void creditCreatorWallet(AffiliateEarning earning) {
        String creatorUserId = resolveCreatorUserId(earning);

        Wallet brandWallet;
        try {
            brandWallet = walletService.requireWorkspaceWallet(earning.getWorkspaceId());
        } catch (ApiException e) {
            log.error(
                    "[EV-033] Affiliate commission NOT credited for AffiliateEarning id={}: brand"
                            + " workspace {} has no wallet to debit ({}). amount={} {}. Nothing was"
                            + " credited and the earning stays retryable.",
                    earning.getId(),
                    earning.getWorkspaceId(),
                    e.getCode(),
                    earning.getCommissionAmount(),
                    earning.getCurrency(),
                    e);
            throw new AffiliateCommissionUnfundedException(
                    "Affiliate commission for earning "
                            + earning.getId()
                            + " cannot be funded: brand workspace "
                            + earning.getWorkspaceId()
                            + " has no wallet",
                    e);
        }

        Wallet creatorWallet = walletService.requireOrCreateUserWallet(creatorUserId);

        try {
            walletLedgerService.post(
                    brandWallet.getId(),
                    creatorWallet.getId(),
                    earning.getCommissionAmount(),
                    earning.getCurrency(),
                    WalletTransactionType.AFFILIATE_COMMISSION,
                    TxnReferenceType.AFFILIATE_EARNING,
                    earning.getId(),
                    "Affiliate commission settlement for redemption " + earning.getRedemptionId(),
                    earning.getIdempotencyKey(),
                    null);
        } catch (ApiException e) {
            log.error(
                    "[EV-033] Affiliate commission NOT credited for AffiliateEarning id={}"
                            + " creatorProfileId={} creatorUserId={} redemptionId={}: the ledger"
                            + " refused the brand-funded debit ({}). brandWorkspaceId={}"
                            + " brandWalletId={} amount={} {}. Nothing was credited and the earning"
                            + " stays retryable.",
                    earning.getId(),
                    earning.getCreatorId(),
                    creatorUserId,
                    earning.getRedemptionId(),
                    e.getCode(),
                    earning.getWorkspaceId(),
                    brandWallet.getId(),
                    earning.getCommissionAmount(),
                    earning.getCurrency(),
                    e);
            throw new AffiliateCommissionUnfundedException(
                    "Affiliate commission for earning "
                            + earning.getId()
                            + " was refused by the ledger ("
                            + e.getCode()
                            + ")",
                    e);
        }
    }

    /**
     * [EV-025] {@code creator_profiles.id} to {@code users.id}. Throws rather than returning the
     * profile id as a fallback: a fallback here is exactly the bug — it would credit a wallet the
     * creator cannot see, and {@code requireOrCreateUserWallet} would happily create that wallet on
     * the way past.
     */
    private String resolveCreatorUserId(AffiliateEarning earning) {
        CreatorProfile profile =
                creatorProfileRepository
                        .findById(earning.getCreatorId())
                        .orElseThrow(
                                () -> {
                                    log.error(
                                            "[EV-025] Affiliate commission NOT credited for"
                                                + " AffiliateEarning id={}: creator_profiles row"
                                                + " {} does not resolve, so there is no users.id to"
                                                + " credit. amount={} {}. Refusing rather than"
                                                + " crediting a profile-id-keyed wallet.",
                                            earning.getId(),
                                            earning.getCreatorId(),
                                            earning.getCommissionAmount(),
                                            earning.getCurrency());
                                    return new IllegalStateException(
                                            "AffiliateEarning "
                                                    + earning.getId()
                                                    + " references creator profile "
                                                    + earning.getCreatorId()
                                                    + " which does not exist");
                                });
        String userId = profile.getUserId();
        if (userId == null || userId.isBlank()) {
            log.error(
                    "[EV-025] Affiliate commission NOT credited for AffiliateEarning id={}: creator"
                            + " profile {} has no user_id. amount={} {}.",
                    earning.getId(),
                    earning.getCreatorId(),
                    earning.getCommissionAmount(),
                    earning.getCurrency());
            throw new IllegalStateException(
                    "Creator profile " + earning.getCreatorId() + " has no user_id to credit");
        }
        return userId;
    }
}
