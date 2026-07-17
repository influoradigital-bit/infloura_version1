package com.influora.domain.entity;

import com.influora.domain.enums.MemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "workspace_members")
public class WorkspaceMember {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "user_id", nullable = false, length = 26)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkspaceMember() {}

    public static WorkspaceMember owner(String id, String workspaceId, String userId) {
        WorkspaceMember m = new WorkspaceMember();
        m.id = id;
        m.workspaceId = workspaceId;
        m.userId = userId;
        m.role = MemberRole.OWNER;
        m.active = true;
        m.joinedAt = Instant.now();
        m.createdAt = Instant.now();
        return m;
    }

    /** Invite acceptance — mirrors {@link #owner(String, String, String)} but with the invited role. */
    public static WorkspaceMember fromInvite(
            String id, String workspaceId, String userId, MemberRole role) {
        WorkspaceMember m = new WorkspaceMember();
        m.id = id;
        m.workspaceId = workspaceId;
        m.userId = userId;
        m.role = role;
        m.active = true;
        m.joinedAt = Instant.now();
        m.createdAt = Instant.now();
        return m;
    }

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getUserId() {
        return userId;
    }

    public MemberRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    /** No {@code updated_at} column on {@code workspace_members} — do not touch one. */
    public void deactivate() {
        this.active = false;
    }

    /**
     * L-9: role-change / ownership-transfer support. No {@code updated_at} column to touch, same
     * as {@link #deactivate()}. Callers ({@code WorkspaceMemberService#changeRole}/{@code
     * #transferOwnership}) are responsible for authorization and for the sole-active-OWNER
     * invariant — this setter itself does not gate {@code newRole}.
     */
    public void changeRole(MemberRole newRole) {
        this.role = newRole;
    }
}
