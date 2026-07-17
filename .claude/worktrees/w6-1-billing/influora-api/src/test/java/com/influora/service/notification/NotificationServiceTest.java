package com.influora.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.entity.EmailPreference;
import com.influora.domain.entity.Notification;
import com.influora.repository.EmailOutboxRepository;
import com.influora.repository.EmailPreferenceRepository;
import com.influora.repository.NotificationRepository;
import com.influora.service.notification.event.CampaignCreatedEvent;
import com.influora.service.notification.event.SiteAnalyzedEvent;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P7: Unit tests for NotificationService (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md).
 * Priority: idempotency, unsubscribe compliance, channel routing.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String USER_ID = "01HWXYZUSER1234567890";
    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456";
    private static final String ENTITY_ID = "01HWXYZENTITY12345678";

    @Mock private NotificationRepository notificationRepository;
    @Mock private EmailOutboxRepository emailOutboxRepository;
    @Mock private EmailPreferenceRepository emailPreferenceRepository;

    private NotificationService notificationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        notificationService = new NotificationService(
                notificationRepository, emailOutboxRepository, emailPreferenceRepository, objectMapper);
    }

    @Test
    @DisplayName("notify: creates both in-app and email for regular events")
    void testNotifyCreatesBothChannels() {
        CampaignCreatedEvent event = new CampaignCreatedEvent(
                USER_ID, WORKSPACE_ID, ENTITY_ID, "Summer Launch", "Brand ABC", "Fashion");

        when(emailOutboxRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(emailPreferenceRepository.findByUserIdAndEventTypeAndUnsubscribedTrue(any(), any()))
                .thenReturn(Optional.empty());
        when(emailPreferenceRepository.findByUserIdAndEventType(any(), any()))
                .thenReturn(Optional.empty());

        notificationService.notify(
                event,
                "New campaign in your category",
                "Brand ABC just launched 'Summer Launch'",
                "/creator/campaigns/" + ENTITY_ID,
                "creator@example.com",
                "creator.campaign_match",
                Map.of("brand_name", "Brand ABC", "campaign_title", "Summer Launch"));

        // Verify in-app notification created
        verify(notificationRepository).save(any(Notification.class));

        // Verify email queued
        verify(emailOutboxRepository).save(any(EmailOutbox.class));
    }

    @Test
    @DisplayName("notify: idempotent - duplicate event does not create duplicate email")
    void testNotifyIdempotent() {
        CampaignCreatedEvent event = new CampaignCreatedEvent(
                USER_ID, WORKSPACE_ID, ENTITY_ID, "Summer Launch", "Brand ABC", "Fashion");

        String idempotencyKey = "campaign.created:" + ENTITY_ID + ":" + USER_ID;

        // Email already exists with this idempotency key
        EmailOutbox existingOutbox = EmailOutbox.builder()
                .id("existing-id")
                .userId(USER_ID)
                .toEmail("creator@example.com")
                .templateKey("creator.campaign_match")
                .templateData("{}")
                .idempotencyKey(idempotencyKey)
                .build();
        when(emailOutboxRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.of(existingOutbox));

        notificationService.notify(
                event,
                "New campaign in your category",
                "Brand ABC just launched 'Summer Launch'",
                "/creator/campaigns/" + ENTITY_ID,
                "creator@example.com",
                "creator.campaign_match",
                Map.of("brand_name", "Brand ABC"));

        // In-app notification is still created (not deduplicated)
        verify(notificationRepository).save(any(Notification.class));

        // But email is NOT duplicated
        verify(emailOutboxRepository, never()).save(any(EmailOutbox.class));
    }

    @Test
    @DisplayName("notify: respects global unsubscribe")
    void testNotifyRespectsGlobalUnsubscribe() {
        CampaignCreatedEvent event = new CampaignCreatedEvent(
                USER_ID, WORKSPACE_ID, ENTITY_ID, "Summer Launch", "Brand ABC", "Fashion");

        when(emailOutboxRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        // User has globally unsubscribed
        EmailPreference globalUnsub = EmailPreference.builder()
                .id("pref-id")
                .userId(USER_ID)
                .eventType("*")
                .unsubscribed(true)
                .build();
        when(emailPreferenceRepository.findByUserIdAndEventTypeAndUnsubscribedTrue(USER_ID, "*"))
                .thenReturn(Optional.of(globalUnsub));

        notificationService.notify(
                event,
                "New campaign in your category",
                "Brand ABC just launched 'Summer Launch'",
                "/creator/campaigns/" + ENTITY_ID,
                "creator@example.com",
                "creator.campaign_match",
                Map.of("brand_name", "Brand ABC"));

        // In-app notification still created
        verify(notificationRepository).save(any(Notification.class));

        // Email NOT queued due to unsubscribe
        verify(emailOutboxRepository, never()).save(any(EmailOutbox.class));
    }

    @Test
    @DisplayName("notify: respects event-specific unsubscribe")
    void testNotifyRespectsEventSpecificUnsubscribe() {
        CampaignCreatedEvent event = new CampaignCreatedEvent(
                USER_ID, WORKSPACE_ID, ENTITY_ID, "Summer Launch", "Brand ABC", "Fashion");

        when(emailOutboxRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(emailPreferenceRepository.findByUserIdAndEventTypeAndUnsubscribedTrue(any(), eq("*")))
                .thenReturn(Optional.empty());

        // User has unsubscribed from this specific event type
        EmailPreference eventUnsub = EmailPreference.builder()
                .id("pref-id")
                .userId(USER_ID)
                .eventType("campaign.created")
                .unsubscribed(true)
                .build();
        when(emailPreferenceRepository.findByUserIdAndEventType(USER_ID, "campaign.created"))
                .thenReturn(Optional.of(eventUnsub));

        notificationService.notify(
                event,
                "New campaign in your category",
                "Brand ABC just launched 'Summer Launch'",
                "/creator/campaigns/" + ENTITY_ID,
                "creator@example.com",
                "creator.campaign_match",
                Map.of("brand_name", "Brand ABC"));

        // In-app notification still created
        verify(notificationRepository).save(any(Notification.class));

        // Email NOT queued due to unsubscribe
        verify(emailOutboxRepository, never()).save(any(EmailOutbox.class));
    }

    @Test
    @DisplayName("notifyInApp: in-app only events do not queue email")
    void testInAppOnlyEventsNoEmail() {
        SiteAnalyzedEvent event = new SiteAnalyzedEvent(
                USER_ID, WORKSPACE_ID, ENTITY_ID, "https://example.com");

        notificationService.notifyInApp(
                event,
                "Website Analysis Complete",
                "Meera has finished analyzing https://example.com",
                "/brand/meera?analysis=" + ENTITY_ID);

        // In-app notification created
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assert saved.getEventType().equals("ai.site_analyzed");

        // No email queued for in-app only events
        verify(emailOutboxRepository, never()).save(any(EmailOutbox.class));
    }
}
