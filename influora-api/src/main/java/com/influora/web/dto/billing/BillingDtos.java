package com.influora.web.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * Read-only billing DTOs for {@code BillingController} (Task 18/26 subscription-billing
 * functional core, Phase 1). Never expose raw {@code Plan}/{@code Subscription}/{@code Invoice}
 * JPA entities over the wire — TECH-STACK.md DTO discipline.
 */
public final class BillingDtos {

    private BillingDtos() {}

    /** Plan shape — mirrors {@code Plan} entity fields, omitting {@code razorpayPlanId} (internal). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanDto(
            String code,
            String name,
            int priceInr,
            String billingCycle,
            Integer feeBps,
            int aiMonthlyAllotment,
            int seatLimit,
            Integer trackedCreatorLimit,
            Integer creatorAnalyticsMonthlyLimit,
            boolean exportEnabled,
            boolean campaignTemplatesEnabled) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubscriptionDto(
            String status,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd) {}

    /** {@code GET /billing/plan} response — the workspace's active plan + its subscription state. */
    public record PlanStatusResponse(PlanDto plan, SubscriptionDto subscription) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvoiceResponse(
            String id,
            int amount,
            String status,
            Instant periodStart,
            Instant periodEnd,
            Instant issuedAt,
            Instant paidAt,
            String pdfDownloadUrl) {}

    /** {@code GET /billing/usage} response — the current-cycle meters the billing settings page needs. */
    public record UsageSummaryResponse(
            String periodStart,
            int trackedCreatorsUsed,
            Integer trackedCreatorLimit,
            int analyticsViewsUsed,
            Integer analyticsViewsLimit,
            int exportsUsed,
            boolean exportEnabled,
            int aiCreditsRemaining,
            int aiCreditsMonthlyAllotment,
            // Task 23 — real "N/seatLimit seats used" meter (seatLimit itself is already on
            // PlanDto from GET /billing/plan; this is just the live active-member count).
            long activeSeatsUsed) {}

    /** BL-1: {@code @NotBlank} + {@code @Valid} on the controller param — an empty/missing
     * {@code planCode} is now rejected at the request-validation layer instead of falling through
     * to {@code parsePlanCode}'s coercion. */
    public record CheckoutRequest(@NotBlank String planCode) {}

    public record CheckoutResponse(String checkoutUrl) {}
}
