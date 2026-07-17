package com.influora.web;

import com.influora.common.ApiException;
import com.influora.security.AdminAuthCookieService;
import com.influora.security.AuthPrincipal;
import com.influora.service.admin.AdminAuthService;
import com.influora.service.admin.AdminAuthService.RefreshRotation;
import com.influora.web.dto.admin.AdminAuthDtos.AdminUserDto;
import com.influora.web.dto.admin.AdminAuthDtos.LoginRequest;
import com.influora.web.dto.admin.AdminAuthDtos.LoginResponse;
import com.influora.web.dto.admin.AdminAuthDtos.MessageResponse;
import com.influora.web.dto.admin.AdminAuthDtos.MfaSetupResponse;
import com.influora.web.dto.admin.AdminAuthDtos.MfaVerifyRequest;
import com.influora.web.dto.admin.AdminAuthDtos.RefreshRequest;
import com.influora.web.dto.admin.AdminAuthDtos.RefreshResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin login/session endpoints (src/admin/TASK_ASSIGNMENTS.md P0: "Admin auth endpoints" —
 * Vikram). RBAC-aware: role lives on {@code admin_users.role} (V34) and is returned in the
 * {@code AdminUser} payload for the SPA to gate UI with; per-request enforcement of "is this
 * caller an admin at all" happens in {@link com.influora.service.admin.AdminContextService}.
 *
 * <p><b>Deliberate deviation from the rest of this codebase (flagged for Priya/Arjun to
 * reconcile, not silently papered over):</b> every other controller here wraps responses in
 * {@link com.influora.common.ApiResponse}. This controller returns raw DTOs instead, because
 * {@code src/admin/services/api-contracts.ts}'s {@code apiRequest()} (Priya) does
 * {@code const data = await response.json(); return { success: true, data }} — i.e. it treats
 * the ENTIRE JSON body as the payload. Wrapping here would double-wrap
 * ({@code response.data.data.token} instead of {@code response.data.token}) and break every admin
 * API call. Error responses are UNCHANGED (still {@link com.influora.common.GlobalExceptionHandler}'s
 * {@code ApiResponse.fail(...)} envelope) — note that {@code apiRequest()}'s catch path reads
 * {@code error.message} on the raw JSON, but the actual message is nested at
 * {@code error.error.message}, so admin-side error messages will render as {@code undefined}
 * until one side of that mismatch is fixed. Not fixed here since both files are outside this
 * task's scope (api-contracts.ts is Priya's; GlobalExceptionHandler is shared infra).
 *
 * <p><b>Path note:</b> mounted at {@code /admin/auth} (full path
 * {@code /api/v1/admin/auth/...} given {@code server.servlet.context-path=/api/v1}), matching
 * this codebase's existing convention of NOT repeating the context path in {@code @RequestMapping}.
 * {@code api-contracts.ts} hardcodes {@code API_BASE = '/api/admin'} (no {@code /v1}) — that
 * mismatch needs a resolution (either a context-path change or a proxy rewrite) before the admin
 * SPA can actually reach this controller; flagged for Arjun/Priya, not invented here.
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final AdminAuthCookieService adminAuthCookieService;

    public AdminAuthController(
            AdminAuthService adminAuthService, AdminAuthCookieService adminAuthCookieService) {
        this.adminAuthService = adminAuthService;
        this.adminAuthCookieService = adminAuthCookieService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest body, HttpServletResponse response) {
        LoginResponse result = adminAuthService.login(body);
        adminAuthCookieService.writeRefreshCookie(response, result.refreshToken());
        return ResponseEntity.ok(result);
    }

    /**
     * Prefers the HttpOnly cookie; falls back to a body-supplied token only if no cookie is
     * present (same precedence as {@code AuthController#refresh}), so a browser client that never
     * reads the cookie value still works via {@code authApi.refreshToken()}'s explicit-body
     * contract.
     */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(
            @RequestBody(required = false) RefreshRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String raw = adminAuthCookieService.readRefreshToken(request);
        if (raw == null && body != null) {
            raw = body.refreshToken();
        }
        if (raw == null || raw.isBlank()) {
            throw new ApiException(
                    "INVALID_REFRESH_TOKEN", "Refresh token is missing", HttpStatus.UNAUTHORIZED);
        }
        RefreshRotation rotation = adminAuthService.refresh(raw);
        adminAuthCookieService.writeRefreshCookie(response, rotation.newRefreshToken());
        return ResponseEntity.ok(new RefreshResponse(rotation.accessToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletResponse response) {
        adminAuthService.logout(principal);
        adminAuthCookieService.clearRefreshCookie(response);
        return ResponseEntity.ok(new MessageResponse("Logged out successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<AdminUserDto> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(adminAuthService.me(principal));
    }

    @PostMapping("/mfa/setup")
    public ResponseEntity<MfaSetupResponse> setupMfa(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(adminAuthService.setupMfa(principal));
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<Void> verifyMfa(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody MfaVerifyRequest body) {
        adminAuthService.verifyMfa(principal, body.code());
        return ResponseEntity.ok().build();
    }
}
