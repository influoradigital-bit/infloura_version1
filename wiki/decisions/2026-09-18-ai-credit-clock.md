# Ruling: which clock refills AI credits

> **Decision by:** Swapnil Maruti (CEO), final authority on business direction. Option A chosen 2026-09-18.
> **Advised by:** Priya (CTO)
> **Date:** 2026-09-18
> **Status:** LOCKED
> **Closes when built:** F-0884, F-0893, F-0894, F-0895, F-0896 (`.proof-os/ledger/failures.jsonl`)
> **Citations:** line numbers are for HEAD `dead7b4`. `AICreditService.java` was last committed in
> `e35d583`. Vikram's uncommitted F-0893 stopgap moves lines in that file from about :440 onward.
> Paths are relative to `influora-api/src/main/java/com/influora/` unless shown in full.

---

## The question

Two clocks refill `brand_ai_credits.credits_remaining`:

- **Calendar month.** `AICreditResetJob` runs at 02:00 UTC on the 1st (`job/AICreditResetJob.java:62`).
  It covers every BRAND workspace (`:95`), syncs the plan allotment (`:102`, `:161-172`), then calls
  `resetForNewCycleIfDue` (`:109`). That method skips the reset if `lastReset` falls in the current
  UTC month (`service/meera/AICreditService.java:507-521`).
- **Billing period.** The renewal safety net calls `resetForNewCycle` when a period advances
  (`service/billing/SubscriptionService.java:819-835`). The upgrade grant sets the full allowance
  once per `currentPeriodEnd` (`AICreditService.java:404-424`, `repository/BrandAiCreditRepository.java:130-142`).

Both clocks apply to every workspace, so a Pro brand that upgraded on the 28th got 400 credits
on the 28th and another 400 on the 1st (F-0896). Commit e35d583 added a guard to stop the second
refill. It compares `lastResetPeriodEnd` against the subscription's `currentPeriodEnd` with no
status filter (`AICreditService.java:433-434`, `:468-476`). A CANCELLED or HALTED row's period
never advances, because the renewal job only selects ACTIVE rows (`job/SubscriptionRenewalResetJob.java:207-210`).
The guard therefore froze Free refills for every ex-Pro brand forever (F-0893).

## Decision: Option A. Pro AI credits follow each brand's billing period. The calendar-month job refills only workspaces on the calendar clock. LOCKED.

### 1. Which clock a workspace is on

One predicate decides this: `SubscriptionService.creditClockFor(workspaceId)`, which returns
`BILLING_PERIOD` or `CALENDAR_MONTH`. The monthly job and every refill path must call it, so no
workspace can be on both clocks or on neither.

The predicate reads the `Subscription` row directly. **It must not be derived from
`getActivePlanForWorkspace`.** That method maps PAST_DUE to Free (`SubscriptionService.java:231-237`,
filter at `:233`). A clock built on it would put a grace-period Pro brand on the calendar clock. The
job would then set its credits to the Free allotment it synced on PAST_DUE, which takes away credits
it already has.

| Subscription state | Clock | Refilled by |
|---|---|---|
| No `Subscription` row | CALENDAR_MONTH | Monthly job |
| Plan FREE, any status | CALENDAR_MONTH | Monthly job |
| Paid plan, not comp, **ACTIVE** (includes `cancelAtPeriodEnd = true`) | BILLING_PERIOD | Period-carrying webhook or safety-net renewal, once per `currentPeriodEnd` |
| Paid plan, not comp, **PAST_DUE** | BILLING_PERIOD | Nothing while PAST_DUE. The balance is held. |
| Paid plan, **HALTED** | CALENDAR_MONTH (as Free) | Monthly job, plus the handover top-up in §3 |
| Paid plan, **CANCELLED** | CALENDAR_MONTH (as Free) | Monthly job, plus the handover top-up in §3 |
| **Comp/admin grant** (`comp = true`), any plan | CALENDAR_MONTH (at the plan's allotment) | Monthly job. The grant itself fills once (§2). |
| Paid plan, not comp, ACTIVE, no `razorpaySubscriptionId` (anomaly) | BILLING_PERIOD | Nothing. The renewal job already refuses to extend this row (`SubscriptionRenewalResetJob.java:264-283`, F-0876). No code path produces it. |

**Why PAST_DUE is on the billing clock.** The grace ruling says Pro stays fully on for 7 days and
the 400 allotment is kept with the "current balance unchanged" (`wiki/decisions/CMO-PRO-DUNNING-GRACE-0917.md`
§1). The work is assigned as S18 in `wiki/processes/assignments-0917-subscription.md:180-189`. It is
not built yet: `getActivePlanForWorkspace` still filters for ACTIVE only (`SubscriptionService.java:233`).
This ruling is correct both before and after S18 lands:

- PAST_DUE has no new paid period, so nothing refills it.
- Recovery arrives as `subscription.charged` carrying the new period (`integration/razorpay/RazorpayWebhookController.java:127-128`).
  That is an ordinary billing-period refill.
- HALTED ends the grace period and moves the brand to the calendar clock.

**Why comp is on the calendar clock.** A comp with no `expiresAt` gets a 10-year period
(`SubscriptionService.java:432`, `:472-473`). On the billing clock it would refill once per decade.
Comp has no charge events, so it follows the same calendar-month anchor that Free rows use
(`SubscriptionService.java:266-273`).

### 2. Every refill path after the change

| Path | Today (HEAD) | After |
|---|---|---|
| `AICreditResetJob` (1st of month) | Syncs and resets every BRAND workspace (`AICreditResetJob.java:95-110`) | **CALENDAR_MONTH workspaces only.** BILLING_PERIOD workspaces are skipped entirely: no sync, no reset. The reset is one atomic UPDATE, guarded in SQL on `last_reset` being before the first of the current UTC month. |
| `applyRenewalSafetyNet` | `applyPlanAllotment`, then `resetForNewCycle`, in one transaction (`SubscriptionService.java:819-835`). This is the F-0894 lost update. | Calls the billing refill primitive with `newEnd`, and only if the workspace is on the billing clock and ACTIVE. It no longer calls `resetForNewCycle`. It stays reachable only for a Razorpay-confirmed, newer period (`SubscriptionRenewalResetJob.java:451-472`). |
| **Razorpay `subscription.charged` webhook** | **Does not refill.** The flow is: `charged` → ACTIVE with `updatePeriod = true` (`RazorpayWebhookController.java:127-128`) → `renewPeriod` (`SubscriptionService.java:722-724`) → `reconcileAiCreditAllotment` (`:738`) → `applyPlanAllotment(400)`. That only grants when `newMonthlyAllotment > oldMonthlyAllotment` (`AICreditService.java:411-412`), which is false for Pro 400 → 400. Today a Pro renewal is refilled only by the monthly job. | **Refills.** `reconcileAiCreditAllotment` calls the billing refill primitive with the row's `currentPeriodEnd` whenever the row is on the billing clock and ACTIVE. The first `activated` and `charged` pair for a new subscription carry the same period, so the second is a no-op. |
| Upgrade grant (Free → Pro, re-subscribe) | `applyPlanAllotment` increase → `grantAllotmentIncrease`, keyed on `credit_grant_period_end` (`BrandAiCreditRepository.java:130-142`). Fails open when `periodEnd` is null (`:137`). | Uses the same primitive as renewal, because an upgrade is a billing-period refill for the period it opens. It fails closed on a null `periodEnd`: a billing-clock workspace always has a row, so null is a bug and is logged at ERROR. |
| `applyEscrowFundedReset` (funded launch, `service/meera/tool/ConfirmLaunchExecutor.java:385-386`) | Refills to `monthlyAllotment` on **every** funded launch, sets a 30-day unlimited window (`ConfirmLaunchExecutor.java:116`), and does a full-row `save()` (`AICreditService.java:323-360`) | **Unchanged in effect.** It is a funding event on neither clock: it refills, sets the loyalty bonus once (`:353-356`), and opens the window. It becomes an atomic UPDATE and **must never write** `credit_grant_period_end`, `last_reset`, or `last_reset_period_end`. Today its full-row `save()` writes back whatever marker values it read, which can revert a concurrent webhook refill's marker and allow a second refill in the same period. |
| `advanceFreePeriod` | Advances the Free period anchor only, with no credit call (`SubscriptionService.java:966-972`) | Unchanged. It never refills. |
| Allotment sync on other transitions (`grantAdminPlan` :508, `finalizeLapsedCancellation` :914, `expireComp` :946, `SubscriptionDunningJob.haltOne` `job/SubscriptionDunningJob.java:167`) | `applyPlanAllotment`, which can grant on an increase | Sync only, through `syncPlanAllotment` (`BrandAiCreditRepository.java:108-115`). A decrease still never takes credits away. Refills come only from the table above, plus the comp initial fill (below) and the handover top-up (§3). |

**Comp initial fill.** `grantAdminPlan` fills to the full allotment once and stamps
`last_reset = today`. The next fill is the monthly job. A comp granted on the 28th therefore gets
400 on the 28th and 400 on the 1st. This is the documented calendar-clock behaviour: a new Free
workspace gets the same treatment, because `ensureInitialized` stamps `lastReset = now`
(`AICreditService.java:141`). It is accepted because comp brings in no revenue.

### 3. Transitions

- **Free → Pro mid-month.** Free on the 1st: credits 100, `last_reset` is the 1st. The
  `subscription.activated` webhook on the 28th carries period 28th → 28th. The workspace is now
  BILLING_PERIOD and ACTIVE, so it refills to 400 with the marker set to the 28th of next month.
  The job on the 1st skips it. The `charged` webhook on the 28th of next month carries a new period
  and refills again. **Exactly one allowance per billing period. F-0896 is closed.**
- **Pro renewal.** Refilled once, by the `charged` webhook, or by the safety net if the webhook was
  missed. A redelivery, a retry, or both paths firing hit the same marker, and the second call
  updates 0 rows.
- **PAST_DUE ⇄ ACTIVE flaps within one period.** Status-only events carry no period
  (`RazorpayWebhookController.java:129-132`, `:353-354`), so `currentPeriodEnd` and therefore the
  marker are unchanged. The result is 0 rows updated. **One grant per period.**
- **Pro → Free on cancel.** `cancel()` leaves the row ACTIVE with `cancelAtPeriodEnd` set
  (`SubscriptionService.java:419-423`), so it stays on the billing clock until the period ends.
  Razorpay cancels at cycle end (`:417`), so no further `charged` event arrives and nothing refills.
  At period end the brand moves to the calendar clock when either of these runs:
  `finalizeLapsedCancellation` (`:906-915`, via `SubscriptionRenewalResetJob.java:247-250`) or the
  `subscription.cancelled` webhook (`RazorpayWebhookController.java:146-147`).
- **Pro → Free on halt.** The dunning job moves the row to HALTED after the 7-day grace
  (`SubscriptionDunningJob.java:80`, `:134-139`), or the `subscription.halted` webhook does it. The
  brand moves to the calendar clock at that moment.
- **Handover top-up (the month of the switch).** Without this, the switching month is skipped. On
  the 1st the brand was still Pro, so the job skipped it. The next calendar refill is up to a month
  after the switch. On every move from BILLING_PERIOD to CALENDAR_MONTH, `reconcileAiCreditAllotment`
  syncs to the Free allotment and then applies a top-up:
  - It sets `credits_remaining = CASE WHEN credits_remaining < monthly_allotment THEN monthly_allotment ELSE credits_remaining END`
    and `last_reset = today`.
  - It runs only when `last_reset` is before the first of the current UTC month. JPQL has no
    GREATEST/LEAST (`BrandAiCreditRepository.java:36-37`), hence the CASE expression.
  - It never lowers a balance, so the "decreases never claw back" rule holds
    (`.proof-os/tasks/T-S3-F0879-0917/RULING-upgrade-grant.md`, "What this does not change").
  - It gives at most one Free allowance per calendar month, and the monthly guard then blocks a
    second refill that month.
  - Because billing refills stamp `last_reset = today`, a brand renewed on the 15th and cancelled
    by webhook on the 20th gets no top-up. It keeps what is left of its 400.
- **Re-subscribe after CANCELLED.** The new `sub_*` id is treated as a reactivation
  (`SubscriptionService.java:708-712`). The new period means a new marker, so the brand gets one
  full fill.

### 4. The two period columns (migrated in `V20260918150000__brand_ai_credit_grant_period.sql:37-39`)

- **`credit_grant_period_end`: KEEP and repurpose.** It becomes the single "billing period last
  filled" marker. The upgrade grant, the webhook renewal and the safety-net renewal all write it
  through one guarded UPDATE.
- It must be **one** marker, not two. `activated` and `charged` both carry the first period
  (`RazorpayWebhookController.java:125-128`). With separate grant and reset markers, `activated`
  would grant 400 and `charged` would refill another 400 in the same period.
- **`last_reset_period_end`: LEAVE UNUSED.** Stop reading and writing it
  (`AICreditService.java:433-434`, `:468-480`). Mark the entity field (`domain/entity/BrandAiCredit.java:67-75`)
  as deprecated. A later housekeeping migration may drop it. No destructive migration is part of
  this build.
- **Backfill.** A new migration sets `credit_grant_period_end` to the subscription's
  `current_period_end` for every workspace on the billing clock whose marker is NULL. Without it,
  existing Pro rows fail the guard's `IS NULL` branch and receive one extra refill on their next
  webhook.
- **Precision.** The markers are `TIMESTAMP` with no fractional seconds (`:38-39`), and so is
  `subscriptions.current_period_end` (`V54__subscription_billing.sql:41`). The comp path builds its
  period from `Instant.now()` (`SubscriptionService.java:472-473`), which has nanoseconds. The
  primitive must truncate `periodEnd` to whole seconds before binding it. Otherwise the stored,
  rounded marker never equals the in-memory value, and the guard never matches.

### 5. Constraints from F-0894 and F-0895

- **F-0894 (lost update).** No refill path may call `save()` on a `BrandAiCredit` it loaded before a
  bulk `@Modifying` UPDATE in the same transaction:
  - Every refill, sync, top-up and escrow write becomes a targeted `@Modifying` query with
    `clearAutomatically = true, flushAutomatically = true`.
  - `resetForNewCycle` and `applyEscrowFundedReset` stop doing full-row saves (`AICreditService.java:359`,
    `:481`).
  - Every refill sets `credits_remaining = c.monthlyAllotment` from the row itself. It never uses a
    Java-computed value. `applyPlanAllotment` currently passes one computed from an in-memory
    `loyaltyBonus` (`:407`, `:411-414`), which would turn a stale read into 400 instead of 450.
- **F-0895 (vacuous gate).** At least one test class must execute the real JPQL against a real
  database.
  - The repo has H2 at test scope (`influora-api/pom.xml:157-159`). The precedent is `@DataJpaTest`
    with an H2 `MODE=MySQL` URL, Flyway disabled and `ddl-auto=create-drop`
    (`influora-api/src/test/java/com/influora/repository/AdminEmailSendLockRepositoryConcurrencyTest.java:52-67`).
  - It also has Testcontainers MySQL (`pom.xml:175-188`), used through `AbstractIntegrationTest` and
    `DockerAvailableCondition`. Those classes **skip** without Docker
    (`influora-api/src/test/java/com/influora/integration/dbconstraints/AICreditRaceIntegrationTest.java:44-53`).
  - **This machine has no Docker. The blocking test is therefore H2.** A Testcontainers twin is
    written too, for CI, because only MySQL shows the `TIMESTAMP` rounding in §4. A skipped
    Testcontainers run does not count as proof.
  - `AICreditResetJobTest` currently leaves `getByWorkspaceId` unstubbed (see F-0893 "missed_by").
    That disables every period guard in the job tests, so it must stub the subscription state
    explicitly.

## Consequence: the build ticket

**T-CREDITCLOCK-0918 · Vikram (BE). Kavya QA, then Kabir. Money path, so falsification is mandatory.**

**Files**
- `service/billing/SubscriptionService.java`:
  - Add `creditClockFor(...)` and an enum for its result.
  - Route `reconcileAiCreditAllotment` to billing refill, handover top-up, or sync only.
  - `applyRenewalSafetyNet` calls the billing refill primitive instead of `resetForNewCycle`.
  - `grantAdminPlan` does the comp initial fill.
- `service/meera/AICreditService.java`:
  - Add `refillForBillingPeriod(workspaceId, periodEnd)`, `topUpOnJoinCalendarClock`, and an atomic
    calendar reset.
  - `applyPlanAllotment` becomes sync only.
  - `applyEscrowFundedReset` becomes atomic and writes no markers.
  - Delete `currentBillingPeriodEnd` and the F-0884 guard. The `@Lazy SubscriptionService` stays
    only for `applyEscrowFundedReset` (`:119-126`, `:338`).
- `repository/BrandAiCreditRepository.java`: add the guarded refill, the calendar reset, the CASE
  top-up and the escrow UPDATE, all with `clearAutomatically`. Retire `grantAllotmentIncrease`.
- `job/AICreditResetJob.java`: skip BILLING_PERIOD workspaces.
- `domain/entity/BrandAiCredit.java`: deprecate `lastResetPeriodEnd`.
- A new Flyway migration (next free slot after `V20260918150000`) for the §4 backfill. Add an entry
  to `wiki/processes/schema-changes.md`.
- Tests:
  - `BrandAiCreditRepositoryPersistenceTest` (H2, real JPQL).
  - `AICreditClockScenarioTest`: H2 `@DataJpaTest` with a real `AICreditService` and real
    repositories, and only `RazorpayClient` mocked.
  - `AICreditClockIntegrationTest`: the Testcontainers twin.
  - `creditClockFor` exhaustiveness test.
  - Fix `AICreditResetJobTest`.

**done_when.** Every scenario runs as a numbers-in, numbers-out test on H2 through the real
services. Each scenario goes red when its guard is deleted, and that check is compiled from a
`git archive` of the commit.

1. **Free, monthly.** Credits 30/100, `last_reset` in August. Job on 1 Oct → 100. Spend 40 → 60.
   Job re-run on 1 Oct → 60.
2. **Ex-Pro CANCELLED, frozen period (Kabir p3).** Row CANCELLED, `currentPeriodEnd` 15 Oct, credits 5.
   Job on 1 Dec → 100. With `loyaltyBonus` 50 → 150.
3. **Ex-Pro HALTED.** Same as scenario 2 but HALTED → 100 (150 with the bonus).
4. **Upgrade on the 28th (F-0896).** Free credits 30. `activated` on 28 Sep with period 28 Sep–28 Oct → 400.
   Spend 50 → 350. `charged` with the same period → 350. Job on 1 Oct → **350**. `charged` on
   28 Oct with period 28 Oct–28 Nov → 400.
5. **Pro renewal once.** Credits 12. `charged` with a new period → 400. Spend 10 → 390.
   Redelivery of the same event → 390. Safety net for the same period → 390.
6. **Safety-net lost update (F-0894 / Kabir p5).** `planAllotment` stale at 100, row Pro and ACTIVE.
   `applyRenewalSafetyNetIfUnchanged` → plan 400, monthly 400, credits 400,
   `credit_grant_period_end` = the new end. It must not be 100 or null.
7. **Flaps.** Pro credits 200 → PAST_DUE → ACTIVE → PAST_DUE → ACTIVE, all status-only in one
   period → 200 throughout. Recovery `charged` with a new period → 400 exactly once.
8. **PAST_DUE on the 1st.** Credits 250 while PAST_DUE. Job on the 1st → 250. It must not be reset
   to 100.
9. **Loyalty preserved.** A funded Pro brand's renewal → 450. An unfunded Pro brand's renewal → 400.
   A funded brand on Free, monthly → 150. A funded launch on Pro → 450 without touching
   `credit_grant_period_end`.
10. **Handover top-up.** Pro renewed on 15 Sep, credits 20, finalized CANCELLED on 15 Oct → 100.
    Job on 1 Nov → 100 after spending. Same case with credits 300 → stays 300. Webhook-cancelled
    on 20 Sep, the same month as the renewal → no top-up.
11. **Grant guard falsified on a real database (F-0895).** With the SQL marker predicate removed,
    scenario 4 or 7 goes red in the H2 test. The Testcontainers twin passes on CI with a
    non-skipped run. The run that counts is Meera's S7 on Docker.
12. **Exhaustiveness.** `creditClockFor` returns exactly one clock for every combination of
    (no row, FREE, paid) × every `SubscriptionStatus` × comp. The table in §1 is asserted verbatim.

**Not decided here:** whether the funded-launch refill should also be once per period. That is a
product question for Swapnil. Today it refills on every funded launch, and this ruling keeps that
behaviour.
