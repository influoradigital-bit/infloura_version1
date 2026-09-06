package com.influora.integration.msg91;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.service.notification.UnsubscribeTokenService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [F-0472, partial-fix-narrows-defect] An email recipient must have a way to stop the email.
 *
 * <p>F-0444 was remediated for the IN-APP preference path, but the row that survived it says the
 * unsubscribe link is "still not reachable from a delivered message" — and reachability here is a
 * CHAIN, not a feature. It only holds if all of these are true at once: the worker passes a
 * non-null {@code userId}, the client mints a signed token from it, the registry actually puts that
 * URL in the rendered body, and the token verifies back to the pair the endpoint needs to flip an
 * {@code EmailPreference}. Break any one link and the other three still look correct in isolation,
 * which is exactly how a partial remediation reads as done.
 *
 * <p>This test walks that chain end to end on the two halves that can be exercised without a mail
 * server, and pins the transactional carve-out as its control: a password-reset mail must NOT carry
 * an unsubscribe footer, because unsubscribing from your own password reset is not a preference.
 * Without that control, code that appended the footer unconditionally would pass every other
 * assertion here.
 *
 * <p>NOT covered here: that {@code EmailWorker} passes {@code userId} (asserted structurally by the
 * F-0472 gate), and that {@code GET /notifications/unsubscribe-link} is permitted without a session
 * — a link in an email is useless behind a login wall, and that exemption lives in SecurityConfig,
 * which the gate checks rather than this test.
 */
class UnsubscribeLinkReachableFromEmailTest {

    private static final String SECRET = "test-unsubscribe-signing-secret-value";
    private static final String USER_ID = "01HUSER00000000000001";
    /** A lifecycle template — deliberately NOT transactional, so it must carry the footer. */
    private static final String LIFECYCLE_KEY = "creator.connect_account";

    private final UnsubscribeTokenService tokens = new UnsubscribeTokenService(SECRET);

    private static Map<String, Object> data() {
        return Map.of("user_name", "Priya", "first_name", "Priya");
    }

    @Test
    @DisplayName("[F-0472] a delivered lifecycle email carries the unsubscribe URL in its body")
    void testLifecycleEmailBodyContainsTheUnsubscribeUrl() {
        String token = tokens.generateToken(USER_ID, LIFECYCLE_KEY);
        String url = "https://api.influora.in/api/v1/notifications/unsubscribe-link?token=" + token;

        EmailTemplateRegistry.Rendered rendered =
                EmailTemplateRegistry.render(LIFECYCLE_KEY, data(), url);

        assertTrue(
                rendered.html().contains(url),
                "the HTML body must contain the unsubscribe URL — a link the recipient cannot see"
                        + " is the F-0472 defect");
        assertTrue(
                rendered.plainText().contains(url),
                "the text/plain part must carry it too: a recipient whose client blocks HTML still"
                        + " needs a way out");
    }

    @Test
    @DisplayName("[F-0472] the token in that body verifies back to the exact user and template")
    void testTokenFromTheEmailRedeemsToTheRightPair() {
        String token = tokens.generateToken(USER_ID, LIFECYCLE_KEY);

        // This is what the unauthenticated endpoint does with the token it receives. If it did not
        // round-trip, the link would render, be clicked, and silently do nothing.
        UnsubscribeTokenService.Parsed parsed =
                tokens.verify(token).orElseThrow(() -> new AssertionError("token did not verify"));

        assertEquals(USER_ID, parsed.userId());
        assertEquals(LIFECYCLE_KEY, parsed.eventType());
    }

    @Test
    @DisplayName("[F-0472] a tampered token is refused — the link must not be a user-id oracle")
    void testTamperedTokenIsRefused() {
        String token = tokens.generateToken(USER_ID, LIFECYCLE_KEY);
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertTrue(
                tokens.verify(tampered).isEmpty(),
                "an unauthenticated endpoint that accepted an unsigned token would let anyone"
                        + " unsubscribe anyone by editing a URL");
    }

    @Test
    @DisplayName("[F-0472 control] a transactional password-reset mail carries NO unsubscribe footer")
    void testTransactionalMailHasNoUnsubscribeFooter() {
        String url =
                "https://api.influora.in/api/v1/notifications/unsubscribe-link?token="
                        + tokens.generateToken(USER_ID, "auth.password_reset");

        EmailTemplateRegistry.Rendered rendered =
                EmailTemplateRegistry.render("auth.password_reset", data(), url);

        // The control for the whole class: without it, appending the footer unconditionally would
        // satisfy every assertion above while offering people the chance to opt out of the mail
        // that lets them back into their account.
        assertFalse(
                rendered.html().contains(url),
                "auth.password_reset is in NO_UNSUBSCRIBE_FOOTER and must not offer to unsubscribe");
        assertFalse(rendered.plainText().contains(url), "same for the text/plain part");
    }
}
