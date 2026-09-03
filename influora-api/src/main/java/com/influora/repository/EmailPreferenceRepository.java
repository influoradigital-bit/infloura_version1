package com.influora.repository;

import com.influora.domain.entity.EmailPreference;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for email preferences (Domain B, unsubscribe tracking for GDPR/CAN-SPAM compliance).
 */
public interface EmailPreferenceRepository extends JpaRepository<EmailPreference, String> {

    /** Check if user has unsubscribed from a specific event type. */
    Optional<EmailPreference> findByUserIdAndEventType(String userId, String eventType);

    /** Get all preferences for a user. */
    List<EmailPreference> findByUserId(String userId);

    /** Check if user has globally unsubscribed (event_type = '*'). */
    Optional<EmailPreference> findByUserIdAndEventTypeAndUnsubscribedTrue(
            String userId, String eventType);

    /**
     * T-ADMINMAIL-0903 control #5 (unsubscribe enforcement) for a whole audience batch in one
     * query, rather than {@code NotificationService.isUnsubscribed}'s two-query-per-user shape —
     * a custom send's audience can be thousands of rows, so {@code AdminCustomEmailService} needs
     * the unsubscribed subset up front, not a per-recipient round trip. Mirrors {@code
     * NotificationService}'s own rule: globally unsubscribed ({@code event_type = '*'}) OR
     * unsubscribed from this specific {@code eventType}.
     */
    @Query(
            "SELECT DISTINCT ep.userId FROM EmailPreference ep WHERE ep.userId IN :userIds "
                    + "AND ep.unsubscribed = true AND (ep.eventType = '*' OR ep.eventType = :eventType)")
    Set<String> findUnsubscribedUserIds(
            @Param("userIds") Collection<String> userIds, @Param("eventType") String eventType);
}
