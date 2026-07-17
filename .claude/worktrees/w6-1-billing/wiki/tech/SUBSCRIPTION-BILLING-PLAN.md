# Subscription Billing — Implementation Plan & Employee Task Loop

> **Owners:** Priya (CTO, architecture) · Arjun (Eng Lead, routing) · Rohan (CFO, economics) · Tejas (market input)
> **Date:** 2026-07-13 (rev. 3 — 2026-07-14: code-audit corrections applied) · **Closes gap A3** from `FEATURE_GAP_ANALYSIS.md`
> **Decisions locked:** Feature-gate layer on top of the existing 10%/15% fee engine (not a replacement) · **Free + Pro only** (Enterprise deferred) · **No time-boxed trial** — Free tier is permanently limited instead · Full engine before launch.

---

## 0.5 CODE-AUDIT VERDICT (Priya, 2026-07-14) — **YELLOW, corrections applied below**

Before opening Task 10's gate, dispatched an 11-agent parallel audit (`Workflow` tool) verifying every "already exists" claim in this plan against the real `influora-api`/frontend code — not against the plan's own wording. Result: **3 of 10 claims fully CONFIRMED, 5 PARTIAL, 2 DIVERGENT.** Two hard factual errors and four load-bearing "reuse" claims that are actually net-new work. This section is the corrected record; the rest of the doc below has been edited in place to match.

**Hard factual errors (fixed throughout this doc):**
- Frontend is **Vite + React 19 + react-router-dom v7** — `next` is not a dependency, `next.config.mjs` is a dead vestigial file. Every reference to "Next.js" below was wrong.
- Next free Flyway migration version is **V54** (highest existing is `V53__disputes_version.sql`, `V40` intentionally skipped) — not V57.

**Confirmed solid — safe to build on directly:**
- Fee engine (`BrandCampaignFeeService.chargeOnPublish`, `PlatformFeeService.deductAtRelease`, `PlatformFeeConfig` basis-points) — live, wired, real. **But it is a single global singleton row (`id='default'`), not per-plan.** No tier/workspace argument exists on either resolve method.
- `BrandAiCredit` / `AICreditService` — exact field names match, 100→150 loyalty bump is real and wired via `ConfirmLaunchExecutor.applyEscrowFundedReset` (fires on **funded launch**, not plain campaign creation). No monthly-cron caller exists yet for `resetForNewCycle`.
- `AnalyticsController` (`/analytics/creators/*`) vs `CampaignController./campaigns/{campaignId}/analytics` — both exist as described, but note: campaign analytics is **brand-workspace-scoped**, not creator "own-campaign," and a third undocumented controller `CreatorAnalyticsController` (`/creator/analytics/me`) mirrors the deep-dive reads for creators — any gate must cover it too or it slips through.
- `ContractPdfService` (pure OpenPDF, `com.lowagie.text.*`) — real, reusable pattern; helpers are `private static` so they'll need duplicating or extracting, not extending.
- `AdminAuditLog` (not `AuditLogEntry` — that's a different, unrelated AI/money-movement trail) — production-ready primitive for comp/override logging.

**Net-new work the plan currently mis-scopes as "reuse" (re-estimate before committing dates):**
1. **No per-plan fee override seam.** Building §1.3's "active Pro subscription → `Plan.feeBps`" requires a new column/table plus a plan-aware `resolveBrandFeeBps(workspaceId)` signature — today it's a single admin-only global row.
2. **No seat gating exists at all.** `WorkspaceMember`/`MemberRole`/`is_active` are real, but there is zero count-vs-limit code, and **no invite/add-member write path exists anywhere** — `AuthService.java:148`'s owner-at-signup call is the *only* place a `WorkspaceMember` row is ever created. Task 11/14 must build the member-add flow itself, not just gate an existing one.
3. **Razorpay Subscriptions is unbuilt.** SDK 1.4.6, `WebhookSignatureVerifier`, `IdempotencyKeyRecord`/`IdempotencyService` are real and reusable, but `RazorpayClient` only wraps `orders.create`/`orders.fetch` (no `plans.create`/`subscriptions.create`), no `Subscription` domain entity exists yet, and `RazorpayWebhookController`'s switch statement **silently 200s `subscription.*` events today** (falls into the default arm, no action taken). Also: `RazorpayProperties.java`'s javadoc is stale ("no SDK dependency yet") — fix while touching the file.
4. **§1.6 "time saved" stat's data source is wrong as specified.** `MeeraToolCall` only logs `create_campaign` / `request_payment` / `confirm_launch` (single `created_at`, no duration). **"Creators found" lives in the separate `audit_log` table via `AuditLogService`/`ShowCreatorsExecutor`, not `MeeraToolCall`.** **"Briefs drafted" has no corresponding tool/event anywhere** — nearest real event is `create_campaign`. "Time saved" can only be a hardcoded per-tool constant × count (no elapsed-time data exists), and the per-month aggregation is a genuinely new query, not a reuse of an existing one. **Rescope §1.6 to the 3 tools actually in the ledger**, or add tracking for the missing events as separate small tasks.
5. **No admin billing console exists.** Only `PlatformFeeAdminController` (fee-config CRUD) exists today; the broader finance API (`getRevenue`/`getEscrowSummary`/`getPayoutQueue`) is explicitly Phase-2/mock per its own javadoc. `AdminAuditLog`'s `BUDGET_OVERRIDE`/`TIER_ADJUST`/`PAYOUT_RETRY`/`WALLET` actions are declared in the allow-list but have **zero call sites** — Task 19 must wire these, not just call them.

**Routing decision:** safe to start now, no Swapnil §6 dependency: AI-credit/loyalty extension work, invoice-PDF service (mirrors `ContractPdfService`), analytics-gate implementation (covering the 3rd controller), and claiming Flyway `V54`. **Still correctly blocked** on Swapnil's §6 price/fee/cap/no-trial sign-off: anything touching `Plan.feeBps`, seat limits, or the tier matrix itself, since building those before the numbers are locked risks throwaway work.

Full 10-finding audit trail: `Workflow` run `wf_79b72587-f93` (11 agents, 601,939 tokens, 119 tool calls, 0 errors).

---

## 0. CTO/CFO alignment note (read first)

**The 10% brand / 15% creator take-rate is already live, CEO-approved (`wiki/decisions/admin-pending-tasks-directive.md` item #5, 2026-07-09), and fully coded** — `BrandCampaignFeeService.chargeOnPublish` (brand, at campaign publish) and `PlatformFeeService.deductAtRelease` (creator, at escrow release). **Subscriptions do NOT replace this.** Pro is a second, optional layer: a lower brand-side rate + feature unlocks, paid for with a monthly fee instead of a discount code.

The **live pricing page still markets "No monthly subscription — pay only when a deal closes"** (`src/pages/pricing.tsx`). That copy must be rewritten in the same release (Task 18) — Free tier keeps that promise (still true: no subscription required), Pro is the new optional add-on.

**Strategy call (locked this round):** no 14-day trial. A trial just delays the upgrade decision. Instead, **Free tier is permanently, usefully limited** — enough to run the business, capped enough that a growing brand feels it every month. The cap re-applies every cycle, so the upgrade pressure is recurring, not a one-time expiring offer.

**Existing hooks this reuses (audit-verified 2026-07-14 — "Reuses" column corrected where reuse was overstated):**
| We need | Already exists | How it maps | Audit verdict |
|---|---|---|---|---|
| Per-plan reduced take-rate | `PlatformFeeConfig` — basis points, bounded `minFeeBps=0`/`maxFeeBps=3000`, admin-editable without redeploy | `Plan.feeBps` override; brand-fee resolution reads plan first, falls back to the global 1000 bps | **PARTIAL** — config primitive is real, but it's a single global singleton row today. The plan-aware `resolve*(workspaceId)` override is **net-new**, not a rewire. |
| Recurring AI credits | `BrandAiCredit` (`monthlyAllotment`, `cycleStart`, `lastReset`, `dailyActionsUsed` cap) | Free = 100→150 (unchanged, existing loyalty bump); Pro = 400/mo | **CONFIRMED** — exact fields match, bump is live via `ConfirmLaunchExecutor` (fires on funded launch, not creation). No monthly-reset cron caller yet. |
| Seats | `WorkspaceMember` (`role`, `active`) | Plan `seatLimit` gates active-member count | **PARTIAL** — entity real, but **zero** count-vs-limit code exists, and **no invite/add-member write path exists at all** (owner-at-signup is the only writer). Build the member-add flow, not just the gate. |
| Analytics gating | `AnalyticsController` (creator-level deep-dive: metrics/scores/demographics/media) vs `CampaignController./campaigns/{campaignId}/analytics` (brand-workspace-scoped, creator-reported) | **Campaign analytics stays free on both tiers** (they already paid the publish fee); **creator deep-dive analytics is the gate** | **CONFIRMED**, with one addition: a third controller `CreatorAnalyticsController` (`/creator/analytics/me`) mirrors the same deep-dive reads for creators and must be covered by the gate too, or it slips through. |
| Payments + webhooks | `razorpay-java 1.4.6`, `WebhookSignatureVerifier`, `IdempotencyKeyRecord` | Razorpay Subscriptions API + signed webhooks | **PARTIAL** — SDK/verifier/idempotency are real and reusable, but `RazorpayClient` only wraps `orders.*` today; no `plans.create`/`subscriptions.create` wrapper, no `Subscription` entity, and the webhook controller **silently 200s `subscription.*` events** right now (falls to default arm). |
| PDF generation | `ContractPdfService` (OpenPDF) | Reuse pattern for invoices | **CONFIRMED** — real, reusable pattern; private-static helpers need duplicating or extracting for the invoice service. |
| AI action logging | `MeeraToolCall` | Feeds the new "time saved" stat (§1.6) — no new tracking needed | **DIVERGENT** — only 3 tools logged (no "briefs drafted" event anywhere; "creators found" is in a *different* table, `audit_log`); no duration data, so "time saved" is a hardcoded constant, not a measured value. See §1.6 rescope. |

---

## 1. Architecture (Priya)

### 1.1 New entities (Vikram, Task 11)
- **`Plan`** — `code` (FREE/PRO), `name`, `priceInr`, `billingCycle`, `razorpayPlanId`, `feeBps` (brand-side override), `aiMonthlyAllotment`, `seatLimit`, `trackedCreatorLimit`, `creatorAnalyticsMonthlyLimit` (null = unlimited), `exportEnabled`, `campaignTemplatesEnabled`, `active`.
- **`Subscription`** — `workspaceId`, `planId`, `status` (ACTIVE/PAST_DUE/HALTED/CANCELLED — **no TRIALING state**, since there's no trial), `razorpaySubscriptionId`, `currentPeriodStart/End`, `cancelAtPeriodEnd`, `seatsPurchased`.
- **`Invoice`** — `subscriptionId`, `workspaceId`, `razorpayInvoiceId`, `amount`, `status`, `periodStart/End`, `pdfUrl`, `issuedAt`, `paidAt`.
- **`UsageCounter`** — `workspaceId`, `metric` (`TRACKED_CREATOR`, `EXPORT`, `CREATOR_ANALYTICS_VIEW`), `periodStart`, `used`.
- Migrations: next sequential **`V54`** (audit-corrected 2026-07-14; was misstated as V57 — highest existing is `V53__disputes_version.sql`, `V40` intentionally skipped). Flyway auto-runs on boot (`spring.flyway.enabled=true`, confirmed).

### 1.2 Integration (Vikram, Tasks 12–13)
- `RazorpaySubscriptionClient`: create plan (Free needs none — only Pro has a `razorpayPlanId`), create subscription (hosted checkout), fetch invoice.
- Webhook handler: `subscription.activated | charged | pending | halted | cancelled`, `invoice.paid` — signature-verified + idempotent.

### 1.3 Fee + gating (Vikram + Kabir, Tasks 14–15)
- Brand-fee resolution: active Pro subscription → `Plan.feeBps` (7%); else the existing global `PlatformFeeConfig.brandFeeBps` (10%). **Creator's 15% is never touched by the brand's plan.** **Audit correction (2026-07-14): no per-plan override seam exists today** — `BrandCampaignFeeService.resolveBrandFeeBps()` takes no workspace/plan argument and reads a single global singleton row. This task must add a plan-aware resolve signature, not flip a config value.
- `PlanGateFilter` + `@RequiresPlan` after `jwtFilter`. Over-limit → `402/403 UPGRADE_REQUIRED`.
- Gates enforced: seats, tracked/saved creators, **creator-analytics-deep-dive views/month**, export, campaign templates.

### 1.4 Analytics gate — specific implementation note
`AnalyticsController` (creator metrics/scores/demographics/media — the vetting deep-dive) gets a `UsageCounter` check per creator-view per calendar month: Free = 1, Pro = unlimited. `CampaignController./{campaignId}/analytics` (their own campaign performance) is **never gated** — it's tied to a campaign they already paid the publish fee on, gating it would feel punitive and hurts trust, not conversion.

### 1.5 Frontend (Ananya, Tasks 17–18)
- `/brand/settings/billing`: plan card (Free vs Pro), Razorpay checkout for Pro, invoices, seats, usage meters (creators tracked, analytics views left this month, AI credits left).
- Plan-gated UI + upgrade CTAs at the exact moment a limit is hit (analytics wall, 6th tracked creator, export button, template picker).
- **Rewrite `pricing.tsx` + FAQ** (Nisha copy) — two tiers only, no trial language, no Enterprise row for now. **Audit correction (2026-07-14): this is a Vite + React 19 app (`react-router-dom` v7), not Next.js** — edit `pricing.tsx` as a Vite/React shadcn/lucide TSX component. The "no monthly subscription" promise is not one string — it's spread across `pricing.tsx` (hero H1, hero body, FAQ, CTA, SEO description) **and duplicated in `landing.tsx`, `how-it-works-brands.tsx`, and `public/llms.txt`** — update all four together or the messaging goes inconsistent. Keep fee framing number-free per `wiki/website/CEO-DECISIONS.md`.

### 1.6 "Time saved" value nudge (new, small, Ananya + Ash)
Derive a simple monthly stat from existing `MeeraToolCall` counts (no new backend tracking) — e.g. *"Meera found 12 creators and drafted 2 campaign briefs this month — an estimated 6 hours saved."* Surface it (a) on the brand dashboard and (b) at the exact paywall moment (analytics cap hit, tracked-creator cap hit) so the upgrade prompt reads as a receipt of value already delivered, not a cold ask. Cheap to build; reuses data that already exists.

**Audit correction (2026-07-14) — rescope before building:** `MeeraToolCall` only logs 3 tools (`create_campaign`, `request_payment`, `confirm_launch`) with a single `created_at` timestamp, no duration. "Creators found" is **not** in this table — `ShowCreatorsExecutor` logs to the separate `audit_log` table via `AuditLogService`. "Briefs drafted" **has no corresponding event anywhere** in the codebase. Concretely: (a) rewrite the copy to something like *"Meera created 2 campaigns and processed 1 payment this month"* sourced from the 3 real tools, or (b) add new tracking for creators-found/briefs-drafted as a separate small task before promising that copy. "Time saved" must be a hardcoded per-tool-type constant multiplied by count — there is no measured elapsed time to derive it from. The per-month aggregation query is new work, not reuse.

### 1.7 Lifecycle & admin (Tasks 16, 19)
- `SubscriptionDunningJob` (retry → PAST_DUE grace → HALTED), `RenewalResetJob` (reset AI credits + usage counters each cycle), invoice PDF, billing emails.
- Admin billing console: subscriptions list, MRR/ARR/churn, comps/overrides — all to `AdminAuditLog`. (Enterprise provisioning UI deferred with the tier itself.) **Audit correction (2026-07-14): no admin billing console exists today** — only `PlatformFeeAdminController` (fee-config CRUD) is built; the broader finance API is Phase-2/mock per its own javadoc. `AdminAuditLog`'s `BUDGET_OVERRIDE`/`TIER_ADJUST`/`PAYOUT_RETRY`/`WALLET` actions are declared in the allow-list but have zero call sites — this task must wire real writers for them, not assume they're already firing.

---

## 2. Tier matrix (Rohan's proposal — pending Swapnil sign-off on the 4 items in §6)

| Capability | **Free** | **Pro** — ₹4,999/mo, no trial |
|---|---|---|
| Brand fee | 10% (unchanged, live today) | **7%** |
| Creator fee | 15% (unchanged — never discounted by the brand's plan) | 15% |
| Seats (`WorkspaceMember`) | 1 | 5 |
| Tracked/saved creators | 5 *(new gate)* | Unlimited |
| AI credits/mo (`BrandAiCredit`) | 100 → 150 after first funded campaign (existing, unchanged) | 400 (~₹90 real cost — trivial vs price) |
| Campaign analytics (own campaigns) | ✓ always included | ✓ |
| **Creator deep-dive analytics** (vetting) | **1 view/month** | **Unlimited** |
| Report export (CSV/PDF) | ✗ | ✓ |
| Campaign templates | ✗ | ✓ |
| Trial | — none — | — none — |

**Enterprise: deferred.** Not in this build. Revisit once Pro has real usage data.

### Why 7% and ₹4,999 (breakeven shown, not hidden)
At ₹1,50,000/month campaign spend, Pro (₹10,500 fee + ₹4,999 price = ₹15,499) costs slightly *more* than Free (₹15,000) — intentional; Pro isn't meant to win on fee math alone at that size. **Breakeven ≈ ₹2,10,000/month spend.** Above that, the 3-point fee cut outweighs the subscription price — squarely inside the "growth tier" brand range (₹4–10L/month, per market data). Below breakeven, the analytics/export/seat/AI limits are the reason to upgrade, not the fee.

Sources on Indian campaign-spend bands: [upGrowth 2026](https://upgrowth.in/influencer-marketing-pricing-india-2026/), [IdentityKit 2026](https://www.identitykit.in/blog/influencer-marketing-cost-india-2026).

---

## 3. Employee task list (Arjun routing)

| # | Task | Owner | Blocked by | Audit-corrected effort (2026-07-14) |
|---|---|---|---|---|
| 10 | Lock spec + gating matrix + API contract (P0 GATE) | **Priya + Rohan** | — | Spec now corrected (§0.5); **still open pending Swapnil §6 sign-off** |
| 11 | Entities + Flyway migrations (Plan/Subscription/Invoice/UsageCounter) | Vikram | 10 | Migration slot is **V54** (not V57); `Subscription` entity is genuinely net-new (confirmed, no prior art) |
| 12 | Razorpay subscription client + checkout | Vikram | 10 | **Larger than scoped** — `RazorpayClient` today only wraps `orders.*`; `plans.create`/`subscriptions.create` wrappers are net-new |
| 13 | Subscription webhook + idempotency | Vikram | 11, 12 | `WebhookSignatureVerifier`/`IdempotencyService` reuse holds; the switch statement's `subscription.*` handling is net-new (currently silently 200s) |
| 14 | Fee override (7% Pro) + AI credits + seat enforcement | Vikram + Kabir | 11 | **Larger than scoped** — no per-plan fee seam, no seat-count code, **no member-add flow exists at all** (build from scratch, not gate an existing one) |
| 15 | Plan-gate filter + `@RequiresPlan` (incl. analytics 1/mo counter) | Vikram | 11 | Confirmed-safe target controllers; **must also cover `CreatorAnalyticsController` (`/creator/analytics/me`)**, which the original plan omitted |
| 16 | Dunning + renewal jobs + invoice PDF + emails | Vikram + Meera | 13 | Invoice PDF: confirmed-safe, mirror `ContractPdfService` directly |
| 17 | Billing settings page + checkout | Ananya | 10 | No corrections — build as scoped |
| 18 | Plan-gated UI + pricing/FAQ rewrite + time-saved nudge | Ananya + Nisha + Ash | 17 | Vite/React not Next.js; copy spans 4 files not 1 (§1.5); time-saved stat needs rescoping to 3 real tools (§1.6) |
| 19 | Admin billing console (MRR/comps, no Enterprise UI) | Ananya + Vikram | 13 | **Larger than scoped** — no billing console backend exists at all; `AdminAuditLog` comp/override actions are declared but unwired (zero writers) |
| 20 | VERIFY LOOP → sign-off | Kavya / Meera / Kabir / Priya | 13–19 | No change |

**Safe to start now (no Swapnil §6 dependency, audit-confirmed):** AI-credit/loyalty extension work (part of 14), invoice-PDF service (part of 16), analytics-gate implementation including the 3rd controller (15), claiming the `V54` migration slot (11). **Everything touching `Plan.feeBps`, seat limits, or the tier matrix itself stays blocked on Swapnil's §6 sign-off** — building those before the numbers are locked risks throwaway work once prices/caps change.

Tasks 11/12/17 start **in parallel** the moment Task 10 is signed off — but Task 10 itself is not yet signed off (§6 still open).

---

## 4. The workflow loop (unchanged)

```
Arjun reads TASK_INBOX → routes task
        │
        ▼
Priya signs the CONTRACT/spec (once, Task 10) ── gate ──┐
        │                                                │
        ▼                                                │
Vikram (backend) / Ananya (frontend) build ◄─────────┐  │
        │                                             │  │
        ▼                                             │  │
Kavya — QA review (standards, bugs, TECH-STACK)  ──fail──┘
        │ pass                                        ▲
        ▼                                             │
Meera — local verify: mvn verify + Testcontainers  ──fail──┘
        + Playwright checkout e2e + FE build
        │ pass
        ▼
Kabir — security audit (billing/escrow touch)  ──fail──► back to owner
        │ pass
        ▼
Priya — final sign-off → merge → archive thread to wiki/
```
Any red routes straight back to the owning agent; nothing is "done" until Priya signs. Rohan validates economics on 14/17/18.

---

## 5. Acceptance criteria (Task 20 checklist)

- [ ] A workspace can subscribe to Pro via Razorpay hosted checkout; `Subscription` → ACTIVE on `subscription.charged`. No trial state exists anywhere in the flow.
- [ ] Subscribed workspace's campaign-publish fee charges **7%**, unsubscribed charges the existing **10%** — creator's 15% is identical either way.
- [ ] Free tier: 6th tracked creator, 2nd creator-analytics view in a month, export button, campaign template picker all return `UPGRADE_REQUIRED` with a clear upgrade CTA.
- [ ] Own-campaign analytics (`/campaigns/{id}/analytics`) works identically on both tiers — never gated.
- [ ] AI credits: Free 100→150 unchanged; Pro 400/mo; both reset correctly on `RenewalResetJob`.
- [ ] "Time saved" stat renders on the dashboard and at the paywall moment, computed from existing `MeeraToolCall` data.
- [ ] Failed payment → PAST_DUE grace → HALTED (dunning); soft-lock behaves correctly.
- [ ] Invoice PDF generates + downloads; billing emails fire.
- [ ] Admin console shows MRR + can comp/override a workspace's plan; logged to `AdminAuditLog`.
- [ ] `pricing.tsx` + FAQ rewritten — two tiers, no trial copy, no Enterprise row.
- [ ] Webhooks signature-verified + idempotent; no card data touches our servers (Razorpay hosted only).
- [ ] `mvn verify` green + Playwright checkout e2e green + frontend build green.

---

## 6. Decisions still needed from Swapnil (Task 10 gate)

1. **Confirm ₹4,999/month Pro price** — or adjust.
2. **Confirm 7% Pro brand fee** (vs. 10% Free) — creator's 15% proposed unchanged.
3. **Confirm the new Free-tier caps**: 5 tracked creators, 1 creator-analytics view/month. These don't exist in the code today — they're new limits being introduced.
4. **Confirm no-trial strategy** — permanently-limited Free instead of a time-boxed Pro trial.

Once 1–4 are locked, Task 10 closes and the parallel build (11/12/17) starts immediately.

**Status (2026-07-14):** items 1-4 are still `[ ]` unchecked — no Swapnil response found in `SHARED_CONTEXT.md` since this gate opened. The plan's own architecture is now code-audit-corrected (§0.5) and ready to build against the moment 1-4 land; the corrections do not change what needs deciding, only what it costs to build once decided (see the "Audit-corrected effort" column in §3 — items 12/14/19 in particular are larger than originally scoped). Non-blocked prep work (V54 migration slot, invoice-PDF service, analytics-gate on the 3rd controller, AI-credit extension) can start now per §0.5's routing without waiting on 1-4.
