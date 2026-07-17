package com.influora.service.brand;

/**
 * Internal-only signal (H-23) — published by {@link AnalyzeSiteTriggerService#trigger} from
 * inside the same short transaction that just wrote the {@code BrandProfile} row to {@code
 * ANALYZING}, consumed by that same class's {@code @TransactionalEventListener(AFTER_COMMIT)}
 * handler. This is NOT a {@code NotificationEvent} — it never reaches a user, it only exists to
 * defer scheduling the actual (slow, external) analyze-site call until the ANALYZING row is
 * durably committed and visible to the background thread that will eventually write the result
 * back (see {@link AnalyzeSiteTriggerService} class javadoc for the race this avoids).
 */
record AnalyzeSiteRequestedEvent(String workspaceId, String websiteUrl) {}
