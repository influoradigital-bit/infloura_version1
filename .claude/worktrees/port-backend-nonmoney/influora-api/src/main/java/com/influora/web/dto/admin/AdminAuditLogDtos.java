package com.influora.web.dto.admin;

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
            Instant timestamp) {}

    /** Matches {@code PaginatedResponse<T>} in admin.types.ts exactly. */
    public record PagedAuditLogDto(
            List<AuditLogEntryDto> data, long total, int page, int pageSize, int totalPages) {}
}
