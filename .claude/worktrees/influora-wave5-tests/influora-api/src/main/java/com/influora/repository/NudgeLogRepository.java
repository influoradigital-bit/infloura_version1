package com.influora.repository;

import com.influora.domain.entity.NudgeLog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NudgeLogRepository extends JpaRepository<NudgeLog, String> {

    /** Tenant-scoped lookup for the click/purchase callback — resolves the row THEN the caller
     * checks {@code workspaceId} ownership (Guardrail 2 / schema lock §5.2). */
    Optional<NudgeLog> findByIdAndWorkspaceId(String id, String workspaceId);

    List<NudgeLog> findByWorkspaceIdOrderByShownAtDesc(String workspaceId);
}
