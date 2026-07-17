package com.influora.web.dto.user;

import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
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
}
