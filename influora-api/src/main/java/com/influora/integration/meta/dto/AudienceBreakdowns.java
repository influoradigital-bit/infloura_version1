package com.influora.integration.meta.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A creator's follower demographics, in the form {@code audience_demographics} stores and every
 * reader expects: follower counts keyed {@code "18-24_female"} / {@code "18-24_male"} /
 * {@code "18-24_unknown"} (age and gender), by city ({@code "Mumbai, Maharashtra"}) and by country
 * ({@code "IN"}). Meta no longer offers a language breakdown, so there is none.
 *
 * <p>Built from {@code follower_demographics} responses by {@link #fromResponses}. Each result is
 * read by the {@code dimension_keys} Meta returns, not by position, so the order Meta lists
 * {@code age}/{@code gender} in cannot swap them.
 */
public record AudienceBreakdowns(
        Map<String, Long> ageGender, Map<String, Long> country, Map<String, Long> city) {

    public boolean isEmpty() {
        return ageGender.isEmpty() && country.isEmpty() && city.isEmpty();
    }

    public static AudienceBreakdowns fromResponses(
            FollowerDemographicsResponse ageGender,
            FollowerDemographicsResponse country,
            FollowerDemographicsResponse city) {
        Map<String, Long> ageGenderCounts = new HashMap<>();
        forEachResult(ageGender, List.of("age", "gender"), (dims, value) -> {
            String age = dims.get("age");
            if (age != null && !age.isBlank()) {
                ageGenderCounts.merge(age.strip() + "_" + genderWord(dims.get("gender")), value, Long::sum);
            }
        });
        return new AudienceBreakdowns(ageGenderCounts, single(country, "country"), single(city, "city"));
    }

    /** Counts for a one-dimension breakdown ({@code city} or {@code country}). */
    private static Map<String, Long> single(FollowerDemographicsResponse response, String key) {
        Map<String, Long> counts = new HashMap<>();
        forEachResult(response, List.of(key), (dims, value) -> {
            String label = dims.get(key);
            if (label != null && !label.isBlank()) {
                counts.merge(label.strip(), value, Long::sum);
            }
        });
        return counts;
    }

    /** Meta's gender codes: F, M, U (unknown). */
    static String genderWord(String code) {
        if (code == null) {
            return "unknown";
        }
        return switch (code.strip().toUpperCase(Locale.ROOT)) {
            case "F" -> "female";
            case "M" -> "male";
            default -> "unknown";
        };
    }

    private static void forEachResult(
            FollowerDemographicsResponse response,
            List<String> requested,
            BiConsumer<Map<String, String>, Long> visitor) {
        if (response == null || response.data() == null) {
            return;
        }
        for (FollowerDemographicsResponse.Metric metric : response.data()) {
            if (metric == null || metric.totalValue() == null || metric.totalValue().breakdowns() == null) {
                continue;
            }
            for (FollowerDemographicsResponse.Breakdown breakdown : metric.totalValue().breakdowns()) {
                if (breakdown == null || breakdown.results() == null) {
                    continue;
                }
                List<String> keys =
                        breakdown.dimensionKeys() != null && !breakdown.dimensionKeys().isEmpty()
                                ? breakdown.dimensionKeys()
                                : requested;
                for (FollowerDemographicsResponse.Result result : breakdown.results()) {
                    if (result == null || result.value() == null || result.value() <= 0
                            || result.dimensionValues() == null) {
                        continue;
                    }
                    Map<String, String> dims = new HashMap<>();
                    for (int i = 0; i < keys.size() && i < result.dimensionValues().size(); i++) {
                        dims.put(keys.get(i), result.dimensionValues().get(i));
                    }
                    visitor.accept(dims, result.value());
                }
            }
        }
    }
}
