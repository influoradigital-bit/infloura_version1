package com.influora.web.dto.admin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.influora.domain.enums.AdminRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * Admin auth request/response records. Field names deliberately match {@code AdminLoginRequest} /
 * {@code AdminLoginResponse} / {@code AdminUser} in {@code src/admin/types/admin.types.ts}
 * exactly, and the controller returns these UNWRAPPED (no {@link com.influora.common.ApiResponse}
 * envelope) — see {@code AdminAuthController} class javadoc for why that's a deliberate deviation
 * from the rest of this codebase's convention.
 */
public final class AdminAuthDtos {

    private AdminAuthDtos() {}

    public record LoginRequest(
            @NotBlank @Email String email, @NotBlank String password, String mfaCode) {}

    /** Mirrors {@code AdminUser} in admin.types.ts field-for-field (including {@code lastLogin} naming). */
    public record AdminUserDto(
            String id,
            String email,
            AdminRole role,
            boolean mfaEnabled,
            Instant lastLogin,
            Instant createdAt) {}

    /**
     * Mirrors {@code AdminLoginResponse} — MINUS the refresh token in the actual wire payload.
     * {@code refreshToken} is {@code @JsonIgnore}d (P1 security fix, Kabir §8 MEDIUM,
     * {@code AdminAuthController.java:71-73}): it is never serialized into the JSON response body
     * (a JS-readable copy would turn a transient XSS into permanent account takeover — same
     * reasoning as {@code AuthCookieService}'s class javadoc for the brand/creator flow), but the
     * record component/accessor still exists so {@code AdminAuthController#login} can read the
     * raw value in Java to write the HttpOnly cookie via {@code AdminAuthCookieService}. The
     * frontend {@code AdminLoginResponse} TypeScript type still declares this field (stale, not
     * fixed here — {@code src/admin/types/admin.types.ts} is Priya's), but nothing in {@code
     * api-contracts.ts} actually reads {@code .refreshToken} off the login response today, so
     * removing it from the wire body is not a breaking change.
     */
    public record LoginResponse(String token, @JsonIgnore String refreshToken, AdminUserDto user) {}

    /** Optional body fallback, same shape/precedence as the brand/creator {@code RefreshRequest}. */
    public record RefreshRequest(String refreshToken) {}

    /** Mirrors the {@code { token: string }} shape {@code authApi.refreshToken()} expects. */
    public record RefreshResponse(String token) {}

    /** Mirrors {@code { qrCode: string; secret: string }} from {@code authApi.setupMfa()}. */
    public record MfaSetupResponse(String qrCode, String secret) {}

    public record MfaVerifyRequest(@NotBlank String code) {}

    public record MessageResponse(String message) {}
}
