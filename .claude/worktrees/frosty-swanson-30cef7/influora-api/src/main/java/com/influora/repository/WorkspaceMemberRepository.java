package com.influora.repository;

import com.influora.domain.entity.WorkspaceMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, String> {

    Optional<WorkspaceMember> findFirstByUserIdAndActiveTrue(String userId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserIdAndActiveTrue(String workspaceId, String userId);
}
