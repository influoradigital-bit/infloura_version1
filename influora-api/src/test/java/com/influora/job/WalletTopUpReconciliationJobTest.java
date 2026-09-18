package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.WalletTopUp;
import com.influora.domain.enums.WalletTopUpStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.WalletTopUpRepository;
import com.influora.service.WalletTopUpService;
import java.math.BigDecimal;
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
 * A captured Razorpay payment whose {@code payment.captured} webhook never arrived used to leave
 * the brand's money taken and the wallet never credited: the webhook was the ONLY credit path, the
 * browser discards the payment signature so there is no verify-on-return call, and the admin
 * reconciliation view is read-only.
 */
@ExtendWith(MockitoExtension.class)
class WalletTopUpReconciliationJobTest {

    private static final String TOPUP_ID = "01HTOPUP00000000000001";
    private static final String ORDER_ID = "order_RZP0000000001";
    private static final String WORKSPACE_ID = "01HWORKSPACE0000000001";

    @Mock private WalletTopUpRepository topUpRepository;
    @Mock private WalletTopUpService topUpService;
    @Mock private RazorpayClient razorpayClient;

    private WalletTopUpReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new WalletTopUpReconciliationJob(topUpRepository, topUpService, razorpayClient);
    }

    private static WalletTopUp pendingTopUp(String orderId) {
        return WalletTopUp.builder()
                .id(TOPUP_ID)
                .workspaceId(WORKSPACE_ID)
                .amount(new BigDecimal("1000.00"))
                .currency("INR")
                .razorpayOrderId(orderId)
                .idempotencyKey("client-key-1")
                .status(WalletTopUpStatus.PENDING)
                .build();
    }

    private void stubCandidates(WalletTopUp... rows) {
        when(razorpayClient.isConfigured()).thenReturn(true);
        when(topUpRepository.findByStatusAndCreatedAtBetween(
                        eq(WalletTopUpStatus.PENDING), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("a paid order whose webhook never arrived is credited through the SAME path the webhook uses")
    void creditsAPaidOrder() {
        stubCandidates(pendingTopUp(ORDER_ID));
        when(razorpayClient.fetchOrderPaymentState(ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("paid", 100_000L, "INR"));

        job.reconcileUncreditedTopUps();

        // Amount comes from the GATEWAY (paise), so confirmCredited's own amount/currency
        // validation still runs — the sweep never asserts its own figure.
        verify(topUpService).confirmCredited(TOPUP_ID, ORDER_ID, 100_000L, "INR");
    }

    @Test
    @DisplayName("an order Razorpay still reports as unpaid is left alone — no credit, no state change")
    void leavesAnUnpaidOrderAlone() {
        stubCandidates(pendingTopUp(ORDER_ID));
        when(razorpayClient.fetchOrderPaymentState(ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("created", 0L, "INR"));

        job.reconcileUncreditedTopUps();

        verify(topUpService, never()).confirmCredited(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("the sweep window is bounded at both ends: a grace period, and a give-up horizon")
    void sweepWindowIsBounded() {
        stubCandidates();

        job.reconcileUncreditedTopUps();

        ArgumentCaptor<Instant> after = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> before = ArgumentCaptor.forClass(Instant.class);
        verify(topUpRepository)
                .findByStatusAndCreatedAtBetween(eq(WalletTopUpStatus.PENDING), after.capture(), before.capture());
        Instant now = Instant.now();
        // `before` = now - grace: a top-up made seconds ago is NOT second-guessed.
        assertTrue(
                before.getValue().isBefore(now.minus(WalletTopUpReconciliationJob.WEBHOOK_GRACE_PERIOD).plusSeconds(5)),
                "must wait out the webhook grace period");
        // `after` = now - give-up: abandoned checkouts stop being polled forever.
        assertTrue(
                after.getValue().isBefore(before.getValue()),
                "the give-up horizon must be older than the grace cutoff");
        assertEquals(
                WalletTopUpReconciliationJob.GIVE_UP_AFTER.toDays(),
                java.time.Duration.between(after.getValue(), now).toDays());
    }

    @Test
    @DisplayName("no Razorpay credentials: the gateway is never called, so nothing can be credited blind")
    void skipsEntirelyWhenUnconfigured() {
        when(razorpayClient.isConfigured()).thenReturn(false);

        job.reconcileUncreditedTopUps();

        verifyNoInteractions(topUpRepository);
        verify(razorpayClient, never()).fetchOrderPaymentState(anyString());
        verify(topUpService, never()).confirmCredited(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("one row's failure does not abort the sweep — the next row is still reconciled")
    void oneFailureDoesNotAbortTheSweep() {
        WalletTopUp broken = pendingTopUp("order_BROKEN");
        WalletTopUp healthy = pendingTopUp(ORDER_ID);
        stubCandidates(broken, healthy);
        when(razorpayClient.fetchOrderPaymentState("order_BROKEN"))
                .thenThrow(new com.influora.integration.razorpay.RazorpayIntegrationException("gateway down", new RuntimeException("timeout")));
        when(razorpayClient.fetchOrderPaymentState(ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("paid", 100_000L, "INR"));

        job.reconcileUncreditedTopUps();

        verify(topUpService).confirmCredited(TOPUP_ID, ORDER_ID, 100_000L, "INR");
    }

    @Test
    @DisplayName("an amount mismatch is left for a human: it must not be swallowed into a credit")
    void anAmountMismatchDoesNotCredit() {
        stubCandidates(pendingTopUp(ORDER_ID));
        when(razorpayClient.fetchOrderPaymentState(ORDER_ID))
                .thenReturn(new RazorpayClient.OrderPaymentState("paid", 1L, "INR"));
        when(topUpService.confirmCredited(TOPUP_ID, ORDER_ID, 1L, "INR"))
                .thenThrow(new ApiException("TOPUP_AMOUNT_MISMATCH", "mismatch", HttpStatus.CONFLICT));

        job.reconcileUncreditedTopUps();

        // The sweep surfaces it and moves on; the row stays PENDING for the next run / a human.
        verify(topUpService).confirmCredited(TOPUP_ID, ORDER_ID, 1L, "INR");
    }

    @Test
    @DisplayName("a PENDING row with no order id is reported, not sent to the gateway as a blank id")
    void rowWithNoOrderIdIsReported() {
        stubCandidates(pendingTopUp(null));

        job.reconcileUncreditedTopUps();

        verify(razorpayClient, never()).fetchOrderPaymentState(anyString());
        verify(topUpService, never()).confirmCredited(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("OrderPaymentState.isPaid is the gate: only Razorpay's own 'paid' with a real amount counts")
    void isPaidSemantics() {
        assertTrue(new RazorpayClient.OrderPaymentState("paid", 100_000L, "INR").isPaid());
        assertTrue(new RazorpayClient.OrderPaymentState("PAID", 1L, "INR").isPaid());
        assertEquals(false, new RazorpayClient.OrderPaymentState("paid", null, "INR").isPaid());
        assertEquals(false, new RazorpayClient.OrderPaymentState("paid", 0L, "INR").isPaid());
        assertEquals(false, new RazorpayClient.OrderPaymentState("created", 100_000L, "INR").isPaid());
        assertEquals(false, new RazorpayClient.OrderPaymentState("attempted", 100_000L, "INR").isPaid());
        // The fail-closed value RazorpayClient returns when credentials are absent.
        assertEquals(false, new RazorpayClient.OrderPaymentState("unconfigured", null, null).isPaid());
    }
}
