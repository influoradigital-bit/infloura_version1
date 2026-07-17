# 🏗️ PRIYA (CTO) — Feature Health Scorecard (Creator + Brand), % Working

> **Date:** 2026-07-14 · **Branch:** `claude/api-connection-workflow-b62285` · **Method:** code-verified multi-agent audit 2026-07-14 (this branch). Per-feature scores read live from source on this branch and cross-checked by a 5-agent audit pass. Supersedes the earlier `feature/analytics-platform` stamp — that branch's numbers do **not** describe this tree.

## Headline

| Vertical | Working properly | One-line reason |
|---|---|---|
| **Creator** | **~73%** | Auth/register are mock-only (no creator backend routes exist); onboarding/deals/portfolio/profile/wallet/settings are facade-wired but most resolve to unmapped controllers in live mode; chat is mock. |
| **Brand** | **~70%** | Auth/onboarding fully live; the daily workspace (campaigns-list, contracts, forgot-pw, hype) went **live this run**; wallet/dashboard/messages are FE-wired but the facade paths have no matching backend controller route (would 404 live). |

**Build state on this branch:** `tsc --noEmit` = **0 errors**, `vite build` green, `newAny` = 0. The earlier "117 tsc errors / missing store slices / unrouted analytics" narrative was tied to `feature/analytics-platform` and **does not apply here** — those slices/exports are not the failure mode on this branch.

**The one systemic drag now:** three brand surfaces (Wallet, Dashboard, Messages) plus the entire creator auth/onboarding path are FE-wired to facade methods whose backend controller routes **do not exist**. They build clean and run fine in mock/dev, but would 404 the moment `isApiLive()` flips true. See the Live-readiness caveat below.

---

## Creator — per feature (% working properly)

| Feature | % | State |
|---|---:|---|
| Onboarding | 80% | 🟡 Facade-wired (`connectCreatorSocial/saveCreatorProfile/completeCreator`) w/ try-catch — but no `/onboarding/creator/*` backend route exists |
| Deals / Deal-room | 80% | 🟡 Facade-wired (`deals.list/accept/reject/counter`, `messages.markRead`) w/ mock fallback — no `/deals` controller in influora-api |
| Portfolio editor | 80% | 🟡 Facade-wired (`portfolio.getMine/analytics/update/syncPlatforms/uploadCover`) — no PortfolioController in backend |
| Portfolio public | 80% | 🟡 Facade-wired (`portfolio.getPublic/contact`), loading + "Page not found" states — no backend controller |
| Profile | 80% | 🟡 Facade-wired to portfolio (`getMine/syncPlatforms/update`) — portfolio facade has no backend; does not use the real `/users/me` |
| Wallet | 80% | 🟡 Facade-wired (`wallet.get/transactions/withdraw`) gated on `isApiLive()` — WalletController is brand-only `/wallet/balance`; creator paths unmapped |
| Settings | 80% | 🟡 Only `auth.logout('creator')` is genuinely live (route exists); notif prefs + account deletion stay mock by design |
| Inbox | 75% | 🟡 Route is a redirect → `/creator/deals?status=new`; standalone `creator-inbox.tsx` orphaned + mock-only |
| Active | 75% | 🟡 Route is a redirect → `/creator/deals?status=in_progress`; standalone `creator-active.tsx` orphaned + mock-only |
| Login | 60% | 🔴 Mock — runs `assertMockAuthAllowed()` + `login(createMockCreatorUser())` w/ hardcoded token; `auth.creatorLogin` exists but unused; no `/auth/creator/login` route |
| Register | 60% | 🔴 Mock — full 3-step UI, zero facade calls; no creator register endpoint |
| Chat | 50% | 🔴 Mock — 1448-line UI, imports no facade; driven by local stores only; no `/deals` messages backend |

**Creator average: ~73%** (mean of the 12 rows). The gap is now backend, not FE wiring: most creator screens already call facades correctly, but the creator auth/onboarding/deals/portfolio/wallet controllers do not exist server-side.

---

## Brand — per feature (% working properly)

| Feature | % | State |
|---|---:|---|
| Login | 100% | 🟢 Live, routed |
| Register | 100% | 🟢 Live |
| Onboarding | 100% | 🟢 Live |
| Discover | 90% | 🟢 Live (mock fallback) |
| New campaign | 90% | 🟢 `api.campaigns.create` |
| Forgot password | 90% | 🟢 **Wired this run** — `auth.forgotPassword` behind `isApiLive()`, error state added, no email-existence leak |
| New hype campaign | 85% | 🟢 **Payload fix this run** — `campaignType`+`hype` now forwarded by `campaignToPayload` |
| Campaigns list | 80% | 🟢 **Live this run** — `campaigns.list` + 4 dropdown actions (duplicate/pause/resume/delete) wired; loading + error UI |
| Creator profile view | 80% | 🟡 Mixed (api + mock) |
| Messages | 80% | 🟡 FE-wired — but facade path `/deals/:id/messages` has **no backend controller** (would 404 live) |
| Contracts | 75% | 🟢 **Live this run** — contracts facade (list/get/sign) + deliverables approve/revise; list `GET /contracts` still unmapped server-side |
| Campaign detail | 70% | 🟡 Mixed |
| Dashboard | 70% | 🟡 FE-wired — but no `DashboardController` exists (`/dashboard/actions`+`/pipeline` would 404 live) |
| Wallet | 60% | 🟡 FE-wired — WalletController only maps `GET /wallet/balance`; `/wallet`, `/transactions`, `/recharge`, `/withdraw` unmapped + field-shape mismatch |
| Edit campaign | 60% | 🟡 `api.campaigns.update` |
| Settings | 40% | 🟠 Thin, partial persistence |
| Deal chat | 40% | 🟠 Mock-heavy |
| Meera | 40% | 🟠 Scripted mock — SSE not wired |
| Deals | 20% | ⚪ Retired redirect |
| Pipeline | 20% | ⚪ Retired redirect |

**Brand average: ~70%** (mean of the 20 rows).

---

## ⚠️ Live-readiness caveat (FE wired, backend route missing)

These surfaces build clean and typecheck at 0, but the facade path has **no matching Spring controller route** — they resolve to mock/dev today and would **404 the moment live mode is on**. This is a live-readiness caveat, not a build error:

1. **Wallet** — facade calls `GET /wallet`, `GET /wallet/transactions`, `POST /wallet/recharge`, `POST /wallet/withdraw`; `WalletController` only maps `GET /wallet/balance` (and its shape `{walletId,balance,escrowBalance,currency}` ≠ FE's `{availableBalance,escrowLocked,pendingPayouts,runwayDays}`). GET summary + transactions are mechanically addable (patch text drafted, needs a Maven toolchain to compile); recharge/withdraw are a real Razorpay-order payments feature, not a route stub.
2. **Dashboard** — facade calls `GET /dashboard/actions` + `GET /dashboard/pipeline`; **no `DashboardController` exists at all**. Needs new `CollaborationRepository` queries + a `DashboardService` mapping `CollaborationStatus` → pipeline buckets. Design-then-build, not a stub.
3. **Messages** — facade calls `GET/POST /deals/:dealId/messages` + `POST .../read`; **no message-thread entity exists** (the only message entity, `AiMessage`, is Meera's AI chat). Needs a new `DealMessage` entity + migration + repository + authz service before any route. Schema-change ticket, not a route patch.

**Also blocking an entire role (higher severity than the 3 above):** creator login (`POST /auth/creator/login`) and the whole creator onboarding flow (`/onboarding/creator/{socials,profile,complete,kyc,payout}`) have **zero backend routes** — a creator cannot log in or onboard in live mode. There is no `DealController` anywhere (domain entity is `Collaboration`, and `CollaborationRepository` has one query method). Escalate separately.

---

## Bottom line

Foundations, security, and the AI service remain solid. On this branch the FE last-mile is largely done — Brand's daily workspace (campaigns-list, contracts, forgot-pw, hype) went live this run, and most Creator screens already call the right facades. The remaining drag has **shifted from frontend wiring to backend contract**: the creator auth/onboarding/deals/portfolio controllers and the brand wallet/dashboard/messages routes must be built server-side (Maven toolchain required) before either vertical is live-ready end-to-end.

*Code-verified on `claude/api-connection-workflow-b62285`. No product code modified by this doc. Companion to `PENDING-WORK-CREATOR-BRAND-CODE-2026-07-14.md`.*
