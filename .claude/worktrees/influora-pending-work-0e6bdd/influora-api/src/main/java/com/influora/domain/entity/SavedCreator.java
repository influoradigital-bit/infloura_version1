package com.influora.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "saved_creators")
public class SavedCreator {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "creator_profile_id", nullable = false, length = 26)
    private String creatorProfileId;

    @Column(nullable = false)
    private boolean saved;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SavedCreator() {}

    public static SavedCreator of(String id, String workspaceId, String creatorProfileId, boolean saved) {
        SavedCreator row = new SavedCreator();
        row.id = id;
        row.workspaceId = workspaceId;
        row.creatorProfileId = creatorProfileId;
        row.saved = saved;
        Instant now = Instant.now();
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getCreatorProfileId() {
        return creatorProfileId;
    }

    public boolean isSaved() {
        return saved;
    }

    public void setSaved(boolean saved) {
        this.saved = saved;
        this.updatedAt = Instant.now();
    }
}
