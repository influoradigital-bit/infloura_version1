# Round 2: Narrow Re-Check (2026-09-17 15:00 UTC)

**Coordinator:** Ananya fixed F6 and built U-6. Narrow re-check of three items only; other passes stand.

**Tools (self-verified round 2):**
- ✅ `tsc`: 0 errors
- ✅ `vitest`: 223 files / 1315 tests passed (vs Ananya's claim: 1312 + one timeout retry)
- ✅ `eslint`: 0 errors, 2 pre-existing warnings (react-refresh on CreatorToolResultRenderer)

---

## 1. F6 Round 2: Type Guard Fixes (PASS ✅)

**Changes verified:**
- `src/lib/meera-api.ts:541-562` — `isGetBriefPayload` now allows `quote` and `extraction_source` as optional
- Lines 544-549: Absent passes, malformed present value rejects
- `extraction` and `flags` stay required (lines 556-558)
- New `isNewBriefStub` (lines 572-576) for defense in depth
- `CreatorToolResultRenderer.tsx:707-718` — `StillReadingBriefCard` extracted
- Lines 834-846: Defense-in-depth fallback path

### ✅ Q1: Does NEW-stub bypass allow non-NEW payloads without flags to render as anything but nothing?

**Answer: NO.** Traced the flow:

**Payload:** `{brief_id: 'x', status: 'ANALYZED'}` (no extraction, no flags)

1. Line 835: `isGetBriefPayload(data)` returns `false` (no `extraction` or `flags`)
2. Line 846: `isNewBriefStub(data)` checks `d.status === 'NEW'`
3. Status is `'ANALYZED'` → returns `false`
4. Line 846: Returns `null`

Result: Nothing rendered. ✅ No bypass exists.

**Test evidence (CreatorToolResultRenderer.test.tsx:509-517):**
Test confirms bare NEW stub reaches "Still reading" message; non-NEW malformed payloads render nothing.

### ✅ Q2: Is "No price suggestion for this brief." honest for a brief whose stored price failed to parse?

**Location:** `CreatorToolResultRenderer.tsx:651-653` (in `BriefCard`)

**Context:**
- Backend: `readJson(brief.getQuoteJson(), PackageQuote.class)` returns `null` on parse failure
- `@JsonInclude(NON_NULL)` omits the `quote` key from JSON
- Frontend: `quote` is `undefined`, card renders "No price suggestion for this brief."

**Is it honest?** YES. ✅

The truth: There WAS a quote column value → It failed to parse → So there's NO USABLE quote.

"No price suggestion" is technically correct — the system cannot present a price suggestion. It doesn't claim "this brief has no price" (false); it says there's no suggestion (true). Honest "no data to show" message instead of nonsense or crash.

**Test evidence:** CreatorToolResultRenderer.test.tsx:490-507 confirms card renders with honest fallback text.

**Verdict: F6 PASS**

---

## 2. Film Timing: Readable Floor (PASS ✅)

**Changes verified:**
- `src/remotion/timing.ts:94-97` — `readableFloorFrames(text)` computes ~250ms/word + 1s baseline
- Lines 111-118: `introReadableFloor` / `outroReadableFloor` aggregate all on-screen text
- Lines 122-135: `introFrames` / `outroFrames` take `Math.max(fixedMin, readableFloor, voiceStretch)`
- Lines 220-222: Beat-level floor applied **only when** `STALE_VOICE_IDS.has(id)`

### ✅ Q1: Does readable floor bleed into beats that aren't silenced?

**Answer: NO.** The check at line 220-222 requires ALL three conditions:
1. `!voice` — beat has no voice clip
2. `beat.kind === 'meera' || 'wa'` — spoken beat type
3. `STALE_VOICE_IDS.has(id)` — **this specific id was force-silenced**

A beat that simply never had a recording fails condition 3. ✅

**Test evidence:** timing.test.ts:129-155 pins exact frames per silenced beat (paste-5, money-0, intro, outro) across all 3 languages.

### ✅ Q2: Does total composition duration match what MeeraDemoPlayer/Root.tsx expects?

**Answer: YES.** Duration is **computed dynamically**, not hardcoded.

Flow:
1. `introFrames(lang)` / `outroFrames(lang)` compute from readable floor + voice + fixed min
2. `totalDuration` (timing.ts:247-251) sums intro + scenes + outro - transitions
3. `MEERA_DEMO_DURATION[lang]` computed at module load (demo-meta.ts:10-13)
4. Root.tsx:57 & MeeraDemoPlayer.tsx:52 use that computed value

When intro/outro durations change (silencing), the total recomputes automatically. ✅ No hardcoded mismatch possible.

**Improvement table (from timing.test.ts:108-122):**

| beat     | lang | floor (frames/s) | old hold | gain    |
|----------|------|------------------|----------|---------|
| paste-5  | hi   | 120f / 4.00s     | 80f      | +40f    |
| paste-5  | en   | 150f / 5.00s     | 82f      | +68f    |
| intro    | hi   | 165f / 5.50s     | 120f     | +45f    |
| outro    | hi   | 255f / 8.50s     | 210f     | +45f    |

Silenced beats now hold 0.5-2.3s longer than typing-only. ✅

**Verdict: Film Timing PASS**

---

## 3. U-6: Consent Paragraph (PASS ✅)

**Changes verified:**
- `src/components/meera/ConsentScreen.tsx:28-40` — CONSENT_TEXT with new third paragraph
- `src/lib/api.ts:6645` — `consent_version: 'v2'`
- `src/components/meera/ConsentScreen.test.tsx` — new test file (3 tests)

### ✅ Q1: Character-for-character match against NISHA-CONSENT-0917.md "Final — Case A (v2)"

**English (ConsentScreen.tsx:37):**

Expected (NISHA L116):
> When you paste a brand's brief or message, Meera's AI reads all of it, including any names, emails, phone numbers, addresses, or bank or UPI details in it. Influora saves a copy, and deleting your conversations does not delete it. Before you paste, remove anything you don't want Meera to read.

**Match:** ✅ Exact, character for character.

**Hindi (ConsentScreen.tsx:31):**

Expected (NISHA L132):
> जब आप किसी ब्रांड का brief या message पेस्ट करते हैं, तो Meera की AI उसमें लिखे नाम, email, phone number, पता, या बैंक या UPI details समेत पूरा text पढ़ती है। Influora उसकी एक copy save करता है, और conversations delete करने से वो copy delete नहीं होती। पेस्ट करने से पहले वो सब हटा दें जो आप नहीं चाहते कि Meera पढ़े।

**Match:** ✅ Exact, including "बैंक" (bank) transliterated to Devanagari per Nisha's register ruling.

**Test evidence:** ConsentScreen.test.tsx:46-65 confirms both languages render all three paragraphs in correct order, first two unchanged.

### ✅ Q2: Ban list narrowness — grade honestly

**Ban patterns (ConsentScreen.test.tsx:38-43):**

| Language | Banned phrase | Pattern |
|----------|--------------|---------|
| English  | "until you delete it" | `/until you delete it/i` |
| English  | "deleted separately" | `/deleted separately/i` |
| Hindi    | "जब तक आप उसे delete नहीं करते" | literal match |
| Hindi    | "अलग-अलग delete होते हैं" | literal match |

**What the patterns catch:** Exact Case B phrasings from NISHA-CONSENT-0917.md "Final — Case B (v3)".

**What they miss:**
- Semantic rewordings: "we delete your briefs when you ask"
- Paraphrases: "will be deleted if you request"
- Hindi alternatives: "brief delete कर सकते हैं"
- Passive voice: "can be deleted later"

**Is this a problem?** NO. ✅

Per coordinator: "It's a regression guard for Case B leaking early, not a semantic check, and Kabir has the words last call."

**Purpose:** Prevent copy-paste error shipping Case B text under v2. Patterns match TWO EXACT STRINGS Nisha wrote for Case B. Any other wording would be NEW, written LATER, gets its own review (Nisha → Kabir → Priya).

**Guard-the-guard (ConsentScreen.test.tsx:78-82):**
- Patterns do NOT match allowed negated sentence
- Test confirms allowed sentence IS present (not passing vacuously)

✅ Ban list correctly narrow by design, scoped to regression guard only.

**Recommendation:** KEEP NARROW. Widening to semantic patterns (LLM/NLP) is out of scope. Existing gates (Nisha copy review, Kabir legal accuracy, Priya CTO ruling, consent version bump) already prevent wrong promises. This test only catches accidental copy-paste of WRONG CASE's exact strings.

### ✅ Q3: Is `consent_version` referenced anywhere else in src/ still saying v1?

**Search:** Grepped src/ for v1 references in consent_version.

**Results:**
- `src/lib/api.ts:6645` — `consent_version: 'v2'` ✅
- Type definition (line 6560): `consent_version: string;` (no hardcoded check)

No other mocks found. Version is data, not logic. ✅

**Verdict: U-6 PASS**

---

## Round 2 Summary

| Item | Status | Notes |
|------|--------|-------|
| **F6 Round 2** | PASS ✅ | Type guard fixed. Optional fields allowed, malformed rejected. Defense in depth works. "No price suggestion" honest. |
| **Film Timing** | PASS ✅ | Readable floor applies only to stale beats. Total duration computed, not hardcoded. Tests pin exact frames. |
| **U-6 Consent** | PASS ✅ | Case A text matches Nisha's final character-for-character. consent_version v2. Ban list correctly narrow (regression guard, not semantic). |

**Tools round 2:**
- tsc: 0 errors ✅
- vitest: 223 files / 1315 tests ✅ (no timeout; Priya's Maven may not have been running concurrently)
- eslint: 0 errors, 2 pre-existing warnings ✅

**Git diff final:** 40 files, 2335 insertions, 342 deletions (slightly more than round 1 baseline due to round 2 fixes + U-6)

**All round 2 items PASS.** Ready for Priya's final call on U-2, U-3, U-5, U-6.

---

**QA Lead:** Kavya Reddy  
**Round 2 complete:** 2026-09-17 15:30 UTC  
**Next:** Priya CTO last call (U-2, U-3, U-5, U-6). Nisha has last call on U-6 wording (already approved in NISHA-CONSENT-0917.md).
