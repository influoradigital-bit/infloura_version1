# Influora Brand — Blind-Spot Audit (Final)

**Date:** 2026-08-04
**Scope:** Brand domain only. This report re-checks every blind spot declared by `PROJECT-DEEP-AUDIT-2026-08-04.md` §7 and by the 2026-08-04 brand verification pass, one by one, then lists **every** brand defect now known: broken, partial, missing.
**Method:** Deterministic oracles (tsc, eslint, vite build, scripted FE↔BE route reconciliation, call-site greps) + two independent fresh-context checkers who re-traced all 53 "WORKING" brand rows from the UI file to the backend controller. Every checker defect claim below was re-verified by a deterministic grep before being listed. Ledger IDs F-0065…F-0074.

---

## 1. Executive summary — the audit's Brand picture was too optimistic

The deep audit said Brand = **53 working / 6 partial / 0 broken / 10 missing**. Re-tracing every row found **7 rows mislabeled as WORKING** and **4 rows with wrong evidence citations**. The wiring the audit *did* check (route existence) is genuinely excellent — the blind spot was **call-site reachability**: a route can exist, the typed wrapper can exist, and still nothing in the brand UI ever calls it.

**Corrected Brand tally:**

| Status | Deep audit | Corrected | Change |
|---|---:|---:|---|
| ✅ Working | 53 | **47** | −7 mislabels, +1 (notifications read-all was wrongly MISSING) |
| 🟡 Partial | 6 | **9** | +billing bundle, +billing checkout, +command bar |
| 🔴 Broken/mislabeled | 0 | **0 runtime** (8 audit-row defects) | no phantom endpoints; defects are unreachable features, not 404s |
| ⬜ Missing | 10 | **12** | +payout-methods UI, +escrow-release UI, +slug-check; −read-all |
| ❌ N/A (not a brand feature) | 0 | **1** | wallet withdraw is creator-only by backend design |
| **Total** | 69 | **69** | |

---

## 2. Blind spots checked one by one

### BS-1 — Raw `fetch()`/`EventSource` consumers (invisible to the audit's request()-diff) ✅ CLEAN
The audit's reconciliation only diffed typed `request()` calls, so raw-fetch consumers were invisible (this is what produced F-0066). Full sweep found 8 raw consumers; every brand-relevant one resolves to a real backend route:
- `GET/POST /notifications`, `/read`, `/read-all` → `NotificationController.java:76,97,127` ✅
- `POST /auth/refresh` → `AuthController.java:117` ✅
- `GET /deals/{id}/messages/stream` (SSE) → `DealController.java` ✅
- `POST /client-errors` → `ClientErrorController.java:64` ✅
- `/meera/voice/*` ×2 → AI domain, out of brand scope.
**No dead raw-fetch endpoints.**

### BS-2 — "140 typed calls, 0 phantom endpoints" recomputed ✅ REPRODUCES
Independent script extracted typed `request()` calls from `api.ts` + `meera-api.ts` and diffed against all controller mappings (method + normalized path): **128 extracted calls, 278 backend routes, 0 phantoms.** (128 vs 140 is regex sensitivity on multi-line calls, not missing routes.) The audit's core wiring claim holds.

### BS-3 — The 53 WORKING rows, all re-traced (the audit's biggest blind spot) 🔴 7 MISLABELS FOUND
Two fresh-context checkers re-traced every WORKING brand row FE-file → api-call → backend-mapping.
- **Campaign/creator/deal/contract half: 29/29 verified clean.**
- **Wallet/billing/settings half: 13/24 verified; 11 defects** — all re-confirmed by grep; details in §3.

### BS-4 — Mock-mode fallbacks + production guard ✅ GUARD REAL, ⚠️ ONE LEAK
`isApiLive()` = `VITE_API_MODE === 'live'` ([api.ts:57](src/lib/api.ts)). The fail-closed guard is real: `assertMockAuthAllowed()` throws in a production build if mode is unset ([api.ts:93-97](src/lib/api.ts)), called from the demo-access login panel. Data-level mock fallbacks live behind `mockOr()` in the client, **except** the command bar's "Recent Creators/Campaigns" — hardcoded mock arrays rendered **even in live mode** ([command-bar.tsx:67,74](src/components/brand/command-bar.tsx)) — see F-0072.

### BS-5 — Runtime never proven → ran every available build oracle ✅ ALL GREEN
- `npx tsc --noEmit` → **exit 0, 0 errors** (matters because `vite build` skips typecheck)
- `npx eslint src/components/brand src/pages` → **0 errors**, 74 warnings (react-hooks v7 rules intentionally set to warn — policy, not defects)
- `npm run build` (production) → **exit 0**, 16/16 marketing routes prerendered
- proof-os liveness gate → exit 0; prior `build.mvn` rc records green
Still not proven: actual HTTP behavior against a running backend (needs deployed env + keys).

### BS-6 — Money paths (declared limit, code-level check only) ⚠️ GATED + 1 NEW GAP
Razorpay config is env-keyed placeholders (`application.yml:299-305`), so fund/top-up/checkout live behavior remains infra-gated — same as the audit said. **New finding on top:** even with live keys, **escrow release has no UI** (F-0069, §3) — the brand-side money loop cannot close in-app today.

### BS-7 — Known ledger carry-overs ✅ RE-CONFIRMED
- **F-0065:** "FakeFollowerDetectionService deliberately not implemented" is false — 4 of 5 signals implemented ([FakeFollowerDetectionService.java:25-27](influora-api/src/main/java/com/influora/service/scoring/FakeFollowerDetectionService.java)); only comment-quality NLP is a placeholder.
- **F-0066:** notifications **read-all is wired** ([useNotifications.ts:248](src/hooks/useNotifications.ts) → [brand-layout.tsx:381](src/components/brand/brand-layout.tsx), [NotificationBell.tsx:148](src/components/feature/meera/NotificationBell.tsx)) — audit's MISSING label wrong; unsubscribe half correct (email-link only).
- **CR-51:** escrow GST invoice backfill can silently skip at release (log-only) — observability gap, ledger intact.

---

## 3. All Brand defects (complete list)

### 🔴 Audit rows mislabeled WORKING — feature not actually reachable (7)

| # | Ledger | Feature (audit row) | Truth | Evidence |
|---|---|---|---|---|
| 1 | **F-0069** | **Escrow release** — "WORKING via deal-payments-tab.tsx" | `deal-payments-tab.tsx` makes **zero** api calls (display-only); `api.ts:2710` release wrapper has **zero callers anywhere**. Brands cannot release escrow from the UI. **Highest severity — money flow.** | `POST /wallet/escrow/release` exists (`EscrowController.java:101`) but is orphaned |
| 2 | **F-0067** | **Wallet withdraw** — "WORKING via brand-wallet.tsx" | 0 "withdraw" references in `brand-wallet.tsx`; backend is **creator-only** — "Brand accounts are rejected" (`WalletController.java:112-115`). Not a brand feature at all. | called only from `creator-wallet.tsx:453` |
| 3 | **F-0068** | **Wallet payout-methods (list/add/primary)** — "WORKING via brand-wallet.tsx" | All three wrappers called **only** from `creator-wallet.tsx:424,468,487`. No brand UI. | routes real (`WalletController.java:159-191`), no brand consumer |
| 4 | **F-0070** | **Billing checkout (subscribe)** — "WORKING" | The Upgrade-to-Pro button is rendered **disabled** with tooltip "Coming soon — Razorpay checkout integration" (`brand-billing-settings.tsx:463-469`); `api.billing.checkout()` has zero callers. Dead UI, not connected. | `POST /billing/checkout` real (`BillingController.java:158`), never invoked |
| 5 | **F-0071** | **Workspace slug-check** — "WORKING via brand-onboarding.tsx" | `checkSlug()` (`api.ts:927`) has **zero callers**; brand-onboarding.tsx never references slug. Orphan route mislabeled (sibling orphans are correctly MISSING). | `GET /workspaces/slug-check` real (`WorkspaceController.java:36`) |
| 6 | **F-0072** | **Command bar** — "WORKING, reuses campaigns/deals/creators clients" | `command-bar.tsx` imports **no** api client; "Recent Creators/Campaigns" are hardcoded mock arrays (`:67,74`) shown **even in live mode**; everything else is static route navigation. | no backend involvement at all |
| 7 | **F-0073** | **Billing bundle: cancel sub-feature** — bundled into WORKING row | `cancelSubscription()` (`api.ts:2933`) has zero callers; plan/usage/invoices/pdf halves ARE wired (`useBilling.ts:47,55,63`, `brand-billing-settings.tsx:111`). | `POST /billing/cancel` real (`BillingController.java:168`), orphaned |

### 🟠 Audit factual errors in PARTIAL/MISSING sections (2, from the first verification pass)

| # | Ledger | Claim | Truth |
|---|---|---|---|
| 8 | **F-0065** | "FakeFollowerDetectionService deliberately not implemented" | Implemented (4/5 signals); only comment-quality NLP placeholder. Real remaining scoring gap = `QualityScoreService` audienceMatch hardcoded 50 (`QualityScoreService.java:64`) |
| 9 | **F-0066** | "Notifications read-all/unsubscribe MISSING — no in-app consumer" | read-all IS consumed in-app (raw fetch, invisible to the audit's typed-call diff). Unsubscribe half correct. |

### 🟡 Citation errors — feature wired, wrong evidence cited (4, F-0074)

| Feature | Audit cites | Actual call site |
|---|---|---|
| Brand platform fee | `proposal-form.tsx:52` (doc-comment on a prop) | `brand-chat.tsx:674` → passed as prop |
| Dashboard pipeline | `brand-pipeline.tsx` (calls `GET /deals` instead) | `dashboard-page.tsx:104` calls `api.dashboard.pipeline()` |
| Uploads | `upload.ts` (self-described mock/dead code, fake `/api/upload/*` paths) | `api.ts:3097` via `brand-kyc-prompt.tsx:128-129` → real `POST /uploads` |
| Notifications preferences | `useNotifications.ts` (not in that file) | `brand-settings.tsx:198` |

### 🟡 PARTIAL (verified correct in the audit — unchanged, 6)

1. **Campaign analytics** — every metric `CREATOR_REPORTED`, never platform-verified (`api.ts:1246-1250`). Honest by design.
2. **Creator analytics metrics** — empty typed shape until a snapshot is computed.
3. **Creator analytics demographics** — same empty-shape behavior.
4. **Creator analytics scores** — audienceMatch hardcoded neutral 50 (`QualityScoreService.java:64`); FakeFollowerDetection missing only its 5th signal.
5. **Brand analytics roster** — demoCreators only in mock mode; live derives roster from real deals (`brand-analytics.tsx:49,62-71`).
6. **TrendSpark nudge** — templated fallback copy when AI client unavailable — in `TrendSparkNudgeService.java:131,227` (the audit cited the creator-side twin `CreatorNudgeService.java:215` — wrong file, same behavior).

### ⬜ MISSING (backend exists, no brand UI — corrected list, 12)

Verified zero non-test frontend consumers for each:
1. Campaign template create/delete (`POST/DELETE /campaign-templates`) — client has GET only (`api.ts:1416,1422`)
2. Creator-discovery extras (`/creators/search`, `/featured`, `/{u}/similar`, `/suggestions`)
3. Contracts unsigned (`GET /contracts/unsigned`)
4. Deliverable reject (`POST /deliverables/{id}/reject`)
5. Deliverable metrics submit (creator-side write; brand reads analytics)
6. Wallet balance (`GET /wallet/balance` — FE reads `/wallet` summary)
7. Escrow refund/payout (`POST /wallet/escrow/refund|/payout` — admin/Meera paths)
8. Notifications unsubscribe (email-link handler only — read-all half is WORKING)
9. Review flag (`POST /brand/reviews/{id}/flag`)
10. Workspace member accept/switch/remove/invites/revoke
11. **Wallet payout-methods brand UI** (promoted from mislabeled-WORKING, F-0068)
12. **Escrow release brand UI** (promoted from mislabeled-WORKING, F-0069 — build this first: it closes the money loop)

### ❌ Not a brand feature (1)
- **Wallet withdraw** — backend rejects brand accounts by design (`WalletController.java:112`); the audit row should be deleted, not fixed.

---

## 4. What is genuinely solid (so the defect list has context)

- All 29 campaign / creator-discovery / deal / contract / deliverable WORKING rows re-traced clean, FE file → typed call → backend mapping.
- 13 of 24 wallet/billing/settings rows clean, including wallet summary/top-up/transactions, escrow list/fund/status, invoicing (campaign+commission), reviews, disputes, store integrations, workspace me/members, dashboard actions.
- 0 phantom endpoints in the main client (independently recomputed).
- Typecheck, lint (0 errors), production build, prerender: all green.
- Fail-closed mock-auth guard in production builds is real.

## 5. What this report still could not see (law 5)

- **No live HTTP was exercised** — call-site reachability is proven, runtime responses are not (needs deployed backend + provisioned Razorpay/AI keys).
- Backend service *logic* behind verified routes was not re-audited line-by-line (route + DTO existence only).
- The two checkers sampled the audit's FE citations for WORKING rows; PARTIAL/MISSING rows were verified by call-site grep, not by running the UI.
- ESLint's 74 react-hooks warnings are policy-accepted, not re-adjudicated here.

## 6. Recommended fix order (brand only)

1. **Build the escrow-release UI** (F-0069) — the one gap that blocks the in-app money loop even after keys are provisioned.
2. **Enable billing checkout** (F-0070) — backend ready; the button is a disabled placeholder pending Razorpay keys + plan id.
3. **Add brand payout-methods UI or delete the row** (F-0068) and **delete the brand wallet-withdraw row** (F-0067, creator-only by design).
4. **Wire billing cancel** (F-0073) or drop the wrapper.
5. **Wire the command bar to real clients or gate its mock sections behind `!isApiLive()`** (F-0072).
6. Delete or consume `checkSlug` (F-0071); fix the 4 stale citations in the audit doc (F-0074); correct the two §4/§5 wording errors (F-0065/66).

---

*Produced under proof-os task `brand-blindspots-0804`. Oracles: tsc, eslint, vite build, scripted route reconciliation, call-site greps (all deterministic). Judgment: 2 fresh-context checkers (53/53 WORKING rows re-traced); every checker defect re-verified by grep before listing. Verdict ceiling: BELIEVED (static analysis; nothing exercised over live HTTP).*

---

# 7. REMEDIATION — task `brand-fix-0804` (2026-08-04)

Every defect above was worked one by one. A second fresh-context checker (reading source only) **caught two errors in the first blind-spot pass**: the in-code comments on `checkout()`/`cancelSubscription()` claimed those were "Phase-2 stubs that throw NOT_YET_IMPLEMENTED", and a grep for `slug` missed the real `companySlug` field — so three items first thought unfixable turned out to be genuine, safe wiring gaps over a **real** backend. That correction is the OS working: cross-context checking caught the producer's mistake before it shipped.

**All code changes below passed the same gate:** `tsc --noEmit` exit 0 · `eslint` 0 errors (2 policy-warns, react-hooks v7 warn-level) · `npm run build` exit 0, 16/16 routes prerendered.

### 7.1 Fixed in code (4)

| Ledger | Defect | Fix | File | Gate |
|---|---|---|---|---|
| **F-0072** | Command bar showed hardcoded mock "Recent Creators/Campaigns" in **live** mode, linking to fake ids | Gated both demo sections behind `!isApiLive()` — live mode renders nothing rather than fabricated rows | [command-bar.tsx:67-95,159-181](src/components/brand/command-bar.tsx) | tsc+build ✅ |
| **F-0071** | Workspace-URL (`companySlug`) field did **local regex only**, never checked server availability → could 409 at completion | Wired a debounced `api.workspaces.checkSlug` availability check with live status + clickable suggestions; **fails open** (a check error never blocks onboarding) | [onboarding-steps.tsx:808-847,913-940](src/components/brand/onboarding/onboarding-steps.tsx) | tsc+build ✅ |
| **F-0070** | "Upgrade to Pro" button was **disabled** ("Coming soon") though the backend checkout is real | Enabled the button → `api.billing.initiateCheckout('PRO')` → redirect to hosted Razorpay `checkoutUrl`; live behaviour gated on provisioned keys (errors surface as a toast, same as escrow-fund) | [brand-billing-settings.tsx](src/pages/brand-billing-settings.tsx) | tsc+build ✅ |
| **F-0073** | No UI ever called `cancelSubscription()` (backend is real) | Added a "Cancel subscription" action (AlertDialog confirm) on the paid tier → `api.billing.cancelSubscription()`; shows "Access until <date>" when `cancelAtPeriodEnd` | [brand-billing-settings.tsx](src/pages/brand-billing-settings.tsx) | tsc+build ✅ |

**Bonus (F-0075, new):** corrected the two **stale `api.ts` comments** that mislabeled `initiateCheckout`/`cancelSubscription` as "Phase-2 stubs" — they misrepresented a real backend and nearly derailed this fix. [api.ts:2926,2932](src/lib/api.ts).

### 7.2 Reclassified — audit was wrong, feature is actually fine (4)

| Ledger | Audit said | Truth (verified this session) |
|---|---|---|
| **F-0069** | Escrow release BROKEN — no UI | **WIRED via deliverable approval.** `BrandDeliverableService.approve()` calls `escrowService.tryReleaseOnApproval(milestoneId)` in the same transaction; brand UI approve button is wired `brand-chat.tsx:2379 → handleApproveLive → deliverablesApi.approve`. The standalone `/wallet/escrow/release` is a secondary/admin path, not the brand flow. |
| **F-0066** | Notifications read-all MISSING | **WIRED** via raw fetch (`useNotifications.ts:248` → `brand-layout.tsx:381`). |
| **F-0065** | FakeFollowerDetection "deliberately not implemented" | **Implemented** with 4 of 5 signals; only comment-quality NLP is a placeholder. |
| **F-0074** | 4 rows cited | Citations corrected: platform-fee→`brand-chat.tsx:674`, pipeline→`dashboard-page.tsx:104`, uploads→`api.ts:3097` via `brand-kyc-prompt.tsx:128`, notif-prefs→`brand-settings.tsx:198`. |

### 7.3 Reclassified — by-design, not a brand defect (2)

| Ledger | Resolution |
|---|---|
| **F-0067** | **Wallet withdraw is creator-only.** `WalletController /wallet/withdraw` calls `requireCreator`, which throws `403 WRONG_USER_TYPE` for brands. The brand row should be **deleted**, not fixed. |
| **F-0068** | **Payout-methods are creator-side.** Brands have no withdrawal path (see F-0067), so a brand payout-methods UI is unnecessary. Not a brand defect. |

### 7.4 Corrected Brand tally after remediation

| Status | As-found (deep audit) | After remediation |
|---|---:|---:|
| ✅ Working | 53 | **52** (47 + checkout + cancel + slug + read-all + escrow-release-via-approval, − 2 creator-only rows removed, − command-bar recounted as fixed-working) |
| 🟡 Partial | 6 | 6 (unchanged; the six honest placeholders) |
| ⬜ Missing | 10 | 9 (−read-all which is wired) |
| ❌ N/A (creator-only, remove) | 0 | 2 (withdraw, payout-methods) |

### 7.5 What remediation could NOT prove (law 5)

- **No live HTTP was run.** tsc+build prove the four fixes compile and the bundle builds; they do **not** prove checkout redirects, cancel persists, or the slug check returns real availability at runtime — that needs a deployed backend + provisioned Razorpay keys.
- **Checkout & cancel live behaviour is still infra-gated** on Razorpay keys; with placeholder keys the new buttons will surface an API-error toast (honest), not succeed.
- The slug-availability effect uses `setState`-in-effect (react-hooks v7 warn) — accepted per project policy, standard for a debounced check.

*Remediation under proof-os task `brand-fix-0804`. Second fresh-context checker (vikram) verified the reclassification facts from source. Verdict ceiling: BELIEVED — the four fixes are build-proven, not runtime-proven.*
