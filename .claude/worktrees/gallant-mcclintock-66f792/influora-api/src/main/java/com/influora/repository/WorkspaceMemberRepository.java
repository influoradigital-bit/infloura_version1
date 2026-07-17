package com.influora.repository;

import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, String> {

    Optional<WorkspaceMember> findFirstByUserIdAndActiveTrue(String userId);

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
}
