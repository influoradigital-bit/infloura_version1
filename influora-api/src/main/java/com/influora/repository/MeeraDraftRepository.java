package com.influora.repository;

import com.influora.domain.entity.MeeraDraft;
import com.influora.domain.enums.DraftStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;2.4). */
public interface MeeraDraftRepository extends JpaRepository<MeeraDraft, String> {

    /**
     * Ownership-scoped single read, and the one the approve/discard routes use. Scoping in the query
     * rather than after the fetch is what makes another creator's draft id a plain 404 instead of a
     * 403 that confirms the id exists.
     */
    Optional<MeeraDraft> findByIdAndCreatorProfileId(String id, String creatorProfileId);

    /**
     * "My pending drafts", newest first — backed by {@code idx_meera_drafts_creator_created}. Status
     * is a parameter rather than hard-coded to {@link DraftStatus#PENDING} because the send log and
     * the level-up counter both need the {@link DraftStatus#SENT} list over the same index.
     */
    List<MeeraDraft> findByCreatorProfileIdAndStatusOrderByCreatedAtDesc(
            String creatorProfileId, DraftStatus status);
}
