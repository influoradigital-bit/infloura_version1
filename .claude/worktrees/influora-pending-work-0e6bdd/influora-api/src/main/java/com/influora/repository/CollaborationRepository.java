package com.influora.repository;

import com.influora.domain.entity.Collaboration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollaborationRepository extends JpaRepository<Collaboration, String> {

    boolean existsByCampaignIdAndCreatorId(String campaignId, String creatorId);
}
