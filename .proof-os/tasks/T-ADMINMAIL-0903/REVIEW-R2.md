# T-ADMINMAIL-0903 — CTO verdict, round 2: DO NOT SHIP

3 SAFE (idempotency, serialization-abort, unsubscribe-at-dispatch), 7 RISK. Three are ship-blocking.

## Ship-blockers — fix these three

**B1 · The rate limit is a lock-free read-then-write, and the idempotency key does not cover it.**
`enforceRateLimit()` is a bare `findTopByOrderByCreatedAtDesc()` with no `@Lock` / `FOR UPDATE`, and
the row that would close the window is not committed until the end of `send()`. Two admins — or one
admin submitting *different* copy twice, or two instances behind the LB — both read the same stale
"last campaign" and both pass. C1's deterministic id does **not** save this: different subject/body
hashes to a different `campaignId`, so there is no unique-index collision. Result: 10,000 emails
against a 5,000 cap and a 1-per-10-minutes throttle. Needs real serialization — a singleton lock row
taken `FOR UPDATE`, or a unique constraint on a time bucket. `AdminCustomEmailServiceTest
.sendRateLimited` stubs a single return and would pass unchanged with the hole wide open.

**B2 · `/custom/preview` is an unaudited, platform-wide email-address oracle that mints live
unsubscribe tokens.** Round 2 scoped `resolveSample` to `matchesAudience` — but the audience is
caller-chosen, so `{userType: "ALL", onlyVerified: false, registeredWithinDays: null}` reduces the
predicate to `status == ACTIVE`. Any userId therefore resolves, and its address is returned in
`sampleRecipientEmail`. Worse, `renderPreview` calls `buildUnsubscribeUrl(sampleUserId, ...)`, which
mints a **real HMAC token** redeemable at the unauthenticated `GET /notifications/unsubscribe-link`.
And `preview()` makes no `recordAdminAction` call at all, so this leaves no trace. Fixes needed:
sample from the audience server-side rather than trusting a caller-supplied id (or verify the id is
in the *narrowed* audience and rate-limit it), never mint a live unsubscribe token for a preview —
use an inert placeholder href — and audit preview.

**B3 · No abort for a queued send.** Nothing deletes or cancels a PENDING outbox row anywhere in
`src/main/java`; `AdminEmailService` exposes only `retry`. Once `send()` commits, a typo'd blast to
5,000 people drains at 50/poll and the only stop is a manual `UPDATE` on production. Add a cancel
endpoint that marks this campaign's PENDING `admin.custom` rows terminal (SUPER_ADMIN + MFA,
audited). This is the difference between a mistake and an incident.

## Also real — fix while you are in here

**B4 · `TOKEN_PATTERN` is `\{\{(\w+)\}\}`, so `{{first-name}}`, `{{ first_name }}` and `{{first.name}}`
match neither validator** and ship as literal text to every recipient. That is exactly the shape a
human typo takes. The javadoc claims "an unknown token never reaches a recipient as a literal
`{{foo}}`" — currently false. Widen the detection pattern to `\{\{[^}]*\}\}` for *rejection* while
keeping `\w+` for substitution.

**B5 · `admin_email_campaigns.recipient_count` disagrees with what was actually mailed.** It stores
the pre-unsubscribe audience size while `toEnqueue.size()` is what went out, and the audit log
records the true `queued` — so the two records contradict each other. Control #4 is "who sent what to
**how many**"; store the queued figure too. `replaySendResponse` reconstructing `queued` as
`recipientCount - skippedUnsubscribed` should read a stored column instead.

## Two documentation defects — a comment that overstates the code is a finding

- `send()`'s javadoc says a double-click is "caught cheaply by the `findById(campaignId)`
  short-circuit". It is not: `enforceRateLimit()` runs ~35 lines earlier, so the real double-click
  returns **429**, not the documented idempotent replay. Either move the short-circuit above the
  rate-limit check or correct the comment.
- A deliberate resend of identical copy to an unchanged audience hashes to the same id, replays, and
  returns 200 with the original counts **while mailing nobody**. `SendResponse` has no field
  distinguishing a replay from a real send, so the admin cannot tell. Add one.

## On the tests — read this before writing more

`EmailWorkerTest`'s C2 case only captures the `priorityKeys` argument; revert the JPQL to plain
`createdAt ASC` and it still passes. `EmailOutboxRepositoryQueryTest` *does* fail on both removal and
inversion of the CASE (verified), but it asserts the **query string**, not behaviour — neither test
executes JPQL against a database. Do not treat either as proof the priority lane orders rows at
runtime.

And the substantive point behind it: the priority lane fixed the 50-minute starvation, but an OTP
arriving just after a 50-row batch is claimed still waits for up to 50 sequential blocking SMTP calls
plus the 30s `fixedDelay` — `CLAIM_LEASE` is 3 minutes, which is the design's own estimate of how long
a batch can run. Consider capping `admin.custom` rows per batch so a blast can never fill all 50 slots.
