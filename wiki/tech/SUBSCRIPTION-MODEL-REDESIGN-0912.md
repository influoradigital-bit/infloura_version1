# Subscription Model — Target Design & Remediation Plan

**Owner:** Priya (CTO) · **Date:** 2026-09-12 · **Status:** DRAFT — 3 decisions open (§7)
**Supersedes nothing.** Builds on `wiki/tech/SUBSCRIPTION-BILLING-PLAN.md` (the original build plan),
which is still accurate about the money path and stale about what actually shipped.

---

## 0. Read this before the rest (added 2026-09-12, after production data)

**The subscription product has no entry point in the brand navigation, and consequently has never
been used by anyone.** Production confirms it:

| Query | Result |
|---|---|
| `SELECT type, COUNT(*) FROM workspaces GROUP BY type` | 27 BRAND, 4 AGENCY |
| workspaces with **no** `subscriptions` row | **31 of 31 — all of them** |

`GET /billing/plan` calls `getOrCreateFreeSubscription`, which lazily inserts a Free row on the
first call. Zero rows across 31 workspaces therefore means **that endpoint has never completed
successfully for anyone.** No brand has ever seen the plan comparison, the usage meters, or the
"Upgrade to Pro" CTA. Zero Pro conversions is not a pricing outcome; it is an arithmetic one.

The cause is in `src/components/brand/brand-layout.tsx:119-147`. The sidebar carries twelve
destinations — Home, Meera, Campaigns, Creators, Deals, Pipeline, Wallet, Contracts, Analytics,
Reviews, Disputes, How it works. **Billing is not among them.** The single route to
`/brand/settings/billing` is one `navigate()` button at `src/pages/brand-settings.tsx:838`, buried
deep inside the settings page.

**This outranks everything else in this document.** The caps, the gates, the conformance harness and
the `<UpgradeGate>` work in §3-§5 are all correct and all worth doing — but they compete for a
conversion funnel whose first step does not exist. Adding a Billing entry to that sidebar array is
a one-line change and is the highest-leverage item on this page.

> **Do not generalise this to the saved-creator finding.** Those are different diagnoses.
> `/brand/discover` **is** in the sidebar ("Creators", line 124), so zero saved creators across 27
> brands is genuine non-use of a reachable feature. Billing's zero is unreachability. Only one of
> them is fixed by a nav link.

---

## 1. Diagnosis (one line)

The money path is sound. The **entitlement** path is not a system — it is four hand-written
mechanisms and one pricing claim with no mechanism at all — and nothing in the build fails when a
tier promise ships unenforced.

Evidence, all verified by reading the code on `feat/meera-creator-phase-e`:

| Tier promise | Mechanism | Status |
|---|---|---|
| Seats (1 / 5) | hand-written `enforceSeatLimit()` in `WorkspaceMemberService:412` | enforced |
| Creator analytics (1 / unlimited) | bespoke `AnalyticsUsageCapInterceptor` bound to one URL pattern | enforced |
| AI credits (100→150 / 400) | `AICreditService` + monthly job | enforced |
| Export, Campaign templates | declarative `@RequiresPlan` + interceptor | enforced |
| Brand fee (10% / 7%) | `BrandCampaignFeeService.resolveBrandFeeBps()` | enforced |
| **Saved creators (5 / unlimited)** | **none** | **sold, metered, never enforced** |

`UsageMetric.TRACKED_CREATOR` is read in `BillingController.getUsage()` and incremented **nowhere**
in `src/main`. `CreatorDiscoveryService.toggleSaved():433` saves with no plan check. The claim is
live in three places: `pricing.tsx:236`, the in-app upgrade copy at
`brand-billing-settings.tsx:636`, and the `/billing/usage` response.

`SUBSCRIPTION-BILLING-PLAN.md` §2 marked this row **"*(new gate)*"** — it was correctly identified
as net-new work and then never built. Every other new gate in that table was.

### Why this matters more than it looks

§2's own breakeven math: Free pays `0.10x`, Pro pays `0.07x + 4999`, meeting at **₹1,66,633/month
of published budget**. Below that, Pro costs *more* than Free. So for the majority of brands the
subscription's entire value proposition is **the four caps** — and one of the four does not exist.

---

## 2. Principle

> A plan limit that exists as data must be impossible to ship without enforcement.

Today adding a column to `plans` costs nothing and buys a marketing claim. That asymmetry is the
root cause. The target state inverts it: adding an entitlement makes a test fail until it is
enforced at a real HTTP boundary.

---

## 3. Target architecture

### 3.1 One closed registry

Replace seven ad-hoc `Plan` getters with an enum that names both the limit's **shape** and the
**endpoint that must enforce it**:

```java
public enum Entitlement {
    SEATS                    (Shape.CAPACITY, "POST", "/workspace/members/invite"),
    SAVED_CREATORS           (Shape.CAPACITY, "POST", "/creators/{id}/save"),
    CREATOR_ANALYTICS_VIEWS  (Shape.METERED,  "GET",  "/analytics/creators/{id}/metrics"),
    AI_CREDITS               (Shape.METERED,  "POST", "/meera/chat"),
    EXPORT                   (Shape.FLAG,     "GET",  "/campaigns/{id}/export"),
    CAMPAIGN_TEMPLATES       (Shape.FLAG,     "POST", "/campaign-templates"),
    BRAND_FEE_BPS            (Shape.RATE,     null,   null);   // money path, not a gate
}
```

Resolution collapses to **one** place — `OptionalInt`, empty meaning unlimited — instead of
`getSeatLimit()`, `getTrackedCreatorLimit()`, `getCreatorAnalyticsMonthlyLimit()`, and friends.
The columns stay; only the read surface collapses.

> **Amended 2026-09-12 after implementation — the accessor lives on `Entitlement`, not on `Plan`.**
> This section originally specified `Plan.limitFor(Entitlement)`. That is wrong for this codebase and
> the implementer was right to move it: both production call sites are driven in their existing unit
> tests by `mock(Plan.class)` stubbing the per-field getters, so a `plan.limitFor(e)` call from
> production would be **intercepted by the mock and return null** — the resolution would never run,
> and the tests would still pass. An enum instance method cannot be stubbed, so `Entitlement.limitIn(Plan)`
> genuinely executes and genuinely reads the stubbed getters. Same logic, different owner, and the
> difference is the gap between a test that proves something and one that cannot.
>
> **CTO ruling: ratified. `Plan.limitFor` is now a delegate with zero callers in `src/main` — delete it.**
> A dead public accessor that the design doc calls "the read surface" is precisely how the next
> engineer reintroduces the split we just closed.

### 3.2 Three primitives, one service

`EntitlementService` — the only thing any caller touches:

| Shape | Primitive | Generalizes what exists today |
|---|---|---|
| FLAG | `@RequiresPlan(E)` (unchanged) | `PlanGateInterceptor` — already correct, keep it |
| METERED | `consume(workspaceId, E, dedupKey)` | `UsageCounterService.recordCreatorLookup` — already correct, rename and widen |
| CAPACITY | `requireCapacity(workspaceId, E, LongSupplier currentCount)` | `WorkspaceMemberService.enforceSeatLimit` — extract verbatim, it is already the right shape |
| RATE | `resolveRate(workspaceId, E)` | `BrandCampaignFeeService` — already correct, keep it |

**Note that nothing here is invented.** Three of the four primitives already exist and work; this
is extraction, not redesign. `SAVED_CREATORS` needs only `requireCapacity` — one call inside
`toggleSaved`, gating the *write*.

Gating the write also gives soft grandfathering for free: a brand already holding 9 saves keeps all
9 and is blocked at the 10th. No migration, no one loses access to data they already have.

### 3.3 One structured 402

Every primitive throws the same exception with a machine-readable body:

```json
{ "code": "UPGRADE_REQUIRED",
  "details": { "entitlement": "SAVED_CREATORS", "limit": 5, "used": 5,
               "planCode": "FREE", "upgradeTo": "PRO" } }
```

This is what turns four bespoke frontend error handlers into one `<UpgradeGate>` component. See §5.

---

## 4. The gate that makes it stick

`EntitlementConformanceTest` — parameterised over `Entitlement.values()`. For each non-RATE value it:

1. provisions a Free workspace,
2. drives the declared endpoint `limit` times → expects 2xx,
3. drives it once more → **expects 402 with the structured body above**.

Properties this has that the current setup does not:

- **An entitlement with no probe does not compile.** The enum constructor requires it.
- **An entitlement with a probe but no enforcement fails the test.** This is exactly the hole
  `SAVED_CREATORS` fell through.
- **MockMvc, not Testcontainers.** A Testcontainers-only suite silently reports `Skipped: 15` with
  `BUILD SUCCESS` on this machine — a conformance gate that can skip is not a gate.

### Falsification protocol (non-negotiable)

Per the two gates already burned in this repo on exactly this pattern:

- Land the test against the **three entitlements that already work** first (Phase 1). A green
  result then means the harness works, not that it is vacuous.
- Then, for **every** entitlement independently — not just the first one — delete its enforcement
  call and confirm the test goes red. A gate written to close a one-caller blind spot has shipped
  with the same hole here before.
- Run `mvn` with cwd inside `influora-api/` (no aggregator pom), unpiped (a piped `mvn | grep`
  reports grep's exit code and a failed suite arrives as exit 0).

---

## 5. The conversion layer (currently 1 of 4)

Hitting a cap is the single highest-intent moment in a subscription product. Today:

- `useCreatorMetrics.ts:34` is the **only** place that recognises `UPGRADE_REQUIRED` — and it just
  swaps the error *string*. No button, no route to checkout.
- Seat-limit, export, and template 402s have no handler at all; they surface as a generic toast.

Target: one `<UpgradeGate>` driven by the structured `details` body — names the limit, shows
`used/limit`, and its primary action is `api.billing.initiateCheckout('PRO')`, the same call the
billing page already makes at `brand-billing-settings.tsx:262`. Role-aware: a MANAGER/MEMBER who
cannot buy sees "ask your workspace owner", reusing `useBrandBillingAccess`.

Four surfaces, one component, zero new backend work once §3.3 lands.

---

## 6. Correctness fixes folded into the same pass

| # | Defect | Fix |
|---|---|---|
| F-2 | `exportsUsed` is structurally always 0 — `ReportExportController` never increments it | Delete `EXPORT` from `UsageMetric` and from `UsageSummaryResponse`. `tsc` then catches the frontend reader. Export is a FLAG, not a meter — a counter between 0 and unlimited means nothing. |
| F-3 | Downgrade wipes the earned loyalty bonus: `applyPlanAllotment` overwrites the same column `applyEscrowFundedReset` wrote 150 into | Split the overloaded column. `planAllotment` (plan sync only) + `loyaltyBonus` (earned, sticky) → `monthlyAllotment` becomes **derived**, never written by two owners. |
| F-4 | `GET /billing/plan` is the only Free-row provisioning path, so a brand who never opens billing settings has no subscription row — and `UsageCounterService.resolvePeriodStart` silently falls back to calendar-month instead of the billing period | Provision the Free row inside the workspace-creation transaction. Keep the lazy path as backfill + one-time migration. Two different period anchors depending on whether someone clicked a settings page is not acceptable for a metered tier. |
| F-5 | `RazorpayProperties.isConfigured()` returns true for the committed `rzp_test_REPLACE_WITH_YOUR_KEY` placeholder | Blank defaults, matching the convention the Meta block in the same `application.yml:401` already documents and gets right. |

---

## 7. Decisions

**SM-0.1 — RULED 2026-09-12 (Swapnil): ENFORCE the 5-saved-creator cap.**
Implement as the soft cap in §3.2 — gate the *write* in `CreatorDiscoveryService.toggleSaved`, so a
workspace already over the limit keeps every creator it has already saved and is refused only at
the next new save. No data is removed from anyone. Unblocks Phase 2 (SM-2.1, SM-2.2).

> **SM-0.4 — CLEARED 2026-09-12.** The over-limit query returned **Empty set**: zero live
> workspaces hold more than 5 saved creators. No grandfathering problem, no pre-enforcement
> notification needed, no customer hits a wall on day one. The cap can ship as a plain soft gate.
>
> **But read the same result the other way.** An empty set is equally consistent with "nobody
> saves many creators" as with "everyone is comfortably under the cap" — and if the real maximum
> is 1 or 2, a limit of 5 constrains nobody and contributes nothing to why a brand would upgrade.
> That does not reopen the ruling (enforce it — it is nearly free and it makes the pricing page
> true), but it does mean Pro may be selling on three effective caps, not four. Worth knowing
> before anyone counts on this row to drive conversion:
> `SELECT COUNT(*) AS ws_with_saves, MAX(c) AS max_saved, ROUND(AVG(c),1) AS avg_saved FROM
> (SELECT workspace_id, COUNT(*) c FROM saved_creators WHERE saved = true GROUP BY workspace_id) t;`

**SM-0.2 — RULED 2026-09-12 (Swapnil): the earned +50 loyalty bonus STACKS on Pro.**
Pro therefore allots 450/month for a workspace that has funded a campaign, 400 for one that has
not. This is exactly why `monthlyAllotment` must become derived rather than overwritten — see F-3
in §6. `planAllotment` is written only by plan sync, `loyaltyBonus` only by
`applyEscrowFundedReset`, and neither may clobber the other. Unblocks SM-3.3.

**ENGAGEMENT-RATE DENOMINATOR — RULED 2026-09-17 (Swapnil): FOLLOWERS, multiplier kept.**
Repairing the dead `CreatorMetric.avgEngagementRate` (F-0815, `e71938c`) had a side effect nobody
had priced: `RateEstimationService` (lines 92-110) applies **+30% above 5% engagement and −30% below
1%** from that field. While it was dead it was always null and the multiplier sat at a neutral 1.0;
populating it switched the multiplier on for every polled creator. `e71938c` used **reach** as the
denominator, which runs several times higher than follower-based rates (7.5% vs 1.5% on the same
test data) and would have pushed most creators over the +30% line.
Ruling: `avgEngagementRate = mean engagement per post / followers × 100`, matching
`QualityScoreService` and `FakeFollowerDetectionService`, whose scale the thresholds were built for.
`followers` null or 0 ⇒ the rate is **null, never 0** — a 0 would trigger the −30% penalty on a
creator we simply have no data for. Not pushed at the time of ruling, so no live estimate moved.

**SM-0.3 — still open. Ratify §6 of the original plan.** Those four items (₹4,999, 7%, the Free caps, no-trial) were
   logged as "pending Swapnil sign-off" and I find no recorded response — but all four are now live
   on the public pricing page and in shipped code. The gate is moot in practice; it should be
   closed as ratified or the numbers should change. *Recommend: ratify.* Changing a live published
   price is worse than the numbers being imperfect.

---

## 8. Sequencing

| Phase | Work | Gate |
|---|---|---|
| 0 | §7 decisions | **SM-0.1 + SM-0.2 RULED 2026-09-12.** SM-0.3 open (housekeeping, blocks nothing). **SM-0.4 over-limit count still outstanding and gates the Phase 2 *ship*, not the build.** |
| 1 | `Entitlement` enum + `EntitlementService` + conformance test, wired to SEATS / CREATOR_ANALYTICS_VIEWS / EXPORT / CAMPAIGN_TEMPLATES only. **No behavior change.** | Test green against known-good code; then falsified per-entitlement |
| 2 | Add `SAVED_CREATORS` — one `requireCapacity` call in `toggleSaved` | Conformance test for that constant goes red→green |
| 3 | F-2 / F-3 / F-4 / F-5 | F-3 needs a migration; F-4 needs a backfill |
| 4 | Structured 402 body + `<UpgradeGate>` across 4 surfaces | Click test, not a screenshot — a styled div passes tsc, eslint, and review |

Phase 1 before Phase 2 is the load-bearing ordering. Building the gate against code that already
works is what distinguishes "the harness functions" from "the harness is vacuously green."

---

## 9. Explicitly out of scope

- Enterprise tier — still deferred, unchanged from the original plan.
- Annual billing / seat add-on SKU — `subscriptions.seats_purchased` exists in V54 and is dead
  today. Leave it dead rather than half-wire it.
- Razorpay Route / per-payment transfers — separate track.
- The 15% creator-side fee — never touched by a brand's plan, by design.
