package com.influora.repository;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CreatorApplicationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CreatorProfileRepository
        extends JpaRepository<CreatorProfile, String>, JpaSpecificationExecutor<CreatorProfile> {

    Optional<CreatorProfile> findByIdAndDiscoverableTrue(String id);

    Optional<CreatorProfile> findByUserId(String userId);

    Optional<CreatorProfile> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCaseAndIdNot(String username, String id);

    /** Creator profiles awaiting an admin application decision — backs the pending-approvals queue. */
    List<CreatorProfile> findByApplicationStatusOrderByCreatedAtAsc(
            CreatorApplicationStatus applicationStatus, Pageable pageable);
}
