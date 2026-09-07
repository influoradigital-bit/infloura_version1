package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0697 — pins the decision table of {@link InstagramLookupCallerStartupValidator#describe}.
 *
 * <p>The defect this guards is not a crash, it is a silence: both halves of the Instagram system
 * caller default to empty and every shipped compose file forwards them as {@code ${VAR:-}}, so on
 * every deploy so far brand handle lookups have silently borrowed a connected creator's Meta token
 * or returned 503, with nothing anywhere saying so. A boot warning is the whole fix, which means
 * the warning existing and being accurate IS the contract — hence a test on its content and not
 * merely on its count.
 *
 * <p>The half-configured case gets its own assertion because it is the one an operator is most
 * likely to create and least likely to notice: setting one variable of a pair looks provisioned in
 * a config dump and behaves exactly like setting neither.
 */
class InstagramLookupCallerStartupValidatorTest {

    private static MetaApiProperties props(String appId, String appSecret, String igUserId, String igToken) {
        MetaApiProperties p = new MetaApiProperties();
        p.setAppId(appId);
        p.setAppSecret(appSecret);
        p.setSystemIgUserId(igUserId);
        p.setSystemIgAccessToken(igToken);
        return p;
    }

    @Test
    @DisplayName("fully provisioned system caller — silent, because there is nothing to warn about")
    void fullyProvisioned_warnsNothing() {
        assertTrue(
                InstagramLookupCallerStartupValidator.describe(
                                props("app-id", "app-secret", "17841400000000000", "EAAG-token"))
                        .isEmpty(),
                "a correctly provisioned deploy must not emit boot noise, or the warning stops"
                        + " meaning anything when it does fire");
    }

    @Test
    @DisplayName("both halves unset — says so, AND names the creator-token borrowing it causes")
    void bothUnset_warnsAboutTheBorrowFallback() {
        List<String> warnings =
                InstagramLookupCallerStartupValidator.describe(props("app-id", "app-secret", "", ""));

        assertEquals(2, warnings.size(), "the state, and its consequence");
        assertTrue(warnings.get(0).contains("UNSET"), warnings.get(0));
        // The consequence is the load-bearing half: "unset" alone reads as harmless.
        assertTrue(warnings.get(1).contains("borrowing an arbitrary connected CREATOR"), warnings.get(1));
        assertTrue(warnings.get(1).contains("rate limit"), warnings.get(1));
    }

    @Test
    @DisplayName("only the user id set — flagged as HALF configured, not as merely unset")
    void userIdWithoutToken_isCalledOutAsHalfConfigured() {
        List<String> warnings =
                InstagramLookupCallerStartupValidator.describe(
                        props("app-id", "app-secret", "17841400000000000", "  "));

        assertTrue(warnings.get(0).contains("HALF configured"), warnings.get(0));
        assertTrue(
                warnings.get(0).contains("META_SYSTEM_IG_ACCESS_TOKEN") && warnings.get(0).contains("empty"),
                "must name the variable that is MISSING, so the operator knows what to set: " + warnings.get(0));
    }

    @Test
    @DisplayName("only the token set — the same half-configured warning, naming the other variable")
    void tokenWithoutUserId_isCalledOutAsHalfConfigured() {
        List<String> warnings =
                InstagramLookupCallerStartupValidator.describe(
                        props("app-id", "app-secret", null, "EAAG-token"));

        assertTrue(warnings.get(0).contains("HALF configured"), warnings.get(0));
        assertTrue(
                warnings.get(0).contains("META_SYSTEM_IG_USER_ID") && warnings.get(0).contains("empty"),
                "must name the variable that is MISSING: " + warnings.get(0));
    }

    @Test
    @DisplayName("no Meta app at all — reports the harder failure and does not bury it under the pair")
    void unconfiguredMetaApp_shortCircuits() {
        List<String> warnings = InstagramLookupCallerStartupValidator.describe(props("", "", "", ""));

        assertEquals(1, warnings.size(), "one clear cause beats three cascading ones");
        assertTrue(warnings.get(0).contains("Instagram handle lookup is OFF"), warnings.get(0));
        assertFalse(
                warnings.get(0).contains("borrowing"),
                "with no app id/secret there is no Graph call to make at all, so the borrow fallback"
                        + " is not what an operator should go fix first");
    }
}
