package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.User;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.payout.CreatorBankAccountService;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorIdResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorKycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorProfileRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorSocialRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * N1 (Wave 6) — covers the parts of CreatorOnboardingService with actual branching logic: the
 * rateMin/rateMax guard, the KYC persistence (reusing the D14 `pan` column + new identity-KYC
 * columns), and R2 doc-URL resolution. The UPI/bank payout-routing tests that used to live here
 * were removed under F-0450 along with {@code savePayout(...)} itself — see that method's note.
 */
@ExtendWith(MockitoExtension.class)
class CreatorOnboardingServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";

    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreatorBankAccountService creatorBankAccountService;
    @Mock private R2StorageService r2StorageService;
    @Mock private R2Properties r2Properties;
    @Mock private AuthPrincipal principal;

    private CreatorOnboardingService service;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        // Real UserPhoneService wired onto the same mocked userRepository — PHONE-0904.
        service =
                new CreatorOnboardingService(
                        creatorContext,
                        creatorProfileRepository,
                        userRepository,
                        creatorBankAccountService,
                        new UserPhoneService(userRepository),
                        r2StorageService,
                        r2Properties);
        profile = CreatorProfile.newForUser("prof_1", CREATOR_USER_ID, "Priya Creates");
        // lenient: not every test below reads this stub (e.g. testResolveKycDocUrlPresignsBareKey
        // exercises a static-file path that never resolves a creator profile).
        org.mockito.Mockito.lenient().when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
    }

    @Test
    @DisplayName(
            "connectSocial (CR-108) refuses every platform with a typed NOT_IMPLEMENTED instead of"
                    + " fabricating a followers(0)/verified(false) row")
    void testConnectSocialRefusesFabricatedConnectionForUnimplementedPlatform() {
        CreatorSocialRequest req = new CreatorSocialRequest("YOUTUBE", "mock_oauth_code");

        ApiException ex = assertThrows(ApiException.class, () -> service.connectSocial(principal, req));

        assertEquals("SOCIAL_OAUTH_NOT_IMPLEMENTED", ex.getCode());
        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatus());
        verify(creatorContext).requireCreatorProfile(principal);
    }

    @Test
    @DisplayName("connectSocial (CR-108) refuses INSTAGRAM here too — that platform's real OAuth is"
            + " a separate dedicated endpoint (MetaOAuthController), not this one")
    void testConnectSocialRefusesInstagramToo() {
        CreatorSocialRequest req = new CreatorSocialRequest("INSTAGRAM", "mock_oauth_code");

        ApiException ex = assertThrows(ApiException.class, () -> service.connectSocial(principal, req));

        assertEquals("SOCIAL_OAUTH_NOT_IMPLEMENTED", ex.getCode());
    }

    @Test
    @DisplayName("saveProfile rejects rateMin > rateMax before touching the repository")
    void testSaveProfileRejectsInvertedRateRange() {
        CreatorProfileRequest req =
                new CreatorProfileRequest(
                        "Priya",
                        null,
                        List.of(),
                        List.of(),
                        null,
                        new BigDecimal("20000"),
                        new BigDecimal("5000"),
                        null);

        ApiException ex = assertThrows(ApiException.class, () -> service.saveProfile(principal, req));
        assertEquals("INVALID_RATE_RANGE", ex.getCode());
        verify(creatorProfileRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------------------
    // PHONE-0904 — creator onboarding phone capture, shared with CreatorProfileService via
    // UserPhoneService. displayName/rates are required fields on this DTO, so every test below
    // supplies a valid rate range.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("saveProfile: null phone leaves users.phone_number untouched (no user lookup at all)")
    void testSaveProfileNullPhoneDoesNotTouchUser() {
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));

        CreatorIdResponse response = service.saveProfile(principal, profileRequest(null));

        assertEquals("prof_1", response.creatorId());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName(
            "saveProfile: blank phone (\"\") is a no-op too, same as null — D2 (Priya CTO review):"
                    + " an onboarding wizard must never clear a number set through another surface")
    void testSaveProfileBlankPhoneDoesNotTouchUser() {
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));

        CreatorIdResponse response = service.saveProfile(principal, profileRequest(""));

        assertEquals("prof_1", response.creatorId());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("saveProfile: sets phone, normalizing +91 and spaces, after the profile row is saved")
    void testSaveProfileSetsPhoneNormalized() {
        User user = realUser();
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(userRepository.findById(CREATOR_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(false);

        service.saveProfile(principal, profileRequest("+91 98765 43210"));

        assertEquals("9876543210", user.getPhoneNumber());
        // D1 regression guard: the non-blank write path MUST flush (see UserPhoneService#applyPhone
        // javadoc) — plain save() would make the raced-duplicate 409 unreachable in production.
        verify(userRepository).saveAndFlush(user);
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("saveProfile: normalizes a leading-0 trunk prefix to the same 10 digits")
    void testSaveProfileNormalizesLeadingZero() {
        User user = realUser();
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(userRepository.findById(CREATOR_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(false);

        service.saveProfile(principal, profileRequest("09876543210"));

        assertEquals("9876543210", user.getPhoneNumber());
    }

    @Test
    @DisplayName("saveProfile: rejects invalid phone format with INVALID_PHONE/400")
    void testSaveProfileRejectsInvalidPhone() {
        User user = realUser();
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(userRepository.findById(CREATOR_USER_ID)).thenReturn(Optional.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.saveProfile(principal, profileRequest("12345")));

        assertEquals("INVALID_PHONE", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("saveProfile: duplicate phone returns a clean PHONE_ALREADY_EXISTS/409")
    void testSaveProfileDuplicatePhoneReturns409() {
        User user = realUser();
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(principal.getUserId()).thenReturn(CREATOR_USER_ID);
        when(userRepository.findById(CREATOR_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.saveProfile(principal, profileRequest("9876543210")));

        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName(
            "saveProfile: an inverted rate range is rejected before the phone (on users) is ever"
                    + " looked up, even when both fields on the request are bad")
    void testSaveProfileRateRangeRejectedBeforePhoneLookup() {
        CreatorProfileRequest req =
                new CreatorProfileRequest(
                        "Priya",
                        null,
                        List.of(),
                        List.of(),
                        null,
                        new BigDecimal("20000"),
                        new BigDecimal("5000"),
                        "not-a-phone");

        ApiException ex = assertThrows(ApiException.class, () -> service.saveProfile(principal, req));

        assertEquals("INVALID_RATE_RANGE", ex.getCode());
        verify(userRepository, never()).findById(any());
    }

    private CreatorProfileRequest profileRequest(String phone) {
        return new CreatorProfileRequest(
                "Priya Creates",
                "bio",
                List.of("Fashion & Lifestyle"),
                List.of("Hindi"),
                "Mumbai",
                new BigDecimal("5000"),
                new BigDecimal("15000"),
                phone);
    }

    private User realUser() {
        return User.newCreator(
                CREATOR_USER_ID, "creator@example.com", "hash", "Priya", "Sharma", "Priya Sharma");
    }

    @Test
    @DisplayName("submitKyc reuses the D14 pan column and moves identityKycStatus to PENDING")
    void testSubmitKycPersistsAndReturnsPending() {
        CreatorKycRequest req = new CreatorKycRequest("abcde1234f", "1234", "creators/x/selfie/1");

        KycResponse response = service.submitKyc(principal, req);

        assertEquals("PENDING", response.kycStatus());
        assertEquals("ABCDE1234F", profile.getPan());
        assertEquals("1234", profile.getAadhaarLast4());
        verify(creatorProfileRepository).save(profile);
    }

    // [F-0450] The three savePayout(...) tests that lived here were removed with the method
    // itself — see CreatorOnboardingService's F-0450 note. WalletController's payout-methods
    // path is the live route through the same CreatorBankAccountService.addInstrument(...)
    // writer these tests exercised; it is covered separately.

    @Test
    @DisplayName("[F-0390 D4] resolveKycDocUrl: a bare R2 key (new, post-D4 upload) resolves to a presigned GET")
    void testResolveKycDocUrlPresignsBareKey() {
        when(r2StorageService.isAvailable()).thenReturn(true);
        when(r2StorageService.presignGet("uploads/creator/u1/selfie.png"))
                .thenReturn(
                        new com.influora.integration.storage.R2StorageService.PresignResult(
                                "https://r2.influora.com/presigned/uploads/creator/u1/selfie.png",
                                "uploads/creator/u1/selfie.png",
                                "influora-dev",
                                Instant.now().plusSeconds(900),
                                0L));

        String resolved = service.resolveKycDocUrl("uploads/creator/u1/selfie.png");

        assertEquals("https://r2.influora.com/presigned/uploads/creator/u1/selfie.png", resolved);
    }

    @Test
    @DisplayName(
            "[F-0390 D4] resolveKycDocUrl: a legacy absolute public URL that does not match the"
                    + " configured R2 public base passes through unchanged")
    void testResolveKycDocUrlPassesThroughUnmatchedLegacyUrl() {
        when(r2Properties.getPublicUrl()).thenReturn("https://r2.influora.com");

        String resolved = service.resolveKycDocUrl("https://some-other-cdn.example.com/old-selfie.png");

        assertEquals("https://some-other-cdn.example.com/old-selfie.png", resolved);
        verify(r2StorageService, never()).presignGet(any());
    }

    @Test
    @DisplayName(
            "[F-0390 D4] resolveKycDocUrl: a legacy absolute URL matching the configured R2 public"
                    + " base is still resolved to a fresh presigned GET, not left permanently public")
    void testResolveKycDocUrlResolvesMatchingLegacyUrl() {
        when(r2Properties.getPublicUrl()).thenReturn("https://r2.influora.com");
        when(r2StorageService.isAvailable()).thenReturn(true);
        when(r2StorageService.presignGet("uploads/creator/u1/legacy-selfie.png"))
                .thenReturn(
                        new com.influora.integration.storage.R2StorageService.PresignResult(
                                "https://r2.influora.com/presigned/uploads/creator/u1/legacy-selfie.png",
                                "uploads/creator/u1/legacy-selfie.png",
                                "influora-dev",
                                Instant.now().plusSeconds(900),
                                0L));

        String resolved =
                service.resolveKycDocUrl("https://r2.influora.com/uploads/creator/u1/legacy-selfie.png");

        assertEquals("https://r2.influora.com/presigned/uploads/creator/u1/legacy-selfie.png", resolved);
    }

    @Test
    @DisplayName("[F-0390 D4] resolveKycDocUrl: null/blank input returns null")
    void testResolveKycDocUrlNullForBlank() {
        assertEquals(null, service.resolveKycDocUrl(null));
        assertEquals(null, service.resolveKycDocUrl(""));
    }
}
