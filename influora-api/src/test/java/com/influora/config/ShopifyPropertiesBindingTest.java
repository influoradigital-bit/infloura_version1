package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Shopify connect could not be switched on in ANY deployed environment: {@code
 * influora.shopify.api-key} / {@code api-secret} / {@code webhook-signing-secret} had no binding
 * in application.yml and no compose file forwarded an env var for them, so {@link
 * ShopifyProperties#isConfigured()} was always false and {@code POST /shopify/oauth/authorize}
 * always answered 503 — while the settings page advertised "OAuth — one click".
 *
 * <p>Binds the REAL application.yml (same approach as {@code
 * MetaApiPropertiesCreatorMarketplaceBindingTest}) so this fails if a placeholder is removed or
 * renamed, and reads the real compose files so it fails if the variable stops being forwarded —
 * an env var the container never receives is as good as unbound.
 */
class ShopifyPropertiesBindingTest {

    private static final String YML = "application.yml";

    private ShopifyProperties bind(Map<String, Object> envVars) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test-env", envVars));
        // influora.web-base-url feeds the redirect-uri default in the same block.
        env.getPropertySources()
                .addLast(new MapPropertySource("base", Map.of("INFLUORA_WEB_BASE_URL", "https://app.example.com")));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load(YML, new ClassPathResource(YML));
        for (PropertySource<?> source : yaml) {
            env.getPropertySources().addLast(source);
        }
        return Binder.get(env)
                .bind("influora.shopify", ShopifyProperties.class)
                .orElseThrow(() -> new AssertionError("application.yml declares no influora.shopify block"));
    }

    @Test
    @DisplayName("with no Shopify env vars the feature is OFF (the default every environment has today)")
    void unsetStaysUnconfigured() throws IOException {
        assertFalse(bind(Map.of()).isConfigured());
    }

    @Test
    @DisplayName("resolved-but-empty values (what docker-compose sends for an unset host var) stay OFF")
    void emptyStaysUnconfigured() throws IOException {
        assertFalse(
                bind(Map.of("INFLUORA_SHOPIFY_APIKEY", "", "INFLUORA_SHOPIFY_APISECRET", "")).isConfigured());
    }

    @Test
    @DisplayName("the three env vars actually reach ShopifyProperties through application.yml")
    void envVarsBind() throws IOException {
        ShopifyProperties props =
                bind(
                        Map.of(
                                "INFLUORA_SHOPIFY_APIKEY", "client-id-123",
                                "INFLUORA_SHOPIFY_APISECRET", "client-secret-456",
                                "INFLUORA_SHOPIFY_WEBHOOKSIGNINGSECRET", "whsec-789"));

        assertTrue(props.isConfigured());
        assertEquals("client-id-123", props.getApiKey());
        assertEquals("client-secret-456", props.getApiSecret());
        assertEquals("whsec-789", props.getWebhookSigningSecret());
    }

    @Test
    @DisplayName("only one of key/secret is not 'configured' — half a credential must not enable the flow")
    void halfConfiguredIsOff() throws IOException {
        assertFalse(bind(Map.of("INFLUORA_SHOPIFY_APIKEY", "client-id-123")).isConfigured());
    }

    @Test
    @DisplayName(
            "the OAuth pair WITHOUT the webhook signing secret is not 'configured' — the store would"
                    + " connect and then silently receive no order events (QA review)")
    void oauthPairWithoutWebhookSecretIsOff() throws IOException {
        ShopifyProperties props =
                bind(
                        Map.of(
                                "INFLUORA_SHOPIFY_APIKEY", "client-id-123",
                                "INFLUORA_SHOPIFY_APISECRET", "client-secret-456"));

        assertFalse(props.isConfigured(), "every webhook delivery would fail signature verification");
        assertTrue(props.isPartiallyConfigured(), "a half-configured deploy must be visible in the boot log");
    }

    @Test
    @DisplayName("a still-placeholder webhook secret does not count as configured")
    void placeholderWebhookSecretIsOff() throws IOException {
        assertFalse(
                bind(
                                Map.of(
                                        "INFLUORA_SHOPIFY_APIKEY", "client-id-123",
                                        "INFLUORA_SHOPIFY_APISECRET", "client-secret-456",
                                        "INFLUORA_SHOPIFY_WEBHOOKSIGNINGSECRET", "REPLACE_WITH_SHOPIFY_WEBHOOK_SECRET"))
                        .isConfigured());
    }

    @Test
    @DisplayName("fully unset is not even 'partially' configured — the normal state, nothing to warn about")
    void unsetIsNotPartial() throws IOException {
        assertFalse(bind(Map.of()).isPartiallyConfigured());
    }

    @Test
    @DisplayName("every deploy compose file forwards all three variables into the API container")
    void composeFilesForwardTheVariables() throws IOException {
        Path deploy = Path.of("..", "deploy");
        for (String compose :
                List.of(
                        "hostinger/docker-compose.hostinger.yml",
                        "utho/docker-compose.utho.yml",
                        "utho/docker-compose.utho-shared.yml")) {
            String text = Files.readString(deploy.resolve(compose));
            for (String var :
                    List.of(
                            "INFLUORA_SHOPIFY_APIKEY",
                            "INFLUORA_SHOPIFY_APISECRET",
                            "INFLUORA_SHOPIFY_WEBHOOKSIGNINGSECRET")) {
                assertTrue(
                        text.contains(var + ": ${" + var),
                        compose + " does not forward " + var + " — the API container would never see it");
            }
        }
    }
}
