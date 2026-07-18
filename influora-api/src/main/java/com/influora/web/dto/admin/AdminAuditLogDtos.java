package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Response records for {@code AuditLogController} (P2, cycle 7 — last of Vikram's
 * TASK_ASSIGNMENTS.md controller list). Field names/shape match {@code AuditLogEntry}/{@code
 * PaginatedResponse<T>} in {@code src/admin/types/admin.types.ts} exactly, same convention as
 * {@code AdminBrandDtos}/{@code AdminSupportDtos}. {@code AuditLogEntryDto.oldValue}/{@code
 * .newValue} carry the raw allow-listed JSON text {@code AdminAuditLogService} already wrote
 * (field-filtered at write time — see that class's Rule 2) — this DTO does not re-filter or
 * re-shape it, it is passed through as-is.
 */
public final class AdminAuditLogDtos {

    private AdminAuditLogDtos() {}

    public record AuditLogEntryDto(
            String id,
            String adminId,
            String adminEmail,
            String action,
            String entityType,
            String entityId,
            String oldValue,
            String newValue,
            String reason,
            String ipAddress,
            /** {@code SERVER_INTERNAL} | {@code CLIENT_REPORTED} (Kabir 2.1) — see {@code
             * AdminAuditLogSource} javadoc. Serialized as the enum's literal name so the admin SPA
             * can show/filter on provenance instead of treating every row as equally authoritative. */
            String source,
            Instant timestamp) {}

    /** Matches {@code PaginatedResponse<T>} in admin.types.ts exactly. */
    public record PagedAuditLogDto(
            List<AuditLogEntryDto> data, long total, int page, int pageSize, int totalPages) {}

    /**
     * {@code POST /admin/audit} request body — matches {@code AuditLogInput} in {@code
     * src/admin/utils/auditLogger.ts} exactly ({@code Pick<AuditLogEntry, 'adminId' | 'action' |
     * 'entityType' | 'entityId'> & Partial<Pick<AuditLogEntry, 'oldValue' | 'newValue' |
     * 'reason'>>}). {@code adminId} is declared here so the client's existing payload shape
     * deserializes cleanly, but {@code AdminAuditLogService#recordClientEntry} deliberately never
     * reads it — per that service's Rule 1 (server-derived identity), {@code adminId}/{@code
     * adminEmail}/{@code ipAddress}/{@code id}/{@code timestamp} are always re-resolved from the
     * authenticated principal and the request, never trusted from the client.
     */
    public record CreateAuditLogEntryRequest(
            String adminId,
            @NotBlank String action,
            @NotBlank String entityType,
            // Kabir 2.4: matches admin_audit_log.entity_id's VARCHAR(26) column width (ULID
            // length) so an over-length id is a clean 400 here, not a silent DB-mode-dependent
            // drop/truncation on write.
            @NotBlank @Size(max = 26) String entityId,
            String oldValue,
            String newValue,
            String reason) {}
}
