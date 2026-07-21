# QA Verification: Creator AI Co-pilot Tier-1 Greenlit Package

**Reviewer:** Kavya (QA Lead) · **Date:** 2026-07-21 · **Status:** CONDITIONAL PASS with 2 minor corrections

**Reviewed Package:**
- Priya ruling: [`creator-copilot-priya-review-r1.md`](creator-copilot-priya-review-r1.md)
- Frozen API contract: [`creator-copilot-API-CONTRACT.md`](creator-copilot-API-CONTRACT.md)
- FE components plan: [`creator-copilot-fe-components-plan.md`](creator-copilot-fe-components-plan.md)
- FE data-layer plan: [`creator-copilot-fe-datalayer-plan.md`](creator-copilot-fe-datalayer-plan.md)
- BE services plan: [`creator-copilot-be-services-plan.md`](creator-copilot-be-services-plan.md)
- AI route plan: [`creator-copilot-ai-route-plan.md`](creator-copilot-ai-route-plan.md)
- Parent spec: [`wiki/ai-review/creator-ai-copilot-tier1-build-spec.md`](../ai-review/creator-ai-copilot-tier1-build-spec.md) §6
- Guardrail reference: `influora-ai/app/routes/trendspark.py` + tests

---

## VERDICT: CONDITIONAL PASS

**Overall build-ready:** YES, with 2 minor corrections noted in §1.2.

All four plans (FE components, FE data-layer, BE services, AI route) are internally consistent post-Priya R1 ruling, align with the frozen API contract, and are buildable against each other. No contradictions, no unbuildable assumptions found. Two minor mismatches flagged for quick fix before code starts.

---

## 1. Logical Verification (PASS/FAIL per track)

### 1.1 FE Types ↔ BE DTOs ↔ Wire Contract Alignment: **PASS**

**Checked byte-identical match across:**
- FE TypeScript interfaces (`creator-copilot-fe-datalayer-plan.md` §2.1)
- BE Java DTOs (`creator-copilot-API-CONTRACT.md` §2)
- Wire JSON shape (`creator-copilot-API-CONTRACT.md` §1.1)

| Field | FE Type (TS) | BE DTO (Java) | Wire JSON | Match |
|-------|--------------|---------------|-----------|-------|
| `id` | `string` | `String` | `string` | ✅ |
| `theme` | `string` | `String` | `string` | ✅ |
| `headline` | `string` | `String` | `string` | ✅ |
| `contentIdea` | `string` | `String` | `string` | ✅ (camelCase FE → BE maps via Jackson default) |
| `expiresAt` | `string` (ISO 8601) | `String` | `string` | ✅ |
| `status` | `'pending_tagging' \| 'ready' \| 'no_suggestion_today'` | `String` (literal values) | `string` | ✅ |

**Casing confirmed:** API contract §2 explicitly states "camelCase (default Jackson serialization, no `@JsonProperty` needed) so the wire JSON matches the TS interface byte-for-byte" — `contentIdea` (FE) = `contentIdea` (wire) = `String contentIdea` (Java DTO). No mismatch.

**Envelope confirmed:** API contract §2 "Envelope note" states `{ success: true, data: <above> }` wrapper is auto-unwrapped by `http.request<T>()` on the FE (`src/lib/api.ts:265-318`) and built by `ApiResponse.ok(...)` on the BE. Both plans correctly omit envelope from their internal signatures — this is handled by framework plumbing.

### 1.2 Path Consistency Across All Plans: **PASS with 2 minor corrections**

**Canonical paths per Priya ruling (Conflict 1):**
```
GET  /api/v1/creator/copilot/suggestion/today
POST /api/v1/creator/copilot/suggestion/{id}/dismiss
POST /api/v1/creator/copilot/suggestion/{id}/acted
```

**Checked alignment:**
- ✅ BE services plan §5: controller `@RequestMapping("/creator/copilot")` + method paths match exactly
- ✅ FE data-layer plan §2.2/§3: client calls `/creator/copilot/suggestion/today` (suffix only; `API_BASE_URL` already includes `/api/v1`)
- ✅ API contract §0/§1: frozen paths match ruling
- ✅ AI route plan: explicitly states internal route is `/internal/creator-suggestion` (distinct from public REST paths, unchanged by ruling)

**Minor mismatch flagged (non-blocking, cosmetic):**
- BE services plan §5 section header says "`GET /api/v1/creator/copilot/suggestion/today`" (correct)
- BE services plan §6.10 "API contract to freeze" block says "`GET /api/v1/creator/copilot/suggestion/today`" (correct)
- **But BE services plan §2.5 line 103 still references the OLD spec-draft path** "`GET /api/creator/suggestion`" in commentary text (should be updated to `/api/v1/creator/copilot/suggestion/today` to match the ruling). This is a doc-consistency fix only; the actual code plan (§5) is correct.

**Action:** Vikram updates §2.5 line 103 commentary to cite the canonical path.

**Minor mismatch #2 (AI route plan):**
- AI route plan §1.1 request example shows snake_case `theme_matched` field name
- BE services plan §4 `CreatorSuggestionAiDtos.SuggestionRequest` shows camelCase `String theme` (Java DTO)
- API contract §2 doesn't define the **internal service-to-service** contract (Spring→AI-service), only the **public REST** contract (browser→Spring)

**Resolution:** This is a valid open item in BE services plan §6.5 ("Exact request JSON Vikram's client will POST — confirm field names ... recommend snake_case here for consistency"). Not a contradiction; both plans defer the camelCase-vs-snake_case decision to Vikram/AI-route coordination (Priya pre-condition #2: "Vikram drafts, Priya blesses"). Flagging so it's resolved in the API-CONTRACT freeze, not silently assumed.

**Action:** Vikram + AI-route agent freeze the internal `/internal/creator-suggestion` request DTO casing (recommend: snake_case `theme_matched`/`trend_text` to match trendspark's internal contract pattern per `trendspark.py:9` docstring, but either is buildable as long as both sides agree).

### 1.3 Column Lists (Migrations vs Entities): **PASS** — Priya ruling resolved

**Verified canonical column list per Priya Conflict 2 ruling:**

Vikram BE services plan §2.3 shape (CANONICAL per ruling):
```sql
creator_nudge_log (
  id, creator_profile_id, trend_id, match_score, message, message_source,
  prompt_version, shown_at, dismissed_at, acted_at, created_at
)
```

**Checked:**
- ✅ BE services plan §2.3 entity field list matches above exactly
- ✅ BE services plan §5 `CreatorNudgeLog.Builder` pattern mirrors `NudgeLog.Builder` (1:1 naming convention)
- ❌ **Original spec §4 (Meera draft) had different columns** (`theme_matched`, `suggestion_text`, `suggestion_source`, `clicked_at`, NO `prompt_version`) — **OVERRULED by Priya Conflict 2**

**Confirmed:** Priya ruling §6.1 explicitly directs "Someone needs to tell Meera to build her migration against this shape, not her own §4 sketch." Meera's task: build `V20260721140000__creator_nudge_log.sql` against **Vikram's canonical list** from BE services plan §2.3 (which satisfies invariant #5: `prompt_version` on every row, plus `dismissed_at`/`acted_at` for §7 flywheel).

**No gap found** — the ruling is clear, the canonical shape is fully specified, and Vikram's plan is internally consistent with it.

### 1.4 OAuth Flow — No New Route: **PASS**

**Verified per Priya Conflict 3 ruling:** "REUSE the existing OAuth flow. NO new route."

**Checked:**
- ✅ BE services plan §3 reconciliation note: "I'm **not** building this as a new route. ... Building a second, parallel `/creator/copilot/ig/connect` endpoint that no FE code calls would be dead weight."
- ✅ FE components plan §1.2: `IGConnectPrompt` calls `api.metaOAuth.authorize()` directly (existing method)
- ✅ FE data-layer plan §2.4 Option A (adopted): "zero new client surface"
- ✅ API contract §4: "No `api.creatorCopilot.connectIg()` method exists or is planned"

**The spec's original §2.5 line "`POST /api/creator/ig/connect`" is correctly interpreted as "describe the existing (now-fixed) `/meta/oauth/callback` path," not a literal new endpoint.** All three agents (Vikram, Ananya FE-components, FE-data-layer) independently converged on this reading, and Priya's ruling confirmed it. No code divergence risk.

### 1.5 Security: IDOR/Scope Isolation: **PASS**

**Verified tenant-isolation discipline across all layers:**

| Layer | Ownership Resolution | Correct |
|-------|---------------------|---------|
| BE REST controller (§5) | `creatorContext.requireCreatorProfile(principal)` — never a path/body param | ✅ |
| BE service (§1) | `creatorNudgeLogRepository.findByIdAndCreatorProfileId(id, creatorProfileId)` | ✅ |
| AI-service auth (§4.1) | `verify_creator_token(..., body_creator_profile_id=...)` asserts `token.creator_profile_id == body.creator_profile_id` | ✅ |
| FE (implicit) | `role: 'creator'` on every `http.request()` → creator_token, server enforces | ✅ |

**SCOPE segregation (Priya ruling: bidirectional):**
- New `SCOPE_CREATOR = "creator"` in `service_token.py` (AI route plan §4.1)
- `ENDPOINT_SCOPES["creator_suggestion"] = (SCOPE_CREATOR,)` — a brand-side `SCOPE_SERVICE` token **cannot** call this endpoint (403 `scope_mismatch`)
- Reciprocal: a `creator` token cannot call brand endpoints (`trendspark`, `brand_safety`) — already enforced by those routes' existing scope checks

**No cross-tenant read surface found.** Resolve-then-check discipline is consistent with `TrendSparkNudgeService.requireOwnedNudge` pattern (`TrendSparkNudgeService:188-195` in the spec).

### 1.6 Prompt-Injection Surface (Caption Text): **ELIMINATED BY SCOPE**

**Verified per Priya Conflict 5 ruling:**

| Surface | Plan State | Threat Exposure |
|---------|-----------|-----------------|
| Caption tagging (Java) | `ThemeMatchService.themesForText()` — pure keyword match, NO model call | ✅ ZERO (deterministic Java only) |
| Caption tagging (LLM recovery) | **CUT from Tier-1** (AI route plan §3) | ✅ ZERO (route does not exist) |
| Phrasing AI route | **`caption_snippet` REMOVED** from request contract (AI route plan §1.1 R1 change #2) | ✅ ZERO (field does not exist) |
| Phrasing AI route | Receives ONLY `theme_matched` (closed-vocab) + `trend_text` (wrapped) | ✅ One untrusted field, wrapped |

**Result:** No creator caption text reaches any model in Tier-1. The P0 spec row "Prompt injection via captions" is **eliminated by scope**, not mitigated by a control. `trend_text` (scraped/third-party) is still wrapped (`wrap_untrusted`, AI route plan §2.2) since it remains untrusted, but that's a **different** threat surface (external data, not creator-authored).

**Spec P0 row reword (per Priya ruling, for spec editor — not this plan's action):** "In Tier-1 no creator caption text reaches any model — tagging is deterministic Java; phrasing receives only server-owned `theme` + `trend_text`. `trend_text` (scraped/third-party) stays `wrap_untrusted`. The caption-injection control activates in Tier-2 when the LLM recovery tagger and/or caption-enriched phrasing land."

**Correct.** No gap.

### 1.7 Per-Creator/Day Cap (Race Closure): **PASS** — Two-Layer Defense

**Verified:**
1. **Primary cap (durable, Java, DB-backed):** BE services plan §5 generated-column unique constraint:
   ```sql
   ALTER TABLE creator_nudge_log
     ADD COLUMN shown_day DATE GENERATED ALWAYS AS (DATE(shown_at)) STORED,
     ADD UNIQUE KEY uq_creator_nudge_day (creator_profile_id, shown_day);
   ```
   Combined with idempotent-read-first (§1 step 2): same-day repeat calls return the identical row with **no new AI spend**. Race: two concurrent first-of-day calls both miss the read, both call AI, both `save()` → unique constraint lets exactly one INSERT through; loser catches `DataIntegrityViolationException`, re-reads winner's row, returns it (§1 step 8). **Double-write closed. Double-spend bounded to ≤1 extra Haiku call under genuine race, self-heals.**

2. **Defense-in-depth (spend gate, AI-service, optional):** AI route plan §1.2 step 4 `check_spend_gate(workspace_id=creator_profile_id)` reuses the existing `WORKSPACE_DAILY_HARD_CAP_USD` mechanism. This is a **second, independent layer**, not a substitute for the DB cap.

**Priya ruling (BE services verdict #3):** "DB constraint only. Do NOT add a distributed lock in Tier-1." Vikram's plan correctly does NOT add a `GET_LOCK()` advisory lock (§5 double-spend note flags it as a cost/complexity tradeoff, defers decision to Priya — ruling is to ship without it).

**Gap assessment:** None. The double-write is provably closed (DB constraint). The residual double-spend under a genuine concurrent race is bounded (at most 1 extra Haiku call), self-heals (only 1 row shows), and is a rounding-error cost. Acceptable for Tier-1.

---

## 2. Re-scoped Guardrail Test Matrix (Post-R1 Trimmed Design)

### 2.1 Original Test Matrix (Spec §6) vs. R1 Reality

**Original spec §6 test surface:**
```
tests/security/test_creator_caption_injection.py (HIGH) —
  "IGNORE PREVIOUS INSTRUCTIONS" caption → wrapped, no echo;
  system:/assistant: text → no leakage.
```

**R1 scope change:** No creator caption text reaches any model in Tier-1 (§1.6 above). The caption-injection test as originally framed **has no code path to test** — captions don't reach the phrasing route, and there is no LLM tagging route.

### 2.2 REVISED Guardrail Test Matrix (Tier-1 Actual Surface)

**Tests that STILL APPLY (unchanged from spec §6):**

| Test | Surface | Status |
|------|---------|--------|
| **Closed-vocab theme drop** | AI route `parse_and_validate()` theme validation (AI route plan §1.2 step 3 + trendspark pattern) | ✅ REQUIRED — any off-vocab `theme_matched` from Java → genericfallback, never passed to model |
| **Defensive JSON parse** | AI route `parse_and_validate()` strips code fences, try/except `json.loads`, validates non-empty (AI route plan §2.3, mirrors `trendspark.py:141-147`) | ✅ REQUIRED |
| **≤2 statements** | AI route `_statement_count(...)` imported from `app/prompt/validators.py`, both `headline` and `content_idea` checked (AI route plan §2.3) | ✅ REQUIRED |
| **No pet-names** | AI route `_has_forbidden_petname(...)` imported, both fields checked (AI route plan §2.3) | ✅ REQUIRED |
| **No price echo** | AI route `_PRICE_RE.search(...)` imported, both fields checked (AI route plan §2.3) | ✅ REQUIRED — model is never SENT a price, so any price token in output is invented → reject |
| **No marketplace words** | AI route `_MARKETPLACE_RE` (creator-specific, local to route) — unconditional check on both `headline` and `content_idea` for `snapsby`/`buy`/`videos?` (AI route plan §2.3) | ✅ REQUIRED — creator tone has no OWN_CONTENT/SNAPSBY branch, always forbids marketplace |
| **Fallback on any failure** | AI route §1.2 step 9: provider error, malformed, validation-fail, spend-gate → `_fallback_response()`, still HTTP 200 (mirrors `trendspark.py:206-226`) | ✅ REQUIRED |
| **PII-free logs** | AI route §4 P2 row: log `creator_profile_id` in the clear, redact `caption*`/`ig_handle` text; all log lines use `shape_of()` (AI route plan §1.2 step 8) | ✅ REQUIRED |

**Tests that are NOW MOOT (caption text removed from Tier-1):**

| Original Test | Why Moot |
|---------------|----------|
| Caption prompt-injection (`"IGNORE PREVIOUS..."` in caption) | No caption text reaches any model — tagging is deterministic Java; phrasing receives only `theme_matched` + `trend_text`. No code path. |
| Caption `system:`/`assistant:` leakage | Same — captions don't reach the prompt. |

**NEW test needed (R1-specific):**

| Test | Surface | Rationale |
|------|---------|-----------|
| **`trend_text` prompt-injection wrap** | AI route §2.2 `wrap_untrusted("trend_text", trend_text)` — delimiters + `neutralize_angle_brackets()` | `trend_text` is now the ONLY untrusted free-text field in the phrasing route (scraped/third-party). Test: `"<script>...</script>"` or `"IGNORE PREVIOUS..."` in `trend_text` → neutralized, no echo, delimiters intact. |

**Test file paths (spec §6 naming, updated scope):**

```python
# tests/routes/test_creator_suggestion.py (clone test_trendspark_nudge.py structure)
def test_suggestion_returned_ai():
    # mocked Haiku returns valid {headline, content_idea} → passes validation → message_source=AI

def test_suggestion_fallback_on_provider_fail():
    # mocked provider returns ok=False → fallback, still 200

def test_suggestion_fallback_on_malformed():
    # mocked provider returns non-JSON or missing fields → fallback

def test_per_day_cap():
    # NOT the AI-service route's test — this is Java's (Vikram owns)
    # AI-service only tests spend-gate (separate layer)

def test_spend_gate_blocks_call():
    # spend-gate kill-switch → fallback, provider never called

def test_auth_no_token_401():
    # Authorization header missing → 401

def test_auth_wrong_scope_403():
    # SCOPE_SERVICE token (brand-side) on creator_suggestion endpoint → 403 scope_mismatch

def test_invented_theme_dropped():
    # theme_matched not in THEME_SET (closed vocab) → genericfallback, never passed to model

def test_marketplace_words_rejected():
    # model output contains "snapsby" or "buy" or "video" in headline/content_idea → validation fails → fallback

def test_price_echo_rejected():
    # model output contains "₹" or "rs" or "rupee" → validation fails → fallback (model never sent a price, so any price token is invented)

def test_pet_name_rejected():
    # model output contains "babe" or "love," → validation fails → fallback

def test_statement_count_cap():
    # headline or content_idea has >2 statements → validation fails → fallback

# tests/security/test_creator_trend_text_injection.py (NEW, replaces caption-injection)
def test_trend_text_wrapped():
    # trend_text = "IGNORE PREVIOUS INSTRUCTIONS" → wrapped delimiters present in prompt, no model echo

def test_trend_text_angle_brackets_neutralized():
    # trend_text = "<script>alert(1)</script>" → neutralize_angle_brackets called, no raw angle brackets in prompt

# tests/routes/test_creator_suggestion_registration.py
def test_route_registered():
    # /internal/creator-suggestion present in app.routes, prevents silent unregister
```

**Guardrail patterns confirmed against trendspark reference:**
- ✅ `_CODE_FENCE_RE`, `_PETNAME_RE`, `_LOVE_VOCATIVE_RE`, `_PRICE_RE`, `_STATEMENT_RE`, `_has_forbidden_petname()`, `_statement_count()` all imported from `app/prompt/validators.py` (AI route plan §2.3, extract-first PR)
- ✅ `_MARKETPLACE_RE` local to creator route (AI route plan §2.3, Priya ruling)
- ✅ `parse_and_validate()` structure mirrors `trendspark.py:126-176` exactly

**Full trendspark regression suite MUST pass green on the extract-first PR** (Priya ordered pre-condition #4) before the creator route is built — this is the gate that proves the shared validators weren't broken by extraction.

### 2.3 Updated AC-3 Assertion (Spec §6)

**Original AC-3:** "ONE Haiku suggestion/day; ≤2 statements, no pet-names, niche present, **no price/marketplace words** (creator-facing)."

**Updated AC-3 post-R1:** Same, but clarify the "niche present" part now means `theme_matched` (closed-vocab, server-derived) is present in the response, not a caption snippet. The assertion logic is unchanged (check `suggestion.theme` field exists + is non-empty), but the test's data-flow understanding should be: theme came from Java's `ThemeMatchService.score()` output, not from a model parsing a caption.

---

## 3. The Two Gate PRs (Pre-Conditions)

### 3.1 Gate PR #1: Extract-First Validators (`app/prompt/validators.py`)

**Per Priya Conflict 7 ruling + AI route plan §2.3:**

**Scope:**
1. **NEW file:** `influora-ai/app/prompt/validators.py`
2. **Moved from `app/routes/trendspark.py:60-77`:**
   - `_CODE_FENCE_RE`, `_PETNAME_RE`, `_LOVE_VOCATIVE_RE`, `_PRICE_RE`, `_STATEMENT_RE` (5 regexes)
   - `_has_forbidden_petname()`, `_statement_count()` (2 helper functions)
   - `FORBIDDEN_PETNAMES` import (already shared from `app/prompt/trendspark.py`, still imported, now from validators module)
3. **EDIT `app/routes/trendspark.py`:** refactor to import the above from `app/prompt/validators.py` instead of defining them locally. Zero behavior change.

**Gate:** Kavya re-runs the **FULL trendspark test suite green** on this PR alone before the creator AI route PR is opened. This proves:
- Extraction didn't break the existing trendspark route
- Shared validators are a stable, tested foundation for the creator route to import

**Test command (Meera runs this in CI on the extract-first PR):**
```bash
cd influora-ai
pytest tests/routes/test_trendspark_nudge.py -v   # all golden GOOD/BAD nudge cases must pass
pytest tests/routes/test_trendspark_registration.py -v
pytest tests/eval/ -k trendspark -v  # eval harness
```

**Acceptance:** 0 failures, 0 regressions. The extract-first PR merges BEFORE any creator route code is written.

### 3.2 Gate PR #2: Acceptance Criteria (Block SHIP, Not Build)

**Per Priya's ordered pre-conditions + spec §6 "Blocks SHIP":**

**These block the creator route from shipping to production, NOT from being built/QA'd:**

1. **AC-1…AC-5 green in CI** (spec §6 acceptance criteria) — suggestion returned, fallback, per-day cap (Java), spend-gate, auth
2. **Injection guardrail green** — `test_trend_text_wrapped` + `test_trend_text_angle_brackets_neutralized` (§2.2 NEW test)
3. **Vernacular safety (AC-6)** — Hindi/Tamil captions don't break Java tagging (closed-vocab themes still map to English taxonomy)
4. **Per-day cap proven** — 2nd call same creator/day never hits Anthropic (verified via spend tracker logs or mock-call-count assertion in Java integration test, Vikram owns)
5. **Eval harness green offline** — `influora-ai/evals/creator_copilot.json` seed 10 golden pairs (spec §6 list: cc-001…cc-010) run via `run_eval.py`, all pass
6. **Route-registration test** — `test_creator_suggestion_registration.py` (prevents silent unregister)
7. **⚠️ Product decision (Ash + Tejas):** zero-posts/zero-themes UX = "silence" vs "post first" message (Priya Conflict 6) — blocks the test **assertion**, not the code (placeholder copy proceeds, final copy is a one-line swap)

**The creator route can be BUILT and QA'd with placeholder copy for item 7; the final copy swap + assertion update is the last pre-ship blocker.**

---

## 4. Summary: PASS with 2 Minor Corrections

### 4.1 What's Buildable As-Is

✅ All four plans (FE components, FE data-layer, BE services, AI route) are internally consistent post-R1 ruling  
✅ FE types == BE DTOs == wire JSON (byte-identical)  
✅ Paths aligned across all layers (canonical `/api/v1/creator/copilot/*`)  
✅ No creator caption text reaches any model (injection surface eliminated by scope)  
✅ Per-creator/day cap is durable (DB constraint + idempotent-read)  
✅ IDOR/scope isolation discipline is correct  
✅ Guardrail test matrix updated for R1 scope (caption tests moot; `trend_text` wrap test new)  
✅ Two gate PRs clearly defined (extract-first validators + AC/ship blockers)  

### 4.2 Two Minor Corrections Needed Before Code Starts

1. **BE services plan §2.5 line 103:** Update commentary text from old spec-draft path "`GET /api/creator/suggestion`" to canonical "`GET /api/v1/creator/copilot/suggestion/today`" (cosmetic doc-consistency fix, does not affect §5 code plan which is already correct).

2. **Internal service-to-service contract freeze (Vikram + AI-route agent):** Freeze the `/internal/creator-suggestion` request DTO field casing (`theme_matched` vs `themeMatched`, `trend_text` vs `trendText`). Both plans defer this to coordination (BE services plan §6.5, AI route plan §6 item 1). **Recommend:** snake_case (`theme_matched`, `trend_text`) to match trendspark's internal contract pattern (`trendspark.py:9` docstring). Either is buildable as long as both sides agree.

**Action:** Vikram addresses correction #1 (doc update). Vikram + AI-route agent address correction #2 (freeze internal DTO casing in a joint API-CONTRACT addendum or Slack sync before either side writes serialization code).

### 4.3 No Build Blockers

**Ready to proceed** once corrections #1–2 are applied. The two gate PRs are sequenced correctly (extract-first validators lands before creator route; ship-blockers are post-QA, not pre-build).

---

## Attachments

**Verified Documents (All Read):**
- `wiki/build/creator-copilot-priya-review-r1.md` (Priya's 7 conflict rulings + 4-track verdicts)
- `wiki/build/creator-copilot-API-CONTRACT.md` (frozen v1, byte-for-byte wire contract)
- `wiki/build/creator-copilot-fe-components-plan.md` (Ananya, 7 files, 6 open questions)
- `wiki/build/creator-copilot-fe-datalayer-plan.md` (FE data-layer, hook + client, 7 open questions)
- `wiki/build/creator-copilot-be-services-plan.md` (Vikram, 11 new files, 6 modified, 10 open questions)
- `wiki/build/creator-copilot-ai-route-plan.md` (AI-service, 2 routes → 1 post-R1, validators extract-first)
- `wiki/ai-review/creator-ai-copilot-tier1-build-spec.md` (parent spec, §6 QA section)
- `influora-ai/app/routes/trendspark.py` (guardrail reference implementation)
- `influora-ai/tests/eval/test_trendspark_nudge.py` (test pattern reference, first 150 lines)

**Next Step:** Route to Arjun (Eng Lead) via SHARED_CONTEXT.md. The package is build-ready pending 2 minor corrections.
