package com.influora.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the resolved Meta {@code redirect-uri} visible at boot instead of discoverable only by a
 * creator who grants Instagram permissions and then lands on {@code UNAUTHENTICATED}.
 *
 * <p>Verified live 2026-09-12: {@code META_REDIRECT_URI} on the production box was the API's own
 * callback path. Nothing anywhere reported it — {@code MetaApiProperties#isConfigured()} checks only
 * {@code app-id}/{@code app-secret}, so {@code /authorize} handed back a dialog URL and the flow
 * looked healthy right up to the moment Meta redirected the browser back. See {@link
 * MetaRedirectUri} for why that configuration can never succeed.
 *
 * <p><b>WARNs where {@link SecretsStartupValidator} and {@link CompanyTaxStartupValidator} throw</b>
 * — the same deliberate deviation {@link InstagramLookupCallerStartupValidator} documents, and here
 * the reason is sharper than "one feature degrades". The box that has this misconfigured right now
 * is production. A validator that aborted startup would convert a broken Instagram connect into a
 * total outage of campaigns, deals, payments and chat on the very next deploy — the fix would take
 * the platform down to announce itself. The creator-facing refusal lives in {@code
 * MetaOAuthService#assertRedirectUriUsable} instead, which fails one endpoint rather than the
 * process.
 *
 * <p>The origin mismatch is a WARN for a different reason: it can be a legitimate deploy shape. It
 * is reported because it was ALSO wrong in tracked code — {@code deploy/utho/generate-env.sh}
 * generated an {@code app.influora.in} host that T-DOMAIN-0820 §4.5 had already ruled out ("No
 * {@code app.} subdomain is needed under this decision") — and a redirect URI on a host that does
 * not serve the SPA dead-ends exactly like the API path does, just with a 404 instead of a 401.
 */
@Configuration
public class MetaRedirectUriStartupValidator {

    private static final Logger log =
            LoggerFactory.getLogger(MetaRedirectUriStartupValidator.class);

    private final MetaApiProperties metaApiProperties;
    private final String webBaseUrl;

    public MetaRedirectUriStartupValidator(
            MetaApiProperties metaApiProperties,
            @Value("${influora.web-base-url}") String webBaseUrl) {
        this.metaApiProperties = metaApiProperties;
        this.webBaseUrl = webBaseUrl;
    }

    @PostConstruct
    void validate() {
        for (String warning : describe(metaApiProperties, webBaseUrl)) {
            log.warn(warning);
        }
    }

    /**
     * Package-private and static so the whole decision table is testable without a Spring context.
     *
     * @return one line per problem, empty when both configured paths land on the SPA route at the
     *     SPA origin. Silent when Meta is switched off entirely — an unset Meta app is a documented
     *     state ({@code application.yml}), and warning about the redirect URI of a disabled
     *     integration is the kind of permanent boot noise that gets logs ignored.
     */
    static List<String> describe(MetaApiProperties props, String webBaseUrl) {
        List<String> warnings = new ArrayList<>();
        if (props.isConfigured()) {
            check(warnings, "FACEBOOK_LOGIN", "META_REDIRECT_URI", props.getRedirectUri(), webBaseUrl);
        }
        if (props.isInstagramLoginConfigured()) {
            check(
                    warnings,
                    "INSTAGRAM_LOGIN",
                    "META_INSTAGRAM_REDIRECT_URI",
                    props.getInstagramRedirectUri(),
                    webBaseUrl);
        }
        return warnings;
    }

    private static void check(
            List<String> warnings,
            String authPath,
            String envVar,
            String configured,
            String webBaseUrl) {
        String effective = MetaRedirectUri.resolve(configured, webBaseUrl);

        if (MetaRedirectUri.pointsAtApiCallback(effective)) {
            warnings.add(
                    authPath
                            + " connect is BROKEN: "
                            + envVar
                            + " is "
                            + effective
                            + " — this API's own callback path. Meta redirects the BROWSER there and a"
                            + " browser navigation carries no Authorization header, so every connect"
                            + " returns UNAUTHENTICATED after the creator has already granted"
                            + " permissions. GET /meta/oauth/authorize now refuses with 503"
                            + " META_REDIRECT_URI_MISCONFIGURED rather than dead-ending them. Set "
                            + envVar
                            + " to "
                            + MetaRedirectUri.resolve(null, webBaseUrl)
                            + " and add that exact URL to the Meta app's Valid OAuth Redirect URIs.");
            return;
        }

        if (!MetaRedirectUri.servesCreatorCallback(effective)) {
            warnings.add(
                    authPath
                            + " connect will dead-end: "
                            + envVar
                            + " is "
                            + effective
                            + ", whose path is not "
                            + MetaRedirectUri.CREATOR_CALLBACK_PATH
                            + " — the only route that completes a Meta connect (src/App.tsx). Meta"
                            + " will send the creator somewhere this app does not serve.");
            return;
        }

        String origin = MetaRedirectUri.originOf(effective);
        String expected = MetaRedirectUri.originOf(MetaRedirectUri.resolve(null, webBaseUrl));
        if (origin != null && expected != null && !origin.equals(expected)) {
            warnings.add(
                    authPath
                            + " redirect origin is "
                            + origin
                            + " but influora.web-base-url is "
                            + expected
                            + ". If the SPA is not served at "
                            + origin
                            + ", the creator lands on a 404 holding a valid one-time code. Intentional"
                            + " only if that host really serves this app's frontend.");
        }
    }
}
