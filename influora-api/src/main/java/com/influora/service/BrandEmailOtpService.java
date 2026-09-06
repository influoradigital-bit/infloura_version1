package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.InfluoraEnvironment;
import com.influora.domain.entity.EmailOtpChallenge;
import com.influora.domain.entity.User;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOtpChallengeRepository;
import com.influora.repository.UserRepository;
import com.influora.security.JwtService;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpResponse;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final EmailOtpChallengeRepository otpRepository;
    private final UserRepository userRepository;
    private final InfluoraEnvironment environment;
    private final Msg91EmailClient msg91EmailClient;

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
            Msg91EmailClient msg91EmailClient) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
        this.environment = environment;
        this.msg91EmailClient = msg91EmailClient;
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
    public SendEmailOtpResponse sendOtp(String email) {
        String normalized = normalizeEmail(email);
        enforcePerEmailSendRateLimit(normalized);

        // Read the row rather than existsByEmailIgnoreCase: "registered" alone can no longer decide
        // delivery, only "registered AND already verified" can.
        boolean alreadyVerified =
                userRepository
                        .findByEmailIgnoreCase(normalized)
                        .map(User::isEmailVerified)
                        .orElse(false);

        String otp = generateOtp();
        otpRepository.save(
                EmailOtpChallenge.create(
                        Ulids.newUlid(),
                        normalized,
                        JwtService.hashToken(otp),
                        Instant.now().plusSeconds(OTP_TTL_SECONDS)));

        // Deliver unless the address is already verified — never branch the HTTP response.
        if (!alreadyVerified) {
            deliverOtp(normalized, otp);
        }

        return new SendEmailOtpResponse(
                "OTP sent successfully",
                OTP_TTL_SECONDS,
                maskEmail(normalized));
    }

    /**
     * Dev: log-only (never call MSG91). Non-dev: send via existing {@link Msg91EmailClient}
     * (V-GA-6) — no second MSG91 client.
     */
    private void deliverOtp(String normalizedEmail, String otp) {
        if (environment.isDev()) {
            log.info("[dev] Brand email OTP for {}: {}", normalizedEmail, otp);
            return;
        }

        if (!msg91EmailClient.isConfigured()) {
            log.error("MSG91 email not configured — cannot deliver OTP to {}", maskEmail(normalizedEmail));
            throw new ApiException(
                    "EMAIL_DELIVERY_FAILED",
                    "Unable to send verification email. Try again later.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        // OTP is digits-only from generateOtp — safe to embed in JSON without escaping.
        String templateData =
                "{\"" + otpTemplateVariable + "\":\"" + otp + "\"}";
        boolean sent = msg91EmailClient.sendTemplateEmail(normalizedEmail, otpTemplateId, templateData);
        if (!sent) {
            log.error("MSG91 OTP delivery failed for {}", maskEmail(normalizedEmail));
            throw new ApiException(
                    "EMAIL_DELIVERY_FAILED",
                    "Unable to send verification email. Try again later.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Transactional
    public VerifyEmailOtpResponse verifyOtp(String email, String otp) {
        String normalized = normalizeEmail(email);
        EmailOtpChallenge challenge =
                otpRepository
                        .findFirstByEmailOrderByCreatedAtDesc(normalized)
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

    public void requireVerifiedEmail(String email) {
        String normalized = normalizeEmail(email);
        boolean verified =
                otpRepository
                        .findFirstByEmailOrderByCreatedAtDesc(normalized)
                        .map(EmailOtpChallenge::isVerified)
                        .orElse(false);
        if (!verified) {
            throw new ApiException(
                    "EMAIL_NOT_VERIFIED",
                    "Please verify your email with the OTP before continuing",
                    HttpStatus.FORBIDDEN);
        }
    }

    private void enforcePerEmailSendRateLimit(String normalizedEmail) {
        Instant windowStart = Instant.now().minusSeconds(OTP_RATE_LIMIT_WINDOW_SECONDS);
        long sentInWindow = otpRepository.countByEmailAndCreatedAtAfter(normalizedEmail, windowStart);
        if (sentInWindow >= otpSendPerEmailPerHour) {
            throw new ApiException(
                    "RATE_LIMITED",
                    "Too many OTP requests for this email. Try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private String generateOtp() {
        int bound = (int) Math.pow(10, otpLength);
        int code = SECURE_RANDOM.nextInt(bound / 10, bound);
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
