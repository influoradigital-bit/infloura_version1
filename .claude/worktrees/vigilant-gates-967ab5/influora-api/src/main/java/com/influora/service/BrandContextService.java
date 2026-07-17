package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
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

    public BrandContextService(
            WorkspaceRepository workspaceRepository, WorkspaceMemberRepository workspaceMemberRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

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
}
