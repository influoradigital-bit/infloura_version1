package com.influora.web;

import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminBillingService;
import com.influora.web.dto.admin.AdminBillingDtos.AdminSubscriptionActionResultDto;
import com.influora.web.dto.admin.AdminBillingDtos.BillingMetricsDto;
import com.influora.web.dto.admin.AdminBillingDtos.CompSubscriptionRequest;
import com.influora.web.dto.admin.AdminBillingDtos.OverrideSubscriptionRequest;
import com.influora.web.dto.admin.AdminBillingDtos.PaginatedSubscriptionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin billing console backend (Task 25, {@code
 * wiki/processes/subscription-billing-task-breakdown.md} Phase 4b). Mounted at {@code
 * /admin/billing} (full path {@code /api/v1/admin/billing/...}), routed here after Phases 1-4a
 * (repositories/services/Razorpay checkout+webhooks/dunning+renewal safety nets) were built and
 * CTO-signed-off end-to-end.
 *
 * <p><b>This is the first NON-webhook money-state writer in the codebase</b> — every previous
 * {@code Subscription} row mutation was either derived from a verified Razorpay webhook ({@link
 * com.influora.service.billing.SubscriptionService#applySubscriptionWebhookUpdate}) or a scheduled
 * job's safety net. {@code /comp} and {@code /override} let a human admin directly flip a
 * workspace's plan/status with no payment processor involved. Per the routing note this shipped
 * under, this is a MANDATORY Kabir red-team gate before Priya sign-off — same discipline as every
 * other money-lifecycle change in this codebase (Phase 2/3a/4a).
 *
 * <p><b>Role-gating:</b> SUPER_ADMIN only (not SUPER_ADMIN+ADMIN) — same reasoning as {@code
 * PlatformFeeAdminController}: this panel can hand out real product entitlements (400 AI
 * credits/5 seats/7% fee) for free, a strictly higher blast radius than brand/creator moderation.
 * Enforced inside {@code AdminBillingService} via {@code AdminContextService
 * #requireRoleWithMfaSatisfied} on every method — this controller is deliberately thin and does no
 * gating of its own, matching every other {@code Admin*Controller} in this codebase (no {@code
 * @PreAuthorize} usage anywhere in {@code com.influora}).
 *
 * <p><b>Admin identity is always server-derived</b> from the authenticated {@code AuthPrincipal},
 * never accepted from the request body — {@code CompSubscriptionRequest}/{@code
 * OverrideSubscriptionRequest} have no admin-id field to begin with, and {@code
 * AdminBillingService} resolves the granting admin's id from {@code AdminContextService
 * #requireRoleWithMfaSatisfied}'s return value, same pattern as {@code PlatformFeeAdminService
 * #update}.
 *
 * <p><b>Reason is mandatory, never optional</b>, on both {@code /comp} and {@code /override} —
 * {@code @Size(min = 10, ...)} bean validation on the request DTOs. An admin cannot grant/adjust a
 * subscription with no documented justification; every grant is also written to {@code
 * admin_audit_log} (action {@code TIER_ADJUST} for comp, {@code BUDGET_OVERRIDE} for override,
 * entity type {@code SUBSCRIPTION} — the first real call sites for those two actions, previously
 * declared in {@code AdminAuditLogService.ALLOWED_ACTIONS} with zero callers).
 *
 * <p><b>Comp vs. override — same mechanism, different framing.</b> Both endpoints call {@link
 * com.influora.service.billing.SubscriptionService#grantAdminPlan} — the single shared "assign
 * this workspace a plan without a real Razorpay subscription" primitive, so the two admin actions
 * can never drift into duplicate activation logic (MP-1's origin incident was exactly two
 * independent code paths flipping the same entity state, one of which silently skipped a step the
 * other did). The only difference between {@code /comp} and {@code /override} is which audit
 * action gets recorded and, presumably, how the eventual admin UI frames the two flows to the
 * operator (comp = "give this workspace Pro for free"; override = "correct/adjust this workspace's
 * plan assignment").
 *
 * <p><b>Override scope — deliberately narrowed for this first pass.</b> The task breakdown
 * describes {@code /override} as adjusting "a workspace's fee or AI-credit allotment" without
 * specifying whether that means (a) reassigning which {@code Plan} a workspace's {@code
 * Subscription} points at, or (b) mutating a single numeric value (e.g. "this ONE workspace gets
 * 6% instead of 7%/10%") while leaving their existing {@code Plan} untouched. Option (b) is
 * materially more schema work: {@code Plan} rows are SHARED across every workspace on a given tier
 * ({@code plans.fee_bps}/{@code ai_monthly_allotment} apply to every Free or every Pro workspace
 * alike per V54's schema) — mutating a shared row to special-case one workspace would corrupt
 * every OTHER workspace on that tier, so a true per-workspace numeric override needs a brand-new
 * override table/column (e.g. {@code workspace_billing_overrides}), not a {@code Subscription}
 * field. This implementation ships ONLY option (a) — {@code /override} reassigns which {@code
 * Plan} (FREE or PRO) a workspace's subscription points at, via the exact same {@link
 * com.influora.service.billing.SubscriptionService#grantAdminPlan} mechanism as {@code /comp}.
 * True per-workspace numeric overrides (distinct fee % or seat/credit caps independent of Free vs.
 * Pro) are explicitly OUT of scope here and would need a separate follow-up task with its own
 * schema design — flagged for Arjun/Priya to confirm this narrowing is acceptable for Phase 4b.
 *
 * <p><b>Known gap — comp expiry is not auto-enforced.</b> {@code compExpiresAt} is stored ({@code
 * V63__subscription_comp_fields.sql}) and used as the row's {@code currentPeriodEnd}, but neither
 * {@code SubscriptionRenewalResetJob} nor {@code SubscriptionDunningJob} distinguishes comp rows
 * from real ones — a comp past its {@code compExpiresAt} will currently be picked up by {@code
 * SubscriptionRenewalResetJob}'s "stale ACTIVE subscription" sweep and have its period silently
 * ROLLED FORWARD (extended), not downgraded back to Free. Building real expiry enforcement (a new
 * job, or teaching the existing renewal job to treat {@code isComp=true} differently) is out of
 * scope for this task per the "narrow first pass" instruction — flagged explicitly here and in the
 * handoff so it isn't mistaken for working expiry enforcement.
 */
@RestController
@RequestMapping("/admin/billing")
public class AdminBillingController {

    private final AdminBillingService adminBillingService;

    public AdminBillingController(AdminBillingService adminBillingService) {
        this.adminBillingService = adminBillingService;
    }

    @GetMapping("/subscriptions")
    public PaginatedSubscriptionResponse listSubscriptions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return adminBillingService.listSubscriptions(principal, page, pageSize, status, search);
    }

    @GetMapping("/metrics")
    public BillingMetricsDto metrics(@AuthenticationPrincipal AuthPrincipal principal) {
        return adminBillingService.getMetrics(principal);
    }

    @PostMapping("/comp")
    public AdminSubscriptionActionResultDto comp(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @Valid @RequestBody CompSubscriptionRequest body) {
        return adminBillingService.grantComp(principal, request, body);
    }

    @PostMapping("/override")
    public AdminSubscriptionActionResultDto override(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request,
            @Valid @RequestBody OverrideSubscriptionRequest body) {
        return adminBillingService.overridePlan(principal, request, body);
    }
}
