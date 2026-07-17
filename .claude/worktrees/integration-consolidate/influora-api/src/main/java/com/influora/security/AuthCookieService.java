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
 * Owns the refresh-token cookie (Kabir audit A1 / B4).
 *
 * <p>The refresh token is the durable credential — a JS-readable copy turns a transient XSS into
 * permanent account takeover. So it never travels in the JSON body or {@code localStorage}; it lives
 * only in an {@code HttpOnly; Secure; SameSite=Strict} cookie the SPA cannot read.
 *
 * <p>CSRF: the cookie is {@code SameSite=Strict} and {@code Path}-scoped to {@code /auth}, so a
 * cross-site page cannot cause the browser to attach it to a forged {@code /auth/refresh} or
 * {@code /auth/logout} call. Every other authenticated request authorizes via the {@code Bearer}
 * header (which browsers never attach cross-site), so the API carries no ambient session cookie to
 * forge against. That is the CSRF control — no separate CSRF token machinery is required for a
 * Bearer + refresh-cookie SPA.
 */
@Service
public class AuthCookieService {

    private final long maxAgeSeconds;

    @Value("${influora.auth.refresh-cookie.name:influora_refresh}")
    private String cookieName;

    /** Must be true in any environment served over HTTPS (all non-local deploys). */
    @Value("${influora.auth.refresh-cookie.secure:true}")
    private boolean secure;

    @Value("${influora.auth.refresh-cookie.same-site:Strict}")
    private String sameSite;

    /** Scope the cookie to the auth subtree so it is not attached to every API call. */
    @Value("${influora.auth.refresh-cookie.path:/api/v1/auth}")
    private String path;

    public AuthCookieService(JwtProperties jwtProperties) {
        this.maxAgeSeconds = jwtProperties.getRefreshExpirySeconds();
    }

    /** Sets (or replaces, on rotation) the refresh cookie. */
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

    /** Expires the refresh cookie (logout). */
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

    /** Reads the raw refresh token from the cookie, or null when absent. */
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
