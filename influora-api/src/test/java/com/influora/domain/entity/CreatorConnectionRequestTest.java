package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.influora.domain.enums.ConnectionRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Q3.2 / Q5.2 (T-CREATORCONNECT-0902, High / Medium) — {@link CreatorConnectionRequest#reopen}.
 *
 * <p>Q3.2: reopening a DECLINED row used to always keep the ORIGINAL {@code requestedByUserId}, so
 * the later {@code brand.connected_creator_joined} email reached whoever made the FIRST request
 * even when a different workspace member re-requested — and was silently lost if that original
 * user had since been removed from the workspace. {@link
 * CreatorConnectionRequest#reopen(String, String)} now re-stamps the requester to whoever is
 * reopening now.
 *
 * <p>Q5.2: {@code reopen} used to always null out {@code joinedNotifiedAt}, disarming the
 * listener's idempotency guard — so a background Meta token refresh that re-fires the JOINED hook
 * after a reopen would re-flip the request and re-email the brand a SECOND time for a creator it
 * was already told about. {@code joinedNotifiedAt} must survive a reopen.
 */
class CreatorConnectionRequestTest {

    private CreatorConnectionRequest newRequest() {
        return CreatorConnectionRequest.builder()
                .id("01HREQUEST0000000001")
                .workspaceId("01HWORKSPACE0000000001")
                .requestedByUserId("01HUSERORIGINAL000001")
                .externalCreatorId("01HEXTCREATOR000000001")
                .message("original message")
                .build();
    }

    @Test
    @DisplayName("reopen(message, userId): re-stamps requestedByUserId to the caller reopening now")
    void reopenWithUserId_updatesRequestedByUserId() {
        CreatorConnectionRequest request = newRequest();
        request.markDeclined("01HADMIN0000000000001", "not a fit");

        request.reopen("please reconsider", "01HUSERDIFFERENT000002");

        assertEquals(ConnectionRequestStatus.PENDING, request.getStatus());
        assertEquals("01HUSERDIFFERENT000002", request.getRequestedByUserId());
        assertEquals("please reconsider", request.getMessage());
        assertNull(request.getAdminNotes());
        assertNull(request.getHandledBy());
        assertNull(request.getHandledAt());
    }

    @Test
    @DisplayName("reopen(message, userId): a blank/null userId leaves the existing requester untouched")
    void reopenWithBlankUserId_preservesExistingRequester() {
        CreatorConnectionRequest request = newRequest();
        request.markDeclined("01HADMIN0000000000001", "not a fit");

        request.reopen("please reconsider", null);

        assertEquals("01HUSERORIGINAL000001", request.getRequestedByUserId());
    }

    @Test
    @DisplayName(
            "reopen: does NOT clear joinedNotifiedAt — once a brand has genuinely been told a"
                    + " creator joined, a reopen must not re-arm a second email")
    void reopen_preservesJoinedNotifiedAt() {
        CreatorConnectionRequest request = newRequest();
        request.markJoined();
        request.markJoinedNotified();
        assertNotNull(request.getJoinedNotifiedAt());
        var stampedAt = request.getJoinedNotifiedAt();

        request.reopen("re-requesting after a JOINED->DECLINED cycle", "01HUSERDIFFERENT000002");

        assertEquals(
                stampedAt,
                request.getJoinedNotifiedAt(),
                "joinedNotifiedAt must survive a reopen — it is a permanent 'brand already told'"
                        + " marker, not per-request-cycle state");
    }

    @SuppressWarnings("deprecation")
    @Test
    @DisplayName("reopen(message) [deprecated 1-arg overload]: still compiles, keeps existing requester")
    void deprecatedSingleArgOverload_stillWorks() {
        CreatorConnectionRequest request = newRequest();
        request.markDeclined("01HADMIN0000000000001", "not a fit");

        request.reopen("re-request, no acting-user threaded through yet");

        assertEquals(ConnectionRequestStatus.PENDING, request.getStatus());
        assertEquals("01HUSERORIGINAL000001", request.getRequestedByUserId());
    }
}
