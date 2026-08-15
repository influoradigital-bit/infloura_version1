package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.InsufficientFundsException;
import com.influora.common.PageMeta;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
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
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.escrow.EscrowBackend;
import com.influora.service.notification.event.EscrowFundedEvent;
import com.influora.service.notification.event.PayoutReleasedEvent;
import com.influora.web.dto.money.MoneyDtos.EscrowFundResponse;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final ContractRepository contractRepository;
    private final DisputeRepository disputeRepository;
    private final WalletService walletService;
    private final BrandContextService brandContext;
    private final CreatorContextService creatorContext;
    private final CampaignServiceInvoiceService campaignServiceInvoiceService;
    private final DeliverableRepository deliverableRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CollaborationLifecycleService collaborationLifecycleService;
    private final EscrowBackend escrowBackend;

    /**
     * CR-51 step 2 — cutover for the {@link #assertReleaseConditionSatisfied} gate. ISO-8601
     * instant (e.g. {@code 2026-07-30T00:00:00Z}), or blank/unset to keep the gate fully
     * disabled (pre-CR-51 behavior: always fail open). Deliberately NOT derived from "has
     * deliverables" — that is the circular condition CR-51 exists to fix. Swapnil's ruling: the
     * boundary is wall-clock time (milestone {@code created_at} vs. this deploy-time cutover),
     * not the presence of deliverable rows.
     */
    @Value("${influora.escrow.release-gate.cutover-instant:}")
    private String releaseGateCutoverInstantRaw;

    private Instant releaseGateCutoverInstant;

    @PostConstruct
    void initReleaseGateCutoverInstant() {
        if (releaseGateCutoverInstantRaw == null || releaseGateCutoverInstantRaw.isBlank()) {
            releaseGateCutoverInstant = null;
            return;
        }
        try {
            releaseGateCutoverInstant = Instant.parse(releaseGateCutoverInstantRaw.trim());
        } catch (RuntimeException e) {
            log.error(
                    "Invalid influora.escrow.release-gate.cutover-instant value '{}' — expected an"
                            + " ISO-8601 instant (e.g. 2026-07-30T00:00:00Z). Gate stays disabled"
                            + " (fail-open) until this is fixed.",
                    releaseGateCutoverInstantRaw,
                    e);
            releaseGateCutoverInstant = null;
        }
    }

    public EscrowService(
            EscrowHoldRepository escrowHoldRepository,
            PaymentMilestoneRepository milestoneRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            ContractRepository contractRepository,
            DisputeRepository disputeRepository,
            WalletService walletService,
            BrandContextService brandContext,
            CreatorContextService creatorContext,
            CampaignServiceInvoiceService campaignServiceInvoiceService,
            DeliverableRepository deliverableRepository,
            WorkspaceRepository workspaceRepository,
            ApplicationEventPublisher eventPublisher,
            CollaborationLifecycleService collaborationLifecycleService,
            EscrowBackend escrowBackend) {
        this.escrowHoldRepository = escrowHoldRepository;
        this.milestoneRepository = milestoneRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.contractRepository = contractRepository;
        this.disputeRepository = disputeRepository;
        this.walletService = walletService;
        this.brandContext = brandContext;
        this.creatorContext = creatorContext;
        this.campaignServiceInvoiceService = campaignServiceInvoiceService;
        this.deliverableRepository = deliverableRepository;
        this.workspaceRepository = workspaceRepository;
        this.eventPublisher = eventPublisher;
        this.collaborationLifecycleService = collaborationLifecycleService;
        this.escrowBackend = escrowBackend;
    }

    /** Paged brand-scoped escrow hold list — GET /wallet/escrow (mirrors {@code WalletService.PagedWalletTransactions}). */
    public record PagedEscrowHolds(List<EscrowStatusResponse> items, PageMeta meta) {}

    /**
     * Creates (or replays, if {@code idempotencyKey} was already used) an escrow hold and funds
     * it immediately from the brand's platform wallet. {@code [FIX: double-charge, 2026-07-26]}
     * this method requires {@code wallet.getBalance() >= amount} below before a hold is even
     * created, so the money is always already in the wallet by the time this runs — there is no
     * legitimate path here where a fresh Razorpay payment is still needed. It used to create a
     * Razorpay order regardless and let the frontend open a second Checkout for the same amount,
     * which double-charged the brand once confirmFunded's webhook debited the wallet on top of
     * that second payment. See {@link #applyFunding} for the actual funding transition.
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

        // [BE-2: Vikram, contract-flow-architecture-2026-07-23 §6.5 — escrow gated on ACTIVE]
        // Previously this method had NO awareness of contract/signature state at all: a brand could
        // fund escrow for a milestone whose contract was still DRAFT or only single-signed (the
        // `promptEscrowFundingIfNeeded` notification in ContractService only fires post-full-sign,
        // but nothing on THIS, the actual money-moving path, ever enforced it). Gated only when a
        // milestoneId is supplied — campaign-level funding with no milestoneId predates the
        // contract/milestone model and has no contract to check against.
        //
        // [FIX: escrow-frozen-hold-fix-spec, Fix 2] this now also returns the milestone so its
        // (non-null) collaborationId can be bound onto the hold below — see the comment at the
        // hold-building block for why.
        PaymentMilestone milestoneForCollaborationBinding = null;
        if (milestoneId != null && !milestoneId.isBlank()) {
            milestoneForCollaborationBinding = assertContractActiveForMilestone(milestoneId, workspaceId);
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
            // [SEC: MF-1 follow-up, 2026-07-21] The 402 body now carries the exact server-derived
            // requiredAmount/walletBalance/shortfallAmount so the frontend's inline top-up leg never
            // has to estimate a charge amount from its own GET /wallet read — it sends this
            // shortfallAmount straight to POST /wallet/topup. All three figures come from this same
            // balance read (`wallet`) and the already-derived `amount`, never re-fetched or guessed.
            throw new InsufficientFundsException(
                    "Wallet balance is insufficient for this escrow amount",
                    amount,
                    wallet.getBalance(),
                    amount.subtract(wallet.getBalance()),
                    currency);
        }

        EscrowHold.Builder holdBuilder =
                EscrowHold.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .campaignId(campaignId)
                        .milestoneId(milestoneId)
                        .amount(amount)
                        .currency(currency)
                        .status(EscrowStatus.PENDING)
                        .idempotencyKey(idempotencyKey);
        // [FIX: escrow-frozen-hold-fix-spec, Fix 2 — root cause] Previously this builder NEVER set
        // collaborationId, for any caller of this endpoint (the ordinary brand escrow flow) — the
        // only code in the entire tree that ever called `EscrowHold.bindCollaboration` was
        // ConfirmLaunchExecutor's AI-launch path. Every hold funded through POST /wallet/escrow/fund
        // therefore carried collaboration_id = NULL forever, which is what made
        // `requireFrozenHoldsForCollaboration`'s (pre-fix) non-fallback lookup silently iterate an
        // empty list during dispute settlement (see class-level Fix 1 helper). `PaymentMilestone`'s
        // collaboration_id column is NOT NULL, so whenever a milestone was supplied we always have a
        // real collaboration to bind here — do it now, at creation, instead of relying on a
        // best-effort bind sometime later.
        //
        // When milestoneId is null (campaign-level pool funding, no collaboration exists yet at
        // this point), collaborationId is deliberately left null — a genuine "not yet resolvable"
        // case, not an oversight. This is safe only because Fix 1
        // (`resolveHoldsForCollaboration`'s milestone-table fallback) makes every downstream lookup
        // robust to a null column; it is not safe on its own.
        if (milestoneForCollaborationBinding != null) {
            holdBuilder.collaborationId(milestoneForCollaborationBinding.getCollaborationId());
        }
        EscrowHold hold = holdBuilder.build();
        escrowHoldRepository.save(hold);

        // [FIX: double-charge, 2026-07-26] The balance check above (wallet.getBalance() >=
        // amount) means every call reaching this line already has the money sitting in the
        // brand's platform wallet — there is no code path in this method where escrow gets
        // funded by a FRESH Razorpay payment instead of an already-topped-up wallet. This used
        // to call `razorpayClient.createOrder(...)` here regardless, and the frontend opened a
        // SECOND Razorpay Checkout for the same amount on top of the wallet debit that follows
        // — the brand paid twice for one hold. Fund immediately from the wallet balance (same
        // ledger movement `confirmFunded` used to apply off a webhook) and skip the gateway
        // entirely; no orderId is returned because no Checkout step is needed.
        applyFunding(hold, null);

        return new EscrowFundResponse(hold.getId(), amount, currency, null, hold.getStatus());
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
     * [BE-2: Vikram, contract-flow-architecture-2026-07-23 §6.5] The actual enforcement point for
     * "escrow cannot be funded before the contract is ACTIVE". {@code Contract.status} only
     * advances to {@code ACTIVE} once BOTH {@code brandSignedAt} and {@code creatorSignedAt} are
     * set ({@code Contract#advanceIfFullySigned}) — checked directly here (not via the enum) so
     * this stays correct even if a future migration back-fills {@code status} inconsistently.
     * Workspace-scoped milestone lookup (mirrors {@code deriveFundAmount}) so a caller cannot probe
     * another workspace's milestone/contract state via this gate either.
     *
     * @return the resolved {@link PaymentMilestone} — reused by the caller ({@link #initiateFund})
     *     to bind {@code collaborationId} onto the new hold (Fix 2 of the escrow-frozen-hold-fix
     *     spec) instead of issuing a second, redundant lookup.
     */
    private PaymentMilestone assertContractActiveForMilestone(String milestoneId, String workspaceId) {
        PaymentMilestone milestone =
                milestoneRepository
                        .findByIdAndWorkspaceId(milestoneId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND));
        Contract contract =
                contractRepository
                        .findById(milestone.getContractId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CONTRACT_NOT_FOUND", "Contract not found", HttpStatus.NOT_FOUND));
        if (contract.getBrandSignedAt() == null || contract.getCreatorSignedAt() == null) {
            throw new ApiException(
                    "CONTRACT_NOT_ACTIVE",
                    "Escrow cannot be funded until the contract is fully signed by both parties",
                    HttpStatus.CONFLICT);
        }

        // [CR-22a, Kabir finding #1] Previously nothing on this, the actual money-moving path,
        // ever read CollaborationStatus — this gate (assertContractActiveForMilestone) only ever
        // inspected the CONTRACT's two signature timestamps, so escrow could be funded for the
        // FIRST time on a CANCELLED collaboration. `PaymentMilestone.collaborationId` is NOT NULL
        // (V10), so this lookup always resolves. Locked (not a plain findById) for the same
        // reason ContractService#generate/#doRecordSignature are: serializes this against a
        // concurrent DealService#reject on the same collaboration row.
        Collaboration collaboration =
                collaborationRepository
                        .findByIdForUpdate(milestone.getCollaborationId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));
        if (collaboration.getStatus() == CollaborationStatus.CANCELLED) {
            throw new ApiException(
                    "COLLABORATION_CANCELLED",
                    "This deal was cancelled and its escrow can no longer be funded",
                    HttpStatus.CONFLICT);
        }

        return milestone;
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

        return applyFunding(hold, gatewayRef);
    }

    /**
     * The one place a PENDING hold actually transitions to FUNDED: posts the ledger movement
     * (brand wallet → platform clearing wallet), marks the hold/milestone funded, and fires the
     * funded-notification best-effort. Shared by {@link #confirmFunded} (webhook-verified gateway
     * payment) and {@link #initiateFund}'s immediate wallet-funded path (2026-07-26 fix) — both
     * apply the exact same movement, the only difference is what {@code gatewayRef} is (a
     * verified Razorpay payment id, or {@code null} when the money was already sitting in the
     * wallet and no gateway call was made).
     */
    private EscrowHold applyFunding(EscrowHold hold, String gatewayRef) {
        // [SEC: Kabir OWASP CRITICAL] The ledger's own idempotency key must be derived from this
        // hold's server-generated id, never the raw client `Idempotency-Key` header
        // (hold.getIdempotencyKey()) — that header is shared, unscoped input and a client reusing
        // it across a top-up and an escrow-fund call would make WalletLedgerService#post's replay
        // short-circuit return the wrong movement's rows. Same "<feature>:<id>" convention as
        // release/refund below.
        String ledgerIdempotencyKey = "escrow-fund:" + hold.getId();
        var outcome =
                escrowBackend.fund(
                        new EscrowBackend.FundCommand(
                                hold.getWorkspaceId(),
                                hold.getId(),
                                hold.getAmount(),
                                hold.getCurrency(),
                                "Escrow fund for campaign " + hold.getCampaignId(),
                                ledgerIdempotencyKey,
                                gatewayRef));

        hold.markFunded(outcome.fundTxnId());
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
     * [P-1' fix, BrandF.md §47a] Releases a FUNDED escrow hold addressed by its OWN id, for holds
     * that have no {@link PaymentMilestone} — {@code EscrowHold.milestoneId} is null. {@code
     * PaymentMilestone} rows are created in exactly one place ({@link ContractService} when a
     * contract is generated), but {@link #initiateFund} has always supported funding a hold at the
     * campaign level with no milestone (see its javadoc) — this is the shape Meera's workspace
     * funds before any contract/milestone exists. Before this method, such a hold had no release
     * path at all: {@link #release}/{@link #releaseInternal} require a milestone to resolve, and
     * {@link #refund} (which already takes a bare {@code escrowHoldId}) only sends the money back
     * to the brand, never out to a creator. Money would sit FUNDED forever with no exit.
     *
     * <p>Deliberately REJECTS a hold that DOES have a milestone (routes the caller back to {@link
     * #release}) — that keeps the B5 {@link #assertReleaseConditionSatisfied} gate, which is
     * milestone-{@code release_condition}-driven, as the sole authority for milestone-backed holds
     * instead of creating a second path that could silently skip it.
     *
     * <p>Same authorization (brand OWNER/ADMIN), row-lock, tenant check, cancellation/dispute
     * guards, FUNDED-state requirement, idempotent RELEASED no-op, and ledger idempotency-key
     * discipline ({@code "release:<holdId>"}, disjoint from milestone releases because this path is
     * only reachable for holds {@link #releaseInternal} can never touch) as the milestone path —
     * this does not weaken or duplicate that logic, it extends the same guard set to holds
     * {@link #releaseInternal} structurally cannot address. Payee is resolved server-side from
     * {@code Collaboration.creatorId}, same as {@link #releaseInternal}, never from the request.
     */
    @Transactional
    public EscrowStatusResponse releaseByHoldId(
            AuthPrincipal principal, String workspaceId, String escrowHoldId) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);
        return releaseByHoldIdInternal(workspaceId, escrowHoldId);
    }

    private EscrowStatusResponse releaseByHoldIdInternal(String workspaceId, String escrowHoldId) {
        EscrowHold hold = requireHoldForUpdate(escrowHoldId);
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND);
        }
        if (hold.getMilestoneId() != null) {
            // Milestone-backed hold — must go through release(milestoneId) so the B5
            // release_condition gate is never bypassed by addressing the hold directly.
            throw new ApiException(
                    "ESCROW_HOLD_HAS_MILESTONE",
                    "This escrow hold is tied to milestone "
                            + hold.getMilestoneId()
                            + " — release it via POST /wallet/escrow/release with that milestoneId"
                            + " instead of escrowHoldId",
                    HttpStatus.CONFLICT);
        }

        String collaborationId = resolveCollaborationId(hold);
        if (collaborationId == null) {
            // Funded at campaign level and never bound to a collaboration yet (see initiateFund's
            // javadoc) — there is no payee to resolve. This is a real "not yet releasable" state,
            // not a bug; refund() remains available to send the money back to the brand.
            throw new ApiException(
                    "ESCROW_HOLD_NOT_LINKED",
                    "This escrow hold is not yet linked to a creator collaboration — nothing to"
                            + " release to",
                    HttpStatus.CONFLICT);
        }
        Collaboration collaboration =
                collaborationRepository
                        .findById(collaborationId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));
        String payeeUserId = collaboration.getCreatorId();

        assertReleaseNotBlockedByCancellation(collaboration);
        assertEscrowNotBlockedByDispute(collaboration);

        if (hold.getStatus() == EscrowStatus.RELEASED) {
            return toStatusResponse(hold); // idempotent no-op
        }
        requireStatus(hold, EscrowStatus.FUNDED, "release");

        // No PaymentMilestone exists for this hold, so the B5 release_condition gate
        // (assertReleaseConditionSatisfied) does not apply — that gate reads a milestone's
        // configured release_condition, which has no equivalent here. Nothing to check before
        // moving money for this shape of hold.

        String idempotencyKey = "release:" + hold.getId();
        var outcome =
                escrowBackend.release(
                        new EscrowBackend.ReleaseCommand(
                                hold.getId(),
                                payeeUserId,
                                hold.getAmount(),
                                hold.getCurrency(),
                                referenceIdFor(hold),
                                TxnReferenceType.ESCROW_HOLD,
                                hold.getId(),
                                "Escrow hold release for campaign " + hold.getCampaignId(),
                                idempotencyKey));

        hold.markReleased(outcome.releaseTxnId());
        escrowHoldRepository.save(hold);

        safelyCreateServiceInvoice(hold, collaboration, outcome.releaseTxnId());
        publishPayoutReleasedEvent(workspaceId, null, collaboration, hold);

        return toStatusResponse(hold);
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
        // [CR-48] Resolve the milestone SCOPED TO THE CALLER'S WORKSPACE first, via the same
        // milestone -> collaboration -> campaign -> workspace join deriveFundAmount already uses to
        // close its own cross-tenant IDOR (see PaymentMilestoneRepository#findByIdAndWorkspaceId).
        // CR-47 moved the *hold's* tenant check above the CANCELLED/DISPUTED guards, but left this
        // milestone lookup and the funded-state check below it BOTH unscoped and sequenced ahead of
        // any tenant gate at all — workspace A could still pass workspace B's milestoneId and learn,
        // via three distinct outcomes (404 MILESTONE_NOT_FOUND / 409 MILESTONE_NOT_FUNDED / 404
        // ESCROW_NOT_FOUND), whether B's milestone exists and whether it's escrow-funded. Scoping
        // this lookup up front collapses "doesn't exist" and "exists in another workspace" into the
        // exact same MILESTONE_NOT_FOUND, matching deriveFundAmount's precedent, BEFORE the
        // MILESTONE_NOT_FUNDED branch can ever be reached for a foreign id.
        //
        // MILESTONE_NOT_FUNDED itself is NOT collapsed — isExpectedReleaseSkip whitelists it so
        // tryReleaseOnApproval can gracefully skip release on an approval that predates funding. That
        // is safe to keep as-is here precisely BECAUSE the lookup above already proved the milestone
        // is in-tenant before this branch can fire; it is no longer reachable for a foreign workspace.
        PaymentMilestone milestone =
                milestoneRepository
                        .findByIdAndWorkspaceId(milestoneId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MILESTONE_NOT_FOUND", "Milestone not found", HttpStatus.NOT_FOUND));
        if (milestone.getEscrowHoldId() == null) {
            throw new ApiException(
                    "MILESTONE_NOT_FUNDED", "Milestone has no funded escrow hold", HttpStatus.CONFLICT);
        }
        // [CR-47] Hold-level tenant check retained as defense-in-depth. By this point the milestone
        // lookup above has already proven workspaceId ownership via the campaign join, so this should
        // never fire for a genuine cross-tenant caller anymore; if it ever does, that indicates a data
        // inconsistency (hold.workspaceId diverged from its milestone's campaign workspace) rather
        // than a live cross-tenant probe, and ESCROW_NOT_FOUND is still the right uniform response.
        EscrowHold hold = requireHoldForUpdate(milestone.getEscrowHoldId());
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND);
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

        // [CR-36 residual, escrow-cancelled-gate-spec] release-only guard — see its javadoc for
        // why refund() below is deliberately NOT given this check.
        assertReleaseNotBlockedByCancellation(collaboration);
        assertEscrowNotBlockedByDispute(collaboration);

        if (hold.getStatus() == EscrowStatus.RELEASED) {
            return toStatusResponse(hold); // idempotent no-op
        }
        requireStatus(hold, EscrowStatus.FUNDED, "release");

        // [B5] release_condition gate — must fail BEFORE any money moves. A hold that is FUNDED but
        // whose milestone's release_condition isn't yet satisfied by the linked deliverable(s) is
        // legitimately not releasable yet; this must never be bypassed by calling release() instead
        // of the (nonexistent, by design) more-specific method.
        assertReleaseConditionSatisfied(milestone);

        String idempotencyKey = "release:" + hold.getId();
        var outcome =
                escrowBackend.release(
                        new EscrowBackend.ReleaseCommand(
                                hold.getId(),
                                payeeUserId,
                                hold.getAmount(),
                                hold.getCurrency(),
                                milestone.getId(),
                                TxnReferenceType.MILESTONE,
                                milestone.getId(),
                                "Milestone release for contract " + milestone.getContractId(),
                                idempotencyKey));

        hold.markReleased(outcome.releaseTxnId());
        escrowHoldRepository.save(hold);
        milestone.markReleased(outcome.releaseTxnId(), idempotencyKey);
        milestoneRepository.save(milestone);

        // D14 Doc#2 — creator service invoice. [B8 fix] createAtRelease now runs in its OWN
        // (REQUIRES_NEW) transaction and this call is defensively wrapped too — a PDF/R2/creator-
        // profile hiccup there must never roll back the release/ledger-posting above, which has
        // already committed-equivalent state in THIS transaction by this point.
        safelyCreateServiceInvoice(hold, collaboration, outcome.releaseTxnId());

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

        String idempotencyKey = "refund:" + hold.getId();
        var outcome =
                escrowBackend.refund(
                        new EscrowBackend.RefundCommand(
                                workspaceId,
                                hold.getId(),
                                hold.getAmount(),
                                hold.getCurrency(),
                                "Escrow refund for campaign " + hold.getCampaignId(),
                                idempotencyKey));

        hold.markRefunded(outcome.refundTxnId());
        escrowHoldRepository.save(hold);

        if (hold.getMilestoneId() != null) {
            milestoneRepository
                    .findById(hold.getMilestoneId())
                    .ifPresent(
                            milestone -> {
                                milestone.markRefunded(outcome.refundTxnId(), idempotencyKey);
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
     * Brand-scoped, paginated escrow hold list — GET /wallet/escrow (backs the brand-wallet page's
     * escrow-items panel, which previously had no live endpoint and rendered mock data). Any member
     * of the workspace may read it (same access level as {@link #getStatus}, no OWNER/ADMIN role
     * gate — this is a read, not a money movement). Each item carries exactly the fields
     * {@link #getStatus} returns for a single hold ({@link EscrowStatusResponse}), same clamped
     * page/limit discipline as {@code WalletService#getTransactionsForUser}.
     */
    @Transactional(readOnly = true)
    public PagedEscrowHolds listForWorkspace(AuthPrincipal principal, String workspaceId, int page, int limit) {
        brandContext.requireMember(principal, workspaceId);

        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        Page<EscrowHold> result =
                escrowHoldRepository.findByWorkspaceIdOrderByCreatedAtDesc(
                        workspaceId,
                        PageRequest.of(safePage - 1, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<EscrowStatusResponse> items =
                result.getContent().stream().map(EscrowService::toStatusResponse).toList();
        return new PagedEscrowHolds(
                items, new PageMeta(safePage, safeLimit, result.getTotalElements(), result.hasNext()));
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
     * [FIX: escrow-frozen-hold-fix-spec, Fix 3 support] Whether the collaboration has at least one
     * FROZEN escrow hold right now (same fallback-aware lookup as
     * {@link #requireFrozenHoldsForCollaboration}). {@link DisputeService#resolveDispute} calls
     * this BEFORE running the settlement to distinguish "this collaboration genuinely has no
     * escrow" (a dispute on an unfunded deal, which must still resolve cleanly) from "this
     * collaboration has frozen escrow but the settlement moved zero holds" (an invariant
     * violation that must never be allowed to silently resolve as if the money moved).
     */
    @Transactional(readOnly = true)
    public boolean hasFrozenEscrow(String collaborationId) {
        return countFrozenHolds(collaborationId) > 0;
    }

    /**
     * How many FROZEN holds this collaboration has right now.
     *
     * [SEC: Kabir gate on CR-35, HIGH-2] `DisputeService` needs the COUNT, not a boolean. A boolean
     * only answers "was there anything to move", which is satisfied by a single hold moving out of
     * a hundred — and, worse, reads {@code false} for a collaboration whose escrow a FIRST dispute
     * already settled, letting a second dispute resolve and audit-log {@code ESCROW_RELEASE} for a
     * movement that did not happen. No race is required for that; two disputes on one collaboration
     * are enough, and nothing today prevents them ({@code DisputeService}'s duplicate check is a
     * bare {@code existsBy} and the index behind it is not unique).
     *
     * Comparing counts makes the settlement assert what it actually promises: every frozen hold was
     * accounted for, not merely that something moved.
     */
    @Transactional(readOnly = true)
    public int countFrozenHolds(String collaborationId) {
        return resolveHoldsForCollaboration(collaborationId, EscrowStatus.FROZEN).size();
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

        List<EscrowStatusResponse> results = new ArrayList<>();
        for (EscrowHold hold : requireFrozenHoldsForCollaboration(collaborationId)) {
            String idempotencyKey = "dispute-release:" + hold.getId();
            var outcome =
                    escrowBackend.release(
                            new EscrowBackend.ReleaseCommand(
                                    hold.getId(),
                                    payeeUserId,
                                    hold.getAmount(),
                                    hold.getCurrency(),
                                    referenceIdFor(hold),
                                    TxnReferenceType.ESCROW_HOLD,
                                    hold.getId(),
                                    "Dispute-resolved release for collaboration " + collaborationId,
                                    idempotencyKey));

            hold.markReleased(outcome.releaseTxnId());
            escrowHoldRepository.save(hold);
            markMilestoneReleasedIfPresent(hold, outcome.releaseTxnId(), idempotencyKey);

            // D14 Doc#2 — same atomic-with-release wiring as the happy-path release() above.
            // [B8 fix] see safelyCreateServiceInvoice javadoc.
            safelyCreateServiceInvoice(hold, collaboration, outcome.releaseTxnId());
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

        List<EscrowStatusResponse> results = new ArrayList<>();
        for (EscrowHold hold : requireFrozenHoldsForCollaboration(collaborationId)) {
            String idempotencyKey = "dispute-refund:" + hold.getId();
            var outcome =
                    escrowBackend.refund(
                            new EscrowBackend.RefundCommand(
                                    hold.getWorkspaceId(),
                                    hold.getId(),
                                    hold.getAmount(),
                                    hold.getCurrency(),
                                    "Dispute-resolved refund for collaboration " + collaborationId,
                                    idempotencyKey));

            hold.markRefunded(outcome.refundTxnId());
            escrowHoldRepository.save(hold);
            markMilestoneRefundedIfPresent(hold, outcome.refundTxnId(), idempotencyKey);
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

        List<EscrowStatusResponse> results = new ArrayList<>();
        for (EscrowHold hold : requireFrozenHoldsForCollaboration(collaborationId)) {
            BigDecimal creatorAmount =
                    hold.getAmount()
                            .multiply(creatorSplitPercent)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal brandAmount = hold.getAmount().subtract(creatorAmount);

            String releaseIdempotencyKey = "dispute-split-release:" + hold.getId();
            String creditLegId = null;
            if (creatorAmount.signum() > 0) {
                var releaseOutcome =
                        escrowBackend.release(
                                new EscrowBackend.ReleaseCommand(
                                        hold.getId(),
                                        payeeUserId,
                                        creatorAmount,
                                        hold.getCurrency(),
                                        referenceIdFor(hold),
                                        TxnReferenceType.ESCROW_HOLD,
                                        hold.getId(),
                                        "Dispute-resolved split release for collaboration " + collaborationId,
                                        releaseIdempotencyKey));
                creditLegId = releaseOutcome.releaseTxnId();
            }

            String refundLegId = null;
            if (brandAmount.signum() > 0) {
                String refundIdempotencyKey = "dispute-split-refund:" + hold.getId();
                var refundOutcome =
                        escrowBackend.refund(
                                new EscrowBackend.RefundCommand(
                                        hold.getWorkspaceId(),
                                        hold.getId(),
                                        brandAmount,
                                        hold.getCurrency(),
                                        "Dispute-resolved split refund for collaboration " + collaborationId,
                                        refundIdempotencyKey));
                refundLegId = refundOutcome.refundTxnId();
            }

            // [Kavya QA, adjacent to CR-36] A 0% creatorSplitPercent is a legitimate settlement —
            // the creator gets nothing, the brand is refunded in full — and creatorSplitPercent
            // validation above permits 0. On that path creatorAmount.signum() > 0 is false, no
            // ESCROW_RELEASE ever posts, and creditLegId stays null. This used to still call
            // hold.markReleased(...) with a synthetic "dispute-split:<id>" string that corresponds
            // to NO ledger transaction at all — RELEASED means "the creator was paid" everywhere
            // else this status is read (creator-facing payment state, reporting, released-volume
            // analytics), so a 100%-to-brand split was recorded and audited as a release. The
            // terminal status must track what actually happened: a real creator credit -> RELEASED
            // with that credit's real txn id; no creator credit -> REFUNDED with the REAL refund
            // txn id captured above (never a synthetic id).
            if (creditLegId != null) {
                hold.markReleased(creditLegId);
            } else {
                hold.markRefunded(refundLegId);
            }
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
     * release is blocked unless every {@link Deliverable} in this milestone's <b>collaboration</b>
     * has reached a status that satisfies the milestone's {@code release_condition}.
     *
     * <p>[CR-51 step 2 / Priya's Option B ruling] The gate is COLLABORATION-scoped, not
     * milestone-scoped: it reads all deliverables for {@link PaymentMilestone#getCollaborationId()}
     * via {@code findByCollaborationIdOrderBySlotIndexAsc}, not {@code deliverables.milestone_id}.
     * Milestones (brand-chosen payment installments, keyed by {@code sequenceNo}) and deliverables
     * (proposal-metadata content slots, keyed by {@code slotIndex}) come from independent sources
     * with no linking field and no principled N:M mapping between them — {@code milestone_id} on
     * {@code Deliverable} stays permanently NULL by design; materialization does not set it. Because
     * deliverable statuses only move forward, gating a milestone release on the collaboration's full
     * deliverable set is strictly conservative: it never releases before the collaboration's content
     * is ready, and never starves a later milestone waiting on the same deliverables.
     *
     * <p>Discriminates on {@link PaymentMilestone#getCreatedAt()} vs. {@link
     * #releaseGateCutoverInstant} (config: {@code influora.escrow.release-gate.cutover-instant}),
     * per Swapnil's ruling — deliberately NOT on "has deliverables", which is the exact circular
     * condition that caused the original CR-51 bug (a guard that cannot see its own precondition
     * always scores "safe"). This cutover discrimination is unchanged by the collaboration re-key.
     *
     * <ul>
     *   <li>Cutover unset, or milestone {@code created_at} at/before cutover: legacy — fail open,
     *       unconditionally, regardless of whether the collaboration has deliverables. Lump-sum /
     *       product-seeding milestones predating per-slot deliverable tracking must not suddenly
     *       start throwing {@code RELEASE_CONDITION_NOT_MET}.
     *   <li>Milestone {@code created_at} after cutover, collaboration has deliverables: gate
     *       normally (unchanged logic below).
     *   <li>Milestone {@code created_at} after cutover, collaboration has ZERO deliverables:
     *       CR-51 step 3 is DECIDED (Swapnil: forbid) — a new, post-cutover, escrow-funded deal may
     *       NOT have zero deliverables, so this throws {@code RELEASE_CONDITION_NOT_MET} rather
     *       than releasing. Reusing that code (not a new one) keeps {@link
     *       #isExpectedReleaseSkip(String)} whitelisting it, so {@link
     *       #tryReleaseOnApproval(String, String)} still skips gracefully (approval succeeds, hold
     *       stays FUNDED) instead of the approve() transaction blowing up. {@code refund()} remains
     *       the recovery escape hatch — it does not go through this gate. Staying inside the
     *       cutover guard above means this is unreachable for legacy pre-cutover milestones, and a
     *       blank/unset cutover instant disables the whole gate, so this ships DISABLED BY DEFAULT.
     * </ul>
     */
    private void assertReleaseConditionSatisfied(PaymentMilestone milestone) {
        if (!isPostCutover(milestone)) {
            // Legacy milestone (cutover unset, or created_at at/before the cutover): keep failing
            // open unconditionally, even if the collaboration has deliverables — this is the
            // pre-existing production behavior and must not change for deals already in flight.
            return;
        }
        List<Deliverable> deliverables =
                deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(
                        milestone.getCollaborationId());
        if (deliverables.isEmpty()) {
            // CR-51 step 3 (DECIDED — Swapnil: forbid): a new, post-cutover, escrow-funded deal
            // may NOT have zero deliverables. Block the release rather than fail open.
            log.warn(
                    "Escrow release blocked: milestone {} was created after the CR-51 release-gate"
                            + " cutover ({}) but its collaboration {} has no deliverables to satisfy"
                            + " the release condition. Milestone created_at={}",
                    milestone.getId(),
                    releaseGateCutoverInstant,
                    milestone.getCollaborationId(),
                    milestone.getCreatedAt());
            throw new ApiException(
                    "RELEASE_CONDITION_NOT_MET",
                    "Escrow cannot release: collaboration "
                            + milestone.getCollaborationId()
                            + " has no deliverables to satisfy the release condition.",
                    HttpStatus.CONFLICT);
        }
        ReleaseCondition condition =
                milestone.getReleaseCondition() != null
                        ? milestone.getReleaseCondition()
                        : ReleaseCondition.ON_POSTED;
        Set<DeliverableStatus> satisfying = satisfyingStatusesFor(condition);
        boolean allSatisfied =
                deliverables.stream().map(Deliverable::getStatus).allMatch(satisfying::contains);
        if (!allSatisfied) {
            throw new ApiException(
                    "RELEASE_CONDITION_NOT_MET",
                    "Milestone release_condition (" + condition + ") is not yet satisfied by its deliverable(s)",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * True only when a cutover is configured AND this milestone's {@code created_at} is strictly
     * after it. A milestone with a {@code null created_at} (should not happen in practice — the
     * column is {@code NOT NULL}) is treated as pre-cutover / legacy, not post-cutover, so a data
     * anomaly fails open rather than unexpectedly gating a release.
     */
    private boolean isPostCutover(PaymentMilestone milestone) {
        if (releaseGateCutoverInstant == null || milestone.getCreatedAt() == null) {
            return false;
        }
        return milestone.getCreatedAt().isAfter(releaseGateCutoverInstant);
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

    /**
     * [FIX: escrow-frozen-hold-fix-spec, Fix 1] Row-locked FROZEN holds for a collaboration, using
     * the same fallback-aware {@link #resolveHoldsForCollaboration} lookup as
     * {@link #findFundedHoldsForCollaboration}. Before this fix, this method queried
     * {@code findByCollaborationIdAndStatus} directly with NO milestone fallback, while its sibling
     * did have one — for the (common, ordinary-brand-flow) case of a hold whose
     * {@code collaboration_id} is null, this returned an empty list even though the hold was very
     * much real, FROZEN, and tied to this collaboration via its milestone. {@code
     * adminReleaseForDispute}/{@code adminRefundForDispute}/{@code adminSplitForDispute} would then
     * iterate zero holds and {@code DisputeService.resolveDispute} would mark the dispute resolved
     * anyway — money frozen forever, books say otherwise. One lookup, not two that can drift.
     */
    private List<EscrowHold> requireFrozenHoldsForCollaboration(String collaborationId) {
        List<EscrowHold> frozen = resolveHoldsForCollaboration(collaborationId, EscrowStatus.FROZEN);
        List<EscrowHold> locked = new ArrayList<>();
        for (EscrowHold snapshot : frozen) {
            EscrowHold hold = requireHoldForUpdate(snapshot.getId());
            // [SEC: Kabir gate on CR-35, HIGH-1] Re-check the status AFTER taking the row lock.
            // `resolveHoldsForCollaboration` filtered on an UNLOCKED read, so between that read and
            // this lock another transaction may already have moved the hold out of FROZEN. Acting on
            // the stale snapshot pays the same hold twice: with two active disputes on one
            // collaboration the two settlements use DIFFERENT idempotency keys
            // (`dispute-refund:<id>` vs `dispute-release:<id>`), so `uq_wtx_idem` does not dedupe
            // them; `markReleased`/`markRefunded` are unguarded setters; the second dispute is a
            // different row so `@Version` never fires; and `WalletLedgerService.post` exempts the
            // clearing wallet from the balance check. Nothing else would have stopped it.
            //
            // This is the same re-check `freezeUnreleasedForDispute` already does after its own
            // lock. The asymmetry was harmless until CR-35: this loop returned an empty list for
            // every ordinary-flow hold, so the window was unreachable. Fix 1 made it reachable for
            // essentially every hold, which is why this landed in the same commit as Fix 1 and not
            // as a follow-up.
            if (hold.getStatus() == EscrowStatus.FROZEN) {
                locked.add(hold);
            }
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
        return resolveHoldsForCollaboration(collaborationId, EscrowStatus.FUNDED);
    }

    /**
     * [FIX: escrow-frozen-hold-fix-spec, Fix 1 — "one lookup, not two that can drift"] The single
     * status-parameterized lookup both {@link #findFundedHoldsForCollaboration} (FUNDED) and
     * {@link #requireFrozenHoldsForCollaboration} (FROZEN) now delegate to, instead of each
     * maintaining its own copy of the same rule. Queries the direct {@code collaboration_id} column
     * AND falls back to the milestone → collaboration link, because {@code EscrowHold}'s
     * {@code collaboration_id} is null for every hold that hasn't gone through
     * {@code EscrowHold#bindCollaboration} (in the main tree, only {@code
     * ConfirmLaunchExecutor}'s AI-launch path calls that) — see {@link #initiateFund}'s Fix 2
     * comment for the root-cause half of this fix. This repo has paid for two copies of one rule
     * drifting apart before (CR-05, CR-24, CR-30, CR-34); this is the same doctrine applied here,
     * for money-movement code where the drift silently produced permanently frozen escrow.
     */
    private List<EscrowHold> resolveHoldsForCollaboration(String collaborationId, EscrowStatus status) {
        Set<String> seen = new LinkedHashSet<>();
        List<EscrowHold> result = new ArrayList<>();
        for (EscrowHold hold :
                escrowHoldRepository.findByCollaborationIdAndStatus(collaborationId, status)) {
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
                    .filter(h -> h.getStatus() == status)
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

    /**
     * [CR-36 residual, {@code wiki/tech/escrow-cancelled-gate-spec.md}] Blocks {@link
     * #releaseInternal} from paying a creator on a collaboration that has been cancelled. Release
     * moves money FORWARD to the creator on a deal the platform has already marked dead — that is
     * the actual defect this closes.
     *
     * <p><b>Deliberately NOT folded into {@link #assertEscrowNotBlockedByDispute}</b>, even though
     * both guards sit in front of sibling methods on the same {@link Collaboration}. This check
     * must NEVER be added to {@code refund()}. Refund sends the money back to the brand that funded
     * it — it is the remedy for a cancelled collaboration, not an abuse of one. Blocking refund on
     * CANCELLED would strand every rupee held against a cancelled collaboration with no code path
     * left to return it, which is exactly the class of bug CR-35 was opened for. A guard that
     * recreates the bug it was written to prevent is worse than no guard, so this stays its own
     * method with its own name rather than becoming a CANCELLED branch inside the shared dispute
     * check.
     */
    private void assertReleaseNotBlockedByCancellation(Collaboration collaboration) {
        if (collaboration.getStatus() == CollaborationStatus.CANCELLED) {
            throw new ApiException(
                    "COLLABORATION_CANCELLED",
                    "This deal was cancelled and its escrow can no longer be released to the creator",
                    HttpStatus.CONFLICT);
        }
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
