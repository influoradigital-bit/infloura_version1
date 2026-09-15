# Task breakdown — subscription model, remaining work

**Owner:** Arjun (Engineering Lead) · **Date:** 2026-09-15
**Source of truth for scope:** `wiki/tech/SUBSCRIPTION-MODEL-REDESIGN-0912.md` (Priya)
**Committed so far:** `7638afb` — entitlement registry, rotate-identifier fix, four class gates, doc sweep (73 files)

> **Bus note.** `SHARED_CONTEXT.md` and `TASK_INBOX.md` are both held uncommitted by the
> concurrent session in this checkout. This breakdown lives here instead of on the bus so it
> cannot clobber their in-flight edits. Fold it into the bus when they land.

---

## The one thing that matters

Everything committed on 2026-09-13 is **machinery**. It has produced **zero subscription rows in
production**. 31 workspaces, 0 rows. Lane A is the only lane that changes that, and it needs almost
no engineering — it is a config check, a migration run, and an admin action.

Do Lane A before any of B–E. Building `<UpgradeGate>` for a page nobody can reach repeats the exact
mistake that made this project necessary.

---

## LANE A — Make it real (no new code)

| ID | Task | Owner | Depends on | Done when |
|---|---|---|---|---|
| A1 | Push `7638afb` | Meera | — | branch pushed, CI green |
| A2 | Verify Razorpay is provisioned on the VPS: `RAZORPAY_KEY_ID`, `KEY_SECRET`, `WEBHOOK_SECRET` all real, none the committed `REPLACE_WITH_*` placeholder | Meera | A1 | all three confirmed non-placeholder |
| A3 | Run `V20260912120000__backfill_free_subscriptions.sql` on prod | Meera | A2 | 27 BRAND workspaces hold a Free/ACTIVE row |
| A4 | Comp Pro to the 27 from the admin console, **with an expiry** (6 or 12 months, not the 10-year default) | Swapnil | A3 | 27 rows on Pro with an end date |

**A2 before A3 is not optional.** The Billing entry is live in the sidebar as of `7638afb`. If
Razorpay holds placeholder keys, every one of those 27 brands now sees an Upgrade button that
returns 503 — `SubscriptionService.initiateCheckout` refuses outright when the webhook secret is
missing.

**A3 before A4 is not optional either.** `AdminBillingService.listSubscriptions` pages over
`Subscription` rows. Before the backfill the admin billing console is empty and there is literally
nobody to comp.

---

## LANE B — Decisions (Swapnil). Each blocks work below.

| ID | Decision | Blocks | Recommendation on record |
|---|---|---|---|
| B1 | Agency chooser: remove it, or build the product? | C5, D2 | Priya recommends remove. 4 workspaces are silently degraded today — no credit reset, invisible to admin, cannot be comped |
| B2 | Restate F-0800's `done_when`: "a subscriptions row **exists**", not "visiting **creates** one" | closes F-0800 | F-4 made the causal version false; a gate written to it would go red against correct code |
| B3 | Ratify §6 of the original plan (₹4,999 / 7% / Free caps / no-trial) | closes the gate | All four are already live on the public pricing page and in shipped code |

---

## LANE C — Backend (Vikram)

| ID | Task | Depends on | Done when |
|---|---|---|---|
| C1 | SM-2.2 — saved-creator cap: one `requireCapacity` call in `CreatorDiscoveryService.toggleSaved`, plus the missing `count` on `SavedCreatorRepository` | — (P1 landed in `7638afb`) | conformance gate red→green; deleting the call turns it red |
| C2 | SM-3.3/3.4 — split `BrandAiCredit.monthlyAllotment` into `planAllotment` + `loyaltyBonus`, derive the total, with migration | B-ruling already given (loyalty stacks) | a plan sync can no longer wipe an earned bonus; migration verified on MySQL 8 |
| C3 | SM-3.6 — F-5 Razorpay blank defaults, matching the Meta convention in the same `application.yml` | **BLOCKED** — other lane holds `application.yml` | `isConfigured()` false on a placeholder tree |
| C4 | F-0815 — repair the six dead metrics: a real writer or removal for each, then drop its baseline entry | — | `_dead_metric_baseline.json` shrinks; gate still green |
| C5 | Convert the 4 AGENCY workspaces to BRAND (SQL) | B1 | 31/31 have subscription rows |

**C4 is the one with user-visible consequence.** A brand's tracked-creator plan limit currently
rests on a meter that is permanently zero, and `MediaMetric.avgWatchTimeSeconds` is serialised to
API clients as a number nothing ever computed.

---

## LANE D — Frontend (Ananya)

| ID | Task | Depends on | Done when |
|---|---|---|---|
| D1 | Phase 4 — structured 402 body consumed; one `<UpgradeGate>` wired to all four gated surfaces (analytics, seats, export, templates), role-aware via `useBrandBillingAccess`, CTA calls `initiateCheckout('PRO')` | C1 + Vikram's 402 shape | all four surfaces render the gate; CTA reaches checkout |
| D2 | Remove the Brand/Agency card from `onboarding-steps.tsx` | B1 | onboarding offers BRAND only |

Today only `useCreatorMetrics.ts:34` recognises `UPGRADE_REQUIRED`, and all it does is swap an
error string — no button, no route to checkout. That is the highest-intent moment in the product,
wired in one of four places.

---

## LANE E — Gates, QA, close-out

| ID | Task | Owner | Depends on |
|---|---|---|---|
| E1 | F-0799 — thin `.proof-os/gates/` wrapper so the billing-nav test can close by promotion | Vikram | — |
| E2 | F-0805/F-0806 — gate for "a doc states a claim and its negation in the same file" | Vikram | — |
| E3 | OWASP pass on C1–C4 | Kabir | C1–C4 |
| E4 | Live click-through as a brand: nav → billing → upgrade → each of the four gates. Real clicks, not synthetic `.click()` | Neha | D1 + Lane A |
| E5 | Cost log for the 2026-09-12/13/15 waves | Rohan | — |

---

## Critical path

```
A1 → A2 → A3 → A4          ← ship it; no engineering, highest value
B1 ─┬→ C5
    └→ D2
C1 → D1 → E4               ← the conversion funnel
C2, C4, E1, E2             ← parallel, unblocked
C3                          ← blocked on the other lane releasing application.yml
```

## Standing constraints for every agent on this list

1. A concurrent session shares this checkout. Check `git status` before editing; never touch a file
   it holds. It currently holds `application.yml`, `InfluoraApiApplication`, `PlanGateInterceptor`,
   `RequiresPlan`, `UsageCounterService`, `CreatorNudgeService`, `SubscriptionRenewalResetJob`,
   `SHARED_CONTEXT.md`, `TASK_INBOX.md`.
2. `mvn` from **inside** `influora-api/` — it is the only pom. Never pipe into grep. Always read the
   `Skipped:` count, not just the exit code.
3. A red gate in this checkout is not automatically a finding — shared `target/` corruption from
   concurrent builds produced three false reds on 2026-09-13/15. Re-run before reporting.
4. Falsify **every** call site a gate names, not the first. Four gates shipped a first cut with that
   exact blind spot in one session.
5. `git add` new files. An untracked file passes every local gate while the pushed branch cannot
   build.
