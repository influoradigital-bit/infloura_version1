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

    public void requireBrand(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.BRAND) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for brand accounts only", HttpStatus.FORBIDDEN);
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
        return workspaceRepository
                .findById(resolvedId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
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
