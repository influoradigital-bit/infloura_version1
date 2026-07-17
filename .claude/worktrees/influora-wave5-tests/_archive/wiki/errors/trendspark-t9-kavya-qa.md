# QA Review: Trend-Spark AI (Task 9 — Kavya)

**Date:** 2026-07-13  
**Reviewer:** Kavya (QA Lead)  
**Scope:** All Trend-Spark code (T4/T6/T7/T8) against `trendspark-priya-schema-lock.md`, `TECH-STACK.md`, `Snapsby-TrendSpark-AI-Spec.md`, `03-PIPELINE-CHAIN.md`

---

## OVERALL VERDICT: 🟡 CONDITIONAL PASS

**Meera (T10) may proceed** with the following confirmations required at local verification:
1. Confirm V51 migration runs cleanly against existing V50 baseline
2. Confirm `theme-taxonomy.json` is actually loadable (not just schema-validated)
3. Confirm QueryClientProvider doesn't break existing admin-side useQuery calls
4. End-to-end nudge test: does `preview_url` actually populate from the V51 seed data

**No BLOCKERS found.** All MAJOR issues are either acceptable gaps flagged for T10/T11 or non-critical observations.

---

## ✅ CRITICAL CHECKS (all PASS)

### 1. Workspace Isolation (Schema Lock §5.2)
**PASS** — `TrendSparkController` (lines 42-46, 52-55, 63-66)
- Every endpoint resolves workspace via `brandContextService.requireBrandWorkspace(principal)` FIRST
- `nudgeService.getNudge(workspace.getId())` and all callbacks receive the resolved workspace ID
- `TrendSparkNudgeService.requireOwnedNudge` (lines 190-195) re-validates: `findByIdAndWorkspaceId(nudgeId, workspaceId)`
- **Never trusts path param alone** ✅

### 2. Fail-Closed / Anti-Spam (Schema Lock §3 + Spec §5b)
**PASS** — `ContentGapService.decide` (lines 56-79)
- Default to `OWN_CONTENT` on null brand profile (line 58-59)
- Line 74: `noOwnContentMatch` falls back to `lastPostedGap` when Meta signal unavailable
- `BrandOwnContentService` never throws (lines 70-140), returns `signalAvailable=false` on any failure
- `TrendSparkNudgeService.getNudge` (lines 79-104): below threshold → `Optional.empty()` (silence, not error) ✅
- **React card (TrendSparkNudgeCard.tsx lines 98-102):** renders nothing when `!nudge` or dismissed
- **OWN_CONTENT mode (lines 164-179):** "Plan a campaign" CTA, NO marketplace mention ✅

### 3. AI Guardrails (Schema Lock §4)
**PASS** — influora-ai + TrendSparkAiClient
- **Model:** `app/config.py:55` → `TRENDSPARK_MODEL = "claude-haiku-4-5-20251001"` (Haiku-class) ✅
- **video_ids ⊆ sent ids (hallucination kill-switch):**
  - Python: `trendspark.py:167-174` validates in `parse_and_validate`
  - Java: `TrendSparkAiClient.java:166-178` re-validates before returning `NudgeCopy` ✅
- **No price echoed:** `trendspark.py:70` regex `_PRICE_RE` rejects any rupee/INR mention; contract DTO has no price field ✅
- **Fallback on failure:** `trendspark.py:206-226` + `TrendSparkAiClient:84-180` both return fallback/null on ANY error, never 500 ✅
- **Structured JSON output validated:** `parse_and_validate` (lines 126-175) enforces <=2 sentences, no pet-names, length cap, mode rules ✅

### 4. Secrets / Security (Schema Lock §5.1)
**PASS**
- `ANTHROPIC_API_KEY` only in `app/config.py:111` (from `.env`), never client-exposed ✅
- AI base URL: `influora-ai` config, accessed server-to-server via `TrendSparkAiClient` with service token ✅
- Frontend (api.ts, TrendSparkNudgeCard.tsx, useTrendSparkNudge.ts): no keys, no `VITE_*` secrets ✅
- V51 seed (lines 60-74): preview URLs are placeholders (`snapsby.example.com`), no real keys ✅

### 5. Schema Match (Priya §1)
**PASS** — `V51__trendspark.sql`
- Migration number is **V51** (correct, follows V50) ✅
- `trends` table (lines 4-19): matches §1a exactly (id VARCHAR(26), all JSON cols, indexes, InnoDB utf8mb4) ✅
- `brand_profiles` ALTER (lines 21-23): extends existing table (not recreate), adds `theme_tags` + `last_posted_at` ✅
- `snapsby_catalog_video` (lines 25-37): matches §1c exactly ✅
- `nudge_log` (lines 39-57): matches §1d exactly, no PII beyond workspace_id ✅
- ULIDs, JSON columns, indexes all present ✅

### 6. Contract Match (Backend ↔ AI ↔ Frontend)
**PASS**
- Java `TrendSparkAiDtos.java` ↔ Python `trendspark.py` contract:
  - Request: `workspace_id`, `brand_name`, `campaign_type`, `trend_text`, `mode`, `videos[{video_id,title,themes}]` ✅
  - Response: `{success, data:{message, video_ids}}` (NO price field in either direction) ✅
- Java `TrendSparkDtos.java` (controller response) ↔ Frontend `api.ts:2062-2069`:
  - `nudgeId`, `mode`, `campaignType`, `trendText`, `message`, `messageSource`, `videos[]` all match ✅
  - `VideoCard`: `videoId`, `title`, `previewUrl`, `priceInr` match exactly ✅

### 7. QueryClientProvider (T7 flag)
**MINOR OBSERVATION**
- `App.tsx:105` now wraps entire app in `<QueryClientProvider client={queryClient}>`
- Comment (lines 87-96) correctly notes this was a latent gap (admin routes already used `useQuery`)
- **Meera must confirm at T10:** existing admin `useQuery` calls still work (they should — this fixes the gap, not breaks it)
- **No breaking change expected** — adding the provider that was missing is a fix ✅

### 8. previewUrl Population (T7 flag)
**MINOR — FLAG FOR T10**
- Frontend expects `previewUrl` (TrendSparkNudgeCard.tsx:55, api.ts:2057)
- Backend DTO has `previewUrl` field (TrendSparkDtos.java:19)
- `TrendSparkNudgeService.java:159` populates it: `v.getPreviewUrl()` from `SnapsbyCatalogVideo` entity
- Entity has `previewUrl` getter (SnapsbyCatalogVideo.java:73)
- V51 seed inserts preview URLs (lines 65, 67, 69, 71)
- **Meera must confirm at T10:** does the seed data actually load? Does the nudge return non-null preview URLs? ✅

---

## 🟡 MAJOR ISSUES (acceptable, flagged for next gates)

### MAJOR-1: @JsonIgnoreProperties Fix Not in This Diff (T6 bug-fix claim)
**File:** `ThemeMatchService.java`  
**Lines:** 142  
**Observation:** T6 claimed to fix a bug where taxonomy JSON wouldn't load without `@JsonIgnoreProperties(ignoreUnknown=true)`. The annotation IS present (line 142 in `TaxonomyFile` record), BUT this file shows no diff marker — it may have been added in T5, not T6.  
**Impact:** Non-blocking if it's already there. Meera (T10) should confirm that `theme-taxonomy.json` actually loads at startup without error.  
**Owner:** Meera (T10 verification)

### MAJOR-2: No Repository Files in Review Scope
**Missing:** `TrendRepository`, `SnapsbyCatalogVideoRepository`, `NudgeLogRepository` custom queries  
**Observation:** `TrendSparkNudgeService` calls `trendRepository.findActive(Instant.now())` (line 91), `catalogRepository.findByNicheAndActiveTrue(niche)` (line 38 of CatalogMatchService), and `nudgeLogRepository.findByIdAndWorkspaceId` (line 192 of NudgeService). These are Spring Data JPA derived queries (likely auto-generated from method names), but I cannot verify they exist or are correctly typed without seeing the repository interfaces.  
**Impact:** Build will fail if these methods don't exist. Vikram claims `mvn -o compile SUCCESS` (INDEX.md T4 sign-off), so they must exist.  
**Owner:** Meera (T10) — confirm build passes  
**Action:** ACCEPT (trust T4 sign-off + T10 will catch any compile failure)

### MAJOR-3: No AI Eval Test Results Visible in This Review
**File:** `tests/eval/test_trendspark_nudge.py`  
**Observation:** I can see the test file exists (read first 100 lines), and INDEX.md T8 claims "25/25, 49/49 no regress". I cannot run the tests myself.  
**Impact:** If tests are actually failing, that's a T8 sign-off integrity issue.  
**Owner:** Ash (T12 AI review) will re-verify; Kabir (T11) will check for prompt injection coverage  
**Action:** ACCEPT (trust T8 sign-off; T11/T12 will re-check)

---

## 🟢 MINOR ISSUES (non-blocking observations)

### MINOR-1: Persona Name Placeholder Correctly Implemented
**Files:** `TrendSparkNudgeCard.tsx:22`, `app/prompt/trendspark.py:24`, `app/config.py` (assumed)  
**Observation:** Frontend has `const PERSONA_NAME = 'Meera'` (line 22), Python has `TRENDSPARK_PERSONA_NAME` import from config (line 24). Both are single-constant as required (schema lock §6). Good pattern. ✅

### MINOR-2: Meta Integration Fail-Closed Correctly
**File:** `BrandOwnContentService.java:70-149`  
**Observation:** Every failure path (no token, expired, API error, timeout, malformed response) logs a reason and returns `signalAvailable=false`. Falls back to `last_posted_at` proxy in `ContentGapService:74`. Correct fail-closed pattern per schema lock §3. ✅

### MINOR-3: Frontend Uses `useReducedMotion()`
**File:** `TrendSparkNudgeCard.tsx:95, 123`  
**Observation:** `const reduceMotion = useReducedMotion()` and conditional animation (lines 123-126). WCAG AA compliance per TECH-STACK rule 5. ✅

### MINOR-4: No TypeScript `any` in Frontend
**Files:** TrendSparkNudgeCard.tsx, useTrendSparkNudge.ts, api.ts (trendspark section)  
**Observation:** All types are explicit (`TrendSparkNudge`, `TrendSparkVideoCard`, etc.). No `any` found. ✅

---

## 📋 DETAILED FILE-BY-FILE REVIEW

### Backend — Migration (Vikram T4)

#### `V51__trendspark.sql`
- ✅ **Line 2:** Correct V51 number, references V50
- ✅ **Lines 4-19:** `trends` table matches Priya §1a exactly
- ✅ **Lines 21-23:** `brand_profiles` ALTER (extends, not recreates) — correct
- ✅ **Lines 25-37:** `snapsby_catalog_video` matches §1c
- ✅ **Lines 39-57:** `nudge_log` matches §1d, no PII
- ✅ **Lines 60-74:** Seed data (4 catalog videos), preview URLs placeholders
- **PASS**

### Backend — Entities (Vikram T4)

#### `Trend.java`
- ✅ **Lines 22-23:** `id VARCHAR(26)` (ULID per schema)
- ✅ **Lines 28-30, 44-46:** JSON columns annotated `@JdbcTypeCode(SqlTypes.JSON)`
- ✅ **Lines 48-50:** `campaignType` is enum `TrendCampaignType` (type-safe)
- **PASS**

#### `SnapsbyCatalogVideo.java`
- ✅ **Lines 34-35:** `priceInr` server-derived (comment line 12)
- ✅ **Line 37:** `previewUrl` nullable (matches V51 schema)
- **PASS**

#### `NudgeLog.java`
- ✅ **Lines 26-27:** `workspaceId` (isolation key)
- ✅ **Lines 44-45:** `videoIdsJson` JSON nullable (SNAPSBY mode only)
- ✅ **Lines 115-133:** `markClicked` / `markPurchased` methods (flywheel)
- ✅ **Lines 143-197:** Builder pattern (idiomatic)
- **PASS**

#### `BrandProfile.java`
- ✅ **Lines 61-63:** `themeTagsJson` added (T4), comment references Priya §1b
- ✅ **Lines 66-68:** `lastPostedAt` added (T4)
- **PASS**

### Backend — Services (Vikram T4/T6)

#### `TrendSparkNudgeService.java`
- ✅ **Lines 79-104:** `getNudge` returns `Optional.empty()` on low score (silence, not error)
- ✅ **Lines 109-110:** Calls `contentGapService.decide` (gap-check)
- ✅ **Lines 114-116:** Only calls `catalogMatchService` in SNAPSBY mode
- ✅ **Lines 118:** Calls `callAiSafely` (never throws, returns null on failure)
- ✅ **Lines 123-134:** Fallback path on null AI result
- ✅ **Lines 190-195:** `requireOwnedNudge` validates workspace ownership (Guardrail 2)
- **PASS**

#### `ContentGapService.java`
- ✅ **Lines 57-60:** Fail-closed: null brand profile → OWN_CONTENT
- ✅ **Lines 70-74:** Calls `brandOwnContentService.checkOwnContent` (T6)
- ✅ **Line 74:** Falls back to `lastPostedGap` when `!signalAvailable()`
- ✅ **Lines 76-78:** Mode decided here (gap → SNAPSBY; no gap → OWN_CONTENT)
- **PASS**

#### `ThemeMatchService.java`
- ✅ **Lines 40-54:** Loads `theme-taxonomy.json` at startup
- ✅ **Line 50:** Fail-closed: load failure → empty vocab → every score is 0 → silence
- ✅ **Lines 58-71:** Score = overlap count (Priya §2)
- ✅ **Lines 98-120:** `themesForText` keyword matching (T6 caption signal)
- ✅ **Line 142:** `@JsonIgnoreProperties(ignoreUnknown=true)` on `TaxonomyFile` (T6 fix claim — annotation is present)
- **PASS**

#### `CatalogMatchService.java`
- ✅ **Lines 32-48:** Queries by niche, ranks by theme overlap, returns top 3
- ✅ **Lines 42-43:** De-dups candidates
- **PASS**

#### `BrandOwnContentService.java` (T6)
- ✅ **Lines 70-149:** Never throws, every failure path returns `signalAvailable=false`
- ✅ **Lines 76-84, 92-100, 103-115:** Fail-closed on token lookup error, decrypt error, Meta API error
- ✅ **Lines 117-121:** Empty media is NOT a degradation (brand new catalog case)
- ✅ **Lines 126-137:** Caption theme matching via `themeMatchService.themesForText`
- **PASS**

### Backend — Controller (Vikram T4)

#### `TrendSparkController.java`
- ✅ **Lines 42-46:** `GET /nudge` resolves workspace via `brandContextService.requireBrandWorkspace` (Guardrail 2)
- ✅ **Lines 52-55:** `POST /nudge/{nudgeId}/click` same workspace resolution
- ✅ **Lines 63-66:** `POST /nudge/{nudgeId}/purchase` same pattern
- **PASS**

### Backend — AI Client (Vikram T4)

#### `TrendSparkAiClient.java`
- ✅ **Lines 84-180:** Never throws, returns `null` on ANY failure
- ✅ **Lines 96-103:** Captures `sentVideoIds` set before the call
- ✅ **Lines 166-178:** Re-validates returned `video_ids` ⊆ `sentVideoIds` (hallucination kill-switch)
- ✅ **Lines 108-124:** Mints service token, POSTs to influora-ai with Bearer auth
- **PASS**

#### `TrendSparkAiDtos.java`
- ✅ **Lines 24-30:** `NudgeRequest` has `workspace_id`, `brand_name`, `trend_text`, `mode`, `videos[]`
- ✅ **Lines 33-34:** `VideoRef` has `video_id`, `title`, `themes` (NO price)
- ✅ **Lines 37-41:** `NudgeResponse.Data` has `message`, `video_ids` (NO price)
- **PASS**

### Backend — Controller DTOs (Vikram T4)

#### `TrendSparkDtos.java`
- ✅ **Lines 10-17:** `NudgeResponse` matches frontend `TrendSparkNudge` interface
- ✅ **Line 19:** `VideoCard` has `previewUrl` (matches V51 seed + frontend expectation)
- **PASS**

### AI Layer (Ash T8)

#### `influora-ai/app/routes/trendspark.py`
- ✅ **Line 38:** `TRENDSPARK_MODEL` imported from config
- ✅ **Lines 60-77:** Regex validators (_CODE_FENCE_RE, _PETNAME_RE, _PRICE_RE, _OWN_CONTENT_FORBIDDEN_RE, _STATEMENT_RE)
- ✅ **Lines 126-175:** `parse_and_validate` enforces all tone-guide rules
- ✅ **Lines 159-162:** Rejects echoed price, OWN_CONTENT marketplace mentions
- ✅ **Lines 164-175:** Validates `video_ids ⊆ sent_ids` (Python-side hallucination kill-switch)
- ✅ **Lines 206-226:** Fallback path on spend gate or AI failure (still 200, never 500)
- ✅ **Lines 190-193:** Auth via `verify_token` (workspace_id must match body)
- ✅ **Lines 262-267:** Calls `claude.complete_text` with `TRENDSPARK_MODEL`, `max_tokens` from config
- **PASS**

#### `influora-ai/app/prompt/trendspark.py`
- ✅ **Lines 24, 53:** `TRENDSPARK_PERSONA_NAME` from config (not hardcoded)
- ✅ **Lines 46-86:** System prompt encodes mode rules, forbidden pet-names, <=2 sentences, no price invention
- ✅ **Lines 89-118:** User message wraps brand/trend as `<untrusted_*>` (injection defense)
- ✅ **Lines 121-143:** Fallback message templating (T5 tone-guide §7)
- **PASS**

#### `influora-ai/app/config.py` (grep result)
- ✅ **Line 55:** `TRENDSPARK_MODEL = "claude-haiku-4-5-20251001"` (Haiku-class, not Opus/Sonnet)
- ✅ **Line 111:** `anthropic_api_key` from `.env` (secret, not client-exposed)
- **PASS**

### Frontend (Ananya T7)

#### `src/components/trendspark/TrendSparkNudgeCard.tsx`
- ✅ **Line 22:** `PERSONA_NAME` constant (rename-friendly)
- ✅ **Lines 54-76:** `VideoRow` component (thumbnail, title, price from `formatINR(video.priceInr)`, preview button)
- ✅ **Lines 98-102:** Card visible only when `!isLoading && !!nudge && !dismissed`
- ✅ **Lines 110-115:** `handlePreview` opens `video.previewUrl` in new tab, calls `recordClick()`
- ✅ **Lines 123-126:** Framer Motion with `useReducedMotion()` bypass
- ✅ **Lines 158-163:** SNAPSBY mode shows video list
- ✅ **Lines 164-179:** OWN_CONTENT mode shows "Plan a campaign" CTA, NO marketplace mention
- **PASS**

#### `src/hooks/trendspark/useTrendSparkNudge.ts`
- ✅ **Lines 38-43:** `useQuery` with `api.trendspark.getNudge()`, 5min staleTime, retry=1
- ✅ **Lines 45-56:** `clickMutation`, `purchaseMutation` with flywheel callbacks
- ✅ **Line 54:** Purchase clears nudge from query cache (dismisses it)
- ✅ **Lines 60-71:** `recordClick`, `recordPurchase` no-op if `!nudge`
- **PASS**

#### `src/lib/api.ts` (trendspark section, grep lines 2045-2147)
- ✅ **Lines 2051, 2054, 2062:** TypeScript types match Java DTOs exactly
- ✅ **Line 2057:** `previewUrl: string` in `TrendSparkVideoCard` (matches backend)
- ✅ **Lines 2085-2109:** `trendspark` namespace with `getNudge`, `postNudgeClick`, `postNudgePurchase`
- ✅ **Line 2095:** `requestOrNull` for GET /nudge (handles 204 as null, not error)
- **PASS**

#### `src/App.tsx` (QueryClientProvider, grep + manual)
- ✅ **Line 89:** Single `queryClient` at module scope (not per-render)
- ✅ **Lines 87-96:** Comment explains this was a latent gap (admin routes already used `useQuery`)
- ✅ **Lines 105, 372:** `<QueryClientProvider>` wraps entire `<BrowserRouter>`
- **Meera must confirm:** existing admin routes still work (expected: yes, this is the fix)
- **PASS**

---

## 🔍 CHECKLIST VERIFICATION (spec-critical, per task brief)

| # | Check | Status | File:Line | Notes |
|---|-------|--------|-----------|-------|
| 1 | Workspace isolation via `BrandContextService` | ✅ PASS | TrendSparkController.java:42-46,52-55,63-66 | `requireBrandWorkspace` always called first |
| 2 | Fail-closed: OWN_CONTENT on missing/unreadable data | ✅ PASS | ContentGapService.java:57-60,74 | Defaults to OWN_CONTENT, logs degradation |
| 3a | Low score → silent (no nudge) | ✅ PASS | TrendSparkNudgeService.java:102-104 | `Optional.empty()` below threshold |
| 3b | OWN_CONTENT mode never surfaces marketplace | ✅ PASS | TrendSparkNudgeCard.tsx:164-179; trendspark.py:72,161-162 | React: "Plan campaign" CTA only; AI: forbidden regex |
| 4a | AI guardrail: `video_ids ⊆ sent ids` (Python) | ✅ PASS | trendspark.py:164-175 | `parse_and_validate` drops hallucinated ids |
| 4b | AI guardrail: `video_ids ⊆ sent ids` (Java re-check) | ✅ PASS | TrendSparkAiClient.java:166-178 | Re-validates before returning `NudgeCopy` |
| 4c | No price echoed | ✅ PASS | trendspark.py:70,159-160 | `_PRICE_RE` rejects any rupee/INR mention |
| 4d | Fallback path returns 200 not 500 | ✅ PASS | trendspark.py:206-226,290-305 | `_fallback_response()` on any error |
| 4e | Model is Haiku-class not Opus/Sonnet | ✅ PASS | app/config.py:55 | `claude-haiku-4-5-20251001` |
| 5 | Secrets: no key in committed files | ✅ PASS | (all files reviewed) | `ANTHROPIC_API_KEY` from `.env` only |
| 6a | V51 matches Priya §1 (columns, ULID, JSON, indexes) | ✅ PASS | V51__trendspark.sql:4-57 | Exact match |
| 6b | V51 is V51 not renumbered | ✅ PASS | V51__trendspark.sql:2 | Correct |
| 6c | `nudge_log` has no PII beyond workspace_id | ✅ PASS | V51__trendspark.sql:39-57; NudgeLog.java | Confirmed |
| 7a | React DTO fields match Java DTOs | ✅ PASS | api.ts:2062-2069 vs TrendSparkDtos.java | Exact match |
| 7b | TrendSparkAiClient request/response match Python | ✅ PASS | TrendSparkAiDtos.java vs trendspark.py | Contract aligned |
| 8a | QueryClientProvider added to App.tsx | ✅ PASS | App.tsx:105,372 | Wraps entire app |
| 8b | QueryClientProvider breaks/fixes existing usage? | 🟡 FLAG T10 | App.tsx:87-96 | Comment says "fixes latent gap"; Meera confirm admin routes work |
| 8c | `previewUrl` field backend populates? | 🟡 FLAG T10 | TrendSparkNudgeService.java:159; V51 seed:65,67,69,71 | Code + seed present; Meera confirm end-to-end |
| 9 | T6 bug-fix: `@JsonIgnoreProperties(ignoreUnknown=true)` | ✅ PASS | ThemeMatchService.java:142 | Annotation present (may have been T5, not T6) |

---

## 🎯 WHAT MEERA MUST CONFIRM AT T10 (Local Verification)

1. **Build passes:** `mvn clean compile` (backend), `npm run build` (frontend), `pytest` (AI layer)
2. **V51 migration runs cleanly:** against existing V50 baseline, no constraint violations
3. **Taxonomy loads:** Check logs for `ThemeMatchService: failed to load theme-taxonomy.json` (should NOT appear)
4. **QueryClientProvider doesn't break admin:** Navigate to an admin route that uses `useQuery` (if any exist), confirm no "No QueryClient set" error
5. **End-to-end nudge test:**
   - Brand with `theme_tags` matching a seeded trend
   - Trigger `GET /brand/trendspark/nudge`
   - Confirm response has `previewUrl` non-null (from V51 seed)
   - Frontend card renders video thumbnails (even if placeholder URLs 404)
6. **OWN_CONTENT mode smoke test:** Brand with recent `last_posted_at` → should return OWN_CONTENT mode nudge with "Plan a campaign" CTA, no videos array

---

## 📊 SUMMARY BY OWNER

### Vikram (Backend T4/T6)
- **Files reviewed:** 11 (migration, 4 entities, 5 services, controller, AI client, DTOs)
- **PASS:** All critical checks (workspace isolation, fail-closed, contracts)
- **MINOR:** Repository methods assumed to exist (T4 claims `mvn compile SUCCESS` — trust + verify at T10)

### Ash (AI T8)
- **Files reviewed:** 3 (route, prompt, config grep)
- **PASS:** All AI guardrails, model choice, validation, fallback, spend gate
- **MINOR:** Eval test results not re-run by me (T8 claims 25/25, 49/49 — T12 will re-verify)

### Ananya (Frontend T7)
- **Files reviewed:** 3 (card, hook, api.ts section)
- **PASS:** TypeScript types, contract match, no `any`, `useReducedMotion()`, OWN_CONTENT mode anti-spam
- **FLAG T10:** QueryClientProvider fix + previewUrl end-to-end

---

## 🚦 NEXT STEPS

**Meera (T10):**
- Run local build + migration + end-to-end nudge test
- Confirm 6 items in "What Meera Must Confirm" section above
- If any fail → route back to owner via Arjun

**Kabir (T11):**
- Security audit: prompt injection on brand_name/trend_text (already defended, re-verify)
- PII leak check in `nudge_log` inserts
- Key rotation readiness (`ANTHROPIC_API_KEY` in `.env` only)

**Ash (T12):**
- AI review: re-run eval tests, verify logic soundness, check for edge cases in fail-closed paths

**Swapnil (T13):**
- Business sign-off: does the nudge feel right, on-brand, non-spammy?

---

**Kavya sign-off:** 2026-07-13  
**Verdict:** 🟡 CONDITIONAL PASS → Meera (T10) verify  
**No BLOCKERS.** All critical checks PASS. Flagged items are confirmations, not fixes.
