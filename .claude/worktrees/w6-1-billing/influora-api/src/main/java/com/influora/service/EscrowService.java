package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DisputeStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.ReleaseCondition;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.EscrowFundedEvent;
import com.influora.service.notification.event.PayoutReleasedEvent;
import com.influora.web.dto.money.MoneyDtos.EscrowFundResponse;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escrow fund / release / refund. Every money movement is posted through
 * {@link WalletLedgerService#post} — this service never mutates a {@link Wallet} balance
 * directly (Guardrail 1, C-3).
 *
 * <p>[SEC: MF-1 / Guardrail 1] {@code fund(...)} takes no client-supplied amount. The amount is
 * re-derived here from the persisted {@link Campaign} budget or, when a milestone is named, from
 * the {@link PaymentMilestone#getAmount()} row — the sole server-authoritative source.
 *
 * <p>[SEC: EscrowStateMachine] Legal transitions: {@code PENDING -> FUNDED -> RELEASED},
 * {@code FUNDED -> REFUNDED}, and an operator-only {@code -> FROZEN}. Enforced by the guard
 * methods below; no caller can jump straight to RELEASED/REFUNDED without having first FUNDED.
 */
@Service
public class EscrowService {

    private static final Logger log = LoggerFactory.getLogger(EscrowService.class);

    private static final Set<DisputeStatus> ACTIVE_DISPUTE_STATUSES =
            EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    private final EscrowHoldRepository escrowHoldRepository;
    private final PaymentMilestoneRepository milestoneRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final DisputeRepository disputeRepository;
    private final WalletLedgerService ledgerService;
    private final PlatformWalletService platformWalletService;
    private final PlatformFeeService platformFeeService;
    private final WalletService walletService;
    private final BrandContextService brandContext;
    private final CreatorContextService creatorContext;
    private final RazorpayClient razorpayClient;
    private final CampaignServiceInvoiceService campaignServiceInvoiceService;
    private final DeliverableRepository deliverableRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CollaborationLifecycleService collaborationLifecycleService;

    public EscrowService(
            EscrowHoldRepository escrowHoldRepository,
            PaymentMilestoneRepository milestoneRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            DisputeRepository disputeRepository,
            WalletLedgerService ledgerService,
            PlatformWalletService platformWalletService,
            PlatformFeeService platformFeeService,
            WalletService walletService,
            BrandContextService brandContext,
            CreatorContextService creatorContext,
            RazorpayClient razorpayClient,
            CampaignServiceInvoiceService campaignServiceInvoiceService,
            DeliverableRepository deliverableRepository,
            WorkspaceRepository workspaceRepository,
            ApplicationEventPublisher eventPublisher,
            CollaborationLifecycleService collaborationLifecycleService) {
        this.escrowHoldRepository = escrowHoldRepository;
        this.milestoneRepository = milestoneRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.disputeRepository = disputeRepository;
        this.ledgerService = ledgerService;
        this.platformWalletService = platformWalletService;
        this.platformFeeService = platformFeeService;
        this.walletService = walletService;
        this.brandContext = brandContext;
        this.creatorContext = creatorContext;
        this.razorpayClient = razorpayClient;
        this.campaignServiceInvoiceService = campaignServiceInvoiceService;
        this.deliverableRepository = deliverableRepository;
        this.workspaceRepository = workspaceRepository;
        this.eventPublisher = eventPublisher;
        this.collaborationLifecycleService = collaborationLifecycleService;
    }

    /**
     * Creates (or replays, if {@code idempotencyKey} was already used) a PENDING escrow hold and
     * a Razorpay order for the human to confirm. The hold only becomes FUNDED once
     * {@code confirmFunded} runs against a verified webhook — never here.
     *
     * @param amount ALWAYS server-derived by the caller (controller) before this method is
     *     invoked — see {@code deriveFundAmount}. This method re-validates it is positive but the
     *     re-derivation contract belongs to the caller, same discipline as
     *     {@code WalletLedgerService.post}.
     */
    @Transactional
    public EscrowFundResponse initiateFund(
            AuthPrincipal principal,
            String workspaceId,
            String campaignId,
            String milestoneId,
            BigDecimal amount,
            String currency,
            String idempotencyKey) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);

        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(
                    "INVALID_ESCROW_AMOUNT", "Escrow amount must be positive", HttpStatus.BAD_REQUEST);
        }

        var existing = escrowHoldRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            EscrowHold hold = existing.get();
            if (!hold.getWorkspaceId().equals(workspaceId)) {
                // Never replay another workspace's hold for a colliding key (mirrors
                // WalletTopUpService#initiateTopUp's same check).
                throw new ApiException(
                        "IDEMPOTENCY_KEY_CONFLICT",
                        "Idempotency-Key was already used for a different workspace",
                        HttpStatus.CONFLICT);
            }
            return new EscrowFundResponse(
                    hold.getId(), hold.getAmount(), hold.getCurrency(), null, hold.getStatus());
        }

        Wallet wallet = walletService.requireWorkspaceWallet(workspaceId);
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new ApiException(
                    "INSUFFICIENT_FUNDS", "Wallet balance is insufficient for this escrow amount", HttpStatus.PAYMENT_REQUIRED);
        }

        EscrowHold hold =
                EscrowHold.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .campaignId(campaignId)
                        .milestoneId(milestoneId)
                        .amount(amount)
                        .currency(currency)
                        .status(EscrowStatus.PENDING)
                        .idempotencyKey(idempotencyKey)
                        .build();
        escrowHoldRepository.save(hold);

        var order = razorpayClient.createOrder(amount, currency, hold.getId());

        return new EscrowFundResponse(hold.getId(), amount, currency, order.orderId(), hold.getStatus());
    }

    /**
     * Re-derives the authoritative fund amount server-side. If {@code milestoneId} is supplied,
     * the milestone's persisted {@code amount} is authoritative; otherwise the whole campaign's
     * budget max is used as the pool amount. AI/client-proposed amounts are never consulted here
     * (Guardrail 1 / MF-1) — this is the one legal place a fund amount is computed.
     */
    @Transactional(readOnly = true)
    public BigDecimal deriveFundAmount(String workspaceId, String campaignId, String milestoneId) {
        if (milestoneId != null && !milestoneId.isBlank()) {
            // [SEC: Wave-1 S5] Workspace-scoped, same as the campaign branch below — an unscoped
            // findById would let any authenticated caller pass another workspace's milestoneId and
            // learn its amount/existence (cross-tenant info disclosure). Must 404, not just filter
            // silently, so behavior matches "milestone truly does not exist" from the caller's POV.
            PaymentMilestone milestone =
                    milestoneRepository
                            .findByIdAndWorkspaceId(milestoneId, workspaceId)
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    "MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND));
            return milestone.getAmount();
        }
        Campaign campaign =
                campaignRepository
                        .findByIdAndWorkspaceId(campaignId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CAMPAIGN_NOT_FOUND", "Campaign not found", HttpStatus.NOT_FOUND));
        if (campaign.getBudgetMax() == null) {
            throw new ApiException(
                    "CAMPAIGN_BUDGET_MISSING",
                    "Campaign has no budget set — cannot derive an escrow amount",
                    HttpStatus.CONFLICT);
        }
        return campaign.getBudgetMax();
    }

    /**
     * Confirms funding on a verified Razorpay webhook (never on the client's order-creation
     * response). Moves money via the ledger: DEBIT brand wallet, CREDIT platform clearing wallet.
     * Idempotent — a duplicate webhook delivery replays the same result via the ledger's
     * idempotency key, never double-charges (uq_wtx_idem / uq_escrow_idem).
     *
     * <p>[SEC: amount cross-check] {@code webhookAmountInPaise}/{@code webhookCurrency} are the
     * values Razorpay actually reports as captured in the webhook payload
     * ({@code payload.payment.entity.amount}/{@code currency}). This method never trusts the
     * pre-existing {@link EscrowHold} row in isolation — it re-validates that what Razorpay says
     * was captured matches what we expected to be funded before flipping status to FUNDED. A
     * mismatch (wrong amount, wrong currency, or a webhook that carries no amount at all) aborts
     * the transition and requires manual review; it is never silently accepted.
     */
    @Transactional
    public EscrowHold confirmFunded(
            String escrowHoldId, String gatewayRef, Long webhookAmountInPaise, String webhookCurrency) {
        EscrowHold hold = requireHold(escrowHoldId);
        if (hold.getStatus() == EscrowStatus.FUNDED) {
            return hold; // already applied — idempotent no-op
        }
        requireStatus(hold, EscrowStatus.PENDING, "fund");

        validateWebhookAmount(hold, webhookAmountInPaise, webhookCurrency);

        Wallet brandWallet = walletService.requireWorkspaceWallet(hold.getWorkspaceId());
        Wallet clearingWallet = platformWalletService.requireClearingWallet();

        // [SEC: Kabir OWASP CRITICAL] The ledger's own idempotency key must be derived from this
        // hold's server-generated id, never the raw client `Idempotency-Key` header
        // (hold.getIdempotencyKey()) — that header is shared, unscoped input and a client reusing
        // it across a top-up and an escrow-fund call would make WalletLedgerService#post's replay
        // short-circuit return the wrong movement's rows. Same "<feature>:<id>" convention as
        // release/refund below.
        String ledgerIdempotencyKey = "escrow-fund:" + hold.getId();
        var posting =
                ledgerService.post(
                        brandWallet.getId(),
                        clearingWallet.getId(),
                        hold.getAmount(),
                        hold.getCurrency(),
                        WalletTransactionType.ESCROW_HOLD,
                        TxnReferenceType.ESCROW_HOLD,
                        hold.getId(),
                        "Escrow fund for campaign " + hold.getCampaignId(),
                        ledgerIdempotencyKey,
                        gatewayRef);

        hold.markFunded(posting.debitLeg().getId());
        escrowHoldRepository.save(hold);

        if (hold.getMilestoneId() != null) {
            milestoneRepository
                    .findById(hold.getMilestoneId())
                    .ifPresent(
                            milestone -> {
                                milestone.markFunded(hold.getId());
                                milestoneRepository.save(milestone);
                            });
        }

        // W2-1 — escrow is funded; work can begin. Not gated on a Meera/AI credit-reset hook (a
        // separate, unrelated concern out of scope here per the prior task boundary — do not touch
        // service/meera or AI entities) — this only advances Collaboration.status and publishes the
        // #5 "campaign is live" notification (07-NOTIFICATION-SYSTEM-SPEC.md §3.1), previously
        // modeled with a listener already wired but never published anywhere (grep 0 refs at a call
        // site, same shape of gap as PayoutReleasedEvent before B3).
        try {
            notifyEscrowFunded(hold);
        } catch (RuntimeException e) {
            log.error(
                    "Failed to publish EscrowFundedEvent / advance collaboration status for escrow hold"
                            + " {} — funding itself already succeeded",
                    hold.getId(),
                    e);
        }

        return hold;
    }

    private void notifyEscrowFunded(EscrowHold hold) {
        if (hold.getCollaborationId() == null) {
            return;
        }
        Collaboration collaboration =
                collaborationRepository.findById(hold.getCollaborationId()).orElse(null);
        if (collaboration == null) {
            return;
        }
        collaborationLifecycleService.onEscrowFunded(collaboration.getId());

        String campaignTitle =
                campaignRepository.findById(hold.getCampaignId()).map(Campaign::getTitle).orElse("your campaign");
        String brandName =
                workspaceRepository.findById(hold.getWorkspaceId()).map(Workspace::getName).orElse("The brand");
        eventPublisher.publishEvent(
                new EscrowFundedEvent(
                        collaboration.getCreatorId(), hold.getWorkspaceId(), hold.getId(), brandName, campaignTitle));
    }

    /**
     * Releases a FUNDED escrow hold to the creator behind the given milestone's collaboration.
     * The payee is always resolved server-side from {@code Collaboration.creatorId} — never
     * accepted from the request (a caller cannot redirect a release to an arbitrary user id).
     */
    @Transactional
    public EscrowStatusResponse release(AuthPrincipal principal, String workspaceId, String milestoneId) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);
        return releaseInternal(workspaceId, milestoneId);
    }

    /**
     * [B3] Best-effort release attempt invoked from {@code BrandDeliverableService#approve}'s own
     * transaction, right after a deliverable is approved. Runs the exact same release logic/gates
     * as {@link #release} — {@link #assertReleaseConditionSatisfied} (B5), the dispute block, the
     * FUNDED requirement, the idempotent no-op if already RELEASED — but WITHOUT the brand
     * OWNER/ADMIN role gate: the caller has already been authorized for this workspace by {@code
     * BrandContextService#requireBrandWorkspace} at the top of {@code approve()}, and the resulting
     * release is a system-triggered consequence of that approval, not a separate user-initiated
     * release request (a plain workspace member who can review deliverables must not be blocked
     * from triggering the release their own approval unlocks).
     *
     * <p>"Not yet eligible" outcomes — no milestone linked, milestone not yet funded, {@code
     * release_condition} not yet satisfied, blocked by an active dispute, milestone/hold not found
     * — are expected/normal here (e.g. a brand may approve creative before escrow is even funded)
     * and return {@code false} rather than throwing, so {@code approve()} still succeeds. Any OTHER
     * failure (an unexpected wallet/ledger error) propagates so the whole {@code approve()}
     * transaction rolls back rather than silently leaving an approved-but-partially-released state.
     *
     * @return {@code true} if a release actually happened (or had already happened — idempotent)
     */
    @Transactional
    public boolean tryReleaseOnApproval(String workspaceId, String milestoneId) {
        if (milestoneId == null || milestoneId.isBlank()) {
            return false;
        }
        var maybeMilestone = milestoneRepository.findById(milestoneId);
        if (maybeMilestone.isEmpty() || maybeMilestone.get().getEscrowHoldId() == null) {
            return false; // no milestone, or not funded yet — nothing to release
        }
        try {
            releaseInternal(workspaceId, milestoneId);
            return true;
        } catch (ApiException e) {
            if (isExpectedReleaseSkip(e.getCode())) {
                log.info(
                        "tryReleaseOnApproval: release not (yet) eligible for milestone {} in workspace"
                                + " {} ({}) — approval proceeds without releasing escrow",
                        milestoneId,
                        workspaceId,
                        e.getCode());
                return false;
            }
            throw e;
        }
    }

    private static boolean isExpectedReleaseSkip(String code) {
        return switch (code) {
            case "MILESTONE_NOT_FUNDED",
                    "MILESTONE_NOT_FOUND",
                    "INVALID_ESCROW_STATE",
                    "RELEASE_CONDITION_NOT_MET",
                    "ESCROW_BLOCKED_BY_DISPUTE",
                    "ESCROW_NOT_FOUND",
                    "COLLABORATION_NOT_FOUND" -> true;
            default -> false;
        };
    }

    private EscrowStatusResponse releaseInternal(String workspaceId, String milestoneId) {
        PaymentMilestone milestone =
                milestoneRepository
                        .findById(milestoneId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND));
        if (milestone.getEscrowHoldId() == null) {
            throw new ApiException(
                    "MILESTONE_NOT_FUNDED", "Milestone has no funded escrow hold", HttpStatus.CONFLICT);
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
        String payeeUserId = collaboration.getCreatorId();

        assertEscrowNotBlockedByDispute(collaboration);

        EscrowHold hold = requireHoldForUpdate(milestone.getEscrowHoldId());
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND);
        }
        if (hold.getStatus() == EscrowStatus.RELEASED) {
            return toStatusResponse(hold); // idempotent no-op
        }
        requireStatus(hold, EscrowStatus.FUNDED, "release");

        // [B5] release_condition gate — must fail BEFORE any money moves. A hold that is FUNDED but
        // whose milestone's release_condition isn't yet satisfied by the linked deliverable(s) is
        // legitimately not releasable yet; this must never be bypassed by calling release() instead
        // of the (nonexistent, by design) more-specific method.
        assertReleaseConditionSatisfied(milestone);

        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet payeeWallet = walletService.requireOrCreateUserWallet(payeeUserId);

        var feeDeduction =
                platformFeeService.deductAtRelease(
                        clearingWallet,
                        milestone.getId(),
                        payeeUserId,
                        hold.getAmount(),
                        hold.getCurrency(),
                        hold.getId());

        String idempotencyKey = "release:" + hold.getId();
        var posting =
                ledgerService.post(
                        clearingWallet.getId(),
                        payeeWallet.getId(),
                        feeDeduction.netAmount(),
                        hold.getCurrency(),
                        WalletTransactionType.ESCROW_RELEASE,
                        TxnReferenceType.MILESTONE,
                        milestone.getId(),
                        "Milestone release for contract " + milestone.getContractId(),
                        idempotencyKey,
                        null);

        hold.markReleased(posting.creditLeg().getId());
        escrowHoldRepository.save(hold);
        milestone.markReleased(posting.creditLeg().getId(), idempotencyKey);
        milestoneRepository.save(milestone);

        // D14 Doc#2 — creator service invoice. [B8 fix] createAtRelease now runs in its OWN
        // (REQUIRES_NEW) transaction and this call is defensively wrapped too — a PDF/R2/creator-
        // profile hiccup there must never roll back the release/ledger-posting above, which has
        // already committed-equivalent state in THIS transaction by this point.
        safelyCreateServiceInvoice(hold, collaboration, posting.creditLeg().getId());

        // [B3] Notify — the release actually happened (not the idempotent no-op branch above).
        publishPayoutReleasedEvent(workspaceId, milestone, collaboration, hold);

        return toStatusResponse(hold);
    }

    /** Refunds a FUNDED escrow hold back to the brand's own wallet (e.g. cancelled campaign). */
    @Transactional
    public EscrowStatusResponse refund(AuthPrincipal principal, String workspaceId, String escrowHoldId) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);

        EscrowHold hold = requireHoldForUpdate(escrowHoldId);
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND);
        }
        if (hold.getStatus() == EscrowStatus.REFUNDED) {
            return toStatusResponse(hold); // idempotent no-op
        }

        String collaborationId = resolveCollaborationId(hold);
        if (collaborationId != null) {
            collaborationRepository
                    .findById(collaborationId)
                    .ifPresent(this::assertEscrowNotBlockedByDispute);
        }

        requireStatus(hold, EscrowStatus.FUNDED, "refund");

        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet brandWallet = walletService.requireWorkspaceWallet(workspaceId);

        String idempotencyKey = "refund:" + hold.getId();
        var posting =
                ledgerService.post(
                        clearingWallet.getId(),
                        brandWallet.getId(),
                        hold.getAmount(),
                        hold.getCurrency(),
                        WalletTransactionType.ESCROW_REFUND,
                        TxnReferenceType.ESCROW_HOLD,
                        hold.getId(),
                        "Escrow refund for campaign " + hold.getCampaignId(),
                        idempotencyKey,
                        null);

        hold.markRefunded(posting.creditLeg().getId());
        escrowHoldRepository.save(hold);

        if (hold.getMilestoneId() != null) {
            milestoneRepository
                    .findById(hold.getMilestoneId())
                    .ifPresent(
                            milestone -> {
                                milestone.markRefunded(posting.creditLeg().getId(), idempotencyKey);
                                milestoneRepository.save(milestone);
                            });
        }

        return toStatusResponse(hold);
    }

    @Transactional(readOnly = true)
    public EscrowStatusResponse getStatus(AuthPrincipal principal, String workspaceId, String escrowHoldId) {
        brandContext.requireMember(principal, workspaceId);
        EscrowHold hold = requireHoldForWorkspace(escrowHoldId, workspaceId);
        return toStatusResponse(hold);
    }

    /**
     * Creator read-only escrow status for deal-room payments tab — only holds linked to the
     * creator's own collaborations are visible.
     */
    @Transactional(readOnly = true)
    public EscrowStatusResponse getStatusForCreator(AuthPrincipal principal, String escrowHoldId) {
        creatorContext.requireCreator(principal);
        EscrowHold hold = requireHoldForCreator(escrowHoldId, principal.getUserId());
        return toStatusResponse(hold);
    }

    /**
     * Freezes every unreleased ({@link EscrowStatus#FUNDED}) hold tied to a collaboration when a
     * dispute opens (CEO §1.3). Already-released holds are untouched — no automatic clawback.
     * Idempotent for holds already {@link EscrowStatus#FROZEN}.
     */
    @Transactional
    public int freezeUnreleasedForDispute(String collaborationId) {
        List<EscrowHold> funded = findFundedHoldsForCollaboration(collaborationId);
        funded.sort(Comparator.comparing(EscrowHold::getId));
        int frozen = 0;
        for (EscrowHold snapshot : funded) {
            EscrowHold hold =
                    escrowHoldRepository
                            .findByIdForUpdate(snapshot.getId())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    "ESCROW_NOT_FOUND",
                                                    "Escrow hold not found",
                                                    HttpStatus.NOT_FOUND));
            if (hold.getStatus() == EscrowStatus.FUNDED) {
                hold.markFrozen();
                escrowHoldRepository.save(hold);
                frozen++;
            }
        }
        return frozen;
    }

    /**
     * Returns whether the collaboration has at least one unreleased funded escrow hold (direct
     * collaboration linkage or via a milestone escrow_hold_id).
     */
    @Transactional(readOnly = true)
    public boolean hasFundedUnreleasedEscrow(String collaborationId) {
        return !findFundedHoldsForCollaboration(collaborationId).isEmpty();
    }

    /**
     * Admin dispute settlement — releases every FROZEN hold on the collaboration to the creator
     * (RESOLVED_CREATOR). Runs inside the caller's ({@code DisputeService}) transaction, after
     * {@link #freezeUnreleasedForDispute} has already FROZEN the holds. [SEC: money-movement path
     * — mandatory Kabir red-team gate before merge, same as {@code release}/{@code refund}.]
     */
    @Transactional
    public List<EscrowStatusResponse> adminReleaseForDispute(String collaborationId) {
        Collaboration collaboration = requireCollaboration(collaborationId);
        String payeeUserId = collaboration.getCreatorId();
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet payeeWallet = walletService.requireOrCreateUserWallet(payeeUserId);

        List<EscrowStatusResponse> results = new ArrayList<>();
        for (EscrowHold hold : requireFrozenHoldsForCollaboration(collaborationId)) {
            var feeDeduction =
                    platformFeeService.deductAtRelease(
                            clearingWallet,
                            referenceIdFor(hold),
                            payeeUserId,
                            hold.getAmount(),
                            hold.getCurrency(),
                            hold.getId());

            String idempotencyKey = "dispute-release:" + hold.getId();
            var posting =
                    ledgerService.post(
                            clearingWallet.getId(),
                            payeeWallet.getId(),
                            feeDeduction.netAmount(),
                            hold.getCurrency(),
                            WalletTransactionType.ESCROW_RELEASE,
                            TxnReferenceType.ESCROW_HOLD,
                            hold.getId(),
                            "Dispute-resolved release for collaboration " + collaborationId,
                            idempotencyKey,
                            null);

            hold.markReleased(posting.creditLeg().getId());
            escrowHoldRepository.save(hold);
            markMilestoneReleasedIfPresent(hold, posting.creditLeg().getId(), idempotencyKey);

            // D14 Doc#2 — same atomic-with-release wiring as the happy-path release() above.
            // [B8 fix] see safelyCreateServiceInvoice javadoc.
            safelyCreateServiceInvoice(hold, collaboration, posting.creditLeg().getId());
            publishPayoutReleasedEvent(hold.getWorkspaceId(), milestoneForHoldOrNull(hold), collaboration, hold);

            results.add(toStatusResponse(hold));
        }
        return results;
    }

    /**
     * Admin dispute settlement — refunds every FROZEN hold on the collaboration back to the
     * brand's wallet (RESOLVED_BRAND). [SEC: money-movement path — mandatory Kabir red-team gate.]
     */
    @Transactional
    public List<EscrowStatusResponse> adminRefundForDispute(String collaborationId) {
        requireCollaboration(collaborationId);
        Wallet clearingWallet = platformWalletService.requireClearingWallet();

        List<EscrowStatusResponse> results = new ArrayList<>();
        for (EscrowHold hold : requireFrozenHoldsForCollaboration(collaborationId)) {
            Wallet brandWallet = walletService.requireWorkspaceWallet(hold.getWorkspaceId());
            String idempotencyKey = "dispute-refund:" + hold.getId();
            var posting =
                    ledgerService.post(
                            clearingWallet.getId(),
                            brandWallet.getId(),
                            hold.getAmount(),
                            hold.getCurrency(),
                            WalletTransactionType.ESCROW_REFUND,
                            TxnReferenceType.ESCROW_HOLD,
                            hold.getId(),
                            "Dispute-resolved refund for collaboration " + collaborationId,
                            idempotencyKey,
                            null);

            hold.markRefunded(posting.creditLeg().getId());
            escrowHoldRepository.save(hold);
            markMilestoneRefundedIfPresent(hold, posting.creditLeg().getId(), idempotencyKey);
            results.add(toStatusResponse(hold));
        }
        return results;
    }

    /**
     * Admin dispute settlement — splits every FROZEN hold on the collaboration between creator and
     * brand by {@code creatorSplitPercent} (RESOLVED_SPLIT). {@code creatorSplitPercent} is range-
     * checked (0-100 inclusive) by {@code ResolveDisputeRequest}'s {@code @DecimalMin}/{@code
     * @DecimalMax} at the controller WHEN PRESENT — but bean validation treats an absent/{@code
     * null} value as vacuously valid (neither annotation is paired with {@code @NotNull}, and there
     * is no cross-field constraint tying "required" to {@code resolution == RESOLVED_SPLIT}, so
     * the DTO alone cannot reject "missing when this resolution needs it"). {@code
     * [SEC: Vikram, P4 defensive fix]} This method — the actual money-moving call — re-validates
     * defensively at its own boundary: rejects {@code null} and out-of-[0,100] values with a clean
     * {@code ApiException} (400) BEFORE touching escrow, wallets, or the ledger, instead of letting
     * a {@code null} reach {@code hold.getAmount().multiply(creatorSplitPercent)} as an unhandled
     * {@link NullPointerException} deep inside a money-movement transaction. Rounds each hold's
     * creator share to 2dp with {@link RoundingMode#HALF_UP}; the brand absorbs any residual paisa
     * on the remainder, matching this service's existing money-rounding discipline. Each hold ends
     * RELEASED (funds were distributed, the creator-payment leg is the transition of record) with
     * both legs posted through the existing ledger idempotency path. [SEC: money-movement path —
     * mandatory Kabir red-team gate before merge.]
     *
     * @throws ApiException {@code CREATOR_SPLIT_PERCENT_INVALID} (400) if {@code creatorSplitPercent}
     *     is {@code null} or outside {@code [0, 100]}
     */
    @Transactional
    public List<EscrowStatusResponse> adminSplitForDispute(
            String collaborationId, BigDecimal creatorSplitPercent) {
        if (creatorSplitPercent == null
                || creatorSplitPercent.compareTo(BigDecimal.ZERO) < 0
                || creatorSplitPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ApiException(
                    "CREATOR_SPLIT_PERCENT_INVALID",
                    "creatorSplitPercent must be between 0 and 100 inclusive",
                    HttpStatus.BAD_REQUEST);
        }
        Collaboration collaboration = requireCollaboration(collaborationId);
        String payeeUserId = collaboration.getCreatorId();
        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet payeeWallet = walletService.requireOrCreateUserWallet(payeeUserId);

        List<EscrowStatusResponse> results = new ArrayList<>();
        for (EscrowHold hold : requireFrozenHoldsForCollaboration(collaborationId)) {
            Wallet brandWallet = walletService.requireWorkspaceWallet(hold.getWorkspaceId());

            BigDecimal creatorAmount =
                    hold.getAmount()
                            .multiply(creatorSplitPercent)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal brandAmount = hold.getAmount().subtract(creatorAmount);

            String releaseIdempotencyKey = "dispute-split-release:" + hold.getId();
            String creditLegId = null;
            if (creatorAmount.signum() > 0) {
                var feeDeduction =
                        platformFeeService.deductAtRelease(
                                clearingWallet,
                                referenceIdFor(hold),
                                payeeUserId,
                                creatorAmount,
                                hold.getCurrency(),
                                hold.getId());
                var releasePosting =
                        ledgerService.post(
                                clearingWallet.getId(),
                                payeeWallet.getId(),
                                feeDeduction.netAmount(),
                                hold.getCurrency(),
                                WalletTransactionType.ESCROW_RELEASE,
                                TxnReferenceType.ESCROW_HOLD,
                                hold.getId(),
                                "Dispute-resolved split release for collaboration " + collaborationId,
                                releaseIdempotencyKey,
                                null);
                creditLegId = releasePosting.creditLeg().getId();
            }

            if (brandAmount.signum() > 0) {
                String refundIdempotencyKey = "dispute-split-refund:" + hold.getId();
                ledgerService.post(
                        clearingWallet.getId(),
                        brandWallet.getId(),
                        brandAmount,
                        hold.getCurrency(),
                        WalletTransactionType.ESCROW_REFUND,
                        TxnReferenceType.ESCROW_HOLD,
                        hold.getId(),
                        "Dispute-resolved split refund for collaboration " + collaborationId,
                        refundIdempotencyKey,
                        null);
            }

            hold.markReleased(creditLegId != null ? creditLegId : "dispute-split:" + hold.getId());
            escrowHoldRepository.save(hold);
            markMilestoneReleasedIfPresent(hold, creditLegId, releaseIdempotencyKey);

            // D14 Doc#2 — only when the creator actually received funds (creatorAmount > 0, i.e. a
            // real ESCROW_RELEASE posting happened and creditLegId is non-null); if the brand got
            // 100% of a split, there is no creator service to invoice.
            // [B8 fix] see safelyCreateServiceInvoice javadoc.
            if (creditLegId != null) {
                safelyCreateServiceInvoice(hold, collaboration, creditLegId);
                publishPayoutReleasedEvent(hold.getWorkspaceId(), milestoneForHoldOrNull(hold), collaboration, hold);
            }

            results.add(toStatusResponse(hold));
        }
        return results;
    }

    /**
     * [B8 fix] Isolates a service-invoice failure from the money-movement transaction that just
     * completed. {@link CampaignServiceInvoiceService#createAtRelease} now runs in its OWN
     * ({@code REQUIRES_NEW}) transaction — but an uncaught exception propagating out of that call
     * would still mark THIS (the release) transaction rollback-only once it reaches this method's
     * own {@code @Transactional} boundary. Catching here is what actually prevents that: any
     * exception is logged loudly (this is a real operational problem — e.g. a payee with no
     * creator profile, or a PDF/R2 failure that also broke the invoice row) but never propagates,
     * so a completed escrow release + ledger posting can never be undone by an invoicing failure.
     */
    private void safelyCreateServiceInvoice(
            EscrowHold hold, Collaboration collaboration, String ledgerCreditLegId) {
        try {
            campaignServiceInvoiceService.createAtRelease(hold, collaboration, ledgerCreditLegId);
        } catch (RuntimeException e) {
            log.error(
                    "Doc#2 creator service invoice failed for escrow hold {} (collaboration {}) — the"
                            + " completed escrow release/ledger posting is NOT affected; invoice must be"
                            + " backfilled manually",
                    hold.getId(),
                    collaboration.getId(),
                    e);
        }
    }

    /**
     * [B3] Publishes {@link PayoutReleasedEvent} (spec #8, previously modeled but never published
     * anywhere — grep 0 refs at a call site) after a release has actually happened. Best-effort:
     * resolving the campaign/workspace display names is a read-only convenience for the
     * notification copy, never a reason to fail an already-completed release, so any lookup issue
     * here is caught and logged rather than propagated.
     *
     * <p>[D2 coupling, flagged] {@code NotificationListener}'s handlers are plain {@code @Async
     * @EventListener} today, not {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — D2's
     * AFTER_COMMIT work is still open per the task brief. That means this event is technically
     * visible to the async listener before the surrounding transaction commits. This is a
     * pre-existing gap shared by every event in this codebase (not introduced here) and is safe in
     * the specific case of {@code tryReleaseOnApproval}/{@code release} because the money movement
     * itself has already succeeded by the time this call is reached (no code after this can still
     * roll the transaction back except an unexpected error, which would be a genuine incident
     * either way) — but a crash between commit-intent and actual commit could still notify for a
     * release that technically never lands. Wiring D2 would close that residual gap; not attempted
     * here as it is explicitly out of this task's scope.
     */
    private void publishPayoutReleasedEvent(
            String workspaceId, PaymentMilestone milestone, Collaboration collaboration, EscrowHold hold) {
        try {
            String campaignTitle =
                    campaignRepository.findById(hold.getCampaignId()).map(Campaign::getTitle).orElse("your campaign");
            String brandName =
                    workspaceRepository.findById(workspaceId).map(Workspace::getName).orElse("The brand");
            String entityId = milestone != null ? milestone.getId() : hold.getId();
            eventPublisher.publishEvent(
                    new PayoutReleasedEvent(
                            collaboration.getCreatorId(),
                            workspaceId,
                            entityId,
                            brandName,
                            campaignTitle,
                            hold.getAmount() + " " + hold.getCurrency()));
        } catch (RuntimeException e) {
            log.error(
                    "Failed to publish PayoutReleasedEvent for escrow hold {} — release itself already"
                            + " succeeded, this only affects the in-app/email notification",
                    hold.getId(),
                    e);
        }
    }

    private PaymentMilestone milestoneForHoldOrNull(EscrowHold hold) {
        if (hold.getMilestoneId() == null) {
            return null;
        }
        return milestoneRepository.findById(hold.getMilestoneId()).orElse(null);
    }

    /**
     * [B5] {@code EscrowService#release} previously never consulted {@link
     * PaymentMilestone#getReleaseCondition()} at all ("dead schema"). This is the actual gate: a
     * release is blocked unless every {@link Deliverable} linked to this milestone (via {@code
     * deliverables.milestone_id}) has reached a status that satisfies the milestone's {@code
     * release_condition}.
     *
     * <p>Deliberately fails OPEN (no gate applied) when NO deliverable is linked to the milestone
     * at all — many existing/legacy milestones (lump-sum payments, product-seeding milestones, etc.
     * predating per-slot deliverable tracking) have no {@code deliverables.milestone_id} pointing
     * at them, and retroactively blocking ALL of those releases would be a production-breaking
     * change unrelated to this fix's intent (closing the specific "content approval was gated on
     * nothing" gap, not inventing a new requirement that every milestone must have a deliverable).
     */
    private void assertReleaseConditionSatisfied(PaymentMilestone milestone) {
        List<Deliverable> linked = deliverableRepository.findByMilestoneId(milestone.getId());
        if (linked.isEmpty()) {
            return;
        }
        ReleaseCondition condition =
                milestone.getReleaseCondition() != null
                        ? milestone.getReleaseCondition()
                        : ReleaseCondition.ON_POSTED;
        Set<DeliverableStatus> satisfying = satisfyingStatusesFor(condition);
        boolean allSatisfied = linked.stream().map(Deliverable::getStatus).allMatch(satisfying::contains);
        if (!allSatisfied) {
            throw new ApiException(
                    "RELEASE_CONDITION_NOT_MET",
                    "Milestone release_condition (" + condition + ") is not yet satisfied by its deliverable(s)",
                    HttpStatus.CONFLICT);
        }
    }

    private static Set<DeliverableStatus> satisfyingStatusesFor(ReleaseCondition condition) {
        return switch (condition) {
            case ON_APPROVAL ->
                    EnumSet.of(
                            DeliverableStatus.APPROVED,
                            DeliverableStatus.POSTED,
                            DeliverableStatus.METRICS_REPORTED,
                            DeliverableStatus.VERIFIED);
            case ON_POSTED ->
                    EnumSet.of(
                            DeliverableStatus.POSTED,
                            DeliverableStatus.METRICS_REPORTED,
                            DeliverableStatus.VERIFIED);
            case ON_VERIFIED_METRICS -> EnumSet.of(DeliverableStatus.VERIFIED);
        };
    }

    private Collaboration requireCollaboration(String collaborationId) {
        return collaborationRepository
                .findById(collaborationId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "COLLABORATION_NOT_FOUND",
                                        "Collaboration not found",
                                        HttpStatus.NOT_FOUND));
    }

    private List<EscrowHold> requireFrozenHoldsForCollaboration(String collaborationId) {
        List<EscrowHold> frozen =
                escrowHoldRepository.findByCollaborationIdAndStatus(collaborationId, EscrowStatus.FROZEN);
        List<EscrowHold> locked = new ArrayList<>();
        for (EscrowHold snapshot : frozen) {
            locked.add(requireHoldForUpdate(snapshot.getId()));
        }
        return locked;
    }

    private static String referenceIdFor(EscrowHold hold) {
        return hold.getMilestoneId() != null ? hold.getMilestoneId() : hold.getId();
    }

    private void markMilestoneReleasedIfPresent(
            EscrowHold hold, String releaseTxnId, String idempotencyKey) {
        if (hold.getMilestoneId() == null || releaseTxnId == null) {
            return;
        }
        milestoneRepository
                .findById(hold.getMilestoneId())
                .ifPresent(
                        milestone -> {
                            milestone.markReleased(releaseTxnId, idempotencyKey);
                            milestoneRepository.save(milestone);
                        });
    }

    private void markMilestoneRefundedIfPresent(
            EscrowHold hold, String refundTxnId, String idempotencyKey) {
        if (hold.getMilestoneId() == null) {
            return;
        }
        milestoneRepository
                .findById(hold.getMilestoneId())
                .ifPresent(
                        milestone -> {
                            milestone.markRefunded(refundTxnId, idempotencyKey);
                            milestoneRepository.save(milestone);
                        });
    }

    private List<EscrowHold> findFundedHoldsForCollaboration(String collaborationId) {
        Set<String> seen = new LinkedHashSet<>();
        List<EscrowHold> result = new ArrayList<>();
        for (EscrowHold hold :
                escrowHoldRepository.findByCollaborationIdAndStatus(
                        collaborationId, EscrowStatus.FUNDED)) {
            if (seen.add(hold.getId())) {
                result.add(hold);
            }
        }
        for (PaymentMilestone milestone : milestoneRepository.findByCollaborationId(collaborationId)) {
            if (milestone.getEscrowHoldId() == null) {
                continue;
            }
            escrowHoldRepository
                    .findById(milestone.getEscrowHoldId())
                    .filter(h -> h.getStatus() == EscrowStatus.FUNDED)
                    .ifPresent(
                            hold -> {
                                if (seen.add(hold.getId())) {
                                    result.add(hold);
                                }
                            });
        }
        return result;
    }

    private EscrowHold requireHoldForWorkspace(String escrowHoldId, String workspaceId) {
        return escrowHoldRepository
                .findByIdAndWorkspaceId(escrowHoldId, workspaceId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));
    }

    private EscrowHold requireHoldForCreator(String escrowHoldId, String creatorUserId) {
        return escrowHoldRepository
                .findByIdAndCreatorId(escrowHoldId, creatorUserId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));
    }

    private EscrowHold requireHold(String escrowHoldId) {
        return escrowHoldRepository
                .findById(escrowHoldId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));
    }

    private EscrowHold requireHoldForUpdate(String escrowHoldId) {
        return escrowHoldRepository
                .findByIdForUpdate(escrowHoldId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));
    }

    private void assertEscrowNotBlockedByDispute(Collaboration collaboration) {
        if (collaboration.getStatus() == CollaborationStatus.DISPUTED) {
            throw escrowBlockedByDispute();
        }
        if (disputeRepository.existsByCollaborationIdAndStatusIn(
                collaboration.getId(), ACTIVE_DISPUTE_STATUSES)) {
            throw escrowBlockedByDispute();
        }
    }

    private static ApiException escrowBlockedByDispute() {
        return new ApiException(
                "ESCROW_BLOCKED_BY_DISPUTE",
                "Escrow release and refund are blocked while a dispute is active on this collaboration",
                HttpStatus.CONFLICT);
    }

    private String resolveCollaborationId(EscrowHold hold) {
        if (hold.getCollaborationId() != null) {
            return hold.getCollaborationId();
        }
        if (hold.getMilestoneId() == null) {
            return null;
        }
        return milestoneRepository
                .findById(hold.getMilestoneId())
                .map(PaymentMilestone::getCollaborationId)
                .orElse(null);
    }

    /**
     * Validates that the amount/currency Razorpay reports as captured in the webhook matches the
     * server-authoritative {@link EscrowHold} amount/currency exactly. Amount is converted from
     * paise (webhook unit) to the hold's DECIMAL(14,2) rupee unit before comparison. Throws (and
     * never transitions to FUNDED) on any mismatch, including a missing amount/currency in the
     * webhook payload — a webhook we cannot verify is treated the same as one that fails
     * verification.
     */
    private static void validateWebhookAmount(EscrowHold hold, Long webhookAmountInPaise, String webhookCurrency) {
        if (webhookAmountInPaise == null || webhookCurrency == null || webhookCurrency.isBlank()) {
            log.error(
                    "Escrow funding webhook missing amount/currency for hold {} (expected {} {}) —"
                            + " rejecting, manual review required",
                    hold.getId(),
                    hold.getAmount(),
                    hold.getCurrency());
            throw new ApiException(
                    "ESCROW_WEBHOOK_AMOUNT_MISSING",
                    "Webhook payload did not include a payment amount/currency to verify",
                    HttpStatus.CONFLICT);
        }

        BigDecimal webhookAmount =
                BigDecimal.valueOf(webhookAmountInPaise).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal expectedAmount = hold.getAmount().setScale(2, RoundingMode.UNNECESSARY);

        boolean amountMatches = webhookAmount.compareTo(expectedAmount) == 0;
        boolean currencyMatches = webhookCurrency.equalsIgnoreCase(hold.getCurrency());

        if (!amountMatches || !currencyMatches) {
            log.error(
                    "Escrow funding amount/currency mismatch for hold {}: expected {} {}, webhook"
                            + " reported {} {} — payment/escrow amount mismatch, requires manual review",
                    hold.getId(),
                    expectedAmount,
                    hold.getCurrency(),
                    webhookAmount,
                    webhookCurrency);
            throw new ApiException(
                    "ESCROW_AMOUNT_MISMATCH",
                    "Webhook-reported payment amount/currency does not match the expected escrow hold",
                    HttpStatus.CONFLICT);
        }
    }

    private static void requireStatus(EscrowHold hold, EscrowStatus expected, String action) {
        if (hold.getStatus() != expected) {
            throw new ApiException(
                    "INVALID_ESCROW_STATE",
                    "Cannot " + action + " escrow hold in status " + hold.getStatus(),
                    HttpStatus.CONFLICT);
        }
    }

    private static EscrowStatusResponse toStatusResponse(EscrowHold hold) {
        return new EscrowStatusResponse(
                hold.getId(),
                hold.getWorkspaceId(),
                hold.getCampaignId(),
                hold.getMilestoneId(),
                hold.getAmount(),
                hold.getCurrency(),
                hold.getStatus(),
                hold.getFundedAt(),
                hold.getReleasedAt());
    }
}
