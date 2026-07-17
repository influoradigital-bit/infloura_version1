package com.influora.repository;

import com.influora.domain.entity.Campaign;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CampaignRepository
        extends JpaRepository<Campaign, String>, JpaSpecificationExecutor<Campaign> {

    Optional<Campaign> findByIdAndWorkspaceId(String id, String workspaceId);
}
