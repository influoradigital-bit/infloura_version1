package com.influora.integration.msg91;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Subject/heading/body copy + optional CTA for every {@code templateKey} the app sends email for
 * (see {@code NotificationListener}, {@code BrandEmailOtpService}, {@code WorkspaceMemberService}
 * — the only {@code sendTemplateEmail} call sites). Data-driven rather than 30+ separate template
 * files: every one of these is structurally identical (heading + one body paragraph + optional
 * single CTA button) inside the same branded shell ({@link #wrapHtml}), so only the copy differs
 * per key.
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

    private record Spec(
            String subject, String heading, String bodyTemplate, String ctaLabel, String ctaUrlVar) {
        Spec(String subject, String heading, String bodyTemplate) {
            this(subject, heading, bodyTemplate, null, null);
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
                        "{{brand_name}} funded the escrow for “{{campaign_title}}” — you're good"
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
                        "Fund escrow to get started",
                        "Fund escrow to get started",
                        "Both parties signed the contract for “{{campaign_title}}” — fund"
                                + " escrow to begin."));
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

    static Rendered render(String templateKey, Map<String, Object> data, String unsubscribeUrl) {
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
                            escapeHtml(plain).replace("\n", "<br>"),
                            null,
                            null,
                            showUnsubscribe ? unsubscribeUrl : null);
            return new Rendered(subject, plain + unsubscribeFooterText(showUnsubscribe, unsubscribeUrl), html);
        }

        String subject = substitute(spec.subject(), data, false);
        String plainBody = substitute(spec.bodyTemplate(), data, false);
        String htmlBody = substitute(spec.bodyTemplate(), data, true);
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

    private static String wrapHtml(
            String heading, String bodyHtml, String ctaLabel, String ctaUrl, String unsubscribeUrl) {
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
                + "Escrow-protected influencer marketing</div>"
                + "</td></tr>"
                + "<tr><td style=\"padding:0 32px;\"><div style=\"height:1px;background-color:#f0eefa;\">"
                + "</div></td></tr>"
                + "<tr><td style=\"padding:28px 32px;\">"
                + "<h1 style=\"margin:0 0 14px;font-size:21px;line-height:1.3;color:#221e35;\">"
                + escapeHtml(heading)
                + "</h1>"
                + "<p style=\"margin:0 0 24px;font-size:15px;line-height:1.65;color:#3d3852;\">"
                + bodyHtml
                + "</p>"
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
