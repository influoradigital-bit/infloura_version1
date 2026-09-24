package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.service.credits.CreatorCreditOrderService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md A31, design-kabir.md K-08) — cloned in shape from {@code
 * WalletTopUpReconciliationJobTest}: a creator-credit order whose {@code order.paid}/{@code
 * payment.captured} webhook never arrived is repaired here, through the SAME {@code confirmPaid}
 * path the webhook itself uses (so the amount/currency check and the PENDING→CREDITED transition
 * are identical either way).
 */
@ExtendWith(MockitoExtension.class)
class CreatorCreditOrderReconciliationJobTest {

    @Mock private CreatorCreditOrderService orderService;
    @Mock private RazorpayClient razorpayClient;

    private CreatorCreditOrderReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new CreatorCreditOrderReconciliationJob(orderService, razorpayClient);
    }

    private static CreatorCreditOrder orderWith(String id, String creatorUserId, String razorpayOrderId) {
        CreatorCreditOrder order = mock(CreatorCreditOrder.class);
        when(order.getId()).thenReturn(id);
        when(order.getCreatorUserId()).thenReturn(creatorUserId);
        when(order.getRazorpayOrderId()).thenReturn(razorpayOrderId);
        // lenient: only the credited-log branch reads getCredits(); the F-6 given-up-order path
        // never does, so a strict stub here would fail that test with UnnecessaryStubbingException.
        org.mockito.Mockito.lenient().when(order.getCredits()).thenReturn(60);
        return order;
    }

    @Test
    @DisplayName(
            "A31/K-08: a stranded PENDING order (grace elapsed, gateway now paid) is credited through"
                    + " confirmPaid exactly once; running the sweep again does not re-credit it (its"
                    + " PENDING-status query no longer returns it); a still-unpaid gateway state is left"
                    + " alone; and a gateway amount mismatch is surfaced (attempted, not swallowed), never"
                    + " silently credited")
    void repairsStrandedOrderOnce() {
        when(razorpayClient.isConfigured()).thenReturn(true);
        when(orderService.findGivenUpPending(any(Instant.class))).thenReturn(List.of());

        CreatorCreditOrder stranded = orderWith("order-stranded-1", "01HCREATORSTRANDED0001", "order_rzp_stranded1");
        CreatorCreditOrder mismatched = orderWith("order-mismatch-1", "01HCREATORMISMATCH0001", "order_rzp_mismatch1");
        CreatorCreditOrder stillUnpaid = orderWith("order-unpaid-1", "01HCREATORUNPAID00001", "order_rzp_unpaid1");

        when(orderService.findStalePending(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(stranded, mismatched, stillUnpaid))
                // A31 "run twice -> still credited once": a real PENDING-status repository query
                // would no longer return `stranded` once confirmPaid has moved it to CREDITED — this
                // stub simulates exactly that state change rather than re-testing confirmPaid's own
                // idempotent CREDITED short-circuit (already covered by
                // CreatorCreditOrderServiceTest#confirmPaidOnceAndValidated).
                .thenReturn(List.of());

        when(razorpayClient.fetchOrderPaymentState("order_rzp_stranded1"))
                .thenReturn(new RazorpayClient.OrderPaymentState("paid", 24900L, "INR"));
        when(razorpayClient.fetchOrderPaymentState("order_rzp_mismatch1"))
                .thenReturn(new RazorpayClient.OrderPaymentState("paid", 1L, "INR"));
        when(razorpayClient.fetchOrderPaymentState("order_rzp_unpaid1"))
                .thenReturn(new RazorpayClient.OrderPaymentState("created", 0L, "INR"));

        when(orderService.confirmPaid(
                        eq("order-mismatch-1"), anyString(), eq("order_rzp_mismatch1"), eq(1L), eq("INR")))
                .thenThrow(
                        new ApiException(
                                "CREDIT_ORDER_AMOUNT_MISMATCH", "amount mismatch", HttpStatus.CONFLICT));

        job.reconcileUncreditedOrders();
        job.reconcileUncreditedOrders(); // A31: run twice -> still credited once

        // Credited exactly once — not once per sweep.
        verify(orderService, times(1))
                .confirmPaid(eq("order-stranded-1"), anyString(), eq("order_rzp_stranded1"), eq(24900L), eq("INR"));
        // The mismatch was attempted (surfaced for a human, per the job's own catch-and-log) but
        // never actually credited — this call throwing IS the "not credited" assertion.
        verify(orderService, times(1))
                .confirmPaid(eq("order-mismatch-1"), anyString(), eq("order_rzp_mismatch1"), eq(1L), eq("INR"));
        // A "created" gateway state is never asked to confirmPaid at all.
        verify(orderService, never())
                .confirmPaid(eq("order-unpaid-1"), anyString(), anyString(), any(), anyString());

        // A31: an order only 5 minutes old must fall inside the grace period and never even be
        // handed to Razorpay as a candidate — the sweep's own window excludes it structurally.
        ArgumentCaptor<Instant> after = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> before = ArgumentCaptor.forClass(Instant.class);
        verify(orderService, atLeastOnce()).findStalePending(after.capture(), before.capture());
        Instant now = Instant.now();
        assertTrue(
                before.getValue().isBefore(now.minus(CreatorCreditOrderReconciliationJob.WEBHOOK_GRACE_PERIOD).plusSeconds(5)),
                "a 5-minute-old order must fall inside the webhook grace period and never be a candidate");
        assertTrue(
                after.getValue().isBefore(before.getValue()), "the give-up horizon must be older than the grace cutoff");
    }

    @Test
    @DisplayName("no Razorpay credentials: the gateway is never called, nothing can be credited blind")
    void skipsEntirelyWhenUnconfigured() {
        when(razorpayClient.isConfigured()).thenReturn(false);

        job.reconcileUncreditedOrders();

        verifyNoInteractions(orderService);
        verify(razorpayClient, never()).fetchOrderPaymentState(anyString());
    }

    @Test
    @DisplayName("F-6: an order stuck PENDING past the 7-day give-up horizon is ERROR-logged every run, not silently dropped from consideration")
    void givenUpOrdersAreReportedEveryRun() {
        when(razorpayClient.isConfigured()).thenReturn(true);
        CreatorCreditOrder givenUp = orderWith("order-givenup-1", "01HCREATORGIVENUP0001", "order_rzp_givenup1");
        when(orderService.findGivenUpPending(any(Instant.class))).thenReturn(List.of(givenUp));
        when(orderService.findStalePending(any(Instant.class), any(Instant.class))).thenReturn(List.of());

        job.reconcileUncreditedOrders();

        ArgumentCaptor<Instant> giveUpBefore = ArgumentCaptor.forClass(Instant.class);
        verify(orderService).findGivenUpPending(giveUpBefore.capture());
        Instant now = Instant.now();
        assertEquals(
                CreatorCreditOrderReconciliationJob.GIVE_UP_AFTER.toDays(),
                Duration.between(giveUpBefore.getValue(), now).toDays());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());
    }
}
