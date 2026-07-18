package com.influora.web.dto.admin;

import java.time.Instant;
import java.util.List;

/**
 * Error-log console response records. Field names match {@code ErrorLogEntry} and the inline stats
 * shape in {@code src/admin/services/api-contracts.ts} ({@code errorApi}, lines 654-671). Raw DTOs
 * (no {@link com.influora.common.ApiResponse} envelope), same convention as every other
 * {@code Admin*Controller}.
 */
public final class AdminErrorLogDtos {

    private AdminErrorLogDtos() {}

    /** Mirrors {@code ErrorLogEntry} in admin.types.ts. {@code severity} is the enum name. */
    public record ErrorLogEntryDto(
            String id,
            String severity,
            String message,
            String stackTrace,
            String endpoint,
            String userId,
            boolean resolved,
            String resolvedBy,
            Instant resolvedAt,
            Instant createdAt) {}

    /** Mirrors {@code errorApi.getStats()}'s inline response shape. */
    public record ErrorStatsDto(
            long total24h, long critical24h, long unresolved, List<EndpointCountDto> topEndpoints) {}

    /** One row of {@code topEndpoints}. */
    public record EndpointCountDto(String endpoint, long count) {}
}
