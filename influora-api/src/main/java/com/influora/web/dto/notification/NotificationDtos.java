package com.influora.web.dto.notification;

import com.influora.domain.entity.Notification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** DTOs for the notification endpoints (Domain B). */
public final class NotificationDtos {

    private NotificationDtos() {}

    /** Response for GET /notifications (paginated list). */
    public record NotificationListResponse(
            List<NotificationResponse> notifications, long unreadCount, int page, int size) {}

    /** Single notification in the list. */
    public record NotificationResponse(
            String id,
            String eventType,
            String title,
            String body,
            String link,
            boolean isRead,
            Instant createdAt) {

        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(
                    n.getId(),
                    n.getEventType(),
                    n.getTitle(),
                    n.getBody(),
                    n.getLink(),
                    n.isRead(),
                    n.getCreatedAt());
        }
    }

    /** Request to mark notification(s) as read. */
    public record MarkReadRequest(@NotBlank String notificationId) {}

    /** Response for mark-read operation. */
    public record MarkReadResponse(boolean success, long newUnreadCount) {}

    /** Request to unsubscribe from email notifications. */
    public record UnsubscribeRequest(@NotBlank String eventType) {}

    /** Response for unsubscribe operation. */
    public record UnsubscribeResponse(boolean success, String eventType) {}

    /**
     * Single row for GET /notifications/preferences — matches the client's
     * NotificationPreference shape in src/lib/api.ts (~line 1584). Sparse: only event types the
     * user has explicitly touched are returned; anything absent is implicitly subscribed
     * (mirrors NotificationService#isUnsubscribed's orElse(false) default).
     */
    public record NotificationPreferenceItem(String eventType, boolean unsubscribed) {}

    /** Response for GET /notifications/preferences. */
    public record PreferencesResponse(List<NotificationPreferenceItem> preferences) {}

    /** Request to set a single event type's email subscription state. */
    public record SetPreferenceRequest(
            @NotBlank @Size(max = 64) String eventType, boolean subscribed) {}

    /** Response for POST /notifications/preferences. */
    public record SetPreferenceResponse(boolean success, String eventType, boolean unsubscribed) {}
}
