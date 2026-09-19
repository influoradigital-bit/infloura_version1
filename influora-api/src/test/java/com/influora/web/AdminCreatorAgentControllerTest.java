package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.admin.CreatorAgentBaselineService;
import com.influora.service.admin.CreatorAgentRateCalibrationService;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.MonthlyCapResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.RateCalibrationResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.RateCalibrationTier;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.SetMonthlyCapRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Gate fix round 1 (Priya Q7, T-MEERA-CREATOR-PHASE-A) — {@code PUT
 * /admin/creator-agent/creators/{creatorId}/monthly-cap}. Admin auth itself is enforced
 * structurally by {@code SecurityConfig}'s {@code hasRole("ADMIN")} matcher on {@code /admin/**}
 * (see {@code SecurityConfigMatcherTest}); this is a plain unit test of the controller's own
 * pass-through to {@link CreatorAgentPreferencesService}, same convention as every other
 * controller test in this codebase (no MockMvc/spring-security-test harness).
 */
@ExtendWith(MockitoExtension.class)
class AdminCreatorAgentControllerTest {

    private static final String CREATOR_PROFILE_ID = "profile-1";

    @Mock private CreatorAgentBaselineService baselineService;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private CreatorAgentRateCalibrationService rateCalibrationService;

    private AdminCreatorAgentController controller;

    @BeforeEach
    void setUp() {
        controller =
                new AdminCreatorAgentController(
                        baselineService, preferencesService, rateCalibrationService);
    }

    @Test
    @DisplayName("setMonthlyCap passes the path creatorId and body value straight through, wraps the returned value")
    void setMonthlyCapPassesThroughAndWrapsResult() {
        when(preferencesService.adminSetMonthlyCapOverride(CREATOR_PROFILE_ID, new BigDecimal("5.00")))
                .thenReturn(new BigDecimal("5.00"));

        MonthlyCapResponse response =
                controller.setMonthlyCap(
                        null, CREATOR_PROFILE_ID, new SetMonthlyCapRequest(new BigDecimal("5.00")));

        assertEquals(new BigDecimal("5.00"), response.aiMonthlyCapUsd());
        verify(preferencesService).adminSetMonthlyCapOverride(CREATOR_PROFILE_ID, new BigDecimal("5.00"));
    }

    @Test
    @DisplayName("setMonthlyCap with a null ai_monthly_cap_usd clears the override")
    void setMonthlyCapNullClearsOverride() {
        when(preferencesService.adminSetMonthlyCapOverride(CREATOR_PROFILE_ID, null)).thenReturn(null);

        MonthlyCapResponse response =
                controller.setMonthlyCap(null, CREATOR_PROFILE_ID, new SetMonthlyCapRequest(null));

        assertEquals(null, response.aiMonthlyCapUsd());
        verify(preferencesService).adminSetMonthlyCapOverride(CREATOR_PROFILE_ID, null);
    }

    // =============================================================================================
    // T-MEERA-CREATOR-PHASE-B (SPEC.md 14.1.g, B0-35) - the rate calibration report
    // =============================================================================================

    @Test
    @DisplayName("getRateCalibration returns the service's BARE DTO - no ApiResponse envelope, matching every other route on this controller")
    void rateCalibrationReturnsABareDto() {
        RateCalibrationResponse expected = calibration();
        when(rateCalibrationService.getRateCalibration()).thenReturn(expected);

        Object returned = controller.getRateCalibration(null);

        // Not merely "equals": the controller must hand back the service's own object. Anything
        // that wrapped it - an ApiResponse, a ResponseEntity - would change the shape the admin
        // client parses, silently, because both compile.
        assertSame(expected, returned);
        assertTrue(returned instanceof RateCalibrationResponse);
        verify(rateCalibrationService).getRateCalibration();
    }

    @Test
    @DisplayName("The serialized payload carries no workspace id and no creator id, and no key that could hold one")
    void rateCalibrationPayloadCarriesNoIdentifiers() throws Exception {
        when(rateCalibrationService.getRateCalibration()).thenReturn(calibration());

        String json =
                wireMapper()
                        .writeValueAsString(controller.getRateCalibration(null))
                        .toLowerCase(java.util.Locale.ROOT);

        // The realised figures are aggregated from a cross-tenant projection under Kabir's
        // k-anonymity gate. Nothing that identifies a party may survive to the wire, and this is
        // the assertion that stops a later "just add the top workspace, it is useful" - the kind
        // of change that compiles, passes tsc, and renders fine.
        for (String forbidden :
                List.of("workspace_id", "workspaceid", "creator_id", "creatorid",
                        "collaboration_id", "collaborationid", "actor_id", "actorid")) {
            assertFalse(json.contains(forbidden), "calibration payload leaked a key: " + forbidden);
        }
        assertTrue(json.contains("\"realised_median\""));
        assertTrue(json.contains("\"distinct_workspaces\""), "the COUNT is fine - the values are not");
    }

    @Test
    @DisplayName("A tier under the sample floor serializes realised_median as an explicit null, never 0")
    void rateCalibrationSerializesTheNullMedianAsNull() throws Exception {
        when(rateCalibrationService.getRateCalibration()).thenReturn(calibration());

        String json = wireMapper().writeValueAsString(controller.getRateCalibration(null));

        assertTrue(json.contains("\"realised_median\":null"), json);
        assertFalse(json.contains("\"realised_median\":0"), json);
        assertTrue(json.contains("\"realised_n\":4"), "the sample size still travels: " + json);
    }

    /**
     * The mapper Spring Boot actually serves this controller with.
     *
     * <p>A bare {@code new ObjectMapper()} has no JSR-310 module, so it throws
     * {@code InvalidDefinitionException} on the response's {@code Instant computed_at} - which is
     * not a finding about the DTO (the running app registers that module through
     * {@code spring-boot-starter-json}), only about the harness. Both assertions below died on
     * that exception before reaching the identifier and null-median checks they exist for, which
     * is the worst kind of failure: a security-shaped test that cannot report on the thing it
     * guards. {@link Jackson2ObjectMapperBuilder} is what Spring Boot's own auto-configuration
     * uses, so the JSON under assertion here is the real wire shape, ISO-8601 timestamp included.
     */
    private static ObjectMapper wireMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }

    /** One tier below the floor - the shape the report has on day one. */
    private static RateCalibrationResponse calibration() {
        return new RateCalibrationResponse(
                List.of(
                        new RateCalibrationTier(
                                "NANO",
                                new BigDecimal("1000"),
                                new BigDecimal("5000"),
                                new BigDecimal("3000"),
                                "compiled default",
                                null,
                                4,
                                4,
                                null,
                                null,
                                0)),
                5,
                90,
                Instant.parse("2026-09-10T00:00:00Z"));
    }
}
