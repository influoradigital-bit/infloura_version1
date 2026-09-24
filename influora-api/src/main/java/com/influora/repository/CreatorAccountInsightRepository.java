package com.influora.repository;

import com.influora.domain.entity.CreatorAccountInsight;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CreatorAccountInsightRepository extends JpaRepository<CreatorAccountInsight, String> {

    /** The newest snapshot is the current one (immutable rows, see V20260924130000). */
    Optional<CreatorAccountInsight> findFirstByCreatorProfileIdOrderByFetchedAtDesc(String creatorProfileId);
}
