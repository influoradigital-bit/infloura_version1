package com.influora.service;

import com.influora.integration.msg91.Msg91EmailClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * [F2] Sends the OTP email OFF the request thread.
 *
 * <p><b>Why this is its own bean.</b> {@code @Async} is proxy-based, exactly like
 * {@code @Transactional}: a method called from inside its own class bypasses the proxy and runs
 * synchronously, with no warning and no error. Putting {@code deliver} on
 * {@link BrandEmailOtpService} itself and calling it from {@code sendOtp} would therefore have
 * looked like a fix and changed nothing at all. {@code @EnableAsync} is already on
 * {@code InfluoraApiApplication} (see the comment there, which records the same trap).
 *
 * <p><b>What it fixes.</b> {@code Msg91EmailClient.sendTemplateEmail} performs a blocking SMTP
 * transaction — connect, implicit-TLS handshake on 465, MAIL/RCPT/DATA — taking hundreds of
 * milliseconds to seconds. The signup send path skips that entirely for an address that already
 * belongs to a verified account, so response time answered "does this person have an account?" in
 * a single request: fast meant yes. Moving every send off the request thread makes both branches
 * return after the same one SELECT and one INSERT, so there is no longer a duration to compare.
 *
 * <p><b>Failures stay here.</b> A delivery failure must never reach the caller: making the HTTP
 * status depend on whether we attempted a send re-opens the same oracle in a cruder form (200 =
 * verified account exists, 503 = it does not) during any mail outage. An {@code @Async void}
 * method's exception is otherwise swallowed by the executor's uncaught handler, so this catches
 * explicitly and logs — a silent send failure with nothing in the log would be worse than the bug.
 *
 * <p>Whether the mailer is configured at all is checked SYNCHRONOUSLY and unconditionally by the
 * caller before it ever gets here, so a genuinely dead mailer is still a loud 503 for everyone
 * rather than an OTP that queues into the void.
 */
@Component
public class OtpEmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OtpEmailDispatcher.class);

    private final Msg91EmailClient msg91EmailClient;

    public OtpEmailDispatcher(Msg91EmailClient msg91EmailClient) {
        this.msg91EmailClient = msg91EmailClient;
    }

    /**
     * @param maskedEmail already masked by the caller — the full address must not reach a log line
     *     here any more than it does anywhere else in the OTP path.
     */
    /** Bean name of the dedicated pool below - referenced by the {@code @Async} qualifier. */
    public static final String EXECUTOR = "otpEmailExecutor";

    @Async(EXECUTOR)
    public void deliver(
            String toEmail, String templateId, String templateDataJson, String maskedEmail) {
        try {
            if (!msg91EmailClient.sendTemplateEmail(toEmail, templateId, templateDataJson)) {
                log.error("MSG91 OTP delivery failed for {}", maskedEmail);
            }
        } catch (RuntimeException e) {
            // Deliberately swallowed after logging: nothing downstream can act on it, and letting
            // it escape an @Async void method only routes it to the uncaught handler.
            log.error("MSG91 OTP delivery threw for {}: {}", maskedEmail, e.toString());
        }
    }
}
