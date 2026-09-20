package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.influora.domain.enums.CreditBucket;
import com.influora.domain.enums.CreditLedgerReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-CREATOR-CREDITS-SEARCH K1 [vikram] -- unit tests for {@link CreatorCreditLedgerEntry}'s
 * builder defaults. CREDITS-SPEC.md §2.5: {@code referenceId}, {@code actorId} and {@code note}
 * must never be null (rule 4 -- {@code ddl-auto=validate} against NOT NULL columns), so the
 * builder defaults each to {@code ""} when omitted.
 */
class CreatorCreditLedgerEntryTest {

    private CreatorCreditLedgerEntry.Builder baseBuilder() {
        return CreatorCreditLedgerEntry.builder()
                .id("01HLEDGER00000000000000001")
                .creatorUserId("01HCREATOR000000000000001")
                .delta(-10)
                .bucket(CreditBucket.MONTHLY)
                .reason(CreditLedgerReason.TURN_DEBIT)
                .monthlyAfter(390)
                .purchasedAfter(300);
    }

    @Test
    @DisplayName("referenceId defaults to \"\" when omitted, never null")
    void referenceIdDefaultsToEmptyString() {
        CreatorCreditLedgerEntry entry = baseBuilder().build();

        assertEquals("", entry.getReferenceId());
    }

    @Test
    @DisplayName("actorId defaults to \"\" when omitted, never null")
    void actorIdDefaultsToEmptyString() {
        CreatorCreditLedgerEntry entry = baseBuilder().build();

        assertEquals("", entry.getActorId());
    }

    @Test
    @DisplayName("note defaults to \"\" when omitted, never null")
    void noteDefaultsToEmptyString() {
        CreatorCreditLedgerEntry entry = baseBuilder().build();

        assertEquals("", entry.getNote());
    }

    @Test
    @DisplayName("createdAt defaults to now() when omitted")
    void createdAtDefaultsToNow() {
        CreatorCreditLedgerEntry entry = baseBuilder().build();

        assertNotNull(entry.getCreatedAt());
    }

    @Test
    @DisplayName("a FREE_SEARCH row can carry delta = 0 with bucket = MONTHLY and a real referenceId")
    void freeSearchRowShapeIsExpressible() {
        CreatorCreditLedgerEntry entry =
                CreatorCreditLedgerEntry.builder()
                        .id("01HLEDGER00000000000000002")
                        .creatorUserId("01HCREATOR000000000000001")
                        .delta(0)
                        .bucket(CreditBucket.MONTHLY)
                        .reason(CreditLedgerReason.FREE_SEARCH)
                        .referenceId("01HSEARCH0000000000000001")
                        .monthlyAfter(400)
                        .purchasedAfter(300)
                        .build();

        assertEquals(0, entry.getDelta());
        assertEquals(CreditBucket.MONTHLY, entry.getBucket());
        assertEquals(CreditLedgerReason.FREE_SEARCH, entry.getReason());
        assertEquals("01HSEARCH0000000000000001", entry.getReferenceId());
    }

    @Test
    @DisplayName("explicitly set referenceId, actorId and note are kept, not overwritten by the defaults")
    void explicitValuesAreKept() {
        CreatorCreditLedgerEntry entry =
                baseBuilder()
                        .referenceId("turn-123")
                        .actorId("01HADMIN000000000000000001")
                        .note("manual comp")
                        .build();

        assertEquals("turn-123", entry.getReferenceId());
        assertEquals("01HADMIN000000000000000001", entry.getActorId());
        assertEquals("manual comp", entry.getNote());
    }
}
