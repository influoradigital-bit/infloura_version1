package com.influora.security;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces {@link RequiresPlan} on whichever handler method carries it. Reads the {@link Plan}
 * {@link PlanGateFilter} resolved earlier in the filter chain — never re-resolves it itself, so a
 * missing attribute (non-brand caller, or a workspace {@link PlanGateFilter} could not resolve)
 * fails closed here rather than silently letting a gated feature through.
 *
 * <p>As of BR-14, {@code POST /campaign-templates} ({@code
 * CampaignTemplateController#saveAsTemplate}) carries {@code @RequiresPlan(CAMPAIGN_TEMPLATES)}
 * and is gated live by this interceptor — see {@code CampaignTemplateControllerTest} for the real
 * 402 case exercised against that endpoint. {@code EXPORT} still has no annotated endpoint; for
 * that feature this class remains the ready mechanism, and an unannotated route is a no-op here
 * (see {@code preHandle}: no annotation short-circuits on the very first check).
 */
public class PlanGateInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresPlan requiresPlan = handlerMethod.getMethodAnnotation(RequiresPlan.class);
        if (requiresPlan == null) {
            return true;
        }

        Object attr = request.getAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR);
        if (!(attr instanceof Plan plan)) {
            throw new ApiException(
                    "PLAN_NOT_RESOLVED",
                    "Could not resolve a plan for this workspace to check feature access",
                    HttpStatus.FORBIDDEN);
        }

        boolean allowed =
                switch (requiresPlan.feature()) {
                    case EXPORT -> plan.isExportEnabled();
                    case CAMPAIGN_TEMPLATES -> plan.isCampaignTemplatesEnabled();
                };

        if (!allowed) {
            throw new ApiException(
                    "UPGRADE_REQUIRED",
                    "This feature (" + requiresPlan.feature() + ") requires a Pro plan",
                    HttpStatus.PAYMENT_REQUIRED);
        }
        return true;
    }
}
