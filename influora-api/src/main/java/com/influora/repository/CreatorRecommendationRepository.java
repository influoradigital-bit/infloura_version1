package com.influora.repository;

import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Meera intelligence v1, slice 2 (spec 8.2) -- {@code creator_recommendations}. */
public interface CreatorRecommendationRepository extends JpaRepository<CreatorRecommendation, String> {

    /**
     * The {@code source_ref}s already stored for these refs -- the replay check that keeps a
     * replayed write-back or challenge start from hitting {@code uk_creator_rec_source}.
     */
    @Query(
            "SELECT r.sourceRef FROM CreatorRecommendation r WHERE r.creatorProfileId = :profileId"
                    + " AND r.source = :source AND r.sourceRef IN :refs")
    List<String> findExistingSourceRefs(
            @Param("profileId") String creatorProfileId,
            @Param("source") CreatorRecommendationSource source,
            @Param("refs") Collection<String> sourceRefs);

    /** Rows still being evaluated (OPEN, MATCHED), in the matcher's order: created_at, then id. */
    List<CreatorRecommendation> findByCreatorProfileIdAndStatusInOrderByCreatedAtAscIdAsc(
            String creatorProfileId, Collection<CreatorRecommendationStatus> statuses);

    /** Every post id that already fills one of this creator's recommendations. */
    @Query(
            "SELECT r.matchedMediaId FROM CreatorRecommendation r WHERE r.creatorProfileId = :profileId"
                    + " AND r.matchedMediaId IS NOT NULL")
    List<String> findClaimedMediaIds(@Param("profileId") String creatorProfileId);

    /** The rows {@code followed_recommendations} is built from, oldest first. */
    List<CreatorRecommendation> findByCreatorProfileIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
            String creatorProfileId, Instant createdSince);

    /** DPDP export: a conversation's plan and script rows, oldest first. */
    List<CreatorRecommendation> findByCreatorProfileIdAndConversationIdOrderByCreatedAtAscIdAsc(
            String creatorProfileId, String conversationId);

    /**
     * DPDP account deletion (Kabir M-1): every row of the creator, challenge rows included. The
     * account delete is a soft delete of {@code users} and never removes {@code creator_profiles},
     * so {@code fk_creator_rec_profile}'s cascade does not cover it.
     */
    @Modifying
    @Query("DELETE FROM CreatorRecommendation r WHERE r.creatorProfileId = :profileId")
    int deleteByCreatorProfileId(@Param("profileId") String creatorProfileId);

    /** DPDP: a deleted conversation takes its plan and script rows with it. */
    @Modifying
    @Query("DELETE FROM CreatorRecommendation r WHERE r.creatorProfileId = :profileId AND r.conversationId = :conversationId")
    int deleteByCreatorProfileIdAndConversationId(
            @Param("profileId") String creatorProfileId, @Param("conversationId") String conversationId);
}
