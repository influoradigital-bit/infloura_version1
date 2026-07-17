package com.influora.repository;

import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.enums.CampaignTemplateScope;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * B5 — Campaign Templates. CUSTOM rows are workspace-scoped; every CUSTOM-facing query filters by
 * workspaceId (never an unscoped {@code findAll}). SYSTEM rows have {@code workspaceId == null}
 * and are read via {@link #findByScope}.
 */
public interface CampaignTemplateRepository extends JpaRepository<CampaignTemplate, String> {

    List<CampaignTemplate> findByScope(CampaignTemplateScope scope);

    /** Tenant-scoped — a workspace's own CUSTOM templates only. */
    List<CampaignTemplate> findByScopeAndWorkspaceId(CampaignTemplateScope scope, String workspaceId);

    /** Ownership-checked single lookup for CUSTOM delete/get, mirroring {@code findByIdAndWorkspaceId} elsewhere. */
    Optional<CampaignTemplate> findByIdAndWorkspaceId(String id, String workspaceId);
}
