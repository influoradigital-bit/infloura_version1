package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * T-CREATORCONNECT-0902 Q7.4 (Critical) regression pin.
 *
 * <p>docker-compose on both Hostinger and Utho passes {@code META_CREATOR_MARKETPLACE_ENABLED} as
 * a resolved-but-EMPTY string whenever the host shell variable is unset ({@code
 * ${META_CREATOR_MARKETPLACE_ENABLED:-false}} only substitutes when the var is literally unset —
 * an empty-but-present var still forwards ""). Spring's relaxed {@link Binder} converting "" to a
 * primitive {@code boolean} throws {@code ConversionFailedException} and crashes API boot before
 * this fix; {@link MetaApiProperties.CreatorMarketplace#enabled} was changed to a nullable {@code
 * Boolean} with a coalescing setter specifically so that "" binds without throwing and yields
 * {@code false}.
 *
 * <p>This binds the REAL {@code application.yml} (not a hand-built object), so it fails the moment
 * either (a) {@code enabled} regresses back to a primitive {@code boolean}, or (b) the {@code
 * influora.meta.creator-marketplace.enabled: ${META_CREATOR_MARKETPLACE_ENABLED:false}} line in
 * application.yml is renamed/removed and stops carrying the env var through to this class.
 */
class MetaApiPropertiesCreatorMarketplaceBindingTest {

    private static final String YML = "application.yml";

    private MetaApiProperties bind(Map<String, Object> envVars) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Highest precedence, standing in for the real OS/docker-compose environment variables.
        env.getPropertySources().addFirst(new MapPropertySource("test-env", envVars));
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load(YML, new ClassPathResource(YML));
        for (PropertySource<?> source : yaml) {
            env.getPropertySources().addLast(source);
        }
        return Binder.get(env)
                .bind("influora.meta", MetaApiProperties.class)
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "application.yml declares no influora.meta block — Meta config"
                                                + " cannot be bound in any environment"));
    }

    @Test
    @DisplayName(
            "META_CREATOR_MARKETPLACE_ENABLED='' (resolved-but-empty, as docker-compose sends it"
                    + " when the host var is unset) binds without throwing and yields disabled")
    void emptyEnvVarBindsWithoutThrowingAndStaysDisabled() {
        MetaApiProperties props =
                assertDoesNotThrow(
                        () -> bind(Map.of("META_CREATOR_MARKETPLACE_ENABLED", "")),
                        "binding an empty META_CREATOR_MARKETPLACE_ENABLED must not throw —"
                                + " this exact throw is what crashed API boot on the live Hostinger"
                                + " VPS (Q7.4)");

        assertFalse(
                props.getCreatorMarketplace().isEnabled(),
                "an empty/unset env var must coalesce to disabled, matching the documented"
                        + " off-by-default convention");
    }

    @Test
    @DisplayName("META_CREATOR_MARKETPLACE_ENABLED unset (absent, not empty) also stays disabled")
    void absentEnvVarStaysDisabled() throws IOException {
        assertFalse(bind(Map.of()).getCreatorMarketplace().isEnabled());
    }

    @Test
    @DisplayName("META_CREATOR_MARKETPLACE_ENABLED=true actually reaches CreatorMarketplace.enabled")
    void trueEnvVarEnablesIt() throws IOException {
        assertTrue(
                bind(Map.of("META_CREATOR_MARKETPLACE_ENABLED", "true"))
                        .getCreatorMarketplace()
                        .isEnabled());
    }
}
