package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.security.AuthCookieService;
import com.influora.security.AuthPrincipal;
import com.influora.service.AuthService;
import com.influora.service.AuthService.RefreshRotation;
import com.influora.service.BrandEmailOtpService;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpRequest;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpResponse;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpRequest;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpResponse;
import com.influora.web.dto.auth.AuthDtos.MessageResponse;
import com.influora.web.dto.auth.AuthDtos.RefreshResponse;
import com.influora.web.dto.auth.AuthDtos.TokenPair;
import com.influora.web.dto.auth.BrandRegisterRequest;
import com.influora.web.dto.auth.ForgotPasswordRequest;
import com.influora.web.dto.auth.LoginRequest;
import com.influora.web.dto.auth.RefreshRequest;
import com.influora.web.dto.auth.ResetPasswordRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final BrandEmailOtpService brandEmailOtpService;
    private final AuthCookieService authCookieService;

    public AuthController(
            AuthService authService,
            BrandEmailOtpService brandEmailOtpService,
            AuthCookieService authCookieService) {
        this.authService = authService;
        this.brandEmailOtpService = brandEmailOtpService;
        this.authCookieService = authCookieService;
    }

    @PostMapping("/brand/send-email-otp")
    public ResponseEntity<ApiResponse<SendEmailOtpResponse>> sendBrandEmailOtp(
            @Valid @RequestBody SendEmailOtpRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(brandEmailOtpService.sendOtp(body.email())));
    }

    @PostMapping("/brand/verify-email")
    public ResponseEntity<ApiResponse<VerifyEmailOtpResponse>> verifyBrandEmail(
            @Valid @RequestBody VerifyEmailOtpRequest body) {
        return ResponseEntity.ok(
                ApiResponse.ok(brandEmailOtpService.verifyOtp(body.email(), body.otp())));
    }

    @PostMapping("/brand/register")
    public ResponseEntity<ApiResponse<TokenPair>> brandRegister(
            @Valid @RequestBody BrandRegisterRequest body, HttpServletResponse response) {
        TokenPair pair = authService.brandRegister(body);
        authCookieService.writeRefreshCookie(response, pair.refreshToken());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(pair.withoutRefresh()));
    }

    @PostMapping("/brand/login")
    public ResponseEntity<ApiResponse<TokenPair>> brandLogin(
            @Valid @RequestBody LoginRequest body, HttpServletResponse response) {
        TokenPair pair = authService.brandLogin(body);
        authCookieService.writeRefreshCookie(response, pair.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(pair.withoutRefresh()));
    }

    /**
     * Refresh reads the refresh token from the HttpOnly cookie (Kabir A1). A JSON body
     * {@code refreshToken} is accepted only as a transitional fallback for non-browser clients; the
     * SPA sends nothing and relies on the cookie. On success the token is rotated and the cookie
     * replaced; the body carries only the new access token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @RequestBody(required = false) RefreshRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String raw = authCookieService.readRefreshToken(request);
        if (raw == null && body != null) {
            raw = body.refreshToken();
        }
        if (raw == null || raw.isBlank()) {
            throw new ApiException(
                    "INVALID_REFRESH_TOKEN", "Refresh token is missing", HttpStatus.UNAUTHORIZED);
        }
        RefreshRotation rotation = authService.refresh(raw);
        authCookieService.writeRefreshCookie(response, rotation.newRefreshToken());
        return ResponseEntity.ok(
                ApiResponse.ok(new RefreshResponse(rotation.accessToken(), rotation.expiresIn())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<MessageResponse>> logout(
            @AuthenticationPrincipal AuthPrincipal principal, HttpServletResponse response) {
        if (principal != null) {
            authService.logout(principal.getUserId());
        }
        authCookieService.clearRefreshCookie(response);
        return ResponseEntity.ok(ApiResponse.ok(new MessageResponse("Logged out successfully")));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<MessageResponse>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest body) {
        String message = authService.forgotPassword(body.email());
        return ResponseEntity.ok(ApiResponse.ok(new MessageResponse(message)));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<MessageResponse>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest body) {
        authService.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.ok(ApiResponse.ok(new MessageResponse("Password reset successfully")));
    }
}
