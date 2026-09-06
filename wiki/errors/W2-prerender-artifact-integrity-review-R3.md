# QA Review: W2-prerender-artifact-integrity (Round 3)
Date: 2026-09-05  
Reviewer: Kavya  
Producer: Priya (reordering) + Vikram (original gate)  
Status: **REJECT** — One CRITICAL security gap requires fix before deployment

---

## Executive Summary

Both prior CRITICALs from round 2 are **VERIFIED FIXED**. The reordering (checks 1-3 before baseline load) correctly prioritizes crash detection over baseline lookup. Homepage baseline arithmetic is correct at 6.

However, **one CRITICAL security gap remains**: `W2_SELFTEST=1` can be exploited via environment pollution (stray CI export, shell profile, malicious injection) to honor a poisoned baseline AND report PASS, hiding real regressions. The gate must refuse to exit 0 when running in self-test mode.

---

## CRITICAL (Must fix before any deployment)

### **W2_SELFTEST=1 allows poisoned baselines to green broken builds**
**Files:** `.proof-os/gates/lib/w2_check_route.py` lines 126-149, 213-214  
**Severity:** CRITICAL — bypass allows silent regression hiding

**The vulnerability:**  
The current fix gates overrides on `W2_SELFTEST=1` being set (lines 142-148). This correctly blocks **accidental** override use (exit 2 if override set without flag). But it does NOT block **intentional** override use when the flag is present.

**Attack scenario:**  
1. Attacker (or stray automation) sets `export W2_SELFTEST=1` in CI environment
2. Attacker sets `W2_BASELINE_PATH` to a poisoned baseline (e.g., `_priya-poison-passing-kavyas-validation.json`: `{"/about": 1}`)
3. Gate loads the poisoned baseline (line 213 prints the override path, but buried in CI logs)
4. Real route has 2 ld+json blocks, baseline says 1, gate **exits 0** with PASS
5. A schema regression (3→2 blocks) is hidden because the floor was lowered

**Proof:**  
```bash
# Without flag: correctly refused
$ W2_BASELINE_PATH=poisoned.json python w2_check_route.py about.html about
GATE UNAVAILABLE: W2_BASELINE_PATH is set but W2_SELFTEST=1 is not
(exit 2) ✓

# With flag: loads poison AND exits 0
$ W2_SELFTEST=1 W2_BASELINE_PATH=poisoned.json python w2_check_route.py about.html about
[w2] ld+json baseline loaded from: poisoned.json
PASS /about - ld+json=2 (baseline 1)
(exit 0) ⚠️ REGRESSION HIDDEN
```

Tested with fixture `.proof-os/gates/fixtures/w2/_priya-poison-passing-kavyas-validation.json` against `_priya-ldjson-regression/about/index.html` — exit 0, regression missed.

**Why this is CRITICAL:**  
- `W2_SELFTEST=1` is an environment variable, not a file-tracked setting
- A single `export W2_SELFTEST=1` left in a CI job or `.bashrc` enables the attack globally
- The override is honoured silently (only the baseline path prints, no WARNING)
- The gate exits 0, greening the build while a real regression ships
- Per `feedback_falsify_gates_against_wrong_fix.md`, this project has precedent for gates greening wrong fixes

**The fix — Option A (RECOMMENDED):**  
If `W2_SELFTEST=1` is set, the gate MUST NEVER exit 0. A self-test run is a test harness, not a production gate run. Change `main()`:

```python
def main() -> int:
    # ... existing checks ...
    
    if os.environ.get("W2_SELFTEST") == "1":
        # Self-test mode detected — this run is part of the falsification
        # harness, not a real production gate. A self-test can exit 1 (test
        # failed, e.g. fixture correctly detected as broken) or exit 2 (gate
        # unavailable, e.g. bad override path), but must NEVER exit 0 and
        # green a build. Emit a warning so CI logs show this is not a normal
        # run, then convert any exit-0 to exit-2.
        print("[w2] WARNING: W2_SELFTEST=1 active — running in falsification harness mode")
        print("[w2] A self-test run will NEVER report PASS (exit 0) — only FAIL or UNAVAILABLE")
    
    # ... rest of main() unchanged until the final PASS ...
    
    # At line 371, before `return 0`:
    if os.environ.get("W2_SELFTEST") == "1":
        print("GATE UNAVAILABLE: W2_SELFTEST=1 is active — cannot report PASS in self-test mode")
        return 2
    
    print(f"PASS {route_label} ({path}) - ld+json={ld_count} (baseline {expected_min})")
    return 0
```

**Alternative fix — Option B (defense in depth, ALSO recommended):**  
Add a second layer: if ANY override is in use, the gate must print a VISIBLE warning at the top and bottom of its output, not just the baseline-load line (which is buried):

```python
# At top of main(), after argument parse:
if os.environ.get("W2_BASELINE_PATH") or os.environ.get("W2_ERRORBOUNDARY_PATH"):
    print("=" * 70)
    print("WARNING: OVERRIDE ACTIVE — NOT A NORMAL GATE RUN")
    if os.environ.get("W2_BASELINE_PATH"):
        print(f"  W2_BASELINE_PATH={os.environ['W2_BASELINE_PATH']}")
    if os.environ.get("W2_ERRORBOUNDARY_PATH"):
        print(f"  W2_ERRORBOUNDARY_PATH={os.environ['W2_ERRORBOUNDARY_PATH']}")
    print("=" * 70)
```

**Verification test:**  
After fix, run:
```bash
W2_SELFTEST=1 W2_BASELINE_PATH=poisoned.json python w2_check_route.py healthy-route.html route
```
Must exit 2, NOT exit 0. A self-test must never green a build.

---

## MEDIUM (Document or mitigate, not a gate-blocker)

### **Poisoned baseline can go undetected if authored during a mass-crash state**
**Files:** `.proof-os/gates/lib/w2_check_route.py` lines 274-338 (checks 1-3 before baseline load)  
**Severity:** MEDIUM — narrow attack window, requires coordinated timing

**The scenario:**  
1. All 26 routes crash (e.g., ErrorBoundary.tsx renamed, breaking derivation)
2. Attacker commits a poisoned `w2_ldjson_baseline.json` (all routes set to `1`)
3. Gate runs, all routes fail checks 1-3, baseline never loads, gate exits 1 (build blocked) ✓
4. Developer fixes the crashes AND the attacker silently un-poisons the baseline in the SAME commit
5. Gate runs on the combined fix, baseline loads (now clean), gate passes, poison never detected

**Is this acceptable?**  
Marginally. The attack requires:
- Mass crash affecting all 26 routes simultaneously
- Poisoned baseline commit during that crash window
- Coordinated un-poisoning in the same commit that fixes the crash
- No review of the baseline file itself

This is a supply-chain attack, not a gate logic flaw. The baseline is version-controlled and diff-visible.

**Mitigation (optional):**  
Add a self-check at gate startup that loads the baseline unconditionally (before any route checks) just to validate its structure, then re-loads it later for actual use. This catches a malformed baseline even if all routes crash. But it's redundant if the baseline is code-reviewed.

**Recommendation:** ACCEPT AS-IS. The baseline is in git, diff-reviewable, and the gate's value validation (lines 235-242) already catches non-integer or <1 entries. This is not a silent bypass.

---

## VERIFIED CORRECT ✓

### 1. **Reordering does not create a silent skip path**
**Question:** Can checks 1-3 running first hide a schema regression?

**Answer:** YES, but it is acceptable and correct.

**What gets skipped:**  
If a route CRASHES (fails checks 1-3), the ld+json baseline check (check 4) never runs. A route that both crashes AND has a schema regression will report only the crash, not the schema loss.

**Example (tested):**  
Fixture `_kavya-new-route-crash-bypass/new-feature-that-crashes/`:
```
FAIL /new-feature-that-crashes:
        - snapshot contains ErrorBoundary fallback heading text
        - <h1> text is the ErrorBoundary fallback
        - snapshot contains both ErrorBoundary fallback controls
EXIT CODE: 1
```
No mention of missing baseline entry, no ld+json count check.

**Why this is correct:**  
1. A crashed route shipping zero content is a **more severe defect** than a crashed route that also lost schema blocks. Prioritizing the crash is diagnostically correct.
2. The skip is **not silent** — the crash is reported in full (three independent signals).
3. Once the crash is fixed, check 4 WILL run on the next build and WILL catch the schema regression then.
4. This was the entire point of the reorder: a crashed unlisted route previously said *"go add a baseline entry"* (exit 2), sending the developer to fix the wrong thing. Now it correctly says *"this route crashed"* (exit 1).

**Verified:** Fixture `_kavya-new-route-crash-bypass` exits 1 with crash reasons ✓  
**Verified:** Fixture `_kavya-new-route-schema-regression` (clean unlisted route) exits 2 asking for baseline entry ✓

---

### 2. **Homepage baseline arithmetic is correct: `/` = 6**
**Question:** Check Priya's derivation.

**Derivation (from baseline comment lines 47-55):**
- Repo-root `index.html` template: **1** static Organization schema block
- `landing.tsx`: **4** `<JsonLd>` call sites (lines 217, 218, 219, 234)
- `FaqSection.tsx`: **1** FAQPage block (line 56, `emitSchema = true` default on line 51)
- **Total:** 1 (template) + 4 (landing) + 1 (FAQ) = **6**

**Verification (tested):**
```bash
# Template contribution verified via /about:
/about baseline = 3
about.tsx JsonLd calls = 2 (grep '<JsonLd' src/pages/about.tsx | wc -l)
Therefore template = 3 - 2 = 1 ✓

# Homepage page-level:
landing.tsx JsonLd calls = 4 (grep '<JsonLd' src/pages/landing.tsx | wc -l)
FaqSection FAQPage = 1 (grep 'emitSchema = true' src/components/site/FaqSection.tsx)
Total page-level = 4 + 1 = 5

# Plus template:
5 + 1 = 6 ✓
```

**CORRECT.** Baseline value at line 58: `"/": 6` matches the derivation method.

**Note:** Priya's comment (line 53) says W3 will confirm against the real prerendered artifact once W1's crash is fixed. If W3 measures a different count, W3's measurement wins. Until then, 6 is correctly derived from source.

---

### 3. **W2_SELFTEST gate works as designed (but see CRITICAL finding above)**
**Question:** Does the `W2_SELFTEST=1` requirement prevent accidental override use?

**Answer:** YES for accidental, NO for intentional (see CRITICAL finding).

**Tested:**
```bash
# Override WITHOUT W2_SELFTEST=1:
$ W2_BASELINE_PATH=poisoned.json python w2_check_route.py about.html about
GATE UNAVAILABLE: W2_BASELINE_PATH is set (to 'poisoned.json') but W2_SELFTEST=1 is not.
Overriding this gate's data/source paths only takes effect under the falsification
harness, which must also set W2_SELFTEST=1 explicitly. Unset W2_BASELINE_PATH for a real run.
(exit 2) ✓

# Override WITH W2_SELFTEST=1:
$ W2_SELFTEST=1 W2_BASELINE_PATH=poisoned.json python w2_check_route.py about.html about
[w2] ld+json baseline loaded from: poisoned.json
(override honored) ✓
```

The accidental-override case (stray env var without flag) correctly exits 2. But the intentional-override case (both env vars set) is the CRITICAL vulnerability above.

---

### 4. **Value validation catches malformed baselines (but not all poisons)**
**Files:** `.proof-os/gates/lib/w2_check_route.py` lines 229-242

**What it catches:**
- Non-integer values (e.g., `"1"` string, `1.5` float, `true` boolean)
- Zero or negative integers (e.g., `0`, `-1`)

**Tested:**
```bash
# Baseline with DEFAULT_MIN: 0
$ W2_SELFTEST=1 W2_BASELINE_PATH=poisoned-baseline.json python w2_check_route.py ...
GATE UNAVAILABLE: ld+json baseline entry 'DEFAULT_MIN' must be an integer >= 1, got 0
(exit 2) ✓
```

**What it does NOT catch:**  
A baseline where every value is a valid positive integer, but TOO LOW for the real route. Example: `{"/about": 1}` when the real healthy count is 3.

**Tested (Priya's validation-passing poison):**
```bash
# _priya-poison-passing-kavyas-validation.json: {"/about": 1}
# Real route has 2 blocks, so 2 >= 1 passes
$ W2_SELFTEST=1 W2_BASELINE_PATH=_priya-poison-passing-kavyas-validation.json \
    python w2_check_route.py _priya-ldjson-regression/about/index.html about
PASS /about - ld+json=2 (baseline 1)
(exit 0) ⚠️ — a regression from 3→2 would be hidden
```

This is why the `W2_SELFTEST=1` flag gate is necessary alongside value validation. Value validation alone is insufficient. Priya's two-layer fix (value validation + flag gate) was correct; the remaining gap is that flag-gated runs can still exit 0.

---

### 5. **No regressions from round-2 verified items**

Retested all seven items from my round-2 review (lines 103-127):

| Item | Status | Evidence |
|------|--------|----------|
| 1. Fixtures reproduce correctly | ✓ PASS | `_kavya-new-route-crash-bypass` exits 1 with 3 crash reasons; `_kavya-new-route-schema-regression` exits 2 asking for baseline |
| 2. Comment-stripping present | ✓ PASS | `w2_check_route.py` line 263 `strip_html_comments()`, called at line 299 |
| 3. Python probe robust | ✓ PASS | Gate script lines 96-109, tests both `python3` and `python` with `--version` |
| 4. Case-insensitive h1 | ✓ PASS | Line 103 `re.IGNORECASE` |
| 5. HTML entity decode | ✓ PASS | Line 316 `html.unescape()` |
| 6. Standards compliance | ✓ PASS | Exit codes 0/1/2, `set -uo pipefail`, header comment, no temp state |
| 7. Portability | ✓ PASS | Runs on Windows Git Bash 5.2.37 (tested), will run on Linux CI |

No regressions. The reordering (checks 1-3 before baseline load) changed only the SEQUENCE, not the operations.

---

## KNOWN ACCEPTABLE LIMITATIONS (unchanged from round 2)

Per gate header comment (W2-prerender-artifact-integrity.sh lines 65-79):

1. **JSON-LD payload validity NOT checked** — gate counts blocks, does not parse or schema.org-validate their contents
2. **`<h1>` copy quality NOT checked** — only existence and non-emptiness
3. **`dist/app-shell.html` NOT checked** — it's the SPA shell, not a prerendered route
4. **Inline styles NOT checked** — that's W3's separate gate (`lib/w3_h1_hidden_text.py`)

These are design decisions, not gaps. ACCEPTABLE.

---

## VERDICT

**REJECT** — route back to Vikram/Priya for fix of the CRITICAL W2_SELFTEST bypass.

The gate's mechanics are sound. Both prior CRITICALs are fixed. The reordering is correct. Homepage arithmetic is correct. However, **a self-test run can still exit 0**, which enables a poisoned-baseline attack if `W2_SELFTEST=1` is ever set in the build environment.

**Required fix:**  
Add the Option A check from the CRITICAL finding above: if `W2_SELFTEST=1` is active, convert exit 0 to exit 2 with a message. A self-test is a test harness, not a production gate, and must never green a build.

**Verification test after fix:**
```bash
W2_SELFTEST=1 W2_BASELINE_PATH=any-baseline.json python w2_check_route.py healthy-route.html route
# Must exit 2, NOT exit 0
```

**Once fixed:**  
Re-submit to me (Kavya) for round-4 review of the self-test exit-0 block only. If that passes, route to Meera for live-build verification (`npm run build` → gate against real `dist/`).

---

## ANSWER TO PRIYA'S DECLARED LIMITATIONS QUESTION

> If you PASS it, say explicitly whether you consider the gate's remaining declared limitations acceptable.

**Not passing yet (see CRITICAL above), but for the record:**

The four declared limitations (JSON-LD payload validity, h1 copy quality, app-shell.html, inline styles) are **ACCEPTABLE**. They are:
1. Clearly documented in the gate header (lines 65-79)
2. Narrow and defensible (payload parsing is W3's job, not W2's)
3. Not silent (the header says what's out of scope)
4. Not security-relevant (schema payload errors don't ship malware, just suboptimal SEO)

The limitation that was NOT acceptable was the one I flagged as CRITICAL in round 2 (hardcoded error string coupling) — Vikram fixed that via runtime derivation. The remaining limitations are design scope, not defects.

---

## FILES REVIEWED (Round 3)

- `.proof-os/gates/W2-prerender-artifact-integrity.sh` (190 lines) — reread after reordering
- `.proof-os/gates/lib/w2_check_route.py` (377 lines) — line-by-line review of reordered main()
- `.proof-os/gates/lib/w2_ldjson_baseline.json` (87 lines) — verified homepage baseline = 6
- `src/pages/landing.tsx` (grep for JsonLd calls) — verified 4 call sites
- `src/components/site/FaqSection.tsx` (grep for emitSchema) — verified default true, 1 block
- `src/pages/about.tsx` (grep for JsonLd calls) — verified 2 call sites (baseline 3 = 1+2)
- `.proof-os/gates/fixtures/w2/_kavya-new-route-crash-bypass/` — verified exit 1 on crashed unlisted route
- `.proof-os/gates/fixtures/w2/_kavya-new-route-schema-regression/` — verified exit 2 on clean unlisted route
- `.proof-os/gates/fixtures/w2/_priya-poison-passing-kavyas-validation.json` — tested validation bypass
- `.proof-os/gates/fixtures/w2/_priya-ldjson-regression/about/index.html` — tested against poison
- `wiki/errors/W2-prerender-artifact-integrity-review.md` (round 2) — verified both CRITICALs fixed

---

## QA CHECKLIST RESULTS (Round 3)

```
TypeScript/Code Standards: N/A (bash + Python, not TS)
Security Checks:
  ✓ Round-2 CRITICAL #1 fixed (DEFAULT_MIN deleted, lookup miss → exit 2)
  ✓ Round-2 CRITICAL #2 fixed (W2_SELFTEST=1 required for overrides)
  ✗ NEW CRITICAL: W2_SELFTEST=1 allows exit 0 in self-test mode (fix required)
  ✓ Value validation present (lines 235-242)
  ✓ No hardcoded credentials
  ✓ No external writes
Performance: N/A (gate, not runtime code)
Accessibility: N/A (gate, not UI)
Architecture:
  ✓ Reordering correct (checks 1-3 before baseline)
  ✓ Exit codes correct (0/1/2 with clear meanings)
  ✓ Header comment updated
  ✓ No temp state
  ✗ Self-test bypass allows production PASS (CRITICAL)
```

**Blocked by:** W2_SELFTEST exit-0 bypass  
**Can proceed after:** Self-test mode converts exit 0 → exit 2  
**Evidence required:** Test showing `W2_SELFTEST=1` forces exit 2, never exit 0

---

**Next Steps:**

1. **Vikram/Priya:** Add the Option A self-test check (lines 266-273 warning, lines 371-374 exit-0 block). Document in module docstring.
2. **Vikram/Priya:** Optionally add Option B visible-warning banner if any override is in use (defense in depth).
3. **Vikram/Priya:** Verify test: `W2_SELFTEST=1 W2_BASELINE_PATH=any.json python w2_check_route.py healthy.html route` must exit 2.
4. **Vikram/Priya:** Re-submit to Kavya via `SHARED_CONTEXT.md`.
5. **Kavya (round 4):** If self-test exit-0 block is solid, **PASS** and route to Meera.
6. **Meera:** Run gate against real `dist/` after `npm run build`, confirm behavior on live artifact.
