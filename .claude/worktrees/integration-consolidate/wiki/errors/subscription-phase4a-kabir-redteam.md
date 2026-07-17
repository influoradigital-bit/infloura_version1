# Subscription Billing Phase 4a — Kabir Red-Team Audit

**FROM → TO:** Kabir (Red-Team/OWASP) → Arjun (routing), cc Vikram, Kavya, Meera, Priya
**TASK:** Mandatory adversarial security gate on Task 24 (dunning + renewal jobs + AI-credit
reconciliation + invoice generation + billing emails), post-Kavya QA PASS, same discipline as
Phase 2/3a.
**SCOPE:** Authorized internal security review of Sage Digital's own codebase (Influora). Defensive
security work on our own application.
**FILES REVIEWED (independently re-read in full, not trusted from Vikram's/Kavya's summaries):**
- `influora-api/src/main/java/com/influora/job/SubscriptionDunningJob.java`
- `influora-api/src/main/java/com/influora/job/SubscriptionRenewalResetJob.java`
- `influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java`
- `influora-api/src/main/java/com/influora/service/billing/InvoiceService.java`
- `influora-api/src/main/java/com/influora/integration/razorpay/RazorpayWebhookController.java`
- `influora-api/src/main/java/com/influora/domain/entity/Subscription.java`
- `influora-api/src/main/java/com/influora/service/meera/AICreditService.java`
- `influora-api/src/main/java/com/influora/service/BrandContextService.java`
- `influora-api/src/main/java/com/influora/service/AuditLogService.java`, `IdempotencyService.java`
- `influora-api/src/main/java/com/influora/service/notification/event/{InvoiceReadyEvent,SubscriptionPaymentFailedEvent,SubscriptionHaltedEvent}.java`
- `influora-api/src/main/java/com/influora/service/notification/{NotificationListener,NotificationService,EmailWorker}.java`
- `influora-api/src/main/java/com/influora/domain/entity/EmailOutbox.java`
- `influora-api/src/main/java/com/influora/web/BillingController.java`
- `influora-api/src/main/resources/db/migration/V34__admin_tables.sql`, `V18__email_outbox.sql`
- `influora-api/src/main/java/com/influora/repository/SubscriptionRepository.java`
- `influora-api/src/main/java/com/influora/job/StaleTokenCleanupJob.java` (precedent check)

## VERDICT: ✅ CONDITIONAL PASS

No Critical or High findings. Two Medium findings — both real correctness bugs on the
credit/audit path, neither externally triggerable/exploitable by an attacker, neither capable of
granting unauthorized entitlement or unrecoverable money loss. Recommend Vikram fix both before
Priya's final sign-off (same standard as the already-fixed Phase 2 MEDIUM-1/MEDIUM-2 findings
visible in this same code today), but they do **not** block Meera's local verification from
starting now.

---

## FOCUS AREA 1 — AI-credit reconciliation completeness (Kavya's #1 priority)

**Independently re-traced every `Subscription` mutation site in the codebase** (grepped for
`.setStatus(`, `.changePlan(`, `.renewPeriod(`, `subscriptionRepository.save`):

| Site | Mutates status/plan? | Calls reconciliation? |
|---|---|---|
| `SubscriptionService.createFreeSubscription` | new row, ACTIVE/Free | N/A — Free allotment set at signup, not plan-driven |
| `SubscriptionService.cancel` | `cancelAtPeriodEnd=true` only, status stays ACTIVE | N/A — no entitlement change yet |
| `SubscriptionService.applySubscriptionWebhookUpdate` (new-row branch) | yes | ✅ `reconcileAiCreditAllotment` (line 359) |
| `SubscriptionService.applySubscriptionWebhookUpdate` (update branch) | yes | ✅ `reconcileAiCreditAllotment` (line 402) |
| `SubscriptionDunningJob.haltOne` | PAST_DUE→HALTED | ❌ **no call** |
| `SubscriptionRenewalResetJob.resyncOne` | period only (status stays ACTIVE) | ✅ inline `applyPlanAllotment` (PRO only) + `resetForNewCycle` |

**Finding: `SubscriptionDunningJob.haltOne` never calls any AI-credit reconciliation.** This is
exactly the shape of gap this task was built to close — but tracing it through, it is currently
**harmless, not a live bug**: `SubscriptionService.getActivePlanForWorkspace` (line 100) filters on
`status == ACTIVE` only — PAST_DUE and HALTED both fall back to Free identically. Since a
subscription can only reach PAST_DUE via the `subscription.pending` webhook (which **does** call
`reconcileAiCreditAllotment`), the allotment is already synced down to Free the moment the
subscription enters PAST_DUE. The dunning job's later PAST_DUE→HALTED transition doesn't change
the workspace's effective plan at all, so skipping reconciliation there produces no drift today.

Rated **MEDIUM, not High**, because correctness currently holds only by an *implicit invariant*
(PAST_DUE and HALTED coincidentally map to the same fallback plan) rather than by design. If
`getActivePlanForWorkspace` is ever changed (e.g. a future "grace-period Pro access while
PAST_DUE" feature — plausible given the dunning-grace-period design already in this task), this
silently reopens the exact drift bug Priya flagged. **Recommend:** have `haltOne` call the same
`reconcileAiCreditAllotment`-equivalent explicitly, purely for defense-in-depth / to remove the
implicit coupling, not because of an active bug.

**Confirmed no other write path exists:** `BillingController` (client-facing) only exposes
`getPlan`/`getInvoices`/`getUsage`/`getInvoicePdf`/`checkout`/`cancel` — none write
status/plan directly. Task 25's admin comp/override console is confirmed **not yet built** (no
`AdminBillingController` file exists, grepped for it — matches Vikram's/Kavya's claim). No direct
DB-manipulation path exists in application code. **Verdict: PASS on "no alternate bypass path
exists today."**

---

## FOCUS AREA 2 — Dunning/renewal idempotency under concurrency

### 2a. Dunning job vs. concurrent `subscription.halted` webhook — PASS, well-hardened

`Subscription` carries `@Version` (line 78) with prior Kabir Phase-2 hardening already in place:
the webhook path uses `saveAndFlush` (not deferred `save`) so a lost-update is caught
synchronously inside the same transaction, and `IdempotencyService`'s FAILED-key-reclaim (fixed
per its own class javadoc, "E2 HIGH-1") means a webhook retry after an optimistic-lock failure is
**re-runnable**, not permanently wedged. Traced both race orders:
- Webhook commits first → dunning job's later `save()` (plain, but still version-checked at
  flush/commit) throws `ObjectOptimisticLockingFailureException`, caught by `doRun`'s
  per-subscription try/catch **before** the audit-log call (line 149) — no corrupted state, no
  double audit entry, subscription no longer matches `findByStatus(PAST_DUE)` on the next run.
- Dunning job commits first → webhook's `saveAndFlush` throws the same exception, propagates
  through `IdempotencyService.executeOnce`, marks the key FAILED, Razorpay retries, retry
  re-reads the now-HALTED row and applies idempotently (same target status).

Both orders resolve safely. This is a genuine strength, not a finding.

### 2b. Renewal job non-atomicity — **MEDIUM finding**

`SubscriptionRenewalResetJob.resyncOne` (lines 135-178) is **not wrapped in `@Transactional`** at
the job level. Sequence:
```
subscription.renewPeriod(newStart, newEnd);
subscriptionRepository.save(subscription);      // commits in its OWN short transaction
...
aiCreditService.applyPlanAllotment(...);         // separate transaction
aiCreditService.resetForNewCycle(workspaceId);   // separate transaction
auditLog.recordMoneyEvent(...);                  // REQUIRES_NEW, separate transaction
```
If `applyPlanAllotment`/`resetForNewCycle` throws **after** the period-advance `save()` already
committed (e.g. a transient DB error), the period has already been permanently advanced. The
job's own trigger condition (`currentPeriodEnd < now()`) then **no longer matches this row on any
future run** — the credit reset for that cycle is silently skipped with no automatic retry. The
exception is caught at the `doRun` loop level and logged as `failed++`, but that counter doesn't
distinguish "genuinely failed, state unchanged" from "period advanced, credit-sync partially
failed" — which could mask the real gap from whoever is monitoring the job's own log line.

**Not exploitable by an attacker** (requires an independent secondary DB failure at the exact
moment, no client input can trigger it) and **self-limiting** (worst case: the workspace's AI
credits/monthlyAllotment go unrefreshed for at most one billing cycle before the same job
re-evaluates it at the next boundary — this under-serves the customer, it never grants
unauthorized access or double-charges). Still a real correctness gap on the credit path this
whole task exists to harden. **Recommend:** wrap `resyncOne` in a single `@Transactional` (or
reorder so the period `save()` happens last, after the credit-sync succeeds) so a partial failure
rolls back cleanly and the job retries the row the next day.

Same non-atomicity pattern exists in `SubscriptionDunningJob.haltOne` for the audit-log call
(status save commits, then `auditLog.recordMoneyEvent` — if that throws, the HALT already took
effect but is unaudited). Rated **LOW** there specifically — no entitlement consequence (see 1
above), and the failure is still visible via `log.error` even if absent from the `audit_log`
table. Worth the same fix for consistency, not urgent on its own.

### 2c. Degenerate/malformed `currentPeriodEnd` — **LOW-MEDIUM finding**

`SubscriptionService.applySubscriptionWebhookUpdate`'s new-subscription-creation branch
(lines 343-345):
```java
Instant start = periodStart != null ? periodStart : Instant.now();
Instant end = periodEnd != null ? periodEnd : start;
```
If the very first webhook for a workspace (`subscription.activated`/`charged`) carries a
missing/unparseable `current_end` (`WebhookEvent.parse` returns `null` for that field), the new
row is created with **`currentPeriodEnd == currentPeriodStart`** — a zero-length period. This
satisfies the `NOT NULL` DB constraint but is semantically "already expired at creation." Blast
radius is limited: `SubscriptionRenewalResetJob.resyncOne` already defends against exactly this
(`cycleLength.isZero() || isNegative()` → falls back to the 30-day constant, line 140-142), so the
practical effect is one spurious safety-net firing the next day, not permanent corruption.
**Recommend:** on subscription creation, reject a webhook with periodStart-but-no-periodEnd (throw
400, force Razorpay retry) rather than silently persisting a degenerate row — cheap fix, matches
the "fail closed on malformed payload" posture already used elsewhere in this controller.

**Timezone claims verified true, not a "documentation lie":** `@Scheduled(cron=..., zone="UTC")`
genuinely anchors the Spring cron trigger to UTC wall-clock, and every comparison in both jobs
uses `Instant.now()`, which is inherently timezone-agnostic (a single point in time, not a
zoned/local value) — there is no hidden local-timezone leak. `SubscriptionService.
currentBillingCycleStart()`/`nextBillingCycleStart()` anchor to day-of-month=1 exclusively, so
there's no Jan-31→Feb-31 style rollover bug to find.

**Kavya's spurious mid-cycle usage-counter-wipe scenario — traced, confirmed NOT reachable as
described.** If the renewal job reads a subscription in-flight (webhook mid-transaction), the
job's own `subscriptionRepository.save(subscription)` — the version-checked write — is the
**first** state-mutating call in `resyncOne`, before any credit-reset call. Whichever of
job/webhook commits second hits the `@Version` check and throws, so the credit-reset steps never
execute against a state that's about to be overwritten by the other actor. The one residual
(narrow, self-correcting) scenario is the job's *own* safety-net design working as intended: if a
webhook is genuinely late (which is the only way `currentPeriodEnd < now()` becomes true in the
first place), the job's estimated renewal + credit reset can fire slightly before the real
webhook's data arrives — this matches Kavya's own characterization exactly ("minor UX bug — free
extra quota — not a security hole") and is not a race-condition bug, it's the designed behavior of
a safety net for a missed webhook. **Confirmed LOW / non-blocking, as Kavya assessed.**

---

## FOCUS AREA 3 — Invoice amount server-derivation (TECH-STACK.md rule #4)

Grepped the **entire** codebase for `Invoice.builder()` and `invoiceRepository.save`:

```
InvoiceService.java:185   Invoice.builder()
InvoiceService.java:197   invoiceRepository.save(invoice)
InvoiceService.java:208   invoiceRepository.save(invoice)   (PDF/R2 key update, same invoice)
```

**Exactly one construction site, in `generateInvoiceFromWebhook`.** Traced `amountInPaise` back
to its source: `RazorpayWebhookController.onSubscriptionCharged` passes `event.amountInPaise()`,
which `WebhookEvent.parse()` derives from `payload.payment.entity.amount` (falling back to
`payload.order.entity.amount` if the payment node is absent) — both are fields of the
HMAC-signature-verified webhook payload, never a client-supplied value, never `Plan.priceInr`.
Confirmed `BillingController` has no invoice-writing endpoint, and `AdminBillingController`
(Task 25) does not yet exist. **PASS — independently confirms Kavya's claim, no alternate path
found.**

Minor **LOW** note: the method's own defense-in-depth idempotency pre-check
(`if (razorpayPaymentReference != null) { check existing }`, line 150) is skipped entirely when
the payment id is null — in that edge case the method relies solely on the outer
`RazorpayWebhookController`'s `IdempotencyService.executeOnce` (keyed on
`eventType:subscriptionId:webhookCreatedAt`), which is still sufficient in practice, just removes
the second layer of defense for that one edge case.

---

## FOCUS AREA 4 — Billing email PII/security hygiene

Confirmed via `NotificationListener.java` lines 530-569:
- `InvoiceReadyEvent` template data: `amount_inr`, `download_url` only.
- `SubscriptionPaymentFailedEvent` / `SubscriptionHaltedEvent`: empty template maps, hardcoded
  title/body text, no dynamic PII.
- `download_url` is a **presigned, time-limited R2 GET URL** (`r2StorageService.presignGet`), not
  a raw storage key or credential — same pattern as the existing `ContractSignedEvent`.
- No card numbers, no Razorpay API keys/webhook secret, no full payment/subscription tokens ever
  enter an email body or template payload. **PASS.**

---

## FOCUS AREA 5 — AdminAuditLog vs AuditLogService FK claim (spot-check)

Independently re-read `V34__admin_tables.sql` (not just Vikram's/Kavya's citations):
- Line 50: `admin_id VARCHAR(26) NOT NULL`
- Line 62: `CONSTRAINT fk_admin_audit_admin FOREIGN KEY (admin_id) REFERENCES admin_users(id)`
- No synthetic "SYSTEM" `admin_users` row exists anywhere in migrations/seed data.
- `StaleTokenCleanupJob.java` (lines 51, 102-109) confirmed as a real, pre-existing precedent:
  an autonomous `@Scheduled` job using `AuditLogService.recordToolCall`, not
  `AdminAuditLogService`.

**Confirmed independently: the deviation is architecturally sound.** Agree with Kavya's PASS
ruling — `admin_audit_log` genuinely cannot accept a row from an unauthenticated scheduled job
without a migration adding a seeded system `AdminUser`, which is correctly scoped out of this
task.

---

## FOCUS AREA 6 — Standard money-path checks

- **SQL injection:** `SubscriptionRepository` (and the other new/modified repositories touched by
  this task) use only Spring Data derived-method queries (`findByStatus`,
  `findByRazorpaySubscriptionId`, `findByWorkspaceId`) — zero `@Query`/native SQL, zero
  string-concatenation surface. **PASS.**
- **Secrets logging:** reviewed every `log.info`/`log.warn`/`log.error` call in the reviewed
  files — all log ids, statuses, counts, and timestamps; none log raw webhook payloads, the
  webhook HMAC secret, Razorpay API keys, or full payment tokens. **PASS.**
- **Replay-attack surface:** `RazorpayWebhookController.receive` gates the **entire** dispatch
  switch (order/payment/payout/subscription, all of it) behind one
  `signatureVerifier.verify(rawPayload, signature)` call before any parsing — no subscription
  event type bypasses this. Idempotency is enforced via `IdempotencyService.executeOnce` keyed
  per exact delivery (`eventType:subscriptionId:webhookCreatedAt`), with the FAILED-key-reclaim
  fix in place so a legitimate retry after a transient failure isn't permanently blocked. Combined
  with `@Version` optimistic locking on `Subscription`, this is a well-hardened path. **PASS.**
- **Secondary, pre-existing (not introduced by this task) observation:**
  `NotificationService.queueEmailIfNotUnsubscribed` (line 129) uses a check-then-insert pattern
  (`findByIdempotencyKey` read, then `save`) rather than an atomic insert-first-wins pattern like
  `IdempotencyService`. A DB-level `UNIQUE KEY uq_email_outbox_idempotency` (V18) still prevents
  an actual duplicate row under a genuine race — the second `save()` throws
  `DataIntegrityViolationException`, uncaught, inside an `@Async` listener, so it's silently
  logged by Spring's async exception handling rather than gracefully short-circuited. Net effect:
  the `SubscriptionHaltedEvent` javadoc's claim ("a genuine double-fire dedupes at the EmailOutbox
  idempotency layer") **holds functionally** (no duplicate email is ever actually sent — verified
  the unique constraint is real, V18 line 18), just via an exception path rather than a clean one.
  This is pre-existing codebase-wide behavior that Task 24 is simply the first caller to
  explicitly depend on for a documented dual-trigger safety design. **LOW / informational** —
  recommend a follow-up hardening ticket to wrap that `save()` in the same
  try/catch-on-`DataIntegrityViolationException` pattern `IdempotencyService` already uses; not a
  Task 24 blocker.

---

## SUMMARY TABLE

| # | Finding | Severity | Blocking? |
|---|---|---|---|
| 1 | `SubscriptionDunningJob.haltOne` doesn't call AI-credit reconciliation (currently harmless — PAST_DUE/HALTED both already fall back to Free) | MEDIUM | No — recommend fix for defense-in-depth |
| 2 | `SubscriptionRenewalResetJob.resyncOne` not atomic — partial failure after period-advance can silently skip one cycle's credit reset, no auto-retry | MEDIUM | No — recommend fix, narrow/non-exploitable window |
| 3 | Dunning job's audit-log call not atomic with the status save (observability gap only) | LOW | No |
| 4 | New-subscription creation can persist a zero-length period if webhook payload's `current_end` is missing (self-heals via renewal job's existing fallback) | LOW-MEDIUM | No |
| 5 | `InvoiceService` idempotency pre-check skipped when payment id is null (outer webhook-level idempotency still covers it) | LOW | No |
| 6 | `NotificationService` check-then-insert TOCTOU (pre-existing, not Task 24) relies on DB unique constraint + uncaught exception rather than a graceful catch | LOW / informational | No |

**No Critical or High findings.** All money-path invariants (invoice amount derivation, webhook
signature gate, idempotency, optimistic locking, no alternate Subscription-write path, no PII in
billing emails, AdminAuditLog FK) independently re-verified and hold.

## NEXT

Route to **Meera** for local verification (`mvn -o test`, `npx tsc --noEmit`, webhook
curl/replay smoke test per Kavya's TODO list) — cleared to proceed now, no blocker. In parallel,
recommend **Vikram** pick up findings #1 and #2 above (both small, targeted fixes — an explicit
reconciliation call in `haltOne`, and wrapping `resyncOne` in `@Transactional`) as a fast-follow
before **Priya's** final sign-off, consistent with how Phase 2's MEDIUM-1/MEDIUM-2 findings were
closed out (both visible as already-fixed, well-documented `[SEC: Kabir red-team MEDIUM-*]`
comments in this same codebase today).
