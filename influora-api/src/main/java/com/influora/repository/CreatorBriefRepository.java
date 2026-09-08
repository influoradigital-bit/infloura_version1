package com.influora.repository;

import com.influora.domain.entity.CreatorBrief;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.2). */
public interface CreatorBriefRepository extends JpaRepository<CreatorBrief, String> {

    /**
     * "My recent briefs", newest first — backed by {@code idx_creator_briefs_creator_created}.
     * Paged: a creator who pastes daily accumulates rows without bound, and the co-pilot page shows
     * only the most recent handful.
     */
    List<CreatorBrief> findByCreatorProfileIdOrderByCreatedAtDesc(
            String creatorProfileId, Pageable pageable);

    /**
     * Ownership-scoped single read. The {@code creatorProfileId} is part of the query, not checked
     * after the fetch: a brief is the creator's private record, and a miss must be indistinguishable
     * from another creator's brief so an id cannot be probed for existence.
     */
    Optional<CreatorBrief> findByIdAndCreatorProfileId(String id, String creatorProfileId);
}
