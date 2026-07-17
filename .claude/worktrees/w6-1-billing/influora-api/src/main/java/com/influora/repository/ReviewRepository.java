package com.influora.repository;

import com.influora.domain.entity.Review;
import com.influora.domain.enums.ReviewerType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, String> {

    boolean existsByCollaborationIdAndReviewerType(String collaborationId, ReviewerType reviewerType);

    Optional<Review> findByIdAndCollaborationId(String id, String collaborationId);

    /**
     * Reviews left by brands about the given creator (V-GA-8). Scoped via collaboration ownership
     * — never returns reviews for another creator's collaborations.
     */
    @Query(
            "SELECT r FROM Review r WHERE r.reviewerType = :reviewerType AND r.hidden = false "
                    + "AND r.collaborationId IN ("
                    + "SELECT c.id FROM Collaboration c WHERE c.creatorId = :creatorUserId) "
                    + "ORDER BY r.createdAt DESC")
    List<Review> findReceivedByCreatorUserId(
            @Param("creatorUserId") String creatorUserId,
            @Param("reviewerType") ReviewerType reviewerType);

    /**
     * P2-14 — reviews left by creators about the given brand workspace. Scoped via collaboration
     * ownership — never returns reviews for another workspace's collaborations.
     */
    @Query(
            "SELECT r FROM Review r WHERE r.reviewerType = :reviewerType AND r.hidden = false "
                    + "AND r.collaborationId IN ("
                    + "SELECT c.id FROM Collaboration c "
                    + "JOIN Campaign camp ON c.campaignId = camp.id "
                    + "WHERE camp.workspaceId = :workspaceId) "
                    + "ORDER BY r.createdAt DESC")
    List<Review> findReceivedByBrandWorkspaceId(
            @Param("workspaceId") String workspaceId,
            @Param("reviewerType") ReviewerType reviewerType);
}
