package com.influora.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.Ulids;
import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.entity.EmailPreference;
import com.influora.domain.entity.Notification;
import com.influora.repository.EmailOutboxRepository;
import com.influora.repository.EmailPreferenceRepository;
import com.influora.repository.NotificationRepository;
import com.influora.service.notification.event.NotificationEvent;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core notification service (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md). Creates in-app
 * notifications and queues emails via the transactional outbox pattern.
 *
 * <p>Idempotent: duplicate events for the same (eventType, entityId, userId) combination are
 * dropped via the {@code idempotencyKey} on the email_outbox table.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Events that are email-only (no in-app notification). */
    private static final Set<String> EMAIL_ONLY_EVENTS =
            Set.of("auth.otp", "auth.reset", "cron.monthly_statement");

    /** Events that are in-app only (no email). */
    private static final Set<String> IN_APP_ONLY_EVENTS =
            Set.of("ai.site_analyzed", "ai.campaign_recommended", "ai.credits_reset");

    private final NotificationRepository notificationRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final EmailPreferenceRepository emailPreferenceRepository;
    private final ObjectMapper objectMapper;

    public NotificationService(
            NotificationRepository notificationRepository,
            EmailOutboxRepository emailOutboxRepository,
            EmailPreferenceRepository emailPreferenceRepository,
            ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.emailOutboxRepository = emailOutboxRepository;
        this.emailPreferenceRepository = emailPreferenceRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates an in-app notification and queues an email (if applicable) for the given event.
     * Idempotent via the idempotency key.
     */
    @Transactional
    public void notify(
            NotificationEvent event,
            String title,
            String body,
            String link,
            String toEmail,
            String templateKey,
            Map<String, Object> templateData) {

        String idempotencyKey = buildIdempotencyKey(event);
        String eventType = event.eventType();

        // Check idempotency for email (in-app notifications are not deduplicated as the same
        // event from different sources may be valid, but emails are strictly deduplicated).
        if (!EMAIL_ONLY_EVENTS.contains(eventType) && !IN_APP_ONLY_EVENTS.contains(eventType)) {
            // Both channels
            createInAppNotification(event, title, body, link);
            queueEmailIfNotUnsubscribed(event, toEmail, templateKey, templateData, idempotencyKey);
        } else if (EMAIL_ONLY_EVENTS.contains(eventType)) {
            // Email only (OTP, password reset, monthly statement)
            queueEmailIfNotUnsubscribed(event, toEmail, templateKey, templateData, idempotencyKey);
        } else {
            // In-app only (AI events)
            createInAppNotification(event, title, body, link);
        }
    }

    /** Simplified overload for events that only need in-app notification. */
    @Transactional
    public void notifyInApp(NotificationEvent event, String title, String body, String link) {
        createInAppNotification(event, title, body, link);
    }

    private void createInAppNotification(
            NotificationEvent event, String title, String body, String link) {
        Notification notification =
                Notification.builder()
                        .id(Ulids.newUlid())
                        .userId(event.userId())
                        .workspaceId(event.workspaceId())
                        .eventType(event.eventType())
                        .title(title)
                        .body(body)
                        .link(link)
                        .build();
        notificationRepository.save(notification);
        log.debug(
                "Created in-app notification: userId={}, eventType={}",
                event.userId(),
                event.eventType());
    }

    private void queueEmailIfNotUnsubscribed(
            NotificationEvent event,
            String toEmail,
            String templateKey,
            Map<String, Object> templateData,
            String idempotencyKey) {

        if (toEmail == null || toEmail.isBlank()) {
            log.warn(
                    "No email address for event: userId={}, eventType={}",
                    event.userId(),
                    event.eventType());
            return;
        }

        // Check idempotency
        if (emailOutboxRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            log.debug("Duplicate email event ignored: idempotencyKey={}", idempotencyKey);
            return;
        }

        // Check user preferences (global unsubscribe or event-specific)
        if (isUnsubscribed(event.userId(), event.eventType())) {
            log.debug(
                    "User unsubscribed from event: userId={}, eventType={}",
                    event.userId(),
                    event.eventType());
            return;
        }

        String templateDataJson = serializeTemplateData(templateData);
        EmailOutbox outbox =
                EmailOutbox.builder()
                        .id(Ulids.newUlid())
                        .userId(event.userId())
                        .toEmail(toEmail)
                        .templateKey(templateKey)
                        .templateData(templateDataJson)
                        .idempotencyKey(idempotencyKey)
                        .build();
        emailOutboxRepository.save(outbox);
        log.debug(
                "Queued email: userId={}, templateKey={}, toEmail={}",
                event.userId(),
                templateKey,
                toEmail);
    }

    private boolean isUnsubscribed(String userId, String eventType) {
        // Check global unsubscribe first
        var globalPref =
                emailPreferenceRepository.findByUserIdAndEventTypeAndUnsubscribedTrue(userId, "*");
        if (globalPref.isPresent()) {
            return true;
        }
        // Check event-specific unsubscribe
        var eventPref = emailPreferenceRepository.findByUserIdAndEventType(userId, eventType);
        return eventPref.map(EmailPreference::isUnsubscribed).orElse(false);
    }

    private String buildIdempotencyKey(NotificationEvent event) {
        return event.eventType() + ":" + event.entityId() + ":" + event.userId();
    }

    private String serializeTemplateData(Map<String, Object> templateData) {
        try {
            return objectMapper.writeValueAsString(templateData);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize template data", e);
            return "{}";
        }
    }
}
