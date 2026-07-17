package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * {@code GET /{id}/insights?metric=...} response — shared shape for media insights and
 * account-level (day/lifetime) insights. Values are per-metric time series; for media-level
 * insights each metric typically has a single "lifetime" value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstagramInsightsResponse(List<InsightMetric> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InsightMetric(String name, String period, String title, String description, List<InsightValue> values) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InsightValue(Object value, String endTime) {}
}
