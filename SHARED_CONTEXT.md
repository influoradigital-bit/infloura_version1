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

Kabir -> Arjun/Priya | PART 5 VERIFIED (BrandF.md §§53-58 appended): NEEDS_FIX. P-1 CRITICAL is a FALSE ALARM (escrow release IS reachable via deliverable approval, BrandDeliverableService.java:117; GET /wallet/escrow/{id} HAS a client at meera-api.ts:570 — census missed the second API layer) -> downgraded to LOW. P-2 UPHELD. §50 praise claims ALL ACCURATE (TDS/GST really null in live). 7 NEW defects incl. 3 HIGH: P-1prime (Meera campaign-level holds unreleasable), P-3 (|| 50000 fabricates "₹50,000 secured in escrow" on live zero-value deals), P-4 (withdrawal idempotency key regenerated per click). | BrandF.md | VERIFIED | NEXT: route P-1prime/P-3/P-4 to Vikram+Ananya; P-3 and P-6 are creator-facing money promises

Kabir -> Arjun/Priya | PART 8 VERIFIED (BrandF.md §80 appended): NEEDS_FIX. N-1/N-2/C-4 all REAL, but §76 "Help & Support: zero defects" is FALSE -- openTour() sets store.ts:127 tourOpen, which NOTHING in the repo reads: "Take the tour again" is a dead control (H-1 MEDIUM), and §77 already listed it as NOT CHECKED while §76 called it "genuinely wired". 5 MISSED: M-A NotificationBell.tsx (204 lines) has ZERO importers -- real bell is inline at brand-layout.tsx:359; M-B the bell NEVER refetches (refresh() only in mount effect, no poll/SSE/focus/popover-open) so notifications are frozen per page-load -- this subsumes N-1; M-C "View all notifications" just closes the popover, no /brand/notifications route; M-D literal "Bearer null" header; M-E no credentials:include. 2 bad cites fixed in place: api.ts:237 -> :304-313, AuthService:445 -> :466 (audit filed D-4 "stale line cites" then committed one). Corrected tally 31 -> 39. | BrandF.md §80 | VERIFIED | NEXT: H-1 + M-B to Ananya (tour overlay never built; bell needs poll/refetch-on-open); M-A delete dead component

Kabir -> Arjun/Priya | PART 12 VERIFIED (BrandF.md §§123-132 appended): NEEDS_FIX. SI-1 CONFIRMED REAL, HIGH justified (StoreIntegrationSetup.tsx orphaned across all 5 false-zero patterns; brand-settings.tsx:428-445 has 4 tabs, no integrations; no admin/Meera path) -- BUT §118 fix "just mount it" is FALSE. §119 "strongest security posture" NOT DESERVED: all 5 named controls verified real, the superlative is not. Both killed findings correctly killed, but #1 killed on false reasoning (repo extends JpaRepository -> findAll/findById ARE unfiltered; conclusion holds only via consumer census). 7 MISSED, 2 HIGH: SI-2 Shopify callback needs Bearer (ShopifyConnectController.java:81 + JwtAuthenticationFilter.java:29) and NO frontend callback route exists -> unconnectable even after SI-1; SI-3 FE sends bare subdomain, BE regex requires FQDN (ShopifyOAuthService.java:54) -> 400 on the UI placeholder; SI-4 disconnect->reconnect violates uq_shopify_shop_domain/uq_woocommerce_site_url; SI-5 rotateToken/rotateSecret never update shopDomain/siteUrl; SI-6 no MemberRole gate on 5 brand endpoints (any member can disconnect the store); SI-7 Woo webhook 404-vs-401 = site-enumeration oracle, §119 certified the opposite from a self-contradicting javadoc; SI-8 no influora.shopify/woocommerce block in application.yml -> non-dev boot blocked. §120 over-claims ignorance: webhook replay IS handled (IdempotencyService both controllers), TTL is 10min, SSRF genuinely absent. tsc --noEmit re-run: exit 0 CONFIRMED. Register 1 -> 8. | BrandF.md §§123-132 | VERIFIED | NEXT: SI-2+SI-3 to Ananya (Shopify callback route + append .myshopify.com) BEFORE mounting SI-1; SI-4/SI-5 to Vikram (revoked-aware unique index + set domain in rotate*); SI-6 requireRole gate

Kabir -> Arjun/Priya | PART 14 VERIFIED (BrandF.md 157-166 appended): NEEDS_FIX. UX-1 REAL, MEDIUM confirmed (bucketFor returns null :369; no other filter/interceptor/@RateLimit/Bucket4j anywhere; PortfolioService.contact:342-353 is field validation only; NotificationListener:373-387 really sends the email). All SIX 152 clean verdicts DESERVED on substance -- Upload allowlist question 154 left open is now CLOSED (MediaMimeSniffer is a closed 7-format magic-byte set, SVG/HTML/JS sniff to null and die at UploadService:152-157; surviving set jpeg/png/gif/webp/pdf; filename never used, key at :81-88); Jwks publicKey is ECPublicKey; /config/razorpay genuinely NOT permitAll (verified in SecurityConfig, not javadoc); 16KB cap real at ClientErrorController:152-169; Health minimal. Both 153 kills CORRECT. 3 defects in Part 14 not 1: UX-2 NEW MEDIUM -- GET /portfolio/{username} is ALSO unbucketed AND writes a portfolio_events row per anonymous request (recordPublicView:156-162), refuting 151/156 "single public endpoint with no rate limit"; UX-3 NEW LOW -- captchaToken (PortfolioDtos:103) read by NOTHING repo-wide while SecurityConfig:154 claims anti-spam is enforced in PortfolioService. Four unexamined controllers all audited: ConversionWebhook/Secret/DeliverableMetric CLEAN (sig verify ordered before dispatch :209/:254, brand-scoped one-time reveal, ownership check :91-94); Portfolio /me/* have NO IDOR (principal-only, no user-supplied id). TWO citation defects: ClientErrorController:45,146 are both JAVADOC (8th instance); 153 cited TrackingDtos:60 annotation instead of the real gate CampaignLinkService.validateBaseUrl:232-250 called at :172. | BrandF.md 157-166 | VERIFIED | NEXT: one bucketFor change covers UX-1+UX-2 (add /portfolio/*/contact POST and /portfolio/* GET); Vikram to add visitor-keyed dedup on recordPublicView; delete-or-implement captchaToken and fix the SecurityConfig:154 comment

Ananya -> Kavya | READY FOR QA: D-2 fix (BrandF.md §11) -- campaigns.list silently truncated at 100, zero pager/load-more/total. api.ts campaigns.list now uses requestWithMeta (was request, discarding envelope.meta), returns {campaigns, meta} mirroring creators.search's pattern. campaigns-list.tsx gets Load-more control + "Showing X of Y" total, driven by meta.hasMore/meta.total (mirrors creator-discovery.tsx's Load-more pattern). Updated all 3 call sites of campaigns.list to the new return shape: campaigns-list.tsx, brand-creator-profile.tsx (invite dropdown, untouched behavior), creator-discovery.tsx (invite-campaign picker, found via a multi-line call my first grep pass missed -- re-grepped `api\.campaigns\b` across src to confirm no others). Updated 2 test mocks (creator-discovery.test.tsx, creator-discovery-redirect.test.tsx) to the new shape; added new src/components/brand/campaigns/campaigns-list.test.tsx (2 tests: 101-campaign response shows Load-more + total, clicking it fetches page 2 and reveals campaign #101). | src/lib/api.ts, src/components/brand/campaigns/campaigns-list.tsx, src/components/brand/campaigns/campaigns-list.test.tsx (new), src/pages/brand-creator-profile.tsx, src/components/brand/discover/creator-discovery.tsx, src/components/brand/discover/creator-discovery.test.tsx, src/components/brand/discover/creator-discovery-redirect.test.tsx | READY_FOR_QA | NEXT: Kavya review, then Meera local verify (tsc --noEmit: 0 errors; vitest on all 3 test files: 7/7 pass -- results below)

---

## Ananya — D-14 / OB-1 / OB-2 frontend wiring (2026-08-10)

Verified Vikram's backend handoff against actual code first (CreatorController.java, OnboardingController.java, DiscoveryDtos.java, OnboardingDtos.java) — matches spec exactly.

**D-14**: Added `featured`/`suggestions`/`similar`/`getProfile` wrappers + matching TS types to `creators` in `src/lib/api.ts`. PR-1 (BrandF.md §87): `src/pages/brand-creator-profile.tsx` swapped `api.creators.get` → `api.creators.getProfile` (GET /creators/profile/:usernameOrId) — real `completedCampaigns`/`avgRating` now render instead of hardcoded 0s; `avgRating` is `null`-safe end-to-end (renders "Not yet rated", never a fabricated 0/star fill). No one else had touched PR-1 yet (only my own prior TODO comments were there). UI slots: "Featured Creators" rail on `creator-discovery.tsx` (shown only pre-search/filter), "Similar Creators" grid on `brand-creator-profile.tsx`, "Suggested Creators" card on `campaign-form.tsx`'s review step (only point all 4 request fields are collected).

**OB-1**: `src/components/brand/campaigns/brand-kyc-prompt.tsx` — reads `GET /onboarding/brand/status` on mount (hides if `kycPromptDismissed`), and `rememberDismiss()` now also fires `POST /onboarding/brand/kyc-prompt-dismissed` fire-and-forget alongside the existing localStorage write.

**OB-2**: `src/App.tsx`'s `ProtectedRoute` now calls `GET /onboarding/brand/status` via `useQuery` (shared cache across route mounts, 5min staleTime, fails open on error) and redirects to `/brand/onboarding` when incomplete. Creator/admin guards untouched.

**P-1**: no code change (Vikram confirmed genuinely uncalled, correctly filed LOW).

Verification: `npx tsc --noEmit` exit 0. `npx eslint` on all 6 touched files: 0 errors, 5 warnings (all pre-existing `react-hooks/set-state-in-effect`/`exhaustive-deps` pattern, matches the project's existing warn-only policy — see MEMORY react-hooks-v7-policy). `npm run build`: succeeded, 16/16 marketing routes prerendered.

Files: `src/lib/api.ts`, `src/pages/brand-creator-profile.tsx`, `src/components/brand/discover/creator-discovery.tsx`, `src/components/brand/campaigns/campaign-form.tsx`, `src/components/brand/campaigns/brand-kyc-prompt.tsx`, `src/App.tsx`.

```
Ananya → Kavya | READY FOR QA: D-14 (4 creator endpoints wired + PR-1 fabricated-stats fix), OB-1 (KYC prompt server-side dismiss), OB-2 (ProtectedRoute server-side onboarding check) | src/lib/api.ts, src/pages/brand-creator-profile.tsx, src/components/brand/discover/creator-discovery.tsx, src/components/brand/campaigns/campaign-form.tsx, src/components/brand/campaigns/brand-kyc-prompt.tsx, src/App.tsx | READY_FOR_QA | NEXT: Kavya review, then Meera local-run verify (live-mode manual check needs a real brand session + backend running — not run here)
```

---

## Ananya — M-1 fabricated "Verified Brand" badge fix (2026-08-10)

Confirmed backend field first: `DealDtos.DealResponse.counterpartyVerificationStatus` (DealDtos.java:39, String, null-safe) — populated in `DealService.resolveCounterparty` from `Workspace.verificationStatus.name()` only when viewer role is CREATOR; null when viewer is BRAND. Matches PR-2 description exactly.

**Fix**: `Deal` type in `src/lib/api.ts` gains `counterpartyVerificationStatus?: VerificationStatus | null`. `creator-deal-mappers.ts` — both `mapDealToDealsPageRow` and `mapDealToChatRoom`'s `brandVerified` now read `deal.counterpartyVerificationStatus === 'VERIFIED'` (was hardcoded `true` at :184 in the former). Added `brandVerified: boolean` to `CreatorChatDealRoom` (it didn't exist before — the badge in `creator-chat.tsx` was literally unconditional JSX, not reading any field). Fixed the stale comment at :198-202 that claimed the mapper "never fabricates a rating/badge" (true now, was false). `creator-chat.tsx`'s "Verified Brand" badge (~1804-1811) is now gated on `selectedDeal.brandVerified`; renders nothing for PENDING/UNVERIFIED/REJECTED/null, matching the existing honest conditional-badge pattern (`invite.brandVerified && <VerifiedBadge />` in `hype-inbox-card.tsx`) rather than inventing new copy. Added `brandVerified: true` to the 5 mock deal rooms (demo-mode only, unaffected by the defect).

**Other `brandVerified` occurrences found, not touched**: `creator-deals.tsx` mock array (lines 99-190) and `creator-chat.tsx` mock array — both explicitly demo-mode-only, replaced by the real mapper in live mode. `hype-inbox-card.tsx`/`demo-data.ts` `HypeInvite.brandVerified` — separate Hype-invite feature; `creator-deals.tsx:226` feeds it a permanently-empty array (`useMemo(() => [], [])`), so it is currently dead/unwired, not live-fabricating anything. Flagging for whoever wires real Hype-invite data later — it'll need its own real verification field then.

Tests: new `src/pages/creator-chat-verified-badge.test.tsx` (4 tests, full-render harness copied from `creator-chat-refresh.test.tsx`) proves "Verified Brand" text is absent for UNVERIFIED/PENDING/null and present for VERIFIED. Also added 10 mapper-level tests to `creator-deal-mappers.test.ts` covering both mappers × 4 status values.

Verification: `npx tsc --noEmit` exit 0. `npx vitest run src/pages/creator-chat-verified-badge.test.tsx src/lib/creator-deal-mappers.test.ts` — 36/36 pass (26 pre-existing + 4 new render tests + 10 new mapper tests, 0 failures).

Files: `src/lib/api.ts`, `src/lib/creator-deal-mappers.ts`, `src/lib/creator-deal-mappers.test.ts`, `src/pages/creator-chat.tsx`, `src/pages/creator-chat-verified-badge.test.tsx` (new).

```
Ananya → Kavya | READY FOR QA: M-1 fabricated Verified-Brand badge fix — real counterpartyVerificationStatus wired through Deal type → both creator-deal-mappers → creator-chat.tsx badge, mock data updated, comment lie fixed, 14 new tests (4 render + 10 mapper) | src/lib/api.ts, src/lib/creator-deal-mappers.ts, src/lib/creator-deal-mappers.test.ts, src/pages/creator-chat.tsx, src/pages/creator-chat-verified-badge.test.tsx | READY_FOR_QA | NEXT: Kavya review, then Meera local-run verify (typecheck + targeted vitest already green above; full build/full suite not run here)
```

---

## Ananya — PR-1 close-out: attestation copy + honest nulls for uncovered metrics (2026-08-10)

Core PR-1 swap (`api.creators.get` → `api.creators.getProfile`, real `completedCampaigns`/`avgRating`, null-safe rating) was already landed in an earlier pass (see prior D-14/OB-1/OB-2 entry above) and was still in the working tree, uncommitted. Re-verified against current backend: `CreatorPublicProfileResponse` (DiscoveryDtos.java:62-82) has exactly `completedCampaigns`/`avgRating` and NOT `reviewCount`/`completionRate`/`onTimeDelivery`/`repeatClients`; `CreatorDiscoveryService.computeAvgRating` (:854) returns `null` (not `BigDecimal.ZERO`) when `reviewRepository.findReceivedByCreatorUserId(...)` is empty — confirmed by reading the method, not just the DTO/javadoc.

Closed the two gaps the core swap left open:
1. Reviews-tab attestation ("Based on verified brand collaborations" / "All reviews are from completed campaigns") was unconditional — printed next to a null/`—` rating too, i.e. a false certification beside no data. Now gated on `creator.stats.rating != null`; the null branch mirrors the app's existing empty state (`collaboration-reviews-panel.tsx`'s "No reviews yet" pattern).
2. `reviewCount` was hardcoded `0` and would print "0 reviews" directly beside a REAL non-null average rating (self-contradictory, not just missing-data). DTO has no reviewCount field at all, so it's now `null` end-to-end; render falls back to "Average brand rating" (no fabricated count) instead of "0 reviews".
3. `completionRate`/`onTimeDelivery`/`repeatClients` (Overview tab "Work Metrics") were hardcoded `0` and rendered unconditionally as "0%" — the exact fabricated-zero-as-fact pattern this ticket exists to kill, just not the two fields named in scope. Confirmed DTO genuinely has none of the three. Changed model type to `number | null`, mapper sets `null`, render falls back to "—".

Test: new `src/pages/brand-creator-profile-pr1.test.tsx` — full-render harness (mocks `api.creators.getProfile`/`similar`, `api.campaigns.list`), 2 cases: (a) creator with `avgRating: null, completedCampaigns: 0` → "Not yet rated", "—"×3 work-metrics, "No reviews yet", attestation text absent, no "0.0"/"0%" anywhere; (b) creator with `avgRating: 4.6, completedCampaigns: 23` → real "23"/"4.6" render, attestation present, no fabricated "0 reviews". `npx vitest run src/pages/brand-creator-profile-pr1.test.tsx` → 2/2 pass. `npx tsc --noEmit -p .` → exit 0, 0 errors repo-wide.

D-14 (no FE caller for `GET /creators/profile/:usernameOrId`): confirmed already closed by the prior pass — `api.creators.getProfile` is called from this page's live-mode `useEffect` (brand-creator-profile.tsx:383), not just defined.

Files: `src/pages/brand-creator-profile.tsx`, `src/pages/brand-creator-profile-pr1.test.tsx` (new).
```
Ananya → Kavya | READY FOR QA: PR-1 close-out — honest attestation copy + null (not fabricated-0) reviewCount/completionRate/onTimeDelivery/repeatClients where the DTO genuinely has no field | src/pages/brand-creator-profile.tsx, src/pages/brand-creator-profile-pr1.test.tsx | READY_FOR_QA | NEXT: Kavya review, then Meera local-run verify (tsc + targeted vitest green above; full build/live-mode manual check not run here)
```

---

## Vikram — BL-2 re-subscribe latch fix + 2 vacuous test fixes (2026-08-10)

Priya's 2nd-round mutation-testing review REJECTED the prior BL-2 fix: `cancelAtPeriodEnd` set `true` by `cancel()` was never cleared on re-activation, so a re-subscribed customer could later be wrongly finalized to CANCELLED by `SubscriptionRenewalResetJob` while Razorpay kept charging. Fixed all 4 items:

1. `SubscriptionService.applySubscriptionWebhookUpdate` — clears `cancelAtPeriodEnd=false` on the existing-row UPDATE branch when `targetStatus == ACTIVE` (line ~512, alongside `setStatus`).
2. New regression test `testReSubscribeAfterLapsedCancellationClearsCancelAtPeriodEndFlag` (SubscriptionServiceTest) — full sequence: cancel → lapse finalized CANCELLED → re-subscribe ACTIVE (flag cleared) → asserts the exact state `SubscriptionRenewalResetJob`'s partition would see.
3. Fixed 2 vacuous assertions via `lenient().when(planRepository.findById(PRO_PLAN_ID))...` stubs in `testCancellationReconcilesDownToFreeImmediately` and `testGetActivePlanForWorkspaceReturnsFreeAfterFinalizeLapsedCancellation` — both previously passed even with `getActivePlanForWorkspace`'s ACTIVE filter deleted.
4. Added realistic `current_start`/`current_end` to `cancelledPayload()`/`completedPayload()` fixtures (RazorpayWebhookControllerTest) so the no-period-update assertions are actually falsifiable.

All 4 verified via break→red→fix→green mutation cycles (production filter/updatePeriod/latch-clear each temporarily reverted, confirmed the guarding test fails, then re-applied and confirmed green). Full target suite: `mvn -o -Dtest=SubscriptionServiceTest,SubscriptionRenewalResetJobTest,RazorpayWebhookControllerTest test` → 31/31 pass, BUILD SUCCESS.

Files: `influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java`, `influora-api/src/test/java/com/influora/service/billing/SubscriptionServiceTest.java`, `influora-api/src/test/java/com/influora/integration/razorpay/RazorpayWebhookControllerTest.java`.

```
Vikram → Arjun | BL-2 round 2: re-subscribe latch bug fixed + 2 vacuous mutation-testing holes closed | influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java, influora-api/src/test/java/com/influora/service/billing/SubscriptionServiceTest.java, influora-api/src/test/java/com/influora/integration/razorpay/RazorpayWebhookControllerTest.java | READY_FOR_REVIEW | NEXT: Priya re-review (mutation-proof cycles run and green — see full transcript for break/red/fix/green output per fix)
```

---

## Vikram — BL-3 checkout double-submit fix (2026-08-10)

BrandF.md §99 HIGH: `initiateCheckout` writes no local row before calling Razorpay (by design — see class javadoc), so `ALREADY_SUBSCRIBED` has nothing to catch on a workspace's FIRST upgrade. Two concurrent requests (2 tabs / replay / curl) both pass the guard and both call `razorpayClient.createSubscription`, producing two real `sub_*` subscriptions; the webhook upsert resolves both into ONE local row, silently orphaning the first (still active, still charging, uncancellable through the product).

**Approach chosen: new `IdempotencyService.runExclusive` (not `executeOnce`, not a PENDING-row/UNIQUE-constraint approach).** Read `IdempotencyService` + `Subscription`/`SubscriptionRepository` fully first. A static-key `executeOnce` was rejected: it marks COMPLETED forever on success, which would permanently lock a workspace out of ever calling `initiateCheckout` again after its first success — breaking the legitimate cancel-then-resubscribe flow this class's own webhook-upsert javadoc documents. The PENDING-row/`workspace_id`-UNIQUE approach was rejected because a Free-tier row already exists for most workspaces by the time checkout runs (lazily created on `GET /billing/plan`), so a second INSERT wouldn't even occur — only a second concurrent UPDATE, which the UNIQUE constraint doesn't arbitrate.

`runExclusive` reserves a per-workspace row (own scope `billing.checkout.pro`) in its own transaction before the Razorpay call — same DB-`UNIQUE`-is-the-arbiter discipline as `executeOnce` — but unlike `executeOnce`, releases (deletes) the reservation on success instead of leaving it COMPLETED, so a later non-concurrent retry/resubscribe is never blocked. A concurrent second caller sees the row still `IN_PROGRESS` and is rejected with `AlreadyInProgressException`, translated to a clean 409 `CHECKOUT_IN_PROGRESS`. A failed Razorpay call leaves the row `FAILED` (reclaimable, same path `executeOnce` already uses), so a genuinely failed attempt is always retryable; a crashed-mid-flight caller is recovered by the existing generic `IdempotencyReservationReaperJob`. The pre-existing `ALREADY_SUBSCRIBED` check is untouched, still runs unconditionally before the lock.

Verified: `testConcurrentCheckoutCallsCreateExactlyOneRazorpaySubscription` — two REAL threads (not mocked sequencing) race `initiateCheckout` for the same workspace via a `ConcurrentHashMap`-backed fake `IdempotencyKeyRecordRepository` (genuine `putIfAbsent` atomicity) + a `CountDownLatch` rendezvous inside the Razorpay stub, with an explicit poll-before-release guard proving true overlap. Asserts exactly 1 `createSubscription` call, exactly 1 success, exactly 1 `CHECKOUT_IN_PROGRESS` rejection. 5/5 repeat runs green (no flakiness). Plus `testFailedCheckoutAttemptCanBeRetried` (Razorpay throws → retry succeeds), `testSuccessfulCheckoutDoesNotPermanentlyLockOutFutureCheckout` (two sequential successful checkouts, not blocked), `testAlreadySubscribedGuardStillFiresAheadOfLock` (guard unweakened). `mvn -o -Dtest=SubscriptionServiceTest test` → 19/19 pass. Full module `mvn -o test` → 1685 run, only 2 pre-existing unrelated failures (`DealControllerTest`, `BrandDeliverableServiceTest` — confirmed untouched by this change, don't reference `IdempotencyService`/`SubscriptionService`, not modified in this working tree).

Files: `influora-api/src/main/java/com/influora/service/IdempotencyService.java` (new `runExclusive`/`releaseTransactional`), `influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java` (`initiateCheckout` wrapped, new constructor param), `influora-api/src/test/java/com/influora/service/billing/SubscriptionServiceTest.java` (4 new tests + in-memory idempotency repo fake).

```
Vikram → Arjun | BL-3 fix: checkout double-submit closed via IdempotencyService.runExclusive (new method) — same-instant concurrent checkout now yields exactly 1 Razorpay subscription, loser gets clean 409 CHECKOUT_IN_PROGRESS, failed/retried/resubscribe flows unaffected | influora-api/src/main/java/com/influora/service/IdempotencyService.java, influora-api/src/main/java/com/influora/service/billing/SubscriptionService.java, influora-api/src/test/java/com/influora/service/billing/SubscriptionServiceTest.java | READY_FOR_REVIEW | NEXT: Priya/Kabir review; mvn -o test 1685 run, 2 pre-existing unrelated failures only (DealControllerTest, BrandDeliverableServiceTest)
```

---

## Vikram — BL-3 round 2: IdempotencyService foundational fix (2026-08-10)

Priya's round-2 review (real Hibernate+H2 probe) REJECTED the BL-3 checkout fix, finding the defect is in shared `IdempotencyService` itself, used by 26 call sites across 18 files, not the checkout code. Two defects fixed:

**Defect 1 (save() silently upserts instead of locking):** `IdempotencyKeyRecord` now `implements Persistable<String>` with a `@Transient isNew` flag (default `true`, flipped `false` by a `@PostLoad`/`@PostPersist` `markNotNew()`). Chosen over the native-INSERT-query alternative because it's the standard Spring Data recipe for a manually-assigned `@Id` with no `@Version`, needs no schema change, and every other write path for this entity (`IdempotencyReservationReaperJob`, `IdempotencyService`) already avoids re-`save()`-ing a loaded instance. `repository.save()` on a fresh reservation now genuinely routes to `entityManager.persist()` (real INSERT), so a duplicate reservation hits the DB `UNIQUE(idempotency_key)` constraint and throws `DataIntegrityViolationException` instead of silently merging over the first row.

**Defect 2 (status transitions never persist):** `markCompletedTransactional`/`markFailedTransactional` no longer find-then-mutate a detached entity in memory (which never flushed). Both now delegate to two new `@Modifying @Query` UPDATE methods on `IdempotencyKeyRecordRepository` (`markCompleted`/`markFailed`), matching the existing `reclaimFailedForRetry` pattern.

**Item 3 (reclaimFailedForRetry transaction requirement) — confirmed via a REAL test, not reasoning:** a `@DataJpaTest`+H2 probe (see below) initially proved that relying on Spring Data's "default repository transactions" for `@Modifying` UPDATE methods does NOT work in this app — `reclaimFailedForRetry`/`reclaimStaleInProgress`/the two new methods all threw `TransactionRequiredException` when called with no ambient transaction (exactly the self-invocation call sites `executeOnce`/`runExclusive` use). Fixed by adding explicit `@Transactional` to all four `@Modifying` methods on the repository interface — matching the codebase's own pre-existing convention already established by `UsageCounterRepository#tryIncrement`. This was a real latent bug: `reclaimFailedForRetry` was previously unreachable on the checkout path only because Defect 1 made every reservation spuriously succeed.

**Real (non-Mockito) test coverage added:** new `IdempotencyServicePersistenceTest` — genuine `@DataJpaTest` + H2 in-memory (MySQL-compat mode; Flyway disabled, `ddl-auto=create-drop`; entity/repository scanning restricted to just `IdempotencyKeyRecord`/`IdempotencyKeyRecordRepository` to avoid unrelated MySQL-only native queries elsewhere in the app). 7/7 pass, proving: duplicate reservation now genuinely rejected (repo-level + end-to-end 2-real-thread `executeOnce`/`runExclusive` races), `markCompleted`/`markFailed` visible on a fresh read (new query, not the same Java object), a real failure→retry cycle actually works end-to-end, and `reclaimFailedForRetry` doesn't throw with no ambient transaction. New test-scope dependency `com.h2database:h2` added to `influora-api/pom.xml` (flagged per TECH-STACK.md rule 6 — no lightweight embedded-DB test dependency existed before; testcontainers-mysql needs Docker, unavailable here) — Priya/CTO sign-off needed since this was requested BY her own review.

Existing `IdempotencyServiceTest` (Mockito) updated to assert the new `repository.markCompleted`/`markFailed` calls instead of the old find-then-mutate lookup. `SubscriptionServiceTest`'s in-memory fake repository (BL-3's `ConcurrentHashMap`-backed mock) updated to stub `markCompleted`/`markFailed` too, so its checkout-concurrency tests stay faithful to the corrected call pattern.

**26 call sites across 18 files reviewed** (all wrap `executeOnce`/`runExclusive` in try/catch for `AlreadyInProgressException`/`AlreadyCompletedException` already — none assumed double-execution was safe, so none needed behavior changes, only correctly get the guarantee they always assumed): `AffiliateEarningsService` (1), `AffiliateSettlementJob` (1), `SubscriptionService` (1, BL-3's `runExclusive`), `ContractService` (2, contract sign brand/creator), `DealService` (3, accept/reject/counter), `WooCommerceWebhookController` (1), `ShopifyWebhookController` (1), `RazorpayWebhookController` (1), `PayoutService` (1), `PayoutReconciliationService` (1), `WalletService` (1, creator withdraw), `RequestPaymentExecutor` (1), `CreateCampaignExecutor` (1), `ConfirmLaunchExecutor` (1), `RedemptionService` (1), `ConversionTrackingService` (1), `MeeraSessionService` (2, send-turn + persist-writeback), `AICreditService` (5: 2×`executeOnce`, 3×`isCompleted` — the money-relevant one: `isCompleted` for AI-credit charge/release markers now actually resolves post-fix instead of always seeing a stuck `IN_PROGRESS` row). None mock `IdempotencyKeyRecordRepository` directly (all mock `IdempotencyService` at the service layer), so none of their own test files needed changes.

Verification: `mvn -o -Dtest=IdempotencyServiceTest,IdempotencyServicePersistenceTest,SubscriptionServiceTest test` → 32/32 pass. Full module `mvn -o test` → 1692 run, only the SAME 2 pre-existing unrelated failures already documented in the prior BL-3 entry (`DealControllerTest`, `BrandDeliverableServiceTest` — confirmed 0 references to `IdempotencyService`/`IdempotencyKeyRecord`).

Files: `influora-api/src/main/java/com/influora/domain/entity/IdempotencyKeyRecord.java`, `influora-api/src/main/java/com/influora/repository/IdempotencyKeyRecordRepository.java`, `influora-api/src/main/java/com/influora/service/IdempotencyService.java`, `influora-api/pom.xml`, `influora-api/src/test/java/com/influora/service/IdempotencyServiceTest.java`, `influora-api/src/test/java/com/influora/service/IdempotencyServicePersistenceTest.java` (new), `influora-api/src/test/java/com/influora/service/billing/SubscriptionServiceTest.java`.

```
Vikram → Arjun | BL-3 round 2: foundational IdempotencyService fix — Persistable<String> makes save() genuinely INSERT/lock (Defect 1), markCompleted/markFailed now real @Modifying UPDATEs (Defect 2), reclaimFailedForRetry+3 others given explicit @Transactional after a real @DataJpaTest proved TransactionRequiredException without it (item 3) | influora-api/src/main/java/com/influora/domain/entity/IdempotencyKeyRecord.java, influora-api/src/main/java/com/influora/repository/IdempotencyKeyRecordRepository.java, influora-api/src/main/java/com/influora/service/IdempotencyService.java, influora-api/pom.xml, influora-api/src/test/java/com/influora/service/IdempotencyServiceTest.java, influora-api/src/test/java/com/influora/service/IdempotencyServicePersistenceTest.java, influora-api/src/test/java/com/influora/service/billing/SubscriptionServiceTest.java | READY_FOR_REVIEW | NEXT: Priya re-review (esp. sign-off on new h2 test-scope dep) + Kabir; mvn -o test 1692 run, 2 pre-existing unrelated failures only (DealControllerTest, BrandDeliverableServiceTest); 26 callers reviewed, none needed code changes
```

---

## Kabir — CR-111 (Medium): MetaOAuthTokenRepository null-workspaceId IDOR-invariant fix (2026-08-10)

Verified the ticket's exact claim against real Spring Data JPA behavior (not assumed): the brand-scoped `findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse` was a plain derived-query method. Spring Data JPA's query-derivation machinery (`ParameterMetadataProvider`/`PredicateBuilder`) rewrites a `SIMPLE_PROPERTY` equality part into `IS NULL` whenever the bound runtime parameter is `null` — so `findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(null, creatorProfileId)` generated `workspace_id IS NULL AND creator_profile_id = ? AND revoked = false`, matching a creator-owned row (creator rows always have `workspace_id IS NULL`). Proved this for real with a red→green test: reverted to the derived-query form temporarily, the new `@DataJpaTest`+H2 regression test failed exactly as predicted (returned the creator row), confirming the bug is real, not theoretical.

Checked every current production caller (`MetaTokenStorage.storeToken/getValidToken/revoke`, `MetricsAuthorizationService.resolveAuthorizedCreatorProfileId` — the latter a real security seam gating `AnalyticsController`/`AnalyticsService` reads) — none currently pass `null`, matching the ticket's claim, but `MetricsAuthorizationService` is exactly the kind of caller where a future null (e.g. malformed/creator-type principal) would silently bypass the authorization check it exists to enforce.

**Fix (option a — fail-closed at the query):** converted the method from a derived query to explicit `@Query` JPQL with `t.workspaceId IS NOT NULL AND t.workspaceId = :workspaceId AND ...` — hand-written JPQL doesn't get Spring Data's null→IS-NULL rewrite (that only applies to method-name-derived `PartTree` queries), and the explicit `IS NOT NULL` makes the guarantee textually enforced rather than an accident of caller behavior. Updated both javadocs that made the same false "never returns a creator-owned row" / "disjoint by construction" claim (`MetaOAuthTokenRepository`, `MetaOAuthToken` entity) to state the guarantee now enforced by the query, not just true of current callers.

**Regression test:** new `MetaOAuthTokenRepositoryNullWorkspaceIdTest` — genuine `@DataJpaTest`+H2 (Mockito mocks can't observe Spring Data's query-derivation behavior at all), scoped to just `MetaOAuthTokenRepository` (same pattern as `IdempotencyServicePersistenceTest`). 2 tests: null workspaceId never matches the creator-owned row (proved red on the old code, green on the fix); a real workspaceId still resolves the legitimate brand row (no regression).

Verified: `mvn -o -Dtest=MetaOAuthTokenRepositoryNullWorkspaceIdTest,MetaTokenStorageTest,MetricsAuthorizationServiceTest test` → 17/17 pass (2 new + 13 existing MetaTokenStorageTest + 2 existing MetricsAuthorizationServiceTest, both existing suites are Mockito-based and unaffected by the query-body change since the method signature is unchanged). Full-module `mvn -o test` currently blocked by a PRE-EXISTING, UNRELATED compile break in `CreatorOnboardingServiceTest.java` (constructor arg-count mismatch vs `CreatorOnboardingService` — 5 args supplied, 4 accepted; zero references to Meta OAuth anything) — isolated via `-Dmaven.compiler.testExcludes=**/CreatorOnboardingServiceTest.java` to scope this run; did not touch that file (looks like a concurrent-session mid-edit, per the "don't fix unrelated failures" instruction).

Files: `influora-api/src/main/java/com/influora/repository/MetaOAuthTokenRepository.java` (method → `@Query` JPQL with explicit `IS NOT NULL`, both javadocs updated), `influora-api/src/main/java/com/influora/domain/entity/MetaOAuthToken.java` (class javadoc updated to match), `influora-api/src/test/java/com/influora/repository/MetaOAuthTokenRepositoryNullWorkspaceIdTest.java` (new).

```
Kabir → Arjun | CR-111 fixed: brand-scoped MetaOAuthTokenRepository query now carries explicit workspaceId IS NOT NULL (JPQL, not derived-query) so a null workspaceId can never cross into creator-owned (workspace_id IS NULL) rows — proved red→green with a real H2 test, old derived-query form DID return the creator row when called with null | influora-api/src/main/java/com/influora/repository/MetaOAuthTokenRepository.java, influora-api/src/main/java/com/influora/domain/entity/MetaOAuthToken.java, influora-api/src/test/java/com/influora/repository/MetaOAuthTokenRepositoryNullWorkspaceIdTest.java | READY_FOR_REVIEW | NEXT: full `mvn -o test` blocked by unrelated pre-existing CreatorOnboardingServiceTest.java compile break (not touched, flagging for whoever owns it); scoped run 17/17 green
```

## Kabir (adversarial re-review) — CR-111 verification: MetaOAuthTokenRepository null-workspaceId fix (2026-08-10)

Independent adversarial re-check of the CR-111 fix above (fresh context, no prior report read). Confirmed by direct inspection + live Hibernate SQL log: `findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse` is now hand-written `@Query` JPQL with `t.workspaceId IS NOT NULL AND t.workspaceId = :workspaceId AND ...` — generated SQL literally shows `where mot1_0.workspace_id is not null and mot1_0.workspace_id=? and ...`. `MetaOAuthTokenRepositoryNullWorkspaceIdTest` is a genuine `@DataJpaTest` + H2 (not Mockito), asserts null-workspaceId returns empty AND that a real workspaceId still resolves the brand row — both present, both pass. `mvn -o test -Dtest=MetaOAuthTokenRepositoryNullWorkspaceIdTest,MetaTokenStorageTest,MetricsAuthorizationServiceTest` → 17/17 pass, BUILD SUCCESS. Javadoc claims match what the query now actually enforces.

**Remaining finding (not blocking this ticket, flagging per audit scope):** `findByWorkspaceIdAndRevokedFalse(String workspaceId)` (same repository, List-returning, line 42) is still a plain derived query with the *identical* null→`IS NULL` rewrite exposure CR-111 just fixed elsewhere — if ever called with `workspaceId == null` it would return the FULL list of every non-revoked creator-owned row system-wide (bigger blast radius than the fixed method, since it's a List not a single Optional). Its only caller, `BrandOwnContentService.checkOwnContent` (line 78), sources the argument from `brandProfile.getWorkspaceId()` — currently safe only because `brand_profiles.workspace_id` has a DB-level `NOT NULL` constraint, not because the query itself enforces it. This is exactly the "true by accident of current caller behavior, not enforced by the query" pattern CR-111's own javadoc calls out as the wrong standard. LOW severity (no live path to null today), but same class of bug; recommend the same `@Query` + explicit `IS NOT NULL` treatment for consistency/defense-in-depth.

Verdict: PASS for CR-111's stated done_when. LOW finding opened for the sibling method above.

```
Kabir(review) → Arjun | CR-111 verified PASS: real IS NOT NULL JPQL confirmed via live Hibernate SQL log, real H2 DataJpaTest proves it (not Mockito), 17/17 green | influora-api/src/main/java/com/influora/repository/MetaOAuthTokenRepository.java | VERIFIED | LOW finding: sibling method findByWorkspaceIdAndRevokedFalse (same file, line 42) still a derived query with the same null-rewrite exposure, currently guarded only by brand_profiles.workspace_id DB NOT NULL constraint not by the query — recommend same @Query fix for consistency
```

---

## Vikram — bare-invite dead-end in creator chat room (investigated, NOT fixed — fix is 100% frontend, outside backend authority)

Investigated the routed defect: a brand's bare invite (`POST /creators/:id/invite`, no budget) leaves the creator's chat room showing "No messages yet" with zero Accept/Decline affordance, even though `Collaboration.canAccept()` genuinely returns true for `INVITED`.

**Read in full:** `CreatorDiscoveryService.invite()` (:434-474), `DealService.createProposal`/`persistProposalMessage` (:189-262, :1114-1169), `Collaboration.canAccept/canReject` (:185-227), `DealMessageKind` enum, and the FE consumers `creator-chat.tsx` (proposal card :2032-2163, `handleAcceptProposal`/`handleDeclineProposal` :1294-1371, `dealAllowsProposalResponse`/`mayIndicateDealStatusChange` :452-507) and `creator-deals.tsx` (New-tab accept/reject :353-402).

**Direction chosen: (b), frontend-only — not (a).** Ruled out (a) (backend writes a `DealMessage` on invite) for a concrete reason found while reading, not just preference: the ONLY card renderer that shows Accept/Decline (`event.type === 'proposal'`, :2033) also renders `formatINR(Number(event.metadata?.amount))` and a full "Your Earnings Breakdown" (gross/platform-fee/net) computed off that same amount (:2063-2103). A bare invite has no amount. Reusing `kind='proposal'` would render "₹0" financials for a deal with no agreed price — exactly the fabricated-zero anti-pattern `TECH-STACK.md`'s locked UI Honesty rule forbids ("A numeric fallback of 0 for no data... is worse than an empty state"). A correct (a) would need a genuinely new `DealMessageKind` + a new, amount-free card component — strictly more surface than (b) for the same outcome.

(b) needs **zero backend changes**: `POST /deals/:id/accept` and `/reject` already dual-role-authorize on `Collaboration.canAccept()`/`canReject()`, both of which already include `INVITED` (`Collaboration.java:185-190, 221-227`) — no gap there. `creator-chat.tsx`'s own `handleAcceptProposal`/`handleDeclineProposal` (:1294-1371) already call `api.deals.accept(selectedDeal.id, 'creator')` / `api.deals.reject(selectedDeal.id, undefined, 'creator')` — **deal-scoped, not message-scoped** (the `proposalId` argument is only used for local feedback-banner tracking, never sent to the server) — so they can be reused as-is against a synthetic id for a bare invite. And the exact gap is already self-documented in this file's own comment block (:490-497, `mayIndicateDealStatusChange` javadoc): *"an INVITED deal built by `Collaboration.invite(...)` has none [proposal card]: `persistProposalMessage` only runs from `createProposal` and `doCounter`"* — a prior pass already found half of this bug and worked around it for the SSE-refetch trigger, but never closed the render-side gap this ticket is about.

**Spec for the FE half (Ananya — this is the entire fix):**
- File: `src/pages/creator-chat.tsx`, in the message list render (around :1981-1986, the `events.length === 0` empty-state block).
- Compute `const hasProposalEvent = events.some(e => e.type === 'proposal');` and `const showBareInviteCard = liveApi && !messagesLoading && !messagesError && !hasProposalEvent && dealAllowsProposalResponse(selectedDeal);` (reuse the existing `canRespondToProposal`/`dealAllowsProposalResponse` from :1723/:478 — do not invent a second status list, per that function's own CR-34 comment).
- Render a new card (NOT inside `events.map`, since there is no backing message) when `showBareInviteCard` is true: "This brand invited you to [campaign]" + Accept/Decline buttons wired to `handleAcceptProposal('bare-invite-' + selectedDeal.id)` / `handleDeclineProposal('bare-invite-' + selectedDeal.id)` — same handlers `creator-deals.tsx`'s New tab and the real proposal card already use successfully, just with a synthetic id since there's no `DealMessage` row to reference. No amount, no earnings breakdown, no Counter button (nothing to counter on a bare invite) — Accept + Decline only.
- Show this card whether or not `events.length === 0` — if the brand's invite included a message, `DealService.listMessages`'s notes-seeding (`:449-451`) already surfaces that as a plain text bubble, and this card should render alongside it, not only in the fully-empty case.
- Leave `creator-deals.tsx`'s `isNew` New-tab flow and `DealService.createProposal`/`persistProposalMessage` untouched — out of scope, both confirmed working.

**Why I'm not implementing this myself:** every line of the actual fix is in `creator-chat.tsx`, a React component — outside backend authority (no `app/api`/Prisma-equivalent/Java service file needs to change). Routing the spec above to Ananya rather than submitting a partial backend-only diff that wouldn't move the user-visible bug at all.

```
Vikram → Arjun | Bare-invite chat dead-end: root-caused, zero backend fix required (accept/reject already authorize INVITED; handlers already deal-scoped, reusable as-is) — full FE spec above is the entire fix | src/pages/creator-chat.tsx (events.length===0 block, :1981-1986; reuse dealAllowsProposalResponse :478, handleAcceptProposal/handleDeclineProposal :1294-1371) | NEEDS_ANANYA | NEXT: route to Ananya for implementation + a creator-chat.test.tsx (or equivalent) case: INVITED deal, no proposal-kind event → card renders with working Accept/Decline calling api.deals.accept/reject; then Kavya QA → Meera build/test (frontend-only, no mvn run needed)
```

---

## Ananya — bare-invite dead-end fix (2026-08-13)

Implemented Vikram's routed spec, per Arjun's task framing: `creator-chat.tsx` now shows a new fallback card — distinct from the `type === 'proposal'` message card — whenever the selected deal has zero timeline events AND `dealAllowsProposalResponse`/`canRespondToProposal` still says the deal is respondable (`showBareInviteResponse = events.length === 0 && canRespondToProposal`, right after the existing `canRespondToProposal` derivation). Scoped strictly to the zero-messages case per this task's explicit requirement 3 (a deal with any messages — chat, counter, settled/stale proposal card — is unaffected and keeps rendering the normal timeline/empty state exactly as before).

The card ("Campaign Invite") names the brand and `selectedDeal.campaignName`, shows NO amount/deliverables/earnings-breakdown/Counter option (a bare invite has no price — fabricating one would be the exact UI-honesty violation `TECH-STACK.md`'s LOCKED rule forbids), and wires Accept/Decline to the SAME deal-scoped handlers the priced proposal card already uses (`handleAcceptProposal`/`handleDeclineProposal` → `api.deals.accept(selectedDeal.id, 'creator')` / `api.deals.reject(selectedDeal.id, undefined, 'creator')`), passing a synthetic per-deal id (`bare-invite-${selectedDeal.id}`) only to key the existing CR-03 feedback banner — confirmed by reading the handlers that this id is never sent to the server. Loading states, toasts, and the stale-409 "Refresh deal" affordance are the same code paths, reused as-is.

Verified: `npx tsc --noEmit` exit 0. New `src/pages/creator-chat-bare-invite.test.tsx` (3 tests): (1) INVITED deal, zero messages → "Campaign Invite" card renders (not "No messages yet"), no amount/breakdown/Counter text, Accept click calls `dealsAccept('deal_bare', 'creator')` + success toast; (2) Decline click calls `dealsReject('deal_bare', undefined, 'creator')` + success toast; (3) regression — same INVITED deal but WITH a real `kind: 'proposal'` message → original "Brand Proposal" card renders (amount, Earnings Breakdown, Counter button all present), bare-invite card does NOT render, single Accept button still calls the same `api.deals.accept`. `npx vitest run src/pages/creator-chat-bare-invite.test.tsx` → 3/3 pass. Full creator-chat suite unaffected: `npx vitest run src/pages/creator-chat-refresh.test.tsx src/pages/creator-chat-verified-badge.test.tsx src/pages/creator-chat-visibility-resync.test.tsx src/pages/creator-chat-bare-invite.test.tsx` → 4 files, 13/13 pass. `npx vitest run src/lib/creator-deal-mappers.test.ts` → 36/36 pass (untouched, sanity check only).

Files: `src/pages/creator-chat.tsx` (new `showBareInviteResponse`/`bareInviteFeedbackId` derivations + new card block, replacing the unconditional "No messages yet" render with a `!showBareInviteResponse` guard), `src/pages/creator-chat-bare-invite.test.tsx` (new).

```
Ananya → Kavya | READY FOR QA: bare-invite dead-end fix — new zero-messages-but-respondable fallback card in creator-chat.tsx, reuses existing deal-scoped accept/decline handlers, no fabricated amount/breakdown/Counter, 3 new tests (render + regression) | src/pages/creator-chat.tsx, src/pages/creator-chat-bare-invite.test.tsx | READY_FOR_QA | NEXT: Kavya review, then Meera local-run verify (tsc + targeted vitest already green above; full build/full suite not run here)
```

---

## Ananya — bare-invite fix ROUND 2 (2026-08-13) — Priya reject remediated

Priya's fresh-context review rejected round 1: `showBareInviteResponse = events.length === 0 && canRespondToProposal` was too broad. `canRespondToProposal` (`ACCEPTABLE_COLLABORATION_STATUSES`, `deal-stage.ts:90-95`) is also true for `APPLIED` — the creator's OWN pending application, not a brand invite — which also has zero messages (`persistProposalMessage` never ran, same structural reason as INVITED). The card's "Brand X invited you..." copy would be false, and worse: `DealService.doAccept`'s `CANNOT_ACCEPT_OWN_OFFER` guard only fires when a `proposal` DealMessage exists to read `senderType` off of — APPLIED has none — so nothing server-side stopped a self-accept straight to TERMS_AGREED.

**Fix applied exactly as specified:**
```diff
- const showBareInviteResponse = events.length === 0 && canRespondToProposal;
+ const showBareInviteResponse =
+   events.length === 0 &&
+   canRespondToProposal &&
+   selectedDeal.collaborationStatus === 'INVITED';
```
Confirmed `collaborationStatus` is the correct real property before typing it in: `DealRoom.collaborationStatus?: CollaborationStatus` (`creator-deal-mappers.ts:167`), populated by `mapDealToChatRoom` as `collaborationStatus: deal.status` (`:240`) — the raw backend status, same field `dealAllowsProposalResponse`/`canRespondToProposal` already reads at line 1723. No new field, no type mismatch.

**Two negative tests added** to `creator-chat-bare-invite.test.tsx`: (1) `APPLIED_DEAL` (zero messages, `collaborationStatus: 'APPLIED'`) — asserts "Campaign Invite" does NOT render and no Accept button exists anywhere on the page, falls through to "No messages yet"; (2) `TERMS_AGREED_DEAL` (zero messages, already-settled) — asserts the same, plain empty state shows.

**Mutation-proof cycle, run for real (not narrated):**
1. Fix in place → `npx vitest run src/pages/creator-chat-bare-invite.test.tsx` → **5/5 pass** (3 original unchanged + 2 new).
2. Reverted to the old broad gate (`events.length === 0 && canRespondToProposal`, no status check) → re-ran same command → **4 passed, 1 failed** — the new APPLIED test failed exactly as predicted: `findByText(/No messages yet/i)` timed out because the broad gate rendered the bare-invite card instead. Proves the new test is a real guard, not vacuous.
3. Fix restored → re-ran → **5/5 pass** again.

The 3 original tests (INVITED renders the card + wires Accept/Decline; proposal-message deal shows the priced "Brand Proposal" card, not the fallback) pass unchanged throughout — confirmed in step 1 and step 3 output, no edits made to those three `it()` blocks.

`npx tsc --noEmit`: exit 0, no output, 0 errors.

Files: `src/pages/creator-chat.tsx` (`showBareInviteResponse` derivation only, ~line 1744), `src/pages/creator-chat-bare-invite.test.tsx` (2 new `it()` blocks + 2 new fixture consts, `APPLIED_DEAL`/`TERMS_AGREED_DEAL`).

```
Ananya → Kavya | READY FOR QA (round 2): CR-02 self-accept-on-own-application closed — showBareInviteResponse now also requires collaborationStatus === 'INVITED', 2 new negative tests, mutation-proof cycle run and documented above | src/pages/creator-chat.tsx, src/pages/creator-chat-bare-invite.test.tsx | READY_FOR_QA | NEXT: Kavya review, then Meera local-run verify
```

---

## Vikram — ACTIVE campaign not editable (2026-08-14)

Added the missing rule: `ensureEditable()` only blocked COMPLETED/CANCELLED — ACTIVE (and PAUSED/PENDING_APPROVAL/DRAFT) passed every PATCH through freely.

**Confirmed status changes share the update() path — no separate endpoint.** There is exactly one PATCH `/campaigns/{id}` (`CampaignController.java:72-78` → `CampaignService.update`). `campaignsApi.update()` (`src/lib/api.ts:1328-1335`) is the only FE call site for status changes too — `brand-campaign-detail.tsx:778` and `campaigns-list.tsx:414` both call `api.campaigns.update(id, { status: next })` for pause/resume/cancel/complete, sending a status-only body through the same PATCH. So a blanket ACTIVE block would have broken every brand's ability to pause/cancel/complete a live campaign — confirmed by reading before writing the guard.

**Fix: status-only patches are exempted.** `CampaignValidator` gets an overload `ensureEditable(CampaignStatus current, boolean statusOnlyPatch)` — COMPLETED/CANCELLED stay unconditionally blocked (delegates to the original method); ACTIVE is blocked only when `!statusOnlyPatch`. `CampaignService.update()` computes `isStatusOnlyPatch(req)` (true iff every `CampaignPatchRequest` field except `status` is null) and passes it in. New code `CAMPAIGN_ACTIVE_NOT_EDITABLE` (409), distinct from the existing `CAMPAIGN_NOT_EDITABLE` (409, terminal states) — grepped the FE for `CAMPAIGN_NOT_EDITABLE` first and confirmed zero existing special-case handling, so the new code is free to use for a follow-up FE gate without colliding with anything.

**Error contract for the FE follow-up task:** PATCH `/campaigns/{id}` on an ACTIVE campaign with any non-status field set → `409 { code: "CAMPAIGN_ACTIVE_NOT_EDITABLE", message: "Cannot edit an active campaign — pause it first, or send a status-only update to pause/complete/cancel it" }`. A body containing ONLY `status` (e.g. `{ status: "PAUSED" }`) still succeeds on ACTIVE.

**Tests added to `CampaignServiceTest.java`** (real file — no `CampaignValidatorTest`/`CampaignControllerTest` exist yet, deliberately didn't create one to avoid colliding with other in-flight validator work per the existing `CampaignValidatorHypeUrlTest` file-header note): ACTIVE + field edit → 409 `CAMPAIGN_ACTIVE_NOT_EDITABLE`, nothing persisted; ACTIVE + status-only (pause) → 200, persisted; ACTIVE + status bundled with a field edit → still 409 (status-only is the only exemption, not "status present"); PAUSED + field edit → 200 (regression); PENDING_APPROVAL + field edit → 200 (regression); COMPLETED + status-only patch → still 409 `CAMPAIGN_NOT_EDITABLE` (terminal states stay unconditional).

**Real test output:** `mvn -o -Dtest=CampaignServiceTest,CampaignValidatorHypeUrlTest test` → both green — `CampaignServiceTest`: 31/31 (23 pre-existing + 8 new), `CampaignValidatorHypeUrlTest`: 7/7, BUILD SUCCESS. Regression sanity `mvn -o -Dtest=CreatorCampaignServiceTest,CampaignTemplateControllerTest,CreatorCampaignControllerTest,MeeraInternalControllerCreateCampaignTest,CampaignTrackingControllerTest test` → exit 0, no failures.

Files: `influora-api/src/main/java/com/influora/service/CampaignValidator.java` (new `ensureEditable(CampaignStatus, boolean)` overload), `influora-api/src/main/java/com/influora/service/CampaignService.java` (`update()` call site + new private `isStatusOnlyPatch`), `influora-api/src/test/java/com/influora/service/CampaignServiceTest.java` (6 new tests + 2 new request-builder helpers).

No frontend changes made — Edit-button gating for `CAMPAIGN_ACTIVE_NOT_EDITABLE` is a separate follow-up task per Arjun's framing.

```
Vikram → Arjun | ACTIVE campaign editability rule added, status-only patches (pause/resume/cancel/complete) confirmed to share update() and exempted so they still work, new code CAMPAIGN_ACTIVE_NOT_EDITABLE (409) | influora-api/.../CampaignValidator.java, CampaignService.java, CampaignServiceTest.java | READY_FOR_QA | NEXT: Kavya QA → Meera local-run verify → route CAMPAIGN_ACTIVE_NOT_EDITABLE + Edit-button-gating spec to Ananya for the FE follow-up
```
