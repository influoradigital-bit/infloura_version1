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

/**
 * Guards the Creator AI Co-pilot's config wiring, which was missing entirely: {@link
 * CreatorCopilotProperties} and both scheduled jobs shipped, but no {@code influora.creator-copilot}
 * block existed in any {@code application*.yml}. {@code enabled} therefore stayed at its Java
 * default of {@code false} in every environment with no way for an operator to turn it on, so
 * {@code CreatorCaptionSyncJob}/{@code CreatorThemeTaggingJob} never ran, {@code
 * CreatorProfile.theme_tags} (whose only writer is the tagging job) stayed NULL, and {@code
 * CreatorNudgeService} returned {@code pending_tagging} forever — surfacing to a creator who HAD
 * connected Instagram as a permanent "Usually ready within a day."
 *
 * <p>Binds the real {@code application.yml} rather than asserting on its text, so this fails if the
 * key is deleted, renamed, or moved under a prefix the properties class doesn't read. A test that
 * only grepped the file would pass on a block that binds to nothing — the exact failure mode this
 * repo has hit before with env var names that matched no {@code ${...}} placeholder.
 */
class CreatorCopilotConfigWiringTest {

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

    private CreatorCopilotProperties bind(Map<String, Object> envVars) throws IOException {
        return Binder.get(environmentWith(envVars))
                .bind("influora.creator-copilot", CreatorCopilotProperties.class)
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "application.yml declares no influora.creator-copilot block — the"
                                                + " co-pilot batch cannot be turned on in any environment"));
    }

    @Test
    @DisplayName("application.yml: creator-copilot binds, and stays OFF when the env var is unset")
    void defaultsToDisabled() throws IOException {
        CreatorCopilotProperties props = bind(Map.of());

        assertFalse(props.isEnabled(), "co-pilot batch must stay off by default");
        assertEquals(2, props.getScoreThreshold());
        assertEquals(1, props.getMaxSuggestionsPerCreatorPerDay());
        assertEquals(25, props.getCaptionSyncMediaLimit());
        assertEquals("creator-copilot-v1", props.getPromptVersion());
    }

    @Test
    @DisplayName("CREATOR_COPILOT_ENABLED=true actually reaches CreatorCopilotProperties.enabled")
    void envVarTurnsItOn() throws IOException {
        assertTrue(
                bind(Map.of("CREATOR_COPILOT_ENABLED", "true")).isEnabled(),
                "CREATOR_COPILOT_ENABLED did not bind — the deploy has no way to enable the batch");
    }

    @Test
    @DisplayName("the two @Scheduled cron placeholders resolve from the same block")
    void cronPlaceholdersResolve() throws IOException {
        StandardEnvironment env = environmentWith(Map.of());

        // Exactly the placeholders CreatorCaptionSyncJob / CreatorThemeTaggingJob declare. The
        // trailing default is what @Scheduled would fall back to; asserting the resolved value
        // differs from a bogus sentinel proves the yaml key is what supplied it.
        assertEquals(
                "0 0 2 * * *",
                env.resolvePlaceholders("${influora.creator-copilot.caption-sync-cron:UNSET}"));
        assertEquals(
                "0 0 3 * * *",
                env.resolvePlaceholders("${influora.creator-copilot.theme-tag-batch-cron:UNSET}"));
    }

    @Test
    @DisplayName("creator-copilot-ai base-url is overridable, matching its four AI siblings")
    void aiBaseUrlBinds() throws IOException {
        CreatorSuggestionAiProperties defaults =
                Binder.get(environmentWith(Map.of()))
                        .bind("influora.creator-copilot-ai", CreatorSuggestionAiProperties.class)
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "application.yml declares no influora.creator-copilot-ai"
                                                        + " block — a container deploy would silently call"
                                                        + " localhost for suggestion phrasing"));
        assertEquals("http://localhost:8000", defaults.getBaseUrl());

        CreatorSuggestionAiProperties overridden =
                Binder.get(environmentWith(Map.of("CREATOR_COPILOT_AI_BASE_URL", "http://influora-ai:8000")))
                        .bind("influora.creator-copilot-ai", CreatorSuggestionAiProperties.class)
                        .orElseThrow(AssertionError::new);
        assertEquals("http://influora-ai:8000", overridden.getBaseUrl());
    }
}
