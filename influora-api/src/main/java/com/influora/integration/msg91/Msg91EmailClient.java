package com.influora.integration.msg91;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.config.InfluoraEnvironment;
import com.influora.service.notification.UnsubscribeTokenService;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * MSG91 email client (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md). Sends templated emails via
 * MSG91's SMTP relay ({@code smtp.mailer91.com}), not MSG91's Email API v5.
 *
 * <p><b>Why SMTP, not the v5 HTTP API this class used to call:</b> live-tested directly against
 * {@code control.msg91.com/api/v5/email/send} with both {@code MSG91_AUTH_KEY} (SMS auth-key) and
 * {@code MSG91_TOKEN_AUTH} (the credential the project's own MSG91-EMAIL-OTP.md docs say the v5
 * API actually wants) — both came back {@code 401 Unauthorized} with different {@code apiError}
 * subcodes (418 vs 201), meaning MSG91 evaluates them via genuinely separate logic and neither
 * currently authenticates. MSG91's SMTP relay under separate {@code SMTP_*} credentials does
 * authenticate, so sending goes through {@link JavaMailSender} (Spring Boot's mail starter,
 * auto-configured from {@code spring.mail.*}) instead.
 *
 * <p>{@code JavaMailSender} is obtained via {@link ObjectProvider}, not constructor-injected
 * directly: {@code MailSenderAutoConfiguration} only creates the bean when {@code spring.mail.host}
 * is set, so a deployment with no SMTP host configured (e.g. dev-mock, or before SMTP creds exist)
 * must still boot — a direct dependency would throw {@code NoSuchBeanDefinitionException}.
 *
 * <p><b>Sender domain constraint:</b> the SMTP relay account only accepts its own authenticated
 * domain as the envelope sender — live-tested {@code MAIL FROM:<...@influora.com>} under these
 * credentials and got {@code 550 5.7.1 Invalid Mail From address received}. {@code fromEmail}
 * therefore stays on the relay account's domain until that domain is verified with MSG91
 * separately; only the display name ({@code fromName}) is Influora-branded.
 *
 * <p>SMTP has no MSG91 dashboard templates to render server-side, so the previous
 * {@code template_id} handling is gone — subject/HTML body/plain-text fallback are rendered from
 * the same {@code templateKey}/variables every caller already passes, via {@link
 * EmailTemplateRegistry}'s per-key copy inside a shared branded shell, plus a one-click
 * unsubscribe link ({@link UnsubscribeTokenService}) for the callers that know a {@code userId}.
 *
 * <p>No PII beyond name in email bodies per Kabir's security constraints (§7).
 */
@Component
public class Msg91EmailClient {

    private static final Logger log = LoggerFactory.getLogger(Msg91EmailClient.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromEmail;
    private final String fromName;
    private final String apiPublicUrl;
    private final String contextPath;
    private final InfluoraEnvironment environment;
    private final ObjectMapper objectMapper;
    private final UnsubscribeTokenService unsubscribeTokenService;

    public Msg91EmailClient(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${influora.msg91.email.from-email:noreply@influora.com}") String fromEmail,
            @Value("${influora.msg91.email.from-name:Influora}") String fromName,
            @Value("${influora.api.public-url}") String apiPublicUrl,
            @Value("${server.servlet.context-path:}") String contextPath,
            InfluoraEnvironment environment,
            ObjectMapper objectMapper,
            UnsubscribeTokenService unsubscribeTokenService) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.apiPublicUrl = apiPublicUrl;
        this.contextPath = contextPath;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.unsubscribeTokenService = unsubscribeTokenService;
    }

    /**
     * Returns true if SMTP is configured (a {@link JavaMailSender} bean exists, i.e.
     * {@code spring.mail.host} / {@code SMTP_HOST} is set).
     */
    public boolean isConfigured() {
        return mailSenderProvider.getIfAvailable() != null;
    }

    /**
     * Convenience overload for callers with no {@code userId} to unsubscribe by — pre-account
     * flows like {@code BrandEmailOtpService}'s OTP delivery and {@code
     * WorkspaceMemberService#sendInviteEmailDirect}'s not-yet-registered invitee, where there is no
     * {@code EmailPreference} row to link an unsubscribe link to anyway.
     */
    public boolean sendTemplateEmail(String toEmail, String templateKey, String templateDataJson) {
        return sendTemplateEmail(toEmail, templateKey, templateDataJson, null);
    }

    /**
     * Sends an email via MSG91's SMTP relay. Returns true on success, false on failure.
     *
     * <p>In dev, an unconfigured client logs and returns true (mock success) — unchanged local-dev
     * convenience so email-dependent flows don't need real SMTP credentials to exercise locally.
     *
     * <p>In every other environment, an unconfigured client returns false instead of silently
     * pretending to succeed (D4) — {@link EmailWorker} already does the right thing on {@code
     * false} (retry with backoff, then {@code FAILED} after 5 attempts) — no caller-side change
     * needed.
     *
     * @param toEmail recipient email address
     * @param templateKey notification template slug (e.g., "creator.proposal_received") — looked
     *     up in {@link EmailTemplateRegistry}; an unrecognized key still sends, falling back to a
     *     generic rendering of the raw key/variables
     * @param templateDataJson JSON string of merge fields substituted into the template copy
     * @param userId the recipient's account id ({@code EmailOutbox.userId}), or {@code null} for a
     *     pre-account recipient — controls whether an unsubscribe link is included at all (see
     *     {@link EmailTemplateRegistry}'s {@code NO_UNSUBSCRIBE_FOOTER} for the further,
     *     per-template-key exclusion of OTP/password-reset)
     */
    public boolean sendTemplateEmail(
            String toEmail, String templateKey, String templateDataJson, String userId) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            if (environment.isDev()) {
                // Dev/test mode - log and mock success
                log.info(
                        "[MOCK] Email would be sent: to={}, templateKey={}, data={}",
                        toEmail,
                        templateKey,
                        templateDataJson);
                return true;
            }
            log.error(
                    "SMTP is not configured (SMTP_HOST unset) outside dev — refusing to report success"
                            + " for: to={}, templateKey={}",
                    toEmail,
                    templateKey);
            return false;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> templateData =
                    objectMapper.readValue(templateDataJson, LinkedHashMap.class);
            String unsubscribeUrl = userId != null ? buildUnsubscribeUrl(userId, templateKey) : null;
            EmailTemplateRegistry.Rendered rendered =
                    EmailTemplateRegistry.render(templateKey, templateData, unsubscribeUrl);

            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true is required for the setText(plain, html) overload below (renders a
            // multipart/alternative message) -- the 2-arg constructor throws
            // "Not in multipart mode" the moment an HTML alternative is set.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(toEmail);
            helper.setSubject(rendered.subject());
            helper.setText(rendered.plainText(), rendered.html());
            mailSender.send(message);
            log.debug("SMTP email sent successfully: to={}, templateKey={}", toEmail, templateKey);
            return true;
        } catch (Exception e) {
            log.error("SMTP email error: to={}, templateKey={}, error={}", toEmail, templateKey, e.getMessage());
            return false;
        }
    }

    private String buildUnsubscribeUrl(String userId, String templateKey) {
        String token = unsubscribeTokenService.generateToken(userId, templateKey);
        String encodedToken;
        try {
            encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
        return apiPublicUrl + contextPath + "/notifications/unsubscribe-link?token=" + encodedToken;
    }
}
