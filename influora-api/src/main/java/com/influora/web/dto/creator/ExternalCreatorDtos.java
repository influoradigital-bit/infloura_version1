package com.influora.web.dto.creator;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Brand-facing DTOs for {@code ExternalCreatorController} (T-CREATORCONNECT-0902,
 * .proof-os/tasks/T-CREATORCONNECT-0902/TASKS.md §Backend API contract). Mirrored field-for-field
 * by {@code src/lib/api.ts}'s {@code ExternalCreator}/{@code ConnectionRequest} — do not rename a
 * field or change its nullability without updating that file (Ananya owns it in parallel).
 */
public final class ExternalCreatorDtos {

    private ExternalCreatorDtos() {}

    /** Nullable metric fields NEVER coerce to 0 — absent means "not yet enriched" (F-0259/F-0260). */
    public record ExternalCreatorResponse(
            String id,
            String source,
            String igUsername,
            String displayName,
            String bio,
            String avatarUrl,
            Long followers,
            Long mediaCount,
            BigDecimal engagementRate,
            String country,
            List<String> categories,
            String status,
            boolean verifiedWithInfluora,
            String linkedCreatorProfileId,
            String connectionStatus,
            String connectionRequestId,
            Instant lastSyncedAt,
            // Q2.5 — additive: without this the INVITED badge has no signal to distinguish itself
            // from UNVERIFIED and the brand panel can never show "invited on {date}". Appended at
            // the end (not inserted mid-record) so this stays a source-compatible change for any
            // caller still constructing the record positionally.
            Instant invitedAt) {}

    public record ConnectionRequestResponse(
            String id,
            String externalCreatorId,
            String igUsername,
            String displayName,
            String avatarUrl,
            String message,
            String status,
            Instant createdAt,
            Instant handledAt,
            Instant updatedAt,
            String linkedCreatorProfileId) {}

    public record ConnectRequest(@Size(max = 1000, message = "Message must be at most 1000 characters") String message) {}
}
