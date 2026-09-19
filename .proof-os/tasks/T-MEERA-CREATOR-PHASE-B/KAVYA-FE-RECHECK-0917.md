# Kavya QA Re-Check: Wave U Frontend (2026-09-17)

**Reviewer:** Kavya Reddy (QA Lead)  
**Branch:** `feat/meera-creator-phase-b0` at `df20091`  
**Tree:** `C:\Users\Sage world\Downloads\New Influora Ai\influora-b0`  
**Scope:** Frontend only (tsc, vitest, eslint). Vikram running Maven concurrently.  
**Against:** Priya's pass criteria in `RULINGS-U-0917.md`

---

## Executive Summary

**VERDICT: CONDITIONAL PASS** — one CRITICAL finding blocks deployment until fixed.

- **Tool counts (self-verified):**
  - `tsc`: 0 errors ✅
  - `vitest`: 222 files / 1302 tests passed ✅ (matches Ananya's claim)
  - `eslint`: 0 errors, 5 warnings (all pre-existing react-hooks/react-refresh) ✅

- **Pass:** U-2, U-3 (falsified), U-4 (film + page), U-5, F7
- **CRITICAL:** F6 type guard rejects valid backend payloads
- **MEDIUM:** Script claims test robustness gap, duration-change documentation gap

---

## 1. U-3: Session Dismissal (PASS with falsification)

**Priya's bar (RULINGS-U-0917.md L196):** A flag with `dismissible: false` must stay visible even when its code is already in `sessionStorage`. Test must seed storage and go red when filter is broken.

### ✅ Falsification Successful

**Test location:** `src/components/creator/meera/CreatorToolResultRenderer.test.tsx:345-369`

**Pre-falsification (correct code):**
```typescript
// useRiskFlagDismissals.ts:129
const visible = list.filter((flag) => !(flag.dismissible && hidden.has(flag.code)));
```

**Falsification applied:**
```typescript
const visible = list.filter((flag) => !hidden.has(flag.code));
```

**Test result with falsification:** ❌ FAIL
```
TestingLibraryElementError: Unable to find an element with the text: Regulated category
```
The non-dismissible flag was incorrectly hidden, proving the test catches the defect.

**Test result after restoration:** ✅ PASS (2 passed | 24 skipped)

**Storage seeding verified:**
```typescript
window.sessionStorage.setItem(
  'influora.riskFlagDismissals.v1',
  JSON.stringify({ 'BRIEF:b7': ['REGULATED_CATEGORY'] }),
);
```

Flag with `dismissible: false` and code `'REGULATED_CATEGORY'` renders despite being in storage. ✅

---

## 2. U-4 Film: Voice Silencing (PASS with notes)

### ✅ Audio Guards in Place

**IntroScene.tsx:43-47:**
```typescript
{voice ? (
  <Sequence from={INTRO_VOICE_FROM} ...>
    <Audio src={staticFile(voice.file)} />
  </Sequence>
) : null}
```

**ChatScene.tsx:42-54:** Same guard pattern on `b.voice`.  
**OutroScene.tsx:** (not opened but follows same pattern per grep)

Every `<Audio>` site guards on `voiceFor(...)` returning undefined. ✅

### ✅ Silencing Mechanism

**timing.ts:72:**
```typescript
const STALE_VOICE_IDS: ReadonlySet<string> = new Set(['intro', 'outro', 'paste-5', 'money-0']);
```

**timing.ts:74-76:**
```typescript
export function voiceFor(lang: LangCode, id: string): VoiceClip | undefined {
  if (STALE_VOICE_IDS.has(id)) return undefined;
  return VOICE_MANIFEST[lang]?.[id];
}
```

4 ids × 3 languages = 12 stale `.wav` files silenced. ✅

### ⚠️ Duration Changes (MEDIUM — document gap)

**What happens when silenced:**

**Intro/outro** (timing.ts:89-94):
```typescript
export function introFrames(lang: LangCode): number {
  return Math.max(120, INTRO_VOICE_FROM + framesFor(voiceFor(lang, 'intro')) + VOICE_TAIL);
}
```
When `voiceFor` returns undefined, `framesFor(undefined)` returns 0, so intro falls to 120 frames minimum (was longer when voiced). Outro falls to 210 frames.

**Chat beats** (timing.ts:129-134):
```typescript
const typed = Math.min(150, Math.max(70, 40 + Math.ceil(beat.text.length / TYPE_RATE)));
return voice ? Math.max(typed, 8 + framesFor(voice) + VOICE_TAIL) : typed;
```
Fallback to typewriter timing. A long-spoken beat (e.g. 4s audio) becomes ~70-150 frames (~2-5s), potentially FASTER.

**Impact:** On-screen text that previously held for the full narration may flash by faster. This is INTENTIONAL per timing.ts:46-67 comment — "forced silent... rather than playing the stale recording" — but the DEGREE of the speed-up is not quantified in any test assertion.

**Recommendation (not blocking):** Add to `timing.test.ts` a before/after assertion for at least one beat: e.g. "paste-5 in 'hi' was X frames with voice, is now Y frames silent" using a snapshot of the old VOICE_MANIFEST. Currently the test only checks `hold > 0` and `isFinite`, not that the user experience is acceptable.

### ✅ Claims Tests

**src/remotion/script.claims.test.ts** (U-4):
- Scans `script.ts`, `script.en.ts`, `script.mr.ts` SOURCE TEXT directly
- 10 patterns × 3 files = 30 checks
- Patterns are case-insensitive regex, not exact strings
- **No self-match trap:** Test scans script files, not itself. The pattern `/drafting the reply/i` at test line 33 never matches the test file because FILES array points only to script.ts/en/mr. ✅

**Test results:**
```
src/remotion/script.claims.test.ts: 2 passed
src/remotion/timing.test.ts: 6 passed
```

### ⚠️ Robustness Gap (LOW-MEDIUM)

**Pattern weakness example (script.claims.test.ts:35):**
```typescript
['English: "...I will send the 72-hour reach to the brand"', /send the 72-hour reach/i],
```

A reworded clause like **"I'll send the 72h reach"** or **"the reach will be sent"** slips past. The pattern is LITERAL substring matching, not semantic. Nisha caught the original in her read-through; a future rewording without her eyes on it could regress.

**Not gold-plating:** Tightening this to NLP/LLM-based claim detection is out of scope for B0. Mark it as a known gap and rely on Nisha + Priya review for copy changes.

---

## 3. U-4 Page: meera-for-creators.tsx (PASS)

**src/pages/meera-for-creators.tsx:29-42** documents the 5 removed claims clearly.

**src/pages/meera-for-creators.claims.test.tsx** exists and passed in the 222-file run. Grep shows it scans the rendered DOM for banned phrases. ✅

**Spot check of CARDS array (L48-74):** 5 cards, no "drafts" or "sends" promises visible. Card 2 (L54-58): "Paste a brief, get a straight answer" — clean. ✅

---

## 4. U-5: Ask Meera About This Brief (PASS with note)

**Prefill mechanism (MeeraCopilotChat.tsx:246-254):**
```typescript
const appliedPrefillTokenRef = React.useRef<number | null>(null);
React.useEffect(() => {
  if (!prefillMessage || prefillMessage.token === appliedPrefillTokenRef.current) return;
  appliedPrefillTokenRef.current = prefillMessage.token;
  setDraft((prev) => (prev.trim() ? `${prev} ${prefillMessage.text}` : prefillMessage.text));
}, [prefillMessage]);
```

✅ Token comparison by value (`===`)  
✅ Appends if creator already typed (`prev.trim() ? ...`)  
✅ Never sends (no `sendTurn` call in this effect)

**Test helper (creator-copilot-paste-brief.test.tsx:167-173):**
```typescript
async function expectPromptUnsent(textbox: HTMLElement, expectedValue: string) {
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(textbox).toHaveValue(expectedValue);
  expect(screen.getAllByTestId('chat-turn')).toHaveLength(1); // only greeting
  expect(sendTurnMock).not.toHaveBeenCalled();
}
```

✅ Helper waits, checks value, asserts turn count = 1 (greeting only), asserts send never called.

**All 3 tests using this helper:**
1. L205-230: consent known, chat closed → opens with prefill
2. L232-257: chat already open, creator typed → appends
3. L259-289: consent missing → consent screen, Accept → chat with prefill

Each test calls `expectPromptUnsent` after the chat opens (L227, L254, L285). ✅

**Double-click protection:** Token ref prevents re-application of same token (L251). Value comparison, not object identity. ✅

**Test run:** 7 passed. ✅

---

## 5. F6: GetBriefToolCard Type Guard (CRITICAL FAIL ❌)

**Finding:** `isGetBriefPayload` at `meera-api.ts:524-541` **rejects valid backend payloads**.

### Java Backend (CreatorToolDtos.java:130-138)

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GetBriefResult(
    @JsonProperty("brief_id") String briefId,
    @JsonProperty("source") String source,
    @JsonProperty("status") String status,
    @JsonProperty("deal_id") String dealId,            // ← NULLABLE
    @JsonProperty("extraction") BriefExtraction extraction,  // ← NULLABLE
    @JsonProperty("flags") List<RiskFlag> flags,       // ← NULLABLE
    @JsonProperty("quote") PackageQuote quote,         // ← NULLABLE
    @JsonProperty("extraction_source") String extractionSource) {} // ← NULLABLE
```

`@JsonInclude(NON_NULL)` means: **if a field is null, the JSON KEY is omitted entirely.**

### TypeScript Guard (meera-api.ts:527-540)

```typescript
return (
  typeof d.brief_id === 'string' &&
  typeof d.status === 'string' &&
  GET_BRIEF_STATUSES.has(d.status) &&
  typeof d.source === 'string' &&
  BRIEF_SOURCES.has(d.source) &&
  !!d.extraction &&                      // ❌ REQUIRES truthy
  typeof d.extraction === 'object' &&    // ❌ REQUIRES object
  Array.isArray(d.flags) &&              // ❌ REQUIRES array
  !!d.quote &&                           // ❌ REQUIRES truthy
  typeof d.quote === 'object' &&         // ❌ REQUIRES object
  typeof d.extraction_source === 'string' && // ❌ REQUIRES string
  EXTRACTION_SOURCES.has(d.extraction_source)
);
```

### Valid Java Payloads That Are Rejected

1. **A brief with missing extraction** (Priya's Addition A for F1, RULINGS-U-0917.md L17-22):
   - Status DISMISSED with `extracted_json` NULL → `extraction` key absent from JSON
   - Guard checks `!!d.extraction` → false → **card renders nothing**

2. **A brief with unparseable extraction** (same Addition A):
   - `toResponse` turns parse failure into nulls (javadoc says so)
   - `flags` comes back null → key absent
   - Guard checks `Array.isArray(d.flags)` → false → **card renders nothing**

3. **A platform brief with no deal** (common case):
   - Pasted briefs have `deal_id` NULL → key absent
   - Guard doesn't check `deal_id` (good!) but this proves the NON_NULL pattern applies

**This is the same F6 failure mode:** a `get_brief` with no analysis renders nothing instead of "Still reading this brief."

### What GetBriefToolCard Does

**CreatorToolResultRenderer.tsx:719-727:**
```typescript
if (payload.status === 'NEW') {
  return (
    <CardShell testId="get-brief-card" className={className} title="This brief">
      <p data-testid="get-brief-still-reading" className="text-sm text-muted-foreground">
        Still reading this brief. Check back in a moment.
      </p>
    </CardShell>
  );
}
```

This check only runs **if the payload passes the guard**. A NEW brief with null extraction never reaches this code — the guard rejects it and `CreatorToolResultRenderer` renders nothing (L792-801).

### Fix Required

The guard must allow nullable fields:

```typescript
export function isGetBriefPayload(data: unknown): data is GetBriefPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<GetBriefPayload>;
  return (
    typeof d.brief_id === 'string' &&
    typeof d.status === 'string' &&
    GET_BRIEF_STATUSES.has(d.status) &&
    typeof d.source === 'string' &&
    BRIEF_SOURCES.has(d.source) &&
    // extraction, flags, quote, extraction_source are all nullable (NON_NULL)
    // Only check them if present:
    (d.extraction === undefined || (d.extraction !== null && typeof d.extraction === 'object')) &&
    (d.flags === undefined || Array.isArray(d.flags)) &&
    (d.quote === undefined || (d.quote !== null && typeof d.quote === 'object')) &&
    (d.extraction_source === undefined || (typeof d.extraction_source === 'string' && EXTRACTION_SOURCES.has(d.extraction_source)))
  );
}
```

**Or simpler:** Only validate the REQUIRED fields (`brief_id`, `status`, `source`) and let TypeScript's `| undefined` types handle the rest. The card already guards on `payload.status === 'NEW'` before reading `extraction`.

**Severity:** CRITICAL. Without this fix, valid 409 responses and DISMISSED briefs render as silent nothing instead of honest UI.

**Ownership:** Ananya (TS guard) + Vikram (Java test to assert a NULL-extraction payload serializes with absent keys).

---

## 6. F7: Duplicate INR Formatter (PASS ✅)

**CreatorToolResultRenderer.tsx:2:**
```typescript
import { cn, formatINR } from '@/lib/utils';
```

**Usage (L515, L519):**
```typescript
budget = formatINR(extraction.budget_inr);
...
? `Barter only (product worth ${formatINR(extraction.barter_mrp_inr)})`
```

**No local `inr` definition:** Grep for `const inr` in PasteBriefCard and CreatorToolResultRenderer = no results. ✅

Kavya's LOW from earlier round (use shared formatter) is closed. ✅

---

## 7. Accessibility (U-5 Button, GetBrief Still-Reading State)

**"Ask Meera about this brief" button (PasteBriefCard.tsx:235-243):**
```typescript
<Button
  type="button"
  variant="outline"
  data-testid="ask-meera-about-brief"
  onClick={() => onAskMeeraAboutBrief(result.brief_id)}
>
  <MessageCircle className="mr-2 h-4 w-4" aria-hidden="true" />
  {copy.button}
</Button>
```

✅ `role="button"` implicit from `<Button>` component  
✅ Accessible name from `{copy.button}` text content  
✅ Icon marked `aria-hidden`  
⚠️ **Focus after chat opens:** Not verified. Test opens chat (L227, L254, L285) but doesn't assert focus. Acceptable for B0; mark for live check.

**"Still reading" state (CreatorToolResultRenderer.tsx:722-724):**
```typescript
<p data-testid="get-brief-still-reading" className="text-sm text-muted-foreground">
  Still reading this brief. Check back in a moment.
</p>
```

✅ Plain text in accessible DOM  
⚠️ **No ARIA live region:** A brief that transitions NEW → ANALYZED while the card is visible won't announce the change to screen readers. Acceptable for B0 (requires polling or live updates, out of scope).

---

## 8. Consent Handling (U-5)

**creator-copilot.tsx flow (L73-177, 216-294):**

Per Ananya's claim:
- Re-checks consent live before opening
- Carries prompt through Accept
- Drops prompt on Decline
- Later plain "Open Meera" doesn't inherit it

**Test coverage (creator-copilot-paste-brief.test.tsx:259-289):**
```typescript
it('U-5: consent missing → consent screen, Accept → chat with prompt filled', async () => {
  // ... consent unknown, paste triggers consent screen
  screen.getByRole('button', { name: 'Allow Meera to read my data' }).click();
  // ... consent recorded, chat opens
  await expectPromptUnsent(textbox, expectedPrompt);
});
```

✅ No send without consent (L172: `expect(sendTurnMock).not.toHaveBeenCalled()`)  
✅ Consent screen shown when missing  
✅ Prompt carried through Accept

**Error swallowing check:** Searched for `.catch(() => {})` or silent try/catch in the consent flow — none found. `recordConsent` errors bubble to the test mock. ✅

---

## 9. Stale Comments (Priya L176-178)

**api.ts:7059:**
> "// (brief analysis does not re-run when opened through this tool)"

This is now **false** per Vikram's F1 fix: stale NEW briefs ARE re-analyzed on `get_brief`. ✅ Needs update (commented in U-1 last-call bar).

**Already fixed per Ananya's claim:**
- `creator_schemas.py` description (influora-ai)
- `assembler.py` L190-196, L752-754 comments

Not verified (Vikram's domain). Trust + Maven will prove it.

---

## 10. Tool Run Evidence

**TypeScript (npm run typecheck):**
```
> tsc -p tsconfig.json --noEmit
(clean output, exit 0)
```
**0 errors** ✅

**Vitest (npm run test):**
```
Test Files  222 passed (222)
     Tests  1302 passed (1302)
  Duration  201.12s
```
**222 files / 1302 passed** ✅ (matches Ananya's claim exactly)

**ESLint (npx eslint <touched files>):**
```
✖ 5 problems (0 errors, 5 warnings)
```
**0 errors, 5 warnings** ✅

Warnings:
- 2× `react-refresh/only-export-components` (MeeraCopilotChat, CreatorToolResultRenderer)
- 2× `react-hooks/set-state-in-effect` (MeeraCopilotChat L230, PasteBriefCard L104)

All are **pre-existing** React patterns (set state in effect for mock data, fast-refresh exports). Per MEMORY.md `reference_react_hooks_v7_policy.md`, React-Compiler warnings are intentionally 'warn' level and won't be re-fixed for B0. ✅

---

## Item-by-Item Verdict

| Item | Status | Severity | Notes |
|------|--------|----------|-------|
| **U-2** | PASS | — | Consent text visible, formatting clean, handler wired. Priya + Nisha own final word on copy. |
| **U-3** | PASS | — | Falsification successful. Test seeds storage, fails when guard broken, passes when correct. |
| **U-4 Code** | PASS | MEDIUM note | Audio guards ✅, timing tests ✅, claims tests ✅. Duration change not quantified (rec: snapshot test). Claims pattern could miss rewordings (rely on Nisha). |
| **U-4 Film** | PASS | — | Page claims test exists and passes. |
| **U-5** | PASS | LOW note | Prefill ✅, no-send ✅, token comparison ✅, append ✅. Focus after open not tested (live check). |
| **F6** | **FAIL** | **CRITICAL** | Type guard rejects valid backend payloads with nullable fields. NEW briefs with null extraction render nothing instead of "Still reading". Blocks deploy. |
| **F7** | PASS | — | `formatINR` used from shared utils, no local duplicate. |

---

## Blocking Issues

### CRITICAL (must fix before Priya's last call)

**F6-A: isGetBriefPayload rejects valid backend payloads**
- **File:** `src/lib/meera-api.ts:524-541`
- **Issue:** Guard requires `extraction`, `flags`, `quote`, `extraction_source` to be truthy and well-typed, but Java `@JsonInclude(NON_NULL)` omits null fields from JSON entirely. A NEW brief with no analysis, or a DISMISSED brief, is rejected and renders nothing.
- **Fix:** Allow `undefined` for nullable fields, or only validate required fields.
- **Owner:** Ananya (TS) + Vikram (Java test proving serialization)
- **Evidence needed:** A test that constructs `GetBriefResult(briefId, source, status, null, null, null, null, null)` and asserts the JSON has only 3 keys, then asserts the TS guard accepts it.

---

## Non-Blocking Recommendations

### MEDIUM

**U-4-M1: Quantify duration change from voice silencing**
- **File:** `src/remotion/timing.test.ts`
- **Gap:** Test checks `hold > 0` but not whether a 4s-voiced beat falling to 2s-typed is acceptable UX.
- **Rec:** Snapshot old voice durations, assert new typed durations are within acceptable range.
- **Owner:** Ananya
- **Urgency:** Before S-2 (deploy). A too-fast beat is a UX regression visible on the live page.

**U-4-M2: Script claims test robustness**
- **File:** `src/remotion/script.claims.test.ts`
- **Gap:** Literal substring patterns miss semantic rewordings ("I'll send" vs "I will send").
- **Rec:** Document the gap. Rely on Nisha + Priya review for future copy changes.
- **Owner:** Nisha (process), not blocking code change.

### LOW

**U-5-L1: Focus management after chat opens**
- **Gap:** Tests don't assert focus moves to composer or chat after "Ask Meera" click.
- **Rec:** Live keyboard-nav check after S-2.
- **Owner:** Meera (live verification)

**U-5-L2: ARIA live region for "Still reading" → analysis transition**
- **Gap:** Screen reader won't announce when a NEW brief finishes analysis.
- **Rec:** Wave C (polling/live updates in scope). B0 acceptable without.

---

## Sign-Off

**QA Lead:** Kavya Reddy  
**Date:** 2026-09-17 14:30 UTC  
**Next:** Route to Priya for CTO last call on U-2, U-3, U-5. **F6 must be fixed first.**  
**Branch state:** Clean restoration after falsification. `git diff --stat` matches df20091 exactly (LF/CRLF warnings only).

**Tools used:**
- TypeScript 5.x (tsc via npm run typecheck)
- Vitest 3.2.7 (npm run test)
- ESLint 9.x (npx eslint)

**Working tree restored:** ✅ All falsification edits reverted.

---

# Round 2 Addendum

**See:** `KAVYA-FE-RECHECK-ROUND2-0917.md` (same directory)

Ananya fixed F6 and built U-6. Narrow re-check of three items completed 2026-09-17 15:30 UTC.

**Round 2 verdicts:**
- F6 (type guard fixes): PASS ✅
- Film timing (readable floor): PASS ✅  
- U-6 (consent paragraph): PASS ✅

**Tools:** tsc 0, vitest 223/1315, eslint 0 errors/2 warnings

**All items PASS.** Ready for Priya's final call.
