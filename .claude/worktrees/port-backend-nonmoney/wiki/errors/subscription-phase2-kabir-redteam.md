# Subscription Billing Phase 2 (Tasks 19-20) — Kabir Red-Team Audit

**Date:** 2026-07-14 · **Auditor:** Kabir (Offensive Security / Red-Team)
**Scope:** Razorpay Subscriptions integration — `RazorpayClient.java` (createPlan/createSubscription/
fetchSubscription/cancelSubscription), `RazorpayWebhookController.java` (subscription.* handlers),
`SubscriptionService.java` (initiateCheckout/cancel/applySubscriptionWebhookUpdate), `Plan.java`,
`Subscription.java`, `PlanRepository.java`, `SubscriptionRepository.java`, `RazorpayProperties.java`,
`BillingController.java`, `WebhookSignatureVerifier.java`, `IdempotencyService.java`, V54 migration.
**Threat model:** can anyone get free/cheap Pro access, or disrupt another workspace's subscription,
without paying or without authorization?
**Trigger:** mandatory money/webhook-security gate per Vikram's flag + standard pipeline rule.
Routed after Kavya's QA PASS (8/8 checklist items), which explicitly asked for Kabir's independent
second opinion on 3 items (notes.workspaceId tamper-evidence, upsert race, mock-fallback reachability).

## VERDICT: 🟡 CONDITIONAL PASS — 1 HIGH finding blocks prod-config sign-off, not code merge. 3 MEDIUM, 3 LOW/informational.

No Critical findings. No path found for an unauthenticated or under-authenticated actor to grant
themselves Pro access without a real Razorpay payment — the core trust boundary Kavya flagged
(signature verification gates every subscription-webhook code path, `notes.workspaceId` is
server-set and covered by the HMAC) checks out under independent verification. The HIGH finding is
an operational/config hazard (a plausible prod misconfiguration silently strands paying customers,
not a way to steal service), and it does not block merging this code — it blocks Priya/Swapnil
sign-off on the *deployment config* until addressed. Route back to Vikram for the 3 MEDIUM fixes
(cheap, all in `SubscriptionService`/`RazorpayProperties`) before Meera local verify; the HIGH needs
an ops/config decision, not necessarily new code, so it can run in parallel.

---

## Kavya's 3 flagged questions — answered independently

**1. "Can `notes.workspaceId` be spoofed by anyone other than us?"** — No, confirmed independently,
not just trusted. Two things had to both be true and I checked both:

- `notes.workspaceId` is set exactly once, at `SubscriptionService.java:166`
  (`notes.put("workspaceId", workspaceId)`), inside `initiateCheckout(workspaceId, planCode)` where
  `workspaceId` comes from `BillingController.checkout` → `brandContextService.requireBrandWorkspace(principal)`
  (`BillingController.java:150`). The client-facing `CheckoutRequest` DTO
  (`BillingDtos.java:63`) is `record CheckoutRequest(String planCode)` — it has **no** workspaceId
  field at all. There is no code path, malformed or otherwise, where a client-supplied workspaceId
  can reach `RazorpayClient.createSubscription`'s `notes` param. ✅
- Tamper-evidence isn't assumed, it's structural: `RazorpayWebhookController.receive` calls
  `signatureVerifier.verify(rawPayload, signature)` (line 67) and, only if that passes,
  `WebhookEvent.parse(rawPayload)` (line 72) — **the exact same raw string** is what gets
  HMAC-verified and what gets parsed for `notes.workspaceId` (`WebhookEvent.parse`, line 256, reads
  `subscriptionEntity.path("notes").path("workspaceId")` straight out of that string). Razorpay's
  webhook contract signs the full delivered JSON body, and `notes` is part of the subscription
  entity in that body — so any bit-flip of `notes.workspaceId` between Razorpay and us breaks the
  HMAC before the value is ever read. This isn't Razorpay-SDK-specific behavior we have to trust;
  it's true by construction of this endpoint (verify-then-parse-same-string). ✅

**2. "Does the Free→Pro upsert-by-workspace path have any race?"** — Yes, two real ones, see
MEDIUM-1 and MEDIUM-2 below. Kavya's read ("I see no race — the upsert is in a `@Transactional`
method, looks up by razorpaySubscriptionId first, then workspaceId") is correct about the *insert*
branch (protected by DB-level `UNIQUE` on both `workspace_id` and `razorpay_subscription_id`,
V54) but does not cover the *update* branch (no optimistic lock) or cross-delivery ordering
(idempotency dedups identical deliveries, not out-of-order distinct ones). Both are real gaps,
neither is Critical/High — see below.

**3. "Is the mock/stub fallback path reachable in prod where it shouldn't be?"** — Kavya verified
the *attacker-forges-a-fake-webhook* angle correctly (mock subscription IDs can't produce a valid
signature). I checked the angle she didn't: whether the mock/live *split* between
`RazorpayClient.isConfigured()` (gates checkout) and `WebhookSignatureVerifier`'s webhook-secret
check (gates webhook trust) can itself be reached in a broken half-configured state in prod. It
can — this is HIGH-1 below.

---

## HIGH-1: Config split-brain — `keyId`/`keySecret` and `webhookSecret` are validated independently, and only one of the two failure combinations is safe

`RazorpayClient.isConfigured()` (`RazorpayClient.java:41`) is:

```java
public boolean isConfigured() {
    return props.isConfigured(); // RazorpayProperties.java:29 -> keyId + keySecret non-blank
}
```

This is the ONLY gate deciding whether `createSubscription` makes a **real** Razorpay API call
(real checkout URL, real payment) or returns a mock (`RazorpayClient.java:186-192`).
`webhookSecret` is not part of this check.

`WebhookSignatureVerifier.verify()` (`WebhookSignatureVerifier.java:26-37`) independently fails
closed when `webhookSecret` is blank — correct in isolation, but it means these two checks can
disagree:

| keyId/keySecret | webhookSecret | Result |
|---|---|---|
| unset | unset | Mock checkout, mock (rejected) webhooks — safe, matches Kavya's dev-mode analysis |
| set | set | Real checkout, real webhooks verify — safe, intended prod state |
| set | **unset/wrong** | **Real checkout (real money charged by Razorpay), but every subsequent webhook — including `subscription.activated`/`charged` for the payment that just happened — fails signature verification forever** |

The third row is the bug. It's a plausible prod mistake, not a contrived one: `keyId`/`keySecret`
and `webhookSecret` are typically provisioned from different places in a Razorpay dashboard (API
Keys page vs. Webhooks page) and are commonly set via separate env vars / secret-manager entries —
a rotation, a copy-paste of the wrong secret, or an incomplete migration of one over the other is
enough. When it happens: a brand completes a real hosted-checkout payment on Razorpay's own page,
Razorpay attempts to deliver `subscription.activated`, our endpoint returns 400
`INVALID_WEBHOOK_SIGNATURE` every single time (the secret never becomes correct without an ops
fix), Razorpay's retry schedule eventually exhausts, and **the local `Subscription` row is never
created** — `applySubscriptionWebhookUpdate` is the only place that happens
(`SubscriptionService.java:254`), and it's unreachable without a passing signature. The brand paid,
sees no Pro access, and support has to reconcile it manually. Nothing distinguishes this failure
mode from "someone is hammering the endpoint with forged signatures" in the logs — both look like a
stream of `INVALID_WEBHOOK_SIGNATURE` 400s.

This is the inverse of what Kavya checked (she confirmed a forged/mock webhook can't reach money
logic when *unconfigured* — true). The gap is a *partially*-configured prod deployment silently
failing a paying customer instead of erroring loudly, which is exactly the failure mode item 5 of
this audit's brief asked about.

**Fix (recommend, not blocking merge):**
- Add `RazorpayProperties.isFullyConfigured()` (keyId + keySecret + webhookSecret all non-blank) and
  gate `initiateCheckout`'s real-API-call branch on it, not just `isConfigured()` — if
  `keyId`/`keySecret` are set but `webhookSecret` isn't, fail the checkout loudly
  (`ApiException("RAZORPAY_MISCONFIGURED", ...)`) rather than taking a real payment we can't confirm.
- Add a startup-time consistency check (fail-fast in non-local profiles) if the three secrets are
  not all-present or all-blank together.
- Alert on a sustained rate of `INVALID_WEBHOOK_SIGNATURE` specifically for `subscription.*`
  events, distinct from generic webhook noise.

---

## MEDIUM-1: No cross-delivery ordering guard on subscription status transitions

`applySubscriptionWebhookUpdate` (`SubscriptionService.java:254-312`) unconditionally applies
`targetStatus` (and, when provided, the new period) to whatever row it finds — there is no check
that the incoming webhook's event is newer than the subscription's current state.
`IdempotencyService.executeOnce`'s key (`eventType:subscriptionId:webhookCreatedAt`,
`RazorpayWebhookController.java:115-121`) dedups a **retried delivery of the same event**, but does
nothing for two **different, out-of-order** deliveries — e.g., a `subscription.charged` delivery
that Razorpay queued and retried after an outage on our side, arriving *after* a genuinely newer
`subscription.halted`/`cancelled` event for the same subscription. Webhook delivery order is not
guaranteed by Razorpay across separate deliveries, only within retries of the same one.

Concrete bad case: workspace's payment fails → Razorpay sends `subscription.halted` → we correctly
set `status=HALTED`. Minutes later, a delayed retry of an earlier `subscription.charged` (queued
from before the failure, e.g. because our endpoint was briefly down) finally lands → different
idempotency key (different `webhookCreatedAt`) → not deduped → `applySubscriptionWebhookUpdate`
runs again → `status` flips back to `ACTIVE` and `renewPeriod` is called with the **older** event's
period, potentially setting `currentPeriodEnd` earlier than it should be, or simply
incorrectly re-activating a subscription that should stay halted.

This can't be used to get *unpaid* Pro access (every event still requires a valid Razorpay
signature, so it still requires a real Razorpay-side event to have actually happened), but it's a
real correctness gap: a stale delivery can override a fresher one and mis-state a paying customer's
subscription in either direction.

**Fix:** compare the incoming event's `webhookCreatedAtEpochSec` (or period) against a stored
"last applied event time" on the `Subscription` row before overwriting status/period; reject/no-op
on an older event rather than applying it.

---

## MEDIUM-2: Lost-update race on the UPDATE branch of `applySubscriptionWebhookUpdate` (no optimistic lock)

Two genuinely concurrent webhook deliveries with different idempotency keys — the documented
example is `subscription.activated` and `subscription.charged` firing near-simultaneously for the
same first payment, which the class javadoc for `RazorpayWebhookController` already anticipates
(hence wrapping each in `executeOnce` at all) — both execute
`findByRazorpaySubscriptionId(...).or(() -> findByWorkspaceId(...))`, both can read the same
pre-update row, and both then `UPDATE` it. `Subscription` has no `@Version` field and the update
path takes no row lock (`SELECT ... FOR UPDATE`), so this is a plain last-write-wins race — the
loser's write is silently discarded, no exception, no log.

- **The INSERT branch is safe**, contrary to what this might suggest at first glance: V54 puts a
  DB-level `UNIQUE` constraint on both `subscriptions.workspace_id` and
  `subscriptions.razorpay_subscription_id` (confirmed in the migration, not just the JPA
  `@Column(unique = true)` annotation which alone wouldn't matter under Flyway-managed schema). Two
  concurrent inserts for the same workspace collide, one throws
  `DataIntegrityViolationException`, that request 500s uncaught (not one of the two exception types
  `RazorpayWebhookController.handleSubscriptionEvent` catches), Razorpay retries, and the retry
  finds the now-existing row. Self-healing.
- **The UPDATE branch is not.** Today the practical impact is low — `activated` and `charged` for
  the same payment carry near-identical target state (status=ACTIVE, same period), so the "lost"
  write is usually a no-op in substance. But there's no guard preventing this from mattering the
  moment two concurrent events diverge (e.g. a plan-change webhook racing a status webhook).

**Fix:** add `@Version` to `Subscription` for optimistic-lock detection on the update path; on
`OptimisticLockException`, let `IdempotencyService.executeOnce`'s existing `markFailedTransactional`
+ rethrow path handle it (Razorpay retries, next attempt re-reads the merged state).

---

## MEDIUM-3 (informational, low real-world severity): `ensureRazorpayPlanId`'s lock is JVM-local

`SubscriptionService.razorpayPlanLock` (`SubscriptionService.java:63`, a plain `Object` used with
`synchronized`) only prevents concurrent double-creation of Pro's Razorpay Plan **within a single
JVM instance**. In a horizontally-scaled deployment (multiple app instances behind a load
balancer), two instances can both pass the double-checked read
(`planRepository.findById(plan.getId())` finding `razorpayPlanId == null`) before either persists,
each call `razorpayClient.createPlan(...)`, and both `planRepository.save(fresh)` — the second save
silently overwrites the first's `razorpayPlanId` (no DB-level `UNIQUE` on
`plans.razorpay_plan_id`, only on `plans.code`).

I confirm this is genuinely low-impact, not just "self-documented as low-impact" — I traced the
consequence: `resolvePlanForWebhook` (`SubscriptionService.java:314-325`) falls back to
`planService.getProPlan()` for *any* unrecognized `razorpayPlanId`, so a subscription created
against the "orphaned" (overwritten) Razorpay Plan id still resolves to the correct local Pro plan
on webhook — the only cost is a wasted duplicate Plan object on Razorpay's side, not incorrect
billing/fee/entitlement resolution. Downgrading to informational; no fix required before ship, but
worth a follow-up ticket if/when this deploys with >1 instance.

---

## LOW / informational

- **No payload-freshness window on webhooks beyond idempotency dedup.** A validly-signed but
  previously-unprocessed old payload could in principle be replayed as long as the webhook secret
  is unchanged. Low risk in practice (producing a valid signature requires the webhook secret,
  at which point the trust boundary is already compromised regardless of a freshness check) but
  standard defense-in-depth would reject payloads whose `created_at` is older than some bound
  (e.g. 24h) independent of idempotency-key dedup.
- **`BillingController.parsePlanCode`** (`BillingController.java:164-170`) silently coerces any
  unparseable `planCode` string to `PlanCode.PRO` instead of 400ing. Not exploitable — Free has no
  checkout endpoint, so PRO is the only value `initiateCheckout` accepts anyway — but it's
  fail-silent input handling that should 400 on garbage input for API contract hygiene.
- **`WebhookSignatureVerifier.constantTimeEquals`** short-circuits immediately on length mismatch
  before the constant-time XOR loop, in principle timing-leaking the expected length. Not an actual
  exposure: SHA-256 hex output length is fixed and public (64 chars), there's nothing to learn. No
  action needed.

---

## Independently re-verified PASS items (not just re-trusting Kavya's QA)

- **Signature verification gates every subscription code path uniformly**, including all 5 new
  event types (`activated`/`charged`/`pending`/`halted`/`cancelled`), the `invoice.paid` stub arm,
  and the unknown-event default arm — `RazorpayWebhookController.java:67` runs before
  `WebhookEvent.parse()` (line 72) and the `switch` (line 73). No malformed-payload, wrong-header,
  or wrong-Content-Type path reaches the switch without it — `@RequestBody String rawPayload`
  binds the raw body via Spring's string converter regardless of declared Content-Type, so there's
  no separate JSON-deserialization step that could run ahead of `verify()`.
- **`cancelSubscription` authorization is clean** — `BillingController.cancel`
  (`BillingController.java:157-162`) takes no request body and resolves the workspace exclusively
  via `brandContextService.requireBrandWorkspace(principal)`. There is no workspaceId parameter
  anywhere in the cancel path for a client to manipulate. A brand cannot cancel another workspace's
  subscription — there's no code path that accepts a target workspace at all.
- **No SQL injection surface.** `PlanRepository`/`SubscriptionRepository` are 100% Spring Data
  derived-query methods (`findByCode`, `findByActiveTrue`, `findByRazorpayPlanId`,
  `findByWorkspaceId`, `findByRazorpaySubscriptionId`, `findByStatus`) — zero `@Query` with string
  concatenation anywhere in the new repository code.
- **No mass-assignment path.** `Plan`/`Subscription` both use a private no-arg constructor +
  internal `Builder` — neither entity is ever bound directly from a client request. The only
  client-writable billing DTO, `CheckoutRequest`, carries a single `planCode` string field; nothing
  resembling `status`/`ACTIVE` is client-settable anywhere in `BillingDtos`.
- **No secrets logged.** Reviewed every `log.*` call added in `RazorpayClient.java`,
  `SubscriptionService.java`, and `RazorpayWebhookController.java` — fields logged are
  `orderId`/`planId`/`subscriptionId`/`status`/`amount`/`notes` (workspaceId only, non-sensitive).
  Zero occurrences of `keyId`/`keySecret`/`webhookSecret`/raw headers in any log statement.
- **`MISSING_WORKSPACE_ID`/`MISSING_SUBSCRIPTION_ID` validation is real, not decorative.** Checked
  what happens when `notes.workspaceId` is null (e.g. `IdempotencyKeyRecord.workspaceId` is
  nullable at both the entity and V15 migration level, so a null workspaceId doesn't get swallowed
  earlier at the idempotency-reservation step) — it correctly reaches
  `applySubscriptionWebhookUpdate`'s explicit `ApiException("MISSING_WORKSPACE_ID", ...)`
  (`SubscriptionService.java:268-275`), which propagates as a 400, not a silent 200/no-op.

---

## Disposition

- **HIGH-1** does not block merging this code (it's a config/ops gap, not a code defect exploitable
  today with the properties as currently deployed) but **does block Priya/Swapnil sign-off on
  going live** until either the `isFullyConfigured()` gate is added or an explicit ops runbook item
  confirms `keyId`/`keySecret`/`webhookSecret` are provisioned and verified together, every
  environment, every rotation.
- **MEDIUM-1/2** should go back to Vikram as small, contained fixes (`@Version` on `Subscription`,
  an ordering/staleness guard in `applySubscriptionWebhookUpdate`) before Meera's local verify —
  neither requires schema changes beyond adding one column.
- **MEDIUM-3** and the LOW items are follow-up tickets, not blockers.

**NEXT:** Route back to Vikram for MEDIUM-1/MEDIUM-2 fixes (quick, contained). Flag HIGH-1 to Arjun/
Priya as a deployment-config item, not a code-review item — Meera's local verify can proceed on the
MEDIUM fixes in parallel with that decision. Re-submit to Kavya for a targeted re-check of the two
`SubscriptionService` methods once fixed (not a full re-review — same discipline as her Phase 1
1-bug re-check).
