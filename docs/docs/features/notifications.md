# Feature: Notifications

**Business Purpose** — Keeps users informed of marketplace lifecycle events (new applications, escrow funded, deliverable ready, payouts, KYC, etc.) via **in-app** notifications and **email**. It's how the platform nudges both sides to keep collaborations moving.

**Who uses it** — All users (recipients), the domain services (emitters).

## User Roles
Brand, Creator (recipients). Admins have a separate realtime channel (see [admin-dashboard.md](admin-dashboard.md)).

## Permissions
Users see only their own notifications; unsubscribe manages their own email preferences.

## Channels
- **In-app**: `notifications` table, polled by the frontend (no realtime).
- **Email**: `email_outbox` → `EmailWorker` (30s poll) → MSG91.
- **SMS**: none (UI toggles are dead).

## Business Flow
```
Domain event (e.g. ApplicationCreatedEvent) → NotificationListener (@Async @EventListener)
  → NotificationService.notify → routes: in-app row and/or email_outbox row (idempotent, honors unsubscribe)
  → EmailWorker sends email; user polls in-app bell
```

## Frontend
- **Components**: bell in `brand-layout`/`creator-layout` ([CORRECTED 2026-09-13, doc-stale-doc-claim, F-0806: this line said "currently inline mock" — false; `brand-layout.tsx:199` destructures `useNotifications('brand')`, whose `useNotifications.ts:182` calls `notificationsApi.list(role)` when `isApiLive()`; `creator-layout.tsx:473` has a real `onClick` navigating to `/creator/notifications`]), `useNotificationStore`, settings toggles ([CORRECTED 2026-09-13, doc-stale-doc-claim, F-0806: toggles call real endpoints — `brand-settings.tsx:443,482` and `creator-settings.tsx:226` call `api.notifications.setPreference`]).
- **API**: `api.notifications.*`.

## Backend
- **Controller**: `NotificationController` (`/notifications`).
- **Services**: `service/notification/NotificationService`, `NotificationListener` ([CORRECTED 2026-09-13, doc-stale-doc-claim, F-0805b: this line said "26 handlers" — stale; `grep -c "public void on("` on NotificationListener.java measures 34, see the Notifications (events) section below]), `EmailWorker`.
- **Email client**: `integration/msg91/Msg91EmailClient`.

## Database
`notifications` (V17), `email_outbox` (V18, idempotent, retry/backoff), `email_preferences` (V18, opt-out). [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a "See [../database.md]" link — docs/docs/ contains only features/, so that file never existed].

## APIs
`GET /notifications` (unread-first), `POST /notifications/read`, `POST /notifications/read-all`, `GET /notifications/unsubscribe-link` (unauthenticated HTML page for email links), `GET /notifications/preferences`, `POST /notifications/preferences` (per-event opt-out; `eventType="*"` is the global opt-out) ([CORRECTED 2026-09-13, doc-stale-doc-claim, F-0805b: this line omitted `/notifications/read-all` and `/notifications/preferences` (real routes — `NotificationController.java:133,253,270`) and named a `POST /notifications/unsubscribe` route that does not exist — the real unsubscribe surfaces are `GET /notifications/unsubscribe-link` (`NotificationController.java:192`) and `POST /notifications/preferences`]).

## AI
Not involved (nudges are a separate TrendSpark feature).

## Notifications (events)
`NotificationEvent` sealed interface permits **34** types; **all 34 have listeners** ([CORRECTED 2026-09-13, doc-stale-doc-claim, F-0605: this line still said 31/26 despite the Known Issues line below already recording the F-0557 34/34 count — `grep -c "public void on("` on NotificationListener.java confirms 34]). Covered: applications, proposals, bids, escrow-funded, contract-signed, deliverable-submitted, payouts, KYC, wallet-low-balance, OTP/reset, user-created, Meera events.

## Dependencies
- **Depends on**: MSG91, the emitting services.
- **Depended on by**: user engagement across features.

## Connected Files
`NotificationController`, `NotificationService`, `NotificationListener`, `EmailWorker`, `Msg91EmailClient`, `domain/entity/{Notification,EmailOutbox,EmailPreference}`.

## Execution Flow
```
Emit: service publishes event → NotificationListener handler → NotificationService.notify
  → EMAIL_ONLY / IN_APP_ONLY / both → in-app row (not deduped) + email_outbox row (idempotency_key)
Send: EmailWorker @Scheduled(30s) → batch 50 → MSG91 → markSent / markFailed (backoff 30/90/270/810s, cap 5)
```

## Error Handling
`NOTIFICATION_NOT_FOUND` (404). Email failures retried with backoff to a terminal FAILED (no dead-letter). Blank-email guard no-ops emails with `toEmail=null`.

## Security
User-scoped; email idempotent; unsubscribe honored (`'*'` = all). MSG91 mock mode in dev.

## Performance
Outbox batches 50/30s; in-app queries use an unread-first index.

## Testing
Notification/outbox tests. Regression risks: idempotency, unsubscribe, backoff.

## Production Readiness
- **Health**: 5/10 · **Completion**: ~65%
- **Known issues**: **no realtime** (poll only); every one of the 34 concrete events implementing the sealed `NotificationEvent` marker is handled in `NotificationListener.java` [CORRECTED 2026-09-06, doc-stale-doc, F-0557: this claimed 5 had no listener; measured 0]; [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0602: handlers (e.g. `on(DeliverableSubmittedEvent)`, NotificationListener.java:390-404) call `emailOf(event.userId())` (NotificationListener.java:145-149), a real `UserRepository` lookup — not a literal `toEmail=null`; it resolves to null only when the user record can't be found]; [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0806: this line also said "frontend UI is mock" — false, contradicting the clause immediately after it; see Frontend section above (bell and settings toggles both call real endpoints)]; [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0605: `/notifications/read-all` and `/notifications/preferences` are real routes — `NotificationController.java:133,253,270` — and `src/lib/api.ts:4217,4231,4242` call them; not nonexistent]; no SMS. [CORRECTED 2026-09-13, doc-stale-doc-claim, F-0807: removed a "See [../known-limitations.md]" link — docs/docs/ contains only features/, so that file never existed].
- **Last verified**: 2026-07-15
