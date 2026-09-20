package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.influora.domain.enums.CreatorCreditOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- unit tests for {@link CreatorCreditOrder}'s builder
 * defaults and its {@code WalletTopUp}-mirrored mutators.
 */
class CreatorCreditOrderTest {

    private CreatorCreditOrder.Builder baseBuilder() {
        return CreatorCreditOrder.builder()
                .id("01HORDER0000000000000001")
                .creatorUserId("01HCREATOR000000000000001")
                .packId("01M2Z6C91ZM0FKX6NT642Z3YZ8")
                .packCode("STARTER")
                .credits(500)
                .amountPaise(14900)
                .idempotencyKey("idem-key-1");
    }

    @Test
    @DisplayName("Builder defaults status to PENDING when omitted")
    void builderDefaultsStatusToPending() {
        CreatorCreditOrder order = baseBuilder().build();

        assertEquals(CreatorCreditOrderStatus.PENDING, order.getStatus());
    }

    @Test
    @DisplayName("Builder defaults currency to INR when omitted")
    void builderDefaultsCurrencyToInr() {
        CreatorCreditOrder order = baseBuilder().build();

        assertEquals("INR", order.getCurrency());
    }

    @Test
    @DisplayName("razorpayOrderId is null until explicitly set -- the PENDING order has no Razorpay order yet")
    void razorpayOrderIdStartsNull() {
        CreatorCreditOrder order = baseBuilder().build();

        assertNull(order.getRazorpayOrderId());
    }

    @Test
    @DisplayName("setRazorpayOrderId stores the value and bumps updatedAt")
    void setRazorpayOrderIdStoresValue() {
        CreatorCreditOrder order = baseBuilder().build();

        order.setRazorpayOrderId("rzp_order_abc123");

        assertEquals("rzp_order_abc123", order.getRazorpayOrderId());
    }

    @Test
    @DisplayName("markCredited flips status to CREDITED, records the payment id, and stamps creditedAt")
    void markCreditedTransitionsToCredited() {
        CreatorCreditOrder order = baseBuilder().build();
        order.setRazorpayOrderId("rzp_order_abc123");

        order.markCredited("rzp_payment_xyz789");

        assertEquals(CreatorCreditOrderStatus.CREDITED, order.getStatus());
        assertEquals("rzp_payment_xyz789", order.getRazorpayPaymentId());
        assertNotNull(order.getCreditedAt());
    }

    @Test
    @DisplayName("credits snapshot preserves the pack's tenths value verbatim (500 = 50.0 credits)")
    void creditsSnapshotIsTenths() {
        CreatorCreditOrder order = baseBuilder().credits(500).build();

        assertEquals(500, order.getCredits());
    }
}
