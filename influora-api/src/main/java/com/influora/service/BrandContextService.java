package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Resolves the authenticated brand user's workspace. */
@Service
public class BrandContextService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public BrandContextService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            UserRepository userRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
    }

    /** Billing-recipient identity resolved for a workspace ({@code SubscriptionDunningJob}). */
    public record BillingRecipient(String userId, String email) {}

    /**
     * [SEC: Kabir H-1 — brand half, F-0708, closed 2026-09-07] The creator twin of this gate
     * ({@code CreatorContextService.requireCreator}) has re-checked {@code deletedAt} since H-1 was
     * first raised; this one never did, and {@code AccountController}'s javadoc asserted for months
     * that both did. Access tokens are stateless JWTs that {@code JwtAuthenticationFilter} parses
     * without a database round-trip, and {@code AccountController.deleteAccount} revokes refresh
     * tokens only — so a soft-deleted BRAND user kept full brand-scoped access, wallet and payout
     * endpoints included, for the remaining access-token lifetime (900s default, application.yml).
     *
     * <p>This is the right place for it because it is the funnel: {@code requireBrandWorkspace} and
     * {@code requireMember} both call this method first, so all three entry points inherit the
     * check — 159 call sites across {@code src/main} at the time of writing. That mirrors the
     * creator side, where {@code requireCreatorProfile} delegates to {@code requireCreator}.
     *
     * <p>{@code orElse(true)} — a principal whose user row cannot be found is treated as deleted,
     * not as present. Copied deliberately from {@code requireCreator}: the safe default for "I
     * cannot tell" on an authorization gate is to refuse. It also means a hard-deleted row behaves
     * like a soft-deleted one.
     *
     * <p>COST: one {@code findById} per brand request, on the path every brand endpoint takes. The
     * creator gate already accepts exactly this, for exactly this reason. It is not free, and the
     * alternative — letting deletion take effect only when the token expires — is what this fixes.
     */
    public void requireBrand(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.BRAND) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for brand accounts only", HttpStatus.FORBIDDEN);
        }
        boolean deleted =
                userRepository.findById(principal.getUserId()).map(u -> u.getDeletedAt() != null).orElse(true);
        if (deleted) {
            throw new ApiException(
                    "ACCOUNT_DELETED", "This account no longer exists", HttpStatus.UNAUTHORIZED);
        }
    }

    public Workspace requireBrandWorkspace(AuthPrincipal principal) {
        requireBrand(principal);
        String workspaceId = principal.getWorkspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            WorkspaceMember member =
                    workspaceMemberRepository
                            .findFirstByUserIdAndActiveTrue(principal.getUserId())
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    "WORKSPACE_NOT_FOUND",
                                                    "No workspace found for this user",
                                                    HttpStatus.NOT_FOUND));
            workspaceId = member.getWorkspaceId();
        }
        final String resolvedId = workspaceId;
        Workspace workspace =
                workspaceRepository
                        .findById(resolvedId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND",
                                                "Workspace not found",
                                                HttpStatus.NOT_FOUND));

        // F-0457: this resolver is what every brand endpoint funnels through, so it is the only
        // place that closes F-0451's ACTUAL stated symptom — "a suspended brand keeps full access
        // to its current workspace". Gating login and refresh alone left a window equal to the
        // access-token lifetime (900s, application.yml:185) during which a just-suspended brand
        // kept operating normally, because JwtAuthenticationFilter is a pure token parse and never
        // touches the database. Enforcing here makes suspension effective on the very next request.
        if (workspace.isSuspended()) {
            throw new ApiException(
                    "WORKSPACE_SUSPENDED",
                    "This workspace has been suspended. Contact support.",
                    HttpStatus.FORBIDDEN);
        }
        return workspace;
    }

    public WorkspaceMember requireMember(AuthPrincipal principal, String workspaceId) {
        requireBrand(principal);
        return workspaceMemberRepository
                .findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "FORBIDDEN", "You are not a member of this workspace", HttpStatus.FORBIDDEN));
    }

    public void requireRole(WorkspaceMember member, MemberRole... allowed) {
        for (MemberRole role : allowed) {
            if (member.getRole() == role) {
                return;
            }
        }
        throw new ApiException("FORBIDDEN", "Insufficient workspace permissions", HttpStatus.FORBIDDEN);
    }

    /**
     * Resolves the workspace's OWNER member -> that user's email, for billing/dunning emails
     * ({@code SubscriptionDunningJob}). Returns {@code null} if unresolved — the caller null-checks
     * {@code recipient != null && recipient.email() != null}.
     */
    public BillingRecipient resolveBillingRecipient(String workspaceId) {
        WorkspaceMember owner =
                workspaceMemberRepository
                        .findFirstByWorkspaceIdAndRoleAndActiveTrue(workspaceId, MemberRole.OWNER)
                        .orElse(null);
        if (owner == null) {
            return null;
        }
        User user = userRepository.findById(owner.getUserId()).orElse(null);
        if (user == null) {
            return null;
        }
        return new BillingRecipient(user.getId(), user.getEmail());
    }
}
