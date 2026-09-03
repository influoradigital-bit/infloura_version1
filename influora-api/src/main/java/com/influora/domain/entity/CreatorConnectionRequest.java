package com.influora.domain.entity;

import com.influora.domain.enums.ConnectionRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A brand workspace's ask to be introduced to one {@link ExternalCreator}
 * (T-CREATORCONNECT-0902, V20260902120000). One row per (workspace, external_creator) — {@code
 * uk_ccr_workspace_creator} means a brand can only ever have ONE live request per creator; a
 * re-request after {@code DECLINED} reopens this same row (see {@code
 * ExternalCreatorService#connect}) rather than inserting a second one, since the schema does not
 * allow it.
 */
@Entity
@Table(name = "creator_connection_requests")
public class CreatorConnectionRequest {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 26)
    private String workspaceId;

    @Column(name = "requested_by_user_id", nullable = false, length = 26)
    private String requestedByUserId;

    @Column(name = "external_creator_id", nullable = false, length = 26)
    private String externalCreatorId;

    @Column(length = 1000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConnectionRequestStatus status;

    @Column(name = "admin_notes", length = 1000)
    private String adminNotes;

    @Column(name = "handled_by", length = 26)
    private String handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "joined_notified_at")
    private Instant joinedNotifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreatorConnectionRequest() {}

    public String getId() {
        return id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getRequestedByUserId() {
        return requestedByUserId;
    }

    public String getExternalCreatorId() {
        return externalCreatorId;
    }

    public String getMessage() {
        return message;
    }

    public ConnectionRequestStatus getStatus() {
        return status;
    }

    public String getAdminNotes() {
        return adminNotes;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public Instant getJoinedNotifiedAt() {
        return joinedNotifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Reopens a DECLINED row for a fresh {@code connect} — the unique key forces row reuse.
     *
     * @deprecated Q3.2 (T-CREATORCONNECT-0902) — keeps {@code requestedByUserId} pinned to
     *     whoever made the ORIGINAL request, so if a different workspace member re-requests after
     *     a decline, the later {@code brand.connected_creator_joined} email still goes to the
     *     original requester (and is silently dropped if that user has since left the workspace).
     *     Use {@link #reopen(String, String)} so the member re-requesting NOW becomes the
     *     recipient of record. Kept only so any not-yet-updated caller still compiles.
     */
    @Deprecated
    public void reopen(String message) {
        reopen(message, this.requestedByUserId);
    }

    /**
     * Reopens a DECLINED row for a fresh {@code connect} — the unique key forces row reuse.
     * Re-stamps {@code requestedByUserId} to {@code userId} (Q3.2), the principal actually making
     * this reopen call, so the eventual join notification reaches whoever is asking now rather
     * than a possibly-removed original requester. A blank/null {@code userId} leaves the existing
     * requester untouched rather than nulling out a required column.
     *
     * <p>Does NOT clear {@code joinedNotifiedAt} (Q5.2): once a brand has genuinely been told a
     * creator joined, that stays true forever — it is not undone by the request cycling through
     * DECLINED and back to PENDING, and it must keep blocking a second {@code
     * brand.connected_creator_joined} email if a background Meta token refresh re-fires the
     * JOINED hook on this row after the reopen.
     */
    public void reopen(String message, String userId) {
        this.status = ConnectionRequestStatus.PENDING;
        this.message = message;
        if (userId != null && !userId.isBlank()) {
            this.requestedByUserId = userId;
        }
        this.adminNotes = null;
        this.handledBy = null;
        this.handledAt = null;
        this.updatedAt = Instant.now();
    }

    public void markContacted(String adminId, String notes) {
        this.status = ConnectionRequestStatus.CONTACTED;
        this.handledBy = adminId;
        this.handledAt = Instant.now();
        if (notes != null) {
            this.adminNotes = notes;
        }
        this.updatedAt = Instant.now();
    }

    public void markDeclined(String adminId, String notes) {
        this.status = ConnectionRequestStatus.DECLINED;
        this.handledBy = adminId;
        this.handledAt = Instant.now();
        if (notes != null) {
            this.adminNotes = notes;
        }
        this.updatedAt = Instant.now();
    }

    public void markInvited(String adminId, String notes) {
        // Invite moves the REQUEST to CONTACTED (the external creator itself moves to INVITED —
        // see ExternalCreator#markInvited); the request only reaches JOINED via the link hook.
        this.status = ConnectionRequestStatus.CONTACTED;
        this.handledBy = adminId;
        this.handledAt = Instant.now();
        if (notes != null) {
            this.adminNotes = notes;
        }
        this.updatedAt = Instant.now();
    }

    /** {@code ExternalCreatorLinkService#onCreatorIdentified} — the JOINED hook. */
    public void markJoined() {
        this.status = ConnectionRequestStatus.JOINED;
        this.updatedAt = Instant.now();
    }

    /** Stamped by {@code NotificationListener} after the {@code brand.connected_creator_joined}
     * email is queued — idempotent guard so a re-fired event never double-sends. */
    public void markJoinedNotified() {
        this.joinedNotifiedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final CreatorConnectionRequest r = new CreatorConnectionRequest();

        public Builder id(String id) {
            r.id = id;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            r.workspaceId = workspaceId;
            return this;
        }

        public Builder requestedByUserId(String requestedByUserId) {
            r.requestedByUserId = requestedByUserId;
            return this;
        }

        public Builder externalCreatorId(String externalCreatorId) {
            r.externalCreatorId = externalCreatorId;
            return this;
        }

        public Builder message(String message) {
            r.message = message;
            return this;
        }

        public CreatorConnectionRequest build() {
            r.status = ConnectionRequestStatus.PENDING;
            Instant now = Instant.now();
            r.createdAt = now;
            r.updatedAt = now;
            return r;
        }
    }
}
