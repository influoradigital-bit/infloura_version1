package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import com.influora.domain.entity.Workspace;
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
    @Mock private RegistrationService registrationService;

    private AuthService authService;

    // PHONE-0904 Q8 ruling — brand phone is now REQUIRED at registration, so the shared happy-path
    // fixture must carry one; a dedicated null/blank REQUEST variant lives in
    // testBrandRegisterRejectsMissingPhone/testBrandRegisterRejectsBlankPhone below instead.
    private static final BrandRegisterRequest REQUEST =
            new BrandRegisterRequest(
                    "Ada",
                    "Lovelace",
                    "ada@example.com",
                    "Supersecret1",
                    "Acme Co",
                    "RETAIL",
                    "SMALL",
                    true,
                    "9876543210");

    // PHONE-0906 ruling -- creator phone is now REQUIRED at registration too, so the shared
    // happy-path fixture must carry one. Deliberately a DIFFERENT number from the brand fixture
    // above: users.phone_number is UNIQUE across user types, and reusing 9876543210 here would
    // have made these two suites quietly describe the same person. Missing/blank/malformed/
    // duplicate variants live in the dedicated tests below via creatorRequestWithPhone.
    private static final CreatorRegisterRequest CREATOR_REQUEST =
            new CreatorRegisterRequest(
                    "riya@example.com",
                    "Supersecret1",
                    "Riya",
                    "Sharma",
                    null,
                    true,
                    null,
                    "9876500001");

    private static final LoginRequest CREATOR_LOGIN =
            new LoginRequest("riya@example.com", "Supersecret1");

    @BeforeEach
    void setUp() throws Exception {
        // Real UserPhoneService wired onto the same mocked userRepository, not a mock — PHONE-0904
        // extracted brandRegister's inline phone normalize/validate/exists logic out unchanged,
        // so the existing phone tests below still exercise the real behavior via one extra hop.
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
                        eventPublisher,
                        registrationService,
                        new UserPhoneService(userRepository));
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
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
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

    // ── PHONE-0829 Gap A: brand register persists phone to users.phone_number ──────────────

    @Test
    @DisplayName("brandRegister: persists a normalized phone (spaces/+91 stripped) to users.phone_number")
    void testBrandRegisterPersistsNormalizedPhone() {
        BrandRegisterRequest req = brandRequestWithPhone("+91 98765 43210");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(workspaceRepository.existsBySlug(any())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workspaceMemberRepository.save(any(WorkspaceMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(anyString(), eq(UserType.BRAND), anyString(), anyString()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.brandRegister(req);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertEquals("9876543210", userCaptor.getValue().getPhoneNumber());
    }

    @Test
    @DisplayName(
            "brandRegister: PHONE-0904 Q8 -- missing phone rejected with its OWN PHONE_REQUIRED"
                    + " code, distinct from INVALID_PHONE/PHONE_ALREADY_EXISTS, before touching"
                    + " persistence")
    void testBrandRegisterRejectsMissingPhone() {
        BrandRegisterRequest req = brandRequestWithPhone(null);
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandRegister(req));

        assertEquals("PHONE_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("brandRegister: PHONE-0904 Q8 -- blank phone (whitespace) also rejected as PHONE_REQUIRED")
    void testBrandRegisterRejectsBlankPhone() {
        BrandRegisterRequest req = brandRequestWithPhone("   ");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandRegister(req));

        assertEquals("PHONE_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("brandRegister: rejects an invalid phone format before touching persistence")
    void testBrandRegisterRejectsInvalidPhone() {
        BrandRegisterRequest req = brandRequestWithPhone("12345");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandRegister(req));

        assertEquals("INVALID_PHONE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName(
            "brandRegister: sequential duplicate phone (existsByPhoneNumber true) throws a clean 409,"
                    + " not a raw 500")
    void testBrandRegisterDuplicatePhoneThrowsFriendly409() {
        BrandRegisterRequest req = brandRequestWithPhone("9876543210");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandRegister(req));

        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName(
            "brandRegister: TOCTOU race on phone -- existsByPhoneNumber says false (loser of the"
                    + " race) but the final save() hits the DB UNIQUE constraint -- must surface"
                    + " PHONE_ALREADY_EXISTS, never a raw 500 or the misleading EMAIL_ALREADY_EXISTS")
    void testBrandRegisterPhoneRaceLoserGetsFriendly409NotRaw500() {
        BrandRegisterRequest req = brandRequestWithPhone("9876543210");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);
        when(userRepository.existsByPhoneNumber("9876543210"))
                .thenReturn(false) // upfront check: loser hasn't lost yet
                .thenReturn(true); // re-check inside the catch block: now it has
        lenient().when(passwordEncoder.encode(any())).thenReturn("hashed");
        lenient().when(workspaceRepository.existsBySlug(any())).thenReturn(false);
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'users.phone_number'"))
                .when(userRepository)
                .saveAndFlush(any(User.class));

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandRegister(req));

        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    private BrandRegisterRequest brandRequestWithPhone(String phone) {
        return new BrandRegisterRequest(
                "Ada", "Lovelace", "ada@example.com", "Supersecret1", "Acme Co", "RETAIL", "SMALL",
                true, phone);
    }

    private CreatorRegisterRequest creatorRequestWithPhone(String phone) {
        return new CreatorRegisterRequest(
                "riya@example.com", "Supersecret1", "Riya", "Sharma", null, true, null, phone);
    }

    // -- PHONE-0906: creator registration phone gate (mirrors the brand set above) ------------

    @Test
    @DisplayName(
            "creatorRegister: PHONE-0906 -- a missing phone gets its own PHONE_REQUIRED/400 code,"
                    + " distinct from INVALID_PHONE/PHONE_ALREADY_EXISTS, before touching"
                    + " persistence")
    void testCreatorRegisterRejectsMissingPhone() {
        CreatorRegisterRequest req = creatorRequestWithPhone(null);
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorRegister(req));

        assertEquals("PHONE_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        // The three codes must stay separable, so "missing" must never reach the uniqueness check.
        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(creatorProfileRepository);
        verifyNoInteractions(walletRepository);
    }

    @Test
    @DisplayName(
            "creatorRegister: PHONE-0906 -- blank phone (whitespace) also rejected as"
                    + " PHONE_REQUIRED")
    void testCreatorRegisterRejectsBlankPhone() {
        CreatorRegisterRequest req = creatorRequestWithPhone("   ");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorRegister(req));

        assertEquals("PHONE_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(creatorProfileRepository);
    }

    @Test
    @DisplayName(
            "creatorRegister: PHONE-0906 -- a malformed phone is INVALID_PHONE/400, not"
                    + " PHONE_REQUIRED, and never reaches persistence")
    void testCreatorRegisterRejectsInvalidPhone() {
        CreatorRegisterRequest req = creatorRequestWithPhone("12345");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorRegister(req));

        assertEquals("INVALID_PHONE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(creatorProfileRepository);
    }

    @Test
    @DisplayName(
            "creatorRegister: PHONE-0906 -- a number already held by ANY account (brand included)"
                    + " throws PHONE_ALREADY_EXISTS/409, never the misleading EMAIL_ALREADY_EXISTS")
    void testCreatorRegisterDuplicatePhoneThrowsFriendly409() {
        CreatorRegisterRequest req = creatorRequestWithPhone("9876543210");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);
        // The row that owns this number is deliberately unspecified here -- users.phone_number is
        // UNIQUE across user types, so a brand account holding it blocks the creator signup just
        // as another creator would. This is the accepted dead-end recorded in
        // wiki/decisions/phone-0904-cto-review.md D4 and its PHONE-0906 addendum.
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorRegister(req));

        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(creatorProfileRepository);
    }

    @Test
    @DisplayName(
            "creatorRegister: PHONE-0906 -- a pasted '+91 98765 00001' is normalized to 10 digits"
                    + " and persisted on the user row")
    void testCreatorRegisterNormalizesAndPersistsPhone() {
        CreatorRegisterRequest req = creatorRequestWithPhone("+91 98765 00001");
        when(userRepository.existsByEmailIgnoreCase(req.email())).thenReturn(false);
        when(passwordEncoder.encode("Supersecret1")).thenReturn("hashed-pw");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creatorProfileRepository.save(any(CreatorProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(anyString(), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        authService.creatorRegister(req);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        // Asserting the STORED value, not merely that the call succeeded: persisting the raw
        // '+91 98765 00001' would defeat the UNIQUE constraint (the same number could be stored
        // in several spellings) and break every lookup that assumes 10 digits.
        assertEquals("9876500001", saved.getValue().getPhoneNumber());
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
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
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
        // Q5.5: an ordinary (non-invite) registration passes the null inviteToken through
        // unconditionally — RegistrationService is responsible for treating that as a no-op.
        verify(registrationService).consumeInviteToken(isNull(), eq(profileCaptor.getValue().getId()));
    }

    @Test
    @DisplayName(
            "Q5.5: creatorRegister forwards a non-blank inviteToken to"
                    + " RegistrationService#consumeInviteToken with the newly created CreatorProfile's"
                    + " id, after the profile is persisted")
    void testCreatorRegisterConsumesInviteToken() {
        CreatorRegisterRequest withInvite =
                new CreatorRegisterRequest(
                        "riya@example.com", "Supersecret1", "Riya", "Sharma", null, true,
                        "signed-invite-token", "9876500001");
        when(userRepository.existsByEmailIgnoreCase(withInvite.email())).thenReturn(false);
        when(passwordEncoder.encode("Supersecret1")).thenReturn("hashed-pw");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(anyString(), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.creatorRegister(withInvite);

        ArgumentCaptor<CreatorProfile> profileCaptor = ArgumentCaptor.forClass(CreatorProfile.class);
        verify(creatorProfileRepository).save(profileCaptor.capture());
        verify(registrationService)
                .consumeInviteToken("signed-invite-token", profileCaptor.getValue().getId());
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
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenPair pair = authService.creatorRegister(CREATOR_REQUEST);

        assertTrue(pair.user().emailVerified());
        verify(brandEmailOtpService).requireVerifiedEmail(CREATOR_REQUEST.email());
    }

    @Test
    @DisplayName("creatorRegister G-Kv3-1: weak password rejected before any persistence")
    void testCreatorRegisterWeakPassword() {
        // PHONE-0906 -- deliberately left on the phone-less 6-arg constructor. This request would
        // ALSO fail PHONE_REQUIRED, so the assertion below only holds while password validation
        // still runs first; that ordering is the thing this test now pins.
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

    // ── F-0551: rememberMe controls refresh-token lifetime server-side ──────

    @Test
    @DisplayName(
            "F-0551: creatorLogin(rememberMe=true) persists a refresh token flagged remembered=true"
                    + " with an expiry drawn from the REMEMBERED (long) lifetime")
    void testCreatorLoginRememberedUsesLongLifetime() {
        User user = creatorUser(true);
        LoginRequest remembered = new LoginRequest("riya@example.com", "Supersecret1", true);
        when(userRepository.findByEmailIgnoreCase(remembered.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds(true)).thenReturn(2_592_000L);
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(tokenCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        authService.creatorLogin(remembered);

        RefreshToken saved = tokenCaptor.getValue();
        assertTrue(saved.isRemembered());
        long secondsUntilExpiry =
                java.time.Duration.between(Instant.now(), saved.getExpiresAt()).getSeconds();
        assertTrue(
                secondsUntilExpiry > 86_400L,
                "remembered login must get the long (30-day) lifetime, not the short one");
        verify(jwtService, never()).getRefreshExpirySeconds(false);
    }

    @Test
    @DisplayName(
            "F-0551: creatorLogin(rememberMe=false) persists a refresh token flagged"
                    + " remembered=false with a SHORTER expiry than a remembered login gets --"
                    + " distinct Max-Age from the remembered case")
    void testCreatorLoginNotRememberedUsesShortLifetime() {
        User user = creatorUser(true);
        LoginRequest notRemembered = new LoginRequest("riya@example.com", "Supersecret1", false);
        when(userRepository.findByEmailIgnoreCase(notRemembered.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds(false)).thenReturn(86_400L);
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(tokenCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        authService.creatorLogin(notRemembered);

        RefreshToken saved = tokenCaptor.getValue();
        assertFalse(saved.isRemembered());
        long secondsUntilExpiry =
                java.time.Duration.between(Instant.now(), saved.getExpiresAt()).getSeconds();
        assertTrue(
                secondsUntilExpiry <= 86_400L,
                "not-remembered login must get the short (24h) lifetime, never the 30-day one");
        verify(jwtService, never()).getRefreshExpirySeconds(true);
    }

    @Test
    @DisplayName(
            "F-0551: creatorLogin with rememberMe omitted from the request (legacy 2-arg"
                    + " LoginRequest) defaults to remembered -- unchanged behavior for a client"
                    + " that predates this field")
    void testCreatorLoginAbsentRememberMeDefaultsToRemembered() {
        User user = creatorUser(true);
        LoginRequest legacy = new LoginRequest("riya@example.com", "Supersecret1");
        assertNull(legacy.rememberMe(), "fixture must actually omit the field to prove the default");
        when(userRepository.findByEmailIgnoreCase(legacy.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds(true)).thenReturn(2_592_000L);
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(tokenCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        authService.creatorLogin(legacy);

        assertTrue(
                tokenCaptor.getValue().isRemembered(),
                "an absent rememberMe field must default to remembered=true (back-compat)");
    }

    @Test
    @DisplayName(
            "F-0551: refresh() preserves a NOT-remembered session's choice across rotation -- must"
                    + " NOT silently upgrade it to remembered")
    void testRefreshPreservesNotRememberedAcrossRotation() {
        User user = creatorUser(true);
        String raw = "old-refresh-raw";
        String hash = JwtService.hashToken(raw);
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESHTOKEN123456789A",
                        user.getId(),
                        hash,
                        Instant.now().plusSeconds(3600),
                        false); // issued as NOT remembered
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.empty());
        when(jwtService.createRefreshTokenValue()).thenReturn("new-refresh-raw");
        when(jwtService.getRefreshExpirySeconds(false)).thenReturn(86_400L);
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("new-access");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(tokenCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        AuthService.RefreshRotation rotation = authService.refresh(raw);

        assertFalse(rotation.remembered(), "rotation must report the ORIGINAL not-remembered choice");
        // save() #1 is the revoked `stored` row being re-saved; save() #2 is the new replacement.
        RefreshToken newToken = tokenCaptor.getAllValues().get(1);
        assertFalse(newToken.isRemembered(), "rotated replacement token must stay NOT-remembered");
        verify(jwtService, never()).getRefreshExpirySeconds(true);
    }

    @Test
    @DisplayName(
            "F-0551: refresh() preserves a REMEMBERED session's choice across rotation")
    void testRefreshPreservesRememberedAcrossRotation() {
        User user = creatorUser(true);
        String raw = "old-refresh-raw";
        String hash = JwtService.hashToken(raw);
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESHTOKEN123456789A",
                        user.getId(),
                        hash,
                        Instant.now().plusSeconds(3600),
                        true); // issued as remembered
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.empty());
        when(jwtService.createRefreshTokenValue()).thenReturn("new-refresh-raw");
        when(jwtService.getRefreshExpirySeconds(true)).thenReturn(2_592_000L);
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.CREATOR), anyString(), isNull()))
                .thenReturn("new-access");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(tokenCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        AuthService.RefreshRotation rotation = authService.refresh(raw);

        assertTrue(rotation.remembered(), "rotation must report the ORIGINAL remembered choice");
        RefreshToken newToken = tokenCaptor.getAllValues().get(1);
        assertTrue(newToken.isRemembered(), "rotated replacement token must stay remembered");
        verify(jwtService, never()).getRefreshExpirySeconds(false);
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
                        Instant.now().plusSeconds(3600), true);
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.empty());
        when(jwtService.createRefreshTokenValue()).thenReturn("new-refresh-raw");
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
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
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600), true);
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
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600), true);
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(raw));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertFalse(stored.isRevoked());
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
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600), true);
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh(raw));

        assertEquals("EMAIL_NOT_VERIFIED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertFalse(stored.isRevoked());
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
                        "01HREFRESHTOKEN123456789A", user.getId(), hash, Instant.now().plusSeconds(3600), true);
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(hash))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.empty());
        when(jwtService.createRefreshTokenValue()).thenReturn("new-refresh-raw");
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
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
    @DisplayName(
            "resetPassword F-FORGOTPW-0907: a PENDING_VERIFICATION user is verified and ACTIVE after reset")
    void testResetPasswordVerifiesPendingAccount() {
        // creatorUser(false) leaves the account exactly as User.newCreator builds it:
        // emailVerified=false, status=PENDING_VERIFICATION — the state every account stranded by
        // F-0601 is sitting in, and the state the login gate refuses.
        User user = creatorUser(false);
        assertFalse(user.isEmailVerified());
        assertEquals(UserStatus.PENDING_VERIFICATION, user.getStatus());

        String rawToken = "reset-raw-token-pending";
        PasswordResetToken token =
                PasswordResetToken.create(
                        "01HRESETTOKENPENDING12345",
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

        // Before this fix both assertions below failed: the password really was changed, and the
        // account stayed PENDING_VERIFICATION, so the very next login threw EMAIL_NOT_VERIFIED.
        assertTrue(user.isEmailVerified(), "a completed reset proves control of the mailbox");
        assertEquals(
                UserStatus.ACTIVE,
                user.getStatus(),
                "the account must be loginable after a successful reset");
        assertEquals("new-hash", user.getPasswordHash());
    }

    /**
     * The token is deliberately VALID in both cases below. A reset that dies on a bad token proves
     * nothing about the status guard — it would pass just as happily with the guard deleted. These
     * drive a good token at a locked account so the only thing that can refuse it is the status
     * check itself.
     */
    @Test
    @DisplayName("resetPassword F-0693: SUSPENDED account → ACCOUNT_SUSPENDED 403, nothing mutated")
    void testResetPasswordSuspendedAccount() throws Exception {
        User user = creatorUser(true);
        setUserStatus(user, UserStatus.SUSPENDED);
        String rawToken = "reset-raw-token-suspended";
        PasswordResetToken token =
                PasswordResetToken.create(
                        "01HRESETTOKENSUSPEND12345",
                        user.getId(),
                        JwtService.hashToken(rawToken),
                        Instant.now().plusSeconds(3600));
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalse(JwtService.hashToken(rawToken)))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> authService.resetPassword(rawToken, "NewSecret9"));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // The refusal must leave no trace: an unburned token (so a later reinstated user can still
        // use it), the old password, and no revoked sessions.
        assertFalse(token.isUsed());
        assertEquals("hashed-pw", user.getPasswordHash());
        assertEquals(UserStatus.SUSPENDED, user.getStatus());
        verify(userRepository, never()).save(any(User.class));
        verify(refreshTokenRepository, never()).revokeAllForUser(anyString());
    }

    @Test
    @DisplayName("resetPassword F-0693: DEACTIVATED account → ACCOUNT_SUSPENDED 403, nothing mutated")
    void testResetPasswordDeactivatedAccount() throws Exception {
        User user = creatorUser(true);
        setUserStatus(user, UserStatus.DEACTIVATED);
        String rawToken = "reset-raw-token-deactivated";
        PasswordResetToken token =
                PasswordResetToken.create(
                        "01HRESETTOKENDEACTIV12345",
                        user.getId(),
                        JwtService.hashToken(rawToken),
                        Instant.now().plusSeconds(3600));
        when(passwordResetTokenRepository.findByTokenHashAndUsedFalse(JwtService.hashToken(rawToken)))
                .thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> authService.resetPassword(rawToken, "NewSecret9"));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertFalse(token.isUsed());
        assertEquals("hashed-pw", user.getPasswordHash());
        verify(userRepository, never()).save(any(User.class));
        verify(refreshTokenRepository, never()).revokeAllForUser(anyString());
    }

    /**
     * The counterpart to the two above: the guard must refuse ONLY the two locked states. Without
     * this, "reject everything" would satisfy both tests above and silently break every real reset
     * — including the PENDING_VERIFICATION recovery path F-0693 exists to keep working.
     */
    @Test
    @DisplayName("resetPassword F-0693: an ACTIVE account is still allowed through the guard")
    void testResetPasswordActiveAccountUnaffected() throws Exception {
        User user = creatorUser(true);
        setUserStatus(user, UserStatus.ACTIVE);
        String rawToken = "reset-raw-token-active";
        PasswordResetToken token =
                PasswordResetToken.create(
                        "01HRESETTOKENACTIVE123456",
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
                        "01HKEEPTOKEN123456789ABCD", user.getId(), tokenHash, Instant.now().plusSeconds(3600), true);
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

    // ── F-0451: suspension must actually stop authentication ────────────────
    // Before this fix, workspaces.is_suspended was read only by WorkspaceService.switchWorkspace
    // -- which has NO production caller (F-0457). The reachable twin is
    // WorkspaceMemberService.switchWorkspace via WorkspaceMemberController:105.
    // and creator_profiles.is_suspended only by discovery/deal filters, so admin "suspend"
    // hid the account from the marketplace while it kept logging in and kept rotating tokens.

    private static final LoginRequest BRAND_LOGIN =
            new LoginRequest("ada@example.com", "Supersecret1");

    private User brandLoginUser() {
        User user =
                User.newBrand(
                        "01HBRANDUSER1234567890AA",
                        "ada@example.com",
                        "hashed-pw",
                        "Ada",
                        "Lovelace",
                        "Ada Lovelace");
        user.setEmailVerified(true);
        return user;
    }

    private Workspace suspendedBrandWorkspace() {
        Workspace ws = Workspace.newBrand("01HWORKSPACE123456789AA", "Acme Co", "acme-co", "RETAIL", "SMALL");
        ws.suspend("fraud review", "01HADMIN12345678901234AA");
        return ws;
    }

    @Test
    @DisplayName("F-0451: brandLogin on a SUSPENDED workspace → WORKSPACE_SUSPENDED 403")
    void testBrandLoginSuspendedWorkspace() {
        User user = brandLoginUser();
        Workspace ws = suspendedBrandWorkspace();
        when(userRepository.findByEmailIgnoreCase(BRAND_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(
                        java.util.List.of(
                                WorkspaceMember.owner("01HMEMBER123456789012AA", ws.getId(), user.getId())));
        when(workspaceRepository.findById(ws.getId())).thenReturn(Optional.of(ws));

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandLogin(BRAND_LOGIN));

        assertEquals("WORKSPACE_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // no token minted and no login stamped: the refusal must be total, not cosmetic
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("F-0451: creatorLogin with a SUSPENDED creator profile → ACCOUNT_SUSPENDED 403")
    void testCreatorLoginSuspendedProfile() {
        User user = creatorUser(true);
        CreatorProfile profile =
                CreatorProfile.newForUser("01HCREATORPROF123456AA", user.getId(), "Riya Sharma");
        profile.suspend("policy violation", "01HADMIN12345678901234AA");
        when(userRepository.findByEmailIgnoreCase(CREATOR_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(creatorProfileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

        ApiException ex = assertThrows(ApiException.class, () -> authService.creatorLogin(CREATOR_LOGIN));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("F-0451: refresh on a SUSPENDED workspace → WORKSPACE_SUSPENDED 403, token NOT burned")
    void testRefreshSuspendedWorkspace() {
        User user = brandLoginUser();
        Workspace ws = suspendedBrandWorkspace();
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESH1234567890AA",
                        user.getId(),
                        JwtService.hashToken("refresh-raw"),
                        Instant.now().plusSeconds(3600), true);
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(JwtService.hashToken("refresh-raw")))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.of(WorkspaceMember.owner("01HMEMBER123456789012AA", ws.getId(), user.getId())));
        when(workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(
                        java.util.List.of(
                                WorkspaceMember.owner("01HMEMBER123456789012AA", ws.getId(), user.getId())));
        when(workspaceRepository.findById(ws.getId())).thenReturn(Optional.of(ws));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh("refresh-raw"));

        assertEquals("WORKSPACE_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // the presented token must survive a rejected refresh, so the retry shows the real reason
        assertFalse(stored.isRevoked());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("F-0451: refresh for a SUSPENDED creator → ACCOUNT_SUSPENDED 403, token NOT burned")
    void testRefreshSuspendedCreator() {
        User user = creatorUser(true);
        CreatorProfile profile =
                CreatorProfile.newForUser("01HCREATORPROF123456AA", user.getId(), "Riya Sharma");
        profile.suspend("policy violation", "01HADMIN12345678901234AA");
        RefreshToken stored =
                RefreshToken.create(
                        "01HREFRESH1234567890AB",
                        user.getId(),
                        JwtService.hashToken("refresh-raw"),
                        Instant.now().plusSeconds(3600), true);
        when(refreshTokenRepository.findByTokenHashAndRevokedFalse(JwtService.hashToken("refresh-raw")))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(workspaceMemberRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(Optional.empty());
        when(creatorProfileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

        ApiException ex = assertThrows(ApiException.class, () -> authService.refresh("refresh-raw"));

        assertEquals("ACCOUNT_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        assertFalse(stored.isRevoked());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName(
            "F-0458: multi-workspace user whose OLDEST workspace is suspended still logs in,"
                    + " landing in the first non-suspended one")
    void testBrandLoginMultiWorkspaceSkipsSuspended() {
        User user = brandLoginUser();
        Workspace suspended = suspendedBrandWorkspace();
        Workspace healthy =
                Workspace.newBrand("01HWORKSPACE123456789BB", "Beta Co", "beta-co", "RETAIL", "SMALL");
        when(userRepository.findByEmailIgnoreCase(BRAND_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(
                        java.util.List.of(
                                WorkspaceMember.owner("01HMEMBER123456789012AA", suspended.getId(), user.getId()),
                                WorkspaceMember.owner("01HMEMBER123456789012BB", healthy.getId(), user.getId())));
        when(workspaceRepository.findById(suspended.getId())).thenReturn(Optional.of(suspended));
        when(workspaceRepository.findById(healthy.getId())).thenReturn(Optional.of(healthy));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createAccessToken(eq(user.getId()), eq(UserType.BRAND), anyString(), eq(healthy.getId())))
                .thenReturn("access-jwt");
        when(jwtService.createRefreshTokenValue()).thenReturn("refresh-raw");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirySeconds(anyBoolean())).thenReturn(2_592_000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenPair pair = authService.brandLogin(BRAND_LOGIN);

        // the admin suspended ONE workspace, not the person: they must land in the other
        assertEquals("access-jwt", pair.accessToken());
        assertEquals(healthy.getId(), pair.workspace().id());
    }

    @Test
    @DisplayName("F-0458: when EVERY active workspace is suspended, brandLogin still refuses")
    void testBrandLoginAllWorkspacesSuspendedStillRefused() {
        User user = brandLoginUser();
        Workspace suspendedA = suspendedBrandWorkspace();
        Workspace suspendedB =
                Workspace.newBrand("01HWORKSPACE123456789CC", "Gamma Co", "gamma-co", "RETAIL", "SMALL");
        suspendedB.suspend("fraud review", "01HADMIN12345678901234AA");
        when(userRepository.findByEmailIgnoreCase(BRAND_LOGIN.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Supersecret1", "hashed-pw")).thenReturn(true);
        when(workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId()))
                .thenReturn(
                        java.util.List.of(
                                WorkspaceMember.owner("01HMEMBER123456789012AA", suspendedA.getId(), user.getId()),
                                WorkspaceMember.owner("01HMEMBER123456789012CC", suspendedB.getId(), user.getId())));
        when(workspaceRepository.findById(suspendedA.getId())).thenReturn(Optional.of(suspendedA));
        when(workspaceRepository.findById(suspendedB.getId())).thenReturn(Optional.of(suspendedB));

        ApiException ex = assertThrows(ApiException.class, () -> authService.brandLogin(BRAND_LOGIN));

        assertEquals("WORKSPACE_SUSPENDED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
    }
}
