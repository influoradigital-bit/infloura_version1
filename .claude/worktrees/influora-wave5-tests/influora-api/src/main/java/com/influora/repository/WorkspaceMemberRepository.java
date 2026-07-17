package com.influora.repository;

import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, String> {

    /**
     * @deprecated H-16: unordered — for a multi-workspace user (every invite-acceptor has ≥2
     *     active memberships) this returns whichever row the DB feels like handing back first,
     *     so the workspace stamped onto the login JWT is nondeterministic across logins. Use
     *     {@link #findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc} instead — kept only for
     *     call sites (e.g. {@code BrandContextService}) not in this pass's scope.
     */
    @Deprecated
    Optional<WorkspaceMember> findFirstByUserIdAndActiveTrue(String userId);

    /**
     * H-16 fix: deterministic ordering by {@code created_at} ascending — the user's oldest active
     * membership (their original/home workspace, e.g. the OWNER row created at brand signup) is
     * always picked first, so the same user logging in twice always lands in the same workspace.
     * Used by {@code AuthService} to stamp the JWT at login/refresh; a {@code POST
     * /workspace/members/switch} endpoint lets a multi-workspace user move to a different one.
     */
    Optional<WorkspaceMember> findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc(String userId);

    /** All active members of a workspace — powers AdminBrandController's team-members read. */
    List<WorkspaceMember> findByWorkspaceIdAndActiveTrue(String workspaceId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserIdAndActiveTrue(String workspaceId, String userId);

    /**
     * Resolves the workspace's OWNER member row — used to find who should receive
     * workspace-level notifications (e.g. the contract-signed email) when the recipient isn't
     * otherwise scoped to a specific acting user.
     */
    Optional<WorkspaceMember> findFirstByWorkspaceIdAndRoleAndActiveTrue(
            String workspaceId, MemberRole role);

    Optional<WorkspaceMember> findByIdAndWorkspaceId(String id, String workspaceId);

    long countByWorkspaceIdAndRoleAndActiveTrue(String workspaceId, MemberRole role);

    long countByWorkspaceIdAndActiveTrue(String workspaceId);
}
