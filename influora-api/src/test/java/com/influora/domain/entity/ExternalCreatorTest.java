package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.domain.enums.ExternalCreatorStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Q5.2 (T-CREATORCONNECT-0902, Medium) — {@link ExternalCreator#markJoined(String)} must be
 * genuinely idempotent, not just re-runnable. Before this fix, a second call for a row already
 * {@code JOINED} to the SAME {@code creatorProfileId} (the exact shape {@code
 * MetaTokenRefreshService}'s ~55-day background token refresh produces, re-firing the JOINED hook
 * on every rotation) unconditionally re-stamped both {@code joinedAt} and {@code updatedAt}: the
 * real join date silently drifted forward forever, and {@code
 * ExternalCreatorService#list}'s {@code updatedAt DESC} sort bumped that creator back to the top
 * of every brand's Discover page on a background write nobody asked for.
 */
class ExternalCreatorTest {

    private ExternalCreator newRow() {
        return ExternalCreator.builder()
                .id("01HEXTCREATOR000000001")
                .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                .igUsername("foodie.mumbai")
                .build();
    }

    @Test
    @DisplayName("markJoined: first call sets status, linkedCreatorProfileId, joinedAt and updatedAt")
    void firstCall_setsStatusAndTimestamps() {
        ExternalCreator row = newRow();

        row.markJoined("01HCREATORPROFILE00001");

        assertEquals(ExternalCreatorStatus.JOINED, row.getStatus());
        assertEquals("01HCREATORPROFILE00001", row.getLinkedCreatorProfileId());
        org.junit.jupiter.api.Assertions.assertNotNull(row.getJoinedAt());
    }

    @Test
    @DisplayName(
            "markJoined: a repeat call for the SAME creatorProfileId (e.g. a Meta token refresh"
                    + " re-firing the hook) is a true no-op on joinedAt/updatedAt")
    void repeatCall_sameProfile_preservesOriginalTimestamps() throws InterruptedException {
        ExternalCreator row = newRow();
        row.markJoined("01HCREATORPROFILE00001");
        Instant originalJoinedAt = row.getJoinedAt();
        Instant originalUpdatedAt = row.getUpdatedAt();

        // Ensure Instant.now() would actually differ if the bug were still present.
        Thread.sleep(20);

        row.markJoined("01HCREATORPROFILE00001");

        assertEquals(originalJoinedAt, row.getJoinedAt(), "joinedAt must not drift on a repeat call");
        assertEquals(
                originalUpdatedAt,
                row.getUpdatedAt(),
                "updatedAt must not bump — otherwise a background token refresh silently re-sorts"
                        + " this creator to the top of every brand's Discover list");
    }

    @Test
    @DisplayName("markJoined: linking to a DIFFERENT creatorProfileId still re-stamps timestamps")
    void call_withDifferentProfile_stillUpdatesTimestamps() throws InterruptedException {
        ExternalCreator row = newRow();
        row.markJoined("01HCREATORPROFILE00001");
        Instant originalJoinedAt = row.getJoinedAt();
        Thread.sleep(20);

        row.markJoined("01HCREATORPROFILE00002");

        assertEquals("01HCREATORPROFILE00002", row.getLinkedCreatorProfileId());
        assertNotEquals(originalJoinedAt, row.getJoinedAt());
    }
}
