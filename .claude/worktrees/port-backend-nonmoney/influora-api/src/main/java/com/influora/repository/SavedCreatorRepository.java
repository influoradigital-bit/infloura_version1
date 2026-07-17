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
}
