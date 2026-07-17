# Incomplete Code Audit — 2026-07-14

> **Author:** Priya (CTO), via Arjun routing · **Trigger:** "lots of feature are missing / not complete code"
> **Method:** 7-lens parallel code sweep (fake UI handlers, mock-only pages, FE↔BE contract divergence, backend dropped-data/dead-events, explicit incompleteness markers, Python AI service, orphaned/dead code). Every finding below is backed by a file:line citation an agent actually read — nothing here is inferred from docs or prior trackers.

## CTO verdict

The user's instinct is correct, but the shape of the problem is specific, not diffuse: **the brand-facing core workspace (wallet, campaigns, contracts, deal inbox, creator profiles) is substantially mock-only**, **the notification/event system is ~85% dead** (built on both ends, never wired to fire), and **the admin panel has ~20 phantom endpoints** because its API client sits outside the one automated guardrail that would have caught this (`src/lib/__tests__/api-contract.test.ts` only scans `src/lib/api.ts`, not `src/admin/services/api-contracts.ts`).

The good news, also verified: this is not universal rot. The creator-side vertical (deals, wallet withdraw, discovery, profile edit) and the Python AI service are both genuinely well-wired. Most "incompleteness markers" in the codebase are *honest*, deliberate gaps (amber "not built yet" banners, disabled buttons with tooltips) — the dangerous ones are the smaller set below that **look fully functional but silently do nothing or show fabricated data.**

**Rough scale:** 1 P0, ~19 P1, ~20 P2, ~15 P3 across frontend, backend, and admin. Not every P1/P2 needs to be fixed before anything ships — but the P0 and the money-adjacent P1s should not go live as-is.

---

## P0 — actively misleading on the money path, fix first

| # | Finding | File | Fix effort |
|---|---|---|---|
| 1 | **Brand Wallet page renders entirely fake money data** — hardcoded ₹2,85,000 balance, ₹4,50,000 escrow, fake transactions, fake TDS/GST totals. `WalletController` backend already exists; page never imports `@/lib/api`. | `src/pages/brand-wallet.tsx:108-233` | M |

---

## P1 — core feature non-functional or silently misleading

### Fake success on real actions (frontend)
| Finding | File:line | Needs decision? |
|---|---|---|
| "Invite creator to campaign" — `setTimeout` fake, no API call, no notification, fabricated campaign dropdown | `src/pages/brand-creator-profile.tsx:200-211` | No |
| Payout method management (creator wallet) — hardcoded UPI/bank shown as the creator's own; Set Primary / Add New Method have no handler | `src/pages/creator-wallet.tsx:485-525` | No |
| "Delete Account" claims permanent server deletion, only logs out locally | `src/pages/creator-settings.tsx:144-150` | **Yes** — compliance/GDPR-style expectation |

### Entire brand workspace pages are hardcoded mocks (backend already exists for each)
| Page | Mock const | Backend that already exists |
|---|---|---|
| Brand deal-room inbox list | `mockDealRooms` | `DealController` |
| Brand campaigns list | `mockCampaigns` | `CampaignController` |
| Brand contracts & deliverables | `mockContracts` | `ContractController` |
| Brand campaign detail (`:id` ignored) | `mockActiveCampaign` etc. | `CampaignController`/`DealController` |
| Brand-side creator profile (`:id` ignored) | `mockCreator` | `CreatorController` |

Files: `src/pages/brand-chat.tsx:107`, `src/components/brand/campaigns/campaigns-list.tsx:54`, `src/components/brand/contracts/contracts-and-deliverables.tsx:107`, `src/pages/brand-campaign-detail.tsx:40`, `src/pages/brand-creator-profile.tsx:55`.

**This is the single biggest finding of the audit:** a brand using this product today cannot see their real deals, campaigns, contracts, or the creator they actually clicked into — every one of these core screens shows the same canned data to every brand, regardless of account. None of these are "missing feature" — the backend for all five already exists; this is pure wiring debt, likely from a UI-first build order that was never closed out.

### Notification/event system — ~85% dead
**`NotificationListener.java`** has a fully-coded `@EventListener` for all 28 event types in the spec. Only **4 are ever published**: `CampaignCreatedEvent`, `ContractReadyForEscrowEvent`, `ContractSignedEvent`, `PortfolioContactEvent`. The other 24 — including **`PayoutReleasedEvent`** (money path), `BidAcceptedEvent`, `ApplicationCreatedEvent`, `EscrowFundedEvent`, `ContractPendingSignatureEvent`, `KycApprovedEvent`/`KycRejectedEvent`, `WalletLowBalanceEvent` — have zero `new XxxEvent(` call sites anywhere. (`AuthOtpEvent`/`PasswordResetEvent` are covered by an alternate direct-email path, so those two still work.)
**File:** `influora-api/.../service/notification/NotificationListener.java:76-620`. **Effort:** L (systemic — needs a pass through every service method that should publish one of these).

### Creator KYC — validated then silently discarded
`submitCreatorKyc()` accepts PAN, Aadhaar-last-4, and a selfie URL (all validated), logs "deferred implementation," and returns `PENDING` **without storing anything.** Creators believe they're verified; withdrawal is gated on KYC that was never actually recorded.
**File:** `influora-api/.../service/OnboardingService.java:283-295`. **Needs decision:** yes (where/how to store PII — schema design).

### Admin panel — five entire API namespaces are phantom (no backing controller at all)
`financeApi`, `escrowApi`, `errorApi`, `emailApi`, `marketingApi` in `src/admin/services/api-contracts.ts` each call multiple endpoints with **no matching Spring controller anywhere in the codebase.** The admin Finance, Escrow (money-path), Error-monitoring, Email-ops, and Marketing dashboards 404 across the board in live mode.
**Files:** `src/admin/services/api-contracts.ts:327-358, 443-470, 650-666, 673-701, 708-727`. **Root cause:** the automated contract guardrail (`api-contract.test.ts`) only parses `src/lib/api.ts` — it never sees this file, so ~20 phantom admin paths shipped with a green test suite. **This process gap itself is worth fixing** (see Recommendations).

### Other P1s
- **Brand-safety AI scoring is fully built on both ends (Python + Java) but the FastAPI route is never mounted** — `main.py` includes 4 routers, forgets `brand_safety.router`. 404s in production; degrades gracefully (no score) but the whole Wave-C sentiment feature is dark. `influora-ai/app/main.py:36-39`. **Effort: S** — one-line fix.
- **Admin content-moderation actioning throws 501** — flags can be seen, never resolved. `ApprovalWorkflowService.java:173-179`. Needs decision (who owns building this).
- **`notifications.markRead` FE/BE path mismatch** (`/notifications/{id}/read` vs. real `/notifications/read`) — 404s, unread badge never clears. `src/lib/api.ts:1277`. **Effort: S.**
- **Legal Grievance Officer details are empty placeholders** — blocks publishing the India-mandated grievance-redressal page. `src/lib/company.ts:43`. **Needs decision:** yes, CEO must supply the real officer/address.
- **Admin campaign/dashboard drill-down endpoints phantom** (`getById`, `getAtRisk`, `getHypeOps`, `getFinancialSummary`, `getMarketingSummary`) — `src/admin/services/api-contracts.ts:131,146,307,310,313`.

---

## P2 — partial or degraded, not actively deceptive (mostly)

- **Brand Messages page** is a second, fake chat surface duplicating the (also-mock, see P1) `/brand/chat` — needs a decision on whether to retire it. `src/pages/brand-messages.tsx:91`.
- **Brand Billing/Plan settings** — fabricated usage counters ("87/150 AI credits") on a page with no subscription backend behind it at all (see orphaned V54 entity cluster below). `src/pages/brand-billing-settings.tsx:95`.
- **Admin CEO Pulse dashboard** — revenue and *every* trend/change metric hardcoded to 0, explicitly `TODO(blocker)` pending a KPI snapshot table + Rohan's fee formula. `AdminDashboardStatsCache.java:82-92`.
- **Admin campaign-monitoring table** — spend/creator-count/deliverables/SLA-breach all hardcoded to 0; campaign `type` hardcoded `STANDARD`. `AdminCampaignService.java:76-91`.
- **Affiliate commission rate is a hardcoded 10% placeholder** on the money path, pending a real per-campaign rate. `AffiliateEarningsService.java:96-102`. **Needs decision.**
- **Meera AI chat**: placeholder `[Awaiting AI response via SSE stream]` persists if the async write-back fails; a settled-turn retry 409s instead of replaying, because the idempotency result-ref capability was never built. `MeeraSessionService.java:109-219`. **Needs decision** (build the result-ref path).
- **All external integrations are dev-stub-only, honestly gated**: Razorpay/RazorpayX, Meta Graph, Shopify, MSG91 all return stub responses when unconfigured — **no real payment/social app has been provisioned yet.** This means the platform cannot move real money or pull real Instagram metrics today, full stop, regardless of any code fix. **Needs decision:** ops/Swapnil to provision real credentials.
- **Instagram per-post media insights never wired** (account-level metrics work; per-post doesn't). `MetricsPollingJob.java:177`.
- **Subscription billing (V54) is a half-built entity cluster**: 4 tables + entities exist, **zero repositories, services, or controllers**, and the frontend billing page (above) isn't wired to it either. Nothing end-to-end. `UsageCounter.java:22` et al. **Needs decision:** whether paid plans ship at all, and when.
- **Creator affiliate-earnings feature is fully built on both ends but has no route** — `src/App.tsx` never registers it, so a complete, tested vertical slice is unreachable. `src/pages/creator-affiliate-earnings.tsx:12`. **Effort: S** (just add the route).
- ~10 more individual admin phantom/mismatched endpoints (brand `PUT`, creator `PUT`/tier, support escalate/stats, moderation suspensions, budget-override) — see full agent output for the complete list; all same root cause as the P1 admin-namespace findings.
- Two stale `NOT_IMPLEMENTED` guards in `src/lib/api.ts` (`brandReviews.listReceived`, creator demographics/content-performance) **block features whose backend now exists** — these are same-day, trivial unblocks. `src/lib/api.ts:1625,1673,1900`.

---

## P3 — cosmetic, tech debt, dead code (condensed — not a priority list)

- **`src/app/` is an entire dead Next.js app-router tree** (7 pages, imports `next`/`next/font`/`@vercel/analytics`) inside a Vite app — never built, never imported, pure confusion risk for future engineers.
- 4 fully-built but retired pages still ship as dead weight behind redirect routes (`brand-deals.tsx`, `brand-pipeline.tsx`, `creator-active.tsx`, `creator-inbox.tsx`, ~2,400 lines) — intentional retirement, just never deleted.
- `creator-dashboard.tsx` (461 lines, tested) and `brand-help.tsx` (135 lines) are built but never routed or linked — likely forgotten wire-ups, worth a keep/delete decision.
- `StaticPage` component, a dead `{false && ...}` Contract Details sheet in `creator-chat.tsx`, stale "mocked for now" doc-comments on already-wired admin components (overstates incompleteness, opposite-direction risk), stale `KNOWN_PHANTOM_PATHS` test baseline (6 of 10 entries now resolve to real routes), inline SEO tags pending a shared `<Seo/>` swap, placeholder help-center copy pending final content from Nisha.

---

## Cross-cutting root causes (fix the pattern, not just each instance)

1. **UI-before-wiring build order.** Every P0/P1 mock-page finding follows the same shape: a fully-designed page ships with a `mockX` const, and the matching backend controller *already exists* — this isn't 6 separate missing features, it's one recurring process gap (frontend agent builds the screen, wiring gets deferred, never comes back). Fixing the pattern (a "no page ships without a live-data check" gate) is higher leverage than fixing each page.
2. **Event-driven notifications were spec'd and consumer-built, but nothing enforces the producer side gets built alongside.** 24 of 28 notification types are silently dead. Needs a lint/test that flags a `NotificationEvent` subtype with zero `new` call sites.
3. **The admin API client lives outside the one guardrail that would have caught its phantom endpoints.** `api-contract.test.ts` should be extended to also parse `src/admin/services/api-contracts.ts` — this single test change would have caught ~20 of the findings above automatically.

---

## Recommendation to Arjun / Swapnil

Do **not** attempt to fix all ~55 findings in one pass — that's how half-fixes happen. Suggested sequencing:

1. **P0 + money-adjacent P1s first** (brand wallet, payout settings, delete-account honesty, KYC data-drop, admin escrow namespace) — these actively mislead users or admins about money/identity state.
2. **Fix the contract-test gap** (extend guardrail to `api-contracts.ts`) — cheap, prevents regression of the admin findings while they're being fixed.
3. **Brand core-workspace wiring** (wallet, campaigns, contracts, campaign-detail, creator-profile, deal inbox) as one coordinated multi-agent pass, since they share the same root cause and several share files.
4. **Notification event-producer pass** — systemic, large, but mechanical (wire `new XxxEvent(...)` calls into the ~20 service methods that should already be publishing them).
5. Everything else (P2/P3) triaged into normal backlog; several are one-line unblocks (stale `NOT_IMPLEMENTED` guards, un-mounted brand-safety router, un-routed affiliate-earnings page) worth doing opportunistically.

**Decisions needed from Swapnil/Rohan before certain items can be built:** delete-account real semantics, KYC PII storage design, affiliate commission rate, subscription-billing ship/no-ship + timeline, Meera idempotency result-ref build, real payment/social integration provisioning, admin error/email-ops ownership, brand-messages retire-or-wire call, Grievance Officer legal details.

**Full per-finding detail (all ~55 findings with complete file:line citations, exact whatExists/whatsMissing/userImpact text) is preserved in the workflow transcript** at `subagents/workflows/wf_8d045789-a66/journal.jsonl` — ask Priya to pull any specific finding's full detail on request rather than duplicating it all here.
