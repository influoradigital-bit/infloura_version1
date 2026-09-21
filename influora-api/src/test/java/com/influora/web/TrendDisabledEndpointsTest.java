package com.influora.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.TrendFeatureGate;
import com.influora.config.TrendIngestProperties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.CreatorContextService;
import com.influora.service.creatorcopilot.CreatorNudgeService;
import com.influora.service.trendspark.TrendSparkNudgeService;
import com.influora.web.dto.creatorcopilot.CreatorCopilotDtos.SuggestionTodayResponse;
import com.influora.web.dto.trendspark.TrendSparkDtos.NudgeResponse;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * T-TSOFF-0920 — with trend ingest off, every trend-derived endpoint answers a DOCUMENTED,
 * DISTINGUISHABLE {@code 404 TRENDS_DISABLED} instead of its ordinary "nothing to say" success.
 *
 * <p>WHY THIS FILE EXISTS. Before the gate, the two read endpoints answered a switched-off feature
 * with the same bytes they answer a quiet day with: {@code GET /brand/trendspark/nudge} sent 204,
 * and {@code GET /creator/copilot/suggestion/today} sent {@code 200 {status:
 * "no_suggestion_today"}}. Those are indistinguishable from "no trend matched you today", so the
 * SPA rendered "check back tomorrow" for a feature that has no tomorrow. Asserting the DIFFERENCE
 * is the point of this file — an assertion that the disabled call merely returns "nothing" would
 * have passed against the old, broken code too.
 *
 * <p>Plain Mockito unit tests against a REAL {@link TrendFeatureGate} over a REAL {@link
 * TrendIngestProperties}, matching this codebase's controller-test convention (no MockMvc
 * harness). The gate is deliberately not mocked: mocking it would stub out the very predicate
 * under test and the suite would still be green if {@code canProduceTrends()} were wrong.
 */
@ExtendWith(MockitoExtension.class)
class TrendDisabledEndpointsTest {

    private static final String BRAND_USER_ID = "01HWXYZBRAND00000000001";
    private static final String CREATOR_USER_ID = "01HWXYZCREATOR000000001";

    @Mock private BrandContextService brandContextService;
    @Mock private TrendSparkNudgeService trendSparkNudgeService;
    @Mock private CreatorContextService creatorContextService;
    @Mock private CreatorNudgeService creatorNudgeService;

    /** The shipped beta default: ingest off, no keys, no classifier workspace. */
    private static TrendFeatureGate offGate() {
        return new TrendFeatureGate(new TrendIngestProperties());
    }

    /** Ingest on, one source key, a classifier workspace to bill. */
    private static TrendFeatureGate onGate() {
        TrendIngestProperties props = new TrendIngestProperties();
        props.setEnabled(true);
        props.setNewsapiApiKey("test-key-not-a-real-credential");
        props.setClassifierWorkspaceId("ws_test");
        return new TrendFeatureGate(props);
    }

    private static AuthPrincipal brand() {
        return new AuthPrincipal(BRAND_USER_ID, "brand@example.com", UserType.BRAND, null);
    }

    private static AuthPrincipal creator() {
        return new AuthPrincipal(CREATOR_USER_ID, "creator@example.com", UserType.CREATOR, null);
    }

    private static void assertTrendsDisabled(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException ex = (ApiException) thrown;
        assertThat(ex.getCode()).isEqualTo("TRENDS_DISABLED");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -----------------------------------------------------------------------------------------
    @Nested
    @DisplayName("GET/POST /brand/trendspark/* (brand Trend-Spark nudge)")
    class TrendSpark {

        private TrendSparkController controller(TrendFeatureGate gate) {
            return new TrendSparkController(brandContextService, trendSparkNudgeService, gate);
        }

        @Test
        @DisplayName("off: nudge answers 404 TRENDS_DISABLED, NOT the 204 it sends on a quiet day")
        void nudgeOff() {
            assertThatThrownBy(() -> controller(offGate()).getNudge(brand()))
                    .satisfies(TrendDisabledEndpointsTest::assertTrendsDisabled);
        }

        @Test
        @DisplayName("off: the workspace is never resolved and the nudge service is never reached")
        void nudgeOffDoesNoWork() {
            // The gate runs BEFORE requireBrandWorkspace, so a disabled feature touches neither
            // the DB nor TrendSparkAiClient (which is a per-workspace, credit-metered AI call).
            assertThatThrownBy(() -> controller(offGate()).getNudge(brand()))
                    .isInstanceOf(ApiException.class);
            verifyNoInteractions(brandContextService);
            verifyNoInteractions(trendSparkNudgeService);
        }

        @Test
        @DisplayName("off: the click callback is refused too — no telemetry for an impossible event")
        void clickOff() {
            assertThatThrownBy(() -> controller(offGate()).click(brand(), "nudge_1"))
                    .satisfies(TrendDisabledEndpointsTest::assertTrendsDisabled);
            verifyNoInteractions(trendSparkNudgeService);
        }

        @Test
        @DisplayName("off: the purchase callback is refused too")
        void purchaseOff() {
            assertThatThrownBy(() -> controller(offGate()).purchase(brand(), "nudge_1", null))
                    .satisfies(TrendDisabledEndpointsTest::assertTrendsDisabled);
            verifyNoInteractions(trendSparkNudgeService);
        }

        @Test
        @DisplayName("on + nothing to say: the ordinary 204 is UNCHANGED (the gate is not a 404 machine)")
        void nudgeOnQuietDay() {
            Workspace workspace =
                    Workspace.newBrand("ws_1", "Acme Snacks Co", "acme-snacks", "FMCG", "11-50");
            when(brandContextService.requireBrandWorkspace(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(workspace);
            when(trendSparkNudgeService.getNudge("ws_1")).thenReturn(Optional.empty());

            ResponseEntity<ApiResponse<NudgeResponse>> response =
                    controller(onGate()).getNudge(brand());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    // -----------------------------------------------------------------------------------------
    @Nested
    @DisplayName("GET/POST /creator/copilot/* (creator daily idea)")
    class CreatorCopilot {

        private CreatorCopilotController controller(TrendFeatureGate gate) {
            return new CreatorCopilotController(creatorContextService, creatorNudgeService, gate);
        }

        @Test
        @DisplayName("off: today answers 404 TRENDS_DISABLED, NOT 200 no_suggestion_today")
        void todayOff() {
            assertThatThrownBy(() -> controller(offGate()).getToday(creator()))
                    .satisfies(TrendDisabledEndpointsTest::assertTrendsDisabled);
        }

        @Test
        @DisplayName("off: the creator profile is never resolved and the nudge service never runs")
        void todayOffDoesNoWork() {
            assertThatThrownBy(() -> controller(offGate()).getToday(creator()))
                    .isInstanceOf(ApiException.class);
            verifyNoInteractions(creatorContextService);
            verifyNoInteractions(creatorNudgeService);
        }

        @Test
        @DisplayName("off: dismiss is refused")
        void dismissOff() {
            assertThatThrownBy(() -> controller(offGate()).dismiss(creator(), "sug_1"))
                    .satisfies(TrendDisabledEndpointsTest::assertTrendsDisabled);
            verifyNoInteractions(creatorNudgeService);
        }

        @Test
        @DisplayName("off: acted is refused")
        void actedOff() {
            assertThatThrownBy(() -> controller(offGate()).acted(creator(), "sug_1"))
                    .satisfies(TrendDisabledEndpointsTest::assertTrendsDisabled);
            verifyNoInteractions(creatorNudgeService);
        }

        @Test
        @DisplayName(
                "on + nothing to say: API-CONTRACT.md §1.1's 200 no_suggestion_today is UNCHANGED")
        void todayOnQuietDay() {
            CreatorProfile profile =
                    CreatorProfile.newForUser("cp_1", CREATOR_USER_ID, "Priya Creates");
            when(creatorContextService.requireCreatorProfile(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(profile);
            when(creatorNudgeService.getSuggestion("cp_1"))
                    .thenReturn(CreatorNudgeService.SuggestionResult.noSuggestionToday());

            ResponseEntity<ApiResponse<SuggestionTodayResponse>> response =
                    controller(onGate()).getToday(creator());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().data().status()).isEqualTo("no_suggestion_today");
        }
    }
}
