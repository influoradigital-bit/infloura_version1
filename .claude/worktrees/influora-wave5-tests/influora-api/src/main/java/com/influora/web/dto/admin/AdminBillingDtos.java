package com.influora.web.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request/response records for {@code AdminBillingController} (Task 25 subscription-billing Phase
 * 4b — admin billing console backend). No pre-existing frontend types to match yet (Ananya's Task
 * 25 FE half is a separate, not-yet-built cycle per {@code
 * wiki/processes/subscription-billing-task-breakdown.md}) — shapes here are this task's own
 * design, kept close to the sibling {@code AdminBrandDtos}/{@code PlatformFeeConfigDtos}
 * conventions (paginated {@code data/total/page/pageSize/totalPages} envelope, percent/bps at the
 * DTO boundary never leaking storage representation) so the eventual FE task has a familiar shape
 * to build against.
 */
public final class AdminBillingDtos {

    private AdminBillingDtos() {}

    /** One row of {@code GET /admin/billing/subscriptions}. */
    public record AdminSubscriptionRowDto(
            String subscriptionId,
            String workspaceId,
            String workspaceName,
            String planCode,
            String status,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            int seatsPurchased,
            boolean isComp,
            String compReason,
            Instant compExpiresAt) {}

    /** Matches {@code AdminBrandDtos.PaginatedBrandResponse}/{@code AdminAuditLogDtos.PagedAuditLogDto} shape. */
    public record PaginatedSubscriptionResponse(
            List<AdminSubscriptionRowDto> data, long total, int page, int pageSize, int totalPages) {}

    /**
     * {@code GET /admin/billing/metrics}. See {@code AdminBillingService#getMetrics} javadoc for
     * the exact MRR/ARR/churn formulas — deliberately documented in ONE place (the code, not
     * duplicated prose here) per MP-1's "a comment is not coverage/truth, the call graph is"
     * discipline; this DTO just carries the numbers out.
     */
    public record BillingMetricsDto(
            BigDecimal mrrInr,
            BigDecimal arrInr,
            BigDecimal churnPercent,
            long activeProCount,
            long churnedInWindowCount,
            int churnWindowDays) {}

    /**
     * {@code POST /admin/billing/comp} — grant a complimentary Pro (or, in principle, any active
     * plan code) subscription to a workspace. {@code reason} is mandatory (Kabir will flag an
     * ungated "flip someone to Pro with no paper trail" capability) — {@code @Size(min = 10, ...)}
     * mirrors {@code PlatformFeeConfigDtos.UpdatePlatformFeeConfigRequest}'s same server-side
     * minimum, since client-side validation alone is never trusted.
     */
    public record CompSubscriptionRequest(
            @NotBlank String workspaceId,
            @NotBlank String planCode,
            @NotBlank @Size(min = 10, max = 2000) String reason,
            Instant expiresAt) {}

    /**
     * {@code POST /admin/billing/override} — see {@code AdminBillingController} class javadoc
     * "Override scope" note for why this is, for this first pass, identical in mechanism to {@link
     * CompSubscriptionRequest} (a plan reassignment), not a per-workspace numeric fee/limit
     * override.
     */
    public record OverrideSubscriptionRequest(
            @NotBlank String workspaceId,
            @NotBlank String planCode,
            @NotBlank @Size(min = 10, max = 2000) String reason,
            Instant expiresAt) {}

    /** Shared response shape for both {@code /comp} and {@code /override}. */
    public record AdminSubscriptionActionResultDto(
            String subscriptionId,
            String workspaceId,
            String planCode,
            String status,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            boolean isComp,
            String compReason,
            Instant compExpiresAt) {}
}
