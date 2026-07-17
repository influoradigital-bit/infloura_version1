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
- **Components**: bell in `brand-layout`/`creator-layout` (currently inline mock), `useNotificationStore`, settings toggles.
- **API**: `api.notifications.*`.

## Backend
- **Controller**: `NotificationController` (`/notifications`).
- **Services**: `service/notification/NotificationService`, `NotificationListener` (26 handlers), `EmailWorker`.
- **Email client**: `integration/msg91/Msg91EmailClient`.

## Database
`notifications` (V17), `email_outbox` (V18, idempotent, retry/backoff), `email_preferences` (V18, opt-out). See [../database.md](../database.md).

## APIs
`GET /notifications` (unread-first), `POST /notifications/read`, `POST /notifications/unsubscribe`.

## AI
Not involved (nudges are a separate TrendSpark feature).

## Notifications (events)
`NotificationEvent` sealed interface permits **31** types; **26 have listeners**. Covered: applications, proposals, bids, escrow-funded, contract-signed, deliverable-submitted, payouts, KYC, wallet-low-balance, OTP/reset, user-created, Meera events.

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
- **Known issues**: **no realtime** (poll only); **5 events have no listener**; **most handlers pass `toEmail=null`** so emails no-op; **frontend UI is mock** and calls **nonexistent endpoints** (`/notifications/read-all`, `/preferences`); no SMS. See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
