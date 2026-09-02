package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.User;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfilePatchRequest;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfileSelfResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class CreatorProfileServiceTest {

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";

    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthPrincipal principal;

    private CreatorProfileService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorProfileService(
                        creatorContext,
                        creatorProfileRepository,
                        platformStatRepository,
                        userRepository);
    }

    @Test
    @DisplayName("getMyProfile: returns self response with completeness score")
    void testGetMyProfile() {
        CreatorProfile profile = profile("Priya Creates", null);
        User user = user(false);

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CreatorProfileSelfResponse response = service.getMyProfile(principal);

        assertEquals(PROFILE_ID, response.id());
        assertEquals("Priya Creates", response.displayName());
        // getMyProfile now auto-assigns a username via ensureUsername() (P-1 fix), which
        // contributes +10 to completeness — so a name-only profile scores 20, not 10.
        assertEquals(20, response.profileCompleteness());
        assertEquals(false, response.onboardingComplete());
    }

    @Test
    @DisplayName("patchMyProfile: updates editable fields and persists")
    void testPatchMyProfile() {
        CreatorProfile profile = profile("Old Name", null);
        User user = user(true);

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CreatorProfilePatchRequest patch =
                new CreatorProfilePatchRequest(
                        "New Name",
                        "priya_creates",
                        "Bio text",
                        null,
                        null,
                        "Mumbai",
                        List.of("Fashion"),
                        List.of("English"),
                        null,
                        BigDecimal.valueOf(15000),
                        BigDecimal.valueOf(25000),
                        true,
                        null);

        CreatorProfileSelfResponse response = service.patchMyProfile(principal, patch);

        assertEquals("New Name", response.displayName());
        assertEquals("priya_creates", response.username());
        assertEquals("Mumbai", response.city());
        assertEquals(true, response.discoverable());
        verify(creatorProfileRepository).save(profile);
    }

    @Test
    @DisplayName("patchMyProfile: rejects invalid rate range")
    void testPatchRejectsInvalidRateRange() {
        CreatorProfile profile = profile("Name", null);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);

        CreatorProfilePatchRequest patch =
                new CreatorProfilePatchRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.valueOf(50000),
                        BigDecimal.valueOf(10000),
                        null,
                        null);

        assertThrows(ApiException.class, () -> service.patchMyProfile(principal, patch));
    }

    @Test
    @DisplayName("patchMyProfile: rejects invalid username")
    void testPatchRejectsInvalidUsername() {
        CreatorProfile profile = profile("Name", null);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);

        CreatorProfilePatchRequest patch =
                new CreatorProfilePatchRequest(
                        null,
                        "__",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.patchMyProfile(principal, patch));
        assertEquals("INVALID_USERNAME", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("patchMyProfile: rejects taken username")
    void testPatchRejectsTakenUsername() {
        CreatorProfile profile = profile("Name", null);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(creatorProfileRepository.existsByUsernameIgnoreCaseAndIdNot(eq("taken_user"), eq(PROFILE_ID)))
                .thenReturn(true);

        CreatorProfilePatchRequest patch =
                new CreatorProfilePatchRequest(
                        null,
                        "taken_user",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.patchMyProfile(principal, patch));
        assertEquals("USERNAME_TAKEN", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    // ---------------------------------------------------------------------------------------
    // PHONE-0829 — creator phone set/update/clear, invalid format, normalization, duplicate.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("patchMyProfile: sets phone, normalizing +91 and spaces")
    void testPatchSetsPhoneNormalized() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(false);

        CreatorProfileSelfResponse response =
                service.patchMyProfile(principal, patchWithPhone("+91 98765 43210"));

        assertEquals("9876543210", response.phone());
        assertEquals("9876543210", user.getPhoneNumber());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("patchMyProfile: normalizes a leading-0 trunk prefix")
    void testPatchSetsPhoneNormalizedLeadingZero() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(false);

        CreatorProfileSelfResponse response =
                service.patchMyProfile(principal, patchWithPhone("09876543210"));

        assertEquals("9876543210", response.phone());
    }

    @Test
    @DisplayName("patchMyProfile: re-saving the same already-owned phone is not treated as a duplicate")
    void testPatchSamePhoneNoOp() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();
        user.setPhoneNumber("9876543210");

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CreatorProfileSelfResponse response =
                service.patchMyProfile(principal, patchWithPhone("9876543210"));

        assertEquals("9876543210", response.phone());
        verify(userRepository, org.mockito.Mockito.never()).existsByPhoneNumber(any());
    }

    @Test
    @DisplayName("patchMyProfile: clears phone on explicit blank")
    void testPatchClearsPhone() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();
        user.setPhoneNumber("9876543210");

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CreatorProfileSelfResponse response = service.patchMyProfile(principal, patchWithPhone(""));

        assertEquals(null, response.phone());
        assertEquals(null, user.getPhoneNumber());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("patchMyProfile: rejects invalid phone format (not 10 digits / bad first digit)")
    void testPatchRejectsInvalidPhone() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.patchMyProfile(principal, patchWithPhone("12345")));
        assertEquals("INVALID_PHONE", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("patchMyProfile: rejects a landline-shaped number (first digit not 6-9)")
    void testPatchRejectsWrongFirstDigit() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.patchMyProfile(principal, patchWithPhone("1234567890")));
        assertEquals("INVALID_PHONE", ex.getCode());
    }

    @Test
    @DisplayName("patchMyProfile: duplicate phone returns a clean 409, not a raw 500")
    void testPatchDuplicatePhoneReturns409() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.patchMyProfile(principal, patchWithPhone("9876543210")));
        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    @DisplayName(
            "patchMyProfile: a raced duplicate phone (DB UNIQUE violation past the pre-check) still"
                    + " returns 409, not an uncaught 500")
    void testPatchPhoneRaceReturns409() {
        CreatorProfile profile = profile("Name", null);
        User user = realUser();

        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(creatorProfileRepository.save(any(CreatorProfile.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(false);
        when(userRepository.save(user)).thenThrow(new DataIntegrityViolationException("dup"));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.patchMyProfile(principal, patchWithPhone("9876543210")));
        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    private CreatorProfilePatchRequest patchWithPhone(String phone) {
        return new CreatorProfilePatchRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, phone);
    }

    private User realUser() {
        return User.newCreator(USER_ID, "creator@example.com", "hash", "Priya", "Sharma", "Priya Sharma");
    }

    private CreatorProfile profile(String displayName, String username) {
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, displayName);
        if (username != null) {
            profile.applyUsername(username);
        }
        return profile;
    }

    private User user(boolean onboardingComplete) {
        User user = org.mockito.Mockito.mock(User.class);
        when(user.isOnboardingCompleted()).thenReturn(onboardingComplete);
        return user;
    }
}
