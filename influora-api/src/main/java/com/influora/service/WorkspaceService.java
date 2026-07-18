package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace-level read/update/switch operations — H-16 and L-9
 * (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md). Before this class, {@code
 * WorkspaceController} only exposed slug-check; there was no way to read/update a workspace's own
 * profile, no way to list a multi-workspace user's other memberships, and no way to switch the
 * active workspace context short of logging out and back in.
 *
 * <p>Seat/invite mutations stay in {@link WorkspaceMemberService} — this class owns the workspace
 * entity itself (profile CRUD, membership listing/switching), not the member rows.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final BrandContextService brandContext;
    private final JwtService jwtService;
    private final com.influora.service.brand.AnalyzeSiteTriggerService analyzeSiteTrigger;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            BrandContextService brandContext,
            JwtService jwtService,
            com.influora.service.brand.AnalyzeSiteTriggerService analyzeSiteTrigger) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.brandContext = brandContext;
        this.jwtService = jwtService;
        this.analyzeSiteTrigger = analyzeSiteTrigger;
    }

    /** L-9 — {@code GET /workspaces/me}. Any active member may read. */
    public Workspace getMyWorkspace(AuthPrincipal principal) {
        return brandContext.requireBrandWorkspace(principal);
    }

    /** Loose sanity check for {@code email} — belt-and-suspenders alongside the DTO's {@code
     * @Email} annotation (see {@link com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceUpdateRequest}),
     * kept here so it is exercised by a plain service-level unit test without standing up a
     * MockMvc/bean-validation harness (this codebase has none — see {@code AuthControllerTest}). */
    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /**
     * Loose sanity check for {@code phone} — belt-and-suspenders alongside the DTO's {@code
     * @Pattern} annotation (see {@link
     * com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceUpdateRequest}), same precedent
     * as {@link #EMAIL_PATTERN}. Only checks the allowed character set; digit-count range is
     * enforced separately in {@link #isValidPhoneDigitCount} so international numbers with
     * formatting punctuation aren't over-restricted.
     */
    private static final java.util.regex.Pattern PHONE_CHARS_PATTERN =
            java.util.regex.Pattern.compile("^[+]?[0-9()\\-\\s]+$");

    private static boolean isValidPhone(String phone) {
        if (!PHONE_CHARS_PATTERN.matcher(phone).matches()) {
            return false;
        }
        int digitCount = (int) phone.chars().filter(Character::isDigit).count();
        return digitCount >= 7 && digitCount <= 15;
    }

    /** L-9 — {@code GET /workspaces/me}/{@code PATCH /workspaces/me} request/response DTOs, see
     * {@link com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceReadResponse}/{@code
     * WorkspaceUpdateRequest} for the fields deliberately excluded (slug, type). */
    @Transactional
    public Workspace updateMyWorkspace(
            AuthPrincipal principal,
            String name,
            String email,
            String phone,
            String industry,
            String companySize,
            String websiteUrl,
            String description,
            String logoUrl) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember actingMember = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);

        if (name == null || name.isBlank()) {
            throw new ApiException(
                    "VALIDATION_ERROR", "Workspace name cannot be blank", HttpStatus.BAD_REQUEST);
        }
        if (email != null && !email.isBlank() && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ApiException("VALIDATION_ERROR", "Invalid email format", HttpStatus.BAD_REQUEST);
        }
        if (phone != null && !phone.isBlank() && !isValidPhone(phone)) {
            throw new ApiException("VALIDATION_ERROR", "Invalid phone format", HttpStatus.BAD_REQUEST);
        }

        String oldWebsiteUrl = workspace.getWebsiteUrl();
        workspace.applyCompanyDetails(
                name,
                workspace.getSlug(),
                workspace.getType(),
                industry,
                companySize,
                websiteUrl,
                description,
                logoUrl);
        workspace.updateContactEmail(email);
        workspace.updatePhone(phone);
        Workspace saved = workspaceRepository.save(workspace);

        // W4-2 / A10 / TrendSpark — re-trigger analysis when website URL changes
        if (hasChanged(oldWebsiteUrl, websiteUrl)) {
            analyzeSiteTrigger.trigger(saved.getId(), websiteUrl);
        }

        return saved;
    }

    /**
     * H-16 — {@code GET /workspaces/mine}. All of the caller's active workspace memberships,
     * oldest first (same ordering {@code WorkspaceMemberRepository#findFirstByUserIdAndActiveTrue}
     * uses to pick the login-time default) — the switch UI's list.
     */
    public List<WorkspaceSummary> listMyWorkspaces(AuthPrincipal principal) {
        if (principal == null || principal.getUserType() != UserType.BRAND) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for brand accounts only", HttpStatus.FORBIDDEN);
        }
        List<WorkspaceMember> memberships =
                workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(principal.getUserId());
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<String, Workspace> workspacesById =
                workspaceRepository.findAllById(memberships.stream().map(WorkspaceMember::getWorkspaceId).toList())
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(Workspace::getId, w -> w));
        return memberships.stream()
                .filter(m -> workspacesById.containsKey(m.getWorkspaceId()))
                .sorted(Comparator.comparing(WorkspaceMember::getCreatedAt))
                .map(
                        m -> {
                            Workspace w = workspacesById.get(m.getWorkspaceId());
                            return new WorkspaceSummary(w.getId(), w.getName(), w.getSlug(), m.getRole().name());
                        })
                .toList();
    }

    /**
     * H-16 — {@code POST /workspaces/switch}. Mints a fresh short-lived access token stamped with
     * {@code targetWorkspaceId} instead of the arbitrary one login picked (see the repository
     * javadoc on {@code findFirstByUserIdAndActiveTrue} for the ordering fix that made login
     * itself deterministic). Requires the caller to actually be an active member of the target
     * workspace — never trusts the workspaceId alone (TECH-STACK.md rule #2). Does not touch the
     * refresh token/cookie — only the access token is workspace-scoped.
     */
    public IssuedAccessToken switchWorkspace(AuthPrincipal principal, String targetWorkspaceId) {
        if (principal == null || principal.getUserType() != UserType.BRAND) {
            throw new ApiException(
                    "WRONG_USER_TYPE", "This endpoint is for brand accounts only", HttpStatus.FORBIDDEN);
        }
        workspaceMemberRepository
                .findByWorkspaceIdAndUserIdAndActiveTrue(targetWorkspaceId, principal.getUserId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "FORBIDDEN", "You are not a member of this workspace", HttpStatus.FORBIDDEN));
        Workspace workspace =
                workspaceRepository
                        .findById(targetWorkspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));
        if (workspace.isSuspended()) {
            throw new ApiException(
                    "WORKSPACE_SUSPENDED",
                    "This workspace has been suspended. Contact support.",
                    HttpStatus.FORBIDDEN);
        }
        String accessToken =
                jwtService.createAccessToken(
                        principal.getUserId(), principal.getUserType(), principal.getEmail(), targetWorkspaceId);
        return new IssuedAccessToken(accessToken, jwtService.getAccessExpirySeconds());
    }

    public record WorkspaceSummary(String id, String name, String slug, String role) {}

    public record IssuedAccessToken(String accessToken, long expiresInSeconds) {}

    private static boolean hasChanged(String oldValue, String newValue) {
        String oldNormalized = oldValue != null && !oldValue.isBlank() ? oldValue.trim() : null;
        String newNormalized = newValue != null && !newValue.isBlank() ? newValue.trim() : null;
        // Both blank → no change
        if (oldNormalized == null && newNormalized == null) {
            return false;
        }
        // One blank, one non-blank → changed ONLY if newValue is non-blank (setting URL for first
        // time or changing it); clearing to blank does NOT count as a trigger-worthy change.
        if (oldNormalized == null) {
            return newNormalized != null;
        }
        if (newNormalized == null) {
            return false;
        }
        // Both non-blank → check equality
        return !oldNormalized.equals(newNormalized);
    }
}
