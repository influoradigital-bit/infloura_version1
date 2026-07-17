package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTopUp;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTopUpStatus;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.WalletTopUpRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.money.MoneyDtos.WalletTopUpResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand wallet top-up — B0, the LOCKED foundation P0 in {@code
 * wiki/tech/BRAND_EXECUTION_PLAN.md} ("brand tops up wallet via Razorpay payment link -> brand
 * wallet, booked as customer-deposit LIABILITY, not revenue"). Every money movement goes through
 * {@link WalletLedgerService#post} — this service never mutates a {@link Wallet} balance directly
 * (Guardrail 1 / C-3), same discipline as {@link EscrowService}.
 *
 * <p>Deliberately a separate service/table from {@link EscrowService}/{@code escrow_holds} — a
 * top-up has no campaign/milestone/collaboration linkage and only two terminal states
 * (PENDING/CREDITED), not the 5-state escrow machine.
 *
 * <p><b>Guardrail 1 note:</b> unlike escrow's fund amount (always re-derived server-side from a
 * campaign/milestone row), a top-up's {@code amount} genuinely originates from the brand's own
 * choice of how much to deposit into their own wallet — there is no persisted "correct" amount to
 * re-derive it from. The amount IS caller-supplied at {@link #initiateTopUp}, exactly like any
 * real payment-gateway top-up form; the money is never trusted to actually move on that basis
 * alone, though — {@link #confirmCredited} re-validates the persisted {@link WalletTopUp#getAmount()}
 * against what Razorpay's webhook reports as actually captured before crediting anything, same
 * cross-check discipline as {@code EscrowService#validateWebhookAmount}.
 *
 * <p><b>No fee is ever charged here.</b> This is top-up only; the 10% publish-time fee cut is B1,
 * a separate gated wave per the LOCKED ruling's COO gate (Kabir OWASP PASS on B0 required before
 * any B1 code ships).
 */
@Service
public class WalletTopUpService {

    private static final Logger log = LoggerFactory.getLogger(WalletTopUpService.class);

    /**
     * Prefix on the Razorpay order {@code receipt} field that distinguishes a wallet top-up order
     * from an escrow-fund order in the shared webhook dispatch ({@code
     * RazorpayWebhookController}) — escrow orders use the bare {@code EscrowHold} id as their
     * receipt (unprefixed), so this prefix can never collide with one.
     */
    public static final String RECEIPT_PREFIX = "topup:";

    private final WalletTopUpRepository topUpRepository;
    private final WalletService walletService;
    private final WalletLedgerService ledgerService;
    private final PlatformWalletService platformWalletService;
    private final BrandContextService brandContext;
    private final WorkspaceRepository workspaceRepository;
    private final RazorpayClient razorpayClient;

    public WalletTopUpService(
            WalletTopUpRepository topUpRepository,
            WalletService walletService,
            WalletLedgerService ledgerService,
            PlatformWalletService platformWalletService,
            BrandContextService brandContext,
            WorkspaceRepository workspaceRepository,
            RazorpayClient razorpayClient) {
        this.topUpRepository = topUpRepository;
        this.walletService = walletService;
        this.ledgerService = ledgerService;
        this.platformWalletService = platformWalletService;
        this.brandContext = brandContext;
        this.workspaceRepository = workspaceRepository;
        this.razorpayClient = razorpayClient;
    }

    /**
     * Creates (or replays, if {@code idempotencyKey} was already used) a PENDING top-up order and
     * a Razorpay order for the brand to pay. The wallet is only ever credited by {@link
     * #confirmCredited} against a verified webhook — never here. OWNER/ADMIN only (brand-context
     * role check, mirrors {@code EscrowService#initiateFund}).
     */
    @Transactional
    public WalletTopUpResponse initiateTopUp(
            AuthPrincipal principal, BigDecimal amount, String pan, String gstin, String idempotencyKey) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember member = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(member, MemberRole.OWNER, MemberRole.ADMIN);

        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(
                    "INVALID_TOPUP_AMOUNT", "Top-up amount must be positive", HttpStatus.BAD_REQUEST);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required",
                    HttpStatus.BAD_REQUEST);
        }

        var existing = topUpRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            WalletTopUp prior = existing.get();
            if (!prior.getWorkspaceId().equals(workspace.getId())) {
                // Never replay another workspace's order for a colliding key.
                throw new ApiException(
                        "IDEMPOTENCY_KEY_CONFLICT",
                        "Idempotency-Key was already used for a different workspace",
                        HttpStatus.CONFLICT);
            }
            return toResponse(prior);
        }

        // Confirms the workspace actually has a wallet row to credit later (created at brand
        // signup — AuthService#register) before we bother creating a Razorpay order at all.
        walletService.requireWorkspaceWallet(workspace.getId());

        // CFO constraint (LOCKED ruling item 6): capture PAN/GSTIN for later TDS reconciliation.
        // Fill-if-blank only — never overwrites already-verified KYC values (Workspace#applyTopUpTaxIds).
        if (pan != null || gstin != null) {
            workspace.applyTopUpTaxIds(pan, gstin);
            workspaceRepository.save(workspace);
        }

        WalletTopUp topUp =
                WalletTopUp.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspace.getId())
                        .amount(amount)
                        .currency("INR")
                        .idempotencyKey(idempotencyKey)
                        .build();
        topUpRepository.save(topUp);

        var order = razorpayClient.createOrder(amount, "INR", RECEIPT_PREFIX + topUp.getId());
        topUp.setRazorpayOrderId(order.orderId());
        topUpRepository.save(topUp);

        return toResponse(topUp);
    }

    /**
     * Confirms a wallet credit on a verified Razorpay webhook (never on the client's
     * order-creation response). Moves money via the ledger: DEBIT platform clearing wallet, CREDIT
     * brand workspace wallet — a customer-deposit liability landing in the brand's own spendable
     * balance, never platform revenue. Idempotent — a duplicate webhook delivery replays the same
     * result via both this row's own PENDING/CREDITED check and the ledger's own idempotency key
     * (uq_wtx_idem), never double-credits.
     *
     * <p>[SEC: amount cross-check] Same discipline as {@code EscrowService#confirmFunded}: the
     * webhook's reported captured amount/currency must match this order's persisted {@code amount}
     * exactly before any credit is applied. A mismatch (or a webhook missing an amount entirely)
     * aborts the transition and requires manual review — never silently accepted.
     */
    @Transactional
    public WalletTopUp confirmCredited(
            String topUpId, String gatewayRef, Long webhookAmountInPaise, String webhookCurrency) {
        WalletTopUp topUp =
                topUpRepository
                        .findById(topUpId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "TOPUP_NOT_FOUND",
                                                "Wallet top-up order not found",
                                                HttpStatus.NOT_FOUND));
        if (topUp.getStatus() == WalletTopUpStatus.CREDITED) {
            return topUp; // already applied — idempotent no-op
        }

        validateWebhookAmount(topUp, webhookAmountInPaise, webhookCurrency);

        Wallet clearingWallet = platformWalletService.requireClearingWallet();
        Wallet brandWallet = walletService.requireWorkspaceWallet(topUp.getWorkspaceId());

        // [SEC: Kabir OWASP CRITICAL] Derive the ledger's own idempotency key from this top-up's
        // server-generated id (same RECEIPT_PREFIX convention used for the Razorpay order
        // receipt above) — never pass the raw client `Idempotency-Key` header
        // (topUp.getIdempotencyKey()) into WalletLedgerService#post. That header is shared,
        // unscoped input: a client reusing it across a top-up call and an escrow-fund call would
        // make the ledger's replay short-circuit return the wrong movement's rows. A retried
        // webhook delivery for this order still always maps to the same ledger posting, since the
        // key is derived from this row's immutable id.
        String ledgerIdempotencyKey = RECEIPT_PREFIX + topUp.getId();
        var posting =
                ledgerService.post(
                        clearingWallet.getId(),
                        brandWallet.getId(),
                        topUp.getAmount(),
                        topUp.getCurrency(),
                        WalletTransactionType.DEPOSIT,
                        TxnReferenceType.DEPOSIT_ORDER,
                        topUp.getId(),
                        "Wallet top-up",
                        ledgerIdempotencyKey,
                        gatewayRef);

        topUp.markCredited(posting.creditLeg().getId());
        topUpRepository.save(topUp);
        return topUp;
    }

    private static void validateWebhookAmount(
            WalletTopUp topUp, Long webhookAmountInPaise, String webhookCurrency) {
        if (webhookAmountInPaise == null || webhookCurrency == null || webhookCurrency.isBlank()) {
            log.error(
                    "Wallet top-up webhook missing amount/currency for order {} (expected {} {}) —"
                            + " rejecting, manual review required",
                    topUp.getId(),
                    topUp.getAmount(),
                    topUp.getCurrency());
            throw new ApiException(
                    "TOPUP_WEBHOOK_AMOUNT_MISSING",
                    "Webhook payload did not include a payment amount/currency to verify",
                    HttpStatus.CONFLICT);
        }

        BigDecimal webhookAmount =
                BigDecimal.valueOf(webhookAmountInPaise).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal expectedAmount = topUp.getAmount().setScale(2, RoundingMode.UNNECESSARY);

        boolean amountMatches = webhookAmount.compareTo(expectedAmount) == 0;
        boolean currencyMatches = webhookCurrency.equalsIgnoreCase(topUp.getCurrency());

        if (!amountMatches || !currencyMatches) {
            log.error(
                    "Wallet top-up amount/currency mismatch for order {}: expected {} {}, webhook"
                            + " reported {} {} — requires manual review",
                    topUp.getId(),
                    expectedAmount,
                    topUp.getCurrency(),
                    webhookAmount,
                    webhookCurrency);
            throw new ApiException(
                    "TOPUP_AMOUNT_MISMATCH",
                    "Webhook-reported payment amount/currency does not match the expected top-up order",
                    HttpStatus.CONFLICT);
        }
    }

    private static WalletTopUpResponse toResponse(WalletTopUp topUp) {
        return new WalletTopUpResponse(
                topUp.getId(),
                topUp.getAmount(),
                topUp.getCurrency(),
                topUp.getRazorpayOrderId(),
                topUp.getStatus().name());
    }
}
