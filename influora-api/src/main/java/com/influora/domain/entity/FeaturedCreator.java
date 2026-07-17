package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "featured_creators")
public class FeaturedCreator {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(name = "featured_category", nullable = false, length = 64)
    private String featuredCategory;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "featured_from")
    private Instant featuredFrom;

    @Column(name = "featured_until")
    private Instant featuredUntil;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "featured_by_user_id", length = 26)
    private String featuredByUserId;

    @Column(name = "featured_reason", length = 500)
    private String featuredReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FeaturedCreator() {}

    public String getId() {
        return id;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public String getFeaturedCategory() {
        return featuredCategory;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public Instant getFeaturedFrom() {
        return featuredFrom;
    }

    public Instant getFeaturedUntil() {
        return featuredUntil;
    }

    public boolean isActive() {
        return active;
    }

    public String getFeaturedByUserId() {
        return featuredByUserId;
    }

    public String getFeaturedReason() {
        return featuredReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
