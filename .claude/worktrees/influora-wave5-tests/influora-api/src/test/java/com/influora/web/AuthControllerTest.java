package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.enums.UserStatus;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthCookieService;
import com.influora.security.AuthPrincipal;
import com.influora.service.AuthService;
import com.influora.service.AuthService.RefreshRotation;
import com.influora.service.BrandEmailOtpService;
import com.influora.web.dto.auth.AuthDtos.MessageResponse;
import com.influora.web.dto.auth.AuthDtos.RefreshResponse;
import com.influora.web.dto.auth.AuthDtos.TokenPair;
import com.influora.web.dto.auth.AuthDtos.UserDto;
import com.influora.web.dto.auth.CreatorRegisterRequest;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpRequest;
import com.influora.web.dto.auth.EmailOtpDtos.SendEmailOtpResponse;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpRequest;
import com.influora.web.dto.auth.EmailOtpDtos.VerifyEmailOtpResponse;
import com.influora.web.dto.auth.LoginRequest;
import com.influora.web.dto.auth.RefreshRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * G-Kv3-1 — AuthController creator OTP send/verify + register/login cookie wiring (direct call
 * style; no MockMvc harness in this codebase yet).
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AuthService authService;
    @Mock private BrandEmailOtpService brandEmailOtpService;
    @Mock private AuthCookieService authCookieService;
    @Mock private HttpServletRequest httpRequest;
    @Mock private HttpServletResponse httpResponse;

    private AuthController controller;

    private static final TokenPair CREATOR_PAIR =
            new TokenPair(
                    new UserDto(
                            "01HCREATORUSER123456789A",
                            "riya@example.com",
                            "Riya Sharma",
                            UserType.CREATOR,
                            UserStatus.ACTIVE,
                            true),
                    null,
                    "access-jwt",
                    "refresh-raw",
                    900L,
                    false);

    @BeforeEach
    void setUp() {
        controller = new AuthController(authService, brandEmailOtpService, authCookieService);
    }

    @Test
    @DisplayName("POST /auth/creator/send-email-otp: delegates to BrandEmailOtpService")
    void sendCreatorEmailOtp_delegates() {
        SendEmailOtpResponse otpResponse =
                new SendEmailOtpResponse("OTP sent successfully", 300L, "r***@example.com");
        when(brandEmailOtpService.sendOtp("riya@example.com")).thenReturn(otpResponse);

        ResponseEntity<ApiResponse<SendEmailOtpResponse>> response =
                controller.sendCreatorEmailOtp(new SendEmailOtpRequest("riya@example.com"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(otpResponse, response.getBody().data());
        verify(brandEmailOtpService).sendOtp("riya@example.com");
    }

    @Test
    @DisplayName("POST /auth/creator/verify-email: delegates verifyOtp")
    void verifyCreatorEmail_delegates() {
        VerifyEmailOtpResponse verified =
                new VerifyEmailOtpResponse(true, "Email verified successfully");
        when(brandEmailOtpService.verifyOtp("riya@example.com", "123456")).thenReturn(verified);

        ResponseEntity<ApiResponse<VerifyEmailOtpResponse>> response =
                controller.verifyCreatorEmail(
                        new VerifyEmailOtpRequest("riya@example.com", "123456"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(verified, response.getBody().data());
        verify(brandEmailOtpService).verifyOtp("riya@example.com", "123456");
    }

    @Test
    @DisplayName("POST /auth/creator/verify-email: propagates INVALID_OTP from service")
    void verifyCreatorEmail_propagatesBadOtp() {
        when(brandEmailOtpService.verifyOtp("riya@example.com", "000000"))
                .thenThrow(
                        new ApiException("INVALID_OTP", "Invalid OTP", HttpStatus.BAD_REQUEST));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                controller.verifyCreatorEmail(
                                        new VerifyEmailOtpRequest("riya@example.com", "000000")));

        assertEquals("INVALID_OTP", ex.getCode());
    }

    @Test
    @DisplayName(
            "POST /auth/creator/register: 201 + refresh cookie written; body strips refresh token")
    void creatorRegister_writesCookieAndStripsRefresh() {
        CreatorRegisterRequest body =
                new CreatorRegisterRequest(
                        "riya@example.com", "Supersecret1", "Riya", "Sharma", null, true);
        when(authService.creatorRegister(body)).thenReturn(CREATOR_PAIR);

        ResponseEntity<ApiResponse<TokenPair>> response =
                controller.creatorRegister(body, httpResponse);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNull(response.getBody().data().refreshToken());
        assertEquals("access-jwt", response.getBody().data().accessToken());
        verify(authCookieService).writeRefreshCookie(httpResponse, "refresh-raw");
    }

    @Test
    @DisplayName("POST /auth/creator/login: 200 + refresh cookie written; body strips refresh token")
    void creatorLogin_writesCookieAndStripsRefresh() {
        LoginRequest body = new LoginRequest("riya@example.com", "Supersecret1");
        when(authService.creatorLogin(body)).thenReturn(CREATOR_PAIR);

        ResponseEntity<ApiResponse<TokenPair>> response = controller.creatorLogin(body, httpResponse);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody().data().refreshToken());
        verify(authCookieService).writeRefreshCookie(httpResponse, "refresh-raw");
    }

    @Test
    @DisplayName("POST /auth/creator/login: propagates INVALID_CREDENTIALS")
    void creatorLogin_propagatesInvalidCredentials() {
        LoginRequest body = new LoginRequest("riya@example.com", "wrong");
        when(authService.creatorLogin(body))
                .thenThrow(
                        new ApiException(
                                "INVALID_CREDENTIALS",
                                "Invalid email or password",
                                HttpStatus.UNAUTHORIZED));

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.creatorLogin(body, httpResponse));

        assertEquals("INVALID_CREDENTIALS", ex.getCode());
        verify(authCookieService, never()).writeRefreshCookie(any(), any());
    }

    @Test
    @DisplayName("POST /auth/refresh: missing cookie+body → INVALID_REFRESH_TOKEN")
    void refresh_missingToken() {
        when(authCookieService.readRefreshToken(httpRequest)).thenReturn(null);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.refresh(null, httpRequest, httpResponse));

        assertEquals("INVALID_REFRESH_TOKEN", ex.getCode());
        assertEquals(401, ex.getStatus().value());
        verify(authService, never()).refresh(any());
    }

    @Test
    @DisplayName("POST /auth/refresh: cookie path rotates and rewrites cookie")
    void refresh_cookieHappyPath() {
        when(authCookieService.readRefreshToken(httpRequest)).thenReturn("old-refresh");
        when(authService.refresh("old-refresh"))
                .thenReturn(new RefreshRotation("new-access", 900L, "new-refresh"));

        ResponseEntity<ApiResponse<RefreshResponse>> response =
                controller.refresh(null, httpRequest, httpResponse);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("new-access", response.getBody().data().accessToken());
        verify(authCookieService).writeRefreshCookie(httpResponse, "new-refresh");
    }

    @Test
    @DisplayName("POST /auth/refresh: body fallback when cookie absent")
    void refresh_bodyFallback() {
        when(authCookieService.readRefreshToken(httpRequest)).thenReturn(null);
        when(authService.refresh("body-refresh"))
                .thenReturn(new RefreshRotation("new-access", 900L, "new-refresh"));

        ResponseEntity<ApiResponse<RefreshResponse>> response =
                controller.refresh(
                        new RefreshRequest("body-refresh"), httpRequest, httpResponse);

        assertEquals("new-access", response.getBody().data().accessToken());
        verify(authService).refresh("body-refresh");
    }

    @Test
    @DisplayName("POST /auth/logout: clears cookie and revokes sessions when principal present")
    void logout_clearsCookie() {
        AuthPrincipal principal =
                new AuthPrincipal(
                        "01HCREATORUSER123456789A",
                        "riya@example.com",
                        UserType.CREATOR,
                        null);

        ResponseEntity<ApiResponse<MessageResponse>> response =
                controller.logout(principal, httpResponse);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(authService).logout("01HCREATORUSER123456789A");
        verify(authCookieService).clearRefreshCookie(httpResponse);
    }
}
