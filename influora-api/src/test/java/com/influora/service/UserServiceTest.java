package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import com.influora.web.dto.user.UserDtos.UpdateProfileRequest;
import com.influora.web.dto.user.UserDtos.UserProfileDto;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PHONE-0904 Q1 ({@code wiki/reports/phone-0904-signoff-qa.md}) — {@code GET/PATCH /users/me} is
 * the self endpoint a brand uses to read and correct the mobile it registered with. The phone
 * branch here routes through the REAL {@link UserPhoneService} (wired over a mocked
 * {@link UserRepository}, same convention as {@code AuthServiceTest}/{@code
 * CreatorProfileServiceTest}) so these tests exercise the shared normalize/validate/dup-check/
 * saveAndFlush behavior, not a re-implementation of it.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;

    private UserService userService;

    private static final String BRAND_ID = "01HBRANDUSER1234567890A";
    private static final String CREATOR_ID = "01HCREATORUSER123456789";

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, new UserPhoneService(userRepository));
    }

    private User brandUser(String phone) {
        User user =
                User.newBrand(BRAND_ID, "brand@example.com", "hashed", "Ada", "Lovelace", "Ada Lovelace");
        user.setPhoneNumber(phone);
        return user;
    }

    private User creatorUser(String phone) {
        User user =
                User.newCreator(
                        CREATOR_ID, "creator@example.com", "hashed", "Riya", "Sharma", "Riya Sharma");
        user.setPhoneNumber(phone);
        return user;
    }

    private static UpdateProfileRequest phoneOnly(String phone) {
        return new UpdateProfileRequest(null, null, null, null, null, phone);
    }

    // ── GET /users/me — read path ───────────────────────────────────────────

    @Test
    @DisplayName("getProfile: returns the brand's own normalized phone")
    void testGetProfileReturnsBrandPhone() {
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(brandUser("9876543210")));

        UserProfileDto dto = userService.getProfile(BRAND_ID);

        assertEquals("9876543210", dto.phone());
    }

    @Test
    @DisplayName("getProfile: a legacy brand row with a NULL phone_number reads fine (phone == null, no error)")
    void testGetProfileLegacyNullPhoneReadsFine() {
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(brandUser(null)));

        UserProfileDto dto = userService.getProfile(BRAND_ID);

        assertNull(dto.phone());
    }

    // ── PATCH /users/me — write path (BRAND) ────────────────────────────────

    @Test
    @DisplayName("updateProfile: BRAND -- valid phone is normalized and persisted via saveAndFlush (never plain save)")
    void testUpdateProfilePersistsNormalizedPhoneForBrand() {
        User user = brandUser(null);
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileDto dto = userService.updateProfile(BRAND_ID, phoneOnly("+91 98765 43210"));

        assertEquals("9876543210", dto.phone());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertEquals("9876543210", captor.getValue().getPhoneNumber());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("updateProfile: BRAND -- blank phone is rejected as PHONE_REQUIRED, not silently cleared")
    void testUpdateProfileBrandBlankPhoneRejected() {
        User user = brandUser("9876543210");
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> userService.updateProfile(BRAND_ID, phoneOnly("")));

        assertEquals("PHONE_REQUIRED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("updateProfile: BRAND -- malformed phone rejected as INVALID_PHONE (shared UserPhoneService rule)")
    void testUpdateProfileBrandInvalidPhoneRejected() {
        User user = brandUser("9876543210");
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(user));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> userService.updateProfile(BRAND_ID, phoneOnly("12345")));

        assertEquals("INVALID_PHONE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("updateProfile: BRAND -- phone already owned by another account -> 409 PHONE_ALREADY_EXISTS")
    void testUpdateProfileBrandDuplicatePhoneReturns409() {
        User user = brandUser("9876543210");
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9123456780")).thenReturn(true);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> userService.updateProfile(BRAND_ID, phoneOnly("9123456780")));

        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName(
            "updateProfile: BRAND -- raced duplicate phone (DB UNIQUE fires on saveAndFlush) still"
                    + " surfaces the friendly 409, never a raw 500")
    void testUpdateProfileBrandPhoneRaceReturns409() {
        User user = brandUser(null);
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByPhoneNumber("9876543210"))
                .thenReturn(false) // upfront check: loser hasn't lost yet
                .thenReturn(true); // re-check inside the catch block: now it has
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'users.phone_number'"))
                .when(userRepository)
                .saveAndFlush(any(User.class));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> userService.updateProfile(BRAND_ID, phoneOnly("9876543210")));

        assertEquals("PHONE_ALREADY_EXISTS", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    @DisplayName("updateProfile: BRAND -- re-saving the SAME number the account already owns is not treated as a duplicate")
    void testUpdateProfileBrandResavingOwnPhoneIsNotADuplicate() {
        User user = brandUser("9876543210");
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileDto dto = userService.updateProfile(BRAND_ID, phoneOnly("9876543210"));

        assertEquals("9876543210", dto.phone());
        verify(userRepository, never()).existsByPhoneNumber(any());
    }

    @Test
    @DisplayName("updateProfile: BRAND -- a legacy row with a NULL phone still saves fine when phone is not in the patch")
    void testUpdateProfileLegacyNullPhoneUntouchedSavesFine() {
        User user = brandUser(null);
        when(userRepository.findById(BRAND_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProfileRequest req = new UpdateProfileRequest("Ada", null, null, null, null, null);
        UserProfileDto dto = userService.updateProfile(BRAND_ID, req);

        assertNull(dto.phone());
        verify(userRepository).save(any(User.class));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    // ── PATCH /users/me — CREATOR is unaffected (regression guard on the ruling) ────────────

    @Test
    @DisplayName(
            "updateProfile: CREATOR -- a non-null phone on this generic endpoint is REJECTED"
                    + " (400 PHONE_NOT_EDITABLE_HERE), not silently discarded, because a 200 with"
                    + " an unchanged phone in the body is indistinguishable from success"
                    + " (PHONE-0904 re-run, Priya, wiki/reports/phone-0904-signoff-qa.md ~L322)."
                    + " Creator phone stays managed via PATCH /me/creator-profile only.")
    void testUpdateProfileCreatorPhoneRejected() {
        User user = creatorUser(null);
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(user));

        UpdateProfileRequest req = new UpdateProfileRequest("Riya", null, null, null, null, "9876543210");
        ApiException ex =
                assertThrows(ApiException.class, () -> userService.updateProfile(CREATOR_ID, req));

        assertEquals("PHONE_NOT_EDITABLE_HERE", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName(
            "updateProfile: CREATOR -- phone == null (absent from the patch) remains a true no-op;"
                    + " other fields still apply via plain save() (proves the new reject-loudly"
                    + " rule does not over-reach into unrelated profile edits)")
    void testUpdateProfileCreatorNullPhoneStillSavesOtherFields() {
        User user = creatorUser(null);
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProfileRequest req = new UpdateProfileRequest("Riya", null, null, null, null, null);
        UserProfileDto dto = userService.updateProfile(CREATOR_ID, req);

        assertNull(dto.phone());
        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("Riya", captor.getValue().getFirstName());
        assertNull(captor.getValue().getPhoneNumber());
    }
}
