package com.influora.security;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.enums.UsageMetric;
import com.influora.service.billing.UsageCounterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Task 22 core: enforces the Free-tier creator-analytics-deep-dive cap ({@code
 * Plan.creatorAnalyticsMonthlyLimit} — Free=1, Pro=null=unlimited) on {@code
 * AnalyticsController}'s 4 brand-facing endpoints ({@code /analytics/creators/{creatorId}/**} —
 * metrics/scores/demographics/media), registered against that path pattern only in {@code
 * PlanGateWebConfig}.
 *
 * <p><b>Scope decision — {@code CreatorAnalyticsController} (`/creator/analytics/me/**`) is
 * deliberately NOT gated here</b>, despite both {@code
 * wiki/processes/subscription-billing-task-breakdown.md} (Task 22) and {@code
 * wiki/tech/SUBSCRIPTION-BILLING-PLAN.md} (§1.1, Task 15/22 row) flagging it as an omission risk:
 * "a Free brand could bypass the analytics cap by hitting this instead." Verified against source
 * before building, per this project's MP-1 culture of trusting call graphs over prose (same
 * discipline that caught the P0 fee-bypass bug, {@code wiki/tech/security.md}):
 *
 * <ul>
 *   <li>{@code CreatorAnalyticsController} delegates every method to {@code
 *       CreatorAnalyticsService}, whose every method opens with {@code
 *       creatorContext.requireCreatorProfile(principal)}.
 *   <li>{@code CreatorContextService.requireCreatorProfile} calls {@code requireCreator}, which
 *       throws {@code WRONG_USER_TYPE} (403) whenever {@code principal.getUserType() !=
 *       UserType.CREATOR} — BEFORE any data is read.
 * </ul>
 *
 * A BRAND principal — the only user type {@link PlanGateFilter} resolves a {@code workspaceId}/
 * {@link Plan} for — cannot reach this controller's data at all. There is no bypass to close.
 *
 * <p>Applying this same workspace-scoped cap to {@code CreatorAnalyticsController} instead would
 * actively regress the product: a CREATOR principal has no {@code workspaceId}, so a
 * workspace-keyed {@link UsageCounterService} lookup would resolve to the Free plan by default
 * and cap every CREATOR at 1 view/month of their OWN analytics dashboard — a limit the plan
 * ({@code SUBSCRIPTION-BILLING-PLAN.md} §1.4/§2, "creator-analytics-deep-dive" = a BRAND vetting a
 * creator) never intended to apply to creators viewing themselves.
 *
 * <p>This is flagged explicitly to Kavya/Arjun/Priya in the Task 22 SHARED_CONTEXT.md handoff as
 * a corrected audit finding, not a silent deviation from the task brief.
 */
public class AnalyticsUsageCapInterceptor implements HandlerInterceptor {

    /**
     * Extracts the {@code creatorId} path segment from any of the 4 gated {@code
     * AnalyticsController} sub-endpoints ({@code /analytics/creators/{creatorId}/metrics|scores|
     * demographics|media}). Matched against the tail of the URI rather than an exact anchor so it
     * is indifferent to a servlet context-path prefix ({@code server.servlet.context-path=/api/v1}
     * in production vs. no prefix in the {@code MockHttpServletRequest}-driven wiring test).
     */
    private static final Pattern CREATOR_ID_PATTERN =
            Pattern.compile(".*/analytics/creators/([^/]+)(?:/.*)?$");

    private final UsageCounterService usageCounterService;

    public AnalyticsUsageCapInterceptor(UsageCounterService usageCounterService) {
        this.usageCounterService = usageCounterService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object planAttr = request.getAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR);
        Object workspaceAttr = request.getAttribute(PlanGateFilter.RESOLVED_WORKSPACE_ID_ATTR);
        if (!(planAttr instanceof Plan plan) || !(workspaceAttr instanceof String workspaceId)) {
            // No plan/workspace resolved for this request (non-brand caller, or PlanGateFilter
            // could not resolve a workspace for a brand mid-onboarding). AnalyticsService's own
            // BrandContextService check downstream will reject a non-brand caller with its own
            // clear error; this interceptor has nothing to gate without a resolved workspace, so
            // it steps aside rather than blocking with an unrelated/confusing error here.
            return true;
        }

        Integer monthlyLimit = plan.getCreatorAnalyticsMonthlyLimit();
        if (monthlyLimit == null) {
            // Pro / unlimited — no cap to enforce, so no dedup bookkeeping is needed either.
            // Tracked for observability only, same as before this fix.
            usageCounterService.incrementUsage(workspaceId, UsageMetric.CREATOR_ANALYTICS_VIEW, 1);
            return true;
        }

        // Per-creator-lookup dedup (Task 22 Flag #1 fix, Rohan CFO ruling in SHARED_CONTEXT.md
        // 2026-07-14): "1 creator-analytics deep-dive/month" (SUBSCRIPTION-BILLING-PLAN.md §1.4/
        // §2) means one distinct creator looked at, not one API call. A brand's 4 sub-endpoint
        // calls (metrics/scores/demographics/media) for the SAME creatorId must only ever consume
        // 1 unit of quota — recordCreatorLookup dedupes by (workspaceId, metric, period,
        // creatorId) and only counts a genuinely NEW creator against the limit, atomically and
        // race-safely (see UsageCounterService#recordCreatorLookup javadoc). Quota is never
        // consumed on a rejected request — recordCreatorLookup checks the limit BEFORE recording.
        String creatorId = extractCreatorId(request);
        boolean allowed =
                usageCounterService.recordCreatorLookup(
                        workspaceId, UsageMetric.CREATOR_ANALYTICS_VIEW, creatorId, monthlyLimit);
        if (!allowed) {
            throw new ApiException(
                    "UPGRADE_REQUIRED",
                    "Free plan allows "
                            + monthlyLimit
                            + " creator-analytics view(s) per month — upgrade to Pro for"
                            + " unlimited",
                    HttpStatus.PAYMENT_REQUIRED);
        }
        return true;
    }

    /**
     * Best-effort {@code creatorId} extraction from the request URI. Defensively falls back to
     * the full request URI (rather than throwing) if the pattern somehow doesn't match — this
     * interceptor is only ever registered against {@code /analytics/creators/**}
     * ({@code PlanGateWebConfig}), so every real request here has a creatorId segment; the
     * fallback exists purely so an unexpected shape degrades to "dedupe by full URL" (equivalent
     * to the old per-call counting for that one anomalous request) instead of an unhandled 500.
     */
    private static String extractCreatorId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        Matcher matcher = CREATOR_ID_PATTERN.matcher(uri);
        return matcher.matches() ? matcher.group(1) : uri;
    }
}
