package com.influora.web.dto.admin;

import java.time.Instant;
import java.util.List;

/**
 * Email-queue console response records. Field names match {@code EmailQueueItem} and the inline
 * template / stats shapes in {@code src/admin/services/api-contracts.ts} ({@code emailApi}, lines
 * 677-706). Backed entirely by the existing {@code email_outbox} table (Domain B) — no new queue
 * table was added. Raw DTOs (no {@link com.influora.common.ApiResponse} envelope).
 */
public final class AdminEmailDtos {

    private AdminEmailDtos() {}

    /**
     * Mirrors {@code EmailQueueItem} in admin.types.ts. Mapping from {@code EmailOutbox}:
     * {@code recipient <- toEmail}, {@code templateId/templateName <- templateKey} (no display-name
     * store exists, see {@code AdminEmailService.toDto}), {@code status} derives RETRYING from a
     * PENDING row with retryCount &gt; 0, {@code scheduledAt <- nextRetryAt ?? createdAt}.
     */
    public record EmailQueueItemDto(
            String id,
            String recipient,
            String templateId,
            String templateName,
            String status,
            int retryCount,
            Instant scheduledAt,
            Instant sentAt,
            String errorMessage) {}

    /** Matches {@code PaginatedResponse<T>} in admin.types.ts exactly. */
    public record PagedEmailQueueDto(
            List<EmailQueueItemDto> data, long total, int page, int pageSize, int totalPages) {}

    /** Mirrors {@code emailApi.getTemplates()}'s {@code { id, name, subject }} shape. */
    public record EmailTemplateDto(String id, String name, String subject) {}

    /** Mirrors {@code emailApi.getStats()}'s inline response shape. */
    public record EmailStatsDto(long sent24h, long failed24h, long pending, double avgDeliveryTime) {}
}
