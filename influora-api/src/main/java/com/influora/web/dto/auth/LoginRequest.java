package com.influora.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * F-0551 backend half — {@code rememberMe} controls REFRESH-TOKEN LIFETIME server-side, per the
 * approved CEO ruling. It does NOT decide which browser store the access token lives in (that is
 * memory-only for every session now, remembered or not); it only decides how long the HttpOnly
 * refresh cookie {@code AuthCookieService} writes — and the {@code refresh_tokens} row backing it
 * — stays valid. See {@code AuthCookieService#writeRefreshCookie} and {@code
 * JwtService#getRefreshExpirySeconds(boolean)} for the two actual durations, and {@code
 * AuthService#refresh} for why a rotation preserves whatever value was chosen at login rather than
 * re-deriving it.
 *
 * <p>Shared by both {@code /auth/brand/login} and {@code /auth/creator/login} — brand and creator
 * login already used one {@code LoginRequest} type before this field existed, so both endpoints
 * pick this up for free.
 *
 * <p><b>Default (backwards compatibility):</b> nullable, and {@link #isRemembered()} treats a
 * missing/{@code null} value as remembered ({@code true}). An existing client that predates this
 * field sends no {@code rememberMe} key at all; before this change every login issued a single
 * fixed-lifetime (30-day) refresh cookie unconditionally, so mapping "absent" to "remembered"
 * reproduces that exact prior behavior — not a new default invented for this change, and not a
 * security downgrade from today's baseline (which already grants that lifetime to every session).
 * A caller that wants the new short-lived, not-remembered behavior must opt in explicitly with
 * {@code "rememberMe": false}.
 *
 * <p>Secondary 2-arg constructor kept for the same reason {@code CreatorRegisterRequest} keeps
 * one: every pre-existing call site that built this record with just email/password (tests
 * included) keeps compiling unchanged, now defaulting to remembered via {@link #isRemembered()}.
 */
public record LoginRequest(
        @NotBlank @Email String email, @NotBlank String password, Boolean rememberMe) {

    public LoginRequest(String email, String password) {
        this(email, password, null);
    }

    /** Resolves the effective remember-me choice — {@code null} (field never sent) means "yes". */
    public boolean isRemembered() {
        return rememberMe == null || rememberMe;
    }
}
