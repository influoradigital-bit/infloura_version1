package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.InfluoraEnvironment;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PasswordResetToken;
import com.influora.domain.entity.RefreshToken;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PasswordResetTokenRepository;
import com.influora.repository.RefreshTokenRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.JwtService;
import com.influora.web.dto.auth.AuthDtos.TokenPair;
import com.influora.web.dto.auth.BrandRegisterRequest;
import com.influora.web.dto.auth.CreatorRegisterRequest;
import com.influora.web.dto.auth.LoginRequest;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Auth unit suite — brand/creator register TOCTOU (E2 #3) plus G-Kv3-1 creator OTP-gated
 * register, login happy/hostile, refresh rotation, and password-reset session revoke.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private BrandEmailOtpService brandEmailOtpService;
    @Mock private InfluoraEnvironment environment;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AuthService authService;

    private static final BrandRegisterRequest REQUEST =
            new BrandRegisterRequest(
                    "Ada",
                    "Lovelace",
                    "ada@example.com",
                    "Supersecret1",
                    "Acme Co",
                    "RETAIL",
                    "SMALL",
                    true);

    private static final CreatorRegisterRequest CREATOR_REQUEST =
            new CreatorRegisterRequest(
                    "riya@example.com",
                    "Supersecret1",
                    "Riya",
                    "Sharma",
                    null,
                    true);

    private static final LoginRequest CREATOR_LOGIN =
            new LoginRequest("riya@example.com", "Supersecret1");

    @BeforeEach
    void setUp() throws Exception {
        authService =
                new AuthService(
                        userRepository,
                        workspaceRepository,
                        workspaceMemberRepository,
                        refreshTokenRepository,
                        passwordResetTokenRepository,
                        walletRepository,
                        creatorProfileRepository,
                        passwordEncoder,
                        jwtService,
                        brandEmailOtpService,
                        environment,
                        eventPublisher);
        // Defaults match application.yml; tests that need OTP/verification gates flip these.
        setField("requireEmailVerification", true);
        setField("requireEmailOtpBeforeRegister", false);
        setField("webBaseUrl", "http://localhost:5173");
    }

    private User creatorUser(boolean emailVerified) {
        User user =
                User.newCreator(
                        "01HCREATORUSER123456789A",
                        "riya@example.com",
                        "hashed-pw",
                        "Riya",
                        "Sharma",
                        "Riya Sharma");
        if (emailVerified) {
            user.setEmailVerified(true);
        }
        return user;
    }

    private void stubTokenIssuance(String userId) {
        when(jwtService.createAccessToken(eq(userId), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds()).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AuthService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(authService, value);
    }

    private void setUserStatus(User user, UserStatus status) throws Exception {
        Field f = User.class.getDeclaredField("status");
        f.setAccessible(true);
        f.set(user, status);
    }

    // ── Brand / creator register TOCTOU (existing E2 coverage) ──────────────

    @Test
    @DisplayName(
            "brandRegister: sequential duplicate (existsByEmailIgnoreCase true) throws the friendly"
                    + " 409 without touching save()")
    void testSequentialDuplicateThrowsFriendly409() {
        when(userRepository.existsByEmailIgnoreCase(REQUEST.email())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandRegister(REQUEST));

        assertEquals("EMAIL_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName(
            "brandRegister: TOCTOU race -- existsByEmailIgnoreCase says false (loser of the race) but"
                    + " the final save() hits the DB UNIQUE constraint -- must surface the SAME"
                    + " friendly 409, never a raw 500")
    void testConcurrentRaceLoserGetsFriendly409NotRaw500() {
        when(userRepository.existsByEmailIgnoreCase(REQUEST.email())).thenReturn(false);
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        lenient().when(workspaceRepository.existsBySlug(any())).thenReturn(false);
        lenient()
                .when(workspaceMemberRepository.save(any(WorkspaceMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'users.email'"))
                .when(userRepository)
                .saveAndFlush(any(User.class));

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandRegister(REQUEST));

        assertEquals("EMAIL_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName(
            "creatorRegister: sequential duplicate (existsByEmailIgnoreCase true) throws the friendly"
                    + " 409 without touching save()")
    void testCreatorSequentialDuplicateThrowsFriendly409() {
        when(userRepository.existsByEmailIgnoreCase(CREATOR_REQUEST.email())).thenReturn(true);

        ApiException ex =
                assertThrows(ApiException.class, () -> authService.creatorRegister(CREATOR_REQUEST));

        assertEquals("EMAIL_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName(
            "creatorRegister: TOCTOU race -- existsByEmailIgnoreCase says false (loser of the race) but"
                    + " the final saveAndFlush() hits the DB UNIQUE constraint -- must surface the SAME"
                    + " friendly 409, never a raw 500")
    void testCreatorConcurrentRaceLoserGetsFriendly409NotRaw500() {
        when(userRepository.existsByEmailIgnoreCase(CREATOR_REQUEST.email())).thenReturn(false);
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        lenient()
                .when(creatorProfileRepository.save(any(CreatorProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'users.email'"))
                .when(userRepository)
                .saveAndFlush(any(User.class));

        ApiException ex =
                assertThrows(ApiException.class, () -> authService.creatorRegister(CREATOR_REQUEST));

        assertEquals("EMAIL_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    // ── G-Kv3-1 creator register happy + OTP gate ───────────────────────────

    @Test
    @DisplayName(
            "creatorRegister G-Kv3-1: happy path creates user + empty profile + wallet and issues JWT")
    void testCreatorRegisterHappyPath() {
        when(userRepository.existsByEmailIgnoreCase(CREATOR_REQUEST.email())).thenReturn(false);
        when(passwordEncoder.encode("Supersecret1")).thenReturn("hashed-pw");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        // userId is a ULID generated inside — match any CREATOR access token call
        when(jwtService.createAccessToken(anyString(), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds()).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenPair pair = authService.creatorRegister(CREATOR_REQUEST);

        assertEquals("access-jwt", pair.accessToken());
        assertEquals("refresh-raw", pair.refreshToken());
        assertEquals(UserType.CREATOR, pair.user().userType());
        assertNull(pair.workspace());
        assertEquals("riya@example.com", pair.user().email());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertEquals("hashed-pw", userCaptor.getValue().getPasswordHash());

        ArgumentCaptor<CreatorProfile> profileCaptor = ArgumentCaptor.forClass(CreatorProfile.class);
        verify(creatorProfileRepository).save(profileCaptor.capture());
        assertEquals(userCaptor.getValue().getId(), profileCaptor.getValue().getUserId());

        verify(walletRepository).save(any(Wallet.class));
        verify(brandEmailOtpService, never()).requireVerifiedEmail(anyString());
    }

    @Test
    @DisplayName(
            "creatorRegister G-Kv3-1: when OTP-before-register is on, unverified email → EMAIL_NOT_VERIFIED")
    void testCreatorRegisterRequiresVerifiedOtp() throws Exception {
        setField("requireEmailOtpBeforeRegister", true);
        when(userRepository.existsByEmailIgnoreCase(CREATOR_REQUEST.email())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pw");
        doThrow(
                        new ApiException(
                                "EMAIL_NOT_VERIFIED",
                                "Please verify your email with the OTP before continuing",
                                org.springframework.http.HttpStatus.FORBIDDEN))
                .when(brandEmailOtpService)
                .requireVerifiedEmail(CREATOR_REQUEST.email());

        ApiException ex =
                assertThrows(ApiException.class, () -> authService.creatorRegister(CREATOR_REQUEST));

        assertEquals("EMAIL_NOT_VERIFIED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName(
            "creatorRegister G-Kv3-1: OTP-before-register on + verified → marks emailVerified and creates user")
    void testCreatorRegisterWithVerifiedOtpMarksEmailVerified() throws Exception {
        setField("requireEmailOtpBeforeRegister", true);
        when(userRepository.existsByEmailIgnoreCase(CREATOR_REQUEST.email())).thenReturn(false);
        when(passwordEncoder.encode("Supersecret1")).thenReturn("hashed-pw");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(anyString(), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds()).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenPair pair = authService.creatorRegister(CREATOR_REQUEST);

        assertTrue(pair.user().emailVerified());
        verify(brandEmailOtpService).requireVerifiedEmail(CREATOR_REQUEST.email());
    }

    @Test
    @DisplayName("creatorRegister G-Kv3-1: weak password rejected before any persistence")
    void testCreatorRegisterWeakPassword() {
        CreatorRegisterRequest weak =
                new CreatorRegisterRequest("riya@example.com", "password", "Riya", "Sharma", null, true);
        when(userRepository.existsByEmailIgnoreCase(weak.email())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorRegister(weak));

        assertEquals("WEAK_PASSWORD", ex.getCode());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    // ── G-Kv3-1 creator login happy + hostile ───────────────────────────────

    @Test
    @DisplayName("creatorLogin G-Kv3-1: happy path issues JWT for verified ACTIVE creator")
    void testCreatorLoginHappyPath() {
        User user = creatorUser(true);
        when(userRepository.findByEmailIgnoreCase(CREATOR_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        stubTokenIssuance(user.getId());

        TokenPair pair = authService.creatorLogin(CREATOR_LOGIN);

        assertEquals("access-jwt", pair.accessToken());
        assertEquals(UserType.CREATOR, pair.user().userType());
        assertNull(pair.workspace());
        assertNotNull(user.getLastLoginAt());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("creatorLogin G-Kv3-1: unknown email → INVALID_CREDENTIALS 401 (no oracle)")
    void testCreatorLoginUnknownEmail() {
        when(userRepository.findByEmailIgnoreCase(CREATOR_LOGIN.email())).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorLogin(CREATOR_LOGIN));

        assertEquals("INVALID_CREDENTIALS", ex.getCode());
        assertEquals(401, ex.getStatus().value());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("creatorLogin G-Kv3-1: wrong password → INVALID_CREDENTIALS 401")
    void testCreatorLoginWrongPassword() {
        User user = creatorUser(true);
        when(userRepository.findByEmailIgnoreCase(CREATOR_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorLogin(CREATOR_LOGIN));

        assertEquals("INVALID_CREDENTIALS", ex.getCode());
        assertEquals(401, ex.getStatus().value());
        verify(jwtService, never()).createAccessToken(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("creatorLogin G-Kv3-1: brand account on creator endpoint → WRONG_USER_TYPE 403")
    void testCreatorLoginWrongUserType() {
        User brand =
                User.newBrand(
                        "01HBRANDUSER123456789ABC",
                        "riya@example.com",
                        "hashed-pw",
                        "Riya",
                        "Sharma",
                        "Riya Sharma");
        when(userRepository.findByEmailIgnoreCase(CREATOR_LOGIN.email())).thenReturn(Optional.of(brand));

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorLogin(CREATOR_LOGIN));

        assertEquals("WRONG_USER_TYPE", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("creatorLogin G-Kv3-1: suspended account → ACCOUNT_SUSPENDED 403")
    void testCreatorLoginSuspended() throws Exception {
        User user = creatorUser(true);
        setUserStatus(user, UserStatus.SUSPENDED);
        when(userRepository.findByEmailIgnoreCase(CREATOR_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorLogin(CREATOR_LOGIN));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    @Test
    @DisplayName(
            "creatorLogin G-Kv3-1: PENDING_VERIFICATION + unverified email → EMAIL_NOT_VERIFIED 403")
    void testCreatorLoginEmailNotVerified() {
        User user = creatorUser(false); // PENDING_VERIFICATION, emailVerified=false
        when(userRepository.findByEmailIgnoreCase(CREATOR_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorLogin(CREATOR_LOGIN));

        assertEquals("EMAIL_NOT_VERIFIED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }

    // ── Refresh + password reset (spec §2) ──────────────────────────────────

    @Test
    @DisplayName("refresh G-Kv3-1: rotates refresh token and returns new access")
    void testRefreshRotatesToken() {
        User user = creatorUser(true);
        String raw = "old-refresh-raw";
        String hash = JwtService.hashToken(raw);
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESHTOKEN123456789A",
                        user.getId(),
                        hash,
                        Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.empty());
        when(jwtService.createRefreshTokenValue()).thenReturn("new-refresh-raw");
        when(jwtService.getRefreshExpirySeconds()).thenReturn(2_592_000L);
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("new-access");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthService.RefreshRotation rotation = authService.refresh(raw);

        assertEquals("new-access", rotation.accessToken());
        assertEquals("new-refresh-raw", rotation.newRefreshToken());
        assertTrue(stored.isRevoked());
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("refresh G-Kv3-1: unknown/expired refresh → INVALID_REFRESH_TOKEN 401")
    void testRefreshInvalidToken() {
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(anyString()))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh("bogus"));

        assertEquals("INVALID_REFRESH_TOKEN", ex.getCode());
        assertEquals(401, ex.getStatus().value());
    }

    // ── AUTH-1 (2026-07-15, Priya): refresh() account-state gates ──────────

    @Test
    @DisplayName(
            "refresh AUTH-1: SUSPENDED account → ACCOUNT_SUSPENDED 403, presented token NOT revoked"
                    + " (rejected refresh must not burn the token)")
    void testRefreshRejectsSuspendedAccount() throws Exception {
        User user = creatorUser(true);
        setUserStatus(user, UserStatus.SUSPENDED);
        String raw = "old-refresh-raw";
        String hash = JwtService.hashToken(raw);
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(raw));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertTrue(!stored.isRevoked(), "rejected refresh must leave the presented token unrevoked");
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
        verify(jwtService, never()).createAccessToken(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName(
            "refresh AUTH-1: DEACTIVATED account → ACCOUNT_SUSPENDED 403, presented token NOT revoked")
    void testRefreshRejectsDeactivatedAccount() throws Exception {
        User user = creatorUser(true);
        setUserStatus(user, UserStatus.DEACTIVATED);
        String raw = "old-refresh-raw";
        String hash = JwtService.hashToken(raw);
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(raw));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertTrue(!stored.isRevoked());
    }

    @Test
    @DisplayName(
            "refresh AUTH-1: PENDING_VERIFICATION + unverified email → EMAIL_NOT_VERIFIED 403,"
                    + " presented token NOT revoked")
    void testRefreshRejectsUnverifiedAccount() {
        User user = creatorUser(false); // PENDING_VERIFICATION, emailVerified=false
        String raw = "old-refresh-raw";
        String hash = JwtService.hashToken(raw);
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(raw));

        assertEquals("EMAIL_NOT_VERIFIED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertTrue(!stored.isRevoked());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName(
            "refresh AUTH-1: normal ACTIVE + verified user still succeeds and rotates the token"
                    + " (gates don't false-positive on the happy path)")
    void testRefreshStillSucceedsForActiveVerifiedUser() {
        User user = creatorUser(true); // ACTIVE, emailVerified=true
        String raw = "old-refresh-raw";
        String hash = JwtService.hashToken(raw);
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.empty());
        when(jwtService.createRefreshTokenValue()).thenReturn("new-refresh-raw");
        when(jwtService.getRefreshExpirySeconds()).thenReturn(2_592_000L);
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("new-access");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthService.RefreshRotation rotation = authService.refresh(raw);

        assertEquals("new-access", rotation.accessToken());
        assertTrue(stored.isRevoked());
    }

    @Test
    @DisplayName("resetPassword G-Kv3-1: valid token updates hash and revokes all sessions")
    void testResetPasswordInvalidatesSessions() {
        User user = creatorUser(true);
        String rawToken = "reset-raw-token";
        PasswordResetToken token =
                PasswordResetToken.create(
                        "01HRESETTOKEN123456789ABC",
                        user.getId(),
                        JwtService.hashToken(rawToken),
                        Instant.now().plusSeconds(3600));
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalse(JwtService.hashToken(rawToken)))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewSecret9")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        authService.resetPassword(rawToken, "NewSecret9");

        assertEquals("new-hash", user.getPasswordHash());
        assertTrue(token.isUsed());
        verify(refreshTokenRepository).revokeAllForUser(user.getId());
    }

    @Test
    @DisplayName("resetPassword G-Kv3-1: invalid/expired token → INVALID_RESET_TOKEN 400")
    void testResetPasswordInvalidToken() {
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalse(anyString()))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> authService.resetPassword("bogus", "NewSecret9"));

        assertEquals("INVALID_RESET_TOKEN", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(refreshTokenRepository, never()).revokeAllForUser(anyString());
    }

    @Test
    @DisplayName("forgotPassword G-Kv3-1: always returns uniform message (no email oracle)")
    void testForgotPasswordUniformMessage() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        String msg = authService.forgotPassword("nobody@example.com");

        assertEquals("If this email exists, a reset link has been sent.", msg);
        verify(passwordResetTokenRepository, never()).save(any());
    }

    // ── changePassword (Priya audit, e60d249 follow-up: revoke-except-current) ─

    @Test
    @DisplayName(
            "changePassword: valid current refresh token -- revokes every OTHER session"
                    + " (revokeAllForUserExcept with the resolved keepId), never the blanket"
                    + " revokeAllForUser, so the caller who just changed their password stays"
                    + " signed in")
    void testChangePasswordKeepsCallersOwnSession() {
        User user = creatorUser(true);
        String rawRefreshToken = "callers-own-refresh-raw";
        String tokenHash = JwtService.hashToken(rawRefreshToken);
        RefreshToken callersToken =
                RefreshToken.create(
                        "01HKEEPTOKEN123456789ABCD", user.getId(), tokenHash, Instant.now().plusSeconds(3600));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldSecret1", "hashed-pw")).thenReturn(true);
        when(passwordEncoder.encode("NewSecret9")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash))
                .thenReturn(Optional.of(callersToken));

        authService.changePassword(user.getId(), "OldSecret1", "NewSecret9", rawRefreshToken);

        assertEquals("new-hash", user.getPasswordHash());
        verify(refreshTokenRepository)
                .revokeAllForUserExcept(user.getId(), "01HKEEPTOKEN123456789ABCD");
        verify(refreshTokenRepository, never()).revokeAllForUser(anyString());
    }

    @Test
    @DisplayName(
            "changePassword: no/blank current refresh token -- falls back to revoking every session"
                    + " (revokeAllForUser), since there is nothing to spare")
    void testChangePasswordFallsBackToRevokeAllWithNoRefreshToken() {
        User user = creatorUser(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldSecret1", "hashed-pw")).thenReturn(true);
        when(passwordEncoder.encode("NewSecret9")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.changePassword(user.getId(), "OldSecret1", "NewSecret9", null);

        assertEquals("new-hash", user.getPasswordHash());
        verify(refreshTokenRepository).revokeAllForUser(user.getId());
        verify(refreshTokenRepository, never()).revokeAllForUserExcept(anyString(), anyString());

        // Also covers the blank (empty-string) case -- same fallback, no lookup attempted.
        verify(refreshTokenRepository, never()).findByTokenHashAndRevokedFalse(anyString());
    }

    @Test
    @DisplayName(
            "changePassword: wrong currentPassword -- INVALID_CURRENT_PASSWORD 401, nothing"
                    + " persisted and no session revoked")
    void testChangePasswordWrongCurrentPasswordDoesNotRevokeAnything() {
        User user = creatorUser(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPassword1", "hashed-pw")).thenReturn(false);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                authService.changePassword(
                                        user.getId(), "WrongPassword1", "NewSecret9", "some-refresh-raw"));

        assertEquals("INVALID_CURRENT_PASSWORD", ex.getCode());
        assertEquals(401, ex.getStatus().value());
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(refreshTokenRepository);
    }
}
