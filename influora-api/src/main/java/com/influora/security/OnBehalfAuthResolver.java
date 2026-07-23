package com.influora.security;

import com.influora.common.ApiException;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.service.meera.OnBehalfTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Second half of the dual-credential gate on {@code /internal/meera/*} ([SEC: 2.3, G1]).
 * {@link InternalServiceTokenFilter} only proves "this call came from the Python mesh service" —
 * it says nothing about which human/workspace the call is acting on behalf of. This resolver
 * re-validates the forwarded on-behalf JWT and enforces:
 * <ul>
 *   <li>the JWT is well-formed, unexpired, correctly signed, {@code iss}/{@code aud}-checked —
 *       via {@link OnBehalfTokenService#verify}, i.e. the DEDICATED per-turn on-behalf token
 *       contract, never the public-API access-token parser (SECURITY FIX #1, {@code
 *       docs/security/meera-onbehalf-auth-security-design.md} §2/§3 — a full public-API access
 *       token is now structurally rejected here: it is signed with a different key/algorithm
 *       entirely and carries no {@code aud} claim, see {@link OnBehalfTokenService#verify});</li>
 *   <li>{@code token.workspaceId == body.workspaceId} — a stolen/forged service token cannot pick
 *       an arbitrary victim workspace merely by putting a different id in the request body;</li>
 *   <li>for money-tier (C) actions, the on-behalf user's workspace role is OWNER or ADMIN.</li>
 * </ul>
 *
 * <p>Even read-tier (R) executors call {@link #resolveForWorkspace} — the matrix requires every
 * tool tier to re-validate the on-behalf JWT, not just commit-tier ones (T4.1 in
 * 16-VIKRAM-REMAINING-TASKS.md), so a service token alone can never read an arbitrary workspace.
 *
 * <p><b>SECURITY FIX (Kabir's SECURITY FIX #1 follow-up #1 — scope enforcement):</b> {@link
 * #resolveForWorkspaceRequiringScope} and {@link #resolveForWorkspaceRequiringElevatedRoleAndScope}
 * additionally assert the on-behalf token's {@code scope} claim (space-delimited tool names,
 * {@link com.influora.service.meera.OnBehalfTokenService#SCOPE_READ_ONLY}) actually authorizes the
 * tool being called. Before this fix, a read-scoped token was accepted by every {@code
 * /internal/meera/*} tool route regardless of tier — "read-only" was only a deploy convention
 * (nothing minted a write-scoped token yet, but nothing would have REJECTED one being presented
 * to a write route either). Every tool route in {@code MeeraInternalController} now passes its own
 * tool name, so a read-only-scoped token is structurally rejected by {@code create_campaign}/
 * {@code request_payment}/{@code confirm_launch} regardless of what Python offers to call or what
 * an injected model attempts.
 *
 * <p><b>Deliberately NOT in this pass</b> (design doc checklist items 8/10 — separate queued
 * fixes): {@code conversationId} is not yet cross-checked against the tenant in tool executors,
 * and {@code jti} single-use/replay is not yet enforced. See the design doc §3 for the exact gap.
 */
@Component
public class OnBehalfAuthResolver {

    private final OnBehalfTokenService onBehalfTokenService;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public OnBehalfAuthResolver(
            OnBehalfTokenService onBehalfTokenService, WorkspaceMemberRepository workspaceMemberRepository) {
        this.onBehalfTokenService = onBehalfTokenService;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    /**
     * Result of a successful on-behalf resolution — the caller's identity, re-proven per call.
     *
     * <p>{@code conversationId} comes straight off the verified JWT's {@code conversationId} claim
     * ({@link OnBehalfTokenService#mint} — minted server-side at {@code MeeraSessionService#doSendTurn}
     * from the real, FK-valid {@code ai_conversations.id} for this turn). Tool routes/executors that
     * need a conversation id MUST read it from here, never from the request body: the body is raw
     * AI-proposed tool input (or, for {@code create_campaign} specifically, simply doesn't carry a
     * {@code conversation_id} field at all — see {@code MeeraInternalController} javadoc), so a
     * body-sourced value is either absent or an unverified, client-influenced string with no
     * workspace/tenant cross-check (the exact "don't trust a client-body conversation_id" risk
     * flagged during this fix's review). May be {@code null} for hand-built/legacy token shapes
     * that never carried the claim — callers persisting it into a NOT NULL column must still
     * validate before use.
     */
    public record OnBehalfContext(String userId, String workspaceId, UserType userType, String conversationId) {}

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

        String conversationId = claims.get("conversationId", String.class);

        return new OnBehalfContext(claims.getSubject(), tokenWorkspaceId, userType, conversationId);
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

    /**
     * Same as {@link #resolveForWorkspace} but additionally asserts {@code requiredTool} is
     * present in the token's space-delimited {@code scope} claim — SECURITY FIX (Kabir's
     * SECURITY FIX #1 follow-up #1, see class javadoc). Used by the R-tier ({@code show_creators},
     * {@code calculate_budget}) and D-tier ({@code create_campaign}) tool routes in {@code
     * MeeraInternalController}. Throws {@code 403 ON_BEHALF_SCOPE_INSUFFICIENT} if the token's
     * scope does not cover {@code requiredTool} — a read-scoped token calling a write route is
     * rejected here, structurally, before any executor runs.
     */
    public OnBehalfContext resolveForWorkspaceRequiringScope(
            String onBehalfJwt, String bodyWorkspaceId, String requiredTool) {
        OnBehalfContext context = resolveForWorkspace(onBehalfJwt, bodyWorkspaceId);
        requireScope(onBehalfJwt, requiredTool);
        return context;
    }

    /**
     * Same as {@link #resolveForWorkspaceRequiringElevatedRole} but additionally asserts {@code
     * requiredTool} is present in the token's {@code scope} claim — the C-tier ({@code
     * request_payment}, {@code confirm_launch}) counterpart to {@link
     * #resolveForWorkspaceRequiringScope}. Both the elevated-role check AND the scope check must
     * pass; either failure rejects before any executor runs.
     */
    public OnBehalfContext resolveForWorkspaceRequiringElevatedRoleAndScope(
            String onBehalfJwt, String bodyWorkspaceId, String requiredTool) {
        OnBehalfContext context = resolveForWorkspaceRequiringElevatedRole(onBehalfJwt, bodyWorkspaceId);
        requireScope(onBehalfJwt, requiredTool);
        return context;
    }

    /**
     * Re-parses the token (cheap, stateless in-memory EC signature verification — no I/O, no
     * caching needed) purely to read the {@code scope} claim; {@link #resolveForWorkspace} already
     * validated signature/iss/aud/exp/workspaceId by the time this runs. Rejects with {@code 403
     * ON_BEHALF_SCOPE_INSUFFICIENT} if {@code scope} is missing or does not list {@code
     * requiredTool} as one of its space-delimited entries.
     */
    private void requireScope(String onBehalfJwt, String requiredTool) {
        Claims claims = parseOrReject(onBehalfJwt);
        String scope = claims.get("scope", String.class);
        boolean allowed =
                scope != null && java.util.List.of(scope.trim().split("\\s+")).contains(requiredTool);
        if (!allowed) {
            throw new ApiException(
                    "ON_BEHALF_SCOPE_INSUFFICIENT",
                    "On-behalf token scope does not authorize tool: " + requiredTool,
                    HttpStatus.FORBIDDEN);
        }
    }

    private Claims parseOrReject(String onBehalfJwt) {
        if (onBehalfJwt == null || onBehalfJwt.isBlank()) {
            throw new ApiException(
                    "ON_BEHALF_JWT_MISSING", "No on-behalf user JWT forwarded", HttpStatus.UNAUTHORIZED);
        }
        try {
            return onBehalfTokenService.verify(onBehalfJwt);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(
                    "ON_BEHALF_JWT_INVALID", "On-behalf user JWT invalid or expired", HttpStatus.UNAUTHORIZED);
        }
    }
}
