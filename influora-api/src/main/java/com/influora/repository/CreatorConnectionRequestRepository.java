package com.influora.repository;

import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.enums.ConnectionRequestStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CreatorConnectionRequestRepository
        extends JpaRepository<CreatorConnectionRequest, String>,
                JpaSpecificationExecutor<CreatorConnectionRequest> {

    /** {@code uk_ccr_workspace_creator} — at most one row per (workspace, creator) can ever exist. */
    Optional<CreatorConnectionRequest> findByWorkspaceIdAndExternalCreatorId(
            String workspaceId, String externalCreatorId);

    List<CreatorConnectionRequest> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    /** {@code ExternalCreatorLinkService#onCreatorIdentified} — every still-open request against
     * this external creator flips to JOINED and gets its own event. */
    List<CreatorConnectionRequest> findByExternalCreatorIdAndStatusIn(
            String externalCreatorId, List<ConnectionRequestStatus> statuses);
}
