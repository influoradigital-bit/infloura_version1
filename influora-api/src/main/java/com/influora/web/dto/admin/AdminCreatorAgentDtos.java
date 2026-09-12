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

    // =============================================================================================
    // T-MEERA-CREATOR-PHASE-B (SPEC.md 14.1.g, B0-35) - GET /admin/creator-agent/rate-calibration
    // =============================================================================================

    /**
     * One tier's row of the rate calibration report.
     *
     * <p><b>What this exists to answer.</b> Every cold-start quote Meera issues is a formula over
     * constants nobody has checked against a real close. This row puts the constant
     * ({@code benchmark_*}), what creators in that tier actually closed at ({@code realised_*}) and
     * what Meera has been telling them ({@code quoted_*}) on one line so a human can see whether
     * the three agree. SPEC.md &sect;14.5.c metric 4 — the gate that decides whether Phase B1 may
     * start — has no other source.
     *
     * <p><b>No workspace id and no creator id appear on this record, and none may ever be added.</b>
     * The realised columns are aggregated from {@code CollaborationRepository.RateBandCandidateRow},
     * a cross-tenant projection whose javadoc carries Kabir's mandatory Phase-2 gate: no row of it
     * may be serialized. {@code distinct_workspaces} is a COUNT of that column, never its values.
     *
     * @param realisedMedian NULL — never {@code 0}, never a partial figure — when {@code realisedN}
     *     is below {@code RateQuoteService.BAND_MIN_DEALS}. A median over four deals is a number
     *     about four identifiable brands, and it is also the kind of number that gets read as
     *     market fact and written into a pricing constant. Absent is the honest answer;
     *     {@code realisedN} is still sent so the reader knows how far below the floor the tier is.
     * @param meeraAnchoredShare the share of the realised sample that carries at least one
     *     {@code MEERA_COUNTER} — the feedback-loop signal: high means Meera is calibrating against
     *     its own past quotes. NULL under the same floor as the median, and for a sharper reason:
     *     at {@code realisedN = 1} this fraction is exactly 0.0 or 1.0 and therefore states an
     *     attribute of one identifiable deal.
     * @param quotedMedian90d deliberately NOT behind the k-anonymity floor — these rows are
     *     Meera's own emissions ({@code RATE_QUOTE_ISSUED}), not third-party deal data, and there
     *     is no other party to protect. Null only when {@code quotedN90d} is 0. Read it next to
     *     {@code quotedN90d}, which is sent for exactly that purpose.
     * @param benchmarkSource {@code "yml override"} or {@code "compiled default"} — B0-36 sets
     *     per-tier overrides from this report, and without this column an admin cannot tell from
     *     the report whether the override took effect.
     */
    public record RateCalibrationTier(
            @JsonProperty("tier") String tier,
            @JsonProperty("benchmark_min") BigDecimal benchmarkMin,
            @JsonProperty("benchmark_max") BigDecimal benchmarkMax,
            @JsonProperty("benchmark_unit") BigDecimal benchmarkUnit,
            @JsonProperty("benchmark_source") String benchmarkSource,
            @JsonProperty("realised_median") BigDecimal realisedMedian,
            @JsonProperty("realised_n") int realisedN,
            @JsonProperty("distinct_workspaces") int distinctWorkspaces,
            @JsonProperty("meera_anchored_share") Double meeraAnchoredShare,
            @JsonProperty("quoted_median_90d") BigDecimal quotedMedian90d,
            @JsonProperty("quoted_n_90d") int quotedN90d) {}

    /**
     * {@code GET /admin/creator-agent/rate-calibration}. Bare DTO, no {@code ApiResponse} envelope
     * — the deliberate deviation this whole controller makes, stated in its own javadoc.
     *
     * <p>{@code sampleFloor} and {@code windowDays} are on the wire rather than hardcoded in the
     * admin client so the page can say "fewer than 5 deals" without owning a second copy of the
     * number; both come from {@code RateQuoteService}'s constants.
     */
    public record RateCalibrationResponse(
            @JsonProperty("tiers") java.util.List<RateCalibrationTier> tiers,
            @JsonProperty("sample_floor") int sampleFloor,
            @JsonProperty("window_days") int windowDays,
            @JsonProperty("computed_at") Instant computedAt) {}
}
