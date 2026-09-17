package com.influora.repository;

import com.influora.domain.entity.SavedCreator;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedCreatorRepository extends JpaRepository<SavedCreator, String> {

    Optional<SavedCreator> findByWorkspaceIdAndCreatorProfileId(String workspaceId, String creatorProfileId);

    List<SavedCreator> findByWorkspaceIdAndCreatorProfileIdInAndSavedTrue(
            String workspaceId, Collection<String> creatorProfileIds);

    // SM-0.1 [vikram · 2026-09-17] — backs CreatorDiscoveryService#enforceSavedCreatorLimit
    // (Entitlement.SAVED_CREATORS, CAPACITY), mirroring
    // WorkspaceMemberRepository#countByWorkspaceIdAndActiveTrue's identical shape for SEATS.
    // Source: wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md §3.2
    long countByWorkspaceIdAndSavedTrue(String workspaceId);
}
