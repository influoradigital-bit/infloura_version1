package com.influora.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * F-0697 (unset-credential-degrades-silently) — makes the resolved state of the Instagram
 * handle-lookup caller chain visible at boot instead of discoverable only by a brand hitting a 503.
 *
 * <p>{@code ExternalCreatorService#resolveBusinessDiscoveryCaller} tries three callers in order:
 * the requesting brand's own Meta token, then Influora's own system account ({@code
 * META_SYSTEM_IG_USER_ID} / {@code META_SYSTEM_IG_ACCESS_TOKEN}), then — as a last resort — an
 * arbitrary connected creator's {@code FACEBOOK_LOGIN} token. Both halves of the system pair
 * default to empty ({@code application.yml:438-439}) and both compose files forward them as
 * {@code ${VAR:-}}, so on every deploy shipped so far step 2 has been dead and every brand lookup
 * has either borrowed a creator's token or returned {@code INSTAGRAM_LOOKUP_UNAVAILABLE}. Nothing
 * said so anywhere.
 *
 * <p><b>Why this one WARNs where {@link CompanyTaxStartupValidator} and {@code
 * SecretsStartupValidator} throw.</b> Those two guard things that make the application wrong when
 * missing — a placeholder GSTIN mis-taxes every invoice, a default signing secret is a security
 * hole. An unset Instagram system caller makes ONE optional feature degrade. Aborting startup over
 * it would convert a degraded lookup into a total outage of campaigns, deals, payments and chat,
 * which is a strictly worse failure than the one this record is about. Deviating from the
 * fail-closed house convention is therefore deliberate, and is the whole reason this is a separate
 * class rather than three more lines in SecretsStartupValidator: bundling it there would have
 * inherited the throw.
 *
 * <p>The half-configured case is called out separately and most loudly. A pair where exactly one
 * side is set is never intentional — it is the {@code KNOWN_DEV_DEFAULTS}-style trap where an env
 * var name looks provisioned but binds to nothing, and it fails identically to setting neither
 * while looking, in a config dump, like it is switched on.
 */
@Configuration
public class InstagramLookupCallerStartupValidator {

    private static final Logger log =
            LoggerFactory.getLogger(InstagramLookupCallerStartupValidator.class);

    private final MetaApiProperties metaApiProperties;
    private final InfluoraEnvironment environment;

    public InstagramLookupCallerStartupValidator(
            MetaApiProperties metaApiProperties, InfluoraEnvironment environment) {
        this.metaApiProperties = metaApiProperties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        for (String warning : describe(metaApiProperties)) {
            log.warn(warning);
        }
        if (environment.isDev()) {
            log.debug("Instagram lookup caller check ran in dev — warnings above are expected locally");
        }
    }

    /**
     * Package-private and static so the whole decision table is testable without a Spring context.
     *
     * @return one line per problem worth a brand's attention; empty when the system caller is fully
     *     provisioned and lookups will run as Influora rather than as somebody else.
     */
    static List<String> describe(MetaApiProperties props) {
        List<String> warnings = new ArrayList<>();

        if (!props.isConfigured()) {
            warnings.add(
                    "Instagram handle lookup is OFF: influora.meta.app-id/app-secret are unset, so"
                        + " GET /creators/external/lookup returns 503 INSTAGRAM_LOOKUP_UNAVAILABLE for"
                        + " every brand. Nothing below can compensate for this.");
            return warnings;
        }

        String userId = props.getSystemIgUserId();
        String token = props.getSystemIgAccessToken();
        boolean hasUserId = userId != null && !userId.isBlank();
        boolean hasToken = token != null && !token.isBlank();

        if (hasUserId && hasToken) {
            return warnings;
        }

        if (hasUserId != hasToken) {
            warnings.add(
                    "Instagram system caller is HALF configured: "
                            + (hasUserId ? "META_SYSTEM_IG_USER_ID" : "META_SYSTEM_IG_ACCESS_TOKEN")
                            + " is set but "
                            + (hasUserId ? "META_SYSTEM_IG_ACCESS_TOKEN" : "META_SYSTEM_IG_USER_ID")
                            + " is empty. resolveBusinessDiscoveryCaller() needs BOTH and treats this"
                            + " exactly like setting neither — the variable that IS set does nothing.");
        } else {
            warnings.add(
                    "Instagram system caller is UNSET (META_SYSTEM_IG_USER_ID and"
                        + " META_SYSTEM_IG_ACCESS_TOKEN are both empty).");
        }

        warnings.add(
                "Consequence: a brand handle lookup with no Meta connection of its own now falls"
                    + " through to borrowing an arbitrary connected CREATOR's FACEBOOK_LOGIN token."
                    + " That consumes that creator's own Graph rate limit (MetaGraphApiClient keys"
                    + " throttling on the same igBusinessAccountId, so their MetricsPollingJob"
                    + " starves), and cross-user token reuse is still awaiting a platform-terms"
                    + " ruling. If no creator token is available either, every lookup 503s.");

        return warnings;
    }
}
