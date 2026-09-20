package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.domain.enums.MilestoneStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EV-002 — {@link PaymentMilestone#markFunded} is the entity-level backstop against a second
 * funding. It used to overwrite {@code escrowHoldId} unconditionally, which is how the first,
 * already-debited hold ended up attached to nothing.
 */
class PaymentMilestoneMarkFundedTest {

    private static PaymentMilestone pending() {
        return PaymentMilestone.builder()
                .id("01HMILESTONE1234567AB")
                .contractId("01HCONTRACT1234567AB")
                .collaborationId("01HCOLLAB1234567890AB")
                .amount(BigDecimal.valueOf(5000))
                .build();
    }

    @Test
    @DisplayName("PENDING milestone with no hold: markFunded binds the hold and moves to FUNDED")
    void pendingMilestoneFunds() {
        PaymentMilestone m = pending();

        m.markFunded("hold-1");

        assertEquals(MilestoneStatus.FUNDED, m.getStatus());
        assertEquals("hold-1", m.getEscrowHoldId());
    }

    @Test
    @DisplayName("FUNDED milestone: a second markFunded throws and keeps the first hold")
    void secondMarkFundedThrowsAndKeepsFirstHold() {
        PaymentMilestone m = pending();
        m.markFunded("hold-1");

        assertThrows(IllegalStateException.class, () -> m.markFunded("hold-2"));

        assertEquals("hold-1", m.getEscrowHoldId());
        assertEquals(MilestoneStatus.FUNDED, m.getStatus());
    }

    @Test
    @DisplayName("RELEASED milestone: markFunded throws, status unchanged")
    void releasedMilestoneRefuses() {
        PaymentMilestone m = pending();
        m.markFunded("hold-1");
        m.markReleased("wtx-1", "release:hold-1");

        assertThrows(IllegalStateException.class, () -> m.markFunded("hold-2"));

        assertEquals(MilestoneStatus.RELEASED, m.getStatus());
        assertEquals("hold-1", m.getEscrowHoldId());
    }

    @Test
    @DisplayName("REFUNDED milestone: markFunded throws, status unchanged")
    void refundedMilestoneRefuses() {
        PaymentMilestone m = pending();
        m.markFunded("hold-1");
        m.markRefunded("wtx-1", "refund:hold-1");

        assertThrows(IllegalStateException.class, () -> m.markFunded("hold-2"));

        assertEquals(MilestoneStatus.REFUNDED, m.getStatus());
    }

    @Test
    @DisplayName("FROZEN milestone: markFunded throws, status unchanged")
    void frozenMilestoneRefuses() {
        PaymentMilestone m = pending();
        m.markFunded("hold-1");
        m.markFrozen();

        assertThrows(IllegalStateException.class, () -> m.markFunded("hold-2"));

        assertEquals(MilestoneStatus.FROZEN, m.getStatus());
    }

    @Test
    @DisplayName("A row built directly in a non-PENDING status also refuses")
    void builtNonPendingRefuses() {
        PaymentMilestone m =
                PaymentMilestone.builder()
                        .id("01HMILESTONE1234567AC")
                        .contractId("01HCONTRACT1234567AB")
                        .collaborationId("01HCOLLAB1234567890AB")
                        .amount(BigDecimal.valueOf(5000))
                        .status(MilestoneStatus.RELEASED)
                        .build();

        assertThrows(IllegalStateException.class, () -> m.markFunded("hold-2"));
    }
}
