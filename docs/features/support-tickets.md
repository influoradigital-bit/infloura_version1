# Feature: Support Tickets

**Business Purpose** — Admin-only support triage. Brand/creator users' tickets are listed, filtered, and worked by admins: view the thread, reply, change status, and assign. Message bodies (PII) are deliberately kept out of the audit log.

**Who uses it** — Admins (SUPPORT/ADMIN/SUPER_ADMIN). Ticket bodies originate from users, but there is **no user-facing ticket-creation endpoint** in this surface.

## User Roles
Admin roles. Assign requires ADMIN/SUPER_ADMIN.

## Permissions
List/view/reply/status → SUPPORT/ADMIN/SUPER_ADMIN (MFA). Assign → ADMIN/SUPER_ADMIN.

## Business Flow
```
Ticket exists → admin lists/filters → view thread → reply (adds ADMIN message)
  → change status (RESOLVED stamps resolved_at) → assign to an admin
```
`TicketStatus`: OPEN, IN_PROGRESS, WAITING_USER, RESOLVED, CLOSED. `TicketPriority`: LOW/MEDIUM/HIGH/URGENT.

## Frontend
- **Admin**: `admin/components/support/TicketList` (queue + drawer: thread, reply, assign, escalate dialog), hooks `useTicketList`/`useTicketDetail`, `supportApi`.

## Backend
- **Controller**: `AdminSupportController` (`/admin/support/tickets`, raw DTOs).
- **Service**: `service/admin/AdminSupportService`.

## Database
`support_tickets`, `support_ticket_messages` (both V34; message `content` is PII, never logged). See [../database.md](../database.md).

## APIs
`GET /admin/support/tickets` (filters + pagination), `GET .../{id}`, `POST .../{id}/reply`, `PUT .../{id}` (status), `POST .../{id}/assign`. **Not implemented**: `escalate`, `getStats` (frontend calls them — 404).

## AI
Not involved.

## Notifications
Admin realtime `support.ticket.*` events over WebSocket.

## Dependencies
- **Depends on**: admin auth/console.
- **Depended on by**: trust-and-safety ops.

## Connected Files
`AdminSupportController`, `AdminSupportService`, `SupportTicketSpecs`, `domain/entity/{SupportTicket,SupportTicketMessage}`; admin support components.

## Execution Flow
```
Reply: POST /admin/support/tickets/{id}/reply → AdminSupportService (RBAC) → append ADMIN message
  (no status change, no audit — PII guardrail)
Status: PUT /admin/support/tickets/{id} → any status accepted (no enforced transitions) → RESOLVED stamps resolved_at (metadata-only audit)
Assign: POST .../assign → verify target admin active → metadata-only audit
```

## Error Handling
`ASSIGNEE_NOT_FOUND` (400). No enforced status transitions (unlike disputes). `TicketDetailDto.relatedEntities` is an honest empty stub.

## Security
PII (message bodies) never logged/audited; RBAC + MFA; pageSize clamped 1–100.

## Performance
Spec-based filtering; append-only messages indexed by `(ticket_id, created_at)`.

## Testing
Support service tests. Regression risks: RBAC on assign, status stamping.

## Production Readiness
- **Health**: 6/10 · **Completion**: ~70%
- **Known issues**: `escalate` + `getStats` not implemented (frontend has live buttons → 404); no user-facing ticket creation endpoint; no status-transition enforcement. See [../known-limitations.md](../known-limitations.md).
- **Last verified**: 2026-07-15
