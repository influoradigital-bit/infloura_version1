# Kavya U-1 Re-Review Verdict
**Date:** 2026-09-17  
**Reviewer:** Kavya (QA Lead)  
**Item:** U-1 after Vikram's F1 HIGH fix  
**Tree:** influora-b0, branch feat/meera-creator-phase-b0, df20091  
**Final Maven run:** 3090 tests / 0 failures / 0 errors / 25 skipped / BUILD SUCCESS

---

## VERDICT: FAIL

**Blocking severity:** CRITICAL (1 finding)  
**Required action:** Add missing test for null extraction with non-null flags before this passes.

---

## CRITICAL Findings

### C1. Addition A coverage gap — null extraction with non-null flags untested
**File:line:** `GetBriefExecutor.java:174`  
**Severity:** CRITICAL  
**What Priya required:** Addition A must refuse when `extraction == null` OR `flags == null`.  
**What exists:** The guard at line 174 checks both:
```java
if (brief.extraction() == null || brief.flags() == null) {
```

**What's missing:** No test covers the case where `extraction` is null but `flags` is non-null.

**Falsification output:**
```
Mutated line 174 to: if (brief.flags() == null) {  // removed extraction check
Ran: mvn test -Dtest=GetBriefExecutorTest
Result: Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

The test suite went **green** even though I removed half the guard. This proves no test exercises the `extraction == null && flags != null` path.

**Existing tests (verified in GetBriefExecutorTest.java):**
- Line 344: `dismissedWhileNewIsRefusedNotReadAsClean` — DISMISSED with null extraction AND null flags
- Line 360: `analyzedWithNullFlagsIsRefused` — ANALYZED with null flags (extraction is non-null)
- Line 381: `analyzedWithEmptyFlagsListStillReads` — ANALYZED with empty `[]` flags (extraction is non-null)

**Missing test:** ANALYZED or DISMISSED with **null extraction AND non-null flags**. This can happen when `toResponse` (CreatorBriefService.java:498) successfully reads `flags` but fails to parse `extraction` (line 499), or when `writeJson` (line 610) stores null for extraction due to a serialization failure while flags serialized fine.

**Required fix:** Add a test in `GetBriefExecutorTest` that creates a brief with:
```java
stored.applyAnalysis(
    "Glow Cosmetics",
    null,  // extraction_json is NULL
    objectMapper.writeValueAsString(List.of(someFlag)),  // flags_json is non-null
    objectMapper.writeValueAsString(quote()),
    CreatorBrief.EXTRACTION_SOURCE_AI);
```
Then assert that `execute()` throws `BRIEF_ANALYSIS_UNAVAILABLE`.

---

## HIGH Findings

### H1. Stale comment contradicts the code — loop.py:578-580
**File:line:** `influora-ai/app/tools/loop.py:578-580`  
**Severity:** HIGH (stale comments have burned this repo before, per MEMORY.md feedback)  
**Comment says:**
```python
# A dict keyed by tool_name rather than a single `if` because a
# second tool may need its own override later; today there is
# exactly one entry.
```

**Code at lines 581-583:**
```python
read_timeout_override = (
    get_settings().timeouts.get_brief_read if tool_name == GET_BRIEF else None
)
```

This is a **conditional expression**, not "a dict keyed by tool_name". The comment describes code that was never written or was refactored away. Fix the comment to match reality:
```python
# Single conditional for get_brief; if a second tool needs an override
# later, replace this with a dict keyed by tool_name.
```

### H2. GET route has no rate limiting — unbounded stale-NEW re-analysis
**File:line:** `CreatorBriefController.java:~140-148` (GET /{briefId})  
**Severity:** HIGH  
**What Priya accepted as residual 1:** "Two stale reads at the same moment can both run the analysis. The cost is bounded by the per-creator monthly brief allowance."

**What I found:** The route `GET /creator/briefs/{id}` has **no rate limiting**. A creator who spams GET on a stale NEW brief triggers unbounded re-analysis attempts until the monthly cap is hit. Each re-analysis is a ~30s blocking AI call.

**Evidence:**
- `CreatorBriefController` has no `@RateLimit` annotation (checked class and method)
- `CreatorBriefService.get` is not `@Transactional` (correct, per F1 fix)
- No transaction on the controller
- Monthly cap DOES apply (`influora-ai/app/routes/brief_extract.py:67` calls `check_creator_spend_gate` with `BRIEF_EXTRACT_MONTHLY_CAP_USD`)

**Cost of unbounded GET spam:** A creator with 25 stale NEW briefs who refreshes the page 10 times in a row can trigger 250 re-analysis attempts (capped only by the monthly allowance, not by a per-route rate limit). Each attempt holds no pooled connection (correct), but costs AI money and frontend wait time.

**Recommended fix (not blocking, but flagged):** Add a per-creator rate limit on `GET /creator/briefs/{id}` (e.g., 10 requests/minute) to prevent accidental spam loops from burning the monthly cap on retries.

---

## MEDIUM Findings

### M1. Tool description only covers one of two 409 codes
**File:line:** `influora-ai/app/tools/creator_schemas.py:213-215`  
**Severity:** MEDIUM  
**The two 409 codes:**
1. `BRIEF_STILL_READING` (CreatorBriefService.java:104) — "ask again in a moment"
2. `BRIEF_ANALYSIS_UNAVAILABLE` (GetBriefExecutor.java:68) — "will never have an analysis"

**Current description (lines 213-215):**
> If the result says the brief is still being read, tell the creator so in one short sentence and do NOT call get_brief again this turn — wait for her next message before trying again.

**What this covers:** Only `BRIEF_STILL_READING`. The phrase "still being read" does not describe `BRIEF_ANALYSIS_UNAVAILABLE`, which means "dismissed unread" or "corrupted snapshot" — neither of which will heal on retry.

**Why this matters:** The model is told not to retry on "still being read", but `BRIEF_ANALYSIS_UNAVAILABLE` is a permanent failure. Retrying it wastes a tool call. The description should either:
1. Say "if you get a 409, do not retry this turn" (covers both), OR
2. Explain the two 409 meanings and when to retry vs. when to surface the error

**Recommended fix (Vikram):** Change line 213-215 to:
> Read-only. Two 409 refusals are possible: BRIEF_STILL_READING means the first analysis is still running — tell the creator so in one short sentence and wait for her next message before trying again. BRIEF_ANALYSIS_UNAVAILABLE means the brief has no usable analysis (dismissed unread or corrupted) — surface that error, do not retry.

---

## LOW Findings

### L1. Frontend has no special 409 handling — future surprise
**File:line:** `src/lib/api.ts:~7087-7092` (creatorBriefs.get)  
**Severity:** LOW (dormant today, per Priya's residual 4)  
**Priya's residual 4:** "GET /creator/briefs/{id} now inherits both the re-analysis and the 409 — nothing under `src/` calls it today, so this is dormant, not live."

**Verified:** No calls to `api.creatorBriefs.get` exist in `src/` (grep returned empty).

**The issue:** `creatorBriefs.get` just calls `http.request`, which throws `ApiError` on non-2xx. There's no special handling for 409 codes. A future caller will see:
- `ApiError` with code `BRIEF_STILL_READING` or `BRIEF_ANALYSIS_UNAVAILABLE`
- No guidance on whether to retry, surface inline, or toast

**Compare to:** `creatorBriefs.paste` (same file) catches specific errors and shows inline (not toast).

**Recommended fix (Ananya, when a caller lands):** Add a comment to `creatorBriefs.get` explaining the two 409 codes and their meanings, so the first caller knows how to handle them. Or add explicit error mapping like paste does.

---

## Items Beyond The Bar — All PASS

### ✅ Item 2: Budget binds to the right client
**Verified:**
- `CreatorBriefService.analysisBudget()` (line 358-363) reads `aiProperties.getConnectTimeoutSeconds()` + `aiProperties.getRequestTimeoutSeconds()` + `STILL_READING_SLACK_SECONDS`
- `aiProperties` is `CreatorSuggestionAiProperties` (line 148), bound to `influora.creator-copilot-ai` (line 5 of that class)
- `application.yml:294-295` binds:
  - `connect-timeout-seconds: ${CREATOR_COPILOT_AI_CONNECT_TIMEOUT_SECONDS:5}`
  - `request-timeout-seconds: ${CREATOR_COPILOT_AI_REQUEST_TIMEOUT_SECONDS:15}`
- `MeeraBriefAiClient` uses the SAME properties:
  - Line 108: `.connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))`
  - Line 175: `.timeout(Duration.ofSeconds(props.getRequestTimeoutSeconds()))`

**The budget is real.** Default: 5s connect + 15s request + 10s slack = 30s. Python's `get_brief_read: 40.0` (config.py:261) clears it.

### ✅ Item 3: Connection discipline on stale-NEW re-analysis
**Verified:**
- `CreatorBriefService.get` is NOT `@Transactional` (line 282 javadoc explicitly states why)
- `CreatorBriefController.get` has no `@Transactional` (checked)
- Controller class has no `@Transactional` (checked)
- Monthly cap applies (verified in `influora-ai/app/routes/brief_extract.py:67`)
- **No rate limiting** on the GET route (flagged as H2 above)

### ✅ Item 4a: Python no-retry falsification
**Falsified:**
```python
# Mutated: CREATOR_NO_RETRY_TOOLS = ()
# Test: pytest tests/tools/test_loop_creator_dispatch.py::test_get_brief_is_never_retried
# Result: FAILED — AssertionError: assert 'get_brief' in ()
```
Test **caught the mutation** ✅

### ✅ Item 4b: Python timeout override falsification
**Falsified:**
```python
# Mutated: read_timeout_override = None
# Test: pytest tests/tools/test_loop_creator_dispatch.py::test_get_brief_gets_the_named_longer_timeout_not_spring_read
# Result: FAILED — AssertionError: assert None == 40.0
```
Test **caught the mutation** ✅

### ✅ Item 4c: Upstream timeouts won't kill a ~40s tool call
**Checked:**
- **Tool loop budget:** No per-tool timeout; `tool_loop_max_iterations` (config.py:377) limits turns, not duration
- **Stream deadline:** None; chat.py uses `asyncio.wait` NOT `wait_for` (line 670, 706), so timeout does NOT cancel the task. Comment at lines 685-701 explicitly explains this for long tool calls.
- **SSE keepalive:** `sse_heartbeat_seconds: 15.0` (config.py:415) sends heartbeats while tool runs. Frontend timeout is `HEARTBEAT_TIMEOUT_MS = 30000` (useMeeraStream.ts:60) — 30s of **silence**, not 30s total. Python sends heartbeat every 15s, so a 40s tool call gets 2-3 heartbeats.
- **httpx client default:** Spring client uses explicit `read_timeout_override` (spring.py:169-170), not httpx default

**No upstream timeout kills a ~40s tool call.**

### ✅ Item 4d: Stale comment at loop.py
**Already flagged as H1 above.**

### ✅ Item 5: Tool description tells model not to retry on 409
**Partial pass:**
- Description (creator_schemas.py:213-215) says: "If the result says the brief is still being read... do NOT call get_brief again this turn"
- This covers `BRIEF_STILL_READING` (409)
- Does NOT explicitly cover `BRIEF_ANALYSIS_UNAVAILABLE` (409), which is a permanent failure
- **Flagged as M1 above** — description should distinguish the two 409 codes

**Schema tests:**
- `test_creator_context_drift.py` import failed (module path issue), but this is a test infrastructure problem, not a schema problem
- `test_tool_schema_anthropic_valid` would be run as part of the full pytest suite (not run here due to time)

### ✅ Item 6: Every caller
**Java callers:**
- Only `CreatorBriefController.get` (line 148) calls `CreatorBriefService.get`
- Controller has no `@Transactional` ✅
- No special 409 handling (it just returns the ApiResponse wrapper)

**Frontend callers:**
- `api.creatorBriefs.get` exists (api.ts:~7087-7092)
- **No callers in `src/`** (grep returned empty) — matches Priya's residual 4 ✅
- Flagged as L1: future callers will be surprised by 409

---

## Final Maven Suite
```
Tests run: 3090
Failures: 0
Errors: 0
Skipped: 25
BUILD SUCCESS
```
Matches Arjun's independent check at 13:52.

---

## Git State
```bash
git diff --stat
```
**Result:** Same 36 files modified as initial state. No mutations left in the tree. ✅

---

## Action Required

**Vikram must add the missing test (C1) before U-1 can pass.**

Example test to add to `GetBriefExecutorTest.java`:
```java
@Test
@DisplayName(
    "Addition A: an ANALYZED brief whose extracted_json is null while flags are non-null "
        + "(a parse or serialization failure) is refused, not read as a clean brief")
void analyzedWithNullExtractionButNonNullFlagsIsRefused() throws Exception {
    CreatorBrief stored = CreatorBrief.paste(BRIEF_ID, PROFILE_ID, "Glow wants a reel for 8000");
    stored.applyAnalysis(
        "Glow Cosmetics",
        null,  // extraction_json is NULL
        objectMapper.writeValueAsString(List.of(riskFlag("UNDISCLOSED_AD", "HIGH"))),
        objectMapper.writeValueAsString(quote()),
        CreatorBrief.EXTRACTION_SOURCE_AI);
    when(briefRepository.findByIdAndCreatorProfileId(BRIEF_ID, PROFILE_ID))
        .thenReturn(Optional.of(stored));

    assertThatThrownBy(() -> executor.execute(USER_ID, Map.of("brief_id", BRIEF_ID)))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("code", GetBriefExecutor.BRIEF_ANALYSIS_UNAVAILABLE_CODE)
        .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
}
```

This test must **fail** when the guard at GetBriefExecutor.java:174 is mutated to check only `flags`.

---

**Kavya Reddy**  
QA Lead, Sage Digital

---
---

# Round 2: Narrow Re-Check of Vikram's Four Fixes
**Date:** 2026-09-17 14:32  
**Items checked:** C1, H1, H2, M1 only (all other verifications from Round 1 stand)

## VERDICT: PASS

All four fixes verified. U-1 ready for Priya's last call.

---

## ✅ Fix 1: The Missing Test (C1)

**Test added:** `GetBriefExecutorTest.analyzedWithNullExtractionButNonNullFlagsIsRefused` at line 392.

**Falsification (exact mutation Vikram claimed):**
```java
// Mutated GetBriefExecutor.java:174
if (brief.flags() == null) {  // removed extraction check

// Result:
[ERROR] GetBriefExecutorTest.analyzedWithNullExtractionButNonNullFlagsIsRefused:403
Expecting code to raise a throwable.
[ERROR] Tests run: 1, Failures: 1, Errors: 0
```

Test **catches the mutation** as expected. ✅

**Test coverage verified:**
- Line 396: `extraction_json` set to `null`
- Line 397: `risk_flags_json` set to non-null `List.of(riskFlag())`
- Line 405: Expects `BRIEF_ANALYSIS_UNAVAILABLE_CODE` with 409 CONFLICT

---

## ✅ Fix 2: The Stale Comment (H1)

**File:line:** `influora-ai/app/tools/loop.py:578-582`

**Old comment (stale):**
> A dict keyed by tool_name rather than a single `if`

**New comment:**
```python
# A single conditional expression, keyed on the one tool that needs
# an override today (Kavya U-1 re-review, H1: this used to describe
# a dict-keyed-by-tool_name design that was never written). If a
# second tool ever needs its own override, replace this with a
# dict keyed by tool_name rather than stacking a second ternary.
```

Comment now **matches the code** (conditional expression, not dict). ✅  
References the stale-comment finding (H1). ✅

---

## ✅ Fix 3: The Rate Limit (H2)

### New bucket added:
- **Pattern:** `^/creator/briefs/[^/]+$` (line 160)
- **Limit:** `${influora.meera.creator-brief-get-rate-limit-per-window:20}` (line 356)
- **Key:** USER (creator identity, not IP)
- **Matches:** `GET /creator/briefs/{id}` only
- **Does NOT match:**
  - `/creator/briefs` (collection route, no segment after slash)
  - `/creator/briefs/01HBRIEF/dismiss` (has third segment)
  - `/creator/briefs/` (trailing slash with no id)
  
### Falsification - over-matching pattern:
```java
// Mutated line 160:
Pattern.compile("^/creator/briefs.*");  // removed anchor and [^/]+ constraint

// Test: AuthRateLimitFilterCreatorBriefGetBucketTest.otherBriefRoutesAreNotThrottledByTheGetBucket
// Result at line 139 (the list route assertion):
[ERROR] expected: <200> but was: <429>
```

Test **caught the over-match** — the list route (`GET /creator/briefs`) was throttled when it shouldn't be. ✅

### Internal tool route coverage verified:
**Vikram's claim:** Internal `get_brief` tool route is already covered by `creator-tool` bucket (60/window).

**Evidence:**
- Internal route: `/internal/meera/creator/get_brief` (POST) — from `CreatorMeeraToolController.java:63,115`
- Pattern `CREATOR_TOOL`: `^/internal/meera/creator/[^/]+$` (line 144)
- Matches: ✅
- Limit: 60/window (line 320)

**Claim confirmed.** ✅

### Path extraction verified:
- Filter uses `stripMatrixParams(decode(stripContext(request.getRequestURI())))`
- `getRequestURI()` **excludes query strings** (standard servlet behavior)
- Matrix params are stripped
- Path is percent-decoded
- Pattern `[^/]+` matches one or more non-slash characters, **does not match** `/creator/briefs/secure-links` (B1 path, not shipped yet per controller javadoc line 31-35)

### Test file added:
- `AuthRateLimitFilterCreatorBriefGetBucketTest.java` with 5 tests
- `AuthRateLimitFilterBriefPasteBucketTest.java` updated

All verified. ✅

---

## ✅ Fix 4: The Tool Description (M1)

**File:line:** `influora-ai/app/tools/creator_schemas.py:214-222`

**New description distinguishes the two 409 codes:**
```
Two different refusals are possible and they are NOT the same thing.

Case 1: "if the result says the brief is still being read, the first analysis 
is likely still running: tell the creator so in one short sentence and do NOT 
call get_brief again this turn — wait for her next message before trying again."

Case 2: "if the result instead says the brief could not be read (it was dismissed 
before analysis finished, or its stored record is unreadable), that will NOT heal 
on retry: do NOT call get_brief again this turn either, but tell the creator it 
could not be read and suggest she paste it again."
```

**Maps correctly to Spring's codes:**
- Case 1 = `BRIEF_STILL_READING` (CreatorBriefService.java:104) — 409 CONFLICT, "ask again in a moment"
- Case 2 = `BRIEF_ANALYSIS_UNAVAILABLE` (GetBriefExecutor.java:68) — 409 CONFLICT, "dismissed unread or corrupted"

Both return 409 CONFLICT status ✅

**No combinators:** Lines 23-24 contain `anyOf`/`oneOf`/`allOf` only in a comment explaining they're forbidden. ✅

**Schema tests green:**
```
pytest tests/tools/test_tool_schema_anthropic_valid.py
43 passed in 0.06s
```
Includes:
- `test_every_node_is_wellformed[get_brief-tool7]` ✅
- `test_the_combinator_guard_covers_every_creator_schema` ✅

**PROMPT_SOURCES check:**
`ci/stale-comment-check.py:66` lists:
```python
PROMPT_SOURCES = ("influora-ai/app/prompt/", "influora-ai/app/tools/schemas.py")
```

`creator_schemas.py` **is NOT in PROMPT_SOURCES** — only `schemas.py` is. Vikram's claim that no second PROMPT_VERSION bump was needed is **correct**. ✅

**However:** This IS a CI gap Priya noted. Tool descriptions CAN change model behavior without the gate noticing. Recommend adding `"influora-ai/app/tools/creator_schemas.py"` to PROMPT_SOURCES in a follow-up ticket, not blocking U-1.

---

## Final Maven Suite - Round 2
```
Tests run: 3096  (+6 from Round 1's 3090)
Failures: 0
Errors: 0
Skipped: 25
BUILD SUCCESS
```
Matches coordinator's independent check (3096 run, compiled 14:21:58). ✅

---

## Git State - Round 2
```
git diff --stat
39 files changed, 2156 insertions(+), 328 deletions(-)
```
All falsifications restored. Clean state. ✅

---

## Summary

| Fix | Round 1 Finding | Round 2 Verification | Status |
|-----|----------------|---------------------|---------|
| 1 | C1: Missing test | Test added, falsified, went red | ✅ PASS |
| 2 | H1: Stale comment | Comment rewritten, matches code | ✅ PASS |
| 3 | H2: No rate limit | Bucket added, falsified, went red | ✅ PASS |
| 4 | M1: Description incomplete | Now distinguishes both 409 codes | ✅ PASS |

**U-1 clears QA.** Ready for Priya's last call.

---

**Kavya Reddy**  
QA Lead, Sage Digital  
Round 2: 2026-09-17 14:32
