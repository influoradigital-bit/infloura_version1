package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Payout;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.payout.RazorpayFundAccountService;
import com.influora.web.dto.money.MoneyDtos.PayoutResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [SEC: PayoutStateMachine] Payout initiation for a RELEASED milestone. The internal ledger
 * release ({@code EscrowService.release}) has already moved the money out of escrow into the
 * creator's Influora wallet; this service is the OUT-OF-BAND step that pushes it to the
 * creator's real bank/UPI account via RazorpayX. A payout is only ever QUEUED here — it becomes
 * PROCESSED asynchronously via a RazorpayX webhook (not modeled in this slice; see
 * {@code RazorpayWebhookController} note), never synchronously in this call.
 *
 * <p>Amount is re-derived from {@link PaymentMilestone#getAmount()} — never accepted from the
 * caller (Guardrail 1).
 *
 * <p><b>Idempotency [E2 audit finding #9, CRITICAL — fixed]</b> — previously this method derived
 * {@code idempotencyKey = "payout:" + milestone.getId()} and passed it straight to {@link
 * RazorpayXClient#initiatePayout}, relying ENTIRELY on RazorpayX's own {@code
 * X-Payout-Idempotency}/{@code reference_id} dedup to stop a retried request from double-paying a
 * creator — no local guard existed at all, unlike every other money-moving endpoint on this
 * controller. This is now wrapped in the shared {@link IdempotencyService#executeOnce}
 * (insert-first-wins on V15's {@code idempotency_keys} table), the same mandated pattern {@link
 * com.influora.service.tracking.RedemptionService#redeem} uses: the deterministic key is checked
 * for a prior result BEFORE the gateway is ever called (via {@link
 * PaymentMilestoneRepository#findByIdempotencyKey}, reusing the column already written by {@link
 * EscrowService#release}/{@code refund} for the exact same replay-detection purpose — safe to
 * share because a milestone can only ever reach {@code queuePayout} after its escrow hold is
 * RELEASED, which is a terminal state for the release/refund state machine, so this method is the
 * only thing that will ever overwrite the key going forward). A concurrent double-submit is
 * arbitrated by {@code executeOnce}'s own {@code UNIQUE(idempotency_key)} insert, not by this
 * method's own check-then-act — the losing request never reaches {@code initiatePayout} a second
 * time; it re-queries the milestone (now updated by the winner) and returns the same response.
 *
 * <p><b>Validation moved out of {@code executeOnce} [SEC: Kabir, E2 HIGH-1 — fixed].</b> A prior
 * version of this fix ran every domain validation (milestone lookup, funded check, RELEASED
 * check, workspace-ownership check) AND the RazorpayX call all inside the {@code executeOnce}
 * supplier. Every one of those validations throws {@link ApiException} (a {@code RuntimeException}),
 * which {@code executeOnce} caught and marked the key FAILED — and FAILED used to be terminal, so
 * a single validation failure (e.g. a brand calling one step too early, before the milestone's
 * escrow was actually RELEASED) permanently wedged that milestone's payout with no in-app
 * recovery. All validation ({@link #validateForPayout}) now runs BEFORE {@code executeOnce} is
 * ever called, so a validation failure never reserves or fails the idempotency key at all — only a
 * genuine attempt to call RazorpayX does. Combined with {@link IdempotencyService}'s FAILED keys
 * now being re-runnable (see its class javadoc), a transient RazorpayX failure (timeout, 5xx) is
 * also no longer a permanent wedge: the very next legitimate call re-validates, reclaims the FAILED
 * key, and retries the gateway call.
 *
 * <p><b>Ownership before state [SEC: Kabir, E2 LOW-2 — fixed].</b> {@link #validateForPayout} now
 * checks the escrow hold's workspace ownership BEFORE checking whether it is RELEASED. Previously
 * the RELEASED check ran first, so a member of ANY workspace who learned another workspace's
 * {@code milestoneId} could distinguish "not released yet" from "not yours" by the differing error
 * code/message — a cross-tenant state oracle. Ownership is now the first thing checked after the
 * milestone/hold rows are loaded, so an unauthorized caller always sees {@code MILESTONE_NOT_FOUND}
 * regardless of the hold's actual state, and (per the fix above) this check running before {@code
 * executeOnce} also means a probing/enumerating caller can no longer poison another workspace's
 * idempotency key as a side effect of the probe.
 *
 * <p><b>NET-vs-GROSS payout amount [money-safety fix, live-payments blocker].</b> Previously this
 * class paid {@link PaymentMilestone#getAmount()} — the milestone's GROSS amount — to RazorpayX.
 * But {@code EscrowService#release} only ever credits the creator's Influora wallet the NET amount
 * (gross minus {@link PlatformFeeService}'s creator-side commission, via {@code
 * WalletLedgerService.post(..., ESCROW_RELEASE, ...)}). Since RazorpayX is a real bank/UPI payout,
 * paying gross would over-pay every creator by the platform's own commission the moment RazorpayX
 * goes live. The payout amount is now re-derived from the SAME {@link
 * com.influora.domain.entity.WalletTransaction} the release actually posted — {@link
 * PaymentMilestone#getReleasedTxnId()} points at the release's credit leg on the creator's wallet,
 * and that leg's {@code amount} column IS the net figure by construction (see {@code
 * EscrowService#releaseInternal}, {@code platformFeeService.deductAtRelease(...).netAmount()}).
 * This is never client-supplied and never re-computed independently (a second, drifting definition
 * of "net" would be its own bug) — it is read straight off the ledger row the release already wrote.
 *
 * <p><b>Double-pay guardrail [money-safety fix].</b> {@code EscrowService#release} credits the
 * creator's Influora wallet balance; that same balance is independently withdrawable via {@code
 * POST /wallet/withdraw} ({@code WalletService#requestCreatorWithdrawal}). Before this fix, {@code
 * doQueuePayout} never touched the wallet ledger at all — a milestone payout and a manual wallet
 * withdrawal could both legitimately fire for the exact same released funds, paying the creator
 * twice from two independent rails. This also confirms {@link PayoutReconciliationService}'s own
 * re-credit-on-reversal logic was already assuming a debit that never actually happened: it
 * re-credits the wallet with {@code WalletTransactionType.PAYOUT} when RazorpayX reports {@code
 * reversed}, which only makes sense if something had debited that same amount at queue time.
 * {@code doQueuePayout} now posts that missing debit — creator wallet to platform clearing wallet,
 * {@code WalletTransactionType.PAYOUT}, idempotency key {@code "payout-debit:" + milestoneId} —
 * BEFORE calling RazorpayX, inside the same {@code @Transactional} method as the gateway call. This
 * closes the hole in both directions: if the creator already withdrew the funds, the wallet balance
 * is short and {@code WalletLedgerService.post} throws {@code INSUFFICIENT_BALANCE} before RazorpayX
 * is ever called; if a payout is queued first, a subsequent {@code /wallet/withdraw} call sees the
 * now-reduced balance and is rejected the same way. On a RazorpayX {@code reversed} webhook, {@link
 * PayoutReconciliationService} puts the money back — now a real re-credit of a real debit, not a
 * credit with no matching debit ever written.
 *
 * <p><b>Orphaned-debit fix [P1, SEC: Kabir, landed-money-path audit 2b].</b> {@link
 * #doQueuePayout} is annotated {@code @Transactional}, but {@link #queuePayout} invokes it as a
 * lambda ({@code () -> doQueuePayout(...)}) on {@code this} -- a Spring self-invocation, which
 * bypasses the AOP proxy entirely, so that {@code @Transactional} is a NO-OP here. It does NOT
 * wrap the wallet debit and the RazorpayX call in one atomic unit; the debit above commits in
 * {@link WalletLedgerService#post}'s own (real, cross-bean) transaction the instant it returns. If
 * RazorpayX fails or the process crashes AFTER the debit commits but BEFORE a result is durably
 * recorded, the debit is orphaned -- the creator has been debited toward a payout that was never
 * confirmed sent. Making this method genuinely transactional would NOT be the fix: rolling back
 * the debit after RazorpayX's send has already partially succeeded would reintroduce the
 * double-pay/lost-money hole the debit-first ordering exists to avoid -- an external gateway call
 * is fundamentally not something a local transaction can safely wrap. The actual fix is a durable
 * intent record plus a background reconciler: a {@link Payout} row is now persisted with {@link
 * Payout#STATUS_PENDING} BEFORE the debit and BEFORE the RazorpayX call (created fresh, or reused
 * by {@code idempotencyKey} on a reclaimed retry). A separate scheduled sweep ({@code
 * PayoutOrphanedDebitSweepJob}) finds rows still PENDING past a grace period and hands them to
 * {@link PayoutReconciliationService#reconcileOrphanedPendingPayout}, which retries the (idempotent)
 * gateway call or, if that itself fails, reverses the debit via the same re-credit path already
 * used for a genuine RazorpayX {@code reversed} webhook.
 */
@Service
public class PayoutService {

    private static final String IDEMPOTENCY_SCOPE = "payout.queue";

    private final PaymentMilestoneRepository milestoneRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final CollaborationRepository collaborationRepository;
    private final PayoutRepository payoutRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorBankAccountRepository creatorBankAccountRepository;
    private final RazorpayXClient razorpayXClient;
    private final RazorpayFundAccountService fundAccountService;
    private final BrandContextService brandContext;
    private final IdempotencyService idempotencyService;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletLedgerService walletLedgerService;
    private final PlatformWalletService platformWalletService;
    private final WalletService walletService;

    public PayoutService(
            PaymentMilestoneRepository milestoneRepository,
            EscrowHoldRepository escrowHoldRepository,
            CollaborationRepository collaborationRepository,
            PayoutRepository payoutRepository,
            CreatorProfileRepository creatorProfileRepository,
            CreatorBankAccountRepository creatorBankAccountRepository,
            RazorpayXClient razorpayXClient,
            RazorpayFundAccountService fundAccountService,
            BrandContextService brandContext,
            IdempotencyService idempotencyService,
            WalletTransactionRepository walletTransactionRepository,
            WalletLedgerService walletLedgerService,
            PlatformWalletService platformWalletService,
            WalletService walletService) {
        this.milestoneRepository = milestoneRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.collaborationRepository = collaborationRepository;
        this.payoutRepository = payoutRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorBankAccountRepository = creatorBankAccountRepository;
        this.razorpayXClient = razorpayXClient;
        this.fundAccountService = fundAccountService;
        this.brandContext = brandContext;
        this.idempotencyService = idempotencyService;
        this.walletTransactionRepository = walletTransactionRepository;
        this.walletLedgerService = walletLedgerService;
        this.platformWalletService = platformWalletService;
        this.walletService = walletService;
    }

    public PayoutResponse queuePayout(AuthPrincipal principal, String workspaceId, String milestoneId) {
        // [L-1] OWNER/ADMIN only — this triggers a real RazorpayX bank payout, the same
        // sensitivity as EscrowService#initiateFund's role gate, not a plain member action.
        var member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);

        String idempotencyKey = "payout:" + milestoneId;

        // [SEC: Kabir] Replay check FIRST — before any gateway call. A milestone whose
        // idempotencyKey already equals this payout's derived key has already had a payout
        // queued for it; return that same outcome rather than re-hitting RazorpayX.
        PayoutResponse replay = replayIfPresent(workspaceId, milestoneId, idempotencyKey);
        if (replay != null) {
            return replay;
        }

        // [SEC: Kabir, E2 HIGH-1/LOW-2 — fixed] ALL domain validation runs here, BEFORE
        // executeOnce reserves the idempotency key. A validation failure (milestone not found,
        // not funded, not released, wrong workspace) now throws straight out of queuePayout
        // without ever touching idempotency_keys — it can never reserve-then-FAIL a key, so it
        // can never wedge a later legitimate attempt. See class javadoc.
        PayoutContext ctx = validateForPayout(workspaceId, milestoneId);

        try {
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    workspaceId,
                    IDEMPOTENCY_SCOPE,
                    () -> doQueuePayout(ctx, idempotencyKey));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            // Lost the insert-first race to a concurrent caller with the same key — the winner's
            // milestone row is now updated (or about to be); replay it instead of a bare 500 or
            // silently re-calling RazorpayX a second time.
            PayoutResponse won = replayIfPresent(workspaceId, milestoneId, idempotencyKey);
            if (won != null) {
                return won;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This payout is already being processed — retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Result of {@link #validateForPayout} — the milestone and collaboration rows it already
     * loaded and validated, threaded into {@link #doQueuePayout} so that method never needs to
     * re-run (or duplicate) any check once inside {@code executeOnce}. {@code netAmount} is the
     * server-derived NET figure the creator's wallet was actually credited at release (see class
     * javadoc) — the amount this payout must pay, never {@link PaymentMilestone#getAmount()}
     * (gross).
     */
    private record PayoutContext(
            PaymentMilestone milestone, Collaboration collaboration, BigDecimal netAmount) {}

    /**
     * Loads the release-ledger credit leg {@link PaymentMilestone#getReleasedTxnId()} points at —
     * the exact figure {@code EscrowService#release} credited to the creator's Influora wallet —
     * and asserts it actually belongs to THIS milestone before returning it.
     *
     * <p><b>[P2, SEC: Kabir, landed-money-path audit 1c].</b> Previously this did a bare {@code
     * findById(releasedTxnId)} with no assertion that the loaded row's {@code referenceId} equals
     * this milestone's id. {@code releasedTxnId} is server-set (never client-supplied) so this was
     * not API-exploitable, but it was also not defended against DB-level tampering/corruption
     * redirecting a milestone's pointer at a different milestone's release credit. This binding
     * assert is defense-in-depth: fails closed with {@code MILESTONE_RELEASE_LEDGER_MISMATCH}
     * rather than silently paying out an amount that was actually released for someone else's
     * milestone.
     */
    private WalletTransaction loadReleaseCredit(PaymentMilestone milestone) {
        String releasedTxnId = milestone.getReleasedTxnId();
        if (releasedTxnId == null) {
            throw new ApiException(
                    "MILESTONE_RELEASE_LEDGER_MISSING",
                    "Milestone release ledger entry not found — cannot determine net payout amount",
                    HttpStatus.CONFLICT);
        }
        WalletTransaction releaseCredit =
                walletTransactionRepository
                        .findById(releasedTxnId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MILESTONE_RELEASE_LEDGER_MISSING",
                                                "Milestone release ledger entry not found — cannot determine net"
                                                        + " payout amount",
                                                HttpStatus.CONFLICT));
        if (releaseCredit.getReferenceType() != TxnReferenceType.MILESTONE
                || !milestone.getId().equals(releaseCredit.getReferenceId())) {
            throw new ApiException(
                    "MILESTONE_RELEASE_LEDGER_MISMATCH",
                    "Milestone release ledger entry does not belong to this milestone",
                    HttpStatus.CONFLICT);
        }
        return releaseCredit;
    }

    /**
     * Re-derives the NET amount a payout for this (already-RELEASED) milestone must pay — the
     * exact figure {@code EscrowService#release} credited to the creator's Influora wallet, read
     * straight off that release's own ledger row rather than recomputed. That leg's {@code amount}
     * column IS {@code platformFeeService.deductAtRelease(...).netAmount()} by construction — gross
     * minus the creator-side platform commission. Only the milestone-binding assert from {@link
     * #loadReleaseCredit} applies here (no creator-wallet check) — used by {@link
     * #replayIfPresent}, which does not have a resolved {@link Collaboration}/creator id on hand;
     * the fuller creator-wallet binding check lives in {@link #validateForPayout}, the only path
     * that actually authorizes money movement.
     */
    private BigDecimal resolveNetPayoutAmount(PaymentMilestone milestone) {
        return loadReleaseCredit(milestone).getAmount();
    }

    /**
     * All domain validation for {@link #queuePayout}, run BEFORE {@code executeOnce} reserves the
     * idempotency key (see class javadoc, E2 HIGH-1). Ownership is checked before the RELEASED
     * check (E2 LOW-2) so an unauthorized caller always sees {@code MILESTONE_NOT_FOUND} instead of
     * an oracle that leaks the hold's real state.
     */
    private PayoutContext validateForPayout(String workspaceId, String milestoneId) {
        PaymentMilestone milestone =
                milestoneRepository
                        .findById(milestoneId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND));

        if (milestone.getEscrowHoldId() == null) {
            throw new ApiException(
                    "MILESTONE_NOT_RELEASED", "Milestone has not been funded", HttpStatus.CONFLICT);
        }
        EscrowHold hold =
                escrowHoldRepository
                        .findById(milestone.getEscrowHoldId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));

        // [SEC: Kabir, E2 LOW-2] Ownership BEFORE state — never let a caller distinguish
        // "not yours" from "not released yet" for a milestone in a workspace they aren't a member
        // of.
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND);
        }
        if (hold.getStatus() != EscrowStatus.RELEASED) {
            throw new ApiException(
                    "MILESTONE_NOT_RELEASED",
                    "Payout can only be queued after the milestone's escrow is RELEASED",
                    HttpStatus.CONFLICT);
        }

        Collaboration collaboration =
                collaborationRepository
                        .findById(milestone.getCollaborationId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));

        // [P2, SEC: Kabir, landed-money-path audit 1c] Full binding assert -- not just that the
        // release credit belongs to THIS milestone (loadReleaseCredit already checks that) but that
        // it landed on THIS milestone's creator's wallet, not some other user's. This is the only
        // path that actually authorizes a RazorpayX call, so it gets the strongest check.
        WalletTransaction releaseCredit = loadReleaseCredit(milestone);
        Wallet creatorWallet = walletService.requireOrCreateUserWallet(collaboration.getCreatorId());
        if (!creatorWallet.getId().equals(releaseCredit.getWalletId())) {
            throw new ApiException(
                    "MILESTONE_RELEASE_LEDGER_MISMATCH",
                    "Milestone release ledger entry does not belong to this milestone's creator wallet",
                    HttpStatus.CONFLICT);
        }
        BigDecimal netAmount = releaseCredit.getAmount();

        return new PayoutContext(milestone, collaboration, netAmount);
    }

    /**
     * Returns the prior response if a payout was already queued for this milestone (its {@code
     * idempotencyKey} column already equals the derived payout key), else {@code null}. Re-derives
     * the response from persisted milestone state — no separate {@code payouts} table exists in
     * this slice (see {@link #confirmExecuted} javadoc), so the RazorpayX-returned {@code payoutId}
     * itself is not durably stored; a replayed call reports {@code "queued"} status rather than a
     * fabricated fresh gateway id, since the milestone row is the only source of truth this method
     * can safely re-read.
     */
    private PayoutResponse replayIfPresent(String workspaceId, String milestoneId, String idempotencyKey) {
        PaymentMilestone milestone = milestoneRepository.findById(milestoneId).orElse(null);
        if (milestone == null) {
            return null;
        }
        if (!idempotencyKey.equals(milestone.getIdempotencyKey())) {
            return null;
        }
        EscrowHold hold =
                milestone.getEscrowHoldId() == null
                        ? null
                        : escrowHoldRepository.findById(milestone.getEscrowHoldId()).orElse(null);
        if (hold != null && !hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND);
        }
        // Replay reports the same NET amount the original queue call paid — never gross — so a
        // retried caller sees exactly what was (or will be) actually sent to the creator's bank.
        BigDecimal netAmount = resolveNetPayoutAmount(milestone);
        return new PayoutResponse(
                idempotencyKey, milestone.getId(), netAmount, milestone.getCurrency(), "queued");
    }

    /**
     * Runs ONLY inside {@code executeOnce} now — no validation left in here (see class javadoc,
     * E2 HIGH-1). {@code ctx} was already validated (milestone funded, escrow RELEASED, workspace
     * ownership) by {@link #validateForPayout} before the idempotency key was ever reserved; the
     * only things that can throw from this point on are the RazorpayX call itself and the final
     * persistence, both of which SHOULD mark the key FAILED (and be retryable) on failure.
     *
     * <p><b>{@code @Transactional} here is a NO-OP [SEC: Kabir, landed-money-path audit 2b] —
     * do not rely on it for atomicity.</b> {@link #queuePayout} calls this method as a lambda
     * ({@code () -> doQueuePayout(...)}) on {@code this}, a Spring self-invocation that bypasses
     * the AOP transaction proxy entirely. The wallet debit below commits in its own, genuinely
     * transactional call ({@link WalletLedgerService#post}) the instant it returns — it is NOT
     * rolled back if the RazorpayX call that follows fails or the process crashes. That is
     * intentional, not a bug to "fix" by wrapping this method in a real transaction: doing so
     * would roll back the debit even after RazorpayX's send had already partially succeeded,
     * reintroducing the double-pay/lost-money hole this debit-first ordering exists to avoid — an
     * external, non-transactional gateway call cannot be safely wrapped in a local DB transaction
     * either way. The safety net for the window between the debit committing and the gateway
     * result being durably recorded is the {@link Payout#STATUS_PENDING} row persisted below
     * BEFORE either of those steps, swept by {@code PayoutOrphanedDebitSweepJob} /
     * {@link PayoutReconciliationService#reconcileOrphanedPendingPayout}.
     */
    @Transactional
    protected PayoutResponse doQueuePayout(PayoutContext ctx, String idempotencyKey) {
        PaymentMilestone milestone = ctx.milestone();
        Collaboration collaboration = ctx.collaboration();
        BigDecimal netAmount = ctx.netAmount();
        String creatorUserId = collaboration.getCreatorId();

        // [B7/C-5] Resolves a REAL RazorpayX fund account off the creator's on-file primary bank
        // account (previously the creator's raw user id was passed straight to RazorpayX as if it
        // were a fund account id).
        CreatorBankAccount bankAccount =
                creatorBankAccountRepository
                        .findByCreatorUserIdAndPrimaryTrue(creatorUserId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "BANK_ACCOUNT_NOT_FOUND",
                                                "Creator has no primary bank/UPI account on file for payout",
                                                HttpStatus.CONFLICT));
        String fundAccountId = fundAccountService.resolveFundAccountId(creatorUserId, bankAccount.getId());

        // [P1 fix, SEC: Kabir, landed-money-path audit 2b] Persist the durable payout-intent row
        // BEFORE the wallet debit and BEFORE the RazorpayX call — the whole point of the fix (see
        // this method's javadoc and Payout#STATUS_PENDING). Looked up by idempotencyKey first: on
        // a reclaimed FAILED-key retry (a prior attempt got this far or further before failing/
        // crashing) the SAME row is reused rather than inserting a second one, which would violate
        // the column's UNIQUE constraint and would also be the wrong behavior — the row from the
        // first attempt is exactly the record PayoutOrphanedDebitSweepJob needs to have seen.
        Payout payout = payoutRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (payout == null) {
            payout =
                    Payout.createPending(
                            Ulids.newUlid(),
                            milestone.getId(),
                            creatorUserId,
                            fundAccountId,
                            netAmount,
                            milestone.getCurrency(),
                            idempotencyKey,
                            Instant.now());
            payoutRepository.save(payout);
        }

        // [Money-safety fix, double-pay guardrail — see class javadoc] Debit the creator's wallet
        // for the SAME net amount BEFORE ever calling RazorpayX. This is the missing counterpart to
        // EscrowService#release's credit: without it, the released funds stay withdrawable via POST
        // /wallet/withdraw at the same time a real bank payout is in flight for them.
        // WalletLedgerService.post enforces the wallet's balance can't go negative, so if the
        // creator already withdrew this money (or a payout for it was already debited under a
        // different flow) this throws INSUFFICIENT_BALANCE here — before RazorpayX is ever called —
        // rather than silently paying out twice. Distinct idempotency key ("payout-debit:") from
        // the queuePayout key itself, scoped to this milestone. NOTE: this commits in its own real
        // transaction regardless of this method's no-op @Transactional (see javadoc above) — that
        // is exactly why the PENDING row above must be written first.
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet creatorWallet = walletService.requireOrCreateUserWallet(creatorUserId);
        String debitIdempotencyKey = "payout-debit:" + milestone.getId();
        walletLedgerService.post(
                creatorWallet.getId(),
                clearingWallet.getId(),
                netAmount,
                milestone.getCurrency(),
                WalletTransactionType.PAYOUT,
                TxnReferenceType.MILESTONE,
                milestone.getId(),
                "Payout queued to bank/UPI for milestone " + milestone.getId(),
                debitIdempotencyKey,
                null);

        // [Money-safety fix — see class javadoc] NET amount, never milestone.getAmount() (gross).
        var gatewayResult =
                razorpayXClient.initiatePayout(
                        fundAccountId, netAmount, milestone.getCurrency(), idempotencyKey);

        // [P1 fix] The gateway call returned — transition the PENDING row to its real RazorpayX
        // identity/status. If the process dies between the debit above and this line, the row is
        // left PENDING with the debit already posted: exactly the orphaned-debit window
        // PayoutOrphanedDebitSweepJob exists to sweep.
        payout.markGatewayConfirmed(gatewayResult.payoutId(), gatewayResult.status());
        payoutRepository.save(payout);

        // Mark this milestone as having had a payout queued under this key — the local replay
        // guard for every subsequent call (see replayIfPresent). Reuses the same column
        // EscrowService.release/refund write their own derived keys into; safe here because a
        // milestone only reaches queuePayout after RELEASED, a terminal state for that pair.
        milestone.markPayoutQueued(idempotencyKey);
        milestoneRepository.save(milestone);

        return new PayoutResponse(
                gatewayResult.payoutId(),
                milestone.getId(),
                netAmount,
                milestone.getCurrency(),
                gatewayResult.status());
    }
}
