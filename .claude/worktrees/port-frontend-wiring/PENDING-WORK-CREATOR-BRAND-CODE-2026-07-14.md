# Influora — Pending CODE Work to Get Creator & Brand Working Properly

**Owner:** Priya (CTO) · **Date:** 2026-07-14 · **Branch:** `claude/api-connection-workflow-b62285`
**Scope:** only tasks that require writing/editing code. Every item is file-anchored to current source on **this branch**.
**Route via:** Arjun. **Gate:** Kavya QA → Meera local verify → Priya sign-off.

Baseline on this branch: **Creator ~73% / Brand ~70%** "working properly." Build is green: `tsc --noEmit` = **0**, `vite build` OK, `newAny` = 0.

---

## ✅ Done this session (this branch)

**Landed this run:**
- [x] **`brand-forgot-password.tsx`** — `handleSubmit` wired to `api.auth.forgotPassword(email)` behind `isApiLive()`; added error state (`role="alert"`, `text-destructive`), generic success copy preserved so email existence never leaks; mock path keeps 800ms fake-success.
- [x] **Hype payload data-loss fix** (`src/lib/api.ts`) — `campaignToPayload()` now forwards `campaignType` + `hype` (new `hypeToPayload` helper serializes `liveUntil` → ISO); added `HypeConfigPayload` type, no `any`. Fixes silently-dropped Hype data on `campaigns.create/update`.
- [x] **`campaigns-list.tsx` mock→live** — wired to `api.campaigns.list()` behind `isApiLive()`; loading skeleton + retryable error Alert; all 4 dropdown actions (duplicate/pause/resume/delete, grid + list) wired to live facade.
- [x] **`contracts-and-deliverables.tsx` mock→live** — wired to contracts facade (list/get/sign) + deliverables approve/requestRevision behind `isApiLive()` with mock fallback + loading/error UI; sign dialog no longer a no-op; Download PDF left a disabled stub (no endpoint). `tsc --noEmit` = 0 after change.

**Prior batch (already on branch):**
- [x] Error boundary + type-safety pass (`newAny` = 0).
- [x] Brand wallet + creator wallet FE wiring (facade-gated).
- [x] Creator profile wired to portfolio facade.
- [x] Creator settings — logout live, rest scoped mock.
- [x] Notifications wiring.
- [x] Messages FE wiring.
- [x] Campaign-detail wiring.
- [x] Model pin.

---

## ⛔ P0 as previously written — DOES NOT APPLY on this branch

The prior P0 ("117 `tsc` errors / 2565-line api.ts / missing store slices `analytics`/`campaignTracking`/`storeIntegrations`/`metaOAuth`/`creatorCampaigns`/`creatorDisputes`/`creatorDeliverables`") was scoped to `feature/analytics-platform`. **None of it reproduces here.** On `claude/api-connection-workflow-b62285`:
- `npx tsc --noEmit` exits **0** (not 117 errors).
- The missing-store-slice / missing-`@/lib/api`-export failure mode is not present.

No action required for that P0. The real remaining work is the backend contract gaps below.

---

## P1 — FE↔BE controller-path reconciliation (needs Maven / backend) *(Vikram + Arjun to route)*

These three brand surfaces are FE-wired and build clean, but the facade path has **no matching Spring controller route** — they run on mock/dev today and would 404 in live mode. `mvn` is not on PATH in this environment; Java patch text was drafted as **uncompiled handoff** for a machine with a Maven toolchain.

- [ ] **Wallet** — facade calls `GET /wallet`, `GET /wallet/transactions`, `POST /wallet/recharge`, `POST /wallet/withdraw`; `WalletController` only maps `GET /wallet/balance` (and the shape differs from FE's `{availableBalance,escrowLocked,pendingPayouts,runwayDays}`).
  - **GET summary + transactions:** mechanically addable now (patch drafted — `WalletSummaryResponse`/`WalletTransactionResponse` DTOs, `WalletService.getSummary/getTransactions`, two `WalletController` routes). `WalletTransactionRepository.findByWalletIdOrderByCreatedAtDesc` already exists; manual sublist for pagination. `pendingPayouts`/`runwayDays` have no backing query → return `0`/`null` with TODO, do not fabricate.
  - **recharge/withdraw:** NOT a route stub — no wallet top-up order-initiation flow exists; needs a Razorpay order-create + webhook-confirm path. Separate ticket, Priya sign-off (Guardrail 1: no client-supplied amounts to the ledger without gateway confirmation).
- [ ] **Dashboard** — facade calls `GET /dashboard/actions` + `GET /dashboard/pipeline`; **no `DashboardController` exists**. Needs new `CollaborationRepository` queries (`findByCampaign_WorkspaceId`, `findByCreatorId`) + a `DashboardService` mapping `CollaborationStatus` → pipeline buckets + a thin controller. `actions` item shape needs product rules (no deadline/deliverable linkage on `Collaboration`) — design-then-build, not a stub.
- [ ] **Messages** — facade calls `GET/POST /deals/:dealId/messages` + `POST .../read`; **no message-thread entity exists** (`AiMessage` is Meera's AI chat, unrelated). Needs a new `DealMessage` entity + migration + repository + authz service (brand member or invited creator only) before any route. Schema-change ticket, owner of `schema-changes.md` to review.

**Also (higher severity — blocks an entire role):**
- [ ] **Creator login + onboarding have zero backend routes.** `POST /auth/creator/login` does not exist (`AuthController` is `/auth/brand/*` + refresh/logout/forgot/reset only). `/onboarding/creator/{socials,profile,complete,kyc,payout}` do not exist (`OnboardingController` is hard-mapped to `/onboarding/brand`). In live mode a creator literally cannot log in or onboard. Escalate immediately, separate from the 3 above.
- [ ] **No `DealController` anywhere** — the whole `deals` facade (list/get/accept/reject/counter + escrow/payout via deal) is dead in live mode. Domain entity is `Collaboration`; `CollaborationRepository` has one query method. Design ticket with the deal-domain owner.
- [ ] **No PortfolioController** — the several `/portfolio/*` and `/me/portfolio*` facade calls are unmapped; same wired-but-unmapped pattern. Follow-up pass.
- [ ] **`GET /contracts` (list)** is unmapped — `ContractController` has only `GET /contracts/{id}`, POST, and `/sign`. The contracts screen went live this run but the list endpoint still needs adding.
- [ ] Confirm `POST /notifications/read-all` (FE) vs `POST /notifications/read` (controller) suffix — Kavya to verify param/body shape.

---

## P2 — Product / scoping decisions *(Ananya + owners)*

- [ ] **Meera (brand) SSE wiring decision** — showcase renders scripted `meera-mock.ts`; SSE stream is not wired. Decide: wire to the live feed, or explicitly scope as a marketing demo.
- [ ] **Dead-page cleanup** — orphaned mock-only standalones now shadowed by redirect routes: `creator-inbox.tsx` (→ `/creator/deals?status=new`), `creator-active.tsx` (→ `/creator/deals?status=in_progress`), plus retired brand `deals`/`pipeline` redirects. Delete to remove the false "built" count.
- [ ] **Creator chat** — 1448-line UI imports no facade (local stores + mock only); revisit once the messages backend exists.
- [ ] **Creator settings** — notification prefs + account deletion have no endpoint (code already notes this); wire when backend lands.

---

## P3 — Infra / deploy

- [ ] Provision a Maven / Java 21 toolchain so the drafted wallet patch (and future backend work) can be compiled/tested; `mvn` is absent from this environment (`mvn -v` → command not found).
- [ ] Confirm the Spring backend compiles/deploys on Java 21. *(Meera)*
- [ ] Code-split the >1.5 MB main chunk (perf, not a blocker). *(Ananya)*

---

## Definition of done (per vertical)

1. `tsc --noEmit` = 0 (✅ already true on this branch) and gated in CI.
2. Every FE-wired facade path has a matching, correctly-shaped backend controller route (the P1 list above).
3. Creator can log in + onboard against real backend routes (currently impossible in live mode).
4. Seeded brand + creator complete the core loop end-to-end live: register → onboard → campaign → deal room → contract → wallet/escrow.
5. Kavya QA → Meera `build`/`dev`/`test` green → Priya sign-off.

**Effort:** FE last-mile is largely done. The remaining work is backend contract reconciliation (Maven-gated) + the creator-role backend gap, not frontend wiring.

*Code-only. No product files modified by this doc. Companion to `PRIYA-CTO-FEATURE-HEALTH-PERCENT-2026-07-14.md`.*
