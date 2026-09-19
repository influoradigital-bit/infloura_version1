package com.influora.service.meera.tool.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.risk.DealRiskService;
import com.influora.web.dto.meera.CreatorToolDtos.CheckDealRisksResult;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code check_deal_risks}.
 *
 * <p>The rules are {@code DealRiskServiceTest}'s subject. What is asserted here is the id space —
 * the executor must pass the profile id it resolved from the verified JWT and never an id off the
 * body — and the "exactly one target" contract.
 */
@ExtendWith(MockitoExtension.class)
class CheckDealRisksExecutorTest {

    private static final String USER_ID = "01HCREATORUSER1234567A";
    private static final String PROFILE_ID = "01HCREATORPROFILE12345";
    private static final String DEAL_ID = "01HDEAL00000000000000";
    private static final String BRIEF_ID = "01HBRIEF0000000000000";

    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private DealRiskService dealRiskService;

    private CheckDealRisksExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CheckDealRisksExecutor(preferencesService, dealRiskService);
        lenient()
                .when(preferencesService.requireCreatorProfile(USER_ID))
                .thenReturn(CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah"));
    }

    @Test
    @DisplayName("a deal id evaluates the DEAL, keyed on the profile resolved from the verified JWT")
    void dealIdEvaluatesTheDeal() {
        when(dealRiskService.evaluateDeal(PROFILE_ID, DEAL_ID))
                .thenReturn(List.of(flag("BELOW_FLOOR", "CRITICAL"), flag("USAGE_LONG", "INFO")));

        CheckDealRisksResult result = executor.execute(USER_ID, Map.of("deal_id", DEAL_ID));

        assertThat(result.target()).isEqualTo(DealRiskService.TARGET_DEAL);
        assertThat(result.targetId()).isEqualTo(DEAL_ID);
        assertThat(result.flags()).extracting(RiskFlag::code).containsExactly("BELOW_FLOOR", "USAGE_LONG");
        // The service sorts most-severe-first; the executor reports the head and re-sorts nothing.
        assertThat(result.highestSeverity()).isEqualTo("CRITICAL");
        verify(dealRiskService).evaluateDeal(PROFILE_ID, DEAL_ID);
        verify(dealRiskService, never()).evaluateBrief(PROFILE_ID, DEAL_ID);
    }

    @Test
    @DisplayName("a brief id evaluates the BRIEF")
    void briefIdEvaluatesTheBrief() {
        when(dealRiskService.evaluateBrief(PROFILE_ID, BRIEF_ID)).thenReturn(List.of());

        CheckDealRisksResult result = executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID));

        assertThat(result.target()).isEqualTo(DealRiskService.TARGET_BRIEF);
        assertThat(result.targetId()).isEqualTo(BRIEF_ID);
        assertThat(result.flags()).isEmpty();
        // NON_NULL on the record, so a clean deal reaches the model with no highest_severity key
        // at all rather than a string it might read as a severity of its own.
        assertThat(result.highestSeverity()).isNull();
    }

    @Test
    @DisplayName("both ids is AMBIGUOUS_RISK_TARGET -- answering about one while narrating the other is worse")
    void bothIdsIsRefused() {
        assertThatThrownBy(
                        () ->
                                executor.execute(
                                        USER_ID, Map.of("deal_id", DEAL_ID, "brief_id", BRIEF_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "AMBIGUOUS_RISK_TARGET")
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verifyNoInteractions(dealRiskService);
    }

    @Test
    @DisplayName("neither id is RISK_TARGET_REQUIRED, and a blank one counts as absent")
    void neitherIdIsRefused() {
        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "RISK_TARGET_REQUIRED");
        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("deal_id", "   ")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "RISK_TARGET_REQUIRED");

        verifyNoInteractions(dealRiskService);
    }

    @Test
    @DisplayName("a creator with no profile row is CREATOR_PROFILE_NOT_FOUND before any rule runs")
    void missingProfileIs404() {
        when(preferencesService.requireCreatorProfile(USER_ID))
                .thenThrow(
                        new ApiException(
                                "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("deal_id", DEAL_ID)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CREATOR_PROFILE_NOT_FOUND");

        verifyNoInteractions(dealRiskService);
    }

    private static RiskFlag flag(String code, String severity) {
        return new RiskFlag(code, severity, "t", "d", null, "a", Map.of(), true);
    }
}
