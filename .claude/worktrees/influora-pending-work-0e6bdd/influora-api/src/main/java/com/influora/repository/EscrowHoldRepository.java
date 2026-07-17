package com.influora.repository;

import com.influora.domain.entity.EscrowHold;
import com.influora.domain.enums.EscrowStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscrowHoldRepository extends JpaRepository<EscrowHold, String> {

    Optional<EscrowHold> findByIdempotencyKey(String idempotencyKey);

    Optional<EscrowHold> findByIdAndWorkspaceId(String id, String workspaceId);

    List<EscrowHold> findByWorkspaceIdAndStatus(String workspaceId, EscrowStatus status);

    List<EscrowHold> findByCampaignId(String campaignId);

    List<EscrowHold> findByMilestoneId(String milestoneId);
}
