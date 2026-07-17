package com.influora.domain.entity;

import com.influora.common.TextSanitizer;
import com.influora.domain.enums.ReviewerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Post-completion collaboration review (Task #29 / CEO §1.2). Creator rates brand and brand rates
 * creator — same table, distinguished by {@link #reviewerType}. Reviewer identity is always stored
 * ({@link #reviewerUserId}); no anonymous reviews.
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "collaboration_id", nullable = false, length = 26)
    private String collaborationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_type", nullable = false)
    private ReviewerType reviewerType;

    @Column(name = "reviewer_user_id", nullable = false, length = 26)
    private String reviewerUserId;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.TINYINT)
    private int stars;

    @Column(name = "review_text", columnDefinition = "TEXT")
    private String reviewText;

    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Review() {}

    public static Review create(
            String id,
            String collaborationId,
            ReviewerType reviewerType,
            String reviewerUserId,
            int stars,
            String text) {
        Review review = new Review();
        review.id = id;
        review.collaborationId = collaborationId;
        review.reviewerType = reviewerType;
        review.reviewerUserId = reviewerUserId;
        review.stars = stars;
        String sanitized = TextSanitizer.sanitizePlainText(text);
        review.reviewText =
                sanitized != null && !sanitized.isBlank() ? sanitized : null;
        review.hidden = false;
        Instant now = Instant.now();
        review.createdAt = now;
        review.updatedAt = now;
        return review;
    }

    public String getId() {
        return id;
    }

    public String getCollaborationId() {
        return collaborationId;
    }

    public ReviewerType getReviewerType() {
        return reviewerType;
    }

    public String getReviewerUserId() {
        return reviewerUserId;
    }

    public int getStars() {
        return stars;
    }

    public String getReviewText() {
        return reviewText;
    }

    public boolean isHidden() {
        return hidden;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
