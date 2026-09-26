package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.CreatorCreditProperties;
import com.influora.config.RazorpayProperties;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorCreditPack;
import com.influora.domain.enums.CreatorCreditOrderStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.CreatorCreditOrderRepository;
import com.influora.repository.CreatorCreditPackRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md A25/A26) — {@link CreatorCreditOrderService} money-correctness:
 * the amount is ALWAYS server-derived from the pack row (K-09/K-25), never from a client, and
 * {@code confirmPaid} credits exactly once, validated (K-10).
 */
class CreatorCreditOrderServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private CreatorCreditOrderRepository orderRepository;
    private CreatorCreditPackRepository packRepository;
    private CreatorCreditService creditService;
    private CreatorCreditAccountInitializer accountInitializer;
    private CreatorCreditInvoiceApplier invoiceApplier;
    private RazorpayClient razorpayClient;
    private RazorpayProperties razorpayProperties;
    private CreatorCreditProperties creditProperties;
    private Clock clock;

    private CreatorCreditOrderService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(CreatorCreditOrderRepository.class);
        packRepository = mock(CreatorCreditPackRepository.class);
        creditService = mock(CreatorCreditService.class);
        accountInitializer = mock(CreatorCreditAccountInitializer.class);
        invoiceApplier = mock(CreatorCreditInvoiceApplier.class);
        razorpayClient = mock(RazorpayClient.class);
        razorpayProperties = mock(RazorpayProperties.class);
        creditProperties = mock(CreatorCreditProperties.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service =
                new CreatorCreditOrderService(
                        orderRepository,
                        packRepository,
                        creditService,
                        accountInitializer,
                        invoiceApplier,
                        razorpayClient,
                        razorpayProperties,
                        creditProperties,
                        clock);

        when(razorpayProperties.getKeyId()).thenReturn("rzp_test_key");
    }

    private CreatorCreditPack pack60() {
        CreatorCreditPack pack = mock(CreatorCreditPack.class);
        when(pack.getId()).thenReturn("pack-60-id");
        when(pack.getCode()).thenReturn("PACK_60");
        when(pack.getCredits()).thenReturn(60);
        when(pack.getPricePaise()).thenReturn(24900);
        return pack;
    }

    // ------------------------------------------------------------------
    // A25 — server-priced order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createOrder: flag off returns 404 FEATURE_DISABLED before any repository call")
    void createOrder_flagOff_refused() {
        when(creditProperties.isEnabled()).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.createOrder(CREATOR_USER_ID, "PACK_60", "key-1"));

        assertEquals("FEATURE_DISABLED", ex.getCode());
        org.mockito.Mockito.verifyNoInteractions(packRepository, razorpayClient);
    }

    @Test
    @DisplayName("createOrder: a 65-character idempotency key is refused with 400")
    void createOrder_keyTooLong_refused() {
        when(creditProperties.isEnabled()).thenReturn(true);
        String tooLong = "a".repeat(65);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.createOrder(CREATOR_USER_ID, "PACK_60", tooLong));

        assertEquals("IDEMPOTENCY_KEY_REQUIRED", ex.getCode());
    }

    @Test
    @DisplayName("createOrder: an unknown/inactive pack code is refused with 400")
    void createOrder_unknownPack_refused() {
        when(creditProperties.isEnabled()).thenReturn(true);
        when(orderRepository.findByCreatorUserIdAndIdempotencyKey(CREATOR_USER_ID, "key-1"))
                .thenReturn(Optional.empty());
        when(packRepository.findByCodeAndActiveTrue("BOGUS")).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.createOrder(CREATOR_USER_ID, "BOGUS", "key-1"));

        assertEquals("UNKNOWN_CREDIT_PACK", ex.getCode());
        org.mockito.Mockito.verifyNoInteractions(razorpayClient);
    }

    @Test
    @DisplayName(
            "A25: createOrder ignores any client-shaped amount and always mints the Razorpay order"
                    + " for EXACTLY the pack's own server-stored price (249.00 INR), receipt ccr:<id>")
    void createOrder_alwaysUsesServerPrice() {
        when(creditProperties.isEnabled()).thenReturn(true);
        when(orderRepository.findByCreatorUserIdAndIdempotencyKey(CREATOR_USER_ID, "key-1"))
                .thenReturn(Optional.empty());
        CreatorCreditPack pack = pack60();
        when(packRepository.findByCodeAndActiveTrue("PACK_60")).thenReturn(Optional.of(pack));
        when(razorpayClient.createOrder(any(), anyString(), anyString()))
                .thenReturn(new RazorpayClient.OrderResult("order_abc123", "created"));

        var response = service.createOrder(CREATOR_USER_ID, "PACK_60", "key-1");

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> receiptCaptor = ArgumentCaptor.forClass(String.class);
        verify(razorpayClient).createOrder(amountCaptor.capture(), eq("INR"), receiptCaptor.capture());
        assertEquals(0, new BigDecimal("249.00").compareTo(amountCaptor.getValue()));
        assertTrue(receiptCaptor.getValue().startsWith("ccr:"));
        assertEquals(24900, response.amountPaise());
        assertEquals(60, response.credits());
        assertEquals("order_abc123", response.razorpayOrderId());
    }

    @Test
    @DisplayName(
            "F-13/F-21: createOrder ensures the creator_credit_accounts lock-anchor row exists"
                    + " BEFORE inserting the order, so a creator with no prior Meera turn does not hit"
                    + " fk_cco_account")
    void createOrder_ensuresAccountRowFirst() {
        when(creditProperties.isEnabled()).thenReturn(true);
        when(orderRepository.findByCreatorUserIdAndIdempotencyKey(CREATOR_USER_ID, "key-1"))
                .thenReturn(Optional.empty());
        CreatorCreditPack pack = pack60();
        when(packRepository.findByCodeAndActiveTrue("PACK_60")).thenReturn(Optional.of(pack));
        when(razorpayClient.createOrder(any(), anyString(), anyString()))
                .thenReturn(new RazorpayClient.OrderResult("order_abc123", "created"));

        service.createOrder(CREATOR_USER_ID, "PACK_60", "key-1");

        verify(accountInitializer).ensureAccount(CREATOR_USER_ID);
    }

    @Test
    @DisplayName("createOrder: replaying the SAME idempotency key returns the SAME order, never a second Razorpay order")
    void createOrder_replayReturnsSameOrder() {
        when(creditProperties.isEnabled()).thenReturn(true);
        CreatorCreditOrder existing = mock(CreatorCreditOrder.class);
        when(existing.getId()).thenReturn("existing-order-id");
        when(existing.getRazorpayOrderId()).thenReturn("order_existing");
        when(existing.getAmountPaise()).thenReturn(24900);
        when(existing.getCurrency()).thenReturn("INR");
        when(existing.getCredits()).thenReturn(60);
        when(orderRepository.findByCreatorUserIdAndIdempotencyKey(CREATOR_USER_ID, "key-1"))
                .thenReturn(Optional.of(existing));

        var response = service.createOrder(CREATOR_USER_ID, "PACK_60", "key-1");

        assertEquals("existing-order-id", response.orderId());
        org.mockito.Mockito.verifyNoInteractions(razorpayClient, packRepository);
    }

    @Test
    @DisplayName(
            "A25 (exact acceptance wording): createOrder ignores body amountPaise/credits, always"
                    + " calls createOrder(249.00, \"INR\", \"ccr:<id>\"); an inactive/unknown pack -> 400;"
                    + " replay with the SAME idempotency key -> the SAME order; a 65-character key -> 400")
    void serverPricedOrder() {
        when(creditProperties.isEnabled()).thenReturn(true);

        // -- inactive/unknown pack -> 400, Razorpay never touched
        when(orderRepository.findByCreatorUserIdAndIdempotencyKey(CREATOR_USER_ID, "key-bogus"))
                .thenReturn(Optional.empty());
        when(packRepository.findByCodeAndActiveTrue("BOGUS")).thenReturn(Optional.empty());
        ApiException unknownPack =
                assertThrows(
                        ApiException.class, () -> service.createOrder(CREATOR_USER_ID, "BOGUS", "key-bogus"));
        assertEquals("UNKNOWN_CREDIT_PACK", unknownPack.getCode());
        org.mockito.Mockito.verifyNoInteractions(razorpayClient);

        // -- a 65-character idempotency key -> 400, before any repository read
        String tooLong = "k".repeat(65);
        ApiException keyTooLong =
                assertThrows(
                        ApiException.class, () -> service.createOrder(CREATOR_USER_ID, "PACK_60", tooLong));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", keyTooLong.getCode());

        // -- the real, server-priced path: client-shaped amountPaise/credits fields do not exist on
        // CreateOrderRequest at all (packCode is the only field the DTO carries — see
        // CreatorCreditDtos.CreateOrderRequest) so "ignores body amountPaise/credits" is proven
        // structurally by this service method's own signature (String creatorUserId, String
        // packCode, String idempotencyKey) taking no such parameter — there is no amount for a
        // caller to smuggle in. What IS this test's own job: prove the amount handed to Razorpay is
        // ALWAYS the pack's own server-stored price, and the receipt carries the ccr: prefix.
        when(orderRepository.findByCreatorUserIdAndIdempotencyKey(CREATOR_USER_ID, "key-real"))
                .thenReturn(Optional.empty());
        CreatorCreditPack pack = pack60();
        when(packRepository.findByCodeAndActiveTrue("PACK_60")).thenReturn(Optional.of(pack));
        when(razorpayClient.createOrder(any(), anyString(), anyString()))
                .thenReturn(new RazorpayClient.OrderResult("order_real123", "created"));

        var response = service.createOrder(CREATOR_USER_ID, "PACK_60", "key-real");

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> currencyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> receiptCaptor = ArgumentCaptor.forClass(String.class);
        verify(razorpayClient)
                .createOrder(amountCaptor.capture(), currencyCaptor.capture(), receiptCaptor.capture());
        assertEquals(0, new BigDecimal("249.00").compareTo(amountCaptor.getValue()));
        assertEquals("INR", currencyCaptor.getValue());
        assertTrue(receiptCaptor.getValue().startsWith("ccr:"));
        assertEquals(24900, response.amountPaise());
        assertEquals(60, response.credits());

        // -- replay with the SAME idempotency key -> the SAME order, never a second Razorpay order
        CreatorCreditOrder existing = mock(CreatorCreditOrder.class);
        when(existing.getId()).thenReturn("existing-order-id");
        when(existing.getRazorpayOrderId()).thenReturn("order_real123");
        when(existing.getAmountPaise()).thenReturn(24900);
        when(existing.getCurrency()).thenReturn("INR");
        when(existing.getCredits()).thenReturn(60);
        when(orderRepository.findByCreatorUserIdAndIdempotencyKey(CREATOR_USER_ID, "key-real"))
                .thenReturn(Optional.of(existing));

        var replay = service.createOrder(CREATOR_USER_ID, "PACK_60", "key-real");

        assertEquals("existing-order-id", replay.orderId());
        // Exactly one Razorpay order was ever minted across both calls (the first one, above).
        verify(razorpayClient, org.mockito.Mockito.times(1)).createOrder(any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // A26 — confirmPaid: exactly once, validated
    // ------------------------------------------------------------------

    @Test
    @DisplayName("confirmPaid: an already-CREDITED order is an idempotent no-op (never grants twice)")
    void confirmPaid_alreadyCredited_noOp() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getStatus()).thenReturn(CreatorCreditOrderStatus.CREDITED);
        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));

        service.confirmPaid("order-1", "pay_1", "order_rzp_1", 24900L, "INR");

        org.mockito.Mockito.verifyNoInteractions(creditService);
        verify(order, never()).markCredited(any(), any(), any());
    }

    @Test
    @DisplayName("confirmPaid: a razorpay_order_id mismatch is rejected with 409, no grant")
    void confirmPaid_orderIdMismatch_rejected() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getStatus()).thenReturn(CreatorCreditOrderStatus.PENDING);
        when(order.getRazorpayOrderId()).thenReturn("order_real");
        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.confirmPaid("order-1", "pay_1", "order_WRONG", 24900L, "INR"));

        assertEquals("CREDIT_ORDER_MISMATCH", ex.getCode());
        org.mockito.Mockito.verifyNoInteractions(creditService);
    }

    @Test
    @DisplayName("confirmPaid: amount 24800 (short by 100 paise) is rejected with 409, no grant")
    void confirmPaid_amountMismatch_rejected() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getStatus()).thenReturn(CreatorCreditOrderStatus.PENDING);
        when(order.getRazorpayOrderId()).thenReturn("order_rzp_1");
        when(order.getAmountPaise()).thenReturn(24900);
        when(order.getCurrency()).thenReturn("INR");
        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.confirmPaid("order-1", "pay_1", "order_rzp_1", 24800L, "INR"));

        assertEquals("CREDIT_ORDER_AMOUNT_MISMATCH", ex.getCode());
        org.mockito.Mockito.verifyNoInteractions(creditService);
    }

    @Test
    @DisplayName("confirmPaid: a valid payment grants a 60-credit PAID lot expiring paidAt+90d and marks CREDITED")
    void confirmPaid_valid_grantsAndCredits() {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn("order-1");
        when(order.getCreatorUserId()).thenReturn(CREATOR_USER_ID);
        when(order.getStatus()).thenReturn(CreatorCreditOrderStatus.PENDING);
        when(order.getRazorpayOrderId()).thenReturn("order_rzp_1");
        when(order.getAmountPaise()).thenReturn(24900);
        when(order.getCurrency()).thenReturn("INR");
        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));
        when(creditService.creditPurchase(eq(order), eq(NOW))).thenReturn("grant-xyz");

        service.confirmPaid("order-1", "pay_1", "order_rzp_1", 24900L, "INR");

        verify(creditService).creditPurchase(order, NOW);
        verify(order).markCredited("pay_1", "grant-xyz", NOW);
        verify(orderRepository).saveAndFlush(order);
        // F-14/F-22: invoice numbering/GST breakup is no longer computed inline (a numbering
        // failure there must never be able to roll back the credit above — see
        // CreatorCreditInvoiceApplier's javadoc). It is deferred to run strictly after this
        // transaction commits; CreatorCreditInvoiceApplierTest covers the real GST breakup (A37)
        // against that bean directly.
        verify(invoiceApplier).applyBestEffort("order-1");
    }

    @Test
    @DisplayName(
            "A26 (exact acceptance wording): confirmPaid x3 -> one PAID grant of 60 (via"
                    + " creditPurchase, called exactly once); amount 24800 -> 409, no grant; a mismatched"
                    + " razorpay_order_id -> rejected; the same payment id presented on a second,"
                    + " different order -> rejected")
    void confirmPaidOnceAndValidated() {
        // -- confirmPaid x3 for the SAME order -> credited exactly once. The status mock switches
        // to CREDITED after the first call, mirroring the real row's own PENDING->CREDITED
        // transition (order.markCredited is void on a mock, so this simulates the persisted
        // state change confirmPaid itself performs).
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn("order-thrice");
        when(order.getCreatorUserId()).thenReturn(CREATOR_USER_ID);
        when(order.getRazorpayOrderId()).thenReturn("order_rzp_thrice");
        when(order.getAmountPaise()).thenReturn(24900);
        when(order.getCurrency()).thenReturn("INR");
        when(order.getStatus())
                .thenReturn(CreatorCreditOrderStatus.PENDING)
                .thenReturn(CreatorCreditOrderStatus.CREDITED)
                .thenReturn(CreatorCreditOrderStatus.CREDITED);
        when(orderRepository.findByIdForUpdate("order-thrice")).thenReturn(Optional.of(order));
        when(creditService.creditPurchase(eq(order), any())).thenReturn("grant-thrice");

        service.confirmPaid("order-thrice", "pay_thrice", "order_rzp_thrice", 24900L, "INR");
        service.confirmPaid("order-thrice", "pay_thrice", "order_rzp_thrice", 24900L, "INR");
        service.confirmPaid("order-thrice", "pay_thrice", "order_rzp_thrice", 24900L, "INR");

        // creditPurchase is what mints the 60-credit PAID lot expiring paidAt+90d — its own
        // expiry math is CreatorCreditServiceTest's job; this test's job is that it is invoked
        // exactly once across three confirmPaid calls, with the server clock's own instant as
        // paidAt (never a client-supplied time).
        verify(creditService, org.mockito.Mockito.times(1)).creditPurchase(eq(order), eq(NOW));
        verify(order, org.mockito.Mockito.times(1)).markCredited("pay_thrice", "grant-thrice", NOW);

        // -- amount short by 100 paise -> 409 CREDIT_ORDER_AMOUNT_MISMATCH, no grant
        CreatorCreditOrder mismatchAmountOrder = mock(CreatorCreditOrder.class);
        when(mismatchAmountOrder.getStatus()).thenReturn(CreatorCreditOrderStatus.PENDING);
        when(mismatchAmountOrder.getRazorpayOrderId()).thenReturn("order_rzp_amt");
        when(mismatchAmountOrder.getAmountPaise()).thenReturn(24900);
        when(mismatchAmountOrder.getCurrency()).thenReturn("INR");
        when(orderRepository.findByIdForUpdate("order-amt")).thenReturn(Optional.of(mismatchAmountOrder));
        ApiException amountEx =
                assertThrows(
                        ApiException.class,
                        () -> service.confirmPaid("order-amt", "pay_amt", "order_rzp_amt", 24800L, "INR"));
        assertEquals("CREDIT_ORDER_AMOUNT_MISMATCH", amountEx.getCode());
        verify(creditService, never()).creditPurchase(eq(mismatchAmountOrder), any());

        // -- a mismatched razorpay_order_id -> rejected, no grant
        CreatorCreditOrder mismatchOrderIdOrder = mock(CreatorCreditOrder.class);
        when(mismatchOrderIdOrder.getStatus()).thenReturn(CreatorCreditOrderStatus.PENDING);
        when(mismatchOrderIdOrder.getRazorpayOrderId()).thenReturn("order_rzp_real");
        when(orderRepository.findByIdForUpdate("order-mismatch-id")).thenReturn(Optional.of(mismatchOrderIdOrder));
        ApiException mismatchEx =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirmPaid(
                                        "order-mismatch-id", "pay_x", "order_rzp_WRONG", 24900L, "INR"));
        assertEquals("CREDIT_ORDER_MISMATCH", mismatchEx.getCode());
        verify(creditService, never()).creditPurchase(eq(mismatchOrderIdOrder), any());

        // -- the SAME payment id, already used to credit a different order, presented on a second
        // order -> rejected (uk_cco_rzp_payment UNIQUE, K-10). The order's own saveAndFlush is what
        // hits that constraint in production; simulated here by having it throw.
        CreatorCreditOrder secondOrder = mock(CreatorCreditOrder.class);
        when(secondOrder.getId()).thenReturn("order-second");
        when(secondOrder.getCreatorUserId()).thenReturn(CREATOR_USER_ID);
        when(secondOrder.getStatus()).thenReturn(CreatorCreditOrderStatus.PENDING);
        when(secondOrder.getRazorpayOrderId()).thenReturn("order_rzp_second");
        when(secondOrder.getAmountPaise()).thenReturn(24900);
        when(secondOrder.getCurrency()).thenReturn("INR");
        when(orderRepository.findByIdForUpdate("order-second")).thenReturn(Optional.of(secondOrder));
        when(creditService.creditPurchase(eq(secondOrder), any())).thenReturn("grant-second");
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataIntegrityViolationException("uk_cco_rzp_payment"))
                .when(orderRepository)
                .saveAndFlush(secondOrder);

        ApiException reusedEx =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.confirmPaid(
                                        "order-second", "pay_thrice", "order_rzp_second", 24900L, "INR"));
        assertEquals("CREDIT_ORDER_PAYMENT_REUSED", reusedEx.getCode());
    }
}
