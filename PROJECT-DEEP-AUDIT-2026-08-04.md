# Influora — Whole-Project Deep Audit (Code-Truth)

**Date:** 2026-08-04
**Method:** Static source analysis only. Four independent fresh-context audit agents (one per domain) each traced every feature from the UI → API client → backend controller route → service logic, reading primary source only (no prior audit doc trusted). Cross-checked by an independent connection reconciliation: every typed `request()` call diffed against all 277 backend routes by method + path.
**Not verified:** Nothing was run live. "Working" = code-complete + correctly wired, **not** runtime-proven. The AI service refuses to boot without provisioned model keys, so all AI verdicts are code-truth only.

> **Creator remediation update — 2026-08-04 (proof-os task `creator-fix-0804`).** The two genuinely-missing creator flows and the shipment wording were remediated after this audit; the **Creator** row and the sections marked *(remediated 2026-08-04)* below reflect the post-fix state:
> - **KYC capture** — now built and reachable: `KycIdentityForm` (Settings › Identity Verification) → `useCreatorKyc` → `onboarding.submitCreatorKyc` → real `POST /onboarding/creator/kyc`. ⬜→✅.
> - **Onboarding payout method** — the dead `saveCreatorPayout` wrapper was removed; payout-method capture already lives in the wallet (`/wallet/payout-methods`), so the row was a redundant duplicate and is dropped (not a separate feature).
> - **Deal-room shipment** — wording corrected: in live mode `items` reflects the real `productName`; only `estimatedDelivery` is a hardcoded placeholder (no backend field). Still correctly PARTIAL.
> Verified by `npx tsc --noEmit` (0 errors) + eslint (0 errors) on the changed files + call-site reachability greps. Ceiling: BELIEVED (static; no live HTTP). Brand numbers are unchanged here — they are governed by `BRAND-BLINDSPOT-AUDIT-2026-08-04.md`.

> **Creator remediation update #2 — 2026-08-04 (proof-os task `creator-deliv-0804`, priya sign-off).** The four previously-orphan **deliverable post-publish lifecycle** routes are now wired into the deal-room deliverables panel (⬜→✅ ×4):
> - `POST /creator/deliverables/:id/mark-posted`, `POST …/metrics`, `POST …/proof` (multipart), `GET …/status` — new wrappers on `creatorDeliverables` (`api.ts`) → `useCreatorDeliverableLifecycle` → `DeliverableLifecyclePanel` (mounted at `creator-chat.tsx:2432`, reachable from the "Deliverables" tools panel for rows in APPROVED/POSTED/METRICS_REPORTED/VERIFIED).
> Creator ⬜ 8 → **4** (remaining: review-flag, analytics me/media, and the brand-facing creator-discovery routes — see §5). Verified by `npx tsc --noEmit` (0 errors) + eslint (0 errors on changed files) + reachability greps (all 4 wrappers have a live caller). Ceiling: BELIEVED (static; no live HTTP).

> **Admin remediation update — 2026-08-04 (proof-os task `admin-fix-0804`, scope: Safe + document, priya sign-off).** The **Admin** row and the sections marked *(remediated 2026-08-04)* below reflect the post-fix state:
> - **2 BROKEN phantom endpoints — RESOLVED (deleted).** The dead `financeApi.getPayoutQueue` (`GET /admin/finance/payouts`) and `marketingApi.getReferrals` (`GET /admin/marketing/referrals`) wrappers had zero UI callers and no controller route; both were deleted from `api-contracts.ts` and their entries removed from the `KNOWN_PHANTOM_PATHS` guardrail baseline (`api-contract.test.ts`). Admin 🔴 2 → **0**; Admin total 37 → **35**.
> - **3 PARTIAL — confirmed by-design, not bugs (no code change).** All three are honest, deliberate gates: email `501 BULK_SEND_DISABLED` (pending abuse controls / security review), moderation `501 APPROVAL_ACTION_NOT_IMPLEMENTED` for `CONTENT_MODERATION` (deliberate read-only queue), and dashboard WoW `*Change` deltas returning `null` pending the `kpi_daily_snapshot` table (Meera-owned). Reversing any of them removes a security/abuse gate or requires a DB migration — out of scope for a bug-fix pass; retained as PARTIAL-by-design.
> - **15 MISSING — logged as backlog (no code change).** The Admin Finance/Escrow/Revenue console UI and the `unavailable()`-parked stubs are genuine unbuilt features (a build, not an error); they remain in §5 as prioritised backlog, unchanged.
> Verified by `npx tsc --noEmit` (0 errors) + call-site greps (0 live references to the deleted wrappers). Ceiling: BELIEVED (static; no live HTTP).

> **Admin remediation update #2 — 2026-08-04 (proof-os task `admin-console-0804`, priya sign-off).** The marquee MISSING item — the **Admin Finance / Escrow / Revenue console UI** — is now **built and reachable** (⬜→✅), consuming **7** real, previously-orphan GET routes:
> - New: `useFinanceConsole.ts` (hook, `Promise.all` over 7 live wrappers, per-panel error isolation), `FinanceConsole.tsx` (tabbed console: Revenue / Flagged Escrow / At-Risk / HYPE Ops / Suspensions + KPI strip), `RevenuePage.tsx` (page shell). Wired: route `admin-console.tsx:58` (`/admin/revenue`) + nav `AdminLayout.tsx:64` ("Revenue", `LineChart`).
> - Routes now consumed (all real `apiRequest`, GET-only, no mock data): `dashboardApi.getFinancialSummary` (`/admin/dashboard/financial` — also drives the revenue-trend table), `financeApi.getEscrowSummary` (`/admin/finance/escrow`), `escrowApi.getFlagged` (`/admin/escrow/flagged`), `campaignApi.getAtRisk` (`/admin/campaigns/at-risk`), `campaignApi.getHypeOps` (`/admin/campaigns/hype/ops`), `moderationApi.getSuspensions` (`/admin/moderation/suspensions`), `marketingApi.getReputation` (`/admin/marketing/reputation`). Admin ⬜ 15 → **8**; Admin ✅ 17 → **24**.
> - **Still MISSING (honestly blocked — no code change):** `/admin/finance/revenue` (`financeApi.getRevenue` — still orphan; the console draws revenue from `getFinancialSummary`, so this wrapper has 0 consumers), `/admin/audit/entity/*` (audit-by-entity lookup, `getByEntity` — not yet wired); and the `unavailable()`-parked stubs that have **no backend** (marketing acquisition/growth, reconciliation, TDS/26Q, payout-retry [Razorpay Route epic], escrow hold/release/refund [backend deliberately rejected direct escrow mutation], appeal review). These need backend built first — a bug-fix pass cannot conjure them.
> - **3 PARTIAL unchanged** — still by-design gates (bulk-send/moderation 501s + `kpi_daily_snapshot` deltas); not touched.
> Verified by `npx tsc --noEmit` (exit 0) + reachability greps (route + nav wired, 7 live wrappers consumed, 0 mock arrays). Ceiling: BELIEVED (static; no live HTTP — panels not exercised against a running backend).

> **Admin remediation update #3 — 2026-08-04 (proof-os task `admin-backend-0804`, priya sign-off).** The first *backend-gap* item is built: a real, data-backed **Growth analytics** endpoint (⬜→🟡). This one is PROVED, not just believed — the backend has passing tests.
> - **New backend:** `GET /admin/marketing/growth` → `AdminMarketingService.getGrowth` → `GrowthMetricsDto` (`AdminMarketingController`/`AdminMarketingDtos`). Repo methods added: `CampaignRepository.countBrandWorkspacesWithCampaign/…WithRepeatCampaigns`, `WorkspaceRepository.countByType`; reused `countByApplicationStatus`. **5/5 unit tests pass** (`AdminMarketingServiceTest`, incl. zero-denominator honesty + role-gate short-circuit).
> - **Serves (all from real `User`/`Campaign`/`CreatorProfile` rows):** `funnel.signups`, `funnel.firstCampaign`, `funnel.repeatCampaign`, `conversionRates.creatorApplicationToApproval`, `conversionRates.brandSignupToFirstCampaign`.
> - **Honestly OMITTED (no backing data — not faked as zeros):** `funnel.profileComplete` (no such flag), `cohortRetention` (no retention-event tracking), `referralStats` (no `Referral` table). FE type made these optional.
> - **FE wired + reachable:** `marketingApi.getGrowth` flipped `unavailable()`→`apiRequest('/marketing/growth')`; consumed by `useFinanceConsole` (8th fetch) and rendered as the "Growth Funnel & Conversion" card in `FinanceConsole` — so it is not a real-wrapper-with-no-UI orphan.
> - **Still MISSING (verified data-layer blockers, NOT built):** acquisition (CAC/attribution — no ad-spend, no signup-source), reconciliation (money-path), TDS/26Q (tax engine), payout-retry (Razorpay Route epic), escrow-mutate (backend deliberately rejected — security decision), `getRevenue`/`audit-entity` (unwired). These need data infrastructure / external integration / a product-security decision — not a code pass.
> Verified by my own oracles: `mvn -o -q clean compile` exit 0, `AdminMarketingServiceTest` 5/5, `npx tsc --noEmit` exit 0, FE↔BE field-name contract match. Ceiling: BELIEVED overall (no live HTTP against a running server) — but the backend logic is PROVED by unit test.

> **Creator + Brand remediation update — 2026-08-04 (proof-os task `verified-analytics-0804`, Swapnil sign-off).** Deliverable analytics are now **verified-first**: Meta-verified (`PLATFORM_VERIFIED`) is the default and only primary source; the manual self-report form is a **failure-only** escape hatch that opens ONLY when the Meta Graph API genuinely fails for a *connected* account (never for a verified result; not-connected shows "Connect Instagram", not manual). Both creator and brand see the real per-row provenance, so a self-report can never be shown as verified.
> - **Backend:** `POST /creator/deliverables/{id}/verify` runs the same verification as the 6h batch, on demand. `reportMetrics` now returns `409 MANUAL_METRICS_NOT_ALLOWED` unless `DeliverableVerificationService.Outcome` is a genuine Meta failure (`manualFallbackAllowed` — unit-tested over all 11 outcomes + a reject-when-`VERIFIED` test). `DeliverableStatusResponse` carries cached `metricSource`/`lastVerifiedAt`/`metaConnected`.
> - **Creator FE:** `DeliverableLifecyclePanel` rewritten verified-first (real IG numbers when verified · "Connect Instagram" when not connected · manual form only when `manualFallbackAllowed`, labelled self-reported). Shared `MetricSourceBadge`.
> - **Brand FE:** `brand-campaign-detail` renders a per-deliverable verified/self-reported badge from the **real** `DeliverableMetric.source` (not a hard-coded string); aggregate banner derived from the rows. This supersedes the §4 "Campaign analytics" honesty note below.
> - **Still gated (not a code gap):** live verified numbers need Meta app/keys provisioned + a connected creator + a real post — key-gated, so BELIEVED not PROVED. Pipeline-B roster/media analytics `source` labels need a backend DTO field (follow-up).
> Commits `e3c8a49` (backend) · `0efada7` (creator FE) · `8ac6a30` (brand FE). Verified: `mvn -o compile` + targeted junit green; `tsc`/`eslint` 0 errors on changed files.

> **Creator remediation update #3 — 2026-08-04 (proof-os task `creator-missing-0804`, priya sign-off).** The last two genuinely creator-facing MISSING routes are now built and reachable (⬜→✅ ×2), from `src/pages/creator-analytics.tsx`:
> - **Own-media analytics** — `creatorAnalytics.getMyMedia` → real `GET /creator/analytics/me/media` → `useCreatorOwnMedia` → `ContentPerformancePanel`. The creator now sees their own per-post reach/impressions/engagement-rate.
> - **Received reviews + flag** — new `CreatorReceivedReviews` surface (lists `GET /creator/reviews/received`) with a Flag dialog wiring the previously-orphan `creatorReviews.flag` → `POST /creator/reviews/{id}/flag`. This route had **no UI at all** before.
> Creator ⬜ 4 → **2**; the remaining 2 are the **brand-facing** creator-discovery routes (`/creators/search|featured|similar|suggestions`) — brands discovering creators, not a creator gap. Verified: `npx tsc --noEmit` 0 errors + eslint 0 errors on changed files + reachability greps. Ceiling: BELIEVED (static; no live HTTP). Commit `b44d30f`.

> **Creator remediation update #4 — 2026-08-04 (proof-os task `creator-shipment-eta-0804`).** The last Creator PARTIAL is resolved (🟡→✅): the deal-room shipment `estimatedDelivery` was a fabricated `now + 3d` on the FE because `Shipment` had no ETA column.
> - **Backend:** Flyway `V20260804130000` adds `estimated_delivery DATE`; `Shipment` entity + `markShipped` param; `MarkShippedRequest` + `ShipmentResponse` carry it; `ShipmentService` passes/maps it. Brand supplies it (optional) on mark-shipped.
> - **FE:** brand mark-shipped now sends the form's already-collected `type=date` value (was dropped); `creator-chat` renders the real `liveShipment.estimatedDelivery`; `ShipmentCard` renders "—" when null.
> **Creator 🟡 1 → 0** (row now `54/0/0/2/56`; TOTAL working +1 / partial −1). Verified: `mvn -o compile` + test-compile exit 0 (no shipment tests to break); `tsc`/`eslint` 0 errors on changed files. Ceiling: BELIEVED (static; no live HTTP / live DB migration run). Commit `43d4260`.

---

## 1. Executive Summary

| Domain | ✅ Working | 🟡 Partial | 🔴 Broken | ⬜ Missing | Total |
|---|---:|---:|---:|---:|---:|
| **Admin** | 24 | 4 | 0 | 7 | 35 |
| **Brand** | 53 | 6 | 0 | 10 | 69 |
| **Creator** | 54 | 0 | 0 | 2 | 56 |
| **AI / Meera** | 27 | 6 | 0 | 1 | 34 |
| **TOTAL** | **158** | **16** | **0** | **20** | **194** |

*Creator row is post-remediation (2026-08-04): `creator-fix-0804` +1 working (KYC), −2 missing, −1 total; `creator-deliv-0804` +4 working / −4 missing (deliverable lifecycle); `creator-missing-0804` +2 working / −2 missing (own-media analytics `GET /me/media` + received-reviews-with-flag `POST /creator/reviews/:id/flag`); then `creator-shipment-eta-0804` +1 working / −1 partial (real brand-supplied shipment ETA — last PARTIAL resolved). Net from the original 46/1/0/10/57 → 54/0/0/2/56. The remaining 2 ⬜ are the **brand-facing** creator-discovery routes (`/creators/search|featured|similar|suggestions`) — brands discovering creators, not a creator gap; tracked under Brand.*
*Admin row is post-remediation (2026-08-04): `admin-fix-0804` 🔴 2 → 0 (phantom endpoints deleted, total 37 → 35); `admin-console-0804` ✅ 17 → 24 / ⬜ 15 → 8 (Finance/Escrow/Revenue console built, **7** real routes wired — `getRevenue` stays orphan); then `admin-backend-0804` ⬜ 8 → 7 / 🟡 3 → 4 (real data-backed Growth endpoint built — `GET /admin/marketing/growth`). The 3 original PARTIAL are by-design gates; the remaining 7 MISSING are honestly blocked on unbuilt backend / external epics / a security decision (see the remediation updates at the top).*

**Completion metrics (transparent, code-truth):**
- Fully working: **158 / 194 = 81.4%**
- Wired to a real backend (working + partial): **174 / 194 = 89.7%**
- Weighted (partial = ½): **166 / 194 = 85.6%**

### The one-line story
**The API wiring is excellent — the problems are not connection bugs.** All 140 typed `request()` calls in the main frontend client resolve to a real backend method+path (**zero phantom endpoints**). The real gaps are elsewhere: an unbuilt admin finance/escrow console (backend is ready, no UI), money flows that are code-complete but blocked on **infra/config** (live Razorpay keys) or **deliberate security scope exclusions**, analytics/scoring placeholders, and a handful of genuinely unbuilt user flows.

---

## 2. API Connection Reconciliation (independent oracle check)

- **Main client (`src/lib/api.ts` + `src/lib/meera-api.ts`) — brand / creator / AI:** 140 typed `request()` calls → **140 resolve. 0 phantom endpoints.** Path-level wiring is sound. Any "broken" feature here is logic / runtime / env, not a missing route.
- **Admin client (`src/admin/services/api-contracts.ts`) — separate wrapper:** **0 phantom endpoints** *(remediated 2026-08-04, `admin-fix-0804`)*. The two that existed were dead code (0 UI consumers) and are now **deleted**:
  - ✅ `GET /admin/finance/payouts` — `financeApi.getPayoutQueue` wrapper **deleted**; baseline entry removed.
  - ✅ `GET /admin/marketing/referrals` — `marketingApi.getReferrals` wrapper **deleted**; baseline entry removed.
- **Orphan backend routes:** 277 backend routes vs ~200 frontend calls. The remainder are webhooks, `/internal/meera/*`, JWKS, health, and OAuth callbacks (correctly no frontend), plus the "Missing" backend routes listed per-domain below.

---

## 3. What's BROKEN (true defects) — 0 *(was 2; both resolved 2026-08-04, `admin-fix-0804`)*

| # | Domain | Feature | Resolution | Evidence |
|---|---|---|---|---|
| ~~1~~ | Admin | Finance payout queue | ✅ **RESOLVED** — `financeApi.getPayoutQueue` (dead code, 0 callers, no controller route) deleted; `/admin/finance/payouts` removed from `KNOWN_PHANTOM_PATHS` baseline. | `api-contracts.ts` (wrapper removed), `api-contract.test.ts` (baseline shrunk) |
| ~~2~~ | Admin | Marketing referrals | ✅ **RESOLVED** — `marketingApi.getReferrals` (dead code, 0 callers; controller exposes only `/reputation`) deleted; `/admin/marketing/referrals` removed from baseline. | `api-contracts.ts` (wrapper removed), `AdminMarketingController.java:38` |

Both were low blast-radius unreachable dead code. Deleted rather than backed (no product need for either route). Verified: `npx tsc --noEmit` exit 0; 0 live references remain.

---

## 4. What's PARTIAL (wired, but stub / placeholder / gated) — 17

### Admin (4)
- 🟡 **Marketing Growth analytics** *(BUILT 2026-08-04, `admin-backend-0804` — was ⬜ Missing)* — `GET /admin/marketing/growth` serves a real, tested, data-backed **subset**: funnel signups/first-campaign/repeat-campaign + the two conversion rates (from real `User`/`Campaign`/`CreatorProfile` rows; `AdminMarketingService.getGrowth`, 5/5 tests). PARTIAL because the fuller `GrowthMetrics` surface — `cohortRetention`, `referralStats`, `funnel.profileComplete`, and the whole separate acquisition/CAC endpoint — is **honestly omitted** (no retention-event tracking, no `Referral` table, no ad-spend/attribution). Wired + reachable in `FinanceConsole`.
- 🟡 **Dashboard CEO pulse** *(by-design, reviewed `admin-fix-0804`)* — core metrics real, but week-over-week `*Change` delta fields return `null` pending a `kpi_daily_snapshot` table; `ESCROW_LOW`/`SLA_BREACH` alerts unimplemented. `AdminDashboardService.java:47-53`. **By design:** honest `null` + TODO, not fabricated; needs the Meera-owned snapshot table + a DB migration to complete — a data-platform task, not a fix.
- 🟡 **Email send-bulk** — backend deliberately returns `501 BULK_SEND_DISABLED` pending abuse controls / rate-limit / security review. `AdminEmailController.java:75-87`. **By design:** the 501 is an intentional abuse-control gate; enabling it without those controls is a security regression, so it is retained until security sign-off.
- 🟡 **Moderation process-approval** — works for `BRAND_KYC` + `CREATOR_APPLICATION`; throws `501 APPROVAL_ACTION_NOT_IMPLEMENTED` for `CONTENT_MODERATION` items (read-only queue). `ApprovalWorkflowService.java:172-178`. **By design:** an honest 501 for a deliberately read-only queue; making it actionable is a scoped feature, not a bug fix.

### Brand (6)
- 🟡 **Campaign analytics** *(verified-first as of `verified-analytics-0804`)* — metrics are now **Meta-verified by default** (`PLATFORM_VERIFIED`); creator self-report is only a failure fallback, and the brand sees each row's real `source` (verified vs self-reported). Still 🟡 because live verified numbers are key-gated (Meta app/keys + a connected creator). `DeliverableMetricService.java:233`, `brand-campaign-detail.tsx:1455`.
- 🟡 **Creator analytics — metrics / demographics** — real, but return an empty typed shape until a metric snapshot is computed (never 404).
- 🟡 **Creator analytics — scores** — `QualityScoreService` audienceMatch is a hardcoded neutral 50; `FakeFollowerDetectionService` deliberately not implemented. `QualityScoreService.java:23,38`.
- 🟡 **Brand analytics roster page** — falls back to `demoCreators` in mock mode; live needs real creator IDs. `brand-analytics.tsx:49,97`.
- 🟡 **TrendSpark nudge** — returns fallback templated placeholder copy when the AI client is unavailable. `CreatorNudgeService.java:215`.

### Creator (1)
- ✅ **Deal-room shipment** *(resolved 2026-08-04, `creator-shipment-eta-0804`)* — `items` reflects the real `productName`, and `estimatedDelivery` is now a **real brand-supplied ETA** (`Shipment.estimatedDelivery` DATE column via Flyway `V20260804130000`; brand enters it on mark-shipped, creator renders `liveShipment.estimatedDelivery`). The hardcoded `now + 3d` is gone — this was the last Creator PARTIAL, so **Creator 🟡 1 → 0**.

### AI / Meera (6)
- 🟡 **Meera streaming generation** — the browser SSE path to Python `/chat` is fully built but short-circuited: the frontend returns the sync `reply` and `return`s **before** `stream.open`. Also key-gated. `MeeraChatPanel.tsx:487-495`.
- 🟡 **Tool `get_campaign_performance`** — data flows and the Living-Canvas advances, but there is no inline chat-bubble renderer branch. `ToolResultRenderer.tsx:421-438`.
- 🟡 **Tool `request_payment`** + 🟡 **Tool `confirm_launch`** — executors are fully built but **deliberately excluded from the minted on-behalf JWT scope** → `403 ON_BEHALF_SCOPE_INSUFFICIENT` before the executor runs (awaiting security sign-off). `OnBehalfTokenService.java:63-66`.
- 🟡 **TrendSpark LLM recovery tagger** — `/internal/trendspark/tag` has no in-repo Java caller; depends on an external n8n workflow and uses static shared-secret auth (tracked tech-debt). `trend_tag.py:11-36`.
- 🟡 **Whole AI service boot** — `require_boot_secrets` refuses to boot without `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `SARVAM_API_KEY`, JWKS, `INTERNAL_HMAC_KEY`, `SERVICE_TOKEN_SIGNING_KEY`. Every model feature is code-complete but only runs when those are provisioned. `config.py:434-452`.

---

## 5. What's MISSING — 22 *(was 36; −2 creator `creator-fix-0804`, −4 creator `creator-deliv-0804`, −7 admin `admin-console-0804`, −1 admin `admin-backend-0804` [growth built])*

**Important distinction:** most "Missing" items are **orphan backend routes** (backend built, no UI wired) — often intentional (email-link handlers, admin/Meera-only paths, alternate endpoints). Only a few are genuinely unbuilt user-facing features. The genuinely-missing UI is what matters. *Admin's MISSING is now **7** (was 15): the Finance/Escrow/Revenue console (`admin-console-0804`, −7 routes) and the data-backed Growth endpoint (`admin-backend-0804`, −1, now 🟡 PARTIAL) were built; the remaining 7 are honestly blocked on **missing data infrastructure / external epics / a security decision** — acquisition (no ad-spend/attribution), reconciliation, TDS engine, payout-retry (Razorpay Route), escrow-mutate (backend rejected), `getRevenue` + `/admin/audit/entity/*` (unwired). Not a code pass — see §8.*

### Genuinely unbuilt user-facing features (build these)
- ✅ **Admin Finance / Escrow / Revenue console UI** *(BUILT 2026-08-04, `admin-console-0804` — was ⬜)* — new `RevenuePage` / `FinanceConsole` / `useFinanceConsole` at `/admin/revenue` (routed `admin-console.tsx:58`, nav `AdminLayout.tsx:64`) consumes **7** real GET routes: `/admin/dashboard/financial` (also feeds the revenue-trend table), `/admin/finance/escrow`, `/admin/escrow/flagged`, `/admin/campaigns/at-risk`, `/admin/campaigns/hype/ops`, `/admin/moderation/suspensions`, `/admin/marketing/reputation`. Live data only, no mock arrays; `tsc` green. **Still orphan:** `/admin/finance/revenue` (`getRevenue`, 0 consumers — revenue is drawn from `getFinancialSummary`) and `/admin/audit/entity/*` (`getByEntity`) not yet wired.
- 🟡 **Admin marketing Growth analytics** *(BUILT 2026-08-04, `admin-backend-0804` — see §4)* — `GET /admin/marketing/growth` now serves the data-backed funnel + conversion subset (5/5 tests, wired into `FinanceConsole`). Now PARTIAL, not missing.
- ⬜ **Admin marketing Acquisition** (CAC / source attribution) + **appeal review** + **escrow mutate** *(blocked — no backing data / security decision)* — still `unavailable()`. **Acquisition:** verified no ad-spend, no signup-source attribution on `User`, no `Referral` table — needs a data-collection project, not a controller. **Escrow mutate:** the backend **deliberately did not adopt** direct escrow manipulation (uses the dispute-mediated model) — a product/security decision, not wiring. **Appeal review:** needs the appeal domain model.
- ✅ **Creator KYC (PAN/Aadhaar)** *(remediated 2026-08-04 — was ⬜ Missing)* — now built and reachable: `KycIdentityForm` mounted in Creator Settings (`creator-settings.tsx:472`, opened from the "Identity Verification (KYC)" menu row) → `useCreatorKyc` (`src/hooks/creator/useCreatorKyc.ts:44`) → `onboarding.submitCreatorKyc` → real `POST /onboarding/creator/kyc`. Selfie uploaded via `uploads.upload(file,'creator')`.
- ~~⬜ **Creator onboarding payout method**~~ *(removed 2026-08-04 — redundant)* — the dead `saveCreatorPayout` (`POST /onboarding/creator/payout`) wrapper was deleted. Payout-method capture already lives in the wallet (`GET/POST /wallet/payout-methods`, wired in `creator-wallet.tsx`) and is counted there — this was a duplicate row, not a separate missing feature.

### Orphan backend routes (built, no consumer — lower priority)
~~Creator deliverable `metrics` / `status` / `proof` / `mark-posted`~~ *(✅ wired 2026-08-04, `creator-deliv-0804` — deal-room deliverables panel)*; ~~`POST /creator/reviews/{id}/flag`~~ *(✅ wired 2026-08-04, `creator-missing-0804` — CreatorReceivedReviews flag dialog)* / `POST /brand/reviews/{id}/flag`; ~~`GET /creator/analytics/me/media`~~ *(✅ wired 2026-08-04, `creator-missing-0804` — ContentPerformancePanel in creator-analytics)*; `GET /wallet/balance`; `POST /wallet/escrow/refund|payout`; `GET /contracts/unsigned`; `POST /deliverables/{id}/reject`; campaign-template create/delete; `POST /notifications/read-all|unsubscribe` (email-link handlers); extra creator-discovery routes (`/search`, `/featured`, `/{u}/similar`, `/suggestions`); most workspace-member management ops (accept/switch/remove/invites/revoke); `meera-help ?ask=` pre-seed constant.

---

## 6. Bugs from prior tracking — status now (code-truth)

| Prior issue | Status in code today | Evidence |
|---|---|---|
| Portfolio-public `toFixed` crash | ✅ **FIXED** — all `.toFixed` calls null-guarded | `creator-portfolio-public.tsx:446,761` |
| Contract brand-sign 400 | ✅ **FIXED** — signer role server-derived from JWT | `api.ts:2147` |
| Meera on-behalf read-only scope (create_campaign 403) | ✅ **FIXED** — `create_campaign` now in `SCOPE_DEFAULT` | `OnBehalfTokenService.java:68` |
| Meera outcome-digest dropped in chat.py | ✅ **FIXED** — copied into brand_fields + rendered | `chat.py:154`, `assembler.py:301` |
| Meera blank-turn (~28%) / max_tokens truncation | ✅ **FIXED** — max_tokens 384→1536, truncation retry | `claude.py`/`loop.py`/`chat.py` |
| Escrow fund 500 on live | ⚠️ **INFRA** — code correct; placeholder Razorpay keys cause 500 | `EscrowController.java:83-88` |
| Meera money tools E2E | 🟡 **SCOPE-BLOCKED** — `request_payment`/`confirm_launch` excluded from minted scope | `OnBehalfTokenService.java:63-66` |

---

## 7. What the audit could NOT see (honesty / limits — proof-os law 5)

- **Nothing was run.** This is static analysis. A route matching by method+path does not prove the handler returns correct data at runtime.
- **The AI service is key-gated** and won't boot without provisioned keys, so no AI feature was live-exercised.
- **Money paths** (escrow, wallet, billing) are code-complete but their live behaviour depends on Razorpay live keys + webhooks — untested here.
- **Mock-mode fallbacks** (`isApiLive()`) are dev conveniences (blocked in production builds); a feature reading demo data in dev is not necessarily a production defect.

---

## 8. Recommended priority order

1. ~~**Delete or back the 2 phantom admin endpoints**~~ — ✅ **DONE 2026-08-04** (`admin-fix-0804`): both `financeApi.getPayoutQueue` and `marketingApi.getReferrals` deleted (dead code, 0 callers); baseline shrunk; `tsc` green.
2. ~~**Build the Admin Finance/Escrow/Revenue console UI**~~ — ✅ **DONE 2026-08-04** (`admin-console-0804`): `/admin/revenue` console consumes 7 real GET routes; `tsc` green. Remaining: wire `/admin/finance/revenue` (`getRevenue`) + `/admin/audit/entity/*`; build backend for marketing-analytics / reconciliation / TDS / payout-retry before their UIs.
3. **Provision Razorpay live keys** — unblocks the entire money E2E (escrow fund/release, wallet, billing checkout) which is code-complete.
4. **Security sign-off + scope for `request_payment`/`confirm_launch`** — unblocks Meera's money tools.
5. ~~**Build Creator KYC + onboarding-payout UI**~~ — ✅ **DONE 2026-08-04** (`creator-fix-0804`): KYC capture built in Settings; redundant onboarding-payout wrapper removed (payout lives in the wallet). No remaining genuinely-missing creator flow.
6. **Finish analytics/scoring** — replace `QualityScoreService` audienceMatch placeholder and implement `FakeFollowerDetectionService`.

---

*Generated by a 4-agent fresh-context audit + independent connection reconciliation. Every verdict cites primary source. See `PROJECT-DEEP-AUDIT-2026-08-04.html` for the interactive, filterable dashboard.*
