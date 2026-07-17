# ADMIN — Code-Anchored Pending-Work Assessment

> **Author:** Priya (CTO) · **Date:** 2026-07-11 · **Method:** full-context read of real source
> (not the `.md` descriptions). Every status below is anchored to `file:line` in the actual tree.
> **Supersedes** the ADMIN half (PART 2) of `wiki/tech/BRAND_ADMIN_PENDING_WORK.md` where they disagree.
> **Scope:** read-only assessment. No source was modified.

---

## TL;DR — the tracker's ADMIN section is materially stale

Earlier cycles kept finding "thin wiring" items that were already live. The ADMIN section of the
tracker has the **opposite and larger** staleness problem: it describes the modular `src/admin/**`
panel as "45–55% built, needs wiring," but two facts change the whole plan:

1. **The modular `src/admin/**` panel is not mounted on any route.** `/admin` renders a *different*
   file — the demo console `src/pages/admin-dashboard.tsx` — driven entirely by `lib/demo-data.ts`.
   The polished `AdminLayout`/`PulseDashboard`/`BrandProfile`/`FlagQueue`/`TicketList`/`CampaignTable`/
   `FeeControlPanel` components are referenced **only by their own test files**, never by `App.tsx`.
   Verified: `App.tsx:27,419` (imports + routes only `AdminDashboardPage`); `grep` for
   `AdminLayout|PulseDashboard|BrandProfile` in `src/**/*.tsx` returns only `src/admin/**` self-refs
   and `.test.tsx`.
2. **Most admin backend controllers already exist and are real implementations**, not stubs — despite
   the hooks' TODO comments claiming otherwise. `AdminBrand/Creator/Support/Dashboard/ApprovalWorkflow/
   PlatformFeeAdmin/AuditLog/AdminDispute` controllers are all present with services + DTOs + the
   `V34__admin_tables.sql` migration.

So ADMIN is **not** "build the backend, then wire." For most modules it is **"mount the real panel and
flip the one-line mock→client swap"** the hooks were explicitly written for — with a smaller set of
genuinely-missing backend endpoints called out per module below.

---

## 1. Per-sub-area verified status

Legend for the last column: **WIRING** = frontend client method + backend endpoint both already exist
(pure swap of mock→real). **BACKEND-GAP** = the specific endpoint the UI needs is not built yet.
**FE-GAP** = backend exists but the typed client method is missing from `api-contracts.ts`.

### Route guard
- **Status:** `/admin` has **zero** React-side auth wrapper — `src/App.tsx:419`
  (`<Route path="/admin" element={<AdminDashboardPage />} />`), comment at `:418` says *"demo data
  only until M2 backend lands."* Confirmed exactly as the tracker states.
- **Real risk is lower than the tracker implies:** the routed page is the **demo console**
  (`src/pages/admin-dashboard.tsx:40-44`, `:127-128`) reading `demoAdminStats`/`demoAdminUsers`/etc.
  from `lib/demo-data.ts`. It performs **no live API calls and no mutations** — it cannot move money
  or approve KYC. And the backend is independently protected: `SecurityConfig.java:120`
  `.requestMatchers("/admin/**")` falls through to `.authenticated()` (`:192`); only
  `/admin/auth/login` + `/refresh` are `permitAll` (`:88-91`). So the exposure today is a
  **public read-only demo screen**, not a live money console.
- **Verdict:** still worth a guard before the *real* panel mounts, but it is a frontend-only gate.
  Backend authz already exists — **WIRING** (frontend `<AdminProtectedRoute>` in the pattern of the
  existing `CreatorProtectedRoute`/brand guards).

### api-contracts.ts wiring
- **Status:** `src/admin/services/api-contracts.ts` is complete and fully typed (547 lines, 12 API
  groups). It is imported in exactly **one** place: `useAdminAuth.ts:17` — which is **already live**
  (`authApi.getCurrentUser()` at `useAdminAuth.ts:230`, real JWT validation `:100-115`). The tracker's
  "client is complete and typed, just unused" is right about the other 11 groups but **wrong that it's
  wholly dead** — auth already runs through it.
- Base path `'/api/v1/admin'` (`:55`) matches the backend `server.servlet.context-path=/api/v1` +
  `@RequestMapping("/admin/...")` convention — confirmed against every controller javadoc. **No path
  renegotiation needed.**
- **Verdict:** the "wire the 7 dead hooks" item is real, but it's **per-module** work (below), not one
  task — and each hook already ships a commented react-query swap-in block naming the exact call.

### Users (Brands + Creators)
- **UI:** `src/admin/components/users/BrandProfile.tsx` + `CreatorProfile.tsx` are real (with tests:
  `BrandProfile.test.tsx`). **Not routed** (see TL;DR #1).
- **Data hook:** `useBrandDetail.ts` returns mock; its TODO (`:5-10`) claims *"AdminBrandController has
  not been built yet"* — **STALE / FALSE.** `AdminBrandController.java:43` exists:
  `GET /admin/brands/{id}` (`:52`), `POST .../verify-kyc` (`:58`), `/suspend` (`:67`), `/reinstate`
  (`:76`), backed by `AdminBrandService`. Client methods `brandApi.getById/verifyKyc/suspend/reinstate`
  all exist (`api-contracts.ts:156-181`).
- **Gaps:** `brandApi.list` (`:147`), `brandApi.update` (`:159`), `brandApi.overrideBudget` (`:183`)
  have **no backend endpoint** — the brand *list* view is a BACKEND-GAP; brand *detail* + KYC/suspend
  actions are pure **WIRING**. `useCreatorDetail.ts` mirrors this: `AdminCreatorController.java:42`
  has `{id}`, `review-application`, `instagram/force-reauth`, `suspend`, `reinstate` — but client's
  `creatorApi.list`/`adjustTier`/`getPendingApplications` have no backend (**BACKEND-GAP** for
  list/tier/pending).

### Dashboard
- **UI:** `PulseDashboard.tsx` real, **not routed**.
- **Hook:** `usePulseData.ts` returns mock; TODO (`:5`) says the endpoint *"is being built this cycle"*
  — **STALE.** `AdminDashboardController.java:39` `GET /admin/dashboard/pulse` **exists** (real service
  call `:41`), plus `/operations` (`:44`). Client `dashboardApi.getPulse` exists (`api-contracts.ts:119`).
- **Gaps:** `dashboardApi.getFinancialSummary` + `getMarketingSummary` are **BACKEND-GAP** —
  controller javadoc (`:22-27`) explicitly defers them (finance blocked until Phase 1; marketing needs
  Rohan/Tejas formulas). Pulse + operations = pure **WIRING**.

### Moderation
- **UI:** `FlagQueue.tsx` + tests real, **not routed**.
- **Hook:** `useFlagQueue.ts` mock; its TODO (`:15-23`) is the **most honest in the tree** — it
  correctly states *"no `AdminModerationController` … no `moderationApi` entry"* for content flags.
  Verified: no controller serves `GET /admin/moderation/flags` or `/flags/{id}/action`. The `moderationApi`
  client methods exist (`api-contracts.ts:403-417`) but point at **non-existent** endpoints.
- **What DOES exist:** `ApprovalWorkflowController.java:42` — `GET /admin/moderation/approvals/pending`
  (`:51`) + `POST /admin/moderation/approvals/{id}` (`:58`). That is the *approvals* workflow, a
  different surface than the *content-flag queue* the `FlagQueue` UI renders.
- **Verdict:** content-flag queue = **BACKEND-GAP** (need an `AdminModerationController` for flags +
  `moderationApi.list`/action already typed). Approvals surface = **WIRING** but has no UI consumer yet.
  The tracker's "moderationApi exists but is unused, wire it up" is misleading: wiring it as-is would
  call dead routes.

### Support
- **UI:** `TicketList.tsx` real, **not routed**.
- **Hook:** `useTicketList.ts` mock; TODO (`:6-14`) says `AdminSupportController` *"hasn't landed yet"*
  — **STALE / FALSE.** `AdminSupportController.java:46` is a **complete real controller**:
  `GET /admin/support/tickets` (list, `:55`), `GET /{id}` (`:70`), `POST /{id}/reply` (`:76`),
  `PUT /{id}` (status, `:85`), `POST /{id}/assign` (`:94`), backed by `AdminSupportService`. Client
  `supportApi.list/getById/reply/update/assign` all exist (`api-contracts.ts:353-387`).
- **Gaps:** `supportApi.escalate` + `getStats` are **BACKEND-GAP** (controller javadoc `:35-39`
  documents them as deliberate follow-ups). List / detail / reply / assign / status = pure **WIRING**.
  This is the **most ready-to-wire module in ADMIN.**

### Campaigns
- **UI:** `CampaignTable.tsx` real, **not routed**.
- **Hook:** `useCampaignList.ts` mock; TODO (`:6-9`) says *"no `AdminCampaignController` exists"* —
  **TRUE / VERIFIED.** No admin campaign controller in `influora-api/**/web`. Client `campaignApi.list/
  getById/getAtRisk/getHypeOps` (`api-contracts.ts:250-273`) point at **non-existent** endpoints.
- **Verdict:** **BACKEND-GAP** — needs an `AdminCampaignController` (read-only monitoring is fine for
  v1). Note the brand-facing `CampaignController` exists but is workspace-scoped, not an admin
  cross-tenant view — do not reuse it directly.

### Finance (fee config)
- **UI:** `FeeControlPanel.tsx` real, **not routed**; submit handler is an explicit stub
  (`FeeControlPanel.tsx:245`, `:258-265`, `:284`) that logs intent and **never fakes success** (good).
- **Hook:** `useFeeConfig.ts` mock; TODO (`:5`) says *"PlatformFeeAdminController has not been built
  yet"* — **STALE / FALSE.** `PlatformFeeAdminController.java:53` **exists** and is notably mature:
  `GET /admin/finance/fee-config` (`:62`), `PUT` (`:76`), `GET /history` (`:91`), backed by
  `PlatformFeeAdminService`. It already has: **SUPER_ADMIN + MFA gating** (javadoc `:42-47`,
  `requireRoleWithMfaSatisfied`), **optimistic-lock concurrency guard** with 409 on race
  (`:67-89`, `PlatformFeeConfig#version`, V44 migration), and an **audit history table**.
- **The real gap is frontend, not backend:** `api-contracts.ts` `financeApi` has
  `revenue/escrow/payouts/reconciliation/tds` (`:280-312`) — all pointing at **non-existent** backends —
  but has **no `getFeeConfig`/`updateFeeConfig`/`getFeeConfigHistory`**. The hook's own TODO (`:9-18`)
  and the controller javadoc (`:30-34`) both name this exact missing trio.
- **Verdict:** **FE-GAP** — add the 3 fee-config methods to `financeApi`, then wire `useFeeConfig` +
  `FeeControlPanel`. Backend money-movement safety (MFA, optimistic lock, audit) already in place.
  The broader `financeApi`/`escrowApi` revenue/payout/reconciliation surface is **BACKEND-GAP** (Phase 2
  per CEO directive, per controller javadoc `:27-30`) — do not wire those; they call dead routes.

---

## 2. Recommended build order (with security gates)

Sequenced by *readiness × risk*. 🔒 = **Kabir sign-off mandatory before merge** (money / KYC / auth).

**Phase 2.0 — Foundation (do first, blocks everything visible)**
1. 🔒 **Mount the modular `src/admin/**` panel + add `<AdminProtectedRoute>`** at `/admin` (replacing or
   nesting the demo console). This is the true unblock — the "wire the hooks" work is invisible until the
   panel is routed. Auth already runs live via `useAdminAuth`; gate the route on it. **Kabir:** verify the
   guard cannot be bypassed and that the demo console is not left publicly routable at a sibling path.

**Phase 2.1 — Pure wiring (backend + client both exist; lowest risk, fastest wins)**
2. **Dashboard** — swap `usePulseData` mock → `dashboardApi.getPulse()` (+ operations). No mutations.
3. **Support** — swap `useTicketList`/`useTicketDetail` → `supportApi.list/getById`; wire reply/assign/
   status. Highest-value ready module. (Support touches PII in tickets — see
   `SUPPORT-TICKET-PII-NOTES.md`; not a Kabir gate, but honor that note.)
4. 🔒 **Users — brand/creator detail + KYC/suspend actions** — swap `useBrandDetail`/`useCreatorDetail`
   → `brandApi.getById`/`creatorApi.getById`; wire `verifyKyc`, `suspend`, `reinstate`,
   `review-application`. **Kabir mandatory** (KYC approval + account suspension are compliance/authz
   actions; verify server-side re-authorization, not just the client RBAC matrix).

**Phase 2.2 — Frontend-gap then wire (backend exists, client method missing)**
5. 🔒 **Finance — fee config** — add `financeApi.getFeeConfig/updateFeeConfig/getFeeConfigHistory` to
   `api-contracts.ts`, then wire `useFeeConfig` + `FeeControlPanel`. **Kabir mandatory** — this sets the
   platform take-rate. Backend already has MFA + optimistic-lock + audit; Kabir should confirm the
   **client sends the MFA-satisfied token path** and that the panel respects the SUPER_ADMIN-only gate.
   *Note for the tracker:* a **true two-person maker-checker does NOT exist** — what exists is
   optimistic-locking + MFA + audit history. If maker-checker is a hard requirement, it is net-new
   backend work; do not record it as "done."

**Phase 2.3 — Backend-gap first, then wire (net-new endpoints)**
6. **Users — brand/creator LIST views** — build list endpoints (`brandApi.list`, `creatorApi.list`,
   `creators/applications/pending`) then wire. 🔒 if the list exposes KYC status/PII at scale (Kabir to
   scope).
7. 🔒 **Moderation — content-flag queue** — build `AdminModerationController` (`GET /moderation/flags`,
   `POST /flags/{id}/action`) then wire `useFlagQueue`. **Kabir** — moderation actions are enforcement
   powers. Existing `FlagQueue.test.tsx` must be re-verified against live shape (Kavya).
8. **Campaigns** — build read-only `AdminCampaignController` (cross-tenant monitoring) then wire
   `useCampaignList`. Read-only ⇒ no Kabir gate for v1.

**Do NOT wire (dead client routes — would create silent failures):** `financeApi.revenue/escrow/
payouts/reconciliation/tds`, `escrowApi.*`, `dashboardApi.financial/marketing`, `moderationApi`
approvals-vs-flags mismatch. These are Phase-2+ backend work per the CEO directive; leave the honest
mock/TODO in place rather than pointing hooks at 404s.

---

## 3. Where the tracker (`BRAND_ADMIN_PENDING_WORK.md` PART 2) is wrong vs. real code

| Tracker line | Tracker claim | Verified reality |
|---|---|---|
| P0 "Add `/admin` route guard" | *"console that moves money and approves KYC is publicly routable"* | The routed `/admin` is the **demo console** (`admin-dashboard.tsx`) on `lib/demo-data.ts` with **no live calls/mutations**. Backend `/admin/**` is already `.authenticated()` (`SecurityConfig.java:120,192`). Real exposure = public read-only demo, not a live money console. Guard still wanted before the real panel mounts. |
| P0 "Wire api-contracts.ts into 7 dead hooks" | *"the client is complete and typed, just unused"* | Partly wrong: it **is already used and live** by `useAdminAuth.ts:17,230`. And several client method groups (`financeApi` revenue/payout, `moderationApi` flags, `campaignApi`, `brandApi.list`) point at **endpoints that don't exist** — wiring them as-is calls dead routes. |
| **Missing entirely** | — | The tracker never notes that **the modular `src/admin/**` panel is not mounted on any route.** This is the single biggest gap and reorders the whole plan (mount first, then wire). |
| Users (55%) | "wire the stubbed actions to api-contracts.ts" | Correct direction, but `useBrandDetail.ts`/`useCreatorDetail.ts` TODOs claiming the controllers *"have not been built yet"* are **STALE** — `AdminBrandController`/`AdminCreatorController` exist with detail+KYC+suspend live. Brand/creator **list** is the actual backend gap. |
| Dashboard (55%) | "usePulseData is 100% mock; wire to real WS/REST" | Mock part true, but `AdminDashboardController.pulse/operations` **already exist** (REST). It's pure WIRING, not "build the feed." `financial`/`marketing` are the only real backend gaps. |
| Moderation (45%) | "moderationApi exists but is unused, wire it up" | Misleading: the content-flag endpoints `moderationApi` targets **don't exist** (only an *approvals* controller does). Needs **new backend**, not just wiring. `useFlagQueue.ts`'s own TODO already says this — the tracker contradicts the code. |
| Support (45%) | "wire reply/assign actions to the backend" | Understated: `AdminSupportController` is **fully built** (list/detail/reply/assign/status). This is the **most ready-to-wire** module — pure WIRING. Hook TODO saying it *"hasn't landed"* is STALE. |
| Campaigns (45%) | "wire to real data" | Correct that it's mock, but implies wiring exists — **no `AdminCampaignController` exists.** Genuine BACKEND-GAP. (Consistent with `useCampaignList.ts` TODO, which is accurate.) |
| Finance (40%) | "financeApi/escrowApi are typed but unused … no maker-checker exists" | The UI's actual backend (`PlatformFeeAdminController`) **exists** and the hook TODO calling it "not built" is STALE. The real gap is **FE**: `financeApi` lacks `getFeeConfig/updateFeeConfig`. "No maker-checker" is **half true** — no two-person control, but MFA + optimistic-lock + audit-history **do** exist. `financeApi`'s revenue/escrow/payout methods are the ones that are genuinely unbacked. |

**Net:** the tracker's ADMIN percentages assume "backend mostly missing." Reality is closer to
**"backend mostly present, panel unmounted, client half-pointed at dead routes."** The dominant risk is
not security exposure (backend is authauthenticated; frontend is a demo) — it's **shipping silent
failures by wiring hooks to the ~5 client method groups that have no backend.** Build order above is
sequenced to avoid exactly that.

---

### Files inspected (anchors above reference these)
- `src/App.tsx` (routing, `:27`, `:409-419`)
- `src/pages/admin-dashboard.tsx` (demo console)
- `src/admin/services/api-contracts.ts`; hooks: `useAdminAuth.ts`, `useBrandDetail.ts`, `usePulseData.ts`,
  `useFlagQueue.ts`, `useTicketList.ts`, `useCampaignList.ts`, `useFeeConfig.ts`
- `src/admin/components/finance/FeeControlPanel.tsx`
- `influora-api/.../web/`: `AdminAuthController`, `AdminDashboardController`, `AdminBrandController`,
  `AdminCreatorController`, `AdminSupportController`, `ApprovalWorkflowController`,
  `PlatformFeeAdminController` (+ `AuditLogController`, `AdminDisputeController` present, not detailed)
- `influora-api/.../config/SecurityConfig.java` (`:88-91`, `:120`, `:192`)
