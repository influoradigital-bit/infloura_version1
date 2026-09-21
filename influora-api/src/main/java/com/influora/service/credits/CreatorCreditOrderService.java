package com.influora.service.credits;

import com.influora.common.AfterCommit;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.CreatorCreditProperties;
import com.influora.config.RazorpayProperties;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorCreditPack;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.repository.CreatorCreditPackRepository;
import com.influora.web.dto.credits.CreatorCreditDtos.CreateOrderResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §5.4) — cloned from {@code WalletTopUpService}. Owns {@code
 * creator_credit_orders} end to end: order creation (flag-gated), and the single crediting path
 * {@link #confirmPaid} (never flag-gated, K-24) reached by the webhook, {@code /verify} and the
 * reconciliation job.
 */
@Service
public class CreatorCreditOrderService {

    private static final Logger log = LoggerFactory.getLogger(CreatorCreditOrderService.class);

    /** Distinguishes a creator-credit Razorpay order from an escrow/top-up one in the shared webhook dispatch (K-07). */
    public static final String RECEIPT_PREFIX = "ccr:";

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final CreatorCreditOrderRepository orderRepository;
    private final CreatorCreditPackRepository packRepository;
    private final CreatorCreditService creatorCreditService;
    private final CreatorCreditAccountInitializer accountInitializer;
    private final CreatorCreditInvoiceApplier invoiceApplier;
    private final RazorpayClient razorpayClient;
    private final RazorpayProperties razorpayProperties;
    private final CreatorCreditProperties creditProperties;
    private final Clock clock;

    public CreatorCreditOrderService(
            CreatorCreditOrderRepository orderRepository,
            CreatorCreditPackRepository packRepository,
            CreatorCreditService creatorCreditService,
            CreatorCreditAccountInitializer accountInitializer,
            CreatorCreditInvoiceApplier invoiceApplier,
            RazorpayClient razorpayClient,
            RazorpayProperties razorpayProperties,
            CreatorCreditProperties creditProperties,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.packRepository = packRepository;
        this.creatorCreditService = creatorCreditService;
        this.accountInitializer = accountInitializer;
        this.invoiceApplier = invoiceApplier;
        this.razorpayClient = razorpayClient;
        this.razorpayProperties = razorpayProperties;
        this.creditProperties = creditProperties;
        this.clock = clock;
    }

    /**
     * SPEC.md §5.4 — flag-gated (404 when off). The amount is ALWAYS the pack's own server-stored
     * price (K-09, K-25) — {@code packCode} is the only client input this method trusts; any other
     * body field (a client-sent {@code amountPaise}/{@code credits}) is simply never read.
     */
    @Transactional
    public CreateOrderResponse createOrder(String creatorUserId, String packCode, String idempotencyKey) {
        if (!creditProperties.isEnabled()) {
            throw new ApiException(
                    "FEATURE_DISABLED", "Creator credits are currently disabled", HttpStatus.NOT_FOUND);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required (max " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters)",
                    HttpStatus.BAD_REQUEST);
        }

        var existing = orderRepository.findByCreatorUserIdAndIdempotencyKey(creatorUserId, idempotencyKey);
        if (existing.isPresent()) {
            return toCreateOrderResponse(existing.get());
        }

        // fk_cco_account (F-13/F-21): creator_credit_orders.creator_user_id FK's to
        // creator_credit_accounts, but that row is otherwise only ever created lazily by
        // charge()/claimVoiceSpeak()/grantWelcome() under the account lock. A creator who has
        // never sent a Meera turn (no IG connection, or simply hasn't chatted yet) and taps Buy
        // first would otherwise hit a raw FK violation here. ensureAccount is itself race-safe
        // (saveAndFlush + DataIntegrityViolationException catch, see
        // CreatorCreditAccountInitializer's own javadoc for exactly why a plain try/catch is not
        // enough on its own) so this is safe to call unconditionally, every time.
        try {
            accountInitializer.ensureAccount(creatorUserId);
        } catch (UnexpectedRollbackException racedAway) {
            // Review finding #16 point 1 — the SAME race CreatorCreditService#lockAccount already
            // guards against: two concurrent first-Buy requests for this creator can both pass
            // ensureAccount's existsById fast path, and the loser's own REQUIRES_NEW commit then
            // fails with this exception despite ensureAccount's internal catch (see that class's
            // javadoc) — even though the row it wanted now exists, exactly as intended.
            log.debug(
                    "CreatorCreditOrderService#createOrder: account for {} already created concurrently",
                    creatorUserId);
        }

        CreatorCreditPack pack =
                packRepository
                        .findByCodeAndActiveTrue(packCode)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "UNKNOWN_CREDIT_PACK", "No such active credit pack", HttpStatus.BAD_REQUEST));

        CreatorCreditOrder order = CreatorCreditOrder.newPending(Ulids.newUlid(), creatorUserId, pack, idempotencyKey);
        try {
            // Review finding #16 point 2 — saveAndFlush (not a plain save left to flush at commit)
            // so a second concurrent request with the SAME Idempotency-Key, which raced past the
            // replay check above before either committed, fails HERE on uk_cco_idem rather than
            // going on to mint a second, orphaned Razorpay order for the same logical request.
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException raced) {
            log.debug(
                    "CreatorCreditOrderService#createOrder: idempotency key {} for creator {} raced —"
                            + " returning the concurrently-created order instead of minting a second"
                            + " Razorpay order",
                    idempotencyKey,
                    creatorUserId);
            return orderRepository
                    .findByCreatorUserIdAndIdempotencyKey(creatorUserId, idempotencyKey)
                    .map(this::toCreateOrderResponse)
                    .orElseThrow(() -> raced);
        }

        BigDecimal amountRupees = BigDecimal.valueOf(pack.getPricePaise()).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
        var rzpOrder = razorpayClient.createOrder(amountRupees, "INR", RECEIPT_PREFIX + order.getId());
        order.setRazorpayOrderId(rzpOrder.orderId());
        orderRepository.save(order);

        return toCreateOrderResponse(order);
    }

    private CreateOrderResponse toCreateOrderResponse(CreatorCreditOrder order) {
        return new CreateOrderResponse(
                order.getId(),
                order.getRazorpayOrderId(),
                order.getAmountPaise(),
                order.getCurrency(),
                order.getCredits(),
                razorpayProperties.getKeyId());
    }

    /**
     * SPEC.md §5.4 — the single crediting path. NOT flag-gated (K-24): a purchase always credits,
     * even if {@code CREATOR_CREDITS_ENABLED} has since been flipped off. Reached from the
     * webhook, {@code /verify} (after a gateway fetch confirms paid) and the reconciliation job —
     * all three pass the SAME already-verified amount/currency, never a client-supplied one.
     */
    @Transactional
    public CreatorCreditOrder confirmPaid(
            String orderId, String paymentId, String razorpayOrderId, Long amountPaise, String currency) {
        CreatorCreditOrder order =
                orderRepository
                        .findByIdForUpdate(orderId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREDIT_ORDER_NOT_FOUND", "Creator credit order not found", HttpStatus.NOT_FOUND));

        if (order.getStatus() == CreatorCreditOrderStatus.CREDITED) {
            return order; // idempotent no-op — exactly-once (A26)
        }

        // The caller (webhook/verify/reconciliation) already resolves THIS row via a unique
        // handle of its own (the receipt prefix, the path {orderId}, or this row's own persisted
        // razorpay_order_id) before ever calling here — razorpayOrderId is a defense-in-depth
        // cross-check, enforced only when the caller actually has one to offer. A payment.captured
        // webhook delivery that carries no order entity at all (Razorpay's documented shape gap —
        // see RazorpayWebhookController#dispatchFundingEventIfResolvable) passes null here and is
        // not rejected on that basis alone.
        if (razorpayOrderId != null && !razorpayOrderId.equals(order.getRazorpayOrderId())) {
            log.error(
                    "CreatorCreditOrderService#confirmPaid: razorpay_order_id mismatch for order {} —"
                            + " expected {}, got {}",
                    orderId,
                    order.getRazorpayOrderId(),
                    razorpayOrderId);
            throw new ApiException(
                    "CREDIT_ORDER_MISMATCH", "Razorpay order id does not match this credit order", HttpStatus.CONFLICT);
        }
        validateAmount(order, amountPaise, currency);

        Instant paidAt = clock.instant();
        String grantId = creatorCreditService.creditPurchase(order, paidAt);

        try {
            order.markCredited(paymentId, grantId, paidAt);
            orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException raced) {
            // uk_cco_rzp_payment — this Razorpay payment id already credited a DIFFERENT order
            // (K-10: the same captured payment can never credit two orders). The PAID grant just
            // minted above is now orphaned rather than referenced by a CREDITED order row; that is
            // the intended fail-safe shape (credit given once, order state never lies about which
            // payment funded it) — a genuine double-delivery of the SAME order+payment pair is
            // already caught by the CREDITED short-circuit above, before this branch is reachable.
            log.error(
                    "CreatorCreditOrderService#confirmPaid: razorpay_payment_id {} was already used to"
                            + " credit a different order — rejecting order {}",
                    paymentId,
                    orderId,
                    raced);
            throw new ApiException(
                    "CREDIT_ORDER_PAYMENT_REUSED", "This payment has already credited a different order", HttpStatus.CONFLICT);
        }

        // F-14/F-22 fix: invoice numbering must NEVER be able to undo a credit. It used to run
        // synchronously, inside THIS transaction, before markCredited — a numbering failure could
        // mark this whole transaction rollback-only and roll back the PAID grant with it (see
        // CreatorCreditInvoiceApplier's javadoc). AfterCommit defers it to a brand-new,
        // fully-separate transaction that starts only once this transaction has actually
        // committed, so nothing it does can ever affect the credit above.
        String committedOrderId = order.getId();
        AfterCommit.run(
                "creator-credit-order invoice numbering (order " + committedOrderId + ")",
                () -> "orderId=" + committedOrderId,
                () -> invoiceApplier.applyBestEffort(committedOrderId));

        return order;
    }

    private static void validateAmount(CreatorCreditOrder order, Long amountPaise, String currency) {
        boolean amountMatches = amountPaise != null && amountPaise == order.getAmountPaise();
        boolean currencyMatches = currency != null && currency.equalsIgnoreCase(order.getCurrency());
        if (!amountMatches || !currencyMatches) {
            log.error(
                    "CreatorCreditOrderService#confirmPaid: amount/currency mismatch for order {}:"
                            + " expected {} {}, got {} {}",
                    order.getId(),
                    order.getAmountPaise(),
                    order.getCurrency(),
                    amountPaise,
                    currency);
            throw new ApiException(
                    "CREDIT_ORDER_AMOUNT_MISMATCH",
                    "Reported payment amount/currency does not match this credit order",
                    HttpStatus.CONFLICT);
        }
    }

    // ------------------------------------------------------------------
    // K-22 writer-boundary reads — CreatorCreditController and the reconciliation job go through
    // these rather than injecting CreatorCreditOrderRepository/CreatorCreditPackRepository
    // themselves (A33: no class outside the credit services touches a credit repository).
    // ------------------------------------------------------------------

    /** Ownership-scoped fetch (K-21) — another creator's order id is indistinguishable from one that does not exist. */
    @Transactional(readOnly = true)
    public Optional<CreatorCreditOrder> findOwnedOrder(String creatorUserId, String orderId) {
        return orderRepository.findByIdAndCreatorUserId(orderId, creatorUserId);
    }

    @Transactional(readOnly = true)
    public List<CreatorCreditOrder> listOrders(String creatorUserId) {
        return orderRepository.findByCreatorUserIdOrderByCreatedAtDesc(creatorUserId);
    }

    @Transactional(readOnly = true)
    public Optional<CreatorCreditPack> findActivePack(String packCode) {
        return packRepository.findByCodeAndActiveTrue(packCode);
    }

    /** The reconciliation job's own sweep query (K-08) — windowed exactly like {@code WalletTopUpRepository#findByStatusAndCreatedAtBetween}. */
    @Transactional(readOnly = true)
    public List<CreatorCreditOrder> findStalePending(Instant after, Instant before) {
        return orderRepository.findByStatusAndCreatedAtBetween(CreatorCreditOrderStatus.PENDING, after, before);
    }

    /** F-6: the reconciliation job's give-up sweep — see {@link CreatorCreditOrderRepository#findByStatusAndCreatedAtBefore}. */
    @Transactional(readOnly = true)
    public List<CreatorCreditOrder> findGivenUpPending(Instant before) {
        return orderRepository.findByStatusAndCreatedAtBefore(CreatorCreditOrderStatus.PENDING, before);
    }
}
