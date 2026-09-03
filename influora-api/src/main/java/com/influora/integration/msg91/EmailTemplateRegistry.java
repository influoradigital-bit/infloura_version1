package com.influora.integration.msg91;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Subject/heading/body copy + optional CTA for every {@code templateKey} the app sends email for
 * (see {@code NotificationListener}, {@code BrandEmailOtpService}, {@code WorkspaceMemberService}
 * — the only {@code sendTemplateEmail} call sites). Data-driven rather than 30+ separate template
 * files: almost every one of these is structurally identical (heading + one body paragraph +
 * optional single CTA button) inside the same branded shell ({@link #wrapHtml}), so only the copy
 * differs per key. A spec may instead supply its own multi-block {@code htmlBodyTemplate} when one
 * paragraph cannot carry the message — {@code creator.connect_account} is the case that needed it.
 *
 * <p>{@code {{variable}}} placeholders in {@code subject}/{@code bodyTemplate} are substituted
 * from the event's own template-data map — already what every {@code sendTemplateEmail} caller
 * passes, no new data plumbing needed. Values are HTML-escaped before going into the HTML body
 * (some, like {@code portfolio.contact}'s sender name/message, are public-visitor-supplied free
 * text — escaping is load-bearing here, not defensive boilerplate).
 *
 * <p>Colors match the app's brand palette ({@code src/app/globals.css} "Violet Ink" — see
 * {@code feedback_brand_cta_contrast} memory): primary {@code #6d5ae6} (white-on-primary CTA,
 * 4.93:1, passes WCAG AA), not the pale {@code #9b8cf2} the app deliberately moved away from.
 */
final class EmailTemplateRegistry {

    private EmailTemplateRegistry() {}

    /**
     * @param bodyTemplate the plain-text body, and — unless {@code htmlBodyTemplate} is set — also
     *     the HTML body, wrapped in a single styled paragraph by {@link #para}.
     * @param htmlBodyTemplate optional multi-block HTML body, supplying its own block markup and
     *     therefore <b>not</b> wrapped in a paragraph. Set it only when one paragraph genuinely
     *     cannot carry the message: the two bodies then have to be kept in sync by hand, and a
     *     divergence is invisible to the compiler. The plain-text part still comes from {@code
     *     bodyTemplate}, so HTML tags must never be put in that field — they would render as
     *     literal angle brackets in the text/plain alternative.
     */
    private record Spec(
            String subject,
            String heading,
            String bodyTemplate,
            String ctaLabel,
            String ctaUrlVar,
            String htmlBodyTemplate) {
        Spec(String subject, String heading, String bodyTemplate) {
            this(subject, heading, bodyTemplate, null, null, null);
        }

        Spec(String subject, String heading, String bodyTemplate, String ctaLabel, String ctaUrlVar) {
            this(subject, heading, bodyTemplate, ctaLabel, ctaUrlVar, null);
        }
    }

    record Rendered(String subject, String plainText, String html) {}

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        // Creator-facing (brand -> creator)
        SPECS.put(
                "creator.campaign_match",
                new Spec(
                        "New campaign match: {{campaign_title}}",
                        "New campaign in your category",
                        "{{brand_name}} just launched “{{campaign_title}}” in {{category}} — check"
                                + " it out."));
        SPECS.put(
                "creator.new_conversation",
                new Spec(
                        "New message from {{brand_name}}",
                        "New message",
                        "{{brand_name}} started a conversation with you."));
        SPECS.put(
                "creator.proposal_received",
                new Spec(
                        "New proposal from {{brand_name}}",
                        "New proposal received",
                        "{{brand_name}} sent you a proposal for “{{campaign_title}}” —"
                                + " {{proposed_amount}}."));
        SPECS.put(
                "creator.bid_accepted",
                new Spec(
                        "Your bid was accepted!",
                        "Your bid was accepted",
                        "{{brand_name}} accepted your bid for “{{campaign_title}}” at"
                                + " {{accepted_amount}}."));
        SPECS.put(
                "creator.campaign_live",
                new Spec(
                        "Campaign is live!",
                        "Campaign is live",
                        "{{brand_name}} secured the funds for “{{campaign_title}}” — you're good"
                                + " to go."));
        SPECS.put(
                "creator.product_shipped",
                new Spec(
                        "Your product has shipped",
                        "Product shipped",
                        "{{brand_name}} shipped “{{product_name}}”.",
                        "Track shipment",
                        "tracking_url"));
        SPECS.put(
                "creator.sign_contract",
                new Spec(
                        "Contract ready for your signature",
                        "Contract ready for signature",
                        "Please sign the contract for “{{campaign_title}}” with {{brand_name}}."));
        SPECS.put(
                "creator.payout_released",
                new Spec(
                        "Payment released!",
                        "Payment released",
                        "{{brand_name}} released {{amount}} for “{{campaign_title}}”."));
        SPECS.put(
                "creator.kyc_approved",
                new Spec(
                        "KYC approved",
                        "KYC approved",
                        "Congratulations! Your KYC verification is complete."));
        SPECS.put(
                "creator.kyc_rejected",
                new Spec(
                        "Action needed: KYC verification issue",
                        "KYC verification issue",
                        "There was an issue with your KYC: {{rejection_reason}}."));

        // Brand-facing (creator -> brand)
        SPECS.put(
                "brand.new_application",
                new Spec(
                        "New application received",
                        "New application received",
                        "{{creator_name}} applied to “{{campaign_title}}”."));
        SPECS.put(
                "brand.counter_bid",
                new Spec(
                        "Counter-bid received",
                        "Counter-bid received",
                        "{{creator_name}} countered with {{counter_amount}} for"
                                + " “{{campaign_title}}”."));
        SPECS.put(
                "brand.proposal_accepted",
                new Spec(
                        "Proposal accepted!",
                        "Proposal accepted",
                        "{{creator_name}} accepted your proposal for “{{campaign_title}}”."));
        SPECS.put(
                "brand.contract_signed",
                new Spec(
                        "Contract signed",
                        "Contract signed",
                        "{{creator_name}} signed the contract for “{{campaign_title}}”."));
        SPECS.put(
                "brand.contract_ready_for_escrow",
                new Spec(
                        "Secure the funds to get started",
                        "Secure the funds to get started",
                        "Both parties signed the contract for “{{campaign_title}}” — secure"
                                + " the funds to begin."));
        SPECS.put(
                "brand.deliverable_ready",
                new Spec(
                        "Deliverable submitted",
                        "Deliverable submitted",
                        "{{creator_name}} submitted a {{deliverable_type}} for"
                                + " “{{campaign_title}}”."));
        SPECS.put(
                "brand.product_received",
                new Spec(
                        "Product received",
                        "Product received",
                        "{{creator_name}} confirmed receipt of “{{product_name}}”."));
        SPECS.put(
                "brand.new_conversation",
                new Spec(
                        "New message from {{creator_name}}",
                        "New message",
                        "{{creator_name}} started a conversation with you."));
        SPECS.put(
                "brand.low_balance",
                new Spec(
                        "Low wallet balance",
                        "Low wallet balance",
                        "Your wallet balance is low ({{current_balance}}). Consider adding funds."));
        SPECS.put(
                "brand.credits_exhausted",
                new Spec(
                        "AI credits exhausted",
                        "AI credits exhausted",
                        "You've used all your free AI credits. Launch a campaign to unlock unlimited"
                                + " access!"));

        // Portfolio / auth / account
        SPECS.put(
                "portfolio.contact",
                new Spec(
                        "New portfolio contact from {{senderName}}",
                        "New portfolio contact",
                        "{{senderName}} ({{senderEmail}}) sent you a message: “{{message}}”"));
        SPECS.put(
                "auth.otp",
                new Spec(
                        "Your Influora verification code",
                        "Your verification code",
                        "Your one-time code is: {{otp}}. It expires in 5 minutes."));
        // BrandEmailOtpService passes the raw MSG91 dashboard template id ("otpman" by default,
        // MSG91_EMAIL_TEMPLATE_ID) as templateKey, not the "auth.otp" domain-event key above.
        SPECS.put(
                "otpman",
                new Spec(
                        "Your Influora verification code",
                        "Your verification code",
                        "Your one-time code is: {{otp}}. It expires in 5 minutes."));
        SPECS.put(
                "auth.password_reset",
                new Spec(
                        "Reset your Influora password",
                        "Reset your password",
                        "We received a request to reset your password. Click below to choose a new"
                                + " one — if you didn't request this, you can ignore this email.",
                        "Reset password",
                        "reset_link"));
        SPECS.put(
                "welcome.brand",
                new Spec(
                        "Welcome to Influora!",
                        "Welcome to Influora!",
                        "We're excited to have you on board, {{user_name}}!"));
        SPECS.put(
                "welcome.creator",
                new Spec(
                        "Welcome to Influora!",
                        "Welcome to Influora!",
                        "We're excited to have you on board, {{user_name}}!"));
        // Activation nudge for a creator who registered but never connected Instagram. Until they
        // do, the profile carries no audience data, so it is absent from brand search and matches
        // never reach them -- the account is inert rather than merely incomplete. The copy leads
        // with that cost, then answers the objection that actually stalls people here (handing a
        // platform their Instagram), because "finish your setup" reads as our admin, not their
        // loss. Sends via a scheduled job; {{connect_url}} must be supplied in the template data.
        // Deliberately NOT in NO_UNSUBSCRIBE_FOOTER: this is lifecycle marketing, not a
        // transactional message, so it has to carry an unsubscribe link.
        SPECS.put(
                "creator.connect_account",
                new Spec(
                        "Brands can't see your profile yet",
                        "You're one step away",
                        "Hi {{user_name}},\n\n"
                                + "You joined Influora to work with brands. Brands are searching"
                                + " for creators right now — but they can't find you.\n\n"
                                + "Your profile has no audience data yet, so you don't appear in"
                                + " brand search and campaign matches never reach you. Connecting"
                                + " Instagram fixes that in about 30 seconds.\n\n"
                                + "WHY WE ASK FOR INSTAGRAM\n"
                                + "Brands choose creators on real numbers — followers, reach,"
                                + " engagement, and the categories you actually post in. Connecting"
                                + " pulls those in automatically, so you never fill a form or"
                                + " upload screenshots of your insights.\n\n"
                                + "We only read. We never post, never message anyone, never see"
                                + " your DMs, and never get your password. You can disconnect"
                                + " anytime from Settings.\n\n"
                                + "ONCE YOU'RE CONNECTED\n"
                                + "- You appear in brand search\n"
                                + "- Campaign matches start arriving in your categories\n"
                                + "- You can apply to campaigns and receive proposals directly\n"
                                + "- Payment is secured before you start, and released when your"
                                + " work is approved",
                        "Connect Instagram",
                        "connect_url",
                        // Kept in step with bodyTemplate above, by hand. Styles are inline and
                        // duplicated rather than shared with P_STYLE: this static block runs
                        // before that field is initialised, so referencing it would read null.
                        "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.65;color:#3d3852;\">"
                                + "Hi {{user_name}},</p>"
                                + "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.65;"
                                + "color:#3d3852;\">You joined Influora to work with brands. Brands"
                                + " are searching for creators right now — but they can't find"
                                + " you.</p>"
                                + "<p style=\"margin:0 0 24px;font-size:15px;line-height:1.65;"
                                + "color:#3d3852;\">Your profile has no audience data yet, so you"
                                + " don't appear in brand search and campaign matches never reach"
                                + " you. Connecting Instagram fixes that in about 30 seconds.</p>"
                                + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;"
                                + "color:#221e35;\">Why we ask for Instagram</p>"
                                + "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.65;"
                                + "color:#3d3852;\">Brands choose creators on real numbers —"
                                + " followers, reach, engagement, and the categories you actually"
                                + " post in. Connecting pulls those in automatically, so you never"
                                + " fill a form or upload screenshots of your insights.</p>"
                                + "<p style=\"margin:0 0 24px;font-size:15px;line-height:1.65;"
                                + "color:#3d3852;\">We only read. We never post, never message"
                                + " anyone, never see your DMs, and never get your password. You"
                                + " can disconnect anytime from Settings.</p>"
                                + "<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;"
                                + "color:#221e35;\">Once you're connected</p>"
                                + "<ul style=\"margin:0 0 24px;padding-left:20px;font-size:15px;"
                                + "line-height:1.65;color:#3d3852;\">"
                                + "<li style=\"margin-bottom:6px;\">You appear in brand search</li>"
                                + "<li style=\"margin-bottom:6px;\">Campaign matches start arriving"
                                + " in your categories</li>"
                                + "<li style=\"margin-bottom:6px;\">You can apply to campaigns and"
                                + " receive proposals directly</li>"
                                + "<li>Payment is secured before you start, and released when your"
                                + " work is approved</li>"
                                + "</ul>"));

        // Workspace
        SPECS.put(
                "brand.workspace_invite",
                new Spec(
                        "You've been invited to {{workspace_name}}",
                        "Workspace invitation",
                        "You've been invited to join “{{workspace_name}}” as {{role}}. This"
                                + " invite expires {{expires_at}}."));
        SPECS.put(
                "brand.workspace_invite_new_user",
                new Spec(
                        "You've been invited to {{workspace_name}} on Influora",
                        "Workspace invitation",
                        "You've been invited to join “{{workspace_name}}” as {{role}} on"
                                + " Influora. Create an account to accept — this invite expires"
                                + " {{expires_at}}."));

        // Creator connections (T-CREATORCONNECT-0902) — Meta-sourced creator -> admin -> brand
        SPECS.put(
                "admin.creator_connection_requested",
                new Spec(
                        "Creator connection request: @{{ig_username}}",
                        "New creator connection request",
                        "{{brand_name}} wants to work with @{{ig_username}} ({{followers}} followers)."
                                + " Message: {{message}}",
                        "Open in admin",
                        "admin_url"));
        SPECS.put(
                "creator.join_invitation",
                new Spec(
                        "{{brand_name}} wants to work with you on Influora",
                        "A brand wants to work with you",
                        "A brand on Influora asked to collaborate with @{{ig_username}}. Join Influora"
                                + " to see the opportunity, get paid through escrow, and manage the deal.",
                        "Join Influora",
                        "signup_url"));
        SPECS.put(
                "brand.connected_creator_joined",
                new Spec(
                        "@{{ig_username}} joined Influora",
                        "@{{ig_username}} joined Influora",
                        "The creator you asked to connect with is now verified on Influora. Create a"
                                + " campaign to start working together.",
                        "Create a campaign",
                        "campaign_url"));

        // Billing
        SPECS.put(
                "billing.subscription_halted",
                new Spec(
                        "Your subscription was halted",
                        "Subscription halted",
                        "Payment retries were exhausted and your subscription has been halted. Update"
                                + " your payment method to resume service."));
        SPECS.put(
                "billing.payment_failed",
                new Spec(
                        "Payment failed",
                        "Payment failed",
                        "Your subscription payment could not be processed. We'll retry automatically,"
                                + " but you may want to update your payment method."));
        SPECS.put(
                "billing.invoice_ready",
                new Spec(
                        "Your invoice is ready",
                        "Your invoice is ready",
                        "Your invoice is ready to download.",
                        "Download invoice",
                        "download_url"));
        SPECS.put(
                "user.monthly_statement",
                new Spec(
                        "Your monthly statement is ready",
                        "Monthly statement ready",
                        "Your statement for {{statement_period}} is ready.",
                        "View statement",
                        "statement_url"));
    }

    /**
     * Security/account-access keys that should never show an "unsubscribe" link — a user cannot
     * opt out of the OTP or password-reset email they just requested, and offering to is
     * confusing at best. {@link com.influora.service.notification.NotificationService}'s own
     * unsubscribe-preference gate still applies to these upstream (unchanged, pre-existing
     * behavior); this only controls whether the footer link is *shown*.
     */
    private static final java.util.Set<String> NO_UNSUBSCRIBE_FOOTER =
            java.util.Set.of("auth.otp", "otpman", "auth.password_reset");

    /**
     * T-ADMINMAIL-0903 — admin custom email send. Deliberately NOT a {@link #SPECS} entry: every
     * other key has fixed, developer-authored copy; this one is admin-authored per campaign, so
     * the rendered subject/body ride in {@code data} itself instead of a static {@link Spec}. See
     * {@link #renderCustom}. Deliberately NOT in {@link #NO_UNSUBSCRIBE_FOOTER} either — this is
     * marketing mail, so it must always carry the unsubscribe footer.
     */
    static final String ADMIN_CUSTOM_TEMPLATE_KEY = "admin.custom";

    static Rendered render(String templateKey, Map<String, Object> data, String unsubscribeUrl) {
        if (ADMIN_CUSTOM_TEMPLATE_KEY.equals(templateKey)) {
            return renderCustom(data, unsubscribeUrl);
        }

        boolean showUnsubscribe = unsubscribeUrl != null && !NO_UNSUBSCRIBE_FOOTER.contains(templateKey);

        Spec spec = SPECS.get(templateKey);
        if (spec == null) {
            // Unknown key (e.g. a new event type added without a matching registry entry yet) —
            // fall back to a generic, still-readable rendering rather than throwing.
            String subject = "Influora notification (" + templateKey + ")";
            String plain = genericPlainText(templateKey, data);
            String html =
                    wrapHtml(
                            subject,
                            para(escapeHtml(plain).replace("\n", "<br>")),
                            null,
                            null,
                            showUnsubscribe ? unsubscribeUrl : null);
            return new Rendered(subject, plain + unsubscribeFooterText(showUnsubscribe, unsubscribeUrl), html);
        }

        String subject = substitute(spec.subject(), data, false);
        String plainBody = substitute(spec.bodyTemplate(), data, false);
        // A spec with its own HTML body supplies complete block markup and is emitted as-is;
        // everything else is one paragraph. Either way the value substituted in is escaped.
        String htmlBody =
                spec.htmlBodyTemplate() != null
                        ? substitute(spec.htmlBodyTemplate(), data, true)
                        : para(substitute(spec.bodyTemplate(), data, true));
        String ctaUrl = spec.ctaUrlVar() != null ? asString(data.get(spec.ctaUrlVar())) : null;
        boolean hasCta = ctaUrl != null && !ctaUrl.isBlank();

        String plain =
                plainBody
                        + (hasCta ? "\n\n" + spec.ctaLabel() + ": " + ctaUrl : "")
                        + unsubscribeFooterText(showUnsubscribe, unsubscribeUrl);
        String html =
                wrapHtml(
                        spec.heading(),
                        htmlBody,
                        hasCta ? spec.ctaLabel() : null,
                        hasCta ? ctaUrl : null,
                        showUnsubscribe ? unsubscribeUrl : null);
        return new Rendered(subject, plain, html);
    }

    /**
     * Renders T-ADMINMAIL-0903's admin custom send inside the SAME branded shell as every other
     * template (class javadoc: "reuse EmailTemplateRegistry's shell... do not fork wrapHtml's
     * markup"). Unlike {@link #render}'s {@link #SPECS} path, there is no fixed {@link Spec} — the
     * admin-authored, per-recipient-personalized {@code subject}/{@code bodyText} (plus optional
     * {@code ctaLabel}/{@code ctaUrl}) ride directly in {@code data}, already substituted by
     * {@code AdminCustomEmailService} at enqueue time. This method does not know about {@code
     * {{token}}} placeholders at all — it only escapes and lays the plain-text body out as HTML.
     *
     * <p>{@code bodyText} is plain text (never raw admin-authored HTML — see class javadoc "No PII
     * beyond name" and the controller-side contract "bodyText is plain text, never raw HTML"), so
     * every paragraph is escaped before being wrapped, exactly like the unknown-{@code
     * templateKey} fallback path above.
     */
    private static Rendered renderCustom(Map<String, Object> data, String unsubscribeUrl) {
        String subject = asString(data.get("subject"));
        String bodyText = asString(data.get("bodyText"));
        String ctaLabel = asString(data.get("ctaLabel"));
        String ctaUrl = asString(data.get("ctaUrl"));
        boolean hasCta = !ctaLabel.isBlank() && !ctaUrl.isBlank();

        // No NO_UNSUBSCRIBE_FOOTER exclusion for this key (see ADMIN_CUSTOM_TEMPLATE_KEY javadoc)
        // — the footer shows whenever the caller supplies a URL, same rule as every marketing key.
        boolean showUnsubscribe = unsubscribeUrl != null;

        String htmlBody = paragraphsToHtml(bodyText);
        String html =
                wrapHtml(
                        subject,
                        htmlBody,
                        hasCta ? ctaLabel : null,
                        hasCta ? ctaUrl : null,
                        showUnsubscribe ? unsubscribeUrl : null);

        String plain =
                bodyText
                        + (hasCta ? "\n\n" + ctaLabel + ": " + ctaUrl : "")
                        + unsubscribeFooterText(showUnsubscribe, unsubscribeUrl);
        return new Rendered(subject, plain, html);
    }

    /**
     * Blank-line-separated paragraphs, each escaped and wrapped in exactly one {@link #para}
     * block — never double-wrapped ({@link #wrapHtml} takes the complete concatenated block). A
     * single newline inside one paragraph becomes a {@code <br>}, same convention as the unknown-
     * {@code templateKey} fallback in {@link #render}.
     */
    private static String paragraphsToHtml(String bodyText) {
        String[] paragraphs = bodyText.split("\\n\\s*\\n");
        StringBuilder html = new StringBuilder();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            html.append(para(escapeHtml(trimmed).replace("\n", "<br>")));
        }
        return html.toString();
    }

    private static String unsubscribeFooterText(boolean show, String unsubscribeUrl) {
        return show ? "\n\nUnsubscribe from this type of email: " + unsubscribeUrl : "";
    }

    private static String substitute(String template, Map<String, Object> data, boolean escapeForHtml) {
        String result = template;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String value = asString(entry.getValue());
            result = result.replace("{{" + entry.getKey() + "}}", escapeForHtml ? escapeHtml(value) : value);
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String genericPlainText(String templateKey, Map<String, Object> data) {
        StringBuilder body = new StringBuilder("You have a notification from Influora.\n\n");
        body.append("Template: ").append(templateKey).append("\n\n");
        data.forEach((key, value) -> body.append(key).append(": ").append(value).append("\n"));
        return body.toString();
    }

    /** Escapes text for safe inclusion in HTML body content or an href attribute value. */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Body paragraph style, shared by {@link #para} and the rich bodies, so they match exactly. */
    private static final String P_STYLE = "margin:0 0 24px;font-size:15px;line-height:1.65;color:#3d3852;";

    /** Wraps inline body HTML in the standard body paragraph. */
    private static String para(String inlineHtml) {
        return "<p style=\"" + P_STYLE + "\">" + inlineHtml + "</p>";
    }

    private static String wrapHtml(
            String heading,
            String bodyBlockHtml,
            String ctaLabel,
            String ctaUrl,
            String unsubscribeUrl) {
        String cta =
                (ctaLabel != null && ctaUrl != null && !ctaUrl.isBlank())
                        ? "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\""
                                + " style=\"margin-top:8px;\"><tr><td style=\"border-radius:8px;"
                                + "background-color:#6d5ae6;\">"
                                + "<a href=\""
                                + escapeHtml(ctaUrl)
                                + "\" style=\"display:inline-block;padding:12px 24px;font-size:14px;"
                                + "font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;\">"
                                + escapeHtml(ctaLabel)
                                + "</a></td></tr></table>"
                        : "";

        String unsubscribeLine =
                unsubscribeUrl != null
                        ? " <a href=\""
                                + escapeHtml(unsubscribeUrl)
                                + "\" style=\"color:#67617d;text-decoration:underline;\">Unsubscribe</a>"
                                + " from this type of email."
                        : "";

        // Hidden preheader: the snippet most inbox list views (Gmail, Apple Mail, Outlook) show
        // next to the subject. Without one, clients fall back to grabbing the first visible text
        // in <body> -- which would be the wordmark/tagline, not anything about the actual email.
        String preheader =
                "<div style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;\">"
                        + escapeHtml(heading)
                        + "&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;</div>";

        // Copy above uses curly quotes/em dashes (not ASCII) -- without an explicit charset, a
        // client that doesn't already default to UTF-8 mis-renders them as mojibake regardless of
        // MimeMessageHelper's charset param (that governs the MIME transport headers; some clients
        // still fall back to the in-document declaration, particularly when viewing raw source).
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head>"
                + "<body style=\"margin:0;padding:0;background-color:#faf9fd;"
                + "font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">"
                + preheader
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                + " style=\"background-color:#faf9fd;padding:40px 16px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\""
                + " style=\"max-width:480px;width:100%;background-color:#ffffff;border-radius:16px;"
                + "overflow:hidden;box-shadow:0 1px 3px rgba(34,30,53,0.08);\">"
                // Gradient accent stripe -- purely decorative, degrades to a flat #6d5ae6 line on
                // clients (older Outlook desktop) that don't support CSS gradients.
                + "<tr><td height=\"4\" style=\"background-color:#6d5ae6;"
                + "background-image:linear-gradient(90deg,#6d5ae6,#8f7ef2);line-height:4px;font-size:4px;\">"
                + "&nbsp;</td></tr>"
                + "<tr><td style=\"padding:28px 32px 20px;\">"
                + "<span style=\"color:#221e35;font-size:19px;font-weight:700;letter-spacing:-0.02em;\">"
                + "Influora</span>"
                + "<div style=\"margin-top:2px;font-size:12px;color:#67617d;\">"
                + "Payment-protected influencer marketing</div>"
                + "</td></tr>"
                + "<tr><td style=\"padding:0 32px;\"><div style=\"height:1px;background-color:#f0eefa;\">"
                + "</div></td></tr>"
                + "<tr><td style=\"padding:28px 32px;\">"
                + "<h1 style=\"margin:0 0 14px;font-size:21px;line-height:1.3;color:#221e35;\">"
                + escapeHtml(heading)
                + "</h1>"
                // Complete block markup, already paragraph-wrapped by the caller (see para()).
                // Wrapping it here instead would nest <p> inside <p> for any multi-block body,
                // which clients silently auto-close -- dropping the paragraph styling.
                + bodyBlockHtml
                + cta
                + "</td></tr>"
                + "<tr><td style=\"padding:20px 32px 24px;background-color:#faf9fd;"
                + "border-top:1px solid #f0eefa;\">"
                + "<p style=\"margin:0 0 4px;font-size:12px;font-weight:600;color:#3d3852;\">Influora"
                + " Digital Private Limited</p>"
                + "<p style=\"margin:0;font-size:12px;line-height:1.6;color:#8b86a0;\">This is an"
                + " automated message, please don't reply directly."
                + unsubscribeLine
                + "</p>"
                + "</td></tr></table>"
                + "</td></tr></table></body></html>";
    }
}
