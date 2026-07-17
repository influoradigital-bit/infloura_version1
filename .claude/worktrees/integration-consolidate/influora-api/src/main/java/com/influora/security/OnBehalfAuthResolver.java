package com.influora.security;

import com.influora.common.ApiException;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.WorkspaceMemberRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Second half of the dual-credential gate on {@code /internal/meera/*} ([SEC: 2.3, G1]).
 * {@link InternalServiceTokenFilter} only proves "this call came from the Python mesh service" —
 * it says nothing about which human/workspace the call is acting on behalf of. This resolver
 * re-validates the forwarded human access JWT (same {@link com.influora.security.JwtService}
 * token shape/signature as the public API) and enforces:
 * <ul>
 *   <li>the JWT is well-formed, unexpired, correctly signed — reusing the exact same parser as
 *       the public {@code JwtAuthenticationFilter} so there is no second, weaker JWT path;</li>
 *   <li>{@code token.workspaceId == body.workspaceId} — a stolen/forged service token cannot pick
 *       an arbitrary victim workspace merely by putting a different id in the request body;</li>
 *   <li>for money-tier (C) actions, the on-behalf user's workspace role is OWNER or ADMIN.</li>
 * </ul>
 *
 * <p>Even read-tier (R) executors call {@link #resolveForWorkspace} — the matrix requires every
 * tool tier to re-validate the on-behalf JWT, not just commit-tier ones (T4.1 in
 * 16-VIKRAM-REMAINING-TASKS.md), so a service token alone can never read an arbitrary workspace.
 */
@Component
public class OnBehalfAuthResolver {

    private final JwtService jwtService;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public OnBehalfAuthResolver(JwtService jwtService, WorkspaceMemberRepository workspaceMemberRepository) {
        this.jwtService = jwtService;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    /** Result of a successful on-behalf resolution — the caller's identity, re-proven per call. */
    public record OnBehalfContext(String userId, String workspaceId, UserType userType) {}

    /**
     * Validates the forwarded on-behalf JWT and asserts it matches {@code bodyWorkspaceId}.
     * Throws {@code 401} on a malformed/expired/invalid token and {@code 403} on a
     * workspace mismatch — both cases are indistinguishable from "reject, do not execute" to the
     * caller (executors must not proceed past this call on any exception).
     */
    public OnBehalfContext resolveForWorkspace(String onBehalfJwt, String bodyWorkspaceId) {
        Claims claims = parseOrReject(onBehalfJwt);

        String tokenWorkspaceId = claims.get("workspaceId", String.class);
        if (tokenWorkspaceId == null
                || bodyWorkspaceId == null
                || !tokenWorkspaceId.equals(bodyWorkspaceId)) {
            throw new ApiException(
                    "ON_BEHALF_WORKSPACE_MISMATCH",
                    "On-behalf JWT workspace does not match the request workspace",
                    HttpStatus.FORBIDDEN);
        }

        String userTypeStr = claims.get("userType", String.class);
        UserType userType;
        try {
            userType = UserType.valueOf(userTypeStr);
        } catch (Exception e) {
            throw new ApiException(
                    "ON_BEHALF_INVALID_CLAIMS", "On-behalf JWT missing/invalid userType", HttpStatus.UNAUTHORIZED);
        }

        return new OnBehalfContext(claims.getSubject(), tokenWorkspaceId, userType);
    }

    /**
     * Same as {@link #resolveForWorkspace} but additionally requires the on-behalf user hold
     * OWNER or ADMIN in the workspace — used by C-tier (commit-adjacent staging) executors per
     * the matrix's "human confirms" model even where the human identity is only being resolved
     * to stage an action, never to auto-execute it.
     */
    public OnBehalfContext resolveForWorkspaceRequiringElevatedRole(
            String onBehalfJwt, String bodyWorkspaceId) {
        OnBehalfContext context = resolveForWorkspace(onBehalfJwt, bodyWorkspaceId);

        WorkspaceMember member =
                workspaceMemberRepository
                        .findByWorkspaceIdAndUserIdAndActiveTrue(context.workspaceId(), context.userId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "ON_BEHALF_NOT_A_MEMBER",
                                                "On-behalf user is not an active member of this workspace",
                                                HttpStatus.FORBIDDEN));
        if (member.getRole() != MemberRole.OWNER && member.getRole() != MemberRole.ADMIN) {
            throw new ApiException(
                    "ON_BEHALF_INSUFFICIENT_ROLE",
                    "On-behalf user lacks OWNER/ADMIN role required for this action",
                    HttpStatus.FORBIDDEN);
        }
        return context;
    }

    private Claims parseOrReject(String onBehalfJwt) {
        if (onBehalfJwt == null || onBehalfJwt.isBlank()) {
            throw new ApiException(
                    "ON_BEHALF_JWT_MISSING", "No on-behalf user JWT forwarded", HttpStatus.UNAUTHORIZED);
        }
        try {
            return jwtService.parseAccessToken(onBehalfJwt);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(
                    "ON_BEHALF_JWT_INVALID", "On-behalf user JWT invalid or expired", HttpStatus.UNAUTHORIZED);
        }
    }
}
