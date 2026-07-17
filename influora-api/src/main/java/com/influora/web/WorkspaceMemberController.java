package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.entity.WorkspaceMemberInvite;
import com.influora.domain.enums.MemberRole;
import com.influora.security.AuthPrincipal;
import com.influora.service.WorkspaceMemberService;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.AcceptRequest;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.InviteRequest;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.InviteResponse;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.MemberResponse;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.SwitchWorkspaceRequest;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.SwitchWorkspaceResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seat invite/add-member flow — Task 23 (Phase 3c subscription billing). Mounted at {@code
 * /workspace/members} (full path {@code /api/v1/workspace/members}).
 *
 * <p>{@code /invite} and {@code DELETE /{memberId}} server-derive {@code workspaceId} from the
 * authenticated principal via {@code WorkspaceMemberService} → {@code
 * BrandContextService.requireBrandWorkspace} — never from a client-supplied value (TECH-STACK.md
 * rule #2). {@code /accept} is the one exception: see {@link WorkspaceMemberService} class
 * javadoc for why it still requires authentication (not a public/no-auth endpoint) despite the
 * task brief describing it that way — flagged there explicitly for Kavya's QA pass.
 */
@RestController
@RequestMapping("/workspace/members")
public class WorkspaceMemberController {

    private final WorkspaceMemberService workspaceMemberService;

    public WorkspaceMemberController(WorkspaceMemberService workspaceMemberService) {
        this.workspaceMemberService = workspaceMemberService;
    }

    @PostMapping("/invite")
    public ResponseEntity<ApiResponse<InviteResponse>> invite(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody InviteRequest body) {
        MemberRole role = parseRole(body.role());
        WorkspaceMemberInvite invite = workspaceMemberService.inviteMember(principal, body.email(), role);
        return ResponseEntity.ok(ApiResponse.ok(toInviteResponse(invite)));
    }

    @PostMapping("/accept")
    public ResponseEntity<ApiResponse<MemberResponse>> accept(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody AcceptRequest body) {
        WorkspaceMember member = workspaceMemberService.acceptInvite(principal, body.inviteToken());
        return ResponseEntity.ok(ApiResponse.ok(toMemberResponse(member)));
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String memberId) {
        workspaceMemberService.deactivateMember(principal, memberId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** {@code GET /workspace/members} — H-15: active members of the caller's own workspace. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<MemberResponse>>> list(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<MemberResponse> members =
                workspaceMemberService.listMembers(principal).stream()
                        .map(WorkspaceMemberController::toMemberResponse)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(members));
    }

    /** {@code GET /workspace/members/invites} — H-15: every invite issued for the caller's workspace. */
    @GetMapping("/invites")
    public ResponseEntity<ApiResponse<List<InviteResponse>>> listInvites(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<InviteResponse> invites =
                workspaceMemberService.listInvites(principal).stream()
                        .map(WorkspaceMemberController::toInviteResponse)
                        .toList();
        return ResponseEntity.ok(ApiResponse.ok(invites));
    }

    /** {@code POST /workspace/members/invites/{inviteId}/revoke} — H-15: cancel an outstanding invite. */
    @PostMapping("/invites/{inviteId}/revoke")
    public ResponseEntity<ApiResponse<Void>> revokeInvite(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String inviteId) {
        workspaceMemberService.revokeInvite(principal, inviteId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** {@code POST /workspace/members/switch} — H-16: move the caller's session to another workspace they belong to. */
    @PostMapping("/switch")
    public ResponseEntity<ApiResponse<SwitchWorkspaceResponse>> switchWorkspace(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody SwitchWorkspaceRequest body) {
        SwitchWorkspaceResponse response = workspaceMemberService.switchWorkspace(principal, body.workspaceId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    private static MemberRole parseRole(String raw) {
        try {
            return MemberRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new ApiException(
                    "INVALID_ROLE", "role must be one of ADMIN, MANAGER, MEMBER, VIEWER", HttpStatus.BAD_REQUEST);
        }
    }

    private static InviteResponse toInviteResponse(WorkspaceMemberInvite invite) {
        return new InviteResponse(
                invite.getId(),
                invite.getWorkspaceId(),
                invite.getEmail(),
                invite.getRole().name(),
                invite.getStatus().name(),
                invite.getExpiresAt());
    }

    private static MemberResponse toMemberResponse(WorkspaceMember member) {
        return new MemberResponse(
                member.getId(), member.getWorkspaceId(), member.getUserId(), member.getRole().name(), member.isActive());
    }
}
