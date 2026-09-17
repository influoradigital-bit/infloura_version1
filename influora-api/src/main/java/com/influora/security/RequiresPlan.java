package com.influora.security;

import com.influora.domain.enums.PlanFeature;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level gate: annotate a {@code @RestController} handler method to require the caller's
 * plan to have {@code feature} enabled ({@code Plan.exportEnabled}/{@code
 * Plan.campaignTemplatesEnabled}), else {@link PlanGateInterceptor} throws a {@code 402
 * UPGRADE_REQUIRED} before the method body runs.
 *
 * <p>Requires {@link PlanGateFilter} to have already resolved a {@link
 * com.influora.domain.entity.Plan} for the request (it runs after {@code JwtAuthenticationFilter}
 * in {@code SecurityConfig}) — this annotation only makes sense on brand-authenticated routes.
 *
 * <p><b>Live call sites — both features are enforced on real endpoints.</b> {@code
 * CampaignTemplateController#saveAsTemplate} uses {@code feature = PlanFeature.CAMPAIGN_TEMPLATES}
 * (BR-14), and {@code ReportExportController#export} ({@code GET /campaigns/{campaignId}/export})
 * uses {@code feature = PlanFeature.EXPORT}. Removing either annotation, or either branch of the
 * switch in {@link PlanGateInterceptor}, un-gates a paid feature for Free workspaces.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPlan {
    PlanFeature feature();
}
