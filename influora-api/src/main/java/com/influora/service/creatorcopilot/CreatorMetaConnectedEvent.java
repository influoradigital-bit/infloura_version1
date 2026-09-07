package com.influora.service.creatorcopilot;

/**
 * Published when a creator's Meta/Instagram connect succeeds with a usable account
 * (T-IGTRUST-0907).
 *
 * <p><b>Why this exists.</b> Before it, nothing at all happened at connect time. The Co-pilot
 * pipeline is two nightly crons — {@code CreatorCaptionSyncJob} at 02:00 UTC and {@code
 * CreatorThemeTaggingJob} at 03:00 UTC — so a creator who connected at 10:00 IST had no captions
 * cached and no themes tagged until roughly 07:30-08:30 IST the following morning. For that whole
 * window {@code /creator/copilot/suggestion/today} answers {@code pending_tagging} and the creator
 * sees "Analysing your recent posts…" — having just granted Instagram access and been promised a
 * daily idea. This event closes that gap by kicking the caption sync for that one creator
 * immediately.
 *
 * <p><b>Why an event and not a direct call.</b> {@code CreatorMetaOAuthService.connect} is
 * {@code @Transactional}, and the sync makes a Graph API round trip. Calling it inline would hold
 * a database connection open across network I/O, and a Meta failure would roll back the token
 * write that had just succeeded — losing the connection the creator just granted, to fix a
 * cosmetic delay. The listener is {@code @Async @TransactionalEventListener(AFTER_COMMIT)}, so the
 * token is durably committed before the sync is attempted and no sync failure can ever affect the
 * connect result. Same pattern as {@code NotificationListener} (see {@code
 * CollaborationLifecycleService}'s note).
 *
 * @param creatorProfileId the creator whose captions should be synced now rather than tonight
 */
public record CreatorMetaConnectedEvent(String creatorProfileId) {}
