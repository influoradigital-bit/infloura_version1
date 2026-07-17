package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /{ig-user-id}/insights?metric=audience_city,audience_country,audience_gender_age,
 * audience_locale&period=lifetime} response. Only available for accounts with 100+ followers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AudienceDemographicsResponse(List<DemographicBreakdown> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DemographicBreakdown(String name, String period, List<DemographicValue> values) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DemographicValue(Map<String, Long> value, String endTime) {}
}
