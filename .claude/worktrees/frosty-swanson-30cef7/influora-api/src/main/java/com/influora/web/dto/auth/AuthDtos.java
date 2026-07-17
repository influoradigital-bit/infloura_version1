package com.influora.web.dto.auth;

import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.VerificationStatus;

public final class AuthDtos {

    private AuthDtos() {}

    public record UserDto(
            String id,
            String email,
            String displayName,
            UserType userType,
            UserStatus status,
            boolean emailVerified) {}

    public record WorkspaceDto(
            String id, String name, String slug, VerificationStatus verificationStatus) {}

    public record TokenPair(
            UserDto user,
            WorkspaceDto workspace,
            String accessToken,
            String refreshToken,
            long expiresIn,
            Boolean onboardingCompleted) {

        /**
         * Copy with the refresh token stripped, for the HTTP body (Kabir A1). The refresh token is
         * delivered only via the HttpOnly cookie; it must never appear in JSON the SPA can read.
         */
        public TokenPair withoutRefresh() {
            return new TokenPair(user, workspace, accessToken, null, expiresIn, onboardingCompleted);
        }
    }

    public record RefreshResponse(String accessToken, long expiresIn) {}

    public record MessageResponse(String message) {}
}
