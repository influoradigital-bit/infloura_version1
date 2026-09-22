package com.influora.service.credits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.common.Ulids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §4/§12, K-26, K-30) — A36: every server-built reference id
 * ({@code turn:}/{@code tts:}/{@code brief:}/{@code m:}/{@code signup}/{@code order:}) fits inside
 * {@code creator_credit_ledger.reference_id VARCHAR(64)}/{@code creator_credit_grants.source_ref
 * VARCHAR(64)} with no DEFAULT (K-30 requires the caller to always supply one explicitly, never
 * silently), and the {@code ccr:} receipt fits Razorpay's 40-character receipt limit. A blank ulid
 * fails loudly (IllegalArgumentException) rather than minting an unbounded or empty reference.
 */
class CreatorCreditReferenceLengthTest {

    @Test
    @DisplayName("A36: every reference format is <= 64 characters for a real ULID")
    void allKeysFit() {
        String ulid = Ulids.newUlid();
        assertTrue(ulid.length() <= 26, "a ULID should be 26 characters — sanity check on the test's own fixture");

        assertWithinLimit(CreatorCreditService.turnRef(ulid), 64);
        assertWithinLimit(CreatorCreditService.ttsRef(ulid), 64);
        assertWithinLimit(CreatorCreditService.briefRef(ulid), 64);
        assertWithinLimit(CreatorCreditService.monthlyRef("2026-09"), 64);
        assertWithinLimit(CreatorCreditService.welcomeRef(), 64);
        assertWithinLimit(CreatorCreditService.orderRef(ulid), 64);

        // The Razorpay receipt is "ccr:" + orderRef's ulid (CreatorCreditOrderService.RECEIPT_PREFIX +
        // order.getId()), which must additionally fit Razorpay's OWN 40-character receipt limit.
        String receipt = CreatorCreditOrderService.RECEIPT_PREFIX + ulid;
        assertWithinLimit(receipt, 40);
    }

    @Test
    @DisplayName("A36: turnRef/ttsRef/briefRef/orderRef reject a blank ulid with IllegalArgumentException")
    void blankUlidRejected() {
        assertThrows(IllegalArgumentException.class, () -> CreatorCreditService.turnRef(""));
        assertThrows(IllegalArgumentException.class, () -> CreatorCreditService.turnRef(null));
        assertThrows(IllegalArgumentException.class, () -> CreatorCreditService.ttsRef(" "));
        assertThrows(IllegalArgumentException.class, () -> CreatorCreditService.briefRef(null));
        assertThrows(IllegalArgumentException.class, () -> CreatorCreditService.orderRef(""));
    }

    @Test
    @DisplayName("A36: a reference that would exceed 64 characters fails loudly instead of being silently truncated")
    void oversizedReferenceRejected() {
        String tooLong = "x".repeat(80);
        assertThrows(IllegalArgumentException.class, () -> CreatorCreditService.turnRef(tooLong));
    }

    @Test
    @DisplayName("welcomeRef() is the fixed literal 'signup', never client- or ulid-derived")
    void welcomeRefIsFixedLiteral() {
        assertEquals("signup", CreatorCreditService.welcomeRef());
    }

    private static void assertWithinLimit(String reference, int max) {
        assertTrue(
                reference.length() <= max,
                "reference '" + reference + "' is " + reference.length() + " characters, expected <= " + max);
    }
}
