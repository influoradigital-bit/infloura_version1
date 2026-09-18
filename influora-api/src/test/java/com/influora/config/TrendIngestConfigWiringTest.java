package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

// T-GOLIVE-0918-R2 [vikram · 2026-09-18] — repair round on COPILOT-INGEST MEDIUM #1 (Kabir
// round-1-on-31f2351 finding): "the round-1 HIGH fix is config-only and nothing tests it. Deleting
// or renaming TREND_INGEST_CLASSIFIER_WORKSPACE_ID, or deleting the region binding, leaves all 40
// tests green." None of TrendPullJobTest's existing tests load application.yml at all — they
// construct TrendIngestProperties in Java and set fields directly, so a yml-only regression (a
// deleted line, a renamed ${ENV} placeholder, a block moved under the wrong prefix) is invisible
// to every one of them. This class binds the REAL application.yml through Spring's Binder, the
// same pattern already used for influora.shopify (ShopifyPropertiesBindingTest) and
// influora.creator-copilot (CreatorCopilotConfigWiringTest) after both were found to have the same
// hole. Source: wiki/decisions/2026-09-18-trend-headline-screening.md step 2 (classifier-workspace-
// id must be operator-settable, not hardcoded) and TrendIngestProperties#classifierWorkspaceId
// javadoc (wiring escalated, value not decided by this lane).
class TrendIngestConfigWiringTest {

    private static final String YML = "application.yml";

    private StandardEnvironment environmentWith(Map<String, Object> envVars) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Highest precedence, standing in for real OS environment variables.
        env.getPropertySources().addFirst(new MapPropertySource("test-env", envVars));
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load(YML, new ClassPathResource(YML));
        for (PropertySource<?> source : yaml) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }

    private TrendIngestProperties bind(Map<String, Object> envVars) throws IOException {
        return Binder.get(environmentWith(envVars))
                .bind("influora.trend-ingest", TrendIngestProperties.class)
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "application.yml declares no influora.trend-ingest block — the trend"
                                                + " pull job cannot be configured in any environment"));
    }

    @Test
    @DisplayName("application.yml: trend-ingest binds, and every knob keeps its documented default")
    void defaultsMatchJavaDefaults() throws IOException {
        TrendIngestProperties props = bind(Map.of());

        assertFalse(props.isEnabled(), "trend-ingest must stay off by default");
        assertEquals(100, props.getMaxRowsPerRun());
        assertEquals("IN", props.getRegion());
        assertEquals("", props.getClassifierWorkspaceId());
        assertFalse(
                props.hasClassifierWorkspaceId(),
                "with no env var set the job must fail closed on every headline (F-0918 HIGH)");
    }

    @Test
    @DisplayName("TREND_INGEST_ENABLED=true actually reaches TrendIngestProperties.enabled")
    void enabledEnvVarBinds() throws IOException {
        assertTrue(bind(Map.of("TREND_INGEST_ENABLED", "true")).isEnabled());
    }

    @Test
    @DisplayName(
            "TREND_INGEST_CLASSIFIER_WORKSPACE_ID reaches getClassifierWorkspaceId() —"
                    + " catches the exact hole Kabir's mutations M4/M5 exploited")
    void classifierWorkspaceIdEnvVarBinds() throws IOException {
        TrendIngestProperties props =
                bind(Map.of("TREND_INGEST_CLASSIFIER_WORKSPACE_ID", "01WSID"));

        assertEquals("01WSID", props.getClassifierWorkspaceId());
        assertTrue(
                props.hasClassifierWorkspaceId(),
                "TREND_INGEST_CLASSIFIER_WORKSPACE_ID did not bind — go-live wiring is broken and"
                        + " the job would fail closed forever even with a real value set in prod");
    }

    @Test
    @DisplayName(
            "TREND_INGEST_REGION reaches getRegion() — catches the exact hole Kabir's"
                    + " mutation M6 exploited")
    void regionEnvVarBinds() throws IOException {
        assertEquals("US", bind(Map.of("TREND_INGEST_REGION", "US")).getRegion());
    }

    @Test
    @DisplayName("TREND_INGEST_MAX_ROWS_PER_RUN reaches getMaxRowsPerRun()")
    void maxRowsPerRunEnvVarBinds() throws IOException {
        assertEquals(7, bind(Map.of("TREND_INGEST_MAX_ROWS_PER_RUN", "7")).getMaxRowsPerRun());
    }

    @Test
    @DisplayName("the three source-key env vars reach their TrendIngestProperties fields")
    void sourceKeyEnvVarsBind() throws IOException {
        TrendIngestProperties props =
                bind(
                        Map.of(
                                "NEWSAPI_KEY", "nk",
                                "TMDB_API_KEY", "tk",
                                "YOUTUBE_API_KEY", "yk"));

        assertEquals("nk", props.getNewsapiApiKey());
        assertEquals("tk", props.getTmdbApiKey());
        assertEquals("yk", props.getYoutubeApiKey());
        assertTrue(props.isFullyConfigured());
    }

    @Test
    @DisplayName("all eight influora.trend-ingest env vars bind together, matching the go-live checklist")
    void allEnvVarsBindTogether() throws IOException {
        TrendIngestProperties props =
                bind(
                        Map.of(
                                "TREND_INGEST_ENABLED", "true",
                                "NEWSAPI_KEY", "nk",
                                "TMDB_API_KEY", "tk",
                                "YOUTUBE_API_KEY", "yk",
                                "TREND_INGEST_PULL_CRON", "0 0 6 * * *",
                                "TREND_INGEST_MAX_ROWS_PER_RUN", "7",
                                "TREND_INGEST_REGION", "US",
                                "TREND_INGEST_CLASSIFIER_WORKSPACE_ID", "01WSID"));

        assertTrue(props.isEnabled());
        assertEquals("nk", props.getNewsapiApiKey());
        assertEquals("tk", props.getTmdbApiKey());
        assertEquals("yk", props.getYoutubeApiKey());
        assertEquals("0 0 6 * * *", props.getPullCron());
        assertEquals(7, props.getMaxRowsPerRun());
        assertEquals("US", props.getRegion());
        assertEquals("01WSID", props.getClassifierWorkspaceId());
        assertTrue(props.hasClassifierWorkspaceId());
    }
}
