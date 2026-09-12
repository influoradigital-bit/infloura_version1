package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.InfluoraEnvironment;
import com.influora.domain.entity.EmailOtpChallenge;
import com.influora.domain.entity.User;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOtpChallengeRepository;
import com.influora.repository.UserRepository;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.FestivalClientIpHasher;
import com.influora.security.JwtService;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpResponse;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandEmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(BrandEmailOtpService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 3;
    private static final long OTP_TTL_SECONDS = 300;
    private static final long OTP_RATE_LIMIT_WINDOW_SECONDS = 3600;

    /**
     * How long a COMPLETED verification stays usable, measured from the moment the CODE expired.
     *
     * <p>Deliberately longer than {@link #OTP_TTL_SECONDS} (which bounds how long the CODE itself
     * can be entered) and deliberately finite. Registration normally follows verification within
     * seconds, but not always: a register call that is refused for a duplicate phone sends the
     * visitor back to fix a field, and holding them to the 5-minute code TTL for that would strand
     * someone who has already proved they own the mailbox. Thirty minutes covers the retry without
     * leaving the proof valid indefinitely.
     */
    private static final long VERIFICATION_VALIDITY_SECONDS = 1800;

    /**
     * [F4] Per-ORIGIN cap on OTP emails actually sent, across ALL recipients, per hour.
     *
     * <p>The existing per-email cap bounds how often ONE address can be mailed; it does nothing
     * about one origin mailing a thousand different strangers, which is what makes an
     * unauthenticated send endpoint a mail cannon. AuthRateLimitFilter's "otp" bucket is per-IP but
     * caps REQUESTS over 60 seconds — 5/minute is 300/hour, i.e. 300 third-party mailboxes — and it
     * is in-memory and per-instance, so it resets on deploy and does not see a second pod. This cap
     * is DB-backed and counts deliveries, which is the thing that actually costs something.
     *
     * <p>Tiered exactly like the enquiry form's per-IP cap and for the same reason: a /48 can be a
     * whole campus or a carrier NAT pool, so reusing the /64 number there would let one abuser lock
     * out everyone in their organisation.
     */
    private static final long MAX_SENDS_PER_IP_PER_WINDOW = 8;

    private static final Duration IP_SEND_WINDOW = Duration.ofHours(1);

    /**
     * [F4, second pass] Ceiling on mints for ONE address across ALL origins, above the smaller
     * per-origin allowance ({@code otpSendPerEmailPerHour}). Larger than that allowance on purpose:
     * it exists to bound total mail to a mailbox, while the per-origin number is what a single
     * attacker can actually spend of someone else's budget.
     */
    private static final long MAX_SENDS_PER_EMAIL_GLOBAL_PER_WINDOW = 10;

    private final EmailOtpChallengeRepository otpRepository;
    private final UserRepository userRepository;
    private final InfluoraEnvironment environment;
    private final Msg91EmailClient msg91EmailClient;
    private final AbuseThrottleService abuseThrottleService;
    private final OtpEmailDispatcher otpEmailDispatcher;
    private final FestivalClientIpHasher clientIpHasher;

    @Value("${influora.auth.otp-length:6}")
    private int otpLength;

    @Value("${influora.auth.otp-send-per-email-per-hour:3}")
    private int otpSendPerEmailPerHour;

    @Value("${influora.msg91.email.template-id:otpman}")
    private String otpTemplateId;

    @Value("${influora.msg91.email.template-otp-variable:otp}")
    private String otpTemplateVariable;

    public BrandEmailOtpService(
            EmailOtpChallengeRepository otpRepository,
            UserRepository userRepository,
            InfluoraEnvironment environment,
            Msg91EmailClient msg91EmailClient,
            AbuseThrottleService abuseThrottleService,
            FestivalClientIpHasher clientIpHasher,
            OtpEmailDispatcher otpEmailDispatcher) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.environment = environment;
        this.msg91EmailClient = msg91EmailClient;
        this.abuseThrottleService = abuseThrottleService;
        this.clientIpHasher = clientIpHasher;
        this.otpEmailDispatcher = otpEmailDispatcher;
    }

    /**
     * Issues an email OTP challenge for signup <em>or</em> for an already-registered account whose
     * email is still unverified. Always returns the same success shape regardless of which case
     * applies (Kabir M-K6-C2-1) so callers cannot enumerate accounts.
     *
     * <p>F-0601 — delivery used to be gated on {@code !alreadyRegistered} alone, which made an
     * unverified account permanently unrecoverable: {@code AuthService.creatorLogin}/{@code
     * brandLogin} reject it with {@code EMAIL_NOT_VERIFIED} while the only endpoint that could
     * clear that state refused to send the code, because the account existed. That is reachable in
     * production whenever {@code require-email-otp-before-register} is off (the default) and
     * {@code require-email-verification} is on: {@code User.newCreator}/{@code newBrandOwner} stamp
     * {@code PENDING_VERIFICATION} unconditionally, so every such signup is bricked at its second
     * login. Delivery now covers the unverified-account case too. This adds no enumeration channel
     * — the HTTP response is byte-identical in all three branches, and the only new signal goes to
     * the mailbox that owns the address. A verified account still gets nothing: there is nothing
     * left to verify, and sending would turn this endpoint into an unauthenticated mail cannon.
     */
    @Transactional
    public SendEmailOtpResponse sendOtp(String email, String clientIp) {
        // [F2] First, and for every address alike - see enforceMailerConfigured().
        enforceMailerConfigured();
        // Read the row rather than existsByEmailIgnoreCase: "registered" alone can no longer decide
        // delivery, only "registered AND already verified" can.
        String normalized = normalizeEmail(email);
        SendEmailOtpResponse reused = reuseLiveChallenge(normalized);
        if (reused != null) {
            return reused;
        }
        // Rate limit FIRST, before the user lookup below. Pinned by
        // BrandEmailOtpServiceTest#testPerEmailRateLimitBlocksFourthSend, which verifies
        // findByEmailIgnoreCase is never reached once the cap is hit: a throttled caller must not
        // be able to make the server do a user lookup per request.
        enforcePerEmailSendRateLimit(normalized, clientIp);
        boolean alreadyVerified =
                userRepository
                        .findByEmailIgnoreCase(normalized)
                        .map(User::isEmailVerified)
                        .orElse(false);
        return issueChallenge(normalized, !alreadyVerified, clientIp);
    }

    /**
     * Same challenge, but delivery is UNCONDITIONAL — for a public form where the submitter may
     * well be an existing, already-verified customer.
     *
     * <p>{@link #sendOtp} must not be reused for that case. Its skip-if-verified branch is correct
     * for signup (a verified address has nothing left to verify, and mailing it would turn an
     * unauthenticated endpoint into a mail cannon aimed at real customers) and FATAL anywhere else:
     * an existing brand filling in the Festival Box enquiry form would get a cheerful "code sent",
     * no email, and no way forward — the same shape of dead end as F-0601, which cost this team a
     * production signup outage.
     *
     * <p>The tradeoff is deliberate and bounded: this DOES make a verified address reachable by an
     * anonymous caller, which {@link #sendOtp} deliberately prevents. The cap is the same 3 sends
     * per email per hour enforced below, and the alternative — dead-ending every existing customer
     * who fills in the form — is worse. Do not widen this to the signup path to "make them
     * consistent"; the asymmetry is the point.
     */
    @Transactional
    public SendEmailOtpResponse sendOtpForPublicForm(String email, String clientIp) {
        enforceMailerConfigured();
        String normalized = normalizeEmail(email);
        SendEmailOtpResponse reused = reuseLiveChallenge(normalized);
        if (reused != null) {
            return reused;
        }
        enforcePerEmailSendRateLimit(normalized, clientIp);
        return issueChallenge(normalized, true, clientIp);
    }

    /**
     * [F4] If an unexpired, unverified challenge already exists for this address, re-deliver THAT
     * code instead of minting another, and do not charge the per-email mint cap.
     *
     * <p>This is what defuses the cheap version of the targeted denial of service. Before it, three
     * unauthenticated POSTs naming someone else's address consumed that person's entire hourly mint
     * budget, after which their own signup answered 429 for the rest of the hour — no account, no
     * knowledge beyond the address, indistinguishable in the logs from the victim retrying. Now the
     * attacker's repeat requests inside a live window mint nothing and cost the victim nothing.
     *
     * <p>It also fixes a real UX bug in passing: "resend" used to mint a SECOND valid code, so a
     * visitor who clicked it while the first email was in flight could receive two different codes
     * and have the older one silently stop working — verifyOtp only ever reads the NEWEST row, so
     * the code in the email they opened first was the one that no longer worked.
     *
     * <p>Nothing is re-sent, deliberately: {@code otpHash} is one-way, so the live code cannot be
     * recovered to mail it again, and minting a fresh one is the behaviour this method exists to
     * avoid. The visitor already has that code in their inbox — the response carries how long it
     * remains valid so the UI can say so. The cost is that a genuinely undelivered first email
     * cannot be retried for up to {@code OTP_TTL_SECONDS}; that is the deliberate price of making
     * a stranger unable to spend someone else's budget, and five minutes beats an hour.
     *
     * <p>HONEST LIMIT — this raises the cost of the per-address lockout, it does not remove it. An
     * attacker who spaces requests past the 5-minute code TTL still mints, and can still burn the
     * three hourly mints in about fifteen minutes. Closing that completely is not possible while
     * the endpoint is anonymous: nothing distinguishes the address's owner from anyone else typing
     * it. If this is ever actually exploited the answer is a CAPTCHA or proof-of-work in front of
     * the send, not a smaller number here.
     *
     * @return the response to return as-is, or {@code null} when there is nothing to reuse
     */
    private SendEmailOtpResponse reuseLiveChallenge(String normalized) {
        EmailOtpChallenge live =
                otpRepository
                        .findNewestForEmail(normalized)
                        .filter(c -> !c.isVerified())
                        .filter(c -> c.getExpiresAt().isAfter(Instant.now()))
                        .orElse(null);
        if (live == null) {
            return null;
        }

        long remaining = Math.max(0, Instant.now().until(live.getExpiresAt(), ChronoUnit.SECONDS));
        // Same envelope as a real send — the caller cannot tell the two apart, which also keeps
        // this from becoming a probe for "does a live challenge exist for this address".
        return new SendEmailOtpResponse("OTP sent successfully", remaining, maskEmail(normalized));
    }

    /**
     * Mint and optionally deliver one challenge. Callers MUST have normalized the address and
     * already applied {@link #enforcePerEmailSendRateLimit} — the cap is deliberately not applied
     * here so each caller can run it before whatever lookups it does, rather than after.
     */
    private SendEmailOtpResponse issueChallenge(
            String normalized, boolean deliver, String clientIp) {
        if (deliver) {
            enforcePerIpSendCap(clientIp);
        }
        String otp = generateOtp();
        otpRepository.save(
                EmailOtpChallenge.create(
                        Ulids.newUlid(),
                        normalized,
                        JwtService.hashToken(otp),
                        Instant.now().plusSeconds(OTP_TTL_SECONDS)));

        if (deliver) {
            deliverOtp(normalized, otp);
        }

        // Never branch the HTTP response on who the address belongs to.
        return new SendEmailOtpResponse(
                "OTP sent successfully",
                OTP_TTL_SECONDS,
                maskEmail(normalized));
    }

    /**
     * [F4] Bound how many OTP emails one origin can cause per hour, across every recipient.
     *
     * <p>Only applied when we are actually going to deliver — a suppressed send (an already-verified
     * address on the signup path) costs nothing to send, so charging for it would let one caller
     * exhaust their own budget probing addresses without a single email leaving the building.
     *
     * <p>Tier caps mirror FestivalEnquiryService#enforceThrottle: the narrowest prefix gets the base
     * number and the coarser ones a multiple, so a shared /48 is not locked by one abuser inside it.
     */
    private void enforcePerIpSendCap(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            // No address to key on. Fail OPEN rather than refusing every send: this is an abuse
            // bound, not an authorization check, and the per-email cap plus the edge filter still
            // apply. Reaching here means the servlet container gave us no remote address, which is
            // not a state a caller can force.
            log.warn("OTP send with no resolvable client address - per-IP cap skipped");
            return;
        }
        List<String> tiers = clientIpHasher.hashTiers(clientIp);
        long[] capByTier = {
            MAX_SENDS_PER_IP_PER_WINDOW, MAX_SENDS_PER_IP_PER_WINDOW * 4, MAX_SENDS_PER_IP_PER_WINDOW * 8
        };
        for (int i = 0; i < tiers.size(); i++) {
            long cap = capByTier[Math.min(i, capByTier.length - 1)];
            if (!abuseThrottleService.tryConsume("otp-send-ip:" + tiers.get(i), IP_SEND_WINDOW, cap)) {
                // Tier index only, never the hash - pairing an origin with a timestamp in the log
                // rebuilds the visitor trail the hashing exists to prevent.
                log.warn("OTP send throttled: per-IP cap reached at prefix tier {}", i);
                throw new ApiException(
                        "RATE_LIMITED",
                        "Too many verification codes requested. Please try again later.",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
        }
    }

    /**
     * Dev: log-only (never call MSG91). Non-dev: send via existing {@link Msg91EmailClient}
     * (V-GA-6) — no second MSG91 client.
     */
    /**
     * [F2] Hands the send to {@link OtpEmailDispatcher} and returns immediately.
     *
     * <p>This method no longer throws. It used to answer a failed or unconfigured send with 503,
     * and because it is only REACHED on the deliver branch, that status was itself an enumeration
     * oracle: during any mail outage a verified address (delivery skipped) answered 200 while a new
     * one answered 503, with no timing analysis needed. Mailer availability is now checked by
     * {@link #enforceMailerConfigured()} on every send regardless of branch, and a per-message
     * failure is logged inside the dispatcher instead of shaping the response.
     */
    private void deliverOtp(String normalizedEmail, String otp) {
        if (environment.isDev()) {
            log.info("[dev] Brand email OTP for {}: {}", normalizedEmail, otp);
            return;
        }

        // OTP is digits-only from generateOtp — safe to embed in JSON without escaping.
        String templateData =
                "{\"" + otpTemplateVariable + "\":\"" + otp + "\"}";
        try {
            otpEmailDispatcher.deliver(
                    normalizedEmail, otpTemplateId, templateData, maskEmail(normalizedEmail));
        } catch (TaskRejectedException rejected) {
            // The OTP pool uses AbortPolicy (OtpEmailExecutorConfig explains why CallerRunsPolicy
            // would be a bug), and rejection surfaces HERE, on the request thread. It must not
            // become the response: a 5xx only when the queue is full would be one more thing the
            // caller can measure, and the queue fills under exactly the load an attacker creates.
            log.error("OTP email rejected - delivery pool saturated for {}", maskEmail(normalizedEmail));
        }
    }

    /**
     * [F2] Refuse when there is no mailer at all — for EVERY caller, before any branch that depends
     * on who the address belongs to.
     *
     * <p>Unconditional is the whole point. The old check sat inside the delivery branch, so it could
     * only ever fire for an address we were going to mail; an already-verified address sailed past
     * it to a 200. Running it here keeps a genuinely dead mailer loud (registration must not
     * silently accept codes nobody will receive — the F-0390 A2 failure shape) while making the
     * answer identical for every address.
     *
     * <p>Constant time: {@code isConfigured()} is a bean-presence lookup, no I/O.
     */
    private void enforceMailerConfigured() {
        if (environment.isDev() || msg91EmailClient.isConfigured()) {
            return;
        }
        log.error("MSG91 email not configured — refusing OTP send");
        throw new ApiException(
                "EMAIL_DELIVERY_FAILED",
                "Unable to send verification email. Try again later.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * {@code noRollbackFor = ApiException.class} is load-bearing, not defensive styling. Without
     * it the 3-attempt lockout below is 100% inert in production: the wrong-code path increments
     * {@code attempts}, saves, and then throws {@code INVALID_OTP} — and {@link ApiException}
     * extends {@link RuntimeException}, which is Spring's default rollback trigger. The very
     * exception that reports the failed guess also erases the record of it, so the next call
     * re-reads {@code attempts = 0} and the {@code >= MAX_ATTEMPTS} guard can never fire. Same
     * shape as the REQUIRED-propagation throttle whose increment is undone when the caller
     * refuses by throwing.
     *
     * <p>Deliberately NOT fixed with a {@code REQUIRES_NEW} helper. The challenge is a managed
     * entity in THIS persistence context; incrementing it in a nested transaction would leave the
     * outer context holding a stale copy that dirty-checking can write back, turning one bug into
     * a double-increment. And a {@code REQUIRES_NEW} method called from inside this class would be
     * a self-invocation, which Spring silently ignores — the fix would look applied and do nothing.
     *
     * <p>Safe for every throw site here, each of which was enumerated before choosing this: the
     * three early refusals (no challenge / OTP_EXPIRED / TOO_MANY_ATTEMPTS) modify nothing, so
     * committing instead of rolling back persists nothing; the wrong-code path modifies exactly
     * the counter this annotation exists to keep. A non-ApiException still rolls back normally.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifyEmailOtpResponse verifyOtp(String email, String otp) {
        String normalized = normalizeEmail(email);
        EmailOtpChallenge challenge =
                otpRepository
                        .findNewestForEmail(normalized)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_OTP", "Invalid or expired OTP", HttpStatus.BAD_REQUEST));

        if (challenge.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException("OTP_EXPIRED", "OTP has expired", HttpStatus.GONE);
        }

        if (challenge.getAttempts() >= MAX_ATTEMPTS) {
            throw new ApiException(
                    "TOO_MANY_ATTEMPTS", "Too many attempts. Request a new code.", HttpStatus.TOO_MANY_REQUESTS);
        }

        challenge.incrementAttempts();
        if (!JwtService.hashToken(otp).equals(challenge.getOtpHash())) {
            otpRepository.save(challenge);
            throw new ApiException("INVALID_OTP", "Invalid OTP", HttpStatus.BAD_REQUEST);
        }

        challenge.setVerified(true);
        otpRepository.save(challenge);

        // F-0601 — the challenge row was the ONLY thing this ever wrote. That is sufficient for the
        // pre-register flow (AuthService reads it back through requireVerifiedEmail before the User
        // row exists), but it left no path at all for an account that already exists: users.status
        // stayed PENDING_VERIFICATION and users.email_verified stayed false forever, so the login
        // gate kept rejecting an account whose owner had just proved they hold the mailbox.
        // User#setEmailVerified promotes PENDING_VERIFICATION -> ACTIVE itself (User.java:280), so
        // the two columns cannot drift apart here. Deliberately a no-op for an already-verified
        // account and for an address with no account yet (register consumes the challenge instead).
        userRepository
                .findByEmailIgnoreCase(normalized)
                .filter(u -> !u.isEmailVerified())
                .ifPresent(
                        u -> {
                            u.setEmailVerified(true);
                            userRepository.save(u);
                            log.info("Email verified via OTP for existing account {}", maskEmail(normalized));
                        });

        return new VerifyEmailOtpResponse(true, "Email verified successfully");
    }

    /**
     * Assert the address has a live, completed verification — and SPEND it.
     *
     * <p>Named for both halves on purpose. The previous {@code requireVerifiedEmail} only read the
     * flag: it never consumed anything and never checked age, so a challenge verified months ago
     * still satisfied registration today, and {@code clearVerifiedForEmail} — the query written to
     * invalidate a spent verification — sat in the repository with ZERO callers. Folding the clear
     * into the same method is what stops that recurring: a caller cannot forget the second step of
     * a one-step call, and renaming forces every existing call site to be revisited rather than
     * silently keeping the old behaviour.
     *
     * <p>Both halves run inside the CALLER's transaction (brandRegister/creatorRegister are
     * {@code @Transactional}), which is what makes spending it safe: if registration then fails —
     * a duplicate phone, a raced email — the rollback restores the verification along with
     * everything else, so the visitor can correct the field and retry without a fresh code. It is
     * spent only if an account is actually created.
     */
    public void requireAndConsumeVerifiedEmail(String email) {
        String normalized = normalizeEmail(email);
        Instant usableSince = Instant.now().minusSeconds(VERIFICATION_VALIDITY_SECONDS);
        // [F6] "Is there ANY live verified challenge for this address", NOT "is the newest one
        // verified". The latter let a stranger revoke a completed verification just by requesting a
        // fresh code: the new unverified row became the newest, and the real owner's registration
        // started failing EMAIL_NOT_VERIFIED. Note the F4 reuse path does NOT prevent this on its
        // own -- it declines to mint only while a live UNVERIFIED challenge exists, and once the
        // owner has verified, that filter no longer matches, so the mint goes ahead and shadows it.
        //
        // Anchored on expiresAt because the entity exposes no createdAt accessor and there is no
        // verifiedAt column. expiresAt is createdAt + OTP_TTL_SECONDS (see create()), and a
        // challenge can only be verified BEFORE that instant, so the real window is 30-35 minutes,
        // never less than 30. Erring long is the right direction: the bound exists to stop a
        // months-old proof being replayed, not to shave minutes off a legitimate retry.
        boolean verified =
                otpRepository.existsByEmailAndVerifiedTrueAndExpiresAtAfter(normalized, usableSince);
        if (!verified) {
            throw new ApiException(
                    "EMAIL_NOT_VERIFIED",
                    "Please verify your email with the OTP before continuing",
                    HttpStatus.FORBIDDEN);
        }
        // Spend every verified challenge for this address, not just the newest — otherwise an
        // older verified row left behind by a re-send would still satisfy the next call.
        otpRepository.clearVerifiedForEmail(normalized);
    }

    /**
     * [F4, second pass] Two caps, not one, and the split is the point.
     *
     * <p>A single per-address cap is unavoidably a denial-of-service primitive on an anonymous
     * endpoint: every request that names an address spends that address's budget, and nothing
     * distinguishes its owner from anyone else typing it. Three POSTs locked a named person out of
     * their own signup for an hour. The live-challenge reuse added earlier removes the cheap
     * version of that (rapid repeats inside one 5-minute window now mint nothing), but an attacker
     * spacing requests past the code TTL could still spend all three.
     *
     * <p>So the per-address budget is now split by ORIGIN as well. Each origin gets its own small
     * allowance for a given address, and the address keeps a larger global ceiling on top. The
     * victim, arriving from their own network, meets an allowance the attacker never touched. A
     * legitimate user sees no change: their own cap is the same small number it always was.
     *
     * <p><b>Still not a cure, and deliberately documented as such.</b> An attacker with several
     * distinct origins can spend the global ceiling and reproduce the lockout. This raises the cost
     * from three clicks on a laptop to needing a proxy pool, which is the difference that matters
     * for the realistic threat here. Actually closing it needs a CAPTCHA or proof-of-work in front
     * of the send — see the note on {@link #reuseLiveChallenge}. Do not respond to an incident by
     * lowering these numbers; that makes the DoS cheaper, not dearer.
     */
    private void enforcePerEmailSendRateLimit(String normalizedEmail, String clientIp) {
        // Per address PER ORIGIN. Keyed on a hash of the address so the counter table never holds
        // a plaintext email, matching how the enquiry throttle keys its own per-email counter.
        String emailKey = JwtService.hashToken(normalizedEmail);
        List<String> tiers =
                (clientIp == null || clientIp.isBlank())
                        ? List.of()
                        : clientIpHasher.hashTiers(clientIp);
        if (!tiers.isEmpty()
                && !abuseThrottleService.tryConsume(
                        "otp-email-origin:" + emailKey + ":" + tiers.get(0),
                        IP_SEND_WINDOW,
                        otpSendPerEmailPerHour)) {
            throw new ApiException(
                    "RATE_LIMITED",
                    "Too many OTP requests for this email. Try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        // Global ceiling for the address, across every origin. Bounds total mail to one mailbox.
        Instant windowStart = Instant.now().minusSeconds(OTP_RATE_LIMIT_WINDOW_SECONDS);
        long sentInWindow = otpRepository.countByEmailAndCreatedAtAfter(normalizedEmail, windowStart);
        if (sentInWindow >= MAX_SENDS_PER_EMAIL_GLOBAL_PER_WINDOW) {
            throw new ApiException(
                    "RATE_LIMITED",
                    "Too many OTP requests for this email. Try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private String generateOtp() {
        int bound = (int) Math.pow(10, otpLength);
        // Full [0, bound), NOT [bound/10, bound). The old lower bound excluded every code with a
        // leading zero, cutting a 6-digit keyspace from 1,000,000 to 900,000 and making the first
        // digit non-uniform (never 0) — for no benefit, since the format below zero-pads anyway.
        int code = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + otpLength + "d", code);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.substring(at);
    }
}
