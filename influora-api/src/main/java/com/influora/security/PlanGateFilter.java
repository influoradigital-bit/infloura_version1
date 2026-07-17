package com.influora.security;

import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.service.BrandContextService;
import com.influora.service.billing.SubscriptionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Task 22 (Phase 3b, {@code wiki/processes/subscription-billing-task-breakdown.md}) — resolves
 * the caller's active {@link Plan} once per request and publishes it as a request attribute for
 * downstream {@code HandlerInterceptor}s ({@link PlanGateInterceptor}, {@link
 * AnalyticsUsageCapInterceptor}) to read, rather than each gate re-resolving the
 * workspace/subscription independently.
 *
 * <p><b>Ordering (load-bearing):</b> registered in {@code SecurityConfig} via {@code
 * addFilterAfter(planGateFilter, JwtAuthenticationFilter.class)} — MUST run after {@link
 * JwtAuthenticationFilter} so {@link AuthPrincipal} is already on the {@code
 * SecurityContextHolder} by the time this filter runs.
 *
 * <p>Only resolves a Plan for an authenticated BRAND principal — the only user type with a
 * workspace/subscription in this system. CREATOR/ADMIN requests and unauthenticated requests pass
 * through untouched, no attribute set. Resolution failure (e.g. a brand mid-onboarding with no
 * workspace yet, or a transient lookup error) is swallowed here rather than thrown: this filter
 * runs before Spring MVC dispatch, so an exception here would bypass {@code
 * GlobalExceptionHandler} and surface a raw container error page instead of a clean JSON error.
 * Downstream gates own their own "no plan resolved" handling and fail closed on a gated route.
 */
@Component
public class PlanGateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlanGateFilter.class);

    /** Request attribute key the resolved {@link Plan} is published under. */
    public static final String RESOLVED_PLAN_ATTR = "com.influora.security.RESOLVED_PLAN";

    /** Request attribute key the resolved brand workspace id is published under. */
    public static final String RESOLVED_WORKSPACE_ID_ATTR =
            "com.influora.security.RESOLVED_WORKSPACE_ID";

    private final BrandContextService brandContextService;
    private final SubscriptionService subscriptionService;

    public PlanGateFilter(
            BrandContextService brandContextService, SubscriptionService subscriptionService) {
        this.brandContextService = brandContextService;
        this.subscriptionService = subscriptionService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.getPrincipal() instanceof AuthPrincipal principal
                && principal.getUserType() == UserType.BRAND) {
            try {
                Workspace workspace = brandContextService.requireBrandWorkspace(principal);
                Plan plan = subscriptionService.getActivePlanForWorkspace(workspace.getId());
                request.setAttribute(RESOLVED_PLAN_ATTR, plan);
                request.setAttribute(RESOLVED_WORKSPACE_ID_ATTR, workspace.getId());
            } catch (RuntimeException e) {
                // No resolvable workspace yet, or a transient lookup failure — leave the
                // attribute unset. A downstream gate that requires a plan treats "unset" as
                // fail-CLOSED (see PlanGateInterceptor / AnalyticsUsageCapInterceptor) — this is
                // an access-control gate, not a money-amount optimization, so it must never
                // fail-open the way BrandCampaignFeeService's fee-lookup does (contrast MP-2,
                // wiki/tech/security.md, which is specifically scoped to pricing/entitlement
                // *amounts*, not access gates).
                log.debug(
                        "PlanGateFilter: could not resolve a plan for this request ({}) — {}",
                        request.getRequestURI(),
                        e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
