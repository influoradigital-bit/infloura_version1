# SHARED CONTEXT — Active Pipeline
**Last update:** 2026-07-30 by Arjun  
**Current task:** Brand audit error fixes (loop mode)  
**Status:** ORCHESTRATION_STARTED

---

## AUDIT FINDINGS → FIX PIPELINE

From: Brand Deep Audit (2026-07-30, 84.5% aligned, 42 features)  
**Razorpay keys:** deferred per Swapnil (add later in HTML, not in this loop)  
**Code-fixable errors to solve:** 10 items

### BROKEN (P1/P2 — quick code fixes)
1. **BR-34 Notifications bell** (P1) — FE hook reads `body.notifications` but backend now wraps in `ApiResponse.data`  
   - Fix: `src/hooks/useNotifications.ts` unwrap `.data`  
   - Owner: **Ananya** (frontend)

2. **BR-37 Report export** (P2) — Backend exists, zero FE wiring  
   - Fix: add export button + `api.ts` method + wire to `brand-campaign-detail.tsx` or `brand-analytics.tsx`  
   - Owner: **Ananya** (frontend)

3. **BR-14 Campaign templates** (P1 MISSING) — Backend fully built, never called from UI  
   - Fix: add templates tab to `brand-campaigns.tsx`, wire `api.campaignTemplates.*` methods  
   - Owner: **Ananya** (frontend + api client)

### PARTIAL (P1 — medium lifts)
4. **BR-05 Brand settings** (P1) — Only workspace-info + email-switch wired; 8 other controls are local-state stubs  
   - Fix tier 1: wire payments/auto-recharge backend (if exists) OR hide disabled controls  
   - Owner: **Vikram** (backend check) → **Ananya** (FE cleanup)

5. **BR-03 Forgot/reset password** (P1) — Backend reset exists but no FE `/reset-password` page  
   - Fix: create `src/pages/brand-reset-password.tsx`, add `api.auth.resetPassword`, route in `App.tsx`  
   - Owner: **Ananya** (frontend)

6. **BR-18 Creator scoring in discovery** (P1) — Scores computed but not surfaced in grid/profile  
   - Fix: `CreatorMapper.java` add score fields to `toResponse`, update DTOs, FE renders QualityScoreBadge in grid  
   - Owner: **Vikram** (DTO) → **Ananya** (badge component)

### PARTIAL (P2 — lower priority)
7. **BR-35 Help center** (P2) — Functional page but all copy is 'TODO Nisha'  
   - Fix: real help copy  
   - Owner: **Nisha** (content) → **Ishaan** (write copy)

8. **BR-42 GARM brand-safety** (P2) — Model-real but dark by default (`enabled=false`)  
   - Decision gate: enable flag OR defer?  
   - Owner: **Ash** (AI review) → **Priya** (config decision)

9. **BR-27 Wallet top-up** (P0 money-path, DEFERRED) — needs live Razorpay keys  
10. **BR-31 Payout** (P0 money-path, DEFERRED) — needs live RazorpayX keys + TDS  

---

## LOOP PROTOCOL (multi-agent, until done)

**Orchestrator:** Arjun  
**Decisions:** Swapnil (business) + Priya (arch/config)  
**Execution:** specialist-only (Ananya FE, Vikram BE, Nisha/Ishaan content, Ash AI, Kavya QA, Meera verify)

**Loop steps:**
1. Arjun reads audit → breaks into subtasks → writes here  
2. Route decision gates to Swapnil + Priya  
3. Assign code fixes to Ananya + Vikram  
4. Each fix: code → Kavya QA → Meera local-run verify → mark DONE  
5. Loop continues until all 10 items = DONE or DEFER  
6. Final: Arjun updates audit HTML, archives to wiki/  

**Next:** dispatch to Priya + Swapnil for decision gates.

---

## LIVE HANDOFFS (loop iteration 3)

Swapnil priority order (this loop): BR-03 → BR-34 → BR-14 → BR-18 → BR-37. Backlog: BR-05, BR-35, BR-42.

```
Arjun → Vikram | CR-48 + CR-49 security fixes (Priya + Kabir findings) | influora-api/.../AuthRateLimitFilter.java, AuthService.java, AccountController.java, RefreshTokenRepository.java, RefreshToken.java, AuthRateLimitFilterMePasswordBucketTest.java (new), AuthServiceTest.java | QA_PASS | NEXT: commit → Meera build/verify
```

**Kavya verdict: PASS with ADVISORY.** All 7 files pass QA gate. One recommendation (not a blocker): Vikram should open follow-up ticket to lower "sensitive" bucket limit from 10→5 for /me/password, or create dedicated password-change bucket with tighter limit for production brute-force hardening.

Loop rule: each fix → Kavya QA → Meera build/test → mark DONE. Loop continues until BR-03/34/14/18/37 all DONE.

---

## PRIYA DECISIONS — 4 gates (2026-07-30)

New locked standards in `TECH-STACK.md`: **UI Honesty**, **Score Exposure**, **AI Cost Gates**. Read them before starting 1/2/4 — Kavya enforces.

### BR-05 Brand settings — ANSWER IS PER-CONTROL, NOT (a) OR (b)

Neither. "Wire everything" and "hide everything" are both wrong; the 8 controls fall into three buckets.

**WIRE NOW (backend already exists, FE-only work — Ananya):**
| Control | Endpoint (already live) |
|---|---|
| Member roster | `GET /workspace/members` — `api.workspaceMembers.list()` exists, `api.ts:854`. The TODO at `brand-settings.tsx:180-183` saying no endpoint exists is **false**. |
| Team invite | `POST /workspace/members/invite` — `WorkspaceMemberController.java:51`. Needs an `api.ts` client method only. |
| Logout all devices | `POST /auth/logout` already revokes **every** refresh token — `AuthController.java:136`. `api.auth.logout` exists. Wire the button. |

**BUILD SMALL BACKEND (Vikram, one endpoint):**
- Change password → new `POST /me/password` (current + new, re-auth on current). No in-session change path exists today; `PATCH /users/me` does not take a password. Table stakes, bounded, build it.

**REMOVE (no backend, and not buildable in this loop):**
- Payment methods — `brand-settings.tsx:417-420` renders a **hardcoded fake** `Credit Card ****4242`. Delete it. `brand-billing-settings.tsx:611-635` already has the honest placeholder; that page is the single owner of payment UI. Depends on Razorpay → deferred anyway.
- Auto-recharge — needs a Razorpay mandate. Deferred with the rest of Razorpay.
- View active sessions — needs a session registry we do not have.
- Delete workspace — deliberately not built. `AccountController.java:23-26` documents that account deletion does **not** cascade into workspaces, and workspace deletion touches escrow holds, signed contracts and GST invoices. That is a data-lifecycle project with legal surface, not a settings toggle. Never ship this as a button that 404s.

**PRIORITY-1 SECURITY FIX inside BR-05 — do this first:**
`brand-settings.tsx:508-511` — the 2FA `Switch` is **interactive and persists nothing**. Every other unbacked control is disabled or dead; this one flips, sticks for the session, and lets a brand owner believe their account is protected. There is no user-facing 2FA backend (only the separate `/admin/auth` realm). **Remove the toggle.** Real 2FA goes to backlog with its own spec. This is a security misrepresentation, not a stub — treat as P1 regardless of BR-05's overall P-level.

**Granular notifications — CONDITIONAL, default NO.** `GET/POST /notifications/preferences` exists and accepts arbitrary `eventType`, but the FE only sends `"*"` and **no emitter reads a category**. Wiring the toggles without emitter support means a user who disables "Bid Notifications" still gets them — trading a disabled control for a lying one. Only wire if Vikram does the emitter side in the same change. Otherwise leave disabled+captioned; it is already honest.

### BR-18 Creator scoring — APPROVED, no separation violation, but the proposed fix is wrong

**Not a violation.** Scores are persisted in `creator_scores` (`V22__creator_scores.sql`), written by a nightly job. Discovery reading them is a denormalized read, not analytics-in-discovery. And discovery genuinely cannot reuse the analytics route: `GET /analytics/creators/{id}/scores` calls `MetricsAuthorizationService:65-75` and **403s unless the workspace holds an active Meta OAuth token for that creator** — every discovery card would 403.

**But do not "add score fields to `CreatorMapper.toResponse`" as written.** Four binding constraints:

1. **Reuse the existing shape.** `DiscoveryDtos.CreatorScores(quality, authenticity, brandSafety)` already exists (`DiscoveryDtos.java:59-60`) and `CreatorDiscoveryService.buildScores:787-793` already maps it (incl. `authenticity = 100 - fakeFollower`). Add a nullable nested `scores` object. Do not add loose fields.
2. **Batch fetch is mandatory.** `toResponse` feeds **four** endpoints, not two — `/creators`, `/creators/{id}`, `/creators/featured`, `/creators/suggestions` (`CreatorDiscoveryService.java:185, 468, 487, 539, 605`). A per-creator lookup in the `.map()` at `:181-190` = 20 queries/page, 100 at max limit. `CreatorScoreRepository` has exactly one finder; Vikram adds a greatest-n-per-group batch finder over `idx_creator_scores_creator_time`. Per-row lookup = automatic Kavya reject.
3. **`null` renders as "not yet scored".** `brandSafety` is `null` for **everyone** until BR-42 (see below), and quality/authenticity are null for any creator never polled by Meta. Ananya must design the unscored state first — it is the common case, not the edge case. Do not copy `AdminCreatorService:564-569`, which fabricates `BigDecimal.ZERO`.
4. **Free bug fix, same change:** `brand-creator-profile.tsx:319` hardcodes `authenticity: 0` and `:746-768` renders it as **"0%" inside a ring captioned "Excellent authenticity"**. That is live misleading UI today. `100 - fakeFollowerScore` is exactly the number that widget wants — kill it with this fix.

Note `GET /creators/profile/{usernameOrId}` (`CreatorController.java:159`) already returns scores and has **zero FE callers** — useful reference for the mapping, not a substitute for the batch work.

### BR-42 GARM — (b) DEFER, and the reason is not the model

Do not flip the flag. Ash is right that Sonnet is overkill, but there is a harder blocker underneath:

- **`max_tokens=4096` is arithmetically too small.** Chunks are 25 items (`BrandSafetyScoreService.java:76`) and the schema requires all 10 GARM categories **each with a `rationale` string** per item (`schemas.py:509-514`). That is ~11k output tokens into a 4096 ceiling. Truncation → incomplete `tool_use` → route returns 502 → Java's fail-safe (`:202-206`) discards the **whole creator**. Flipping today produces mass NULLs that look like "scoring is broken".
- **Cost blows the shared ceiling.** ~$20 per run at cap 100 against `AI_DAILY_SPEND_CEILING_USD = 15.0`, which is **global across every AI feature**. Enabling it starves Meera mid-day. Per TECH-STACK AI Cost Gates, this is now also a **Swapnil call**, not mine — ~₹50k/mo is a business decision. Arjun: route the number to him once we have a measured one.

**Authorized now (Ash, no eval needed, no flag flip):**
1. Drop the per-post `rationale` from the required schema — `BrandSafetyScoreService:269-285` flattens to category names and discards every rationale (`:106-117` admits no reader exists). We are paying Sonnet output tokens for ~250 strings per creator that nothing reads. This alone likely fixes the truncation *and* most of the cost.
2. Add `cache_control` on the ~1.5k-token static system+schema prefix (currently disabled by design, `prompt/brand_safety.py:82-86`).
3. Add a per-`media_id` result cache — media rows are immutable, so re-scoring on re-target is pure waste.
4. Set an explicit low `temperature` (currently unset, `providers/claude.py:338-345`).

Then re-measure. If cost lands inside headroom, the Haiku A/B becomes optional rather than blocking. **BR-42 stays out of this bug-fix loop** — it is a cost/eval project.

### BR-14 Campaign templates — NOT SHELVED, but NOT a tab

**Do not add a Templates tab to `brand-campaigns.tsx`.** That file is a 5-line wrapper; the real page is `src/components/brand/campaigns/campaigns-list.tsx`, and its `Tabs` at `:516-528` are a **status filter** (`ALL/ACTIVE/DRAFT/...`) with no `TabsContent` at all. Adding a section tab there means splitting one state into two orthogonal ones and reworking the mobile `Select` mirror, stat cards, search and sort. Wrong semantics, high surgery.

**Ship it at campaign creation instead.** `src/pages/brand-new-campaign.tsx` is already a Step-0 "how do you want to start" chooser (`TYPE_OPTIONS:27-47`, 3-card grid `:77-131`). "Start from a template" is a fourth card. Near-zero structural change.

**Phase 1 — now, Ananya, zero backend:**
- `api.ts` gains `campaignTemplates.list()` / `.get(id)` → `GET /campaign-templates`, `GET /campaign-templates/{id}`.
- Template picker card → fetch template → prefill the form.
- `CampaignForm` (`campaign-form.tsx:150`) currently takes only `{ campaignId? }` and seeds from a module-level `initialFormData:102`. Add an `initialValues?: Partial<CampaignFormData>` prop. This is the actual work — there is no apply-template REST route, so prefill is client-side. Do **not** add `templateId` to `POST /campaigns`; that would duplicate Meera's `CreateCampaignExecutor:207-250`.
- **No plan gate needed.** Read is free to all tiers — 4 SYSTEM presets are seeded (`V20260714150000__campaign_templates.sql:38-116`) and only `POST` (save-as-template) carries `@RequiresPlan`. A Free brand must see and use the presets.

**Phase 2 — later:** "Save as template" on campaign detail, Pro-gated. Blocked on two things: there is **no FE plan-gating pattern anywhere** in `src/` (this would be the first `usePlanFeature` hook), and a backend bug.

**Vikram, before Phase 2:** `CampaignTemplateService:86-104` never copies `source.getCampaignType()`, so every CUSTOM template saves with `campaignType = null` and silently degrades to `STANDARD` on apply (`CreateCampaignExecutor:208`). Only bites Phase 2, but fix it with the tests below.

**Vikram, before Phase 1 FE lands:** there are **zero** tests for this feature. Add `CampaignTemplateControllerTest` covering list/get visibility, the 404-not-403 cross-workspace guard (`requireVisible:150-162`), `SYSTEM_TEMPLATE_IMMUTABLE`, and the 402 upgrade gate. Also kill the stale "no `@RequiresPlan` endpoint exists yet" comments at `PlanGateInterceptor.java:18-21` and `PlanGateInterceptorTest.java:22` — both are false.

```
Priya → Ananya | BR-14 Phase 1 template picker (NOT a tab) + BR-05 wire roster/invite/logout-all, REMOVE 2FA toggle + fake payment cards | src/pages/brand-new-campaign.tsx, src/components/brand/campaigns/campaign-form.tsx, src/pages/brand-settings.tsx, src/lib/api.ts | DECIDED | NEXT: build → Kavya QA
Priya → Vikram | BR-18 nested scores + batch greatest-n-per-group finder; BR-05 POST /me/password; BR-14 campaignType bug + template tests | influora-api/.../CreatorMapper.java, CreatorScoreRepository.java, CampaignTemplateService.java | DECIDED | NEXT: build → Kavya QA
Priya → Ash    | BR-42 authorized cost fixes only, NO flag flip | influora-ai/app/tools/schemas.py, prompt/brand_safety.py, config.py | DECIDED | NEXT: measure real per-run cost → Arjun → Swapnil
Priya → Arjun  | BR-42 enable decision escalates to Swapnil (shared $15/day ceiling, ~$600/mo) | TECH-STACK.md | ESCALATED | NEXT: Swapnil call after Ash re-measures
```

---

## MEERA VERIFICATION REPORT — 2026-07-30

Task: BR-03 reset-password page + BR-34 notifications unwrap (post-Kavya QA)
Files verified: `src/hooks/useNotifications.ts`, `src/lib/api.ts`, `src/pages/brand-reset-password.tsx` (new), `src/App.tsx`

### Results
- `npx tsc -p tsconfig.json --noEmit` (via `npm run typecheck`): ✅ PASS — 0 errors, no output
- `npm run build` (`vite build` + `postbuild` prerender): ✅ PASS — exit code 0, built in 37.68s, 16/16 marketing routes prerendered successfully
  - Pre-existing, unrelated: esbuild warning "Duplicate key baseUrl" in root `tsconfig.json:20-21`, and a >500kB chunk size warning on `index-B5H5Ly6R.js` (2.7MB) — neither blocks the build, not caused by this change, not filed as a new bug here.
- Route wiring check: `src/App.tsx:10` imports `BrandResetPasswordPage`, `:157` registers `<Route path="/reset-password" element={<BrandResetPasswordPage />} />` — confirmed present.
- Unit tests: no test files exist for either change (`grep` for `useNotifications|reset-password|resetPassword` across `*.test.{ts,tsx}` returned zero matches). Did not run `npx vitest run` — nothing scoped to hit. Not a gate failure, just no coverage yet.
- Did not run `npm run dev` + curl (no backend API routes changed — pure FE hook/page/route diff; `/api/products`, `/api/health` don't apply here).

### VERDICT: ✅ ALL PASS — Ready for Swapnil/Arjun sign-off on BR-03 + BR-34. No fix loop needed.

Note: no test coverage added for `useNotifications` unwrap or the reset-password page — flagging for backlog, not blocking.

---

## MEERA VERIFICATION REPORT — 2026-07-30 (iteration 2, post-Vikram test add)

Task: BR-18 creator scoring + BR-14 templates + BR-05 settings (combined FE+BE diff), Kavya PASSED after Vikram added missing tests.
Files verified: FE — `src/lib/api.ts`, `src/lib/types.ts`, `src/pages/brand-settings.tsx`, `src/pages/brand-creator-profile.tsx`, `src/pages/brand-new-campaign.tsx`, `src/pages/brand-campaign-detail.tsx`, `src/pages/brand-help.tsx`, `src/components/brand/discover/creator-discovery.tsx`, `src/components/brand/campaigns/campaign-form.tsx`. BE — `CreatorScoreMath.java`, `CreatorDiscoveryService.java`, `CreatorMapper.java`, `CreatorScoreRepository.java`, `AnalyticsService.java`, `AccountController.java`, `AuthService.java`, `UserDtos.java`, `CampaignTemplateService.java`, `CreatorDtos.java`.

### FRONTEND
- `npm run typecheck` (`tsc -p tsconfig.json --noEmit`): ❌ **FAIL** — exit code 2, 10 errors, all in **`src/pages/brand-help.tsx`** (not in the files Vikram/Ananya were asked to touch for this iteration, but it IS in the diff — untracked line-count churn shows it modified).
  ```
  src/pages/brand-help.tsx(48,200): error TS1005: ',' expected.
  src/pages/brand-help.tsx(48,202): error TS1005: ',' expected.
  ... (8 more cascading TS1005/TS1003/TS1002 errors on the same line)
  ```
  **Root cause** (`brand-help.tsx:48`): an un-escaped apostrophe inside a single-quoted string literal breaks parsing:
  ```
  body: 'Meera is your AI cofounder. ... Just open the chat and ask — she's available anytime from the sidebar.',
  ```
  The `she's` closes the string early at the `'` before `s`, then the rest of the sentence is parsed as bare tokens → cascading errors through `:49`.
- `npm run build` (`vite build`): ❌ **FAIL** — exit code 1, same root cause, confirmed by esbuild:
  ```
  [vite:esbuild] Transform failed with 1 error:
  src/pages/brand-help.tsx:48:201: ERROR: Expected "}" but found "s"
  ```
  Pre-existing unrelated warning (not new, not blocking on its own): duplicate `baseUrl` key in `tsconfig.json:20-21`.

**FE VERDICT: ❌ FAIL — build-breaking syntax error, blocks the entire frontend bundle, not just brand-help.tsx.** This is a one-character fix (escape the apostrophe or switch to a template literal) but per protocol I do not fix it. Routing back via Arjun to whoever owns `brand-help.tsx` in this iteration.

### BACKEND
- `mvn -q -o compile` (offline): ✅ **PASS** — exit code 0, clean compile, no resolution issues.
- `mvn -o -Dtest=CreatorDiscoveryServiceTest,AnalyticsServiceTest,CampaignTemplateControllerTest test`: ✅ **PASS** — exit code 0, **32/32 tests, 0 failures, 0 errors**, BUILD SUCCESS (21.8s).
  - `AnalyticsServiceTest`: 9/9 pass — confirmed the corrected assertion at `:230` is `assertEquals(new BigDecimal("7.50"), result.authenticityScore())` against a stubbed `fakeFollowerScore(92.50)` — the 92.50→7.50 inversion fix is verified, not just present.
  - `CreatorDiscoveryServiceTest`: 14/14 pass — confirmed all 3 named inversion tests exist and pass: `testGetInvertsFakeFollowerScoreIntoAuthenticity` (:254), `testGetNullFakeFollowerScoreStaysNullAuthenticity` (:279), `testGetNoScoreRowYieldsNullScores` (:302).
  - `CampaignTemplateControllerTest`: 9/9 pass — confirmed `@DisplayName` for the 404-not-403 cross-workspace guard (:132), `SYSTEM_TEMPLATE_IMMUTABLE` (:158, asserts `ex.getCode()`), and the 402 upgrade gate via real `PlanGateInterceptor` (:193 `saveAsTemplate_freePlanBlockedWith402`).

**BE VERDICT: ✅ PASS — compiles clean, all 3 required test classes green with the exact named cases requested.**

### OVERALL: ❌ BLOCKED on FE — do not ship. BE is ready but the combined diff can't go out with a broken frontend build.
Routing back to Arjun: single-file, single-character fix needed in `src/pages/brand-help.tsx:48` (escape `she's` → `she\'s`, or convert the string to a template literal) before this can go to Swapnil review. No other issues found in either stack.

---

## MEERA VERIFICATION REPORT — 2026-07-30 (iteration 2 re-verify, FE-only, post-Nisha fix)

Task: Nisha converted all 5 SECTIONS bodies in `src/pages/brand-help.tsx` to backtick template literals to fix the build-breaking apostrophe. Backend already PASSED (32/32) in prior run — not re-run per Arjun's instruction.
Files verified: `src/pages/brand-help.tsx`.

### FRONTEND
- `npm run typecheck` (`tsc -p tsconfig.json --noEmit`): ✅ **PASS** — exit code 0, 0 errors.
- `npm run build` (`vite build`): ✅ **PASS** — exit code 0. 4771 modules transformed, built in 37.13s, postbuild prerender 16/16 routes snapshotted successfully. No `${...}` accidental-interpolation errors from the backtick conversion. The prior cascading TS1005/TS1003/TS1002 cascade at `brand-help.tsx:48` is gone.
  - Only warning present: pre-existing duplicate `baseUrl` key in `tsconfig.json:20-21` (unrelated, not new, not blocking) and a chunk-size advisory on `index-CKwZ9yjW.js` (2.7 MB, pre-existing, unrelated to this fix).

**FE VERDICT: ✅ PASS — both commands exit 0, cascade confirmed gone.**

### OVERALL: ✅ ITERATION 2 CLOSED. FE fix verified green; BE already PASSED (32/32) in prior run. Combined diff (BR-18/BR-14/BR-05 + brand-help fix) is clear to proceed to Swapnil review per Arjun.

---

## MEERA VERIFICATION REPORT — 2026-07-30 (CR-48 + CR-49 security fixes, post-Kavya PASS)

Task: Final BE verify for Vikram's 2 security fixes (Kavya PASSED with advisory). Backend-only, Spring Boot/Maven.
Files verified: `AuthRateLimitFilter.java`, `AuthService.java`, `AccountController.java`, `RefreshTokenRepository.java`, `RefreshToken.java`, `AuthRateLimitFilterMePasswordBucketTest.java` (new), `AuthServiceTest.java`.

### Results
- `mvn -q -o compile` (offline): ✅ **PASS** — exit code 0, clean compile, no Java errors, no dependency resolution issues.
- `mvn -o -Dtest=AuthRateLimitFilterMePasswordBucketTest,AuthServiceTest test`: ✅ **PASS** — exit code 0, **30/30 tests, 0 failures, 0 errors**, BUILD SUCCESS (5.5s).
  - `AuthRateLimitFilterMePasswordBucketTest`: 4/4 pass (0.145s) — confirms `/me/password` routes to the sensitive bucket, GET/PATCH `/me` and DELETE `/me/account` correctly resolve to null bucket.
  - `AuthServiceTest`: 26/26 pass (1.888s) = 23 existing + 3 new (keeps-caller-session, fallback-revoke-all, wrong-password-revokes-nothing), all confirmed present and green.
- Regression sanity `mvn -o -Dtest=CreatorDiscoveryServiceTest,CampaignTemplateControllerTest test`: ✅ **PASS** — exit code 0, **23/23 tests, 0 failures, 0 errors**, BUILD SUCCESS (11.9s). `CreatorDiscoveryServiceTest` 14/14, `CampaignTemplateControllerTest` 9/9 — no regression from the CR-48/CR-49 changes.

### VERDICT: ✅ BE PASS — no fix loop needed, nothing to route back to Vikram.
All three Maven runs exit 0 with exact expected counts (4, 26, 14, 9). No unrelated pre-existing failures encountered. Ready for Swapnil/Arjun sign-off; local verification gate cleared for CR-48 + CR-49.

---

## MEERA VERIFICATION REPORT — 2026-07-30 (CR-51 escrow/contract + F-SHIP-BRAND, combined, post-Kavya PASS)

Task: Local verify gate for two uncommitted Kavya-PASSED diffs on `fix/brand-audit-remediation` ahead of Kabir red-team on CR-51 (money-path).
Files verified: BE — `ContractService.java`, `EscrowService.java`, `application.yml`, `ContractServiceDeliverableMaterializationTest.java` (new), `EscrowServiceTest.java`, `EscrowServiceReleaseTest.java`. FE — `src/lib/api.ts`, `src/pages/brand-chat.tsx`.

### BACKEND (influora-api/, mvn -o)
- `mvn -o -q compile`: ✅ **PASS** — exit code 0, clean, no resolution issues.
- `mvn -o test` (full suite): ✅ **PASS** — exit code 0, BUILD SUCCESS (51.1s). **`Tests run: 1559, Failures: 0, Errors: 0, Skipped: 3`** — confirms Vikram's claimed counts exactly, independently run, not restated.
  - Touched classes isolated from the full-suite log:
    - `ContractServiceDeliverableMaterializationTest`: 1/1 pass (0.596s)
    - `ContractServiceTest`: 29/29 pass (0.231s)
    - `EscrowServiceTest`: 33/33 pass (0.277s)
    - `EscrowServiceReleaseTest`: 8/8 pass (0.094s)
    - `DealServiceTest`: 37/37 pass (0.281s, regression sanity)
  - Stack-trace-looking lines for `mockIdempotencyExecuteOnce`, `setCutover`, `testReleaseSurvivesInvoiceCreationFailure` in the raw log are exercised-exception-path log output from within passing test methods, not failures — each surrounding `Tests run` line confirms 0 Failures/0 Errors.

**BE VERDICT: ✅ PASS**

### FRONTEND (repo root, npm)
- `npm run typecheck` (`tsc -p tsconfig.json --noEmit`): ✅ **PASS** — exit code 0, 0 errors.
- `npm run build` (`vite build` + postbuild prerender): ✅ **PASS** — exit code 0, built in 50.90s, 16/16 marketing routes prerendered. No new errors. Same two known, non-blocking advisories as prior iterations (unrelated to this diff): duplicate `baseUrl` key in root `tsconfig.json:20-21`, and >500kB chunk-size warning (`index-CJNE8Irs.js`, 2.7MB / `PerformanceMonitor` 892KB).

**FE VERDICT: ✅ PASS**

### OVERALL: ✅ ALL PASS — both stacks verified independently, exact test counts confirmed, no regressions. Nothing routed back to Vikram or Ananya. Ready for Kabir red-team on CR-51 (money-path).

---

## KABIR RED-TEAM — CR-51 (2026-07-30) — ❌ BLOCK

Money-path release gate is INERT by construction — CR-51 does NOT achieve its stated objective.
- **HIGH-1 (BLOCK):** `ContractService.materializeDeliverables` (:397) is the ONLY producer of `Deliverable` rows and never sets `milestone_id` (col defaults NULL, no backfill, no `setMilestoneId` anywhere). The gate `EscrowService.assertReleaseConditionSatisfied` (:1118) queries `deliverableRepository.findByMilestoneId(...)`, which therefore ALWAYS returns empty → `linked.isEmpty()` → fail-open (warn) on EVERY release, post-cutover included. Enabling the cutover changes nothing. ON_POSTED / ON_VERIFIED_METRICS conditions are unenforceable; escrow releases with no content gate — the exact gap CR-51 claims to close. 1559 tests miss it (gate test mocks `findByMilestoneId`; materialization test never asserts `milestone_id`).
  - Fix direction: link deliverable→milestone (set `Deliverable.milestoneId` at materialization/funding), OR re-key the gate off `collaborationId`+slot. Then add an integration test that funds→materializes→releases WITHOUT mocking the repo.
- **MED-1:** A2 observability worse than flagged — the warn fires on 100% of post-cutover releases (not just anomalies), so it cannot signal abuse.
- **LOW-1:** A1 boundary `isAfter()` strict — created_at == cutover fails open (sub-second, moot while gate inert).
- **PASS:** config parse (Instant.parse fail-safe, no injection/ReDoS/tz-drift), materialization tenant scoping (no IDOR — all keyed off passed Collaboration), release entrypoints (both funnel through gate). Admin/dispute release bypasses gate but is pre-existing + arbiter-gated, out of CR-51 scope.

Full evidence in Kabir's return message to Arjun.

---

## PRIYA DECISION — CR-51 HIGH-1 architecture (2026-07-30)

**RULING: OPTION B — re-key the release gate to `collaborationId`. Option A REJECTED.**

Cardinality (why A is wrong): milestones (`ContractService.java:288-301`) come from `req.milestones()` keyed by `sequenceNo` = brand-chosen payment installments; deliverables (`:390-408`) come from proposal metadata keyed by monotonic `slotIndex`. Independent sources, no linking field, N⊥M (e.g. 1 lump-sum milestone : 3 reels). No principled slot→milestone rule exists — any mapping would be invented and break the moment N≠M, which is the norm. Option A cannot be implemented soundly.

Option B is safe: gate reads all deliverables for `milestone.getCollaborationId()` (available `EscrowService.java:582/605`), reuses existing `findByCollaborationIdOrderBySlotIndexAsc` (`DeliverableRepository:25`). Statuses monotonic → strictly conservative (never releases early, never starves a later milestone). No schema change, no backfill, `milestone_id` stays unused.

A1: keep strict `isAfter` (tie fails open = correct). A2/MED-1: self-resolves — under B the lookup key IS populated, so the empty-warn only fires for genuinely-zero-deliverable collabs (real rare signal), not 100% of releases. Acceptable.

```
Priya → Vikram | CR-51 re-code: gate off collaborationId (Option B), NOT milestone_id; do NOT touch materialization; add no-mock funds→materialize→release integration test | influora-api/.../EscrowService.java (assertReleaseConditionSatisfied only), new EscrowReleaseGateIntegrationTest | DECIDED | NEXT: build → Kavya QA → Kabir re-verify
Priya → Arjun  | CR-51 unblocked with Option B; no migration, no ContractService change; test is the gate | EscrowService.java | DECIDED | NEXT: route spec to Vikram
```

---

## PRIYA DECISION — CR-51 STEP-3 (zero-deliverable new deals) — 2026-07-30

**RULING: SHIP BOTH HALVES + one refinement. Swapnil's forbid stands — no legitimate flow relies on zero-deliverable escrow.**

### Audit (funded-path safety)
- **A. @NotEmpty scoping = SAFE.** `CreateDealRequest`/`CounterRequest` are imported/consumed ONLY by `DealController` (POST /deals→createProposal, POST /{id}/counter→counter). No HYPE/Meera/campaign-app/admin/dispute path touches these DTOs. Constraint hits only the direct-deal negotiation entry points — exactly Swapnil's target.
- **B. Gate-throw = SAFE for all funded paths.** `PaymentMilestone` rows are created in EXACTLY ONE place: `ContractService.generate` (:288-302) — direct-deal only (`PayoutService` only reads/updates them post-release). The gate `assertReleaseConditionSatisfied` is reachable ONLY from `releaseInternal` (:628), i.e. `release()` + `tryReleaseOnApproval()`, both milestone-keyed. **HYPE/Meera holds (`ConfirmLaunchExecutor`:261-262, `findByCampaignId`, campaign-level, NO milestone) can never reach the gate** — they release only via dispute settlement (adminRelease/Refund/Split), which does NOT call the gate. So throwing strands nothing HYPE/campaign-level. Within direct-deal, refund() also bypasses the gate → funds always recoverable, never permanently stranded.
- **C. usageRights/exclusivity = no conflict.** `DealDtos`:47-55 — exclusivity was removed 2026-07-26 (never persisted/enforced); `usageRights` persists onto Collaboration but ONLY alongside a deliverables set. No standalone "pay for usage-rights/exclusivity, zero deliverable" endpoint exists. @NotEmpty breaks no real flow.

### Refinement (why @NotEmpty alone is insufficient)
`materializeDeliverables` (`ContractService`:392-406) loops `qty` times per slot → a slot with `qty=0` materializes ZERO rows even with a non-empty list. Also the `deliverables` list has NO `@Valid`, so `DeliverableSlot`'s own `@NotBlank/@NotNull` are currently DORMANT. To truly guarantee ≥1 protected deliverable, cascade validation + require positive qty.

### Vikram spec
1. `web/dto/deal/DealDtos.java` — imports `NotEmpty`, `Positive`, `jakarta.validation.Valid`.
   - `CreateDealRequest.deliverables` (:61): `@NotEmpty(message="At least one deliverable is required") @Valid List<DeliverableSlot> deliverables`
   - `CounterRequest.deliverables` (:84): same.
   - `DeliverableSlot.qty` (:66): `@NotNull @Positive Integer qty` (keep `@NotBlank String type`). `@Valid` on the list is what activates these.
2. `service/EscrowService.java` — `assertReleaseConditionSatisfied`, the `if (deliverables.isEmpty())` branch (:1130-1144): replace `log.warn + return` with `throw new ApiException("RELEASE_CONDITION_NOT_MET", "This escrow-funded deal has no deliverables to protect; release is blocked (CR-51 step 3).", HttpStatus.CONFLICT)`. **MUST reuse code `RELEASE_CONDITION_NOT_MET`** so `isExpectedReleaseSkip` (:552-563) still whitelists it → `tryReleaseOnApproval` skips gracefully (approval succeeds, hold stays FUNDED) instead of blowing up the approve() txn. Keep the throw INSIDE the existing `isPostCutover` guard (:1121 early-returns for legacy) → legacy pre-cutover unaffected; blank/unset `cutover-instant` ⇒ gate fully disabled ⇒ ships DISABLED BY DEFAULT. Do NOT add a new error code.

### Tests (mandatory)
- **Validation web test** (`DealControllerTest`): POST /deals `deliverables:[]`→400; single slot `qty:0`→400 (proves @Positive+@Valid cascade); POST /{id}/counter empty→400; happy path ≥1 slot qty≥1→201.
- **NO-MOCK integration** (extend `EscrowReleaseGateIntegrationTest`, Testcontainers MySQL, real `deliverableRepository`, `spring.cache.type=none`, cutover set to past): seed full FK chain users→…→PaymentMilestone(created_at AFTER cutover)→EscrowHold FUNDED with ZERO Deliverable rows → `release()` throws `RELEASE_CONDITION_NOT_MET` (409) AND hold re-read from DB stays `EscrowStatus.FUNDED` (no money moved). Companion: same seed, cutover blank → `assertDoesNotThrow` (fail-open preserved = disabled-by-default proof). Third: `tryReleaseOnApproval` on that milestone returns `false`, hold stays FUNDED.
- **Creation-time** rejection covered by the validation web test above; `ContractServiceDeliverableMaterializationTest` already covers ≥1-slot materialization.

Invariants held: legacy pre-cutover untouched; cutover disabled by default; refund() remains the recovery escape hatch for any anomaly.

```
Priya → Vikram | CR-51 step-3: SHIP BOTH — @NotEmpty+@Valid+@Positive on DealDtos (Create+Counter+DeliverableSlot); gate-throw on empty-set post-cutover branch reusing RELEASE_CONDITION_NOT_MET (keep cutover guard, disabled by default); + validation web test + no-mock release-blocked integration test | influora-api/.../DealDtos.java, EscrowService.java (assertReleaseConditionSatisfied only), DealControllerTest, EscrowReleaseGateIntegrationTest | DECIDED | NEXT: build → Kavya QA → Kabir re-verify (money-path)
Priya → Arjun  | CR-51 step-3 decided: both halves safe (gate only reachable for direct-deal milestone holds; HYPE/campaign-level never hit it; refund is escape hatch); no migration | EscrowService.java, DealDtos.java | DECIDED | NEXT: route spec to Vikram
```


---

## Meera Verification Report — 2026-07-30 21:25 IST
Task: CR-51 step-3 (DealDtos validation + EscrowService gate-throw, uncommitted on fix/brand-audit-remediation)
Files verified: DealDtos.java, EscrowService.java, DealControllerTest.java, EscrowReleaseGateIntegrationTest.java (new), EscrowServiceReleaseTest.java, EscrowServiceTest.java, ContractServiceDeliverableMaterializationTest.java (new)

### Results
1. `mvn -o -Dtest=DealControllerTest test` → **Tests run: 14, Failures: 0, Errors: 0, Skipped: 0** — ✅ PASS (empty deliverables 400, qty:0 slot 400, valid≥1 201, CounterRequest empty 400 all present)

2. `mvn -o -Dtest=EscrowReleaseGateIntegrationTest test` → **Tests run: 7, Failures: 0, Errors: 0, Skipped: 0** — ✅ PASS. Confirmed REAL run (not Docker-skipped): log shows `tc.mysql:8.0.40 -- Container mysql:8.0.40 is starting`, `Waiting for database connection ... jdbc:mysql://localhost:<port>/influora_it`, container started in ~29s, Spring context boot ~59s.
   - `releaseBlockedWhenCollaborationHasZeroDeliverables` (D): ✅ throws ApiException code=`RELEASE_CONDITION_NOT_MET`, hold re-read = `FUNDED`
   - `releaseSucceedsWhenGateDisabledEvenWithZeroDeliverables` (D2): ✅ blank cutover (via ReflectionTestUtils null) → `assertDoesNotThrow`, hold = `RELEASED` — disabled-by-default proven
   - `tryReleaseOnApprovalReturnsFalseWhenCollaborationHasZeroDeliverables` (F): ✅ returns `false`, hold = `FUNDED`
   - Pre-existing: `findByCollaborationIdFindsRealDeliverables_milestoneIdStaysNull`, `releaseBlockedWhenRealDeliverablesUnsatisfied`, `releaseSucceedsWhenRealDeliverablesAllPosted` (C), `releaseSucceedsForPreCutoverMilestoneRegardlessOfDeliverableState` (E) — all ✅ still pass, no regression.
   - Note: stack traces logged for `releaseSucceedsWhenRealDeliverablesAllPosted` are an internal caught-and-logged `CampaignServiceInvoiceService` invoice failure (`safelyCreateServiceInvoice`, msg "the completed escrow release/ledger posting is NOT affected") — expected test-fixture behavior (no creator profile seeded for invoice), not a test failure.

3. `mvn -o -Dtest=EscrowServiceTest,EscrowServiceReleaseTest,ContractServiceDeliverableMaterializationTest test` → EscrowServiceTest 33/33, EscrowServiceReleaseTest 8/8, ContractServiceDeliverableMaterializationTest 1/1 = **Tests run: 42, Failures: 0, Errors: 0, Skipped: 0** — ✅ PASS, no regression.

4. `mvn -o test` (full suite) → **Tests run: 1572, Failures: 0, Errors: 0, Skipped: 0** — ✅ BUILD SUCCESS (3:55 min). EscrowReleaseGateIntegrationTest re-confirmed running inside full suite (own Testcontainers MySQL boot, 7/7 pass).

### VERDICT: ✅ ALL PASS — Ready for next step (Kabir money-path re-verify per Priya's routing). Not committed (per instructions).

---

## Ananya — BR-05 granular notification toggles (2026-07-30)

FE-only, per Priya's "Granular notifications — CONDITIONAL" note above: emitter-side condition IS met (`NotificationService.isUnsubscribed` falls through to `findByUserIdAndEventType` after the global "*" check), so wired both toggles for real.

**Correction to the assigned mapping:** the requested eventType strings (`creator.campaign_match`, `brand.new_application`, `creator.bid_accepted`, `brand.counter_bid`, `brand.proposal_accepted`, `creator.proposal_received`) are `templateKey` values `NotificationListener` passes for email-template selection — `isUnsubscribed` keys on `event.eventType()` instead, a different string. Also `campaign.created` and `bid.accepted` ARE real `eventType()` values but only ever notify the *creator* (Brand→Creator listener block), never the brand, so binding them here would be a dead preference row. Shipped only eventTypes that are both (a) a real `event.eventType()` return value and (b) from a Creator→Brand event (brand is the actual notify recipient):
- **Campaign Alerts** → `["application.created"]`
- **Bid Notifications** → `["bid.countered"]`

Full reasoning + dropped-string list documented inline at `src/pages/brand-settings.tsx` (module-level comment above `CAMPAIGN_ALERT_EVENT_TYPES`).

```
Ananya → Kavya | READY FOR QA: src/pages/brand-settings.tsx (campaignAlerts/bidNotifications wired to real per-eventType preferences; weeklyDigest tooltip corrected; stale comment fixed) | src/pages/brand-settings.tsx | READY_FOR_QA | NEXT: Kavya review, flag if eventType-vs-recipient reasoning needs Vikram sign-off before merge
```

typecheck: ✅ exit 0. build: ✅ exit 0 (16/16 routes prerendered).
