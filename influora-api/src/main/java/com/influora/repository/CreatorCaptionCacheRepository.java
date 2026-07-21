package com.influora.repository;

import com.influora.domain.entity.CreatorCaptionCache;
import com.influora.domain.enums.CaptionTagStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorCaptionCacheRepository extends JpaRepository<CreatorCaptionCache, String> {

    Optional<CreatorCaptionCache> findByCreatorProfileIdAndIgMediaId(
            String creatorProfileId, String igMediaId);

    /** Batch page for {@code CreatorThemeTaggingJob} — oldest-first so backlog drains in order. */
    List<CreatorCaptionCache> findByTagStatusOrderByCreatedAtAsc(
            CaptionTagStatus status, Pageable pageable);
}
