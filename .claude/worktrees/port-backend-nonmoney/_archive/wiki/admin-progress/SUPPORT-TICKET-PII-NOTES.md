# Support Ticket PII / Role-Scope Notes — prep for AdminSupportController

> Owner: Kabir (Red-Team). Written Cycle 6/7, in parallel with Vikram building
> `AdminSupportController.java` (not yet on disk as of this writing — grepped
> `influora-api/src/main/java/com/influora/web` and `service/admin`, confirmed zero hits for
> `AdminSupportController|AdminSupportService`). This is prep guidance, not a review of shipped
> code — re-review once the controller lands, same pattern as `AUDIT-LOG-WRITE-SPEC.md` before
> `AdminBrandController`.

Companion doc to `AUDIT-LOG-WRITE-SPEC.md` — read that one first for the general audit-log rules
(field allow-list, server-derived identity/IP, Rule 1a on `X-Forwarded-For`). This doc is specific
to the support-ticket surface: what SUPPORT-tier admins should see, whether ticket message content
needs scanning/redaction, and confirming the audit posture already implied by the schema comments.

## 1. SUPPORT-tier read/write scope on tickets

Current schema/entity state (`V34__admin_tables.sql:68-97`, `SupportTicket.java`,
`SupportTicketRepository.java`): `support_tickets` and `support_ticket_messages` exist, but there is
no `SupportTicketMessage` entity/repository yet — only the ticket header row is mapped. Full
CRUD (reply/assign/escalate) is explicitly deferred to this controller per
`SupportTicket.java`'s own class javadoc (line 17-18).

**Recommendation — SUPPORT should see full message content, not just ticket metadata:**

- Tickets are opened by brand/creator users about their own account/campaign/payment issues.
  Restricting message *content* from SUPPORT-tier admins while giving them the ticket queue
  (status/priority/assignment) but not the actual complaint text would make the role
  non-functional for its stated purpose — SUPPORT is the first-line tier that's supposed to
  read and respond to tickets. This differs from the BRAND/CREATOR KYC-verify/suspend surface
  (where SUPPORT is correctly excluded per `AdminContextService`'s documented matrix), because
  that surface is about *taking destructive action on an account*, not *reading a support
  request the user voluntarily submitted to get help*.
- Matrix already documented in `AdminContextService.java:53-57`: "SUPPORT = read + ticket/moderation
  actions only." Read this as: SUPPORT gets full read + reply/assign/status-change on tickets
  (the "ticket actions" it's explicitly scoped for), NOT escalation to account-level mutations
  (suspend/reinstate/KYC) — those stay ADMIN+, matching every other controller shipped so far.
- Concretely, suggested allow-list for `AdminSupportController` methods:
  - `listTickets` / `getTicketById` / `getTicketMessages` — `requireRoleWithMfaSatisfied(SUPER_ADMIN, ADMIN, SUPPORT)` (read, all tiers)
  - `postReply` (admin sends a message) — `SUPER_ADMIN, ADMIN, SUPPORT` (this is the core SUPPORT function)
  - `updateStatus` / `updatePriority` / `assignTicket` (self-assign or reassign) — `SUPER_ADMIN, ADMIN, SUPPORT`
  - `escalate` (if this action exists — bumps priority/reassigns to ADMIN+ tier, or triggers something
    account-level like a linked suspend) — if it stays a ticket-only mutation (status/priority/assign),
    SUPPORT is fine; if "escalate" is ever wired to also suspend/flag the underlying account, that part
    needs the ADMIN+ gate the account-mutation endpoints already use — don't let a ticket action become
    a side-channel around the KYC/suspend role gate.
- One MFA note carried over from `AdminContextService`'s existing design: `requireRoleWithMfaSatisfied`
  currently only *enforces* MFA for SUPER_ADMIN/ADMIN (SUPPORT is allowed through the MFA check even
  with `mfaEnabled=false`, per `requireMfaSatisfied`'s `mfaRequiredForRole` condition). Given SUPPORT
  will now read/reply to messages that may contain payment disputes, account details, or (per section 2
  below) accidentally-pasted secrets, this is worth a revisit — not blocking this cycle (matches the
  existing documented design, and tickets are lower blast-radius than escrow/suspend), but flag it if
  SUPPORT volume/access grows. Not asking Vikram to change this now, just recording it as a forward
  note so it isn't silently reconsidered without a paper trail.

## 2. Message content — scanning/redaction for accidentally-pasted secrets

Checked whether anything in the current schema or `AUDIT-LOG-WRITE-SPEC.md` already covers this: no.
The spec's "safe to log" table lists `SUPPORT_TICKET` audit fields as `id, status, priority,
assigned_to, category` (message *content* is correctly never in that allow-list — see section 3
below), but that only protects the audit-log copy. It says nothing about the ticket message's
primary row in `support_ticket_messages.content` itself, which is a real, user-facing, freely-typed
TEXT column a brand/creator user could paste a card number, password, or API key into while
describing their problem ("my payment failed, here's my card 4111-1111-1111-1111" is a completely
plausible support message).

**Recommendation: out of scope for this sprint, but flag it explicitly rather than silently
skipping it, and pick the cheapest mitigation available now:**

- Building real PII/secret-scanning (regex-based card-number/PAN detection, entropy-based
  secret detection, etc.) with redaction-before-store is a legitimate control for a support
  system, but it's a meaningfully bigger feature than what this sprint's scope (ticket
  list/detail/reply/assign, P2 CRUD per `SHARED_CONTEXT.md`'s Cycle 7 plan) calls for. Recommend
  explicitly deferring it as a tracked follow-up rather than an implicit gap — add a one-line
  TODO/comment in `SupportTicketMessage` (once that entity exists) or the service layer noting
  "message content is stored verbatim, no PII/secret scanning yet — see
  wiki/admin-progress/SUPPORT-TICKET-PII-NOTES.md §2" so a future reader doesn't assume it was
  considered and rejected.
  - This is genuinely PCI-relevant if a user pastes a full card number: to be safe, no code path
    should be able to log the message content to `admin_audit_log.old_value`/`new_value` (only the
    allow-listed ticket-metadata fields go there — already correct per section 3) or to any general
    application logger. Confirm ticket message content is never included in application-level log
    statements when the controller/service is built (same posture as never logging `mfa_secret`).
  - Cheapest real mitigation available *this* sprint without building a scanner: cap message length
    (already flagged in `SECURITY-NOTES.md` cycle-2, `support_ticket_messages.content` is unbounded
    TEXT — a 10-20k char app-layer cap was already recommended there for storage-exhaustion reasons;
    that same cap has no PII-redaction benefit but is still worth doing when the insert endpoint is
    built) and make sure the eventual `TicketList.tsx`/ticket detail UI doesn't render message content
    in any place with broader visibility than "the SUPPORT/ADMIN/SUPER_ADMIN admin who's handling that
    specific ticket" (i.e. no message-content preview in a dashboard widget, notification payload, or
    email digest — keep secrets-if-pasted confined to the one screen where an admin is already
    reading the full ticket to help the user).
  - Do not build ad-hoc regex-redaction as a rushed afterthought under this sprint's time pressure —
    a half-built PAN-detection regex that misses common formats gives false confidence and is worse
    than clearly documenting "not done yet." If/when this becomes a real requirement, treat it as its
    own scoped task with its own test cases (Luhn-valid PAN detection, common secret-key prefixes,
    etc.), not a bolt-on inside the CRUD controller PR.

## 3. Audit-log guidance — confirming consistency with what's already established

Re-checked `AUDIT-LOG-WRITE-SPEC.md`'s existing `SUPPORT_TICKET` allow-list
(`id, status, priority, assigned_to, category` — line 167 of that doc) against the schema and this
cycle's read of `AdminCreatorService`/`AdminBrandService`'s established pattern (field-allowlisted
snapshots only, never raw entity dumps, confirmed clean in both services' `record(...)` calls
reviewed this cycle). Conclusion: **the existing guidance is correct and needs no change** for
ticket status/assignment changes. Restating explicitly for `AdminSupportController`'s author,
since this is the first controller that will actually touch `support_ticket_messages`:

- **Log:** ticket status changes (`OPEN -> IN_PROGRESS` etc.), priority changes, assignment changes
  (`assigned_to` before/after), category changes if that becomes editable — all via the existing
  `SUPPORT_TICKET` allow-list, same `oldValueAllowed`/`newValueAllowed` map pattern
  `AdminBrandService`/`AdminCreatorService` already use.
- **Never log:** `support_ticket_messages.content` (the actual message body/reply text) in
  `old_value`/`new_value`/`reason` for ANY audit action — not for `postReply`, not for anything else.
  This wasn't fully spelled out as its own rule in the original spec (the spec's allow-list table
  simply doesn't mention a `content` field for `SUPPORT_TICKET`, which by Rule 2's allow-list-only
  design already excludes it — but making it explicit here since this is the first cycle a message-
  bearing entity type is actually being built against). If `postReply` needs an audit row at all
  (recommend: yes, log the reply *event* — `action=CREATE, entityType=SUPPORT_TICKET, entityId=<ticketId>,
  reason=null or a short non-content marker like "Admin reply added"`), it must not put the reply body
  into any audit column. The audit trail should record *that* a reply happened and *who* sent it (via
  the already-server-derived `admin_id`), not *what* it said — matches section 2's stance that message
  content should stay confined to the one ticket-detail surface, not fan out into a second,
  longer-retention, broadly-admin-readable table (`admin_audit_log` is read by SUPPORT/ADMIN/SUPER_ADMIN
  alike per the original spec's own framing).
- This is consistent with, not a change to, the guidance Vikram is already building against — no
  correction needed, just confirming before the controller lands so it isn't independently
  reinvented under time pressure.

## Revisit trigger

Re-review once `AdminSupportController.java`/`AdminSupportService.java` (and, if built alongside it,
a `SupportTicketMessage` entity/repository) actually land: confirm (a) SUPPORT-tier role gate matches
section 1's suggested allow-list — full read+reply+assign+status, no account-level (suspend/KYC)
escalation path; (b) message content never reaches `admin_audit_log` per section 3; (c) whether the
length-cap / no-scanning stance from section 2 was carried forward as documented, or a scanner was
actually added (update this note either way so it doesn't go stale).
