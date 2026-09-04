package com.influora.web.dto.user;

import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public final class UserDtos {

    private UserDtos() {}

    /**
     * PHONE-0904 Q1 ({@code wiki/reports/phone-0904-signoff-qa.md}) — {@code phone} added so a
     * brand (now that its mobile is REQUIRED, PHONE-0904 Q8) can read the number it registered
     * with. This is the SELF endpoint only ({@code GET /users/me}, guarded by
     * {@code @AuthenticationPrincipal} so it can only ever be the caller's own row) — do not reuse
     * this record, or add {@code phone}, anywhere a different user's row is serialized. Sign-off
     * Q10 grepped every other {@code User.phoneNumber} read site (public creator profile,
     * brand-facing creator cards, discovery, admin, the Meera/AI context assembler) and confirmed
     * none of them touch it; re-grep after any change here to keep that true.
     */
    public record UserProfileDto(
            String id,
            String email,
            String displayName,
            String firstName,
            String lastName,
            UserType userType,
            UserStatus status,
            String avatarUrl,
            boolean emailVerified,
            boolean phoneVerified,
            String phone,
            String timezone,
            Instant createdAt) {}

    /**
     * {@code phone}: PHONE-0904 Q1 — null means "leave unchanged" (same convention every other
     * field on this record already uses). Routed through {@link
     * com.influora.service.UserPhoneService#applyPhone} by {@code UserService#updateProfile} so
     * normalization/{@code INVALID_PHONE}/{@code PHONE_ALREADY_EXISTS}/TOCTOU handling can never
     * drift from the other three phone-write call sites — see that method for why this is honored
     * for BRAND callers only. A BRAND caller sending a blank string gets {@code PHONE_REQUIRED}
     * (brand phone has no valid "cleared" state post-Q8, unlike creator Settings' own
     * blank-clears-it path on a different endpoint).
     */
    public record UpdateProfileRequest(
            String firstName,
            String lastName,
            String displayName,
            String timezone,
            String avatarUrl,
            String phone) {}

    public record DeleteAccountResponse(boolean deleted) {}

    /**
     * BR-05 — POST /me/password. {@code newPassword} complexity is enforced server-side by {@link
     * com.influora.common.PasswordPolicy#validate}, same as register/reset-password; not
     * duplicated here as a Bean Validation annotation.
     */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {}

    public record ChangePasswordResponse(boolean changed) {}
}
