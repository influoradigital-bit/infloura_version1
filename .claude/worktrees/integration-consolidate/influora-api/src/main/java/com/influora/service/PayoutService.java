package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Payout;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorBankAccountRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.PayoutRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.payout.RazorpayFundAccountService;
import com.influora.web.dto.money.MoneyDtos.PayoutResponse;
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
            IdempotencyService idempotencyService) {
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
     * re-run (or duplicate) any check once inside {@code executeOnce}.
     */
    private record PayoutContext(PaymentMilestone milestone, Collaboration collaboration) {}

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

        return new PayoutContext(milestone, collaboration);
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
        return new PayoutResponse(
                idempotencyKey, milestone.getId(), milestone.getAmount(), milestone.getCurrency(), "queued");
    }

    /**
     * Runs ONLY inside {@code executeOnce} now — no validation left in here (see class javadoc,
     * E2 HIGH-1). {@code ctx} was already validated (milestone funded, escrow RELEASED, workspace
     * ownership) by {@link #validateForPayout} before the idempotency key was ever reserved; the
     * only things that can throw from this point on are the RazorpayX call itself and the final
     * persistence, both of which SHOULD mark the key FAILED (and be retryable) on failure.
     */
    @Transactional
    protected PayoutResponse doQueuePayout(PayoutContext ctx, String idempotencyKey) {
        PaymentMilestone milestone = ctx.milestone();
        Collaboration collaboration = ctx.collaboration();
        String creatorUserId = collaboration.getCreatorId();

        // [B7/C-5] Resolves a REAL RazorpayX fund account off the creator's on-file primary bank
        // account (previously the creator's raw user id was passed straight to RazorpayX as if it
        // were a fund account id) and persists a Payout row keyed on the RazorpayX payout id — the
        // durable record PayoutReconciliationService updates from the payout.processed/
        // payout.reversed webhook.
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

        var payout =
                razorpayXClient.initiatePayout(
                        fundAccountId, milestone.getAmount(), milestone.getCurrency(), idempotencyKey);

        payoutRepository.save(
                Payout.createQueued(
                        Ulids.newUlid(),
                        milestone.getId(),
                        creatorUserId,
                        payout.payoutId(),
                        fundAccountId,
                        milestone.getAmount(),
                        milestone.getCurrency(),
                        payout.status(),
                        idempotencyKey,
                        Instant.now()));

        // Mark this milestone as having had a payout queued under this key — the local replay
        // guard for every subsequent call (see replayIfPresent). Reuses the same column
        // EscrowService.release/refund write their own derived keys into; safe here because a
        // milestone only reaches queuePayout after RELEASED, a terminal state for that pair.
        milestone.markPayoutQueued(idempotencyKey);
        milestoneRepository.save(milestone);

        return new PayoutResponse(
                payout.payoutId(), milestone.getId(), milestone.getAmount(), milestone.getCurrency(), payout.status());
    }
}
