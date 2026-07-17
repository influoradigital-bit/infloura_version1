package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.TransactionStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.WalletRepository;
import com.influora.repository.WalletTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sole writer of {@code wallet_transactions}. Every logical money movement is posted as a
 * balanced double-entry pair: one DEBIT leg on the source wallet, one CREDIT leg on the
 * destination wallet, sharing a {@code group_id}. {@code wallets.balance} is a denormalized
 * projection kept in sync inside the same transaction — the ledger rows are the source of truth.
 *
 * <p>Guardrail 1 (see 03-SECURITY-SPEC.md): callers never hand this service a client-trusted
 * amount to move blindly. {@link #post} takes a caller-supplied {@link BigDecimal} amount, but
 * every call site in this codebase is required to have already re-derived that amount from
 * persisted state (a milestone row, server fee config, etc.) before calling in — this service
 * enforces that the amount is positive and re-validates it is not null/zero, but the
 * re-derivation itself is the caller's contract. No controller in this slice calls this service
 * directly with a request-body amount.
 */
@Service
public class WalletLedgerService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    public WalletLedgerService(
            WalletRepository walletRepository,
            WalletTransactionRepository walletTransactionRepository) {
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
    }

    /** The two ledger rows written by a single balanced posting. */
    public record LedgerPostingResult(WalletTransaction debitLeg, WalletTransaction creditLeg) {}

    /**
     * Posts a balanced double-entry movement: debits {@code debitWalletId} and credits
     * {@code creditWalletId} for the same {@code amount}, in one transaction, sharing a single
     * {@code group_id}. Idempotent on {@code idempotencyKey} — a retry with the same key returns
     * the previously-applied result instead of moving money twice.
     *
     * @param amount server-derived, always positive. Never pass a raw client/LLM-supplied value.
     */
    @Transactional
    public LedgerPostingResult post(
            String debitWalletId,
            String creditWalletId,
            BigDecimal amount,
            String currency,
            WalletTransactionType type,
            TxnReferenceType referenceType,
            String referenceId,
            String description,
            String idempotencyKey,
            String gatewayRef) {

        validateAmount(amount);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "idempotency_key is required for every ledger posting",
                    HttpStatus.BAD_REQUEST);
        }
        if (debitWalletId == null || creditWalletId == null || debitWalletId.equals(creditWalletId)) {
            throw new ApiException(
                    "INVALID_LEDGER_WALLETS",
                    "Debit and credit wallets must both be set and distinct",
                    HttpStatus.BAD_REQUEST);
        }

        // Fast-path optimization only: avoids taking wallet locks on the common non-race case.
        // This upfront SELECT is NOT sufficient for correctness on its own — two concurrent
        // requests with the same idempotencyKey can both observe null here before either has
        // committed. The uq_wtx_idem unique constraint at save() time is the real serialization
        // point; see the catch block below for the authoritative "insert-first, catch-and-fetch"
        // handling.
        LedgerPostingResult existing = findExistingPosting(idempotencyKey);
        if (existing != null) {
            assertReplayMatches(existing, debitWalletId, creditWalletId, amount, type);
            return existing;
        }

        // Lock wallets in a stable order (by id) to avoid deadlocks between concurrent postings
        // that touch the same two wallets in opposite directions.
        String firstId = debitWalletId.compareTo(creditWalletId) <= 0 ? debitWalletId : creditWalletId;
        String secondId = firstId.equals(debitWalletId) ? creditWalletId : debitWalletId;

        Wallet first = requireWalletForUpdate(firstId);
        Wallet second = requireWalletForUpdate(secondId);
        Wallet debitWallet = first.getId().equals(debitWalletId) ? first : second;
        Wallet creditWallet = first.getId().equals(creditWalletId) ? first : second;

        if (!debitWallet.getCurrency().equals(creditWallet.getCurrency())) {
            throw new ApiException(
                    "CURRENCY_MISMATCH",
                    "Debit and credit wallets must share the same currency",
                    HttpStatus.BAD_REQUEST);
        }
        if (currency != null && !currency.equals(debitWallet.getCurrency())) {
            throw new ApiException(
                    "CURRENCY_MISMATCH",
                    "Supplied currency does not match the wallets' currency",
                    HttpStatus.BAD_REQUEST);
        }

        // [W1-1] The platform clearing wallet is a contra/clearing account, not a spendable
        // balance: it is the ledger counterparty for funds that have physically arrived at (or are
        // about to leave through) Razorpay. A brand's first top-up DEBITS this wallet while it is
        // still at its bootstrap balance of 0 — that debit is correct and must be allowed to go
        // negative. Seeding it with an opening balance instead would FABRICATE MONEY on a
        // double-entry ledger, so the fix is to exempt it from the non-negative check rather than
        // give it starting funds. This exemption is intentionally narrow: only the clearing
        // wallet's owner id qualifies. The platform revenue wallet is NOT exempted — every call
        // site in this codebase only ever CREDITS it (BrandCampaignFeeService, PlatformFeeService),
        // it is never a debitWallet, so it needs no exemption and keeping the invariant on it is
        // free defense in depth. Brand/creator wallets are never exempt; this must never leak.
        boolean debitWalletIsExemptPlatformAccount =
                PlatformWalletService.PLATFORM_CLEARING_WALLET_OWNER_ID.equals(debitWallet.getOwnerId());
        if (!debitWalletIsExemptPlatformAccount
                && debitWallet.getBalance().compareTo(amount) < 0) {
            throw new ApiException(
                    "INSUFFICIENT_BALANCE",
                    "Insufficient available balance",
                    HttpStatus.BAD_REQUEST);
        }

        String resolvedCurrency = debitWallet.getCurrency();
        String groupId = Ulids.newUlid();

        debitWallet.applyBalanceDelta(amount.negate());
        WalletTransaction debitLeg =
                WalletTransaction.builder()
                        .id(Ulids.newUlid())
                        .walletId(debitWallet.getId())
                        .groupId(groupId)
                        .direction(TxnDirection.DEBIT)
                        .type(type)
                        .amount(amount)
                        .currency(resolvedCurrency)
                        .balanceAfter(debitWallet.getBalance())
                        .status(TransactionStatus.COMPLETED)
                        .referenceType(referenceType)
                        .referenceId(referenceId)
                        .description(description)
                        .idempotencyKey(idempotencyKey + ":D")
                        .gatewayRef(gatewayRef)
                        .build();

        creditWallet.applyBalanceDelta(amount);
        WalletTransaction creditLeg =
                WalletTransaction.builder()
                        .id(Ulids.newUlid())
                        .walletId(creditWallet.getId())
                        .groupId(groupId)
                        .direction(TxnDirection.CREDIT)
                        .type(type)
                        .amount(amount)
                        .currency(resolvedCurrency)
                        .balanceAfter(creditWallet.getBalance())
                        .status(TransactionStatus.COMPLETED)
                        .referenceType(referenceType)
                        .referenceId(referenceId)
                        .description(description)
                        .idempotencyKey(idempotencyKey + ":C")
                        .gatewayRef(gatewayRef)
                        .build();

        try {
            walletRepository.save(debitWallet);
            walletRepository.save(creditWallet);
            walletTransactionRepository.save(debitLeg);
            walletTransactionRepository.save(creditLeg);
        } catch (DataIntegrityViolationException e) {
            // The upfront SELECT missed a concurrent in-flight posting with the same
            // idempotencyKey; the uq_wtx_idem unique constraint is the real serialization point.
            // Re-query for the now-committed posting and return it instead of leaking a raw
            // DB exception as an uncaught 500.
            LedgerPostingResult raced = findExistingPosting(idempotencyKey);
            if (raced != null) {
                assertReplayMatches(raced, debitWalletId, creditWalletId, amount, type);
                return raced;
            }
            throw new ApiException(
                    "LEDGER_POSTING_CONFLICT",
                    "Ledger posting conflicted with a concurrent request for the same idempotency key",
                    HttpStatus.CONFLICT);
        }

        return new LedgerPostingResult(debitLeg, creditLeg);
    }

    /**
     * Looks up the ledger rows for a group_id (both legs of one posting) — used by callers that
     * need to display or reference a completed movement (e.g. escrow hold linking its debit leg).
     */
    @Transactional(readOnly = true)
    public List<WalletTransaction> findGroup(String groupId) {
        return walletTransactionRepository.findByGroupId(groupId);
    }

    /**
     * Defense in depth against idempotency-key collisions across unrelated calls (e.g. a client
     * reusing the same raw {@code Idempotency-Key} header across a top-up and an escrow-fund
     * request — [SEC: Kabir OWASP CRITICAL]). {@code idempotencyKey} is only globally unique at
     * the DB level ({@code uq_wtx_idem}); it is NOT feature-scoped by construction, so a found
     * "existing" posting must be verified to actually correspond to the current call before it is
     * trusted as a legitimate replay. A mismatch on wallets/amount/type means two different
     * logical operations collided on the same key — that is a genuine conflict, not a replay, and
     * must throw rather than silently hand back the wrong money movement.
     */
    private static void assertReplayMatches(
            LedgerPostingResult existing,
            String debitWalletId,
            String creditWalletId,
            BigDecimal amount,
            WalletTransactionType type) {
        WalletTransaction debitLeg = existing.debitLeg();
        WalletTransaction creditLeg = existing.creditLeg();
        boolean matches =
                debitLeg.getWalletId().equals(debitWalletId)
                        && creditLeg.getWalletId().equals(creditWalletId)
                        && debitLeg.getType() == type
                        && creditLeg.getType() == type
                        && debitLeg.getAmount().compareTo(amount) == 0
                        && creditLeg.getAmount().compareTo(amount) == 0;
        if (!matches) {
            throw new ApiException(
                    "LEDGER_IDEMPOTENCY_KEY_COLLISION",
                    "Idempotency key was already used for a different money movement",
                    HttpStatus.CONFLICT);
        }
    }

    private LedgerPostingResult findExistingPosting(String idempotencyKey) {
        var debit = walletTransactionRepository.findByIdempotencyKey(idempotencyKey + ":D");
        var credit = walletTransactionRepository.findByIdempotencyKey(idempotencyKey + ":C");
        if (debit.isPresent() && credit.isPresent()) {
            return new LedgerPostingResult(debit.get(), credit.get());
        }
        if (debit.isPresent() || credit.isPresent()) {
            // One leg exists without its pair — a prior attempt crashed mid-write. Since both
            // legs are written in the same @Transactional method, this state means the
            // transaction never committed and the half-row was rolled back; treat as not found
            // rather than silently returning a mismatched pair.
            throw new ApiException(
                    "LEDGER_POSTING_INCONSISTENT",
                    "Ledger posting for this idempotency key is in an inconsistent state",
                    HttpStatus.CONFLICT);
        }
        return null;
    }

    private Wallet requireWalletForUpdate(String walletId) {
        return walletRepository
                .findByIdForUpdate(walletId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "WALLET_NOT_FOUND", "Wallet not found", HttpStatus.NOT_FOUND));
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(
                    "INVALID_LEDGER_AMOUNT",
                    "Ledger amount must be a positive, server-derived value",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
