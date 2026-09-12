package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The decision table behind the 2026-09-12 live failure: Meta redirected a creator to {@code
 * https://influora.in/api/v1/meta/oauth/callback} and the API answered UNAUTHENTICATED.
 *
 * <p>The load-bearing case is {@link #theCorrectValueIsNotRejected()}. A guard written to reject the
 * API path is trivially satisfiable by a predicate that rejects too much — matching {@code
 * /callback} or {@code meta/callback} rejects the CORRECT value too, which would brick the flow it
 * exists to protect while every "does it reject the bad value" test stayed green.
 */
class MetaRedirectUriTest {

    private static final String WEB_BASE = "https://influora.in";
    private static final String SPA = "https://influora.in/creator/settings/meta/callback";

    @Test
    @DisplayName("the exact value that was live on 2026-09-12 is rejected")
    void theLiveBrokenValueIsRejected() {
        assertTrue(
                MetaRedirectUri.pointsAtApiCallback(
                        "https://influora.in/api/v1/meta/oauth/callback"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://influora.in/api/v1/meta/oauth/callback",
                "https://api.influora.in/api/v1/meta/oauth/callback",
                "http://localhost:8080/api/v1/meta/oauth/callback",
                // No /api prefix — a deploy that serves the API at the root is just as broken.
                "https://influora.in/meta/oauth/callback"
            })
    @DisplayName("every shape of 'pointed at our own callback' is rejected")
    void apiCallbackShapesAreRejected(String uri) {
        assertTrue(MetaRedirectUri.pointsAtApiCallback(uri), uri);
    }

    @Test
    @DisplayName("the CORRECT value is not rejected — the guard must not match on /callback alone")
    void theCorrectValueIsNotRejected() {
        assertFalse(MetaRedirectUri.pointsAtApiCallback(SPA));
        assertTrue(MetaRedirectUri.servesCreatorCallback(SPA));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank resolves to the SPA route, because compose sends empty, not absent")
    void blankResolvesToTheSpaRoute(String configured) {
        assertEquals(SPA, MetaRedirectUri.resolve(configured, WEB_BASE));
    }

    @Test
    @DisplayName("null resolves to the SPA route too")
    void nullResolvesToTheSpaRoute() {
        assertEquals(SPA, MetaRedirectUri.resolve(null, WEB_BASE));
    }

    @Test
    @DisplayName("a trailing slash on web-base-url does not produce a doubled slash")
    void trailingSlashOnWebBaseUrlIsStripped() {
        assertEquals(SPA, MetaRedirectUri.resolve(null, "https://influora.in/"));
    }

    @Test
    @DisplayName("a configured value wins over the derived default, and is trimmed")
    void configuredValueWins() {
        assertEquals(
                "https://staging.influora.in/creator/settings/meta/callback",
                MetaRedirectUri.resolve(
                        "  https://staging.influora.in/creator/settings/meta/callback  ", WEB_BASE));
    }

    @Test
    @DisplayName("a wrong-host value is NOT rejected outright — only its origin is reportable")
    void wrongHostIsReportedByOriginNotRejected() {
        // deploy/utho/generate-env.sh generated this for months. It serves nothing, but blocking on
        // host would also block any legitimate split-origin deploy, so this stays a WARN-level fact.
        String appSubdomain = "https://app.influora.in/creator/settings/meta/callback";

        assertFalse(MetaRedirectUri.pointsAtApiCallback(appSubdomain));
        assertTrue(MetaRedirectUri.servesCreatorCallback(appSubdomain));
        assertEquals("https://app.influora.in", MetaRedirectUri.originOf(appSubdomain));
        assertEquals("https://influora.in", MetaRedirectUri.originOf(SPA));
    }

    @Test
    @DisplayName("a port is part of the origin, so localhost:5173 and :8080 do not look equal")
    void portIsPartOfTheOrigin() {
        assertEquals(
                "http://localhost:5173",
                MetaRedirectUri.originOf("http://localhost:5173/creator/settings/meta/callback"));
    }

    @Test
    @DisplayName("garbage is not reported as the API path — Meta rejects it at the dialog instead")
    void garbageIsNotTreatedAsTheApiPath() {
        assertFalse(MetaRedirectUri.pointsAtApiCallback("not a uri at all"));
        assertFalse(MetaRedirectUri.pointsAtApiCallback(null));
        assertFalse(MetaRedirectUri.servesCreatorCallback(null));
        assertNull(MetaRedirectUri.originOf("not a uri at all"));
    }

    @Test
    @DisplayName("the constant matches the React route this app actually serves")
    void constantMatchesTheReactRoute() {
        // Byte-for-byte with src/App.tsx. .proof-os/gates/meta-connect-is-completable.sh compares
        // the two files; this pins the Java side so a rename here fails fast in the unit suite too.
        assertEquals("/creator/settings/meta/callback", MetaRedirectUri.CREATOR_CALLBACK_PATH);
    }
}
