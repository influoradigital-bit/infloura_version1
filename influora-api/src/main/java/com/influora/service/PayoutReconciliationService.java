package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Payout;
import com.influora.domain.entity.Wallet;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletTransactionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [B7/C-6] Webhook-driven {@link Payout} reconciliation — the counterpart to {@link
 * PayoutService#queuePayout}'s queue-time persistence. Called from {@code
 * RazorpayWebhookController} on a verified {@code payout.processed}/{@code payout.reversed}
 * RazorpayX webhook, never synchronously by a client request (same out-of-band-confirm discipline
 * as every other money-state transition in this codebase).
 *
 * <p>Deliberately a separate service/bean from {@link PayoutService} rather than a method on it:
 * {@link PayoutService}'s constructor is pinned by its existing unit test suite (fund-account
 * resolution + idempotent queueing, no wallet-ledger dependency) — adding {@link
 * WalletLedgerService}/{@link PlatformWalletService}/{@link WalletService} to that constructor
 * would break that suite's exact wiring. Splitting the webhook-reconciliation concern out here
 * keeps {@code PayoutService}'s pinned shape intact while still giving the re-credit-on-reversal
 * logic below the wallet-ledger access it needs.
 */
@Service
public class PayoutReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciliationService.class);

    /** RazorpayX payout status meaning the payout failed/bounced back — money never reached the creator. */
    private static final String STATUS_REVERSED = "reversed";

    /**
     * [Admin finance console, payout-retry bug fix] The other two RazorpayX terminal-failure
     * statuses — same "money never reached the creator" meaning as {@link #STATUS_REVERSED}, but
     * {@link #confirmExecuted} previously only re-credited the wallet on {@code reversed}. A
     * payout the bank/RazorpayX itself REJECTED, or one CANCELLED before disbursement, left the
     * creator's wallet debited with no re-credit path — see {@link #isFailureStatus} and the
     * broadened condition in {@link #confirmExecuted}.
     */
    private static final String STATUS_REJECTED = "rejected";

    private static final String STATUS_CANCELLED = "cancelled";

    /**
     * [Red-team F2] Public so {@code PayoutOrphanedDebitSweepJob} can build its own repository
     * query off the exact same set this class's {@link #isFailureStatus} checks against — one
     * source of truth, no risk of the sweep's query drifting from what this service actually
     * treats as a terminal failure.
     */
    public static final List<String> FAILURE_STATUSES =
            List.of(STATUS_REVERSED, STATUS_REJECTED, STATUS_CANCELLED);

    /** {@link IdempotencyService} scope for {@link #retryFailedPayout} — distinct from every other scope in this codebase (see {@code IdempotencyService}'s composite-key javadoc). */
    private static final String RETRY_IDEMPOTENCY_SCOPE = "admin.payout.retry";

    private final PayoutRepository payoutRepository;
    private final WalletLedgerService ledgerService;
    private final PlatformWalletService platformWalletService;
    private final WalletService walletService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final RazorpayXClient razorpayXClient;
    private final IdempotencyService idempotencyService;

    public PayoutReconciliationService(
            PayoutRepository payoutRepository,
            WalletLedgerService ledgerService,
            PlatformWalletService platformWalletService,
            WalletService walletService,
            WalletTransactionRepository walletTransactionRepository,
            RazorpayXClient razorpayXClient,
            IdempotencyService idempotencyService) {
        this.payoutRepository = payoutRepository;
        this.ledgerService = ledgerService;
        this.platformWalletService = platformWalletService;
        this.walletService = walletService;
        this.walletTransactionRepository = walletTransactionRepository;
        this.razorpayXClient = razorpayXClient;
        this.idempotencyService = idempotencyService;
    }

    /** True for any RazorpayX status meaning the payout definitively did not reach the creator. */
    private static boolean isFailureStatus(String status) {
        return STATUS_REVERSED.equals(status)
                || STATUS_REJECTED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    /**
     * Applies a verified {@code payout.processed}/{@code payout.reversed}/{@code payout.rejected}
     * webhook to the matching {@link Payout} row (looked up by {@code razorpayPayoutId} — the id
     * {@link PayoutService#queuePayout} persisted at queue time). Idempotent: a duplicate webhook
     * delivery reporting the SAME status is a no-op (checked before touching the wallet ledger),
     * and — for a genuine {@code reversed} — the re-credit itself is idempotent via the ledger's
     * own {@code idempotencyKey} dedup, so a retried delivery can never re-credit the creator
     * twice.
     *
     * @param razorpayPayoutId RazorpayX's own payout id (webhook {@code payload.payout.entity.id})
     * @param newStatus the webhook's reported terminal status (RazorpayX payout status values:
     *     {@code processing}, {@code processed}, {@code reversed}, {@code cancelled}, {@code
     *     rejected})
     * @param rawWebhookPayload the full verified webhook body, stored on the row for audit/replay
     */
    @Transactional
    public void confirmExecuted(String razorpayPayoutId, String newStatus, String rawWebhookPayload) {
        if (razorpayPayoutId == null || razorpayPayoutId.isBlank()) {
            log.warn("payout webhook missing payload.payout.entity.id — nothing to reconcile");
            return;
        }

        Payout payout = payoutRepository.findByRazorpayPayoutId(razorpayPayoutId).orElse(null);
        if (payout == null) {
            // Defensive only: every payout queued via PayoutService#doQueuePayout after the B7/C-5/C-6
            // fix persists a row up front, so this should not happen for a genuinely-queued payout.
            // Ack the webhook rather than 500/retry-storm on an id we have no record of (e.g. a
            // payout queued before this fix shipped, or a manual RazorpayX dashboard action outside
            // this codebase).
            log.warn("No Payout row found for razorpayPayoutId={} — webhook acknowledged, not applied", razorpayPayoutId);
            return;
        }

        if (newStatus != null && newStatus.equals(payout.getStatus())) {
            // Duplicate delivery reporting the same status this row already has — no-op.
            return;
        }

        boolean wasAlreadyFailed = isFailureStatus(payout.getStatus());
        payout.confirmStatus(newStatus, rawWebhookPayload);
        payoutRepository.save(payout);

        // [C-6, broadened — admin finance console payout-retry bug fix] Re-credit on a genuine
        // terminal failure — a payout RazorpayX reports as `reversed`, `rejected`, or `cancelled`
        // all mean the same thing: money never actually reached the creator's bank/UPI account
        // (bounced, rejected by the bank, cancelled before disbursement, etc.), but
        // EscrowService#release already credited the creator's Influora wallet's NET amount out of
        // escrow before the payout was ever queued, and PayoutService#doQueuePayout debited that
        // same amount back out again before calling RazorpayX. Without this, the creator would be
        // left short — their wallet no longer reflects money that, in reality, never left the
        // platform. Previously this ONLY fired for `reversed`, leaving a `rejected`/`cancelled`
        // payout's debit permanently stranded (the bug the admin payout-retry feature fixes).
        // Re-crediting the clearing wallet -> creator wallet mirrors a refund, using the same
        // ledger idempotency-key dedup every other money movement in this codebase relies on, so a
        // retried delivery can never double-credit.
        if (isFailureStatus(newStatus) && !wasAlreadyFailed) {
            reCreditReversedPayout(payout, razorpayPayoutId);
        }
    }

    /**
     * [P1 fix, SEC: Kabir, landed-money-path audit 2b — orphaned-debit sweeper.] Called by {@code
     * PayoutOrphanedDebitSweepJob} for every {@link Payout} row still sitting in {@link
     * Payout#STATUS_PENDING} past the sweep's grace period — i.e. {@code
     * PayoutService#doQueuePayout} persisted the row before calling RazorpayX, but no result was
     * ever durably recorded (a crash/failure between the wallet debit committing and the gateway
     * response being saved; see that method's javadoc).
     *
     * <p>Two cases, distinguished by whether the queue-time debit actually posted:
     *
     * <ul>
     *   <li><b>No debit posted</b> ({@code payout-debit:&lt;milestoneId&gt;} ledger key absent) —
     *       the process crashed before ever debiting the creator. There is no orphaned debit to
     *       reconcile; this row is simply awaiting a legitimate retry through {@code
     *       PayoutService#queuePayout} (which will find and reuse this same row by {@code
     *       idempotencyKey}). Logged only, no money movement.
     *   <li><b>Debit posted</b> — retry the (idempotent) RazorpayX call first, reusing the SAME
     *       {@code idempotencyKey}/{@code reference_id} the original attempt used, so this can
     *       never double-pay even if the original call actually succeeded and only the local
     *       confirmation was lost. Only if that retry itself fails do we reverse the debit — the
     *       SAME re-credit path {@link #confirmExecuted} already uses for a genuine RazorpayX
     *       {@code reversed} webhook (C-6), kept as the one re-credit code path in this codebase
     *       rather than inventing a second one.
     * </ul>
     */
    @Transactional
    public void reconcileOrphanedPendingPayout(Payout payout) {
        if (!Payout.STATUS_PENDING.equals(payout.getStatus())) {
            // Already progressed past PENDING between the sweep query running and this row being
            // processed (e.g. the original request finally completed, or a prior sweep run already
            // handled it) — nothing to do.
            return;
        }

        boolean debitPosted =
                walletTransactionRepository
                        .findByIdempotencyKey("payout-debit:" + payout.getMilestoneId() + ":D")
                        .isPresent();
        if (!debitPosted) {
            log.warn(
                    "PayoutReconciliation: PENDING payout {} (milestone {}) has no debit posted yet —"
                            + " nothing orphaned, awaiting a legitimate retry via PayoutService#queuePayout",
                    payout.getId(),
                    payout.getMilestoneId());
            return;
        }

        // [Admin finance console, payout-retry] Gateway-attempt core extracted to
        // attemptGatewayPayout so retryFailedPayout (below) can reuse the exact same
        // success/failure handling instead of a second copy of it. Reuses the SAME idempotencyKey
        // the original queue-time debit was posted under (see that method's own javadoc for why —
        // this call may be confirming an attempt that actually already succeeded at RazorpayX).
        //
        // [Red-team F1 fix] Reversal key is keyed on razorpayPayoutId (here still the
        // Payout#createPending placeholder "pending:<id>", since markGatewayConfirmed has not run
        // yet for this row), not the payout row id, for the SAME reason confirmExecuted's own
        // re-credit key changed below — one namespace, no risk of a later attempt's re-credit
        // colliding with this one.
        attemptGatewayPayout(
                payout, payout.getIdempotencyKey(), "payout-reversed:" + payout.getRazorpayPayoutId());
    }

    /**
     * Shared gateway-attempt core reused by {@link #reconcileOrphanedPendingPayout} (orphaned-debit
     * sweep — reuses the SAME idempotencyKey the original queue-time debit was posted under, so a
     * retried delivery can never double-pay if the original call actually succeeded) and {@link
     * #retryFailedPayout} (admin-triggered retry of a definitively-failed payout — passes a FRESH
     * per-attempt idempotencyKey instead, since {@code retryFailedPayout}'s whole point is a new
     * attempt after RazorpayX already definitively rejected/cancelled the old one).
     *
     * <p>On a successful RazorpayX response the {@link Payout} row is transitioned to its new
     * gateway identity/status. On any failure it is marked {@link #STATUS_REVERSED} and the debit
     * is re-credited via {@code reversalIdempotencyKey} — unless it was already reversed, in which
     * case the re-credit call is skipped (its own ledger idempotency key would make a repeat call a
     * safe no-op anyway; skipping just avoids a pointless extra ledger write attempt).
     */
    private void attemptGatewayPayout(
            Payout payout, String gatewayIdempotencyKey, String reversalIdempotencyKey) {
        try {
            RazorpayXClient.PayoutResult result =
                    razorpayXClient.initiatePayout(
                            payout.getFundAccountId(),
                            payout.getAmount(),
                            payout.getCurrency(),
                            gatewayIdempotencyKey);
            payout.markGatewayConfirmed(result.payoutId(), result.status());
            payoutRepository.save(payout);
            log.info(
                    "PayoutReconciliation: gateway attempt confirmed for payout {} — razorpayPayoutId={}"
                            + " status={}",
                    payout.getId(),
                    result.payoutId(),
                    result.status());
        } catch (Exception e) {
            log.error(
                    "PayoutReconciliation: gateway attempt failed for payout {} (milestone {}) —"
                            + " reversing the debit instead",
                    payout.getId(),
                    payout.getMilestoneId(),
                    e);
            boolean wasAlreadyReversed = STATUS_REVERSED.equals(payout.getStatus());
            payout.confirmStatus(STATUS_REVERSED, null);
            payoutRepository.save(payout);
            if (!wasAlreadyReversed) {
                reCreditReversedPayout(payout, payout.getRazorpayPayoutId(), reversalIdempotencyKey);
            }
        }
    }

    /**
     * [Admin finance console, payout-retry] Re-initiates a definitively-failed ({@code rejected}/
     * {@code cancelled}/{@code reversed}) creator payout from the admin Finance console ({@code
     * POST /admin/finance/payouts/{id}/retry}, gated SUPER_ADMIN+ADMIN+MFA in {@code
     * AdminFinanceService} — role-gating happens there, this method has no principal/auth
     * awareness of its own). Money-path, so every step is deliberately defensive:
     *
     * <ul>
     *   <li><b>Terminal-failure only.</b> {@code queued}/{@code pending}/{@code processing}/{@code
     *       processed}/{@link Payout#STATUS_PENDING} are all rejected with {@code
     *       PAYOUT_NOT_RETRYABLE} — never silently re-attempted.
     *   <li><b>Double-retry guard.</b> The whole operation runs inside {@link
     *       IdempotencyService#executeOnce}, keyed on {@link #buildRetryAttemptKey} — {@code
     *       payoutId + status + updatedAt} at the moment THIS attempt starts, i.e. on THIS SPECIFIC
     *       failure event. A concurrent double-click (or a client-retried HTTP request) collides on
     *       the same reservation row and is rejected with {@code PAYOUT_RETRY_ALREADY_HANDLED}
     *       rather than ever reaching RazorpayX a second time for the same failure. Once a retry
     *       actually changes the row's status/updatedAt, a LATER genuine retry (e.g. after a
     *       second, independent failure) computes a different key and is allowed to proceed — this
     *       is a per-failure-event lock, not a permanent one-shot lock on the payout.
     *   <li><b>Re-credit safety net.</b> Re-credits the creator's wallet for the ORIGINAL
     *       queue-time debit if that has not already happened — idempotent no-op via {@link
     *       #reCreditReversedPayout}'s own ledger key if {@link #confirmExecuted}'s fix already did
     *       it when the failure webhook first arrived. Retrying on top of an un-recredited debit
     *       would let a successful retry pay the creator twice: once via the stale debit eventually
     *       becoming withdrawable again, once via the real bank transfer this retry sends.
     *   <li><b>Fresh debit, fresh gateway key — deterministic, not random [Red-team F2 fix].</b>
     *       Debits the creator's wallet again for THIS retry attempt under a key built from {@link
     *       #buildRetryAttemptKey} (the SAME string guarding the idempotency reservation above) —
     *       reusing the original queue-time debit key would be a no-op against an already-settled
     *       ledger row and silently skip the debit entirely — and calls RazorpayX with a matching
     *       FRESH gateway key, never {@link Payout#getIdempotencyKey()}, which RazorpayX has
     *       already seen and definitively rejected/cancelled once. Deliberately NOT a random id
     *       (e.g. a fresh ULID): a crash between the debit committing and the gateway result being
     *       recorded would make a random id unrecoverable, leaving the debit permanently orphaned
     *       with nothing able to reconstruct which key it was posted under. {@code
     *       buildRetryAttemptKey}'s inputs (row id, status, updatedAt) do not change again until
     *       {@link #attemptGatewayPayout} itself resolves this attempt, so {@link
     *       #reconcileFailedPayoutRetry} (the sweep counterpart, {@code
     *       PayoutOrphanedDebitSweepJob}) can recompute the IDENTICAL key from the row's own
     *       persisted state after a crash — no new column required.
     *   <li>Delegates the actual gateway call and success/failure handling to {@link
     *       #attemptGatewayPayout} — the SAME core {@link #reconcileOrphanedPendingPayout} and
     *       {@link #reconcileFailedPayoutRetry} use, not a second copy of that logic.
     * </ul>
     */
    public Payout retryFailedPayout(String payoutId) {
        Payout payout =
                payoutRepository
                        .findById(payoutId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "PAYOUT_NOT_FOUND", "Payout not found", HttpStatus.NOT_FOUND));

        if (!isFailureStatus(payout.getStatus())) {
            throw new ApiException(
                    "PAYOUT_NOT_RETRYABLE",
                    "Payout "
                            + payoutId
                            + " is not in a retryable state (status="
                            + payout.getStatus()
                            + ")",
                    HttpStatus.CONFLICT);
        }

        String retryAttemptKey = buildRetryAttemptKey(payout);
        try {
            return idempotencyService.executeOnce(
                    retryAttemptKey,
                    null,
                    RETRY_IDEMPOTENCY_SCOPE,
                    () -> doRetryFailedPayout(payout, retryAttemptKey));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            throw new ApiException(
                    "PAYOUT_RETRY_ALREADY_HANDLED",
                    "This payout's current failure is already being retried",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * [Red-team F2 fix, HIGH — VARCHAR(64) overflow.] {@code retryDebitKey}/{@code
     * retryReversalKey} are written to {@code wallet_transactions.idempotency_key} — {@code
     * VARCHAR(64)} (V8__wallet_transactions.sql:15, {@code WalletTransaction.java:61 length=64}) —
     * via {@link WalletLedgerService#post}, which itself appends {@code ":D"}/{@code ":C"} (2 more
     * chars) before writing either leg. The RAW {@code payoutId(26) + ":" + status + ":" +
     * updatedAt(~30-char Instant.toString())} concatenation this used to build on
     * ({@link #buildRetryAttemptKey}) is 60-67 characters on its own — comfortably over budget once
     * a key PREFIX and the {@code :D}/{@code :C} suffix are added (measured worst case 86-91 chars
     * against a 26-char ULID id and {@code "cancelled"}, the longest {@link #FAILURE_STATUSES}
     * value). Against real MySQL that is either a hard {@code Data too long} failure (strict mode —
     * every retry fails) or silent truncation (non-strict — the {@code :D}/{@code :C} suffix gets
     * cut off, both legs collapse onto the SAME stored key, {@code uq_wtx_idem} throws a UNIQUE
     * violation, AND {@link #reconcileFailedPayoutRetry}'s lookup by the untruncated full key can
     * never match the truncated row — crash recovery silently broken).
     *
     * <p>Fixed by hashing: every per-attempt key below is a short, fixed-width, readable prefix
     * plus a SHA-256 digest of the underlying {@code payoutId:status:updatedAt} identity, truncated
     * to {@link #RETRY_KEY_DIGEST_LENGTH} hex characters — deterministic (same input, same output,
     * every time) and STILL fully recomputable by {@link #reconcileFailedPayoutRetry} purely from
     * the row's own persisted state after a crash (no new column, same recoverability guarantee as
     * before — only the STRING SHAPE changed, not what it is derived from). Worst-case stored
     * lengths after WalletLedgerService's suffix: {@code retryDebitKey} 19 (prefix) + 32 (digest) +
     * 2 (suffix) = 53 chars; {@code retryReversalKey} 22 + 32 + 2 = 56 chars — both comfortably
     * under 64. {@code retryGatewayKey} (sent to RazorpayX, never stored in a length-bounded column
     * of ours) is hashed the same way purely for consistency/brevity, not because it was ever a
     * measured overflow risk here.
     */
    static final int RETRY_KEY_DIGEST_LENGTH = 32;

    /**
     * [Red-team F1/F2] Deterministic identity for "one specific retry attempt against one specific
     * failure snapshot" — {@code payoutId:status:updatedAt} at the instant the attempt starts.
     * Doubles as (a) the {@link IdempotencyService} reservation key guarding against a concurrent
     * double-submit — stored in {@code idempotency_keys.idempotency_key}, {@code VARCHAR(128)} per
     * V15, composed as {@code scope + ":" + workspaceId + ":" + idempotencyKey} by {@code
     * IdempotencyService#compositeKey}; worst case {@code "admin.payout.retry" (18) + ":" + "" + ":"
     * + } this key's own 67-char worst case {@code = 87} chars, comfortably under 128 — and (b) the
     * seed hashed into every per-attempt ledger/gateway key ({@link #retryDebitKey}/{@link
     * #retryGatewayKey}/{@link #retryReversalKey}, see {@link #RETRY_KEY_DIGEST_LENGTH}'s javadoc
     * for why THOSE are hashed rather than using this raw string directly). A retry attempt that
     * itself fails advances {@code status}/{@code updatedAt} (via {@link #attemptGatewayPayout}'s
     * {@code confirmStatus} call), so the NEXT retry attempt against the SAME payout always computes
     * a different key here — never a collision between two distinct attempts' debit/reversal
     * postings.
     */
    static String buildRetryAttemptKey(Payout payout) {
        return payout.getId() + ":" + payout.getStatus() + ":" + payout.getUpdatedAt();
    }

    /**
     * SHA-256 of {@code retryAttemptKey}, hex-encoded and truncated to {@link
     * #RETRY_KEY_DIGEST_LENGTH} characters. {@code SHA-256} is a standard JCA algorithm guaranteed
     * present on every JVM, so {@link NoSuchAlgorithmException} is not a normal runtime condition —
     * wrapped as an unchecked failure rather than forcing every caller to declare/handle it.
     */
    private static String digestRetryAttemptKey(String retryAttemptKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(retryAttemptKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, RETRY_KEY_DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }

    static String retryDebitKey(String retryAttemptKey) {
        return "payout-debit:retry:" + digestRetryAttemptKey(retryAttemptKey);
    }

    static String retryGatewayKey(String retryAttemptKey) {
        return "payout-retry:" + digestRetryAttemptKey(retryAttemptKey);
    }

    static String retryReversalKey(String retryAttemptKey) {
        return "payout-reversed:retry:" + digestRetryAttemptKey(retryAttemptKey);
    }

    private Payout doRetryFailedPayout(Payout payout, String retryAttemptKey) {
        // Safety net for the re-credit bug (see class/method javadoc): ensure the ORIGINAL
        // queue-time debit was actually reversed. Idempotent via reCreditReversedPayout's own
        // razorpayPayoutId-keyed ledger key — a safe no-op if confirmExecuted's fix already
        // handled it. payout.getRazorpayPayoutId() here is still the OLD (pre-retry) attempt's
        // real gateway id, so this correctly matches whatever key confirmExecuted used for it.
        reCreditReversedPayout(payout, payout.getRazorpayPayoutId());

        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet creatorWallet = walletService.requireOrCreateUserWallet(payout.getCreatorUserId());
        String debitIdempotencyKey = retryDebitKey(retryAttemptKey);
        ledgerService.post(
                creatorWallet.getId(),
                clearingWallet.getId(),
                payout.getAmount(),
                payout.getCurrency(),
                WalletTransactionType.PAYOUT,
                payout.getMilestoneId() != null ? TxnReferenceType.MILESTONE : TxnReferenceType.MANUAL,
                payout.getMilestoneId() != null ? payout.getMilestoneId() : payout.getId(),
                "Payout retry debit for payout " + payout.getId(),
                debitIdempotencyKey,
                null);

        attemptGatewayPayout(
                payout, retryGatewayKey(retryAttemptKey), retryReversalKey(retryAttemptKey));
        return payout;
    }

    /**
     * [Red-team F2 fix, MEDIUM — retry debit stranded on crash] Sweep counterpart to {@link
     * #doRetryFailedPayout}'s debit-then-gateway-call window, called by {@code
     * PayoutOrphanedDebitSweepJob} for every {@link Payout} row still sitting in a {@link
     * #FAILURE_STATUSES} status past the grace period. A retry attempt never transitions the row
     * into {@link Payout#STATUS_PENDING} (unlike the original queue-time debit), so a crash between
     * {@link #doRetryFailedPayout}'s debit committing and its paired {@link #attemptGatewayPayout}
     * call resolving is INVISIBLE to {@link #reconcileOrphanedPendingPayout}'s STATUS_PENDING-only
     * scan — this method is that sweep's missing counterpart for the retry path specifically.
     *
     * <p>Recoverable with no new persisted column: {@link #buildRetryAttemptKey} is built entirely
     * from state already on the row ({@code id}, {@code status}, {@code updatedAt}), and none of
     * those change again until {@link #attemptGatewayPayout} itself resolves the attempt — so if a
     * crash happened before that resolution, this method recomputes the EXACT same key {@link
     * #doRetryFailedPayout} used and can find (and resume) the orphaned debit by it.
     */
    @Transactional
    public void reconcileFailedPayoutRetry(Payout payout) {
        if (!isFailureStatus(payout.getStatus())) {
            // Already progressed (the retry succeeded, or a later independent retry attempt is
            // itself in flight/resolved) — nothing stuck for THIS snapshot.
            return;
        }

        String retryAttemptKey = buildRetryAttemptKey(payout);
        String debitKey = retryDebitKey(retryAttemptKey);
        boolean debitPosted = walletTransactionRepository.findByIdempotencyKey(debitKey).isPresent();
        if (!debitPosted) {
            // No retry was ever actually attempted against this exact failure snapshot — nothing
            // orphaned; this row is simply awaiting (or not yet due for) a legitimate admin retry.
            return;
        }

        log.warn(
                "PayoutReconciliation: stuck retry debit found for payout {} (attemptKey={}) —"
                        + " resuming via the idempotent gateway/reversal keys",
                payout.getId(),
                retryAttemptKey);
        attemptGatewayPayout(
                payout, retryGatewayKey(retryAttemptKey), retryReversalKey(retryAttemptKey));
    }

    /**
     * [Red-team F1 fix, HIGH — lost creator money] The ledger idempotency key is keyed on the
     * unique-per-attempt {@code razorpayPayoutId}, NOT {@code payout.getId()}. The row id is
     * STABLE across every retry attempt a given {@link Payout} ever goes through, but each attempt
     * gets its own real RazorpayX id once {@link Payout#markGatewayConfirmed} runs — keying on the
     * row id meant a SECOND attempt's webhook-driven reversal (a fresh {@code razorpayPayoutId})
     * collided with the FIRST attempt's already-settled re-credit posting under the exact same
     * key, so {@code WalletLedgerService#post}'s idempotency dedup silently replayed the old
     * result instead of writing a new credit — the creator was never re-credited for the retry
     * attempt's own debit. Keying on {@code razorpayPayoutId} instead gives every distinct
     * RazorpayX attempt its own credit, while still deduping correctly on a genuine duplicate
     * webhook delivery for the SAME attempt (same id in, same key out).
     */
    private void reCreditReversedPayout(Payout payout, String razorpayPayoutId) {
        reCreditReversedPayout(payout, razorpayPayoutId, "payout-reversed:" + razorpayPayoutId);
    }

    private void reCreditReversedPayout(
            Payout payout, String razorpayPayoutId, String ledgerIdempotencyKey) {
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet creatorWallet = walletService.requireOrCreateUserWallet(payout.getCreatorUserId());

        ledgerService.post(
                clearingWallet.getId(),
                creatorWallet.getId(),
                payout.getAmount(),
                payout.getCurrency(),
                WalletTransactionType.PAYOUT,
                TxnReferenceType.MILESTONE,
                payout.getMilestoneId(),
                "Payout reversed by RazorpayX — re-credited to creator wallet",
                ledgerIdempotencyKey,
                razorpayPayoutId);
    }
}
