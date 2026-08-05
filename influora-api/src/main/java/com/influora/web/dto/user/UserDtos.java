package com.influora.web.dto.user;

import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public final class UserDtos {

    private UserDtos() {}

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
            String timezone,
            Instant createdAt) {}

    public record UpdateProfileRequest(
            String firstName,
            String lastName,
            String displayName,
            String timezone,
            String avatarUrl) {}

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
