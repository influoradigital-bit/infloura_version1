package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.money.MoneyDtos.EscrowFundResponse;
import com.influora.web.dto.money.MoneyDtos.EscrowStatusResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final EscrowHoldRepository escrowHoldRepository;
    private final PaymentMilestoneRepository milestoneRepository;
    private final CampaignRepository campaignRepository;
    private final CollaborationRepository collaborationRepository;
    private final WalletLedgerService ledgerService;
    private final PlatformWalletService platformWalletService;
    private final WalletService walletService;
    private final BrandContextService brandContext;
    private final RazorpayClient razorpayClient;

    public EscrowService(
            EscrowHoldRepository escrowHoldRepository,
            PaymentMilestoneRepository milestoneRepository,
            CampaignRepository campaignRepository,
            CollaborationRepository collaborationRepository,
            WalletLedgerService ledgerService,
            PlatformWalletService platformWalletService,
            WalletService walletService,
            BrandContextService brandContext,
            RazorpayClient razorpayClient) {
        this.escrowHoldRepository = escrowHoldRepository;
        this.milestoneRepository = milestoneRepository;
        this.campaignRepository = campaignRepository;
        this.collaborationRepository = collaborationRepository;
        this.ledgerService = ledgerService;
        this.platformWalletService = platformWalletService;
        this.walletService = walletService;
        this.brandContext = brandContext;
        this.razorpayClient = razorpayClient;
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
            PaymentMilestone milestone =
                    milestoneRepository
                            .findById(milestoneId)
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
                        hold.getIdempotencyKey(),
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

        return hold;
        // NOTE: EscrowFundedEvent (credit-reset hook for Meera, V14) is out of scope for this
        // slice per the task boundary (do not touch service/meera or AI entities); a follow-on
        // change should publish it here once BrandAiCredit exists.
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

        EscrowHold hold = requireHold(milestone.getEscrowHoldId());
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND);
        }
        if (hold.getStatus() == EscrowStatus.RELEASED) {
            return toStatusResponse(hold); // idempotent no-op
        }
        requireStatus(hold, EscrowStatus.FUNDED, "release");

        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet payeeWallet = walletService.requireOrCreateUserWallet(payeeUserId);

        String idempotencyKey = "release:" + hold.getId();
        var posting =
                ledgerService.post(
                        clearingWallet.getId(),
                        payeeWallet.getId(),
                        hold.getAmount(),
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

        return toStatusResponse(hold);
    }

    /** Refunds a FUNDED escrow hold back to the brand's own wallet (e.g. cancelled campaign). */
    @Transactional
    public EscrowStatusResponse refund(AuthPrincipal principal, String workspaceId, String escrowHoldId) {
        WorkspaceMember member = brandContext.requireMember(principal, workspaceId);
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);

        EscrowHold hold = requireHold(escrowHoldId);
        if (!hold.getWorkspaceId().equals(workspaceId)) {
            throw new ApiException("ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND);
        }
        if (hold.getStatus() == EscrowStatus.REFUNDED) {
            return toStatusResponse(hold); // idempotent no-op
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
        EscrowHold hold =
                escrowHoldRepository
                        .findByIdAndWorkspaceId(escrowHoldId, workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));
        return toStatusResponse(hold);
    }

    private EscrowHold requireHold(String escrowHoldId) {
        return escrowHoldRepository
                .findById(escrowHoldId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "ESCROW_NOT_FOUND", "Escrow hold not found", HttpStatus.NOT_FOUND));
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
