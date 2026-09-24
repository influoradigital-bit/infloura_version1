package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code GET /{ig-user-id}/insights?metric=reach,views,...&period=day&metric_type=total_value
 * &since=..&until=..} (Instagram User Insights reference, 2026-09).
 *
 * <pre>
 * {"data":[{"name":"reach","period":"day","title":"Accounts reached",
 *           "total_value":{"value":12400},"id":"..."}, ...]}
 * </pre>
 *
 * With {@code metric_type=total_value} each number sits in {@code total_value.value}, NOT in the
 * {@code values[]} list {@link InstagramInsightsResponse} reads for per-media insights: bound to
 * that type, every account number would silently come back empty.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInsightsResponse(List<Metric> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metric(String name, String period, @JsonProperty("total_value") TotalValue totalValue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TotalValue(Long value) {}

    /** The total for one metric, or null when Meta did not return it (never 0 in its place). */
    public Long valueOf(String metricName) {
        if (data == null) {
            return null;
        }
        for (Metric metric : data) {
            if (metric != null && metricName.equals(metric.name()) && metric.totalValue() != null) {
                return metric.totalValue().value();
            }
        }
        return null;
    }
}
