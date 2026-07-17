package com.influora.web.dto.workspace;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/** Task 23 (Phase 3c subscription billing) — seat invite/add-member flow DTOs. */
public final class WorkspaceMemberDtos {

    private WorkspaceMemberDtos() {}

    public record InviteRequest(
            @NotBlank @Email String email, @NotBlank String role) {}

    public record InviteResponse(
            String id, String workspaceId, String email, String role, String status, Instant expiresAt) {}

    public record AcceptRequest(@NotBlank String inviteToken) {}

    public record MemberResponse(
            String id, String workspaceId, String userId, String role, boolean active) {}

    /** H-16 — {@code POST /workspace/members/switch} request body. */
    public record SwitchWorkspaceRequest(@NotBlank String workspaceId) {}

    /** H-16 — {@code POST /workspace/members/switch} response: a fresh access token scoped to the target workspace. */
    public record SwitchWorkspaceResponse(String accessToken, long expiresIn, WorkspaceSummary workspace) {}

    public record WorkspaceSummary(String id, String name, String slug, String role) {}

    /** L-9 — {@code PATCH /workspace/members/{memberId}/role} request body. */
    public record ChangeRoleRequest(@NotBlank String role) {}

    /**
     * L-9 — {@code GET /workspaces/me} response. No {@code description} field: {@code
     * Workspace} has a {@code description} column but no getter for it (entity is out of scope
     * for this pass), so it cannot be safely surfaced or round-tripped here.
     */
    public record WorkspaceReadResponse(
            String id,
            String name,
            String slug,
            String industry,
            String companySize,
            String websiteUrl,
            String logoUrl,
            String verificationStatus) {}

    /**
     * L-9 — {@code PATCH /workspaces/me} request body. Full-replace semantics for every field
     * included below (not a deep merge) — a client omitting/nulling a field clears it, same
     * contract as the existing onboarding {@code BrandCompanyRequest}. {@code name} is the only
     * required field; {@code slug} and workspace {@code type} are intentionally not editable here
     * (slug changes go through {@code GET /workspaces/slug-check} + onboarding to avoid duplicating
     * collision handling).
     */
    public record WorkspaceUpdateRequest(
            @NotBlank String name,
            String industry,
            String companySize,
            String websiteUrl,
            String description,
            String logoUrl) {}
}
