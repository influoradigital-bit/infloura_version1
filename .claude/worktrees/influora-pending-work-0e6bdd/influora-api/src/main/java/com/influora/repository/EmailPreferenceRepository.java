package com.influora.repository;

import com.influora.domain.entity.EmailPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
