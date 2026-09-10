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

    /**
     * SPEC.md &sect;3.6 — {@code get_my_deals} populates {@code brief_id} with the brief attached to
     * a collaboration, when one exists.
     *
     * <p>Ownership-scoped for the same reason as the method above: {@code collaboration_id} is not
     * an FK on this table (see {@link CreatorBrief}), so it is not a key another creator's row can
     * be reached through, and scoping the query rather than checking after the fetch keeps that
     * true if a collaboration is ever re-assigned.
     *
     * <p>{@code First} rather than a unique constraint: nothing in the schema forbids two briefs on
     * one collaboration (a creator may paste a revised brief for a deal she already has), and the
     * newest is not necessarily the interesting one, so the caller takes whichever row the index
     * yields rather than this method pretending there is exactly one.
     *
     * <p>Returns empty for every deal until Wave 4 lands {@code CreatorBriefService} and briefs
     * start being written; the field is {@code @JsonInclude(NON_NULL)}, so it is simply absent from
     * the payload rather than null until then.
     */
    Optional<CreatorBrief> findFirstByCollaborationIdAndCreatorProfileId(
            String collaborationId, String creatorProfileId);
}
