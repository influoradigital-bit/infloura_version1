package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorCreditPack;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import com.influora.integration.razorpay.CheckoutSignatureVerifier;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.credits.BalanceView;
import com.influora.service.credits.CreatorCreditOrderService;
import com.influora.service.credits.CreatorCreditService;
import com.influora.web.dto.credits.CreatorCreditDtos.BalanceResponse;
import com.influora.web.dto.credits.CreatorCreditDtos.CostsInfo;
import com.influora.web.dto.credits.CreatorCreditDtos.CreateOrderRequest;
import com.influora.web.dto.credits.CreatorCreditDtos.CreateOrderResponse;
import com.influora.web.dto.credits.CreatorCreditDtos.MonthlyInfo;
import com.influora.web.dto.credits.CreatorCreditDtos.OrderHistoryItem;
import com.influora.web.dto.credits.CreatorCreditDtos.PackInfo;
import com.influora.web.dto.credits.CreatorCreditDtos.PaidExpiringItem;
import com.influora.web.dto.credits.CreatorCreditDtos.PendingInfo;
import com.influora.web.dto.credits.CreatorCreditDtos.VerifyOrderRequest;
import com.influora.web.dto.credits.CreatorCreditDtos.VerifyOrderResponse;
import com.influora.web.dto.credits.CreatorCreditDtos.WelcomeInfo;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §8, B18) — the creator-facing credits surface. Identity is
 * ALWAYS {@code creatorContext.requireCreatorProfile(principal)} + {@code principal.getUserId()}
 * (K-21) — never a path/query/body id. A brand principal gets 403; unauthenticated gets 401.
 * Meera consent is not required (this is not a Meera route).
 */
@RestController
@RequestMapping("/creator/credits")
public class CreatorCreditController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final CreatorContextService creatorContext;
    private final CreatorCreditService creditService;
    private final CreatorCreditOrderService orderService;
    private final CreatorCreditProperties creditProperties;
    private final CheckoutSignatureVerifier signatureVerifier;
    private final RazorpayClient razorpayClient;

    public CreatorCreditController(
            CreatorContextService creatorContext,
            CreatorCreditService creditService,
            CreatorCreditOrderService orderService,
            CreatorCreditProperties creditProperties,
            CheckoutSignatureVerifier signatureVerifier,
            RazorpayClient razorpayClient) {
        this.creatorContext = creatorContext;
        this.creditService = creditService;
        this.orderService = orderService;
        this.creditProperties = creditProperties;
        this.signatureVerifier = signatureVerifier;
        this.razorpayClient = razorpayClient;
    }

    /** {@code GET /creator/credits} — read-only. Flag off: {@code {enabled:false}} (200), never a 404 (this route itself always exists). */
    @GetMapping
    public ResponseEntity<ApiResponse<BalanceResponse>> balance(@AuthenticationPrincipal AuthPrincipal principal) {
        String creatorUserId = requireCreatorUserId(principal);
        if (!creditProperties.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.ok(BalanceResponse.disabled()));
        }

        BalanceView view = creditService.balance(creatorUserId);
        CreatorCreditPack pack = orderService.findActivePack("PACK_60").orElse(null);

        var response =
                new BalanceResponse(
                        true,
                        view.total(),
                        view.free(),
                        view.paid(),
                        view.dailyUsed(),
                        view.dailyCap(),
                        view.dailyResetsAt(),
                        view.nextMonthlyGrantAt(),
                        new WelcomeInfo(view.welcomeEligible(), view.welcomeGranted(), view.welcomeGrantedAt()),
                        new MonthlyInfo(view.monthlyPeriod(), view.monthlyGrantedThisPeriod()),
                        new PendingInfo(view.pendingWelcome(), view.pendingMonthly()),
                        view.paidExpiring().stream()
                                .map(e -> new PaidExpiringItem(e.credits(), e.expiresAt()))
                                .toList(),
                        pack == null
                                ? null
                                : new PackInfo(pack.getCode(), pack.getCredits(), pack.getPricePaise(), pack.isGstInclusive()),
                        new CostsInfo(
                                creditProperties.getTurnCost(),
                                creditProperties.getVoiceTurnCost(),
                                creditProperties.getBriefCost(),
                                creditProperties.getScriptCost(),
                                creditProperties.getProfileReviewCost()));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * {@code POST /creator/credits/orders} — the ONLY creator-credit route that spends nothing but
     * mints a real Razorpay order. Rate-limited by the {@code creator-credit-order} bucket
     * (USER-keyed) — see {@code AuthRateLimitFilter}.
     */
    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest body) {
        String creatorUserId = requireCreatorUserId(principal);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required (max " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters)",
                    HttpStatus.BAD_REQUEST);
        }
        CreateOrderResponse response = orderService.createOrder(creatorUserId, body.packCode(), idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * {@code POST /creator/credits/orders/{orderId}/verify} — SPEC.md B16/K-11. Ownership check,
     * then a constant-time signature check, then a SERVER-SIDE gateway fetch; credits ONLY via
     * {@code confirmPaid}, and only when Razorpay itself says paid. The client's {@code onSuccess}
     * callback alone is never trusted (K-11) — this endpoint is what actually credits.
     */
    @PostMapping("/orders/{orderId}/verify")
    public ResponseEntity<ApiResponse<VerifyOrderResponse>> verify(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String orderId,
            @Valid @RequestBody VerifyOrderRequest body) {
        String creatorUserId = requireCreatorUserId(principal);
        CreatorCreditOrder order =
                orderService
                        .findOwnedOrder(creatorUserId, orderId)
                        .orElseThrow(
                                () -> new ApiException("CREDIT_ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));

        if (order.getStatus() != CreatorCreditOrderStatus.CREDITED) {
            boolean validSignature =
                    signatureVerifier.verify(order.getRazorpayOrderId(), body.razorpayPaymentId(), body.razorpaySignature());
            if (!validSignature) {
                throw new ApiException(
                        "INVALID_CHECKOUT_SIGNATURE", "Checkout signature verification failed", HttpStatus.BAD_REQUEST);
            }

            RazorpayClient.OrderPaymentState state = razorpayClient.fetchOrderPaymentState(order.getRazorpayOrderId());
            if (state.isPaid()) {
                orderService.confirmPaid(
                        order.getId(),
                        body.razorpayPaymentId(),
                        order.getRazorpayOrderId(),
                        state.amountPaidInPaise(),
                        state.currency());
            }
        }

        CreatorCreditOrder reloaded = orderService.findOwnedOrder(creatorUserId, orderId).orElseThrow();
        int balance = creditService.balance(creatorUserId).total();
        String status = reloaded.getStatus() == CreatorCreditOrderStatus.CREDITED ? "CREDITED" : "PENDING";
        return ResponseEntity.ok(ApiResponse.ok(new VerifyOrderResponse(status, balance)));
    }

    /** {@code GET /creator/credits/orders} — the caller's own orders, newest first. */
    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<List<OrderHistoryItem>>> orders(@AuthenticationPrincipal AuthPrincipal principal) {
        String creatorUserId = requireCreatorUserId(principal);
        List<OrderHistoryItem> items =
                orderService.listOrders(creatorUserId).stream()
                        .map(
                                o ->
                                        new OrderHistoryItem(
                                                o.getId(),
                                                o.getCredits(),
                                                o.getAmountPaise(),
                                                o.getStatus().name(),
                                                o.getPaidAt(),
                                                null,
                                                o.getInvoiceNumber(),
                                                o.getTaxablePaise(),
                                                o.getCgstPaise(),
                                                o.getSgstPaise(),
                                                o.getIgstPaise()))
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    private String requireCreatorUserId(AuthPrincipal principal) {
        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        return profile.getUserId();
    }
}
