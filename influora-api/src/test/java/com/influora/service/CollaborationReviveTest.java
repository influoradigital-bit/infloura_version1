package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Collaboration#revive} — F-0225 (status-blind-duplicate-guard).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Withdrawing an application does not delete the collaboration; it sets {@code CANCELLED} and
 * the row stays. {@code UNIQUE(campaign_id, creator_id)} (V6) then makes a second row
 * impossible, so before this fix a creator who withdrew once could never re-apply to that
 * campaign, and a brand could never re-invite them — a permanent 409 on a deal both sides had
 * abandoned.
 *
 * The fix revives the existing row. That makes the entity method below the single point where
 * a dead collaboration becomes a live one, so what it CLEARS matters as much as what it sets:
 * an agreed rate or usage-rights string surviving from the withdrawn negotiation would let the
 * abandoned attempt silently set the terms of the fresh one. These tests hold that.
 *
 * Run: mvn -o test -Dtest=CollaborationReviveTest
 */
class CollaborationReviveTest {

    private static final String CAMPAIGN_ID = "camp_1";
    private static final String CREATOR_ID = "creator_1";

    /** A collaboration that negotiated terms and was then withdrawn — the real revive input. */
    private static Collaboration withdrawnAfterNegotiating() {
        Collaboration c =
                Collaboration.propose(
                        "collab_1", CAMPAIGN_ID, CREATOR_ID, new BigDecimal("50000"), "INR", "our offer");
        c.setUsageRights("12 months, all platforms");
        c.transitionTo(CollaborationStatus.CANCELLED);
        return c;
    }

    @Test
    @DisplayName("revive: a withdrawn collaboration comes back as a fresh APPLIED application")
    void testReviveToApplication() {
        Collaboration c = withdrawnAfterNegotiating();

        c.revive(CollaborationStatus.APPLIED, CollaborationSource.APPLICATION, "second time", "INR");

        assertEquals(CollaborationStatus.APPLIED, c.getStatus());
        assertEquals(CollaborationSource.APPLICATION, c.getSource());
        assertEquals("second time", c.getNotes());
    }

    @Test
    @DisplayName("revive: the withdrawn negotiation's terms never carry into the new attempt")
    void testReviveClearsStaleTerms() {
        Collaboration c = withdrawnAfterNegotiating();
        assertEquals(new BigDecimal("50000"), c.getAgreedRate());
        assertEquals("12 months, all platforms", c.getUsageRights());

        c.revive(CollaborationStatus.APPLIED, CollaborationSource.APPLICATION, null, "INR");

        // The whole point: a rate agreed in an abandoned negotiation must not price the next one.
        assertNull(c.getAgreedRate());
        assertNull(c.getUsageRights());
    }

    @Test
    @DisplayName("revive: source flips, so a re-application is not still filed as an invitation")
    void testReviveResetsSource() {
        // Brand invited, deal died, creator later applies of their own accord. "My applications"
        // lists only source=APPLICATION rows (CreatorApplicationService), so leaving source as
        // INVITATION here would make the creator's own application invisible to them.
        Collaboration c = Collaboration.invite("collab_1", CAMPAIGN_ID, CREATOR_ID, "join us", "INR");
        c.transitionTo(CollaborationStatus.CANCELLED);

        c.revive(CollaborationStatus.APPLIED, CollaborationSource.APPLICATION, "applying now", "INR");

        assertEquals(CollaborationSource.APPLICATION, c.getSource());
    }

    @Test
    @DisplayName("revive: appliedAt moves to now; createdAt stays the row's birth")
    void testReviveMovesAppliedAtButNotCreatedAt() throws InterruptedException {
        Collaboration c = withdrawnAfterNegotiating();
        var originalCreatedAt = c.getCreatedAt();
        var originalAppliedAt = c.getAppliedAt();
        Thread.sleep(5);

        c.revive(CollaborationStatus.APPLIED, CollaborationSource.APPLICATION, null, "INR");

        // createdAt is updatable=false and means "row created" — it must not move.
        assertEquals(originalCreatedAt, c.getCreatedAt());
        // appliedAt is what the creator sees as "Applied" and what the list sorts on.
        assertNotEquals(originalAppliedAt, c.getAppliedAt());
        assertTrue(c.getAppliedAt().isAfter(originalAppliedAt));
    }

    @Test
    @DisplayName("revive: a brand re-invite comes back as INVITED/INVITATION, not APPLIED")
    void testReviveToInvitation() {
        Collaboration c = withdrawnAfterNegotiating();

        c.revive(CollaborationStatus.INVITED, CollaborationSource.INVITATION, "come back?", "INR");

        assertEquals(CollaborationStatus.INVITED, c.getStatus());
        assertEquals(CollaborationSource.INVITATION, c.getSource());
    }

    @Test
    @DisplayName("revive: the incoming message is sanitised, same as the factories")
    void testReviveSanitisesMessage() {
        Collaboration c = withdrawnAfterNegotiating();

        c.revive(
                CollaborationStatus.APPLIED,
                CollaborationSource.APPLICATION,
                "<script>alert(1)</script>I am a great fit",
                "INR");

        assertEquals("I am a great fit", c.getNotes());
    }

    @Test
    @DisplayName("revive: a null currency falls back to INR, same as the factories")
    void testReviveCurrencyFallback() {
        Collaboration c = withdrawnAfterNegotiating();

        c.revive(CollaborationStatus.APPLIED, CollaborationSource.APPLICATION, null, null);

        assertEquals("INR", c.getCurrency());
    }

    @Test
    @DisplayName("factories: appliedAt is set alongside createdAt on every entry point")
    void testFactoriesSetAppliedAt() {
        Collaboration applied = Collaboration.apply("a", CAMPAIGN_ID, CREATOR_ID, null, "INR");
        Collaboration invited = Collaboration.invite("i", CAMPAIGN_ID, CREATOR_ID, null, "INR");
        Collaboration proposed =
                Collaboration.propose("p", CAMPAIGN_ID, CREATOR_ID, BigDecimal.ONE, "INR", null);

        // NOT NULL in the schema — a factory that forgot this would fail only at flush time.
        assertEquals(applied.getCreatedAt(), applied.getAppliedAt());
        assertEquals(invited.getCreatedAt(), invited.getAppliedAt());
        assertEquals(proposed.getCreatedAt(), proposed.getAppliedAt());
    }
}
