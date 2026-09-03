package com.influora.web.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Admin-facing DTOs for {@code AdminCreatorConnectionController} (T-CREATORCONNECT-0902,
 * .proof-os/tasks/T-CREATORCONNECT-0902/TASKS.md §Backend API contract). Same deliberate
 * deviation as every other {@code Admin*Controller}: raw DTOs, no {@link com.influora.common.ApiResponse}
 * envelope (matches {@code AdminCreatorController}). Mirrored field-for-field by {@code
 * src/admin/types/admin.types.ts}'s {@code AdminConnection}/{@code AdminExternalCreator}/{@code
 * PagedConnections} — Ananya's code already exists against this exact shape; do not rename a
 * field or change its nullability.
 */
public final class AdminCreatorConnectionDtos {

    private AdminCreatorConnectionDtos() {}

    public record AdminConnectionDto(
            String id,
            String status,
            String message,
            String adminNotes,
            String workspaceId,
            String brandName,
            String requestedByUserId,
            String requestedByEmail,
            String externalCreatorId,
            String igUsername,
            String displayName,
            String avatarUrl,
            Long followers,
            String creatorEmail,
            String creatorStatus,
            String linkedCreatorProfileId,
            Instant createdAt,
            Instant handledAt,
            Instant joinedNotifiedAt) {}

    /** {@code PagedConnectionsDto} — deliberately distinct from the brand-facing {@code
     * ApiResponse<T> + PageMeta} envelope: {@code items}, not {@code data}; no {@code totalPages}. */
    public record PagedConnectionsDto<T>(List<T> items, int page, int pageSize, long total) {}

    public record AdminExternalCreatorDto(
            String id,
            String source,
            String igUsername,
            String displayName,
            String avatarUrl,
            Long followers,
            java.math.BigDecimal engagementRate,
            String country,
            String email,
            String status,
            String linkedCreatorProfileId,
            Instant invitedAt,
            Instant joinedAt,
            Instant lastSyncedAt,
            Instant createdAt) {}

    public record NotesRequest(@Size(max = 1000, message = "Notes must be at most 1000 characters") String notes) {}

    public record InviteRequest(
            @NotBlank(message = "Email is required") @Email(message = "A valid email is required") String email,
            @Size(max = 1000, message = "Notes must be at most 1000 characters") String notes) {}

    public record ImportRequest(List<String> usernames) {}

    public record ImportResult(int imported, int enriched, List<String> skipped) {}
}
