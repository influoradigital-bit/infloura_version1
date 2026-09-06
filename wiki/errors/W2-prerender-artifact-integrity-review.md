# QA Review: W2-prerender-artifact-integrity.sh & lib/w2_check_route.py
Date: 2026-09-05  
Reviewer: Kavya  
Producer: Vikram  
Status: **REJECT** — Critical coupling risk requires fix before deployment

---

## Executive Summary

The gate correctly detects the specific defect it exists to catch (ErrorBoundary crash captured as success) across all six provided fixtures and Priya's positive control. However, **one critical coupling vulnerability makes this gate fragile against the exact class of change it should survive**: if the ErrorBoundary's error message is reworded, the gate silently stops working.

**Severity: CRITICAL** — this is the same class of defect that has bitten this project twice before (gates greening their own wrong fix, per Priya's brief). The fix is straightforward and non-negotiable.

---

## CRITICAL (Must fix before any deployment)

### 1. **Hardcoded error string creates silent coupling to ErrorBoundary copy** 
**File:** `.proof-os/gates/lib/w2_check_route.py`, line 47  
**Also:** Header comment line 12 quotes the exact string

**The defect:**  
```python
ERROR_STRING = "Something went wrong"
```

This literal string is checked against:
- `src/components/ErrorBoundary.tsx` line 146: `<h1 className="text-2xl font-semibold">Something went wrong</h1>`

**What breaks:**  
If ErrorBoundary.tsx line 146 is reworded to ANY other string — "Oops! An error occurred", "Page Error", "An unexpected error occurred", or even just "Something Went Wrong" with different casing in the h1 — the gate's two core assertions (lines 101–104 and 115–116) no longer detect the crash. The gate would **exit 0 on a genuinely broken homepage** while reporting "PASS — real content, a real \<h1\>".

**Why this is CRITICAL:**  
1. ErrorBoundary copy is UX text, not an API contract. It is **exactly** the kind of thing someone rewrites during a copy-editing pass without realizing a gate depends on it.
2. The gate has no way to know it's been defanged — it still runs, still exits 0, still says "PASS". The coupling is invisible.
3. Per `reference_bash_heredoc_eats_backslashes.md` and the two proof-os gates that greened wrong fixes, this project has precedent for gates silently becoming inert.

**The fix:**  
Check for a STRUCTURAL marker that cannot drift with copy edits:
- Option A: Check for the presence of the ErrorBoundary's **two buttons** (class names `"Try again"` and `"Reload page"`, or the button text itself, which are less likely to change than the h1).
- Option B: Check for the **absence of expected content** rather than presence of error text — e.g., the homepage MUST contain specific landmarks (a CTA href, a known section heading, the brand name in a known position). If those are missing, it's broken regardless of what error string replaced them.
- Option C: Grep `src/components/ErrorBoundary.tsx` at gate-run-time to extract the ACTUAL rendered h1 text, then check for that. Adds a file-read dependency but removes the coupling.

**Recommended:** Option A (check for both buttons' text). The gate would then detect "any page that renders the ErrorBoundary fallback", not "any page that contains this one specific h1 string we hardcoded in 2026".

**Verification test:** After the fix, construct a fixture where `ErrorBoundary.tsx` renders `<h1>Page Error</h1>` instead of "Something went wrong", snapshot that crash, and confirm the gate still exits 1.

---

## HIGH (Fix before delivery, but gate can proceed to Meera for mechanics verification)

### 2. **Floor check `>= 1` for non-homepage routes cannot detect partial regressions**
**File:** `.proof-os/gates/W2-prerender-artifact-integrity.sh`, lines 146–150; `lib/w2_check_route.py`, lines 120–124

**The weakness:**  
Non-homepage routes are checked only for `ld_count >= 1`. Per the verified fixture, `/about` currently ships 3 ld+json blocks. If that route regresses to 1 block (losing 2 of 3), the gate passes with "PASS /about ... ld+json=1".

**Is this acceptable?**  
The header comment (lines 43–56) explicitly documents this as a known trade-off: "A floor of >=1 ... distinguishes a healthy route from a route that regressed to zero schema blocks ... it just does not pretend to know the correct exact count for routes nobody has derived one for yet."

That reasoning is sound AS LONG AS going from 3→1 blocks is not itself a regression. But it **is** a regression if those 2 lost blocks were weight-bearing for SEO (e.g., Organization + WebPage schema).

**The fix:**  
Derive exact counts for the routes that matter most (at minimum: `/about`, `/pricing`, `/how-it-works-brands`, `/how-it-works-creators`) using the same grep-and-cite method the gate already documents for the homepage (lines 29–41). Update lines 146–150 to route those known paths to their exact counts instead of the -1 floor.

This is not a gate-blocker if Priya rules that "any schema presence is enough for non-homepage routes", but it is a known gap.

---

## MEDIUM (Document or accept as design)

### 3. **Homepage ld+json count is brittle against W1 hero changes**
**File:** `.proof-os/gates/W2-prerender-artifact-integrity.sh`, lines 29–41

**The situation:**  
The gate hardcodes `expected=5` for the homepage, derived from:
- 4 blocks in `src/pages/landing.tsx` (lines 217, 218, 219, 234)
- 1 block in `src/components/site/FaqSection.tsx` (line 56, with `emitSchema` defaulting true)

If Ananya's W1 hero work adds or removes a `<JsonLd>` call (e.g., adds a Product schema, or moves the WebPage schema into a different component), the gate will reject a healthy build.

**Is this acceptable?**  
Yes, per the header's own instructions (line 40–41): "re-run `grep -n '<JsonLd' src/pages/landing.tsx` if this ever needs re-deriving after a landing.tsx edit."

This is **documented brittleness**, which is better than silent brittleness. The gate failing on a legitimate change is a LOUD failure (build blocked, developer investigates, finds the comment, re-derives the count). That's the correct failure mode.

**Recommendation:** Accept as-is. The alternative — checking for `>= 5` instead of `== 5` — would let the homepage silently GAIN blocks without review, which is also a regression (unreviewed schema changes affect SEO).

---

## LOW (Note for future maintenance)

### 4. **Bash 4+ required for `mapfile -d ''`**
**File:** `.proof-os/gates/W2-prerender-artifact-integrity.sh`, line 120

**Status:** Verified — this Windows box runs bash 5.2.37 (Git Bash). Linux CI almost certainly runs bash 4+.

**Action:** None required. If a future CI environment runs bash 3.x, the gate will error loudly at line 120 with "mapfile: command not found", which is an exit 2 (GATE UNAVAILABLE), not a false pass.

---

## VERIFIED WORKING ✓

1. **All six fixtures reproduce as expected:**
   - `case1-todays-real-broken` → exit 1, 3 reasons (error text, h1 is error, 1 block not 5)
   - `case6-genuinely-healthy/about` → exit 0, ld+json=3
   - Priya's `_priya-positive-control` → exit 0, ld+json=5

2. **Comment-stripping fix is present** (w2_check_route.py lines 54, 57, 96) — catches the fixture-authoring trap where an HTML comment's bare `<h1>` tag matched into the real closing tag.

3. **Python interpreter probe is robust** (W2 script lines 96–102) — tests both `python3` and `python` with `--version`, catches Windows Store stub that resolves but doesn't run.

4. **Case-insensitive h1 matching** (w2_check_route.py line 52: `re.IGNORECASE`) — handles `<H1>`, `<h1>`, `<H1>`.

5. **HTML entity decoding in h1 check** (w2_check_route.py line 112: `html.unescape(inner)`) — so `Something&nbsp;went&nbsp;wrong` in the h1 still fails check 2, even though check 1 would miss it. However, this is moot once finding #1 (hardcoded string) is fixed.

6. **Standards compliance:**
   - `set -uo pipefail` ✓ (line 75)
   - `cd` to project root early ✓ (line 83)
   - Exit codes 0/1/2 with clear meanings ✓
   - Header comment documents the defect and scope ✓
   - No writes outside project ✓
   - No temp state left behind ✓

7. **Portability:** Works on Windows (Git Bash 5.2.37), will work on Linux CI. Python shebang is not load-bearing (the bash script probes for a working interpreter).

---

## CANNOT VERIFY WITHOUT LIVE BUILD

- Whether `dist/` is actually scanned correctly when the gate runs in CI (I can only test it against fixtures)
- Whether the real `dist/index.html` matches the defect pattern in `case1` (Priya says it does, but I haven't seen the real file)

---

## ADVERSARIAL TESTS ATTEMPTED

I attempted to construct cases that would make the gate false-pass (exit 0 on a broken page):

| Attack | Result |
|--------|--------|
| Error text inside `<!-- HTML comment -->` | Blocked by `strip_html_comments()` on line 96 — comment stripped before checks run. Passes, but page isn't actually broken (comment never renders). |
| Error string split across tags: `<h1>Something <span>went</span> wrong</h1>` | Caught by check 2 (line 111: `TAG_RE.sub()` removes tags, then line 115 detects the error string). |
| Uppercase `<H1>` tags | Caught by `re.IGNORECASE` on line 52. |
| HTML entities: `Something&nbsp;went&nbsp;wrong` | Check 1 misses it (line 101 checks raw content), but check 2 catches it after `html.unescape()` on line 112. |
| Different error message entirely | **This is finding #1 above — CRITICAL vulnerability.** |

None of these bypass ALL three checks, except changing the ErrorBoundary message (finding #1).

---

## VERDICT

**REJECT** — route back to Vikram for fix of finding #1 (ERROR_STRING coupling).

The gate's mechanics are sound and its six fixtures prove it detects the defect. But it is coupled to UX copy that will drift, and when it drifts, the gate becomes silently inert. This is a known failure mode in this project (per memory, two prior gates greened wrong fixes; per the brief, this one exists because `prerender.mjs:315` was satisfied by its own failure).

**Required fix:**  
Replace the hardcoded `ERROR_STRING = "Something went wrong"` check with a structural check for the ErrorBoundary's fallback UI (e.g., presence of both "Try again" and "Reload page" button text, which are less likely to change and serve no other purpose).

**After the fix:**  
Re-submit to me (Kavya) for re-review of the coupling fix only. If that passes, then route to Meera for live-build verification.

**Optional improvements (HIGH severity, but not gate-blockers):**  
- Derive exact ld+json counts for `/about`, `/pricing`, and how-it-works pages (finding #2)
- Add a self-test: grep ErrorBoundary.tsx at run-time to confirm the expected error string is still present (catches drift before the gate silently fails)

---

## FILES REVIEWED

- `.proof-os/gates/W2-prerender-artifact-integrity.sh` (179 lines)
- `.proof-os/gates/lib/w2_check_route.py` (144 lines)
- `.proof-os/gates/fixtures/w2/case1-todays-real-broken/index.html`
- `.proof-os/gates/fixtures/w2/case6-genuinely-healthy/about/index.html`
- `.proof-os/gates/fixtures/w2/_priya-positive-control/index.html`
- `src/components/ErrorBoundary.tsx` (173 lines) — verified actual error string
- `src/pages/landing.tsx` (grep for JsonLd blocks) — verified count of 4
- `src/components/site/FaqSection.tsx` (grep for emitSchema) — verified default true, 1 block
- `.proof-os/gates/F-SEO-marketing-surface.sh` (standards reference)

---

## NEXT STEPS

1. **Vikram:** Replace hardcoded ERROR_STRING check with structural fallback detection (buttons, or absence-of-expected-content, or runtime grep of ErrorBoundary.tsx). Document the new check in the header comment.
2. **Vikram:** Add a fixture (`case7-different-error-message`) where ErrorBoundary renders a different h1, confirm gate still exits 1.
3. **Vikram:** Re-submit to Kavya via `SHARED_CONTEXT.md`.
4. **Kavya (after re-review):** If coupling fix is solid, route to Meera for live `dist/` verification.
5. **Meera:** Run gate against real `dist/` after `npm run build`, confirm exit 1 on current broken state (if Priya's manual test still reproduces), then confirm exit 0 after the hero is fixed.

---

**QA Checklist Results:**

```
TypeScript/Code Standards: N/A (bash + Python, not TS)
Security Checks:
  ✓ No hardcoded credentials
  ✓ No external writes
  ✗ Silent coupling to UX copy (CRITICAL — fix required)
Performance: N/A (gate, not runtime code)
Accessibility: N/A (gate, not UI)
Architecture:
  ✓ Follows gate conventions
  ✓ Exit codes correct
  ✓ Header comment clear
  ✓ No temp state
  ✗ Hardcoded expectation fragile to copy edits (CRITICAL)
```

**Blocked by:** Finding #1 (ERROR_STRING coupling)  
**Can proceed after:** Structural check replaces literal string check  
**Evidence required:** Fixture proving gate catches ErrorBoundary with different h1 text
