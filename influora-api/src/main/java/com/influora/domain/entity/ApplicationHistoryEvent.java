package com.influora.domain.entity;

import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Append-only application/deal lifecycle timeline. One row per event, NEVER updated or deleted —
 * {@link #createdAt} is set once at construction and the column is {@code updatable = false}, and
 * this entity deliberately exposes no setters at all.
 *
 * <p>This is the persistent history {@link Collaboration} itself cannot provide: {@code
 * Collaboration.updatedAt} is overwritten on every transition, so the application journey
 * (applied -&gt; viewed -&gt; accepted/rejected -&gt; ...) cannot be reconstructed from it alone.
 *
 * <p>{@code applicationId} is always the backing {@link Collaboration} id — application and,
 * once accepted, the deal room share the same row (see {@code CreatorApplicationMapper}'s {@code
 * dealId} javadoc for why). {@code dealRoomId} stays {@code null} until the application is
 * actually accepted; from {@code APPLICATION_ACCEPTED} on, callers populate it with the same id,
 * so a reader can tell whether an event happened before or after the deal room existed without
 * cross-referencing {@code Collaboration.status}.
 */
@Entity
@Table(name = "application_history_events")
public class ApplicationHistoryEvent {

    @Id
    @Column(name = "id", length = 26)
    private String historyId;

    /**
     * DB-assigned {@code AUTO_INCREMENT} ordering tiebreak for {@link #createdAt} (a {@code
     * TIMESTAMP(3)} column — millisecond precision, and two rows can genuinely land in the same
     * millisecond, e.g. {@code CreatorCampaignService#recordApplicationHistory}'s back-to-back
     * CAMPAIGN_APPLIED/APPLICATION_RECEIVED writes). Never set by application code
     * ({@code insertable = false, updatable = false}) — MySQL assigns it on INSERT, monotonically,
     * with no shared in-JVM generator state to get wrong. See the V69 migration's comment for why
     * this was chosen over a monotonic ULID id.
     */
    @Column(name = "sequence_no", insertable = false, updatable = false)
    private Long sequenceNo;

    @Column(name = "campaign_id", nullable = false, length = 26)
    private String campaignId;

    @Column(name = "application_id", nullable = false, length = 26)
    private String applicationId;

    @Column(name = "deal_room_id", length = 26)
    private String dealRoomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private ApplicationHistoryEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_status", nullable = false)
    private CollaborationStatus eventStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ApplicationHistoryActorType actorType;

    @Column(name = "actor_id", length = 26)
    private String actorId;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /** Optional. Free-form reason text (e.g. a rejection reason) or a small JSON blob. */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    /** Optional. Frontend route for "jump to this" navigation off the timeline. */
    @Column(name = "target_route", length = 255)
    private String targetRoute;

    /** Optional. Id the route above resolves against (usually {@code applicationId} again). */
    @Column(name = "target_id", length = 26)
    private String targetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApplicationHistoryEvent() {}

    public static ApplicationHistoryEvent create(
            String historyId,
            String campaignId,
            String applicationId,
            String dealRoomId,
            ApplicationHistoryEventType eventType,
            CollaborationStatus eventStatus,
            ApplicationHistoryActorType actorType,
            String actorId,
            String description,
            String metadata,
            String targetRoute,
            String targetId) {
        ApplicationHistoryEvent e = new ApplicationHistoryEvent();
        e.historyId = historyId;
        e.campaignId = campaignId;
        e.applicationId = applicationId;
        e.dealRoomId = dealRoomId;
        e.eventType = eventType;
        e.eventStatus = eventStatus;
        e.actorType = actorType;
        e.actorId = actorId;
        e.description = description;
        e.metadata = metadata;
        e.targetRoute = targetRoute;
        e.targetId = targetId;
        e.createdAt = Instant.now();
        return e;
    }

    public String getHistoryId() {
        return historyId;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getDealRoomId() {
        return dealRoomId;
    }

    public ApplicationHistoryEventType getEventType() {
        return eventType;
    }

    public CollaborationStatus getEventStatus() {
        return eventStatus;
    }

    public ApplicationHistoryActorType getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getDescription() {
        return description;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getTargetRoute() {
        return targetRoute;
    }

    public String getTargetId() {
        return targetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
