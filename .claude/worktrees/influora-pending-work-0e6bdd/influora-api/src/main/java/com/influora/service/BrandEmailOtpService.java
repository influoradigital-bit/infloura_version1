package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.EmailOtpChallenge;
import com.influora.repository.EmailOtpChallengeRepository;
import com.influora.repository.UserRepository;
import com.influora.security.JwtService;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpResponse;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpResponse;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandEmailOtpService {

    private static final Logger log = LoggerFactory.getLogger(BrandEmailOtpService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long OTP_TTL_SECONDS = 300;

    private final EmailOtpChallengeRepository otpRepository;
    private final UserRepository userRepository;

    @Value("${influora.auth.otp-length:6}")
    private int otpLength;

    public BrandEmailOtpService(EmailOtpChallengeRepository otpRepository, UserRepository userRepository) {
        this.otpRepository = otpRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public SendEmailOtpResponse sendOtp(String email) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new ApiException("EMAIL_ALREADY_EXISTS", "An account with this email already exists", HttpStatus.CONFLICT);
        }

        String otp = generateOtp();
        otpRepository.save(
                EmailOtpChallenge.create(
                        Ulids.newUlid(),
                        normalized,
                        JwtService.hashToken(otp),
                        Instant.now().plusSeconds(OTP_TTL_SECONDS)));

        log.info("[dev] Brand email OTP for {}: {}", normalized, otp);
        // TODO: MSG91 Email API (docs/MSG91-EMAIL-OTP.md)

        return new SendEmailOtpResponse(
                "OTP sent successfully",
                OTP_TTL_SECONDS,
                maskEmail(normalized));
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

    private String generateOtp() {
        int bound = (int) Math.pow(10, otpLength);
        int code = ThreadLocalRandom.current().nextInt(bound / 10, bound);
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
