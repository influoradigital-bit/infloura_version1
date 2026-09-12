package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
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
    @Mock private com.influora.service.security.AbuseThrottleService abuseThrottleService;
    @Mock private com.influora.service.security.FestivalClientIpHasher clientIpHasher;
    @Mock private OtpEmailDispatcher otpEmailDispatcher;

    private static final String IP = "203.0.113.7";

    private BrandEmailOtpService service;

    @BeforeEach
    void setUp() throws Exception {
        service =
                new BrandEmailOtpService(
                        otpRepository,
                        userRepository,
                        environment,
                        msg91EmailClient,
                        abuseThrottleService,
                        clientIpHasher,
                        otpEmailDispatcher);
        // Default: one tier, always under the cap. Individual tests override to assert the cap.
        lenient().when(clientIpHasher.hashTiers(anyString())).thenReturn(java.util.List.of("tier0"));
        lenient()
                .when(abuseThrottleService.tryConsume(anyString(), any(), anyLong()))
                .thenReturn(true);
        // [F2] enforceMailerConfigured() now runs on EVERY send, before any branch, so a test that
        // does not care about mailer config must still look configured or it gets a 503.
        lenient().when(msg91EmailClient.isConfigured()).thenReturn(true);
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
    @DisplayName("sendOtp: fourth send from one origin returns 429 RATE_LIMITED")
    void testPerEmailRateLimitBlocksFourthSend() {
        // [F4 second pass] The per-address budget is now split by origin: this is the per-ORIGIN
        // allowance refusing, which is what a single caller actually runs into. The global ceiling
        // is checked separately and is deliberately higher - see testAttackerOnOneOriginCannot...
        when(abuseThrottleService.tryConsume(startsWith("otp-email-origin:"), any(), anyLong()))
                .thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> service.sendOtp(EMAIL, IP));

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

        SendEmailOtpResponse response = service.sendOtp(EMAIL, IP);

        assertEquals("OTP sent successfully", response.message());
        assertEquals(300L, response.expiresIn());
        assertEquals("c***@example.com", response.maskedEmail());

        ArgumentCaptor<EmailOtpChallenge> captor = ArgumentCaptor.forClass(EmailOtpChallenge.class);
        verify(otpRepository).save(captor.capture());
        EmailOtpChallenge saved = captor.getValue();
        assertEquals(EMAIL, saved.getEmail());
        assertEquals(64, saved.getOtpHash().length());
        // [F2] The send goes through the @Async dispatcher, never MSG91 on the request thread --
        // that is what removes the duration difference between a delivered and a skipped send.
        verify(otpEmailDispatcher).deliver(eq(EMAIL), eq("otpman"), anyString(), anyString());
        verify(msg91EmailClient, never()).sendTemplateEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendOtp V-GA-6: dev mode logs only — never calls MSG91")
    void testSendOtpDevModeSkipsMsg91() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);
        when(environment.isDev()).thenReturn(true);

        SendEmailOtpResponse response = service.sendOtp(EMAIL, IP);

        assertEquals("OTP sent successfully", response.message());
        verify(otpRepository).save(any(EmailOtpChallenge.class));
        verify(msg91EmailClient, never()).isConfigured();
        verify(msg91EmailClient, never()).sendTemplateEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("F2: an UNCONFIGURED mailer 503s identically for a new AND a verified address")
    void testUnconfiguredMailerRefusesUniformly() {
        // The old check lived inside the delivery branch, so it could only ever fire for an address
        // we were going to mail. During a mail outage that made the STATUS CODE an enumeration
        // oracle needing no timing analysis at all: 503 meant "no verified account here", 200 meant
        // there was one. Both must now answer identically.
        when(environment.isDev()).thenReturn(false);
        when(msg91EmailClient.isConfigured()).thenReturn(false);

        ApiException first = assertThrows(ApiException.class, () -> service.sendOtp(EMAIL, IP));
        ApiException second = assertThrows(ApiException.class, () -> service.sendOtp(EMAIL, IP));

        assertEquals("EMAIL_DELIVERY_FAILED", first.getCode());
        assertEquals(first.getCode(), second.getCode());
        assertEquals(first.getStatus(), second.getStatus());
        assertEquals(503, second.getStatus().value());

        // The strongest form of "identical for every address": the refusal happens before the code
        // ever looks up WHICH address this is, so there is no branch left that could differ. Both
        // userRepository stubs this test originally carried were rejected by Mockito as
        // unnecessary - that rejection IS the evidence, so it is asserted here rather than
        // stubbed away.
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        verify(otpRepository, never()).save(any(EmailOtpChallenge.class));
    }

    @Test
    @DisplayName("F2: a failed delivery never reaches the caller - handed off, never inspected")
    void testDeliveryFailureDoesNotShapeTheResponse() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);
        when(environment.isDev()).thenReturn(false);

        SendEmailOtpResponse response = service.sendOtp(EMAIL, IP);

        assertEquals("OTP sent successfully", response.message());
        verify(otpEmailDispatcher).deliver(eq(EMAIL), eq("otpman"), anyString(), anyString());
    }

    @Test
    @DisplayName("sendOtp M-K6-C2-1: registered email returns identical success shape (no 409 oracle)")
    void testSendOtpRegisteredEmailUniformSuccess() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verifiedUser()));
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);

        SendEmailOtpResponse response = service.sendOtp(EMAIL, IP);

        assertEquals("OTP sent successfully", response.message());
        assertEquals(300L, response.expiresIn());
        assertEquals("c***@example.com", response.maskedEmail());
        verify(otpRepository).save(any(EmailOtpChallenge.class));
        verify(otpEmailDispatcher, never())
                .deliver(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendOtp M-K6-C2-1: unregistered vs registered responses are shape-identical")
    void testSendOtpRegisteredAndUnregisteredIdenticalShapes() {
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(1L);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(environment.isDev()).thenReturn(true);
        SendEmailOtpResponse unregistered = service.sendOtp(EMAIL, IP);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verifiedUser()));
        SendEmailOtpResponse registered = service.sendOtp(EMAIL, IP);

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

        service.sendOtp(EMAIL, IP);

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
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.of(challenge));
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
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.of(challenge));

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
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.of(challenge));

        ApiException ex = assertThrows(ApiException.class, () -> service.verifyOtp(EMAIL, OTP));

        assertEquals("OTP_EXPIRED", ex.getCode());
        assertEquals(410, ex.getStatus().value());
        verify(otpRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifyOtp G-Kv3-1: missing challenge returns INVALID_OTP")
    void testRejectMissingChallenge() {
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.verifyOtp(EMAIL, OTP));

        assertEquals("INVALID_OTP", ex.getCode());
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    @DisplayName("verifyOtp G-Kv3-1: locks after 3 failed attempts (4th call → TOO_MANY_ATTEMPTS 429)")
    void testLockAfterThreeFailedAttempts() {
        EmailOtpChallenge challenge = challengeWithHash(OTP, Instant.now().plusSeconds(300));
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.of(challenge));

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
    @DisplayName("F4: one origin exhausting its allowance does NOT lock the address out elsewhere")
    void testAttackerOnOneOriginCannotLockTheAddressOutGlobally() {
        // The lockout this splits apart: three POSTs naming someone else's address used to consume
        // that person's ENTIRE hourly budget, so their own signup answered 429 for the rest of the
        // hour. The budget is now per (address, origin), so the attacker spends only their own.
        String attackerIp = "198.51.100.4";
        String victimIp = "203.0.113.99";
        when(clientIpHasher.hashTiers(attackerIp)).thenReturn(java.util.List.of("attacker-tier"));
        when(clientIpHasher.hashTiers(victimIp)).thenReturn(java.util.List.of("victim-tier"));
        // The attacker's origin is spent; the victim's is not. Same address in both cases.
        when(abuseThrottleService.tryConsume(contains("attacker-tier"), any(), anyLong()))
                .thenReturn(false);

        assertThrows(ApiException.class, () -> service.sendOtp(EMAIL, attackerIp));

        // The victim, on their own network, still gets a code for their own address.
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(3L);
        when(environment.isDev()).thenReturn(true);

        SendEmailOtpResponse victimResponse = service.sendOtp(EMAIL, victimIp);

        assertEquals("OTP sent successfully", victimResponse.message());
        verify(otpRepository).save(any(EmailOtpChallenge.class));
    }

    @Test
    @DisplayName("F4: the global per-address ceiling still bounds total mail to one mailbox")
    void testGlobalPerEmailCeilingStillApplies() {
        // The per-origin split must not become an unlimited mail cannon via origin rotation: the
        // address keeps a ceiling across every origin.
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(10L);

        ApiException ex = assertThrows(ApiException.class, () -> service.sendOtp(EMAIL, IP));

        assertEquals("RATE_LIMITED", ex.getCode());
        verify(otpRepository, never()).save(any(EmailOtpChallenge.class));
    }

    @Test
    @DisplayName("F4: a live challenge is reused - no new code minted, no second email, no budget spent")
    void testLiveChallengeIsReusedNotReminted() {
        // The victim-DoS scenario: someone else POSTs this address again while a code is still
        // live. Before F4 that minted a second challenge and burned one of the OWNER's three
        // hourly sends; three of them locked the real owner out of signup for the rest of the hour.
        EmailOtpChallenge live = challengeWithHash(OTP, Instant.now().plusSeconds(240));
        when(otpRepository.findNewestForEmail(EMAIL))
                .thenReturn(Optional.of(live));

        SendEmailOtpResponse res = service.sendOtp(EMAIL, IP);

        verify(otpRepository, never()).save(any(EmailOtpChallenge.class));
        verify(msg91EmailClient, never()).sendTemplateEmail(anyString(), anyString(), anyString());
        // The per-email cap must not even be consulted - that is the whole point.
        verify(otpRepository, never()).countByEmailAndCreatedAtAfter(anyString(), any());
        // Envelope is indistinguishable from a real send, so this cannot be used to probe whether
        // a live challenge exists for an address. Only the TTL differs, and it counts down.
        assertEquals("OTP sent successfully", res.message());
        assertTrue(res.expiresIn() > 0 && res.expiresIn() <= 240, "remaining TTL, not a fresh 300");
    }

    @Test
    @DisplayName("F4: per-IP send cap refuses with 429 and sends no email")
    void testPerIpSendCapRefuses() {
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.empty());
        when(clientIpHasher.hashTiers(IP)).thenReturn(java.util.List.of("tier0"));
        when(abuseThrottleService.tryConsume(anyString(), any(), anyLong())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> service.sendOtp(EMAIL, IP));

        assertEquals("RATE_LIMITED", ex.getCode());
        assertEquals(429, ex.getStatus().value());
        verify(msg91EmailClient, never()).sendTemplateEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("requireAndConsumeVerifiedEmail G-Kv3-1: EMAIL_NOT_VERIFIED when no live proof exists")
    void testRequireVerifiedEmailRejectsUnverified() {
        when(otpRepository.existsByEmailAndVerifiedTrueAndExpiresAtAfter(eq(EMAIL), any(Instant.class)))
                .thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.requireAndConsumeVerifiedEmail(EMAIL));

        assertEquals("EMAIL_NOT_VERIFIED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // A refusal must not spend anything - otherwise a failed check would clear a verification
        // the caller never got to use.
        verify(otpRepository, never()).clearVerifiedForEmail(anyString());
    }

    @Test
    @DisplayName("requireAndConsumeVerifiedEmail G-Kv3-1: passes when a live verified proof exists")
    void testRequireVerifiedEmailPassesWhenVerified() {
        when(otpRepository.existsByEmailAndVerifiedTrueAndExpiresAtAfter(eq(EMAIL), any(Instant.class)))
                .thenReturn(true);

        service.requireAndConsumeVerifiedEmail(EMAIL);
    }

    @Test
    @DisplayName("F6: a stranger requesting a new code cannot revoke a completed verification")
    void testNewChallengeCannotShadowACompletedVerification() {
        // The attack: the owner verifies, then someone else POSTs send-email-otp for that address.
        // The fresh UNVERIFIED row becomes the newest, and the old "is the newest row verified?"
        // check answered no -- so the owner's registration started failing EMAIL_NOT_VERIFIED while
        // the code they needed sat in the attacker's chosen moment.
        // A live verified proof for the address exists, alongside the attacker's fresh unverified
        // row that is now the NEWEST for this email.
        when(otpRepository.existsByEmailAndVerifiedTrueAndExpiresAtAfter(eq(EMAIL), any(Instant.class)))
                .thenReturn(true);

        service.requireAndConsumeVerifiedEmail(EMAIL);

        // Registration proceeds, and the proof is still spent exactly once.
        verify(otpRepository).clearVerifiedForEmail(EMAIL);
        // The strongest statement of the fix: the "newest row" is never consulted here at all, so
        // there is no longer anything for a fresh row to shadow. Mockito rejected the stub this
        // test first carried for that row as UNNECESSARY - that rejection is the evidence, so it
        // is asserted rather than stubbed away.
        verify(otpRepository, never()).findNewestForEmail(anyString());
    }

    @Test
    @DisplayName("F5: a used verification is SPENT, so the same proof cannot satisfy a second register")
    void testRequireVerifiedEmailConsumesTheVerification() {
        when(otpRepository.existsByEmailAndVerifiedTrueAndExpiresAtAfter(eq(EMAIL), any(Instant.class)))
                .thenReturn(true);

        service.requireAndConsumeVerifiedEmail(EMAIL);

        // clearVerifiedForEmail had ZERO callers in src/main before this - the query existed to
        // invalidate a spent verification and nothing ever ran it.
        verify(otpRepository).clearVerifiedForEmail(EMAIL);
    }

    @Test
    @DisplayName("F5: a verification older than the validity window no longer satisfies register")
    void testRequireVerifiedEmailRejectsStaleVerification() {
        // Verified, but outside the 30-minute validity window. Before F5 this passed: the check
        // read the verified flag and never looked at age, so a challenge verified months earlier
        // still satisfied registration. The repository now applies the window, so a stale proof
        // simply does not match.
        when(otpRepository.existsByEmailAndVerifiedTrueAndExpiresAtAfter(eq(EMAIL), any(Instant.class)))
                .thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.requireAndConsumeVerifiedEmail(EMAIL));

        assertEquals("EMAIL_NOT_VERIFIED", ex.getCode());
        verify(otpRepository, never()).clearVerifiedForEmail(anyString());
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

        SendEmailOtpResponse response = service.sendOtp(EMAIL, IP);

        assertEquals("OTP sent successfully", response.message());
        verify(otpRepository).save(any(EmailOtpChallenge.class));
        // The assertion that matters: under the old !alreadyRegistered gate this was never(...).
        // [F2] Delivery is now the dispatcher hand-off rather than a direct MSG91 call; what this
        // test pins - that an unverified registered account DOES get a code - is unchanged.
        verify(otpEmailDispatcher).deliver(eq(EMAIL), eq("otpman"), anyString(), anyString());
    }

    @Test
    @DisplayName("sendOtp F-0601: unverified-account send is shape-identical to the verified one")
    void testSendOtpUnverifiedAndVerifiedIdenticalShapes() {
        when(otpRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(1L);
        when(environment.isDev()).thenReturn(true);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(unverifiedUser()));
        SendEmailOtpResponse unverified = service.sendOtp(EMAIL, IP);

        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verifiedUser()));
        SendEmailOtpResponse verified = service.sendOtp(EMAIL, IP);

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
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.of(challenge));
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
        when(otpRepository.findNewestForEmail(EMAIL)).thenReturn(Optional.of(challenge));
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
