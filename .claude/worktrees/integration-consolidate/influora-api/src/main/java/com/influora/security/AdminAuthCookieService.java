package com.influora.security;

import com.influora.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * Admin-session counterpart to {@link AuthCookieService} — same HttpOnly/Secure/SameSite=Strict
 * shape (Kabir A1), kept as a DISTINCT cookie (name + path) rather than reusing
 * {@link AuthCookieService} so an admin session can never be confused with, or accidentally
 * revoked/rotated alongside, a brand/creator one. Path is scoped to {@code /api/v1/admin/auth} so
 * the cookie is never attached outside the admin auth surface.
 *
 * <p><b>[FIXED, P1 security hardening 2026-07-12, Kabir §8 MEDIUM]</b> {@code AdminAuthController}
 * previously ALSO returned the raw refresh token in the login JSON body (a JS-readable copy —
 * XSS-to-account-takeover risk on the highest-value credential surface). It no longer does:
 * {@code AdminAuthDtos.LoginResponse.refreshToken} is now {@code @JsonIgnore}d, so the cookie set
 * here is the ONLY place the raw refresh token ever leaves the server. {@code POST
 * /admin/auth/refresh} still accepts an optional body-supplied token as a fallback (see {@code
 * AdminAuthController#refresh}, same precedence as the brand/creator flow) for any client that
 * genuinely cannot rely on cookies — that fallback is a client-supplied-on-refresh-call
 * convenience, not a value the SERVER ever hands back in a response body.
 */
@Service
public class AdminAuthCookieService {

    private final long maxAgeSeconds;

    @Value("${influora.auth.admin-refresh-cookie.name:influora_admin_refresh}")
    private String cookieName;

    @Value("${influora.auth.admin-refresh-cookie.secure:true}")
    private boolean secure;

    @Value("${influora.auth.admin-refresh-cookie.same-site:Strict}")
    private String sameSite;

    @Value("${influora.auth.admin-refresh-cookie.path:/api/v1/admin/auth}")
    private String path;

    public AdminAuthCookieService(JwtProperties jwtProperties) {
        this.maxAgeSeconds = jwtProperties.getRefreshExpirySeconds();
    }

    public void writeRefreshCookie(HttpServletResponse response, String rawRefreshToken) {
        ResponseCookie cookie =
                ResponseCookie.from(cookieName, rawRefreshToken)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite(sameSite)
                        .path(path)
                        .maxAge(maxAgeSeconds)
                        .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie =
                ResponseCookie.from(cookieName, "")
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite(sameSite)
                        .path(path)
                        .maxAge(0)
                        .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public String readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }
}
