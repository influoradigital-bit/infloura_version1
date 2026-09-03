package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.domain.enums.ExternalCreatorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — Priya's re-review found {@link
 * ExternalCreator#markInvited(String)} stamping {@code invitedAt} at full millisecond precision,
 * while {@code external_creators.invited_at} is a MySQL {@code TIMESTAMP} with fsp 0. MySQL
 * ROUNDS a fractional value into a zero-fsp column (it does not truncate), so a value like
 * {@code 10:15:30.750} lands in the DB as {@code 10:15:31}. {@code
 * InviteTokenService#issue} truncates (floors) {@code invitedAt} to whole seconds when signing
 * the invite token, so a token issued immediately after {@code markInvited} against the
 * millisecond-precision in-memory value would embed {@code 10:15:30} while the row that is
 * actually persisted reads {@code 10:15:31} — {@code RegistrationService#consumeInviteToken}
 * would then reject roughly half of all legitimately-issued tokens as "superseded by a
 * re-invite".
 *
 * <p>The fix truncates {@code invitedAt} to whole seconds INSIDE {@code markInvited}, before
 * Hibernate/MySQL ever see a fractional component to round. This test does not mock the clock —
 * it asserts the truncation contract directly: after a real {@code markInvited} call, the
 * stamped {@code invitedAt} carries zero nanoseconds, so the in-memory value, the value handed to
 * {@code InviteTokenService#issue}, and the value MySQL persists are all identical.
 */
class ExternalCreatorMarkInvitedTest {

    private ExternalCreator newRow() {
        return ExternalCreator.builder()
                .id("01HEXTCREATOR000000002")
                .source(ExternalCreatorSource.ADMIN_IMPORT)
                .igUsername("foodie.mumbai")
                .build();
    }

    @Test
    @DisplayName(
            "markInvited: invitedAt is truncated to whole seconds so it can never disagree with"
                    + " what a zero-fsp MySQL TIMESTAMP column rounds it to")
    void markInvited_truncatesInvitedAtToWholeSeconds() {
        ExternalCreator row = newRow();

        row.markInvited("creator@example.com");

        assertNotNull(row.getInvitedAt());
        assertEquals(
                0,
                row.getInvitedAt().getNano(),
                "invitedAt must carry zero sub-second precision — any fractional component would"
                        + " be ROUNDED (not truncated) by the fsp-0 invited_at column, drifting"
                        + " away from the floor-truncated value InviteTokenService#issue embeds"
                        + " in the invite token and silently invalidating it");
    }

    @Test
    @DisplayName("markInvited: first invite moves status UNVERIFIED -> INVITED")
    void markInvited_firstCall_movesStatusToInvited() {
        ExternalCreator row = newRow();

        row.markInvited("creator@example.com");

        assertEquals(ExternalCreatorStatus.INVITED, row.getStatus());
        assertEquals("creator@example.com", row.getEmail());
    }

    @Test
    @DisplayName("markInvited: re-invite re-stamps invitedAt (still whole seconds) without moving status backwards")
    void markInvited_reinvite_stillTruncatesAndKeepsStatus() throws InterruptedException {
        ExternalCreator row = newRow();
        row.markInvited("creator@example.com");
        var firstInvitedAt = row.getInvitedAt();

        Thread.sleep(1100); // ensure a real, observable second-boundary change

        row.markInvited("creator@example.com");

        assertEquals(ExternalCreatorStatus.INVITED, row.getStatus());
        assertEquals(0, row.getInvitedAt().getNano(), "re-invite must also truncate to whole seconds");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                firstInvitedAt,
                row.getInvitedAt(),
                "a re-invite must move invitedAt forward — this is exactly what invalidates any"
                        + " previously-issued invite token per InviteTokenService's javadoc");
    }
}
