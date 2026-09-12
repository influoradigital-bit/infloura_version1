package com.influora.config;

import java.net.URI;

/**
 * The one place that decides what {@code redirect_uri} Meta is given, and what makes a configured
 * value impossible. Static and Spring-free so the whole decision table is unit-testable, and shared
 * by {@link com.influora.integration.meta.oauth.MetaOAuthService} (which refuses to build a dialog
 * URL from an impossible value) and {@link MetaRedirectUriStartupValidator} (which says so at boot).
 *
 * <p><b>Why this class exists.</b> Verified live 2026-09-12: Meta redirected a creator to {@code
 * https://influora.in/api/v1/meta/oauth/callback?code=…&state=…} and the API answered {@code
 * UNAUTHENTICATED}. That is not a session problem and no retry could ever have fixed it —
 * {@code JwtAuthenticationFilter} reads authorization ONLY from an {@code Authorization: Bearer}
 * header (the sole cookie is {@code AuthCookieService}'s refresh cookie, {@code SameSite=Strict}
 * and path-scoped to {@code /auth}), and Meta's redirect is a top-level browser navigation from
 * facebook.com, which carries neither. So {@code redirect_uri} pointing at the API's own callback
 * path is a configuration that cannot succeed even once, for anyone.
 *
 * <p>Meta must be pointed at the SPA route instead ({@link #CREATOR_CALLBACK_PATH}, served by
 * {@code src/pages/creator-meta-callback.tsx} via {@code src/App.tsx}), which reads {@code
 * code}/{@code state} off its own query string and calls {@code GET /meta/oauth/callback} itself as
 * an authenticated XHR. {@code MetaOAuthController}'s javadoc has described that design since the
 * endpoint was written; only the deployed value disagreed.
 *
 * <p>Three separate wrong values existed when this was found, which is why the check lives in code
 * rather than in a comment: the live {@code .env} held the API path above; {@code
 * deploy/utho/generate-env.sh} generated an {@code app.} host that T-DOMAIN-0820 §4.5 had already
 * ruled out; and the commented example in {@code application-dev.yml} showed the API path as if it
 * were the shape to copy.
 */
public final class MetaRedirectUri {

    /**
     * The SPA route Meta redirects the browser to. Must stay byte-identical to the route registered
     * in {@code src/App.tsx}; {@code .proof-os/gates/meta-connect-is-completable.sh} extracts both
     * strings and compares them, so this constant cannot drift from the page that serves it.
     */
    public static final String CREATOR_CALLBACK_PATH = "/creator/settings/meta/callback";

    /**
     * The API path Meta must never be pointed at. Matched on the FULL path, deliberately: a looser
     * match on {@code /callback} or {@code meta/callback} would also reject {@link
     * #CREATOR_CALLBACK_PATH}, i.e. the correct value.
     */
    static final String API_CALLBACK_PATH = "/meta/oauth/callback";

    private MetaRedirectUri() {}

    /**
     * The redirect URI actually handed to Meta: the configured value when it is set, otherwise one
     * derived from the SPA origin — mirroring how {@code influora.shopify.redirect-uri} has always
     * defaulted ({@code application.yml}).
     *
     * <p>Blank is treated as unset on purpose. {@code application.yml} can only default a property
     * that is ABSENT, and both Utho compose files forward {@code META_REDIRECT_URI: ${META_REDIRECT_URI}}
     * — an unset host variable reaches the container as set-but-empty, which defeats a yaml default
     * entirely. That is the F-0390 class already documented in {@code
     * deploy/hostinger/docker-compose.hostinger.yml}. Handling blank here is what makes the default
     * real rather than decorative.
     */
    public static String resolve(String configured, String webBaseUrl) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return stripTrailingSlash(webBaseUrl) + CREATOR_CALLBACK_PATH;
    }

    /**
     * Whether this value points at the API's own OAuth callback — the configuration that returns
     * {@code UNAUTHENTICATED} on every Meta redirect. An unparseable value is NOT reported here:
     * Meta rejects garbage at the dialog against its own Valid OAuth Redirect URIs, and only the
     * provably-impossible case is worth failing a creator's connect over.
     */
    public static boolean pointsAtApiCallback(String redirectUri) {
        String path = pathOf(redirectUri);
        return path != null && path.endsWith(API_CALLBACK_PATH);
    }

    /** Whether this value's path is the SPA route that actually completes the connect. */
    public static boolean servesCreatorCallback(String redirectUri) {
        return CREATOR_CALLBACK_PATH.equals(pathOf(redirectUri));
    }

    /**
     * {@code scheme://host[:port]}, or {@code null} if unparseable. Used only to WARN when the
     * redirect origin is not the SPA origin — never to fail a request. A deploy may legitimately
     * serve the SPA somewhere other than {@code influora.web-base-url}, and turning that into a
     * hard block would brick a working connect to catch a typo.
     */
    public static String originOf(String redirectUri) {
        URI uri = parse(redirectUri);
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        return uri.getScheme()
                + "://"
                + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static String pathOf(String redirectUri) {
        URI uri = parse(redirectUri);
        return uri == null ? null : uri.getPath();
    }

    private static URI parse(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return null;
        }
        try {
            return new URI(redirectUri.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        String trimmed = base.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
