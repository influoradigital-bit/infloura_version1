package com.influora.web.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.1, A1) — {@code GET /admin/creator-agent/baselines}. Wire
 * keys are snake_case, matching SPEC.md's literal response example (same deliberate exception as
 * {@code CreatorAgentDtos}).
 */
public final class AdminCreatorAgentDtos {

    private AdminCreatorAgentDtos() {}

    public record BriefsPerCreatorPerMonth(
            @JsonProperty("median") double median,
            @JsonProperty("p75") double p75,
            @JsonProperty("p90") double p90) {}

    public record SampleLabelCompliance(
            @JsonProperty("sample_size") int sampleSize,
            @JsonProperty("labelled_count") int labelledCount,
            @JsonProperty("compliance_rate") double complianceRate) {}

    public record BaselinesResponse(
            @JsonProperty("creators_by_tier") Map<String, Long> creatorsByTier,
            @JsonProperty("briefs_per_creator_per_month") BriefsPerCreatorPerMonth briefsPerCreatorPerMonth,
            @JsonProperty("meta_connect_rate") double metaConnectRate,
            @JsonProperty("median_creator_reply_hours") Double medianCreatorReplyHours,
            @JsonProperty("sample_label_compliance") SampleLabelCompliance sampleLabelCompliance,
            @JsonProperty("computed_at") Instant computedAt) {}

    /**
     * Gate fix round 1 (Priya Q7) — {@code PUT /admin/creator-agent/creators/{creatorId}/monthly-cap}.
     * {@code null}/absent clears the override back to the process-wide default
     * ({@code AI_CREATOR_MONTHLY_CAP_USD}).
     */
    public record SetMonthlyCapRequest(
            @JsonProperty("ai_monthly_cap_usd") @DecimalMin(value = "0", message = "ai_monthly_cap_usd must be >= 0")
                    BigDecimal aiMonthlyCapUsd) {}

    public record MonthlyCapResponse(@JsonProperty("ai_monthly_cap_usd") BigDecimal aiMonthlyCapUsd) {}
}
