package com.influora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiErrorBody;
import com.influora.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders Spring Security's two rejection paths as the same {@link ApiResponse} envelope every
 * controller returns, with the correct status on each.
 *
 * <p><b>Why this exists (2026-07-26).</b> {@code SecurityConfig} had no {@code exceptionHandling}
 * block at all. With {@code httpBasic}/{@code formLogin} absent, Spring fell back to {@code
 * Http403ForbiddenEntryPoint}, so an <em>unauthenticated</em> request — including one whose access
 * token had merely expired — was answered with <b>403 and an empty body</b>.
 *
 * <p>That broke token refresh across the whole SPA. {@code JwtAuthenticationFilter} swallows an
 * expired token ({@code catch (JwtException ignored)}) and lets the request continue anonymously;
 * the client's refresh-and-retry interceptor keys on <b>401</b>, so a 403 sailed straight past it
 * and no refresh was ever attempted. The refresh token stayed valid for 30 days in its HttpOnly
 * cookie while the user was forced to log in again every time the access token aged out. The empty
 * body was the second half of the bug: the SPA parses every response as JSON, so users saw
 * "Unexpected non-JSON response from the server (HTTP 403)".
 *
 * <p>The split is the standard HTTP one, and the distinction matters to the client:
 * <ul>
 *   <li><b>401 {@code UNAUTHENTICATED}</b> — no/expired/invalid credentials. Retryable: refresh
 *       the access token and try again.
 *   <li><b>403 {@code FORBIDDEN}</b> — authenticated, but not permitted (role gates such as
 *       OWNER/ADMIN-only campaign actions). NOT retryable — refreshing changes nothing, and a
 *       client that retried these would loop on requests the server is correctly refusing.
 * </ul>
 */
@Component
public class JsonAuthErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAuthErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Unauthenticated — the access token is missing, malformed or expired. */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        write(
                response,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED",
                "Your session has expired. Please sign in again.");
    }

    /** Authenticated, but lacking the authority this endpoint requires. */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        write(
                response,
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "You do not have permission to perform this action.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        // Guard against a committed response (e.g. an error raised mid-stream on an SSE endpoint
        // such as GET /deals/{id}/messages/stream) — writing again would throw and mask the
        // original failure.
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getOutputStream(), ApiResponse.fail(ApiErrorBody.of(code, message)));
    }
}
