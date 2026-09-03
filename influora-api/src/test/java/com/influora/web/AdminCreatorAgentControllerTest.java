package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.admin.CreatorAgentBaselineService;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.MonthlyCapResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.SetMonthlyCapRequest;
import java.math.BigDecimal;
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

    private AdminCreatorAgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminCreatorAgentController(baselineService, preferencesService);
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
}
