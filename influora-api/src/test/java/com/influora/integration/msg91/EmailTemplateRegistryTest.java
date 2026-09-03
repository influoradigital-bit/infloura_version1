package com.influora.integration.msg91;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the two-body rendering added for {@code creator.connect_account}.
 *
 * <p>The registry renders one {@code bodyTemplate} into <b>both</b> MIME parts, so before this
 * change a multi-paragraph email had no correct spelling: HTML tags in the template leaked into
 * the text/plain alternative as literal angle brackets, and bare newlines collapsed in HTML
 * because the known-spec path never converted them to {@code <br>}. A spec may now carry a
 * separate {@code htmlBodyTemplate}, which is emitted as complete block markup rather than being
 * wrapped in a paragraph.
 *
 * <p>That wrapper move is the risk this class exists for: {@code wrapHtml} previously wrapped its
 * body argument in {@code <p>} itself, and every one of the ~32 templates depends on it. These
 * tests pin both sides — the rich body must not be paragraph-wrapped, and the simple bodies must
 * still be — because the failure is silent. Nested {@code <p>} is auto-closed by mail clients and
 * the styling is simply dropped; nothing throws and no build breaks.
 */
class EmailTemplateRegistryTest {

    private static final String CONNECT = "creator.connect_account";

    @Test
    @DisplayName("rich body: text/plain part carries no HTML markup")
    void richBodyPlainTextHasNoMarkup() {
        EmailTemplateRegistry.Rendered r =
                EmailTemplateRegistry.render(CONNECT, Map.of("user_name", "Aditi"), null);

        assertFalse(r.plainText().contains("<p"), "plain text leaked a <p> tag");
        assertFalse(r.plainText().contains("<ul"), "plain text leaked a <ul> tag");
        assertFalse(r.plainText().contains("</"), "plain text leaked a closing tag");
        assertTrue(r.plainText().contains("Hi Aditi,"), "greeting missing from plain text");
        assertTrue(
                r.plainText().contains("ONCE YOU'RE CONNECTED"),
                "plain text lost its section structure");
    }

    @Test
    @DisplayName("rich body: HTML keeps its block structure and is not nested inside a paragraph")
    void richBodyHtmlIsNotParagraphWrapped() {
        EmailTemplateRegistry.Rendered r =
                EmailTemplateRegistry.render(CONNECT, Map.of("user_name", "Aditi"), null);

        assertTrue(r.html().contains("<ul style="), "list did not survive into the HTML part");

        // Two independent tells, because the nesting is otherwise invisible — nothing throws and
        // the markup still "looks" fine. Both were checked against a deliberately reintroduced
        // wrapper: each fails when wrapHtml re-wraps the body, and passes when it does not.
        //
        // 1. A paragraph opening immediately inside another paragraph. Do NOT try to express this
        //    as "<p> ... <ul> with no </p> between" — the rich body closes a paragraph before the
        //    list, so such a pattern can never match and the assertion is a tautology that passes
        //    in both directions.
        assertFalse(
                r.html().matches("(?s).*<p style=\"[^\"]*\"><p.*"),
                "rich body was paragraph-wrapped — found <p> opening directly inside a <p>");

        // 2. The rich body must start where the heading ends, with its own first paragraph
        //    (16px bottom margin), not with the shared wrapper (24px).
        assertTrue(
                r.html().contains("</h1><p style=\"margin:0 0 16px"),
                "rich body did not begin directly after the heading — a wrapper was inserted");
    }

    @Test
    @DisplayName("simple bodies are still wrapped in exactly one styled paragraph")
    void simpleBodyStillParagraphWrapped() {
        EmailTemplateRegistry.Rendered r =
                EmailTemplateRegistry.render("welcome.creator", Map.of("user_name", "Aditi"), null);

        assertTrue(
                r.html().contains("<p style=\"margin:0 0 24px;font-size:15px;line-height:1.65;color:#3d3852;\">"
                        + "We're excited to have you on board, Aditi!</p>"),
                "the shared paragraph wrapper was lost for simple specs");
    }

    @Test
    @DisplayName("unknown key still renders, and is still paragraph-wrapped")
    void unknownKeyFallbackStillWrapped() {
        EmailTemplateRegistry.Rendered r =
                EmailTemplateRegistry.render("creator.no_such_event", Map.of("a", "b"), null);

        assertTrue(r.html().contains("<p style="), "fallback body lost its paragraph wrapper");
        assertTrue(r.subject().contains("creator.no_such_event"));
    }

    @Test
    @DisplayName("substituted values are HTML-escaped in the rich body")
    void richBodyEscapesSubstitutedValues() {
        EmailTemplateRegistry.Rendered r =
                EmailTemplateRegistry.render(
                        CONNECT, Map.of("user_name", "<script>alert(1)</script>"), null);

        assertFalse(r.html().contains("<script>"), "template data was injected unescaped");
        assertTrue(r.html().contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("CTA renders from connect_url, and is absent when the caller omits it")
    void ctaDependsOnConnectUrl() {
        EmailTemplateRegistry.Rendered with =
                EmailTemplateRegistry.render(
                        CONNECT,
                        Map.of("user_name", "Aditi", "connect_url", "https://app.influora.in/settings"),
                        null);
        assertTrue(with.html().contains("https://app.influora.in/settings"));
        assertTrue(with.plainText().contains("Connect Instagram: https://app.influora.in/settings"));

        // ctaUrlVar is read from the template data and never auto-built, so a caller that forgets
        // to pass it silently ships an email with no way to act on it.
        EmailTemplateRegistry.Rendered without =
                EmailTemplateRegistry.render(CONNECT, Map.of("user_name", "Aditi"), null);
        assertFalse(without.html().contains("Connect Instagram"), "CTA rendered without a URL");
    }

    @Test
    @DisplayName("lifecycle nudge carries an unsubscribe link; the OTP mail does not")
    void unsubscribeAppliesToTheNudgeButNotToOtp() {
        String url = "https://app.influora.in/u/tok";

        EmailTemplateRegistry.Rendered nudge =
                EmailTemplateRegistry.render(CONNECT, Map.of("user_name", "Aditi"), url);
        assertTrue(nudge.html().contains(url), "marketing nudge must be unsubscribable");

        EmailTemplateRegistry.Rendered otp =
                EmailTemplateRegistry.render("auth.otp", Map.of("otp", "482910"), url);
        assertFalse(otp.html().contains(url), "transactional OTP must not offer unsubscribe");
    }

    @Test
    @DisplayName("subject stays inside the mobile truncation budget")
    void subjectIsShortEnoughForMobile() {
        EmailTemplateRegistry.Rendered r =
                EmailTemplateRegistry.render(CONNECT, Map.of("user_name", "Aditi"), null);
        assertEquals("Brands can't see your profile yet", r.subject());
        assertTrue(r.subject().length() <= 40, "subject will truncate in a phone inbox");
    }
}
