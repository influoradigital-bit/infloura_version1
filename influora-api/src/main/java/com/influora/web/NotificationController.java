package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.EmailPreference;
import com.influora.domain.entity.Notification;
import com.influora.repository.EmailPreferenceRepository;
import com.influora.repository.NotificationRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.UnsubscribeTokenService;
import com.influora.web.dto.notification.NotificationDtos.MarkReadRequest;
import com.influora.web.dto.notification.NotificationDtos.MarkReadResponse;
import com.influora.web.dto.notification.NotificationDtos.NotificationListResponse;
import com.influora.web.dto.notification.NotificationDtos.NotificationPreferenceItem;
import com.influora.web.dto.notification.NotificationDtos.NotificationResponse;
import com.influora.web.dto.notification.NotificationDtos.PreferencesResponse;
import com.influora.web.dto.notification.NotificationDtos.SetPreferenceRequest;
import com.influora.web.dto.notification.NotificationDtos.SetPreferenceResponse;
import com.influora.web.dto.notification.NotificationDtos.UnsubscribeRequest;
import com.influora.web.dto.notification.NotificationDtos.UnsubscribeResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints for in-app notifications (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md).
 * Most queries are scoped to the authenticated user — the exception is {@link
 * #unsubscribeViaLink}, which a recipient reaches straight from an email with no session at all
 * (see {@code SecurityConfig}'s {@code permitAll} for this exact path).
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final EmailPreferenceRepository emailPreferenceRepository;
    private final UnsubscribeTokenService unsubscribeTokenService;

    public NotificationController(
            NotificationRepository notificationRepository,
            EmailPreferenceRepository emailPreferenceRepository,
            UnsubscribeTokenService unsubscribeTokenService) {
        this.notificationRepository = notificationRepository;
        this.emailPreferenceRepository = emailPreferenceRepository;
        this.unsubscribeTokenService = unsubscribeTokenService;
    }

    /**
     * GET /notifications - paginated list of notifications, unread-first, most-recent-first.
     */
    @GetMapping
    public ResponseEntity<NotificationListResponse> list(
            @AuthenticationPrincipal AuthPrincipal user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<Notification> pageResult =
                notificationRepository.findByUserIdOrdered(user.getUserId(), PageRequest.of(page, size));

        List<NotificationResponse> notifications =
                pageResult.getContent().stream().map(NotificationResponse::from).toList();

        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(user.getUserId());

        return ResponseEntity.ok(new NotificationListResponse(notifications, unreadCount, page, size));
    }

    /**
     * POST /notifications/read - mark a notification as read.
     */
    @PostMapping("/read")
    public ResponseEntity<MarkReadResponse> markRead(
            @AuthenticationPrincipal AuthPrincipal user,
            @Valid @RequestBody MarkReadRequest request) {

        Notification notification =
                notificationRepository.findByIdAndUserId(request.notificationId(), user.getUserId());

        if (notification == null) {
            throw new ApiException(
                    "NOTIFICATION_NOT_FOUND",
                    "Notification not found",
                    HttpStatus.NOT_FOUND);
        }

        notification.setRead(true);
        notificationRepository.save(notification);

        long newUnreadCount = notificationRepository.countByUserIdAndIsReadFalse(user.getUserId());

        return ResponseEntity.ok(new MarkReadResponse(true, newUnreadCount));
    }

    /**
     * POST /notifications/unsubscribe - unsubscribe from email notifications for an event type.
     */
    @PostMapping("/unsubscribe")
    public ResponseEntity<UnsubscribeResponse> unsubscribe(
            @AuthenticationPrincipal AuthPrincipal user,
            @Valid @RequestBody UnsubscribeRequest request) {

        EmailPreference preference =
                emailPreferenceRepository
                        .findByUserIdAndEventType(user.getUserId(), request.eventType())
                        .orElseGet(
                                () ->
                                        EmailPreference.builder()
                                                .id(Ulids.newUlid())
                                                .userId(user.getUserId())
                                                .eventType(request.eventType())
                                                .unsubscribed(false)
                                                .build());

        preference.setUnsubscribed(true);
        emailPreferenceRepository.save(preference);

        return ResponseEntity.ok(new UnsubscribeResponse(true, request.eventType()));
    }

    /**
     * GET /notifications/unsubscribe-link?token=... - one-click unsubscribe from the link in an
     * email footer ({@code EmailTemplateRegistry}). Deliberately unauthenticated: the recipient is
     * reading their inbox, not a logged-in session — {@link UnsubscribeTokenService}'s HMAC
     * signature is what proves the (userId, eventType) pair wasn't tampered with, not a JWT.
     * Returns a plain confirmation page rather than JSON since this is a browser-clicked link, not
     * an API call.
     */
    @GetMapping(value = "/unsubscribe-link", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribeViaLink(@RequestParam("token") String token) {
        return unsubscribeTokenService
                .verify(token)
                .map(
                        parsed -> {
                            EmailPreference preference =
                                    emailPreferenceRepository
                                            .findByUserIdAndEventType(parsed.userId(), parsed.eventType())
                                            .orElseGet(
                                                    () ->
                                                            EmailPreference.builder()
                                                                    .id(Ulids.newUlid())
                                                                    .userId(parsed.userId())
                                                                    .eventType(parsed.eventType())
                                                                    .unsubscribed(false)
                                                                    .build());
                            preference.setUnsubscribed(true);
                            emailPreferenceRepository.save(preference);
                            return ResponseEntity.ok(confirmationPage(true));
                        })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(confirmationPage(false)));
    }

    private static String confirmationPage(boolean success) {
        String heading = success ? "You've been unsubscribed" : "This link is invalid or expired";
        String body =
                success
                        ? "You will no longer receive this type of email from Influora."
                        : "Please request a new email from Influora, or manage your preferences from"
                                + " your account settings.";
        return "<!doctype html><html><body style=\"font-family:-apple-system,Segoe UI,Roboto,"
                + "Helvetica,Arial,sans-serif;padding:48px;text-align:center;color:#221e35;\">"
                + "<h2 style=\"margin:0 0 12px;\">"
                + heading
                + "</h2><p style=\"color:#3d3852;\">"
                + body
                + "</p></body></html>";
    }

    /**
     * GET /notifications/preferences - the authenticated user's per-event-type email preferences
     * (Domain B EmailPreference model; matches NotificationPreference in src/lib/api.ts). Only
     * rows the user has explicitly touched are returned; any event type absent from the list is
     * implicitly subscribed (mirrors NotificationService#isUnsubscribed's default).
     */
    @GetMapping("/preferences")
    public ResponseEntity<PreferencesResponse> getPreferences(
            @AuthenticationPrincipal AuthPrincipal user) {

        List<NotificationPreferenceItem> preferences =
                emailPreferenceRepository.findByUserId(user.getUserId()).stream()
                        .map(p -> new NotificationPreferenceItem(p.getEventType(), p.isUnsubscribed()))
                        .toList();

        return ResponseEntity.ok(new PreferencesResponse(preferences));
    }

    /**
     * POST /notifications/preferences - set the authenticated user's email subscription state for
     * a single event type (or "*" for the global opt-out already honored by
     * NotificationService#isUnsubscribed). Upserts on (userId, eventType).
     */
    @PostMapping("/preferences")
    public ResponseEntity<SetPreferenceResponse> setPreference(
            @AuthenticationPrincipal AuthPrincipal user,
            @Valid @RequestBody SetPreferenceRequest request) {

        EmailPreference preference =
                emailPreferenceRepository
                        .findByUserIdAndEventType(user.getUserId(), request.eventType())
                        .orElseGet(
                                () ->
                                        EmailPreference.builder()
                                                .id(Ulids.newUlid())
                                                .userId(user.getUserId())
                                                .eventType(request.eventType())
                                                .unsubscribed(false)
                                                .build());

        boolean unsubscribed = !request.subscribed();
        preference.setUnsubscribed(unsubscribed);
        emailPreferenceRepository.save(preference);

        return ResponseEntity.ok(new SetPreferenceResponse(true, request.eventType(), unsubscribed));
    }
}
