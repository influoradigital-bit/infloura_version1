package com.influora.repository;

import com.influora.domain.entity.BrandProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandProfileRepository extends JpaRepository<BrandProfile, String> {

    /** Tenant-scoped lookup — one profile per workspace (Guardrail 4). */
    Optional<BrandProfile> findByWorkspaceId(String workspaceId);
}
