package com.influora.security;

import com.influora.common.ApiErrorBody;
import com.influora.common.ApiResponse;
import com.influora.config.InternalServiceTokenProperties;
import com.influora.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First half of the dual-credential gate on {@code /internal/meera/*}
 * ([SEC: 2.1/2.2, MF-2, LB-2]). Verifies:
 * <ol>
 *   <li>{@code X-Meera-Service-Token}: a JWT with {@code aud=influora-internal},
 *       {@code iss=meera-python}, a pinned algorithm (HS256 — no {@code alg:none}/confusion),
 *       and {@code exp} no more than {@link InternalServiceTokenProperties#MAX_TTL_SECONDS}
 *       seconds in the future of "now" (defense against a long-lived forged/leaked token even if
 *       signed correctly).</li>
 *   <li>{@code X-Meera-Signature} + {@code X-Meera-Timestamp} + {@code X-Meera-Nonce}: HMAC over
 *       the request via {@link InternalRequestVerifier} — rejects replay and body tampering.</li>
 * </ol>
 *
 * <p>A lone static key (e.g. a hardcoded shared secret with no token structure) is rejected by
 * construction: there is no code path here that accepts anything other than a well-formed,
 * correctly-signed, non-expired, correctly-audienced/issued JWT plus a valid HMAC signature —
 * both must hold. On any failure, the filter halts the chain with 401/403, writes a structured
 * {@code auth.internal.rejected} audit record (reason enum, no token bytes), and never lets the
 * request reach {@link OnBehalfAuthResolver} or the controller. This filter does NOT itself
 * authorize a workspace — that is {@link OnBehalfAuthResolver}'s job, run explicitly by the
 * executors, so a stolen/forged service token alone can never pick a victim workspace.
 *
 * <p>Registered only against {@code /internal/**} — never in the public {@code SecurityConfig}
 * filter chain (Domain E's {@code InternalSecurityConfig} is the seam that would wire a second,
 * mesh-only {@code SecurityFilterChain}; until that lands, this filter self-guards by checking
 * the path and no-oping otherwise, so its presence in the singleton filter chain cannot leak
 * onto public routes).
 */
@Component
public class InternalServiceTokenFilter extends OncePerRequestFilter {

    /**
     * EV-004: matched through {@link RequestPaths#isUnder} (decoded, dot/matrix/slash-normalised,
     * context-stripped, case-insensitive, fail-closed) — NOT {@code getRequestURI().startsWith},
     * which {@code /api/v1/%69nternal/meera/turns/release} walked straight past while Spring MVC
     * still dispatched it to the refund route.
     */
    static final String INTERNAL_ROOT = "/internal";
    public static final String SERVICE_TOKEN_AUDIENCE = "influora-internal";
    public static final String SERVICE_TOKEN_ISSUER = "meera-python";

    /**
     * {@link ApiResponse#timestamp()} is an {@link java.time.Instant}; a bare {@code new
     * ObjectMapper()} cannot serialise it, so every rejection used to throw from {@link #reject}
     * instead of writing the 401/403 body (EV-004 red run).
     */
    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    private final InternalServiceTokenProperties props;
    private final InternalRequestVerifier requestVerifier;
    private final AuditLogService auditLogService;

    public InternalServiceTokenFilter(
            InternalServiceTokenProperties props,
            InternalRequestVerifier requestVerifier,
            AuditLogService auditLogService) {
        this.props = props;
        this.requestVerifier = requestVerifier;
        this.auditLogService = auditLogService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Fail closed: isUnder answers true for any path it cannot normalise.
        if (!RequestPaths.isUnder(request, INTERNAL_ROOT)) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        String serviceToken = request.getHeader("X-Meera-Service-Token");
        Claims claims;
        try {
            claims = parseServiceToken(serviceToken);
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, "BAD_SERVICE_TOKEN", "auth.internal.rejected", HttpStatus.UNAUTHORIZED);
            return;
        }

        // The HMAC covers the canonical application path (what influora-ai signs, e.g.
        // /internal/meera/messages). A path with no canonical form is refused outright.
        String path;
        try {
            path = RequestPaths.pathWithinApplication(request);
        } catch (RequestPaths.UnnormalisablePathException e) {
            reject(response, "NON_CANONICAL_PATH", "auth.internal.rejected", HttpStatus.BAD_REQUEST);
            return;
        }

        var verification =
                requestVerifier.verify(
                        request.getMethod(),
                        path,
                        cachedRequest.getCachedBodyAsString(),
                        request.getHeader("X-Meera-Timestamp"),
                        request.getHeader("X-Meera-Nonce"),
                        request.getHeader("X-Meera-Signature"));
        if (!verification.accepted()) {
            reject(response, verification.rejectionReason(), "auth.internal.rejected", HttpStatus.FORBIDDEN);
            return;
        }

        InternalPrincipal principal = new InternalPrincipal(claims.getSubject());
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(cachedRequest, response);
    }

    private Claims parseServiceToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Missing X-Meera-Service-Token");
        }
        Claims claims =
                Jwts.parser()
                        .requireAudience(SERVICE_TOKEN_AUDIENCE)
                        .requireIssuer(SERVICE_TOKEN_ISSUER)
                        .verifyWith(signingKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

        if (claims.getExpiration() == null) {
            throw new JwtException("Service token missing exp");
        }
        if (claims.getIssuedAt() == null) {
            throw new JwtException("Service token missing iat");
        }
        long ttlSeconds =
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000L;
        if (ttlSeconds > InternalServiceTokenProperties.MAX_TTL_SECONDS) {
            throw new JwtException("Service token TTL exceeds ceiling");
        }
        return claims;
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(props.getSigningSecret().getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, String reasonCode, String eventType, HttpStatus status)
            throws IOException {
        SecurityContextHolder.clearContext();
        auditLogService.recordAuthRejection(null, eventType, reasonCode, null);
        response.setStatus(status.value());
        response.setContentType("application/json");
        var body =
                ApiResponse.fail(
                        ApiErrorBody.of("INTERNAL_AUTH_REJECTED", "Internal request rejected: " + reasonCode));
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
