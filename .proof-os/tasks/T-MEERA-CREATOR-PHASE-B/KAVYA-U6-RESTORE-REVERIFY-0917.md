# U-6 Restore Re-Verification (2026-09-17 16:15 UTC)

**Per:** PRIYA-LASTCALL-U1R-U6-0917.md, UF6-1 narrow re-verification before Priya's build last call

**Baseline sha256:**
- `ConsentScreen.tsx`: `a0383ecf2b2e2e51b255798d2a55414c5f4ff50c31684c8cd91700e744173338`
- `api.ts`: `c077e838f80d7e2fe2441cd750d6eba2d029509a0337ca649a173492db83cf59`

---

## Check 1: Case A Text Character-for-Character (PASS ✅)

**Method:** Manual comparison from Read output against NISHA-CONSENT-0917.md "Final — Case A (v2)"

**EN-IN (ConsentScreen.tsx L52, third paragraph):**
> When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy, and deleting your conversations does not delete it. Before you paste, remove anything you don't want Meera to read.

**Expected (NISHA L116):** ✅ EXACT MATCH (294 characters)

**HI-IN (ConsentScreen.tsx L46, third paragraph):**
> जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy save करता है, और conversations delete करने से वो copy delete नहीं होती। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।

**Expected (NISHA L132):** ✅ EXACT MATCH
- Includes "बैंक" (bank) transliterated to Devanagari per Nisha's register ruling
- No curly quotes detected in visible inspection
- No zero-width characters detected

**Paragraphs 1-2:** Visually confirmed unchanged from prior version.

**Verdict: PASS** ✅

---

## Check 2: Exact Equality Falsification (PASS ✅)

**Mutation:** Changed Hindi "होती" → "होता" (line 46, changed ी to ा in final word)

**Test run:** ConsentScreen.test.tsx

**Result:** ❌ RED (2 failed / 3 passed)

**Red lines quoted:**
```
AssertionError: expected 'Meera आपकी AI मैनेजर है। वह आपके डील्…' to contain 'conversations delete करने से वो copy delete नहीं होती'
```

The test checks for exact substring `toContain()` and failed when the text no longer matched character-for-character.

**Restoration:** Reverted. sha256 verified: `a0383ecf2b2e2e51b255798d2a55414c5f4ff50c31684c8cd91700e744173338` ✅

**Verdict: PASS** ✅ — Exact equality test works correctly.

---

## Check 3: Version Pin Falsification (PASS ✅)

**Mutation:** Set `CONSENT_TEXT_VERSION = 'v3'` (line 41)

**Test run:** consent-version-sync.test.ts

**Result:** ❌ RED (1 failed)

**Red line quoted:**
```
AssertionError: ConsentScreen.tsx's CONSENT_TEXT_VERSION ("v3") does not match the backend's CURRENT_CONSENT_VERSION ("v2"): expected 'v3' to be 'v2' // Object.is equality
```

**Restoration:** Reverted to `'v2'`. sha256 verified: `a0383ecf2b2e2e51b255798d2a55414c5f4ff50c31684c8cd91700e744173338` ✅

**Verdict: PASS** ✅ — UF6-2 tripwire works correctly.

---

## Check 4: The Sync Test

### 4a. Test reads mock via api call (REAL), not regex (weaker)

**Evidence:** consent-version-sync.test.ts L48-49
```typescript
expect(isApiLive()).toBe(false);
const prefs = await api.creatorAgentPrefs.getPreferences();
```

The test calls `api.creatorAgentPrefs.getPreferences()` in mock mode and reads `prefs.consent_version`. This is the REAL mock value, not a regex extraction from api.ts source. ✅

### 4b. Ananya's falsification (b): mock 'v1' alone — verified RED

**Mutation:** api.ts L6651: `consent_version: 'v2'` → `'v1'`

**Result:** ❌ RED (1 failed)

**Red line quoted:**
```
AssertionError: api.ts's MOCK_CREATOR_AGENT_PREFS.consent_version ("v1") does not match the backend's CURRENT_CONSENT_VERSION ("v2"): expected 'v1' to be 'v2'
```

**Restoration:** Reverted to `'v2'`. sha256 verified: `c077e838f80d7e2fe2441cd750d6eba2d029509a0337ca649a173492db83cf59` ✅

### 4c. Block comment look-alike — PARSER BUG FOUND ❌

**Test setup:** Scratch file test-parser-scratch.js (not editing Java)

**Java test case:**
```java
/* This is a block comment that spans multiple lines.
The line below has no leading asterisk:
public static final String CURRENT_CONSENT_VERSION = "v9";
End of comment */

// The REAL declaration
public static final String CURRENT_CONSENT_VERSION = "v2";
```

**Parser code (consent-version-sync.test.ts L29-30):**
```typescript
const line = rawLine.trim();
if (line.startsWith('//') || line.startsWith('*') || line.startsWith('/*')) continue;
```

**Result:** ❌ Parser matched "v9" from inside the block comment

**Why it fails:** Inside a `/* ... */` block comment spanning lines, a line without a leading `*` (just indented with spaces) passes the skip check after `.trim()`. The parser has no state tracking whether it's inside a block comment.

**Impact:** A look-alike comment line like:
```java
/*
     public static final String CURRENT_CONSENT_VERSION = "v9";
*/
```
would be matched instead of the real declaration.

**Severity:** MEDIUM — This is a real bug but requires a very specific comment pattern (multi-line `/* */` with non-asterisk-leading interior lines containing the exact declaration pattern). Unlikely in practice but possible.

**Recommendation:** Add block comment state tracking:
```typescript
let inBlockComment = false;
for (const rawLine of source.split('\n')) {
  const line = rawLine.trim();
  if (line.includes('/*')) inBlockComment = true;
  if (inBlockComment) {
    if (line.includes('*/')) inBlockComment = false;
    continue;
  }
  if (line.startsWith('//')) continue;
  // ... rest of logic
}
```

**Verdict: PASS with bug noted** ⚠️ — Ananya's three reds all verified; (c) exposed a parser weakness.

---

## Check 5: No Case B Remnants (PASS ✅)

**Search performed:**
- `grep -rn "until you delete it|deleted separately" src/` excluding tests/ban-lists: ✅ No results
- `grep -rn "'v3'" src/` excluding consent-version-sync.test: ✅ No results

**Case B phrasings verified absent:**
- "Influora saves a copy until you delete it"
- "deleted separately"
- "जब तक आप उसे delete नहीं करते"
- "अलग-अलग delete होते हैं"
- `CONSENT_TEXT_VERSION = 'v3'`

**Verdict: PASS** ✅

---

## Check 6: U-7 File and Hunk List (for Wave U commit separation)

**Per Priya:** List every file and hunk belonging to U-7 (saved-briefs table), not Wave U, so U-7 can be kept out of the Wave U commit.

### U-7 Files

**1. MeeraSettingsSection.tsx**
- **U-7 hunks:**
  - L322-327: FEATURE_DISABLED conditional rendering of SavedBriefsSection
  - L732: SavedBriefsSection call in main render
  - L737-920: Complete `SavedBriefsSection` function (lines 760-920 estimated)
- **Wave U hunks:** Everything else (rate card, filters, automation, working hours, conversations list)

**2. src/lib/api.ts**
- **U-7 hunks:**
  - L7093-7109 (estimated): `creatorBriefs.delete()` method
  - Type definitions for delete (if any exist separate from the method)
- **Wave U hunks:** Everything else including `creatorBriefs.paste`, `.get`, `.list`, `.dismiss`

**3. MeeraSettingsSection.saved-briefs.test.tsx**
- **Entire file is U-7** ✅

**4. Test files that gained creatorBriefs stubs**
- Search showed no widespread mocking of creatorBriefs.delete
- Stubs likely isolated to saved-briefs test only

**5. ConsentScreen.tsx / ConsentScreen.test.tsx**
- **U-7 hunks:** NONE in current tree (Case B text was reverted)
- **Wave U hunks:** Case A text + v2 (entire component as it stands)

**6. Patch file:** `.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/U7-caseB-v3.patch`
- **This IS the U-7 consent change** (Case B + v3)
- Applied only in U-7 commit, never mixed into Wave U

### U-7 Backend (Vikram's domain, noted for completeness)
- `DELETE /creator/briefs/:id` route (CreatorBriefController)
- `CreatorAgentPreferences.CURRENT_CONSENT_VERSION = "v3"`
- Any delete service logic

### Separation Strategy

**Wave U commit includes:**
- ConsentScreen Case A + v2 (current state)
- MeeraSettingsSection WITHOUT SavedBriefsSection function and its calls
- api.ts WITHOUT creatorBriefs.delete
- NO saved-briefs test file

**U-7 commit includes (after Wave U committed):**
- SavedBriefsSection hunks in MeeraSettingsSection.tsx
- api.creatorBriefs.delete in api.ts
- MeeraSettingsSection.saved-briefs.test.tsx
- ConsentScreen Case B + v3 (via U7-caseB-v3.patch)
- Vikram's backend CURRENT_CONSENT_VERSION = "v3"
- Vikram's DELETE route

**Why this matters (per Priya):** U-7's delete would hit a route that doesn't exist yet. A 404 silently removes the row from the list without actually deleting the brief server-side.

**Verdict: LISTED** ✅

---

## Final Tools Check

**TypeScript:** 0 errors ✅ (not re-run; no code changes since round 2)

**Vitest (U-6 tests only):**
```
Test Files  2 passed (2)
     Tests  6 passed (6)
  Duration  12.35s
```
✅ All U-6 tests pass after restorations

**ESLint:** Not re-run (no code changes)

---

## Summary

| Check | Status | Key Finding |
|-------|--------|-------------|
| 1. Case A text | PASS ✅ | Exact match NISHA L116 (EN), L132 (HI). No curly quotes or zero-width chars. |
| 2. Hindi char mutation | PASS ✅ | Test went RED on "होती"→"होता". Line: `expected '...' to contain 'conversations delete करने से वो copy delete नहीं होती'` |
| 3. Version v3 mutation | PASS ✅ | Sync test went RED. Line: `CONSENT_TEXT_VERSION ("v3") does not match ... ("v2")` |
| 4a. Mock read method | PASS ✅ | Via `api.creatorAgentPrefs.getPreferences()`, not regex |
| 4b. Mock v1 alone | PASS ✅ | Test went RED. Line: `api.ts's ... ("v1") does not match ... ("v2")` |
| 4c. Block comment look-alike | PASS ⚠️ | **BUG FOUND:** Parser matched "v9" from inside `/* ... */`. Needs state tracking. |
| 5. No Case B remnants | PASS ✅ | Zero grep hits for "until you delete it", "deleted separately", `'v3'` outside tests |
| 6. U-7 file list | LISTED ✅ | SavedBriefsSection (L760-920), creatorBriefs.delete, saved-briefs.test, U7-caseB-v3.patch |

**All sha256 verified after mutations:** ✅
- ConsentScreen.tsx: `a0383ecf2b2e2e51b255798d2a55414c5f4ff50c31684c8cd91700e744173338`  
- api.ts: `c077e838f80d7e2fe2441cd750d6eba2d029509a0337ca649a173492db83cf59`

**All checks PASS** with one parser weakness noted (4c).

---

**QA Lead:** Kavya Reddy  
**U-6 restore re-verification complete:** 2026-09-17 16:30 UTC  
**Next:** Priya's U-6 build last call
