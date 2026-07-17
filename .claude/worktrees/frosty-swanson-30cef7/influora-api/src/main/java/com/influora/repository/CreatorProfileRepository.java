package com.influora.repository;

import com.influora.domain.entity.CreatorProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CreatorProfileRepository
        extends JpaRepository<CreatorProfile, String>, JpaSpecificationExecutor<CreatorProfile> {

    Optional<CreatorProfile> findByIdAndDiscoverableTrue(String id);

    Optional<CreatorProfile> findByUserId(String userId);
}
