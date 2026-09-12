package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decision table for the boot-time report on {@code influora.meta.redirect-uri}.
 *
 * <p>Two cases carry the weight. {@link #silentWhenMetaIsSwitchedOff()} — a warning on every deploy
 * that has Meta off is permanent noise, and noisy validators are the reason a real warning goes
 * unread. {@link #silentWhenBothPathsAreCorrect()} — a validator that warns even when the config is
 * right proves nothing by warning when it is wrong.
 */
class MetaRedirectUriStartupValidatorTest {

    private static final String WEB_BASE = "https://influora.in";
    private static final String SPA = "https://influora.in/creator/settings/meta/callback";
    private static final String API = "https://influora.in/api/v1/meta/oauth/callback";

    private static MetaApiProperties props(String facebookRedirect, String instagramRedirect) {
        MetaApiProperties p = new MetaApiProperties();
        p.setAppId("app-id");
        p.setAppSecret("app-secret");
        p.setRedirectUri(facebookRedirect);
        p.setInstagramAppId("ig-app-id");
        p.setInstagramAppSecret("ig-app-secret");
        p.setInstagramRedirectUri(instagramRedirect);
        return p;
    }

    @Test
    @DisplayName("silent when Meta is switched off entirely")
    void silentWhenMetaIsSwitchedOff() {
        MetaApiProperties off = new MetaApiProperties();
        off.setRedirectUri(API); // wrong, but nothing can use it

        assertEquals(List.of(), MetaRedirectUriStartupValidator.describe(off, WEB_BASE));
    }

    @Test
    @DisplayName("silent when both paths point at the SPA route on the SPA origin")
    void silentWhenBothPathsAreCorrect() {
        assertEquals(List.of(), MetaRedirectUriStartupValidator.describe(props(SPA, SPA), WEB_BASE));
    }

    @Test
    @DisplayName("silent when both are unset — the derived default is already correct")
    void silentWhenUnsetBecauseTheDefaultIsCorrect() {
        assertEquals(List.of(), MetaRedirectUriStartupValidator.describe(props("", ""), WEB_BASE));
    }

    @Test
    @DisplayName("reports the live 2026-09-12 configuration as BROKEN, naming the env var")
    void reportsTheApiCallbackPath() {
        List<String> warnings = MetaRedirectUriStartupValidator.describe(props(API, SPA), WEB_BASE);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("FACEBOOK_LOGIN connect is BROKEN"), warnings.get(0));
        assertTrue(warnings.get(0).contains("META_REDIRECT_URI"), warnings.get(0));
        assertTrue(warnings.get(0).contains(SPA), "must name the value to set instead");
    }

    @Test
    @DisplayName("reports each auth path separately — both were wrong in generate-env.sh")
    void reportsBothPathsIndependently() {
        List<String> warnings = MetaRedirectUriStartupValidator.describe(props(API, API), WEB_BASE);

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(1).contains("META_INSTAGRAM_REDIRECT_URI"), warnings.get(1));
    }

    @Test
    @DisplayName("reports the app. subdomain generate-env.sh used to emit, as an origin mismatch")
    void reportsOriginMismatch() {
        List<String> warnings =
                MetaRedirectUriStartupValidator.describe(
                        props("https://app.influora.in/creator/settings/meta/callback", SPA),
                        WEB_BASE);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("https://app.influora.in"), warnings.get(0));
        assertTrue(warnings.get(0).contains("influora.web-base-url"), warnings.get(0));
    }

    @Test
    @DisplayName("reports a path the SPA does not serve")
    void reportsAnUnservedPath() {
        List<String> warnings =
                MetaRedirectUriStartupValidator.describe(
                        props("https://influora.in/creator/meta/callback", SPA), WEB_BASE);

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("will dead-end"), warnings.get(0));
    }

    @Test
    @DisplayName("the Instagram path is checked only when that app is configured")
    void instagramPathCheckedOnlyWhenConfigured() {
        MetaApiProperties facebookOnly = props(SPA, API);
        facebookOnly.setInstagramAppId("");
        facebookOnly.setInstagramAppSecret("");

        assertEquals(
                List.of(), MetaRedirectUriStartupValidator.describe(facebookOnly, WEB_BASE));
    }
}
