package com.influora.service.notification.event;

/**
 * Morning reminder for an ACTIVE {@link com.influora.domain.entity.CreatorChallenge} whose today is
 * a posting day, not yet DONE (CHALLENGE-SPEC.md Backend &sect;7). Raised by {@code
 * CreatorChallengeDailyEmailJob}, a scheduled job with no surrounding transaction -- same reason
 * {@link CreatorNotConnectedEvent} is handled by a plain {@code @EventListener} rather than an
 * {@code AFTER_COMMIT} one (see that event's javadoc; an AFTER_COMMIT listener would never fire at
 * all here).
 *
 * <p>{@code entityId} is {@code challengeId + ":" + date} (ISO {@code yyyy-MM-dd}), not just the
 * challenge id -- CHALLENGE-SPEC.md requires idempotency <b>per (challenge id, date)</b>, and the
 * outbox's {@code UNIQUE (idempotency_key)} is built from {@code eventType:entityId:userId}
 * ({@link NotificationService#buildIdempotencyKey}), so the date has to be part of {@code entityId}
 * for a second day's reminder to ever send at all.
 */
public record CreatorChallengeDayDueEvent(
        String userId,
        String entityId,
        String creatorName,
        String plannedType,
        String windowText,
        String toEmail,
        String link)
        implements NotificationEvent {

    @Override
    public String eventType() {
        return "creator.challenge_day_due";
    }

    @Override
    public String workspaceId() {
        return null;
    }
}
