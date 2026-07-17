# QA Review: Wave C4 garm_flags Write/Read Shape Fix

**Date:** 2026-07-07  
**Reviewer:** Kavya (QA Lead)  
**Verdict:** **APPROVED WITH PROCESS RETROSPECTIVE**

---

## Bug Summary

**Original issue (Ananya's C4 frontend investigation):**  
`BrandSafetyScoreService.writeGarmFlagsJson` serialized a `List<ClassifiedItem>` (rich `{category, risk, rationale}` objects) into `creator_scores.garm_flags`, but `AnalyticsService.getCreatorScores` read it back via `JsonLists.stringListFromJson` (a `List<String>` parser). The type mismatch caused:
- Parse threw `JsonProcessingException`
- Exception silently caught, returned `Collections.emptyList()`
- `garmFlags` always read as `[]` in brand-facing responses, even when GARM found real risk
- Frontend `BrandSafetyBadge.tsx` showed "No elevated-risk GARM categories reported" despite actual findings

**Impact:** Brand-safety findings invisible to brands. Score was correct (separate scalar column), but category-level detail was lost.

---

## The Fix (Vikram)

### Write Side: `BrandSafetyScoreService.writeGarmFlagsJson`

**Line 198-213:**
```java
private String writeGarmFlagsJson(List<ClassifiedItem> classified) {
    Set<String> aboveFloorCategories = new LinkedHashSet<>();
    for (ClassifiedItem item : classified) {
        if (item.garmFlags() == null) continue;
        for (GarmFlag flag : item.garmFlags()) {
            if (flag == null || flag.category() == null) continue;
            if (!NO_CONCERN_RISK_LEVEL.equals(flag.risk())) {
                aboveFloorCategories.add(flag.category());
            }
        }
    }
    return JsonLists.toJson(new ArrayList<>(aboveFloorCategories));
}
```

**Logic:**
1. Flatten all classified items' GARM flags
2. Extract category NAMES ONLY (not full `{category,risk,rationale}` objects)
3. Filter: only above-floor risk levels (`"low"`/`"medium"`/`"high"`, exclude `"floor"`)
4. Deduplicate (same category can appear in multiple posts)
5. Serialize via `JsonLists.toJson` → matches reader's `List<String>` expectation

### Read Side: `AnalyticsService.getCreatorScores` (unchanged)

**Line 199-201:**
```java
score.getGarmFlagsJson() == null
    ? null
    : JsonLists.stringListFromJson(score.getGarmFlagsJson())
```

Now receives what it expects: a JSON string array like `["hate_speech_acts_of_aggression","adult_content"]`.

---

## Correctness Analysis

### 1. Does the flattening produce useful signal?

**YES, with one acceptable trade-off:**

✅ **Category names preserved:** Brands see which GARM categories flagged (hate speech, adult content, etc.)  
✅ **Risk-level filter works:** Only above-floor categories surface (noise correctly suppressed)  
✅ **Frontend wire contract met:** `BrandSafetyBadge.tsx` expects `string[]` of category names (lines 20, 139-210) — this is exactly what it gets now  

⚠️ **Risk LEVEL detail lost:** A `"high"` risk flag and a `"medium"` risk flag both just become the category name string. The brand sees "hate_speech_acts_of_aggression" but not whether it was scored HIGH or MEDIUM.

**Is this a regression?**  
NO — it's a **deliberate simplification aligned with the existing UI contract.** The frontend has no design/UI for per-category risk levels (checked `BrandSafetyBadge.tsx` lines 196-210: just renders flat tags). The full `ClassifiedItem` detail (per-post risk + rationale) was NEVER consumed by any reader — not in DTO, not in UI. If a future UI wants drill-down (e.g., "show me which POST had the HIGH hate-speech flag"), that needs:
- A new column/DTO field (perhaps `garm_flags_detail` holding the full `List<ClassifiedItem>` as JSON)
- A new frontend component to render it

The current fix correctly implements "what the UI actually uses" rather than "preserve all data in a shape nothing can read."

### 2. The round-trip test is genuinely load-bearing

**YES.** `AnalyticsServiceTest.testGetCreatorScoresGarmFlagsSurviveRealWriteReadRoundTrip` (lines 264-359):

✅ **Uses REAL services:** Instantiates actual `BrandSafetyScoreService` with only external boundaries mocked (`BrandSafetyAiClient`, `MetaOAuthTokenRepository`)  
✅ **Real classification data:** Feeds 3 GARM flags (2 above-floor, 1 floor) through the write side  
✅ **Persists exactly what the writer produces:** No test-only stub shape — stores `writeResult.get().garmFlagsJson()` verbatim into `CreatorScore.garmFlagsJson`  
✅ **Reads via the REAL reader:** Calls `analyticsService.getCreatorScores` (the actual brand-facing API method) and asserts on the response DTO  
✅ **Non-tautological assertions:**
  - `assertEquals(2, result.garmFlags().size())` — proves 2 categories survived
  - `assertTrue(...contains("hate_speech_acts_of_aggression"))` — proves content integrity
  - `assertFalse(...contains("spam_or_harmful_content"))` — proves floor-risk exclusion worked (not lost by parse failure)

**Would it have caught the original bug?**  
YES. Before the fix, `writeGarmFlagsJson` wrote `List<ClassifiedItem>` JSON → `stringListFromJson` silently returned `[]` → `result.garmFlags().size()` would be 0, not 2 → assertion fails immediately.

### 3. `BrandSafetyScoreServiceTest` assertions aren't mirrored-from-implementation

**VERIFIED — assertions are independently derived from spec.**

**Old buggy assertions (corrected in this fix):**  
Lines 162-166 in `BrandSafetyScoreServiceTest.testScoreCreatorMapsWorstItemAsCreatorLevelScore`:
```java
// OLD (prior to Vikram's fix):
// assertTrue(value.garmFlagsJson().contains("m1"));  // content IDs — WRONG SHAPE
// assertTrue(value.garmFlagsJson().contains("m2"));

// NEW (corrected):
assertFalse(value.garmFlagsJson().contains("m1"), "flags must not embed content ids");
assertFalse(value.garmFlagsJson().contains("m2"), "flags must not embed content ids");
assertTrue(value.garmFlagsJson().contains("hate_speech_acts_of_aggression"));
assertFalse(value.garmFlagsJson().contains("spam_or_harmful_content")); // floor excluded
```

**Analysis:**  
The old test WAS tautological — it only passed because the buggy writer serialized full `ClassifiedItem` objects (which HAVE `contentId` fields). The corrected assertions now check:
- Category names present (from `GarmFlag.category`, the actual wire-contract field)
- Content IDs ABSENT (because they shouldn't be in a flat category-name list)
- Floor-risk categories ABSENT (because the spec says exclude them)

These are all **independently checkable properties** from the javadoc spec ("flat list of above-floor category names") — not just "assert whatever the current code produces."

---

## Test Quality: Net +1 (387 total)

**Baseline before fix:** 386 passing tests  
**After fix:** 387 passing tests

**Changes:**
1. **One existing test CORRECTED:** `BrandSafetyScoreServiceTest.testScoreCreatorMapsWorstItemAsCreatorLevelScore` had its assertions fixed (was checking for content-id substrings, now checks for category names + excludes content IDs) — this is NOT a new test, just a corrected one
2. **One NEW test added:** `AnalyticsServiceTest.testGetCreatorScoresGarmFlagsSurviveRealWriteReadRoundTrip` — genuine new round-trip integration test

Net +1 is correct.

---

## Process Retrospective: Why Did the Original C3 Review Miss This?

**The Gap:**  
All C3 sign-offs (Kavya QA, Kabir security, Meera local run) verified:
- ✅ `BrandSafetyScoreService` writes something to `garm_flags`
- ✅ `AnalyticsService` reads from `garm_flags`
- ✅ Each side had unit tests that passed in isolation

**What was NOT verified:**  
❌ That what the WRITER produces can actually be PARSED by the READER

**Root cause:**  
**Isolated unit tests with mocked shapes on each side, no integration round-trip.**

- `BrandSafetyScoreServiceTest` mocked the writer's output and asserted it contained certain strings (content IDs, which only exist in the buggy `List<ClassifiedItem>` shape)
- `AnalyticsServiceTest` mocked a `CreatorScore` row with a stub `garmFlagsJson` value (likely null or a valid `List<String>` JSON) and asserted the reader didn't crash
- **Neither test fed the REAL writer's output into the REAL reader**

**Why did 386 tests all pass?**  
Because both sides' tests used shapes that matched their OWN side's expectations, never the OTHER side's reality.

---

## Standing QA Checklist Addition: Multi-Layer Read/Write Contracts

**For all future features where Service A writes structured data that Service B reads back:**

### New mandatory checklist item (before Kavya sign-off):

```
□ At least ONE integration test where:
  - Service A's REAL writer produces the persisted value
  - That value is stored in the REAL entity (or DTO/JSON column)
  - Service B's REAL reader parses it back
  - Assertions verify the round-trip preserves semantic content
  - NOT just "doesn't throw" — actual data integrity (counts, key fields)
```

### Applies to these D/E-wave features (flag for extra scrutiny):

- **Shopify/WooCommerce webhook payloads** (Phase 4 UTM/conversion tracking):  
  E2 writes `utm_campaign`/`coupon_code` from webhook → affiliate settlement reads it back
  
- **Affiliate settlement calculations** (Phase 4 E3):  
  Writes `settlement_breakdown_json` → creator payout UI reads it back
  
- **Meera session context** (ongoing):  
  Writes `context_json` → MeeraSessionService reads it back on resume

**Why this matters for D/E waves:**  
These all involve:
1. External data shapes (webhook JSON from Shopify/WC, not our own DTO)
2. Money calculations (affiliate settlements — a parse failure = lost revenue tracking)
3. Cross-service contracts (tracking service writes, payout service reads)

**Action:** Add this checklist item to `wiki/processes/qa-checklist.md` under "Integration & Data Contract Tests" section (create if doesn't exist).

---

## Verdict: APPROVED

### Fix Quality: 9.5/10

**Strengths:**
- ✅ Structurally correct flattening logic
- ✅ Risk-level filtering works (floor excluded, proven by test)
- ✅ Genuine integration test added (load-bearing, would catch the original bug)
- ✅ Javadoc explains the wire-shape fix in full context (lines 80-91)
- ✅ Acceptable information-richness trade-off (lost per-category risk levels, but UI doesn't use them)

**Minor deduction (-0.5):**  
The fix could have been caught BEFORE shipping C3 if the original test suite had included a round-trip test from the start. But Vikram's corrective test NOW closes that gap going forward.

### Process Learning: HIGH VALUE

This is the EXACT KIND of multi-layer contract bug that:
1. Passes all isolated unit tests
2. Slips through code review (both sides "look correct" in isolation)
3. Only manifests when real data flows through the full stack
4. Is trivial to prevent with ONE integration test

**Retrospective note written to:** `wiki/errors/wave-c4-garm-flags-roundtrip-fix-QA.md` (this file)  
**Standing checklist updated:** (pending — Arjun to route this recommendation to Priya for `wiki/processes/qa-checklist.md` update)

---

## Next Steps

1. ✅ **Approved for Meera local verification** — Vikram's fix passes QA
2. **Post-merge action:** Arjun to ensure `wiki/processes/qa-checklist.md` adds the "multi-layer read/write round-trip test" requirement before D-wave backend tasks begin
3. **Flag for Kabir (E-wave security pre-review):** Affiliate settlement JSON (E3) and webhook payload parsing (E2) should both get the same round-trip scrutiny — those involve MONEY, not just display

---

**Files reviewed:**
- `influora-api/src/main/java/com/influora/service/scoring/BrandSafetyScoreService.java` (lines 188-213)
- `influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java` (lines 199-201)
- `influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java` (lines 264-359 new test)
- `influora-api/src/test/java/com/influora/service/scoring/BrandSafetyScoreServiceTest.java` (lines 120-167 corrected assertions)
- `influora-api/src/main/java/com/influora/common/JsonLists.java` (lines 37-45 `stringListFromJson`)
- `influora-api/src/main/java/com/influora/web/dto/analytics/AnalyticsDtos.java` (line 110 `List<String> garmFlags`)
- `influora-api/src/main/java/com/influora/integration/ai/dto/BrandSafetyDtos.java` (lines 43-53 `ClassifiedItem`/`GarmFlag`)
- `src/components/analytics/BrandSafetyBadge.tsx` (lines 1-217, frontend consumer)

**Status:** READY FOR MEERA LOCAL VERIFICATION (build + `curl` checks on `/analytics/creators/{id}/scores`)
