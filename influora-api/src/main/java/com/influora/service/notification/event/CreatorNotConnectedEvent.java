package com.influora.service.notification.event;

/**
 * A creator finished registering but has never connected a Meta account, so their profile carries
 * no audience data — absent from brand search, and campaign matches never reach them. Raised by
 * {@code CreatorConnectNudgeJob}, not by a user action.
 *
 * <p>Published through {@code ApplicationEventPublisher} like every other event here, and handled
 * by {@code NotificationListener#on(CreatorNotConnectedEvent)}. Its listener is a plain {@code
 * @EventListener} rather than the {@code @TransactionalEventListener(AFTER_COMMIT)} the rest use:
 * the publisher is a scheduled job with no surrounding transaction, and an AFTER_COMMIT listener
 * simply never fires without one — the nudge would silently never send.
 *
 * <p>{@code entityId} is the <b>sequence stage</b> ({@code connect-nudge-1|2|3}), not an entity id.
 * That is what makes the three-send sequence work with no extra table: the outbox's {@code UNIQUE
 * (idempotency_key)} over {@code eventType:entityId:userId} means each stage can be delivered at
 * most once per creator, forever, while a re-run of an already-sent stage collapses to a no-op.
 *
 * <p>{@code toEmail} rides on the event instead of being re-resolved by the listener. The job has
 * already loaded the {@code User} to compute the stage, so re-reading it per recipient would add a
 * query per creator per daily run for a value it is holding — the same reason the OTP and
 * password-reset events carry their own address.
 */
public record CreatorNotConnectedEvent(
        String userId,
        String workspaceId,
        String entityId,
        String userName,
        String toEmail,
        String connectUrl)
        implements NotificationEvent {

    /** Stage identifiers — the {@code entityId} half of the idempotency key. */
    public static String stage(int sendNumber) {
        return "connect-nudge-" + sendNumber;
    }

    @Override
    public String eventType() {
        return "creator.not_connected";
    }
}
