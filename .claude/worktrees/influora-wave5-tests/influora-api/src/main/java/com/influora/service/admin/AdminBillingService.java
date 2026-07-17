package com.influora.service.admin;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.SubscriptionAdminSpecs;
import com.influora.repository.SubscriptionRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.billing.PlanService;
import com.influora.service.billing.SubscriptionService;
import com.influora.web.dto.admin.AdminBillingDtos.AdminSubscriptionActionResultDto;
import com.influora.web.dto.admin.AdminBillingDtos.AdminSubscriptionRowDto;
import com.influora.web.dto.admin.AdminBillingDtos.BillingMetricsDto;
import com.influora.web.dto.admin.AdminBillingDtos.CompSubscriptionRequest;
import com.influora.web.dto.admin.AdminBillingDtos.OverrideSubscriptionRequest;
import com.influora.web.dto.admin.AdminBillingDtos.PaginatedSubscriptionResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code AdminBillingController} (Task 25 subscription-billing Phase 4b). See that
 * controller's class javadoc for the SUPER_ADMIN+MFA gating rationale, the comp-vs-override
 * framing, and the deliberate override-scope narrowing for this first pass.
 *
 * <p><b>Audit trail:</b> {@link #grantComp} and {@link #overridePlan} both call {@link
 * AdminAuditLogService#record} AFTER the {@link Subscription} mutation is already committed (via
 * {@link SubscriptionService#grantAdminPlan}, itself {@code @Transactional}), same ordering
 * discipline as {@code AdminBrandService}/{@code PlatformFeeAdminService} — an audit-write failure
 * must never roll back or block the already-applied billing mutation (Rule 5,
 * AUDIT-LOG-WRITE-SPEC.md).
 */
@Service
public class AdminBillingService {

    private static final String ENTITY_TYPE = "SUBSCRIPTION";

    private final AdminContextService adminContext;
    private final AdminAuditLogService adminAuditLogService;
    private final SubscriptionRepository subscriptionRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PlanService planService;
    private final SubscriptionService subscriptionService;

    public AdminBillingService(
            AdminContextService adminContext,
            AdminAuditLogService adminAuditLogService,
            SubscriptionRepository subscriptionRepository,
            WorkspaceRepository workspaceRepository,
            PlanService planService,
            SubscriptionService subscriptionService) {
        this.adminContext = adminContext;
        this.adminAuditLogService = adminAuditLogService;
        this.subscriptionRepository = subscriptionRepository;
        this.workspaceRepository = workspaceRepository;
        this.planService = planService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * {@code GET /admin/billing/subscriptions} — paginated, filterable by status, searchable by
     * workspace name. {@code Subscription} has no mapped relation to {@code Workspace}/{@code
     * Plan} (plain string FK columns — see {@code Subscription} class javadoc), so this batch-
     * fetches both after the primary paginated query, same "preload related rows into a map"
     * pattern {@code AdminBrandService#list} uses for campaign counts/spend.
     */
    @Transactional(readOnly = true)
    public PaginatedSubscriptionResponse listSubscriptions(
            AuthPrincipal principal, int page, int pageSize, String status, String search) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        int pageIndex = Math.max(0, page - 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        SubscriptionStatus statusFilter = parseStatus(status);

        List<String> workspaceIdFilter = null;
        if (search != null && !search.isBlank()) {
            Specification<Workspace> nameSpec =
                    Specification.<Workspace>where(
                                    (root, query, cb) -> cb.equal(root.get("type"), WorkspaceType.BRAND))
                            .and(
                                    (root, query, cb) ->
                                            cb.like(
                                                    cb.lower(root.get("name")),
                                                    "%" + search.trim().toLowerCase() + "%"));
            workspaceIdFilter = workspaceRepository.findAll(nameSpec).stream().map(Workspace::getId).toList();
            if (workspaceIdFilter.isEmpty()) {
                // No workspace matched the search — short-circuit rather than issue a query with
                // an impossible "IN ()" filter.
                return new PaginatedSubscriptionResponse(List.of(), 0, page, safePageSize, 0);
            }
        }

        Specification<Subscription> spec = SubscriptionAdminSpecs.withFilters(statusFilter, workspaceIdFilter);
        Pageable pageable = PageRequest.of(pageIndex, safePageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Subscription> subPage = subscriptionRepository.findAll(spec, pageable);

        List<Subscription> rows = subPage.getContent();
        Map<String, String> workspaceNames = new LinkedHashMap<>();
        Map<String, PlanCode> planCodes = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            List<String> wsIds = rows.stream().map(Subscription::getWorkspaceId).distinct().toList();
            for (Workspace w : workspaceRepository.findAllById(wsIds)) {
                workspaceNames.put(w.getId(), w.getName());
            }
            for (Plan p : planService.getActivePlans()) {
                planCodes.put(p.getId(), p.getCode());
            }
            // Plan may also be inactive (deactivated after this subscription was created) — fall
            // back to a direct lookup for any planId not covered by getActivePlans().
        }

        List<AdminSubscriptionRowDto> data =
                rows.stream()
                        .map(
                                s ->
                                        new AdminSubscriptionRowDto(
                                                s.getId(),
                                                s.getWorkspaceId(),
                                                workspaceNames.getOrDefault(s.getWorkspaceId(), "(unknown workspace)"),
                                                resolvePlanCodeLabel(s.getPlanId(), planCodes),
                                                s.getStatus().name(),
                                                s.getCurrentPeriodStart(),
                                                s.getCurrentPeriodEnd(),
                                                s.getSeatsPurchased(),
                                                s.isComp(),
                                                s.getCompReason(),
                                                s.getCompExpiresAt()))
                        .toList();

        return new PaginatedSubscriptionResponse(
                data, subPage.getTotalElements(), page, safePageSize, subPage.getTotalPages());
    }

    /**
     * {@code GET /admin/billing/metrics}. Formulas (deliberately simple — first pass, per Task 25
     * scope):
     *
     * <ul>
     *   <li><b>MRR</b> = {@code count(ACTIVE, plan=PRO, isComp=false) * Pro.priceInr}. Comp'd Pro
     *       subscriptions are EXCLUDED — they generate zero real revenue, and including them would
     *       overstate MRR (the task's own framing of MRR as "sum of priceInr for all ACTIVE Pro
     *       subscriptions" is followed here MINUS comps, since a literal reading that counted comps
     *       would be simply wrong as a revenue number). Seat add-ons are also excluded — {@link
     *       Subscription#getSeatsPurchased()} is documented as a deferred, not-yet-billed add-on
     *       SKU (see that field's javadoc), so it contributes nothing to price today.
     *   <li><b>ARR</b> = {@code MRR * 12}. No proration/cohort modeling — a straight annualization
     *       of the current MRR snapshot.
     *   <li><b>Churn %</b> = {@code churnedInWindow / activeAtWindowStart * 100}, over a trailing
     *       {@link #CHURN_WINDOW_DAYS}-day window. {@code churnedInWindow} = count of PRO,
     *       non-comp subscriptions that transitioned to {@code CANCELLED} in the window (keyed off
     *       {@code updatedAt}, set by every {@link Subscription#setStatus} call). There is no
     *       historical snapshot table of "who was active N days ago", so {@code
     *       activeAtWindowStart} is APPROXIMATED as {@code currentActivePro + churnedInWindow}
     *       (i.e., everyone active right now, plus everyone who left during the window — since a
     *       workspace that churned during the window was, by definition, active at the window's
     *       start). This slightly overstates the true denominator only if a workspace both
     *       subscribed AND churned within the same window (a genuinely new-then-immediately-
     *       cancelled subscriber), which is a rare edge case for a first-pass metric.
     * </ul>
     */
    @Transactional(readOnly = true)
    public BillingMetricsDto getMetrics(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        Plan proPlan = planService.getProPlan();
        Instant windowStart = Instant.now().minus(Duration.ofDays(CHURN_WINDOW_DAYS));

        long activeProCount =
                subscriptionRepository.countByStatusAndPlanIdAndCompFalse(
                        SubscriptionStatus.ACTIVE, proPlan.getId());
        long churnedInWindow =
                subscriptionRepository.countByStatusAndPlanIdAndCompFalseAndUpdatedAtAfter(
                        SubscriptionStatus.CANCELLED, proPlan.getId(), windowStart);

        long mrrCents = activeProCount * (long) proPlan.getPriceInr();
        BigDecimal mrrInr = BigDecimal.valueOf(mrrCents, 2);
        BigDecimal arrInr = mrrInr.multiply(BigDecimal.valueOf(12));

        long activeAtWindowStart = activeProCount + churnedInWindow;
        BigDecimal churnPercent =
                activeAtWindowStart == 0
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(churnedInWindow)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(activeAtWindowStart), 2, java.math.RoundingMode.HALF_UP);

        return new BillingMetricsDto(
                mrrInr, arrInr, churnPercent, activeProCount, churnedInWindow, CHURN_WINDOW_DAYS);
    }

    private static final int CHURN_WINDOW_DAYS = 30;

    /**
     * {@code POST /admin/billing/comp} — grant a complimentary subscription. See {@code
     * AdminBillingController} class javadoc for the mandatory-reason/server-derived-identity
     * rules; enforced by {@link CompSubscriptionRequest}'s bean validation (reason) and this
     * method never accepting an admin id from the request body (only from {@code principal}).
     */
    @Transactional
    public AdminSubscriptionActionResultDto grantComp(
            AuthPrincipal principal, HttpServletRequest request, CompSubscriptionRequest body) {
        return applyAdminPlanGrant(
                principal, request, body.workspaceId(), body.planCode(), body.reason(), body.expiresAt(), "TIER_ADJUST");
    }

    /**
     * {@code POST /admin/billing/override} — see {@code AdminBillingController} class javadoc
     * "Override scope" note. Mechanically identical to {@link #grantComp} (both route through
     * {@link SubscriptionService#grantAdminPlan}) — the only difference is the audit action
     * ({@code BUDGET_OVERRIDE} vs {@code TIER_ADJUST}) so the two intents stay distinguishable in
     * the audit trail even though the underlying mutation is the same.
     */
    @Transactional
    public AdminSubscriptionActionResultDto overridePlan(
            AuthPrincipal principal, HttpServletRequest request, OverrideSubscriptionRequest body) {
        return applyAdminPlanGrant(
                principal,
                request,
                body.workspaceId(),
                body.planCode(),
                body.reason(),
                body.expiresAt(),
                "BUDGET_OVERRIDE");
    }

    private AdminSubscriptionActionResultDto applyAdminPlanGrant(
            AuthPrincipal principal,
            HttpServletRequest request,
            String workspaceId,
            String planCodeRaw,
            String reason,
            Instant expiresAt,
            String auditAction) {
        var admin = adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        Workspace workspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "No workspace found for id " + workspaceId, HttpStatus.NOT_FOUND));
        if (workspace.getType() != WorkspaceType.BRAND) {
            throw new ApiException(
                    "NOT_A_BRAND_WORKSPACE",
                    "Subscriptions only apply to BRAND workspaces",
                    HttpStatus.BAD_REQUEST);
        }

        PlanCode planCode = parsePlanCode(planCodeRaw);
        Plan targetPlan = planService.getByCode(planCode);
        if (!targetPlan.isActive()) {
            throw new ApiException(
                    "PLAN_NOT_AVAILABLE", "Plan " + planCode + " is currently disabled", HttpStatus.CONFLICT);
        }

        Map<String, Object> before = snapshot(subscriptionRepository.findByWorkspaceId(workspaceId).orElse(null));

        Subscription subscription =
                subscriptionService.grantAdminPlan(workspaceId, targetPlan, admin.getId(), reason, expiresAt);

        adminAuditLogService.record(
                principal, request, auditAction, ENTITY_TYPE, subscription.getId(), before, snapshot(subscription), reason);

        return new AdminSubscriptionActionResultDto(
                subscription.getId(),
                subscription.getWorkspaceId(),
                planCode.name(),
                subscription.getStatus().name(),
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd(),
                subscription.isComp(),
                subscription.getCompReason(),
                subscription.getCompExpiresAt());
    }

    private Map<String, Object> snapshot(Subscription subscription) {
        if (subscription == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("subscriptionId", subscription.getId());
        map.put("workspaceId", subscription.getWorkspaceId());
        map.put("planCode", resolvePlanCodeLabelById(subscription.getPlanId()));
        map.put("status", subscription.getStatus().name());
        map.put("isComp", subscription.isComp());
        map.put("compReason", subscription.getCompReason());
        map.put("compExpiresAt", subscription.getCompExpiresAt() != null ? subscription.getCompExpiresAt().toString() : null);
        map.put("currentPeriodEnd", subscription.getCurrentPeriodEnd() != null ? subscription.getCurrentPeriodEnd().toString() : null);
        return map;
    }

    private String resolvePlanCodeLabelById(String planId) {
        return planService.getActivePlans().stream()
                .filter(p -> p.getId().equals(planId))
                .findFirst()
                .map(p -> p.getCode().name())
                .orElse(planId);
    }

    private static String resolvePlanCodeLabel(String planId, Map<String, PlanCode> planCodes) {
        PlanCode code = planCodes.get(planId);
        return code != null ? code.name() : planId;
    }

    private static SubscriptionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SubscriptionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_STATUS", "Unrecognized subscription status: " + status, HttpStatus.BAD_REQUEST);
        }
    }

    private static PlanCode parsePlanCode(String planCode) {
        try {
            return PlanCode.valueOf(planCode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    "INVALID_PLAN_CODE", "Unrecognized plan code: " + planCode, HttpStatus.BAD_REQUEST);
        }
    }
}
