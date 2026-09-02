package com.influora.web.dto.workspace;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
     * L-9 — {@code GET /workspaces/me} response.
     *
     * <p>[F-0462] {@code description} is surfaced here deliberately. This record originally
     * omitted it because {@code Workspace} had no getter for the column; the getter now exists
     * ({@link com.influora.domain.entity.Workspace#getDescription}), and the omission had become
     * a data-loss bug rather than a scoping note. {@code WorkspaceUpdateRequest} accepts {@code
     * description} and {@link com.influora.domain.entity.Workspace#updateCompanyDetails} assigns
     * it unconditionally, so a client that cannot READ the value back has no way to echo it on
     * the next full-replace PATCH — every settings save silently cleared the brand bio captured
     * at onboarding. A write-only field on a full-replace endpoint is a destructive contract.
     *
     * <p>{@code email} maps to {@code workspaces.billing_email} server-side (see {@link
     * com.influora.domain.entity.Workspace#updateContactEmail}) — same field the admin panel
     * already surfaces as "email" ({@code AdminBrandDtos.UpdateBrandRequest}). {@code phone} maps
     * to {@code workspaces.phone} (added by V20260718180000__workspace_phone.sql, see {@link
     * com.influora.domain.entity.Workspace#updatePhone}).
     */
    public record WorkspaceReadResponse(
            String id,
            String name,
            String slug,
            String email,
            String phone,
            String industry,
            String companySize,
            String websiteUrl,
            String description,
            String logoUrl,
            String verificationStatus) {}

    /**
     * L-9 — {@code PATCH /workspaces/me} request body. Full-replace semantics for every field
     * included below (not a deep merge) — a client omitting/nulling a field clears it, same
     * contract as the existing onboarding {@code BrandCompanyRequest}. {@code name} is the only
     * required field; {@code slug} and workspace {@code type} are intentionally not editable here
     * (slug changes go through {@code GET /workspaces/slug-check} + onboarding to avoid duplicating
     * collision handling).
     *
     * <p>{@code email} round-trips to {@code workspaces.billing_email} (see {@link
     * WorkspaceReadResponse}). {@code phone} round-trips to {@code workspaces.phone} — blank/null
     * clears it, same full-replace contract as every other field here. Character-set-only pattern
     * here (belt-and-suspenders); the digit-count range (7-15) is enforced in {@code
     * WorkspaceService#updateMyWorkspace}, same "loose sanity check alongside the DTO annotation"
     * precedent as the {@code email} field.
     */
    public record WorkspaceUpdateRequest(
            @NotBlank @Size(max = 200) String name,
            @Email @Size(max = 255) String email,
            @Size(max = 30)
                    @Pattern(regexp = "^$|^[+]?[0-9()\\-\\s]{7,20}$", message = "Invalid phone number format")
                    String phone,
            String industry,
            String companySize,
            @Size(max = 500)
                    @Pattern(
                            regexp = "^$|^(https?://)?([\\w-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$",
                            message = "Invalid website URL")
                    String websiteUrl,
            String description,
            String logoUrl) {}
}
