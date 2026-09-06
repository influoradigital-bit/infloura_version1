package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.InfluoraEnvironment;
import com.influora.domain.entity.EmailOtpChallenge;
import com.influora.domain.entity.User;
import com.influora.domain.enums.UserStatus;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOtpChallengeRepository;
import com.influora.repository.UserRepository;
import com.influora.security.JwtService;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpResponse;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpResponse;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Kabir H1/M2/M3 — OTP secret logging gate, SecureRandom generation, per-email send cap. V-GA-6
 * MSG91. G-Kv3-1 — verify / lockout / requireVerifiedEmail paths for creator+brand OTP signup.
 */
@ExtendWith(MockitoExtension.class)
class BrandEmailOtpServiceTest {

    private static final String EMAIL = "creator@example.com";
    private static final String OTP = "123456";

    @Mock private EmailOtpChallengeRepository otpRepository;
    @Mock private UserRepository userRepository;
    @Mock private InfluoraEnvironment environment;
    @Mock private Msg91EmailClient msg91EmailClient;

    private BrandEmailOtpService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new BrandEmailOtpService(otpRepository, userRepository, environment, msg91EmailClient);
        setField("otpLength", 6);
        setField("otpSendPerEmailPerHour", 3);
        setField("otpTemplateId", "otpman");
        setField("otpTemplateVariable", "otp");
    }

    private User unverifiedUser() {
        return User.newCreator("01HUSER00000000000000000A", EMAIL, "hash", "Priya", "Ingle", "Priya Ingle");
    }

    private User verifiedUser() {
        User u = unverifiedUser();
        u.setEmailVerified(true);
        return u;
    }

    private EmailOtpChallenge challengeWithHash(String otp, Instant expiresAt) {
        return EmailOtpChallenge.create(
                "01HOTPCHALLENGE123456789A", EMAIL, JwtService.hashToken(otp), expiresAt);
    }

    @Test
    @DisplayName("sendOtp: fourth send within one hour returns 429 RATE_LIMITED")
    void testPerEmailRateLimitBlocksFourthSend() {
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(3L);

        ApiException ex = assertThrows(ApiException.class, () -> service.sendOtp(EMAIL));

        assertEquals("RATE_LIMITED", ex.getCode());
        assertEquals(429, ex.getStatus().value());
        verify(otpRepository, never()).save(any());
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(msg91EmailClient, never()).sendTemplateEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendOtp: under the per-email cap persists a hashed challenge and returns masked email")
    void testSendOtpSucceedsUnderRateLimit() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(2L);
        when(environment.isDev()).thenReturn(false);
        when(msg91EmailClient.isConfigured()).thenReturn(true);
        when(msg91EmailClient.sendTemplateEmail(eq(EMAIL), eq("otpman"), anyString())).thenReturn(true);

        SendEmailOtpResponse response = service.sendOtp(EMAIL);

        assertEquals("OTP sent successfully", response.message());
        assertEquals(300L, response.expiresIn());
        assertEquals("c***@example.com", response.maskedEmail());

        ArgumentCaptor<EmailOtpChallenge> captor = ArgumentCaptor.forClass(EmailOtpChallenge.class);
        verify(otpRepository).save(captor.capture());
        EmailOtpChallenge saved = captor.getValue();
        assertEquals(EMAIL, saved.getEmail());
        assertEquals(64, saved.getOtpHash().length());
        verify(msg91EmailClient).sendTemplateEmail(eq(EMAIL), eq("otpman"), anyString());
    }

    @Test
    @DisplayName("sendOtp V-GA-6: dev mode logs only — never calls MSG91")
    void testSendOtpDevModeSkipsMsg91() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);
        when(environment.isDev()).thenReturn(true);

        SendEmailOtpResponse response = service.sendOtp(EMAIL);

        assertEquals("OTP sent successfully", response.message());
        verify(otpRepository).save(any(EmailOtpChallenge.class));
        verify(msg91EmailClient, never()).isConfigured();
        verify(msg91EmailClient, never()).sendTemplateEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendOtp V-GA-6: non-dev MSG91 failure returns 503 EMAIL_DELIVERY_FAILED")
    void testSendOtpMsg91Failure() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);
        when(environment.isDev()).thenReturn(false);
        when(msg91EmailClient.isConfigured()).thenReturn(true);
        when(msg91EmailClient.sendTemplateEmail(eq(EMAIL), eq("otpman"), anyString())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> service.sendOtp(EMAIL));

        assertEquals("EMAIL_DELIVERY_FAILED", ex.getCode());
        assertEquals(503, ex.getStatus().value());
    }

    @Test
    @DisplayName("sendOtp M-K6-C2-1: registered email returns identical success shape (no 409 oracle)")
    void testSendOtpRegisteredEmailUniformSuccess() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verifiedUser()));
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);

        SendEmailOtpResponse response = service.sendOtp(EMAIL);

        assertEquals("OTP sent successfully", response.message());
        assertEquals(300L, response.expiresIn());
        assertEquals("c***@example.com", response.maskedEmail());
        verify(otpRepository).save(any(EmailOtpChallenge.class));
        verify(msg91EmailClient, never()).sendTemplateEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendOtp M-K6-C2-1: unregistered vs registered responses are shape-identical")
    void testSendOtpRegisteredAndUnregisteredIdenticalShapes() {
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(1L);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(environment.isDev()).thenReturn(true);
        SendEmailOtpResponse unregistered = service.sendOtp(EMAIL);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verifiedUser()));
        SendEmailOtpResponse registered = service.sendOtp(EMAIL);

        assertEquals(unregistered.message(), registered.message());
        assertEquals(unregistered.expiresIn(), registered.expiresIn());
        assertEquals(unregistered.maskedEmail(), registered.maskedEmail());
    }

    @Test
    @DisplayName("sendOtp G-Kv3-1: persisted challenge stores SHA-256 hash, never plaintext OTP")
    void testSendOtpHashesBeforeStorage() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);
        when(environment.isDev()).thenReturn(true);

        service.sendOtp(EMAIL);

        ArgumentCaptor<EmailOtpChallenge> captor = ArgumentCaptor.forClass(EmailOtpChallenge.class);
        verify(otpRepository).save(captor.capture());
        EmailOtpChallenge saved = captor.getValue();
        assertEquals(64, saved.getOtpHash().length());
        assertNotEquals(OTP, saved.getOtpHash());
        assertFalse(saved.isVerified());
        assertEquals(0, saved.getAttempts());
    }

    @Test
    @DisplayName("verifyOtp G-Kv3-1: correct OTP marks challenge verified")
    void testVerifyCorrectOtp() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        VerifyEmailOtpResponse response = service.verifyOtp(EMAIL, OTP);

        assertTrue(response.emailVerified());
        assertEquals("Email verified successfully", response.message());
        assertTrue(challenge.isVerified());
        assertEquals(1, challenge.getAttempts());
        verify(otpRepository).save(challenge);
    }

    @Test
    @DisplayName("verifyOtp G-Kv3-1: wrong OTP returns INVALID_OTP and increments attempts")
    void testRejectWrongOtp() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));

        ApiException ex = assertThrows(ApiException.class, () -> service.verifyOtp(EMAIL, "999999"));

        assertEquals("INVALID_OTP", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        assertEquals(1, challenge.getAttempts());
        assertFalse(challenge.isVerified());
        verify(otpRepository).save(challenge);
    }

    @Test
    @DisplayName("verifyOtp G-Kv3-1: expired challenge returns OTP_EXPIRED 410")
    void testRejectExpiredOtp() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().minusSeconds(1));
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));

        ApiException ex = assertThrows(ApiException.class, () -> service.verifyOtp(EMAIL, OTP));

        assertEquals("OTP_EXPIRED", ex.getCode());
        assertEquals(410, ex.getStatus().value());
        verify(otpRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifyOtp G-Kv3-1: missing challenge returns INVALID_OTP")
    void testRejectMissingChallenge() {
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.verifyOtp(EMAIL, OTP));

        assertEquals("INVALID_OTP", ex.getCode());
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    @DisplayName("verifyOtp G-Kv3-1: locks after 3 failed attempts (4th call → TOO_MANY_ATTEMPTS 429)")
    void testLockAfterThreeFailedAttempts() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));

        for (int i = 0; i < 3; i++) {
            ApiException wrong =
                    assertThrows(ApiException.class, () -> service.verifyOtp(EMAIL, "000000"));
            assertEquals("INVALID_OTP", wrong.getCode());
        }
        assertEquals(3, challenge.getAttempts());

        ApiException locked =
                assertThrows(ApiException.class, () -> service.verifyOtp(EMAIL, OTP));

        assertEquals("TOO_MANY_ATTEMPTS", locked.getCode());
        assertEquals(429, locked.getStatus().value());
        // Correct OTP after lock must not verify
        assertFalse(challenge.isVerified());
        verify(otpRepository, times(3)).save(challenge);
    }

    @Test
    @DisplayName("requireVerifiedEmail G-Kv3-1: throws EMAIL_NOT_VERIFIED when latest challenge unverified")
    void testRequireVerifiedEmailRejectsUnverified() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));

        ApiException ex = assertThrows(ApiException.class, () -> service.requireVerifiedEmail(EMAIL));

        assertEquals("EMAIL_NOT_VERIFIED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName("requireVerifiedEmail G-Kv3-1: passes when latest challenge is verified")
    void testRequireVerifiedEmailPassesWhenVerified() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        challenge.setVerified(true);
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));

        service.requireVerifiedEmail(EMAIL);
    }


    // ---- F-0601: the unverified-account recovery path -------------------------------------------
    // Before this, an account created while require-email-otp-before-register was off (the default,
    // and what production ran) was unrecoverable: PENDING_VERIFICATION blocked every login, sendOtp
    // refused to deliver because the account existed, and verifyOtp only ever wrote the challenge
    // row. These four pin the three halves of that trap shut.

    @Test
    @DisplayName("sendOtp F-0601: registered-but-UNVERIFIED account does get the code delivered")
    void testSendOtpDeliversToRegisteredUnverifiedAccount() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(unverifiedUser()));
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);
        when(msg91EmailClient.isConfigured()).thenReturn(true);
        when(msg91EmailClient.sendTemplateEmail(eq(EMAIL), eq("otpman"), anyString())).thenReturn(true);

        SendEmailOtpResponse response = service.sendOtp(EMAIL);

        assertEquals("OTP sent successfully", response.message());
        verify(otpRepository).save(any(EmailOtpChallenge.class));
        // The assertion that matters: under the old !alreadyRegistered gate this was never(...).
        verify(msg91EmailClient).sendTemplateEmail(eq(EMAIL), eq("otpman"), anyString());
    }

    @Test
    @DisplayName("sendOtp F-0601: unverified-account send is shape-identical to the verified one")
    void testSendOtpUnverifiedAndVerifiedIdenticalShapes() {
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(1L);
        when(environment.isDev()).thenReturn(true);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(unverifiedUser()));
        SendEmailOtpResponse unverified = service.sendOtp(EMAIL);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verifiedUser()));
        SendEmailOtpResponse verified = service.sendOtp(EMAIL);

        // Delivery differs; the response must not, or this endpoint becomes a verification oracle.
        assertEquals(unverified.message(), verified.message());
        assertEquals(unverified.expiresIn(), verified.expiresIn());
        assertEquals(unverified.maskedEmail(), verified.maskedEmail());
    }

    @Test
    @DisplayName("verifyOtp F-0601: correct OTP promotes a PENDING_VERIFICATION account to ACTIVE")
    void testVerifyOtpActivatesPendingAccount() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        User pending = unverifiedUser();
        assertFalse(pending.isEmailVerified());
        assertEquals(UserStatus.PENDING_VERIFICATION, pending.getStatus());
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(pending));

        VerifyEmailOtpResponse response = service.verifyOtp(EMAIL, OTP);

        assertTrue(response.emailVerified());
        // Both columns, because AuthService's login gate reads both (AuthService.java:412).
        assertTrue(pending.isEmailVerified());
        assertEquals(UserStatus.ACTIVE, pending.getStatus());
        verify(userRepository).save(pending);
    }

    @Test
    @DisplayName("verifyOtp F-0601: an already-verified account is not re-saved")
    void testVerifyOtpLeavesVerifiedAccountAlone() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        when(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(challenge));
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verifiedUser()));

        service.verifyOtp(EMAIL, OTP);

        verify(userRepository, never()).save(any(User.class));
    }

    private void setField(String name, Object value) throws Exception {
        Field f = BrandEmailOtpService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }
}
