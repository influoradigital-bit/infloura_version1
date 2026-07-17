package com.influora.web.dto.auth;

/**
 * Optional fallback body for {@code POST /auth/refresh}. The refresh token is normally read from the
 * HttpOnly cookie (Kabir A1); this field exists only for non-browser clients that cannot hold a
 * cookie. May be null/blank when the cookie is used.
 */
public record RefreshRequest(String refreshToken) {}
