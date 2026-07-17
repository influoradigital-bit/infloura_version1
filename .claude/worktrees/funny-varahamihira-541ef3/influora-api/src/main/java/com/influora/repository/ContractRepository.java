package com.influora.repository;

import com.influora.domain.entity.Contract;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, String> {

    Optional<Contract> findByIdAndWorkspaceId(String id, String workspaceId);

    List<Contract> findByCollaborationIdOrderByVersionDesc(String collaborationId);

    List<Contract> findByWorkspaceId(String workspaceId);
}
