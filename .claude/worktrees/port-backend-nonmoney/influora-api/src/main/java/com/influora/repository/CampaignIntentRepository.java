package com.influora.repository;

import com.influora.domain.entity.CampaignIntent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignIntentRepository extends JpaRepository<CampaignIntent, String> {

    /** Tenant-scoped finders (Guardrail 4). */
    List<CampaignIntent> findByConversationIdAndWorkspaceId(String conversationId, String workspaceId);

    Optional<CampaignIntent> findByIdAndWorkspaceId(String id, String workspaceId);
}
