package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code GET /{ig-user-id}/insights?metric=follower_demographics&period=lifetime
 * &metric_type=total_value&breakdown=...} (Instagram User Insights reference, 2026-09).
 *
 * <pre>
 * {"data":[{"name":"follower_demographics","period":"lifetime",
 *   "total_value":{"breakdowns":[{"dimension_keys":["age","gender"],
 *     "results":[{"dimension_values":["18-24","F"],"value":120}, ...]}]}}]}
 * </pre>
 *
 * Replaces the {@code audience_gender_age}/{@code audience_country}/{@code audience_city}/
 * {@code audience_locale} metrics, removed for every Graph API version on 2023-12-11 (v18.0
 * changelog). Meta returns nothing for an account under 100 followers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FollowerDemographicsResponse(List<Metric> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metric(String name, String period, @JsonProperty("total_value") TotalValue totalValue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TotalValue(List<Breakdown> breakdowns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Breakdown(
            @JsonProperty("dimension_keys") List<String> dimensionKeys, List<Result> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(@JsonProperty("dimension_values") List<String> dimensionValues, Long value) {}
}
