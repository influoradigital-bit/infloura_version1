package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Invoice;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UsageMetric;
import com.influora.repository.BrandAiCreditRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.WorkspaceMemberService;
import com.influora.service.billing.InvoiceService;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.billing.UsageCounterService;
import com.influora.web.dto.billing.BillingDtos.CheckoutRequest;
import com.influora.web.dto.billing.BillingDtos.CheckoutResponse;
import com.influora.web.dto.billing.BillingDtos.InvoiceResponse;
import com.influora.web.dto.billing.BillingDtos.PlanDto;
import com.influora.web.dto.billing.BillingDtos.PlanStatusResponse;
import com.influora.web.dto.billing.BillingDtos.SubscriptionDto;
import com.influora.web.dto.billing.BillingDtos.UsageSummaryResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand-facing billing surface — plan, invoices, usage meters. Task 18/26 (read-only slice)
 * subscription-billing functional core, Phase 1.
 *
 * <p>Every read is brand-workspace-scoped via {@code BrandContextService.requireBrandWorkspace}
 * (TECH-STACK.md rule #2 — resolve the row, then check ownership; never trust a path param
 * alone). Mounted at {@code /billing} (full path {@code /api/v1/billing}).
 *
 * <p>Write endpoints ({@code /checkout}, {@code /cancel}) are REAL — Phase 2 (Task 19/20) wired
 * them to real Razorpay Subscriptions flows via {@link SubscriptionService#initiateCheckout} and
 * {@link SubscriptionService#cancel}. (Stale note removed [Track B, P2, 2026-07-21]: an earlier
 * revision of this javadoc claimed these throw a typed {@code NOT_YET_IMPLEMENTED} — that was true
 * only before Task 19/20 landed and is no longer accurate; both methods can still throw a typed
 * {@link com.influora.common.ApiException} for real business-rule failures — e.g. {@code
 * ALREADY_SUBSCRIBED}, {@code RAZORPAY_MISCONFIGURED}, {@code SUBSCRIPTION_NOT_FOUND} — which
 * {@link com.influora.common.GlobalExceptionHandler} still translates into the standard {@code
 * ApiResponse} envelope, so no local try/catch is needed here.)
 */
@RestController
@RequestMapping("/billing")
public class BillingController {

    private final BrandContextService brandContextService;
    private final SubscriptionService subscriptionService;
    private final InvoiceService invoiceService;
    private final UsageCounterService usageCounterService;
    private final BrandAiCreditRepository brandAiCreditRepository;
    private final WorkspaceMemberService workspaceMemberService;

    public BillingController(
            BrandContextService brandContextService,
            SubscriptionService subscriptionService,
            InvoiceService invoiceService,
            UsageCounterService usageCounterService,
            BrandAiCreditRepository brandAiCreditRepository,
            WorkspaceMemberService workspaceMemberService) {
        this.brandContextService = brandContextService;
        this.subscriptionService = subscriptionService;
        this.invoiceService = invoiceService;
        this.usageCounterService = usageCounterService;
        this.brandAiCreditRepository = brandAiCreditRepository;
        this.workspaceMemberService = workspaceMemberService;
    }

    /** Current plan + subscription status/period for the caller's workspace. Lazily provisions Free. */
    @GetMapping("/plan")
    public ResponseEntity<ApiResponse<PlanStatusResponse>> getPlan(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        Subscription subscription = subscriptionService.getOrCreateFreeSubscription(workspace.getId());
        Plan plan = subscriptionService.getActivePlanForWorkspace(workspace.getId());
        return ResponseEntity.ok(ApiResponse.ok(toPlanStatusResponse(plan, subscription)));
    }

    /** Billing history for the caller's workspace, most recent first. */
    @GetMapping("/invoices")
    public ResponseEntity<ApiResponse<List<InvoiceResponse>>> getInvoices(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        List<InvoiceResponse> invoices =
                invoiceService.getInvoicesForWorkspace(workspace.getId()).stream()
                        .map(BillingController::toInvoiceResponse)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(invoices));
    }

    /** Current-billing-cycle usage meters: tracked creators, analytics views, exports, AI credits. */
    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<UsageSummaryResponse>> getUsage(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        String workspaceId = workspace.getId();
        Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);

        int trackedCreatorsUsed =
                usageCounterService.getUsageForCurrentPeriod(workspaceId, UsageMetric.TRACKED_CREATOR);
        int analyticsViewsUsed =
                usageCounterService.getUsageForCurrentPeriod(workspaceId, UsageMetric.CREATOR_ANALYTICS_VIEW);
        int exportsUsed = usageCounterService.getUsageForCurrentPeriod(workspaceId, UsageMetric.EXPORT);

        int aiCreditsRemaining = 0;
        int aiCreditsMonthlyAllotment = plan.getAiMonthlyAllotment();
        BrandAiCredit aiCredit = brandAiCreditRepository.findByWorkspaceId(workspaceId).orElse(null);
        if (aiCredit != null) {
            aiCreditsRemaining = aiCredit.getCreditsRemaining();
            aiCreditsMonthlyAllotment = aiCredit.getMonthlyAllotment();
        }

        long activeSeatsUsed = workspaceMemberService.getActiveSeatCount(workspaceId);

        UsageSummaryResponse response =
                new UsageSummaryResponse(
                        LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString(),
                        trackedCreatorsUsed,
                        plan.getTrackedCreatorLimit(),
                        analyticsViewsUsed,
                        plan.getCreatorAnalyticsMonthlyLimit(),
                        exportsUsed,
                        plan.isExportEnabled(),
                        aiCreditsRemaining,
                        aiCreditsMonthlyAllotment,
                        activeSeatsUsed);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** Ownership-checked invoice PDF download, rendered on demand via {@code InvoicePdfService}. */
    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> getInvoicePdf(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String invoiceId) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        byte[] pdf = invoiceService.getInvoicePdf(invoiceId, workspace.getId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"invoice-" + invoiceId + ".pdf\"")
                .body(pdf);
    }

    /**
     * Phase 2 (Task 19) — real Razorpay Subscriptions hosted-checkout URL.
     *
     * <p>BL-4 (BrandF.md §105, static-certain): {@code requireBrandWorkspace} only checks the
     * caller is a BRAND user with a resolvable workspace — it never inspects {@link MemberRole},
     * so any active member (including VIEWER) could start the company's paid subscription. Same
     * {@code requireMember} + {@code requireRole(OWNER, ADMIN)} two-step already established at
     * {@code WorkspaceMemberService.java:127-130} for workspace-mutating actions, and the pattern
     * the wallet/escrow path uses ({@code EscrowController#fund}).
     */
    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CheckoutRequest body) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        WorkspaceMember actingMember = brandContextService.requireMember(principal, workspace.getId());
        brandContextService.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);
        String checkoutUrl =
                subscriptionService.initiateCheckout(workspace.getId(), parsePlanCode(body.planCode()));
        return ResponseEntity.ok(ApiResponse.ok(new CheckoutResponse(checkoutUrl)));
    }

    /** Phase 2 (Task 19/20) — real Razorpay Subscriptions cancel-at-period-end. BL-4: same role gate as {@link #checkout}. */
    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(@AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        WorkspaceMember actingMember = brandContextService.requireMember(principal, workspace.getId());
        brandContextService.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);
        subscriptionService.cancel(workspace.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * BL-1: an unrecognized {@code planCode} is now rejected with 400 {@code INVALID_PLAN_CODE}
     * instead of being silently coerced to {@code PRO}. The coercion previously meant garbage
     * input still reached {@code SubscriptionService#initiateCheckout} /
     * {@code ensureRazorpayPlanId} / {@code createSubscription}, creating real Razorpay-side
     * objects for a plan the caller never actually specified.
     */
    private static com.influora.domain.enums.PlanCode parsePlanCode(String raw) {
        try {
            return com.influora.domain.enums.PlanCode.valueOf(raw);
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_PLAN_CODE", "Unrecognized planCode: " + raw, HttpStatus.BAD_REQUEST);
        }
    }

    private static PlanStatusResponse toPlanStatusResponse(Plan plan, Subscription subscription) {
        PlanDto planDto =
                new PlanDto(
                        plan.getCode().name(),
                        plan.getName(),
                        plan.getPriceInr(),
                        plan.getBillingCycle().name(),
                        plan.getFeeBps(),
                        plan.getAiMonthlyAllotment(),
                        plan.getSeatLimit(),
                        plan.getTrackedCreatorLimit(),
                        plan.getCreatorAnalyticsMonthlyLimit(),
                        plan.isExportEnabled(),
                        plan.isCampaignTemplatesEnabled());
        SubscriptionDto subscriptionDto =
                new SubscriptionDto(
                        subscription.getStatus().name(),
                        subscription.getCurrentPeriodStart(),
                        subscription.getCurrentPeriodEnd(),
                        subscription.isCancelAtPeriodEnd());
        return new PlanStatusResponse(planDto, subscriptionDto);
    }

    private static InvoiceResponse toInvoiceResponse(Invoice invoice) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getAmount(),
                invoice.getStatus().name(),
                invoice.getPeriodStart(),
                invoice.getPeriodEnd(),
                invoice.getIssuedAt(),
                invoice.getPaidAt(),
                "/billing/invoices/" + invoice.getId() + "/pdf");
    }
}
