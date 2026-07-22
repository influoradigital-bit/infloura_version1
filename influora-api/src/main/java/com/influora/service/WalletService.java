package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.Payout;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.service.payout.RazorpayFundAccountService;
import com.influora.web.dto.money.MoneyDtos.CreatorWithdrawResponse;
import com.influora.web.dto.money.MoneyDtos.WalletBalanceResponse;
import com.influora.web.dto.money.MoneyDtos.WalletSummaryResponse;
import com.influora.web.dto.money.MoneyDtos.WalletTransactionRowResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only balance queries plus a thin wrapper around {@link WalletLedgerService} for simple
 * deposit/withdrawal postings. Never mutates {@link Wallet} balances directly — every money
 * movement goes through {@code WalletLedgerService.post()} so the ledger stays the single source
 * of truth (Guardrail 1 / C-3).
 */
@Service
public class WalletService {

    /** Trailing window used for the average-daily-burn denominator in {@link #computeRunwayDays}. */
    private static final int BURN_WINDOW_DAYS = 30;

    /**
     * Sanity ceiling on the reported runway so a near-zero (but non-zero) burn rate can't render
     * an absurd number of days (e.g. 4-digit "runway" on a wallet that spent one rupee in 30
     * days). This is a display cap only — it never turns a real zero-burn wallet into a fake
     * number; that case is handled separately by returning {@code null}.
     */
    private static final int MAX_REPORTED_RUNWAY_DAYS = 3650; // 10 years

    /** Per 10_CREATOR_PAYMENTS_SPEC.md §7.3 — server-enforced withdrawal limits. */
    private static final BigDecimal MIN_CREATOR_WITHDRAWAL = new BigDecimal("500.00");

    private static final BigDecimal MAX_CREATOR_WITHDRAWAL = new BigDecimal("100000.00");
    private static final int MAX_CREATOR_WITHDRAWALS_PER_DAY = 3;

    private final WalletRepository walletRepository;
    private final WalletLedgerService ledgerService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentMilestoneRepository paymentMilestoneRepository;
    private final PlatformWalletService platformWalletService;
    private final CreatorBankAccountRepository creatorBankAccountRepository;
    private final RazorpayXClient razorpayXClient;
    private final RazorpayFundAccountService fundAccountService;
    private final PayoutRepository payoutRepository;
    private final IdempotencyService idempotencyService;
    private final EscrowHoldRepository escrowHoldRepository;

    public WalletService(
            WalletRepository walletRepository,
            WalletLedgerService ledgerService,
            WalletTransactionRepository walletTransactionRepository,
            PaymentMilestoneRepository paymentMilestoneRepository,
            PlatformWalletService platformWalletService,
            CreatorBankAccountRepository creatorBankAccountRepository,
            RazorpayXClient razorpayXClient,
            RazorpayFundAccountService fundAccountService,
            PayoutRepository payoutRepository,
            IdempotencyService idempotencyService,
            EscrowHoldRepository escrowHoldRepository) {
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
        this.walletTransactionRepository = walletTransactionRepository;
        this.paymentMilestoneRepository = paymentMilestoneRepository;
        this.platformWalletService = platformWalletService;
        this.creatorBankAccountRepository = creatorBankAccountRepository;
        this.razorpayXClient = razorpayXClient;
        this.fundAccountService = fundAccountService;
        this.payoutRepository = payoutRepository;
        this.idempotencyService = idempotencyService;
        this.escrowHoldRepository = escrowHoldRepository;
    }

    public record PagedWalletTransactions(List<WalletTransactionRowResponse> items, PageMeta meta) {}

    @Transactional
    public WalletBalanceResponse getBalance(String workspaceId) {
        Wallet wallet = getOrCreateWorkspaceWallet(workspaceId);
        return toBalanceResponse(wallet);
    }

    /**
     * Creator wallet balance — owner id is always the authenticated user's id, never a request
     * parameter (Kabir Task #11 GO pattern). Returns zero balances when no wallet row exists yet
     * (creators are not provisioned a wallet at signup).
     */
    @Transactional(readOnly = true)
    public WalletBalanceResponse getBalanceForUser(String userId) {
        return walletRepository
                .findByOwnerId(userId)
                .map(this::toBalanceResponse)
                .orElseGet(
                        () ->
                                new WalletBalanceResponse(
                                        null, BigDecimal.ZERO, BigDecimal.ZERO, "INR", null));
    }

    /**
     * Brand dashboard wallet summary (canonical {@code GET /wallet}). {@code pendingPayouts} is the
     * total of FUNDED milestones — money already committed into escrow but not yet released to
     * creators — across the workspace's collaborations.
     *
     * <p>{@code escrowLocked} is derived on read as the sum of the workspace's {@link EscrowHold}
     * rows currently {@code FUNDED} — {@code wallets.escrow_balance} is never written by any
     * service (funding a hold only ever moves {@code wallets.balance} via {@code
     * WalletLedgerService.post()}), so reading that column always returned a stale 0.00 on the
     * brand dashboard. Derive-on-read (vs. maintain-on-write) can't drift out of sync with the
     * {@link EscrowHold} rows that are the actual source of truth for locked funds.
     */
    @Transactional
    public WalletSummaryResponse getSummary(String workspaceId) {
        Wallet wallet = getOrCreateWorkspaceWallet(workspaceId);
        BigDecimal pendingPayouts =
                paymentMilestoneRepository.sumAmountByWorkspaceAndStatus(
                        workspaceId, MilestoneStatus.FUNDED);
        if (pendingPayouts == null) {
            pendingPayouts = BigDecimal.ZERO;
        }
        BigDecimal escrowLocked =
                escrowHoldRepository.sumAmountByWorkspaceIdAndStatus(workspaceId, EscrowStatus.FUNDED);
        if (escrowLocked == null) {
            escrowLocked = BigDecimal.ZERO;
        }
        return toSummaryResponse(wallet, escrowLocked, pendingPayouts);
    }

    /**
     * Creator wallet summary — {@code pendingPayouts} is FUNDED milestone totals across the
     * creator's collaborations (money in escrow awaiting release to them).
     */
    @Transactional(readOnly = true)
    public WalletSummaryResponse getSummaryForUser(String userId) {
        BigDecimal pendingRaw =
                paymentMilestoneRepository.sumAmountByCreatorIdAndStatus(
                        userId, MilestoneStatus.FUNDED);
        final BigDecimal pendingPayouts =
                pendingRaw == null ? BigDecimal.ZERO : pendingRaw;
        // Creator-side escrowLocked derivation is out of scope for this fix (Track C targets only
        // the brand dashboard's escrowLocked figure) — still reads the dead `escrow_balance`
        // column here, unchanged from prior behavior.
        return walletRepository
                .findByOwnerId(userId)
                .map(wallet -> toSummaryResponse(wallet, wallet.getEscrowBalance(), pendingPayouts))
                .orElseGet(
                        () ->
                                new WalletSummaryResponse(
                                        BigDecimal.ZERO, BigDecimal.ZERO, pendingPayouts, null));
    }

    /**
     * Creator withdrawal — debits the authenticated user's wallet via the ledger, initiates a real
     * RazorpayX payout to the creator's on-file bank/UPI account, and persists a durable {@link
     * Payout} row (reconciled by the same {@code payout.processed}/{@code payout.reversed} webhook
     * path {@code PayoutReconciliationService} uses for milestone payouts). Owner id is always
     * {@code userId} from the JWT, never a request param. Platform fee was already deducted at
     * escrow release (§1A) — withdrawal moves net balance only.
     *
     * <p><b>[B10/C-5]</b> Previously this method only moved the creator's balance into the
     * platform's clearing wallet and returned a fabricated {@code payoutId} (the internal ledger
     * reference id) with no RazorpayX call, no {@code payouts} row, and no way for the money to
     * ever actually reach the creator — mirrors the fix already applied to {@code
     * PayoutService#doQueuePayout} (resolve a real fund account, call RazorpayX, persist {@code
     * Payout}).
     *
     * <p><b>[B10/M-6]</b> {@code Idempotency-Key} is now mandatory (the previous fallback derived a
     * fresh key on every call missing the header — a new ULID each time — so a client retry with no
     * header double-debited the wallet and could double-disburse). The client-supplied key is also
     * never passed to the ledger/gateway raw — it is namespaced per user+endpoint before use.
     */
    @Transactional
    public CreatorWithdrawResponse requestCreatorWithdrawal(
            String userId, BigDecimal amount, String idempotencyKey) {
        validateCreatorWithdrawalAmount(amount);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required for a withdrawal",
                    HttpStatus.BAD_REQUEST);
        }

        // Pessimistic owner lock serializes concurrent withdrawals for the same creator so
        // balance and daily-count checks cannot race ahead of ledgerService.post() (Kabir M-18-1/M-18-2).
        Wallet wallet =
                walletRepository
                        .findByOwnerIdForUpdate(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INSUFFICIENT_BALANCE",
                                                "Insufficient available balance",
                                                HttpStatus.BAD_REQUEST));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new ApiException(
                    "INSUFFICIENT_BALANCE",
                    "Insufficient available balance",
                    HttpStatus.BAD_REQUEST);
        }

        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        long withdrawalsToday =
                walletTransactionRepository.countByWalletIdAndTypeAndCreatedAtAfter(
                        wallet.getId(), WalletTransactionType.WITHDRAWAL, dayStart);
        if (withdrawalsToday >= MAX_CREATOR_WITHDRAWALS_PER_DAY) {
            throw new ApiException(
                    "WITHDRAWAL_RATE_LIMIT",
                    "Maximum " + MAX_CREATOR_WITHDRAWALS_PER_DAY + " withdrawals per day",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        CreatorBankAccount bankAccount =
                creatorBankAccountRepository
                        .findByCreatorUserIdAndPrimaryTrue(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "BANK_ACCOUNT_NOT_FOUND",
                                                "No primary bank/UPI account on file for withdrawal",
                                                HttpStatus.CONFLICT));

        // Namespaced, never the raw client header — see method javadoc [M-6].
        String scopedKey = "creator-withdraw:" + userId + ":" + idempotencyKey;

        try {
            return idempotencyService.executeOnce(
                    scopedKey,
                    userId,
                    "wallet.withdraw",
                    () -> doProcessWithdrawal(wallet, bankAccount, amount, userId, scopedKey));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            CreatorWithdrawResponse replay = replayWithdrawalIfPresent(scopedKey);
            if (replay != null) {
                return replay;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This withdrawal is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Runs ONLY inside {@code executeOnce} — {@code wallet}/{@code bankAccount} were already
     * loaded/validated before the idempotency key was reserved (mirrors {@code
     * PayoutService#doQueuePayout}). Debits the creator's wallet via the ledger (its own unique
     * idempotency-key constraint backstops this against a double-debit even if this method were
     * somehow re-entered), resolves a real RazorpayX fund account, initiates the payout, and
     * persists the durable {@link Payout} row.
     */
    @Transactional
    protected CreatorWithdrawResponse doProcessWithdrawal(
            Wallet wallet, CreatorBankAccount bankAccount, BigDecimal amount, String userId, String scopedKey) {
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        ledgerService.post(
                wallet.getId(),
                clearingWallet.getId(),
                amount,
                wallet.getCurrency(),
                WalletTransactionType.WITHDRAWAL,
                TxnReferenceType.MANUAL,
                Ulids.newUlid(),
                "Creator withdrawal",
                scopedKey,
                null);

        String fundAccountId = fundAccountService.resolveFundAccountId(userId, bankAccount.getId());
        RazorpayXClient.PayoutResult payoutResult =
                razorpayXClient.initiatePayout(fundAccountId, amount, wallet.getCurrency(), scopedKey);

        payoutRepository.save(
                Payout.createQueued(
                        Ulids.newUlid(),
                        null, // no linked milestone — lump-sum wallet withdrawal
                        userId,
                        payoutResult.payoutId(),
                        fundAccountId,
                        amount,
                        wallet.getCurrency(),
                        payoutResult.status(),
                        scopedKey,
                        Instant.now()));

        return new CreatorWithdrawResponse(payoutResult.payoutId());
    }

    /**
     * Returns the prior response if a withdrawal was already queued under {@code scopedKey} (a
     * {@link Payout} row already exists for it), else {@code null} — mirrors {@code
     * PayoutService#replayIfPresent}.
     */
    private CreatorWithdrawResponse replayWithdrawalIfPresent(String scopedKey) {
        return payoutRepository
                .findByIdempotencyKey(scopedKey)
                .map(p -> new CreatorWithdrawResponse(p.getRazorpayPayoutId()))
                .orElse(null);
    }

    /**
     * Paginated ledger history for the creator's own wallet. Returns an empty page when no wallet
     * row exists yet (creators are not provisioned a wallet at signup).
     */
    @Transactional(readOnly = true)
    public PagedWalletTransactions getTransactionsForUser(String userId, int page, int limit) {
        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        return walletRepository
                .findByOwnerId(userId)
                .map(
                        wallet -> {
                            Page<WalletTransaction> result =
                                    walletTransactionRepository.findByWalletIdOrderByCreatedAtDesc(
                                            wallet.getId(),
                                            PageRequest.of(
                                                    safePage - 1,
                                                    safeLimit,
                                                    Sort.by(Sort.Direction.DESC, "createdAt")));
                            List<WalletTransactionRowResponse> items =
                                    result.getContent().stream().map(this::toTransactionRow).toList();
                            return new PagedWalletTransactions(
                                    items,
                                    new PageMeta(
                                            safePage,
                                            safeLimit,
                                            result.getTotalElements(),
                                            result.hasNext()));
                        })
                .orElseGet(
                        () ->
                                new PagedWalletTransactions(
                                        List.of(),
                                        new PageMeta(safePage, safeLimit, 0, false)));
    }

    private void validateCreatorWithdrawalAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(
                    "INVALID_WITHDRAWAL_AMOUNT",
                    "Withdrawal amount must be positive",
                    HttpStatus.BAD_REQUEST);
        }
        if (amount.compareTo(MIN_CREATOR_WITHDRAWAL) < 0) {
            throw new ApiException(
                    "MINIMUM_WITHDRAWAL",
                    "Minimum withdrawal is Rs. " + MIN_CREATOR_WITHDRAWAL.toPlainString(),
                    HttpStatus.BAD_REQUEST);
        }
        if (amount.compareTo(MAX_CREATOR_WITHDRAWAL) > 0) {
            throw new ApiException(
                    "MAXIMUM_WITHDRAWAL",
                    "Maximum withdrawal is Rs. " + MAX_CREATOR_WITHDRAWAL.toPlainString(),
                    HttpStatus.BAD_REQUEST);
        }
    }

    private WalletTransactionRowResponse toTransactionRow(WalletTransaction txn) {
        return new WalletTransactionRowResponse(
                txn.getId(),
                txn.getType().name(),
                txn.getDirection().name(),
                txn.getAmount(),
                txn.getCurrency(),
                txn.getDescription(),
                txn.getStatus().name(),
                txn.getCreatedAt(),
                txn.getBalanceAfter());
    }

    private WalletBalanceResponse toBalanceResponse(Wallet wallet) {
        Integer runwayDays = computeRunwayDays(wallet);
        return new WalletBalanceResponse(
                wallet.getId(),
                wallet.getBalance(),
                wallet.getEscrowBalance(),
                wallet.getCurrency(),
                runwayDays);
    }

    private WalletSummaryResponse toSummaryResponse(
            Wallet wallet, BigDecimal escrowLocked, BigDecimal pendingPayouts) {
        return new WalletSummaryResponse(
                wallet.getBalance(), escrowLocked, pendingPayouts, computeRunwayDays(wallet));
    }

    /**
     * Real-ledger runway calc (replaces the previously-fabricated frontend fallback of "47
     * days" — see wiki/tech/TASK-brand-audit-backend-build.md, item #1).
     *
     * <p>{@code avgDailyBurn = (sum of DEBIT ledger legs in the trailing BURN_WINDOW_DAYS) /
     * BURN_WINDOW_DAYS}. {@code runwayDays = floor(availableBalance / avgDailyBurn)}.
     *
     * <p>Returns {@code null} — never {@code Infinity} or a made-up number — when there has been
     * no spend in the window, since a runway is undefined (effectively infinite) for a dormant
     * wallet. The frontend is expected to render an honest "—" / "Healthy" state for a null value.
     */
    Integer computeRunwayDays(Wallet wallet) {
        Instant since = Instant.now().minus(BURN_WINDOW_DAYS, ChronoUnit.DAYS);
        BigDecimal trailingDebits =
                walletTransactionRepository.sumAmountByWalletIdAndDirectionSince(
                        wallet.getId(), TxnDirection.DEBIT, since);

        if (trailingDebits == null || trailingDebits.signum() <= 0) {
            return null;
        }

        BigDecimal avgDailyBurn =
                trailingDebits.divide(BigDecimal.valueOf(BURN_WINDOW_DAYS), 10, RoundingMode.HALF_UP);

        if (avgDailyBurn.signum() <= 0) {
            return null;
        }

        BigDecimal availableBalance = wallet.getBalance();
        if (availableBalance == null || availableBalance.signum() <= 0) {
            return 0;
        }

        BigDecimal rawRunwayDays = availableBalance.divide(avgDailyBurn, 0, RoundingMode.FLOOR);
        BigDecimal cap = BigDecimal.valueOf(MAX_REPORTED_RUNWAY_DAYS);
        return rawRunwayDays.compareTo(cap) > 0 ? MAX_REPORTED_RUNWAY_DAYS : rawRunwayDays.intValue();
    }

    /**
     * Money-path lookup — throws {@code WALLET_NOT_FOUND} (404) when the workspace has no wallet
     * row. Callers on the spend/fund path (e.g. {@link BrandCampaignFeeService}, {@link
     * EscrowService}, {@link WalletTopUpService}) rely on this throwing behavior: a missing wallet
     * means "cannot afford" and must NOT be silently papered over with a zero-balance wallet.
     * Read-only display endpoints should use {@link #getOrCreateWorkspaceWallet} instead.
     */
    @Transactional(readOnly = true)
    public Wallet requireWorkspaceWallet(String workspaceId) {
        return walletRepository
                .findByOwnerId(workspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "WALLET_NOT_FOUND", "Wallet not found for workspace", HttpStatus.NOT_FOUND));
    }

    /**
     * Lazily creates a workspace's wallet on first read — brands/workspaces aren't guaranteed a
     * wallet row at registration, and until now that meant a fresh brand's dashboard read {@code
     * GET /wallet} / {@code GET /wallet/balance} 404'd with {@code WALLET_NOT_FOUND} instead of
     * showing a zero balance. Display-only: does not affect {@link #requireWorkspaceWallet}, which
     * money-moving callers still use to treat a missing wallet as "cannot afford".
     */
    @Transactional
    public Wallet getOrCreateWorkspaceWallet(String workspaceId) {
        return walletRepository
                .findByOwnerId(workspaceId)
                .orElseGet(() -> walletRepository.save(Wallet.forWorkspace(Ulids.newUlid(), workspaceId)));
    }

    /** Lazily creates a creator's wallet on first payout — creators aren't given one at signup. */
    @Transactional
    public Wallet requireOrCreateUserWallet(String userId) {
        return walletRepository
                .findByOwnerId(userId)
                .orElseGet(() -> walletRepository.save(Wallet.forUser(Ulids.newUlid(), userId)));
    }

    /**
     * Deposits externally-received funds (e.g. a confirmed Razorpay top-up) into a workspace's
     * wallet. {@code amount} must already be server-derived from a verified payment gateway
     * event — never a raw client-supplied value.
     */
    @Transactional
    public void deposit(
            String workspaceId,
            String platformWalletId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String gatewayRef) {
        Wallet destination = requireWorkspaceWallet(workspaceId);
        ledgerService.post(
                platformWalletId,
                destination.getId(),
                amount,
                currency,
                WalletTransactionType.DEPOSIT,
                TxnReferenceType.DEPOSIT_ORDER,
                Ulids.newUlid(),
                "Wallet deposit",
                idempotencyKey,
                gatewayRef);
    }

    /**
     * Withdraws funds from a workspace's wallet to the platform's clearing wallet ahead of a
     * payout. {@code amount} must already be server-derived (e.g. from a released milestone) —
     * never a raw client-supplied value.
     */
    @Transactional
    public void withdraw(
            String workspaceId,
            String platformWalletId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String gatewayRef) {
        Wallet source = requireWorkspaceWallet(workspaceId);
        ledgerService.post(
                source.getId(),
                platformWalletId,
                amount,
                currency,
                WalletTransactionType.WITHDRAWAL,
                TxnReferenceType.MANUAL,
                Ulids.newUlid(),
                "Wallet withdrawal",
                idempotencyKey,
                gatewayRef);
    }
}
