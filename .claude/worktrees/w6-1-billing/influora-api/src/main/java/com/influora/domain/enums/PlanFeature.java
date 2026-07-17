package com.influora.domain.enums;

/**
 * Boolean plan-gated features checked by {@code @RequiresPlan} (Task 22, Phase 3b
 * subscription-billing). Maps 1:1 to a boolean column on {@link com.influora.domain.entity.Plan}.
 */
public enum PlanFeature {
    EXPORT,
    CAMPAIGN_TEMPLATES
}
