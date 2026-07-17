# Tracking Subsystem — CTO Architectural Ruling

> **Ruling by:** Priya (CTO)
> **Date:** 2026-07-13
> **In response to:** `wiki/tech/tracking-subsystem-gaps.md` (Arjun, Engineering Lead)
> **Method:** Full read of `RedemptionService`, `AffiliateEarningsService`, `AffiliateEarning` entity, `AffiliateEarningReconciliationJob`, `ConversionTrackingService` + git history of the redemption file.
> **Status:** 🟢 DECIDED — routes below are binding. GAP #3 is a P0 regression, route to Vikram immediately.

---

## TL;DR (one line per question)

| Q | Verdict | Route |
|---|---------|-------|
| **Q1 — GAP #3 (earnings cron)** | **Synchronous is correct. The code is the bug — a lost fix, not a design choice. Javadoc is NOT stale.** | 🔴 **Vikram — P0 fix now** |
| **Q2 — GAP #2 (UTM no earnings)** | **Reporting-only is intentional and FINAL. Coupons are the only commission path.** Add UI label. | 🟡 Ananya (UI label), no backend change |
| **Q3 — GAP #1 (link not enforced)** | **(b) UI warning + surface the redirect URL as the canonical share link.** No shortener yet. | 🟡 Ananya (UI), backend returns redirect URL |
| **Q4 — two-path split** | **Split stays. Do NOT converge.** Convergence is a future feature, not a fix. | ⚪ No action |

---

## Q1 — GAP #3 (CRITICAL): synchronous `recordEarning` is correct; the missing call is a regression

### Verdict: **The synchronous call is the intended design. The current `RedemptionService` is the defect. This is a P0.**

This is not an ambiguous "which did we mean" call. The evidence that synchronous-at-redemption-time is the intended, reviewed, signed-off design is overwhelming — **three independent code artifacts document the synchronous call as an accomplished, Kabir-reviewed fact**, and only the one file that was supposed to carry the call is missing it:

1. **`AffiliateEarningsService` class javadoc** (`AffiliateEarningsService.java:44-58`): *"`recordEarning` is called by `RedemptionService#doRedeem` AFTER `redemptionRepository.save(redemption)` has already run inside `RedemptionService`'s own `IdempotencyService.executeOnce` wrapper."*
2. **`recordEarning` method javadoc** (`AffiliateEarningsService.java:270-273`): *"Called from `RedemptionService#doRedeem` immediately after the redemption row is saved (still inside that method's own transaction/idempotency wrapper)."*
3. **`AffiliateEarningsService.java:79-85` and `:123-126`** reference *"`RedemptionService`'s identical `self` field"* and a shared *"[SEC: Kabir, Wave D task D4 HIGH-1 — FIXED]"* self-invocation transactional fix — describing wiring in `RedemptionService` that **does not exist**.
4. **`AffiliateEarningReconciliationJob.java:22-34`** describes itself as a *"belt-and-suspenders floor"* on top of the synchronous path, and asserts *"`RedemptionService#doRedeem` now runs inside a real, proxy-honored `@Transactional` boundary … so a `RuntimeException` thrown by `affiliateEarningsService.recordEarning` correctly rolls back the whole redemption."*
5. **`AffiliateEarning` entity javadoc** (`AffiliateEarning.java:13-15`): *"Created by `AffiliateEarningsService` when a `CouponRedemption` qualifies for affiliate commission."*

**What the code actually is** (verified, not assumed):
- `RedemptionService` does **not** inject `AffiliateEarningsService`, has **no** `self` field, and **never** calls `recordEarning`. Grep across `service/tracking/RedemptionService.java`: **0 matches** for `recordEarning|AffiliateEarningsService|self.doRedeem|@Lazy`.
- Grep across the whole backend: `recordEarning` is referenced in **only 2 files** — `AffiliateEarningsService` (defines it) and `AffiliateEarningReconciliationJob` (the hourly cron). **The cron is the sole production caller.**
- `git log` on `RedemptionService.java`: **exactly one commit** (`f54e5dc feat(tracking): Phase 4`). The file was written once and never touched again. The Wave D "[… FIXED]" change that three other files reference **was never committed to this file.**

**Root-cause classification:** This is a **lost/never-landed fix**, not stale documentation and not an intentional async design. The Wave D task D4 fix was applied to `AffiliateEarningsService` (it got its `@Lazy self` proxy) and the reconciliation floor was added, but the corresponding `RedemptionService` change — inject the earnings service, call `recordEarning`, add its own `self` proxy — was dropped before commit. The surrounding files were written against the intended end state and now over-describe reality.

**Why this is P0, not "delayed but fine":** the reconciliation cron was designed as a *safety net for rare residual gaps* (process crash between commit and idempotency finalize). It is currently load-bearing for **100% of commissions**. Consequences:
- Every commission lags up to ~1.5h (hourly cron + 30-min grace).
- If the cron is disabled or its sweep throws before the batch, **creators are never paid** — a silent money-path failure with no synchronous fallback.
- The documented contract ("immediate") is violated on every redemption.

### Fix — exactly where the call goes

Route to **Vikram**. This restores the lost, already-documented, already-reviewed wiring. Two parts, both required to satisfy the contract the other three files already assert:

**Part A (the missing earnings call) — REQUIRED:**
1. Inject `AffiliateEarningsService` into `RedemptionService` (constructor field, same as the other four deps at `RedemptionService.java:80-94`).
2. In `performRedemption(...)`, add the call **after the `auditLogService.recordMoneyEvent(...)` block (ends `RedemptionService.java:311`) and immediately before `return redemption;` (`RedemptionService.java:313`)**:
   ```java
   affiliateEarningsService.recordEarning(redemption);
   ```
   This is the exact placement the `recordEarning` javadoc specifies: *"immediately after the redemption row is saved, still inside that method's transaction."* Because `performRedemption` is called by both `doRedeem` and `doRedeemScoped`, this single placement covers the workspace-scoped and legacy paths.

**Part B (honor `@Transactional` so redemption + earning commit atomically) — REQUIRED for the rollback guarantee to be real:**
- `doRedeem`/`doRedeemScoped` are annotated `@Transactional` (`RedemptionService.java:240,247`) but are invoked via **same-bean self-invocation** — `redeem()` calls `doRedeem(...)` directly inside the `executeOnce` lambda (`RedemptionService.java:173` and `:222`). Spring's proxy is bypassed, so `@Transactional` is currently **not honored**. Today that is masked (each `save` auto-commits); once Part A adds a second money-path write, a partial failure would leave a redemption with no earning **committed** — exactly the state the reconciliation job's javadoc (`:22-34`) claims is impossible.
- Restore the `@Lazy`-qualified self-reference (mirror `AffiliateEarningsService`'s `self` field at `:122-130`): inject `@Lazy RedemptionService self`, and change the two `executeOnce` lambdas to call `self.doRedeem(...)` / `self.doRedeemScoped(...)`. Then redemption save + coupon update + `recordEarning` all commit or roll back as one unit, and a `recordEarning` `RuntimeException` correctly rolls back the redemption (retried on next webhook delivery, then the cron as final floor).

**Keep the cron.** After this fix it becomes the true belt-and-suspenders sweep it was documented to be. Do not remove it.

**Do NOT choose Option B from the gaps doc** (rewrite the javadoc to bless the cron as primary). That would enshrine a ~1.5h commission lag and a single point of total payment failure as the official design. Rejected.

### Follow-up work for Q1 (all part of the same PR / route)
- **Regression test (blocking):** a test proving `redeem(...)` creates the matching `AffiliateEarning` synchronously **without invoking the reconciliation job** — assert the earning exists immediately after the redeem call returns. This is the guard that would have caught the lost wiring. Add to the redemption service test suite.
- **Atomicity test:** force `recordEarning` to throw; assert the redemption row is **not** persisted (rollback), i.e. Part B is actually working.
- **Cron alerting:** the sweep already audit-logs `AFFILIATE_EARNING_RECONCILIATION_COMPLETED` with a `backfilled` count. Post-fix, `backfilled > 0` should be **rare** — wire an alert on any non-zero backfill so a future recurrence of this exact regression is caught in hours, not by a creator complaint. (Meera/DevOps to own the alert threshold.)
- **Pipeline:** money path — Vikram → **Kabir (money-path red-team, mandatory)** → Kavya → Meera → me for sign-off. No shortcut; this touches commission creation.

---

## Q2 — GAP #2: UTM conversions are reporting-only. Intentional and FINAL.

### Verdict: **Reporting-only is correct and intentional. Coupons are the sole commission path. Do NOT add UTM-conversion earnings. Add a UI label.**

This is not an oversight — it is a **data-model consequence** the code already documents:
- `AffiliateEarningsService` javadoc (`:36-42`): a coupon redemption is the "qualifying conversion" precisely because it *"is already workspace/campaign/creator-scoped via its owning `CouponCode` and carries the exact per-order commission base (`orderAmount`)."*
- `ConversionTrackingService` javadoc (`:39-58`): `UtmCampaign` has **no `workspace_id`**, **no per-creator revenue rollup**, and **no unique idempotency column** (`:59-70`). Paying off a UTM conversion would require inventing a commission base, a workspace scope, and a double-credit guard that the schema does not have.

Paying UTM conversions is therefore a **feature with a migration**, not a bug fix. We are not doing it now. A creator can always be given a coupon if commission is intended; that is the designed commission instrument.

**Required change:** UI label so creators are never misled. Route to **Ananya**. Copy, on the trackable-link surface and analytics view:
> "Trackable links measure clicks and attributed sales for reporting. **Commission is earned through your coupon code**, not links."

No backend change.

---

## Q3 — GAP #1: link indirection not enforced. Option (b) — UI warning + canonical redirect URL.

### Verdict: **(b). Surface the `/track/click/{id}` redirect URL as THE share link, with a warning not to post the raw brand URL. No shortener yet.**

- **(a) auto-wrap in a shortener — rejected for now.** A shortener (Bitly/Rebrandly/self-hosted) is a **new external dependency** — it needs my sign-off in `wiki/tech/approved-deps.md` **and** Rohan's cost review before anyone integrates it. It is not blocked forever, but it does not ship as an unreviewed side effect of a tracking bug. Not this pass.
- **(c) leave as-is — rejected.** Silent tracking loss on the money-adjacent path is not acceptable "creator responsibility." We at least have to make the correct action obvious.
- **(b) — accepted.** Backend already builds the redirect id; ensure the API returns the **redirect URL** (`/track/click/{id}`) as the primary shareable field, not just the raw `fullTrackingUrl`. UI presents the redirect URL as the copy-this link with warning copy:
  > "Share **this exact link** so your clicks are counted: `{redirectUrl}`. Posting the store's direct URL will not track clicks."

Route: backend (Vikram) confirms/returns the redirect URL as canonical; UI (Ananya) presents + warns. Low effort, closes the silent-loss gap without new vendors.

**Long-term (backlog, not now):** if link volume justifies it, revisit a shortener behind a proper dependency approval + cost sign-off. Logged, not scheduled.

---

## Q4 — Broader: keep the two-path split. Do NOT converge.

### Verdict: **The split is the final design for this release. Coupons = earnings (structurally scoped, idempotent, per-order base). Links = attribution reporting.**

Convergence (link conversions also pay) is a **product + schema decision**, not an architectural cleanup:
- It requires `utm_campaigns` to gain a `workspace_id` (or a resilient join), a unique idempotency column, and an agreed commission base — plus a rule for **double-counting** when a single checkout is attributed via *both* a link and a coupon (`ConversionTrackingService.java:39-48` already flags this exact double-count risk for campaign-level rollups).
- Until Swapnil/product actually want link-paid commissions, building the plumbing is speculative.

If/when product asks for it, the clean path is: add the missing scope/idempotency columns to `utm_campaigns`, add a `recordEarningFromConversion(...)` path resolving the creator via `utm.creatorProfileId`, and define the link-vs-coupon dedup rule first. That is a scoped feature I will architect then. **No action now.**

---

## Consolidated routing for Arjun

| Item | Owner | Priority | Type |
|------|-------|----------|------|
| **GAP #3 Part A** — inject `AffiliateEarningsService`, call `recordEarning(redemption)` at `RedemptionService.java:312` (before `return redemption;`) | Vikram | 🔴 **P0** | Regression fix |
| **GAP #3 Part B** — `@Lazy self` proxy so `doRedeem`/`doRedeemScoped` `@Transactional` is honored (fix self-invocation at `:173`, `:222`) | Vikram | 🔴 **P0** | Regression fix |
| **GAP #3 tests** — synchronous-earning regression test + rollback-on-throw test | Vikram | 🔴 **P0** | Blocking |
| **GAP #3 alert** — alert on cron `backfilled > 0` | Meera | 🟠 P1 | Monitoring |
| **GAP #2** — UI label "coupons pay, links report" | Ananya | 🟡 P2 | UI |
| **GAP #1** — return redirect URL as canonical + UI warning | Vikram + Ananya | 🟡 P2 | UI/API |
| **GAP #4** — none | — | ⚪ | No action |
| **GAP #5 placeholders** (10% rate, INR, dedup, per-user limits) | — | ⚪ | Accepted as documented; revisit when product signs off on real numbers |

**Money-path pipeline for the GAP #3 fix (mandatory, no shortcut):** Vikram → **Kabir** (money-path red-team) → Kavya (QA) → Meera (local verify + alert wiring) → **Priya (sign-off)**.

**Documentation note:** once Part A + B land, the javadocs in `AffiliateEarningsService`, `AffiliateEarningReconciliationJob`, and `AffiliateEarning` become accurate — no doc edits needed there; they were correct about the *intended* state, the code just hadn't caught up. Only add a brief "restored in <commit>" note to `RedemptionService`'s class javadoc so the history is legible.

---

_Ruling issued 2026-07-13 by Priya (CTO). GAP #3 is a P0 silent-payment regression: the synchronous earnings call is the intended, reviewed design and must be restored. Binding on the tracking subsystem until superseded by a CTO-signed revision._
