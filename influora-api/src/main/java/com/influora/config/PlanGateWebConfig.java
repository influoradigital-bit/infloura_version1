package com.influora.config;

import com.influora.security.AnalyticsUsageCapInterceptor;
import com.influora.security.PlanGateInterceptor;
import com.influora.service.billing.UsageCounterService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers Task 22's plan-gate {@code HandlerInterceptor}s (Phase 3b subscription billing).
 *
 * <p>{@link PlanGateInterceptor} (the generic {@code @RequiresPlan} mechanism) is registered
 * globally — it no-ops instantly on any handler method without the annotation, so there's no cost
 * to applying it broadly. {@link AnalyticsUsageCapInterceptor} (the analytics-view cap) is scoped
 * to {@code /analytics/creators/**} only — {@code AnalyticsController}'s 4 brand-facing endpoints.
 * {@code CreatorAnalyticsController} ({@code /creator/analytics/me/**}) and {@code
 * CampaignController./campaigns/{id}/analytics} are deliberately excluded — see {@link
 * AnalyticsUsageCapInterceptor}'s class javadoc for why gating either of those would be wrong, not
 * merely out of scope.
 *
 * <p>Interceptors are plain (non-{@code @Component}) classes constructed here with explicit
 * collaborators, mirroring how {@code SecurityConfig} wires the security filter chain explicitly
 * rather than relying on component-scan ordering.
 */
@Configuration
public class PlanGateWebConfig implements WebMvcConfigurer {

    private final UsageCounterService usageCounterService;

    public PlanGateWebConfig(UsageCounterService usageCounterService) {
        this.usageCounterService = usageCounterService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PlanGateInterceptor());
        registry
                .addInterceptor(new AnalyticsUsageCapInterceptor(usageCounterService))
                .addPathPatterns("/analytics/creators/**");
    }
}
