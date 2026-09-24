package com.influora.integration.meta.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Follower demographics, from Meta's JSON to the keys we store (2026-09-24).
 *
 * <p>The metrics this replaced ({@code audience_gender_age} and friends) were removed by Meta on
 * 2023-12-11, and the job kept calling them, so no creator ever had audience data: a silent,
 * all-green failure. Every assertion here is on VALUES parsed from the documented response shape
 * ({@code data[].total_value.breakdowns[].dimension_keys / results[].dimension_values / value}),
 * with the same plain Jackson mapper the Meta RestClient binds with.
 */
class FollowerDemographicsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private FollowerDemographicsResponse parse(String json) throws Exception {
        return mapper.readValue(json, FollowerDemographicsResponse.class);
    }

    private static final String AGE_GENDER =
            """
            {"data":[{"name":"follower_demographics","period":"lifetime",
              "title":"Follower demographics","description":"The demographic characteristics of followers",
              "total_value":{"breakdowns":[{"dimension_keys":["age","gender"],
                "results":[{"dimension_values":["18-24","F"],"value":410},
                           {"dimension_values":["18-24","M"],"value":190},
                           {"dimension_values":["25-34","U"],"value":12},
                           {"dimension_values":["35-44","F"],"value":0}]}]},
              "id":"17841400000000021/insights/follower_demographics/lifetime"}]}
            """;

    private static final String COUNTRY =
            """
            {"data":[{"name":"follower_demographics","period":"lifetime",
              "total_value":{"breakdowns":[{"dimension_keys":["country"],
                "results":[{"dimension_values":["IN"],"value":580},{"dimension_values":["AE"],"value":20}]}]}}]}
            """;

    private static final String CITY =
            """
            {"data":[{"name":"follower_demographics","period":"lifetime",
              "total_value":{"breakdowns":[{"dimension_keys":["city"],
                "results":[{"dimension_values":["Jaipur, Rajasthan"],"value":230},
                           {"dimension_values":["Mumbai, Maharashtra"],"value":140}]}]}}]}
            """;

    @Test
    @DisplayName("age+gender, country and city bind and become the keys every screen reads")
    void documentedShapeBecomesStoredKeys() throws Exception {
        AudienceBreakdowns b =
                AudienceBreakdowns.fromResponses(parse(AGE_GENDER), parse(COUNTRY), parse(CITY));

        assertEquals(Map.of("18-24_female", 410L, "18-24_male", 190L, "25-34_unknown", 12L), b.ageGender());
        assertEquals(Map.of("IN", 580L, "AE", 20L), b.country());
        assertEquals(Map.of("Jaipur, Rajasthan", 230L, "Mumbai, Maharashtra", 140L), b.city());
    }

    @Test
    @DisplayName("dimensions are read by dimension_keys, so gender-first order cannot swap age and gender")
    void readsByDimensionKeysNotPosition() throws Exception {
        String genderFirst =
                """
                {"data":[{"name":"follower_demographics","total_value":{"breakdowns":[
                  {"dimension_keys":["gender","age"],"results":[{"dimension_values":["F","18-24"],"value":7}]}]}}]}
                """;
        AudienceBreakdowns b = AudienceBreakdowns.fromResponses(parse(genderFirst), null, null);
        assertEquals(Map.of("18-24_female", 7L), b.ageGender());
    }

    @Test
    @DisplayName("an account under 100 followers (Meta sends no breakdowns) is empty, never zero-filled")
    void noBreakdownsIsEmpty() throws Exception {
        String empty = """
                {"data":[{"name":"follower_demographics","period":"lifetime","total_value":{"breakdowns":[]}}]}
                """;
        AudienceBreakdowns b =
                AudienceBreakdowns.fromResponses(parse(empty), parse("{\"data\":[]}"), null);
        assertTrue(b.isEmpty());
    }
}
