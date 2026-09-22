package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import com.influora.domain.enums.UserType;
import com.influora.integration.razorpay.CheckoutSignatureVerifier;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.credits.BalanceView;
import com.influora.service.credits.CreatorCreditOrderService;
import com.influora.service.credits.CreatorCreditService;
import com.influora.web.dto.credits.CreatorCreditDtos.VerifyOrderRequest;
import com.influora.web.dto.credits.CreatorCreditDtos.VerifyOrderResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md A30, design-kabir.md K-11) — {@code POST
 * /creator/credits/orders/{orderId}/verify} never grants credits from client-supplied data alone.
 * A bad checkout signature is rejected before the gateway is ever consulted; another creator's
 * order id is indistinguishable from a nonexistent one (K-21 IDOR discipline, defense-in-depth
 * with {@link CreatorCreditControllerIdorTest}); an unpaid gateway state leaves the order PENDING
 * with no credit; and only a Razorpay-confirmed paid state credits, via the exact same {@code
 * confirmPaid} the webhook itself uses (fed the GATEWAY's amount/currency, never the client's).
 *
 * <p>No {@code @WebMvcTest} harness exists for this controller family (see {@code
 * AuditLogControllerTest}'s javadoc) — this drives {@link CreatorCreditController} directly with
 * mocked collaborators, same pattern as every other controller test in this module.
 */
class CreatorCreditCheckoutVerifyTest {

    private static final String CREATOR_USER_ID = "01HCREATORVERIFY0000001";
    private static final String ORDER_ID = "order-verify-1";
    private static final String RAZORPAY_ORDER_ID = "order_rzp_verify1";

    private CreatorContextService creatorContext;
    private CreatorCreditService creditService;
    private CreatorCreditOrderService orderService;
    private CreatorCreditProperties creditProperties;
    private CheckoutSignatureVerifier signatureVerifier;
    private RazorpayClient razorpayClient;
    private CreatorCreditController controller;

    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        creatorContext = mock(CreatorContextService.class);
        creditService = mock(CreatorCreditService.class);
        orderService = mock(CreatorCreditOrderService.class);
        creditProperties = mock(CreatorCreditProperties.class);
        signatureVerifier = mock(CheckoutSignatureVerifier.class);
        razorpayClient = mock(RazorpayClient.class);
        controller =
                new CreatorCreditController(
                        creatorContext,
                        creditService,
                        orderService,
                        creditProperties,
                        signatureVerifier,
                        razorpayClient);

        principal = new AuthPrincipal(CREATOR_USER_ID, "creator@example.com", UserType.CREATOR, null);
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
    }

    private static CreatorCreditOrder pendingOrder() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn(ORDER_ID);
        when(order.getStatus()).thenReturn(CreatorCreditOrderStatus.PENDING);
        when(order.getRazorpayOrderId()).thenReturn(RAZORPAY_ORDER_ID);
        return order;
    }

    private static BalanceView zeroBalance() {
        return new BalanceView(0, 0, 0, 0, 30, null, null, false, false, null, null, false, 0, 0, List.of());
    }

    private static BalanceView sixtyBalance() {
        return new BalanceView(60, 0, 60, 0, 30, null, null, false, false, null, null, false, 0, 0, List.of());
    }

    @Test
    @DisplayName("A30/K-11: a bad checkout signature is rejected 400 — the gateway is never even consulted, no grant")
    void badSignature_rejected_noGrant() {
        CreatorCreditOrder order = pendingOrder();
        when(orderService.findOwnedOrder(CREATOR_USER_ID, ORDER_ID)).thenReturn(Optional.of(order));
        when(signatureVerifier.verify(anyString(), anyString(), anyString())).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.verify(principal, ORDER_ID, new VerifyOrderRequest("pay_x", "bad-sig")));

        assertEquals("INVALID_CHECKOUT_SIGNATURE", ex.getCode());
        verify(razorpayClient, never()).fetchOrderPaymentState(anyString());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A30/K-21: another creator's order id is indistinguishable from a nonexistent one — 404, no grant, signature never even checked")
    void anotherCreatorsOrder_notFound() {
        when(orderService.findOwnedOrder(CREATOR_USER_ID, "not-mine")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.verify(principal, "not-mine", new VerifyOrderRequest("pay_x", "sig")));

        assertEquals("CREDIT_ORDER_NOT_FOUND", ex.getCode());
        verify(signatureVerifier, never()).verify(any(), any(), any());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A30: a valid signature but a gateway state of created/attempted leaves the order PENDING — no grant")
    void validSignatureButGatewayNotPaid_staysPending() {
        CreatorCreditOrder order = pendingOrder();
        when(orderService.findOwnedOrder(CREATOR_USER_ID, ORDER_ID)).thenReturn(Optional.of(order));
        when(signatureVerifier.verify(RAZORPAY_ORDER_ID, "pay_x", "good-sig")).thenReturn(true);
        when(razorpayClient.fetchOrderPaymentState(RAZORPAY_ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("created", 0L, "INR"));
        when(creditService.balance(CREATOR_USER_ID)).thenReturn(zeroBalance());

        ResponseEntity<ApiResponse<VerifyOrderResponse>> response =
                controller.verify(principal, ORDER_ID, new VerifyOrderRequest("pay_x", "good-sig"));

        assertEquals("PENDING", response.getBody().data().status());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "A30: only a Razorpay-confirmed paid state credits — via the SAME confirmPaid the webhook"
                    + " uses, fed the GATEWAY's own amount/currency, never anything from the client body")
    void gatewayPaid_credits() {
        CreatorCreditOrder order = pendingOrder();
        CreatorCreditOrder creditedOrder = mock(CreatorCreditOrder.class);
        when(creditedOrder.getStatus()).thenReturn(CreatorCreditOrderStatus.CREDITED);
        when(orderService.findOwnedOrder(CREATOR_USER_ID, ORDER_ID))
                .thenReturn(Optional.of(order))
                .thenReturn(Optional.of(creditedOrder));
        when(signatureVerifier.verify(RAZORPAY_ORDER_ID, "pay_x", "good-sig")).thenReturn(true);
        when(razorpayClient.fetchOrderPaymentState(RAZORPAY_ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("paid", 24900L, "INR"));
        when(creditService.balance(CREATOR_USER_ID)).thenReturn(sixtyBalance());

        ResponseEntity<ApiResponse<VerifyOrderResponse>> response =
                controller.verify(principal, ORDER_ID, new VerifyOrderRequest("pay_x", "good-sig"));

        verify(orderService).confirmPaid(ORDER_ID, "pay_x", RAZORPAY_ORDER_ID, 24900L, "INR");
        assertEquals("CREDITED", response.getBody().data().status());
        assertEquals(60, response.getBody().data().balance());
    }

    @Test
    @DisplayName("A30: an already-CREDITED order skips the signature/gateway round-trip entirely (idempotent re-poll)")
    void alreadyCredited_skipsVerificationEntirely() {
        CreatorCreditOrder creditedOrder = mock(CreatorCreditOrder.class);
        when(creditedOrder.getStatus()).thenReturn(CreatorCreditOrderStatus.CREDITED);
        when(orderService.findOwnedOrder(CREATOR_USER_ID, ORDER_ID)).thenReturn(Optional.of(creditedOrder));
        when(creditService.balance(CREATOR_USER_ID)).thenReturn(sixtyBalance());

        ResponseEntity<ApiResponse<VerifyOrderResponse>> response =
                controller.verify(principal, ORDER_ID, new VerifyOrderRequest("pay_x", "whatever"));

        assertEquals("CREDITED", response.getBody().data().status());
        verify(signatureVerifier, never()).verify(any(), any(), any());
        verify(razorpayClient, never()).fetchOrderPaymentState(any());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "A30 (exact acceptance wording): verify never grants from client-supplied data alone —"
                    + " bad signature -> 400, no grant; another creator's order -> 404; valid signature"
                    + " but gateway created/attempted -> PENDING, no grant; gateway paid -> CREDITED"
                    + " (via the SAME confirmPaid the webhook uses, fed the GATEWAY's own amount)")
    void verifyNeverGrantsFromClientData() {
        // -- bad signature -> 400, no grant, gateway never even consulted.
        CreatorCreditOrder badSigOrder = pendingOrder();
        when(orderService.findOwnedOrder(CREATOR_USER_ID, "order-bad-sig")).thenReturn(Optional.of(badSigOrder));
        when(signatureVerifier.verify(anyString(), anyString(), eq("bad-sig"))).thenReturn(false);
        ApiException badSig =
                assertThrows(
                        ApiException.class,
                        () ->
                                controller.verify(
                                        principal, "order-bad-sig", new VerifyOrderRequest("pay_x", "bad-sig")));
        assertEquals("INVALID_CHECKOUT_SIGNATURE", badSig.getCode());
        verify(razorpayClient, never()).fetchOrderPaymentState(anyString());
        verify(orderService, never()).confirmPaid(eq("order-bad-sig"), any(), any(), any(), any());

        // -- another creator's order id -> 404, indistinguishable from nonexistent (K-21), signature
        // never even checked.
        when(orderService.findOwnedOrder(CREATOR_USER_ID, "not-mine")).thenReturn(Optional.empty());
        ApiException notFound =
                assertThrows(
                        ApiException.class,
                        () -> controller.verify(principal, "not-mine", new VerifyOrderRequest("pay_x", "sig")));
        assertEquals("CREDIT_ORDER_NOT_FOUND", notFound.getCode());

        // -- valid signature but the gateway itself still says created/attempted -> PENDING, no grant.
        CreatorCreditOrder pendingForPoll = pendingOrder();
        when(orderService.findOwnedOrder(CREATOR_USER_ID, ORDER_ID)).thenReturn(Optional.of(pendingForPoll));
        when(signatureVerifier.verify(RAZORPAY_ORDER_ID, "pay_x", "good-sig")).thenReturn(true);
        when(razorpayClient.fetchOrderPaymentState(RAZORPAY_ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("created", 0L, "INR"));
        when(creditService.balance(CREATOR_USER_ID)).thenReturn(zeroBalance());
        ResponseEntity<ApiResponse<VerifyOrderResponse>> pendingResponse =
                controller.verify(principal, ORDER_ID, new VerifyOrderRequest("pay_x", "good-sig"));
        assertEquals("PENDING", pendingResponse.getBody().data().status());
        verify(orderService, never()).confirmPaid(eq(ORDER_ID), any(), any(), any(), any());

        // -- gateway itself now says paid -> CREDITED, via the SAME confirmPaid the webhook uses,
        // fed the GATEWAY's own amount/currency — never anything the client's VerifyOrderRequest
        // body carries (K-11's whole point).
        CreatorCreditOrder creditedAfterPoll = mock(CreatorCreditOrder.class);
        when(creditedAfterPoll.getStatus()).thenReturn(CreatorCreditOrderStatus.CREDITED);
        when(orderService.findOwnedOrder(CREATOR_USER_ID, ORDER_ID))
                .thenReturn(Optional.of(pendingForPoll))
                .thenReturn(Optional.of(creditedAfterPoll));
        when(razorpayClient.fetchOrderPaymentState(RAZORPAY_ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("paid", 24900L, "INR"));
        when(creditService.balance(CREATOR_USER_ID)).thenReturn(sixtyBalance());

        ResponseEntity<ApiResponse<VerifyOrderResponse>> creditedResponse =
                controller.verify(principal, ORDER_ID, new VerifyOrderRequest("pay_x", "good-sig"));

        verify(orderService).confirmPaid(ORDER_ID, "pay_x", RAZORPAY_ORDER_ID, 24900L, "INR");
        assertEquals("CREDITED", creditedResponse.getBody().data().status());
        assertEquals(60, creditedResponse.getBody().data().balance());
    }
}
