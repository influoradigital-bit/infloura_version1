package com.influora.repository;

import com.influora.domain.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, String id);
}
