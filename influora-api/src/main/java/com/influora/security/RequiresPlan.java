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
 * <p>As of BR-14, {@code CampaignTemplateController#saveAsTemplate} uses this with {@code
 * feature = PlanFeature.CAMPAIGN_TEMPLATES}. {@code EXPORT} still has no annotated endpoint; this
 * annotation + {@link PlanGateInterceptor} remain the ready mechanism for whichever future export
 * endpoint adds it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPlan {
    PlanFeature feature();
}
