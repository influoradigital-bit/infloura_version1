package com.influora.integration.meta.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Account insights bind from {@code total_value.value} (2026-09-24). Bound to the per-media
 * {@code values[]} shape instead, every number would come back null with no error, so every
 * assertion here is on the parsed VALUES, with the plain Jackson mapper the Meta RestClient uses.
 */
class AccountInsightsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String DOCUMENTED =
            """
            {"data":[
              {"name":"reach","period":"day","title":"Accounts reached","total_value":{"value":12400},"id":"1/insights/reach/day"},
              {"name":"views","period":"day","total_value":{"value":48210}},
              {"name":"total_interactions","period":"day","total_value":{"value":1930}},
              {"name":"accounts_engaged","period":"day","total_value":{"value":822}},
              {"name":"profile_links_taps","period":"day","total_value":{"value":64,
                 "breakdowns":[{"dimension_keys":["contact_button_type"],"results":[]}]}}]}
            """;

    @Test
    @DisplayName("each of the five metrics is read from total_value.value")
    void documentedShapeBinds() throws Exception {
        AccountInsightsResponse r = mapper.readValue(DOCUMENTED, AccountInsightsResponse.class);
        assertEquals(12400L, r.valueOf("reach"));
        assertEquals(48210L, r.valueOf("views"));
        assertEquals(1930L, r.valueOf("total_interactions"));
        assertEquals(822L, r.valueOf("accounts_engaged"));
        assertEquals(64L, r.valueOf("profile_links_taps"));
    }

    @Test
    @DisplayName("a metric Meta did not send is null, never 0")
    void missingMetricIsNull() throws Exception {
        AccountInsightsResponse r =
                mapper.readValue("{\"data\":[{\"name\":\"reach\",\"total_value\":{\"value\":5}}]}", AccountInsightsResponse.class);
        assertEquals(5L, r.valueOf("reach"));
        assertNull(r.valueOf("views"));
        assertNull(new AccountInsightsResponse(null).valueOf("reach"));
    }
}
