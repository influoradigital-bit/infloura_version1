package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.User;
import com.influora.domain.enums.UserType;
import com.influora.repository.UserRepository;
import com.influora.web.dto.user.UserDtos.UpdateProfileRequest;
import com.influora.web.dto.user.UserDtos.UserProfileDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserPhoneService userPhoneService;

    public UserService(UserRepository userRepository, UserPhoneService userPhoneService) {
        this.userRepository = userRepository;
        this.userPhoneService = userPhoneService;
    }

    public UserProfileDto getProfile(String userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
        return toDto(user);
    }

    @Transactional
    public UserProfileDto updateProfile(String userId, UpdateProfileRequest req) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));

        if (req.firstName() != null) {
            user.setFirstName(req.firstName());
        }
        if (req.lastName() != null) {
            user.setLastName(req.lastName());
        }
        if (req.displayName() != null) {
            user.setDisplayName(req.displayName());
        }
        if (req.timezone() != null) {
            user.setTimezone(req.timezone());
        }
        if (req.avatarUrl() != null) {
            user.setAvatarUrl(req.avatarUrl());
        }

        // PHONE-0904 Q1 — phone is honored on this generic self endpoint for BRAND callers only.
        // CREATOR/ADMIN already have (or don't need) their own phone write path -- most notably
        // CreatorProfileService#patchMyProfile, which routes the SAME UserPhoneService#applyPhone
        // but with different, intentional null-means-clear semantics for an optional field
        // (UserPhoneService's javadoc). Wiring this generic field through for creators too would
        // be a second write path for the same column with conflicting blank-handling -- exactly
        // what UserPhoneService was built to prevent.
        //
        // req.phone() == null means "leave unchanged" (this record's usual convention). A BRAND
        // caller sending a blank string is rejected with PHONE_REQUIRED rather than treated as
        // "clear it": brand phone became mandatory at registration (PHONE-0904 Q8), so there is no
        // longer a valid cleared state for a brand to fall back into on this path -- unlike
        // creator Settings' own blank-clears-it flow on a different endpoint/column-owner pairing.
        //
        // PHONE-0904 re-run (Priya, 2026-09-04, wiki/reports/phone-0904-signoff-qa.md ~L322) —
        // a non-BRAND caller sending a NON-NULL phone here must be rejected loudly, not silently
        // discarded: this endpoint returns 200 with a toDto() body echoing the unchanged phone, so
        // a silent no-op is indistinguishable from success and the client can never detect the
        // drop. The "no second write path" reasoning above still holds -- that justifies not
        // WRITING creator/admin phone here, it does not justify not ERRORING. A null/absent phone
        // from a non-BRAND caller remains a true no-op (nothing was asked of this field).
        if (req.phone() != null && user.getUserType() != UserType.BRAND) {
            throw new ApiException(
                    "PHONE_NOT_EDITABLE_HERE",
                    "Phone number cannot be edited on this endpoint. Use PATCH /me/creator-profile"
                            + " instead.",
                    HttpStatus.BAD_REQUEST);
        }

        if (req.phone() != null && user.getUserType() == UserType.BRAND) {
            if (req.phone().isBlank()) {
                throw new ApiException(
                        "PHONE_REQUIRED", "Phone number is required", HttpStatus.BAD_REQUEST);
            }
            // applyPhone normalizes, validates, dup-checks (PHONE_ALREADY_EXISTS/409 against any
            // OTHER account), and persists (saveAndFlush + TOCTOU catch) the whole managed `user`
            // row in one call -- including the other field mutations already applied above -- so
            // this replaces the generic save() below rather than stacking a second write on it.
            userPhoneService.applyPhone(user, req.phone());
        } else {
            userRepository.save(user);
        }

        return toDto(user);
    }

    private static UserProfileDto toDto(User user) {
        return new UserProfileDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getFirstName(),
                user.getLastName(),
                user.getUserType(),
                user.getStatus(),
                user.getAvatarUrl(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getPhoneNumber(),
                user.getTimezone(),
                user.getCreatedAt());
    }
}
