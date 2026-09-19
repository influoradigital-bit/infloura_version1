# R7 last call: HideDisclosureRule alternatives, compliance rows, "an ad" (kabir, 2026-09-19)

**done_when (my half), verbatim:** "Every HideDisclosureRule alternative has a corpus row that goes red when that alternative alone is removed; Nisha's blind compliance rows in NISHA-COMPLIANCE-ROWS-0918.md, loaded verbatim, and the round-7 compliance lines raise zero flags; and 'Don't disclose this as an ad.' raises HIDE_DISCLOSURE."

## Verdict: MET on all three clauses. SIGN-OFF (non-blocking notes below)

| Clause | Result | Evidence |
|---|---|---|
| 1. Every alternative has a row that goes red when that alternative alone is removed | **MET** | 38 of 38 alternation alternatives deleted one at a time: 38 red. Also 5 non-alternative R7-A parts: 5 red. 43 of 43 runs red, 0 stayed green. |
| 2. Nisha's blind compliance rows (loaded verbatim) and the round-7 compliance lines raise zero flags | **MET** | Baseline: `hideDisclosureHasNoFalsePositives` is green, and both fidelity tests ran (Skipped: 0). My own byte check: 14 of 14 TSV rows match the markdown. C1-C12 match RULINGS round 7 byte for byte. Falsified both ways; see below. |
| 3. "Don't disclose this as an ad." raises HIDE_DISCLOSURE | **MET** | `VIK-GUARD3-HD-F-an-ad` is in the ratchet and green. It goes red when `an?` is changed back to `a`, and red when B3's `ad` is deleted. On the built pattern, the curly-apostrophe and all-caps forms also flag. `apply()` (L157-175) returns `CODE` whenever `inText` is true. |

Artifact hashes, the same at the start and end of this check. The real worktree was only read. Its `target/` still has its 14:50 mtime.
- `HideDisclosureRule.java`: `5bc1394ea061…`
- `RiskFlagCorpusTest.java`: `ab38ff1d534a…`
- `nisha-compliance-r7.tsv`: `98e8a191b9ee…`
- `NISHA-COMPLIANCE-ROWS-0918.md`: `4f6393e7c494…`

## What I read
- `HideDisclosureRule.java` in full. `RiskFlagCorpusTest.java` in full. `RiskText.norm` and `RiskText.matches`. `gen_nisha_compliance_tsv.py`. The compliance TSV, byte by byte.
- Ledger F-1776 and F-1778.
- RULINGS-U-0917.md round 7 in full (L853-1165).
- NISHA-COMPLIANCE-ROWS-0918.md in full.
- Nisha's blind HD-F rows in `nisha-blind-0917.tsv`.

## How I ran it
- **Scratch copy.** `…\scratchpad\kabir-r7-hide\influora-api` holds pom.xml, src and .mvn, without `target`. Both Nisha markdowns were copied to `…\kabir-r7-hide\.proof-os\tasks\T-MEERA-CREATOR-PHASE-B\`, keeping the relative path the fidelity tests read. The rule's sha in the copy was `5bc1394ea061`.
- **Baseline.** `mvn -o -f <copy>/pom.xml clean -Dtest=RiskFlagCorpusTest test` gave Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS. Log: `run-00-baseline.log`.
- **Deviation, for time.** One clean run took 112 s, and there were 43 mutants. I ran them in 4 identical lanes: the main copy plus `lane2`, `lane3` and `lane4`, all byte-identical copies in the same scratch folder. Each lane runs its mutants one after another. Every mutant still:
  - restores the file from `bak\HideDisclosureRule.java.orig` and sha-checks it;
  - applies exactly one change;
  - runs `mvn -o clean -Dtest=RiskFlagCorpusTest test`.
- **Harness.** `…\kabir-r7-hide\mut.py`:
  - works in binary mode;
  - asserts that the old substring occurs exactly once;
  - asserts the backup's sha before restoring.
- **Checks on every run.** Each log shows:
  - the MUTATED line and its sha;
  - "Compiling 870 source files … to target\classes", so the mutant was really compiled;
  - Tests run: 19.
- **After the runs.** All 4 lanes were restored to `5bc1394ea061`. A restored run in lane4 was green (19/0/0/0, BUILD SUCCESS).
- **Where things are.** Logs: `…\kabir-r7-hide\logs\run-<id>.log`. Summary: `logs\summary.txt`. Extractor: `extract.py`.

## Clause 1: each deletion and its red line

"ratchet" means `hideDisclosureStillCatches` failed with the message "Expecting empty but was: […]". "zero-FP" means `hideDisclosureHasNoFalsePositives` failed.

**B1: negators, verbs and labels**

| Deleted | Red |
|---|---|
| `do not` | ratchet `["KAB-HD-F-02","RND-HD-F-nbsp"]`; `nonBreakingSpaceBypassFixed` |
| `don'?t` | ratchet `["KAB-HD-F-01","KAB-HD-F-03","VIK-GUARD2-HD-F-put"]`; `nonBreakingSpaceBypassFixed` |
| `(?<!with\s)no` | ratchet `["HD-F-02","HD-F-10","KAB-HD-F-04","RND-HD-F-zwsp","VIK-GUARD2-HD-F-collab","VIK-GUARD2-HD-F-adtag","VIK-GUARD2-HD-F-sponsoredtag"]`; `zeroWidthSpaceBypassFixed` |
| `use` | ratchet `["KAB-HD-F-01"]` |
| `add` | ratchet `["KAB-HD-F-02"]` |
| `put` | ratchet `["VIK-GUARD2-HD-F-put"]` |
| `#ad` | ratchet `["HD-F-02","KAB-HD-F-01","KAB-HD-F-03","KAB-HD-F-04","RND-HD-F-nbsp","VIK-GUARD2-HD-F-put"]`; NBSP and ZWSP tests |
| `#collab` | ratchet `["VIK-GUARD2-HD-F-collab"]` |
| `#sponsored` | ratchet `["RND-HD-F-zwsp"]`; `zeroWidthSpaceBypassFixed` |
| `ad\s*tag` | ratchet `["VIK-GUARD2-HD-F-adtag"]` |
| `ad\s*label` | ratchet `["HD-F-10"]` (Nisha blind) |
| `paid\s+partnership` | ratchet `["KAB-HD-F-02"]` |
| `sponsored\s+tag` | ratchet `["VIK-GUARD2-HD-F-sponsoredtag"]` |

**B1: placement exclusions (R7-A part 3)**

| Deleted | Red |
|---|---|
| `at\s+the\s+end` | zero-FP `["C6"]` |
| `in\s+the\s+comments?` | zero-FP `["C8"]` |
| `only` | zero-FP `["C7"]` |
| `in\s+place` | zero-FP `["C9"]` |

**B2 and B3**

| Deleted | Red |
|---|---|
| B2 `it'?s` | ratchet `["KAB5-HD-F-dont-mention"]` |
| B2 `this\s+is` | ratchet `["VIK-GUARD2-HD-F-thisis"]` |
| B3 `this` | ratchet `["VIK-GUARD3-HD-F-an-ad","TRIGGER_TEXT"]`; `triggerTextFiresBothFlags` |
| B3 `it` | ratchet `["VIK-GUARD2-HD-F-it"]` |
| B3 `paid\s+partnership` | ratchet `["VIK-GUARD2-HD-F-it","TRIGGER_TEXT"]`; `triggerTextFiresBothFlags` |
| B3 `ad` | ratchet `["VIK-GUARD3-HD-F-an-ad"]` |

**B4: Hinglish short form**

| Deleted | Red |
|---|---|
| subject `ad` | ratchet `["KAB5-HD-F-hinglish-01","KAB5-HD-F-hinglish-02","VIK-GUARD-HD-F-nahi","VIK-GUARD-HD-F-laga"]` |
| subject `sponsored` | ratchet `["VIK-GUARD-HD-F-sponsored-hinglish"]` |
| subject `paid\s+partnership` | ratchet `["KAB5-HD-F-hinglish-03","VIK-GUARD-HD-F-mention"]` |
| negator `mat` | ratchet, 6 rows: `hinglish-01/02/03`, `VIK-GUARD-HD-F-sponsored-hinglish/laga/mention` |
| negator `nahi` | ratchet `["VIK-GUARD-HD-F-nahi"]` |
| verb `likh` | ratchet `["KAB5-HD-F-hinglish-01","VIK-GUARD-HD-F-sponsored-hinglish","VIK-GUARD-HD-F-nahi"]` |
| verb `daal` | ratchet `["KAB5-HD-F-hinglish-02"]` |
| verb `laga` | ratchet `["VIK-GUARD-HD-F-laga"]` |
| verb `dikha` | ratchet `["KAB5-HD-F-hinglish-03"]` |
| verb `mention` | ratchet `["VIK-GUARD-HD-F-mention"]` |

**B5: Devanagari short form**

| Deleted | Red |
|---|---|
| subject `#ad` | ratchet `["KAB5-HD-F-devanagari-02","VIK-GUARD-HD-F-nahi-devanagari"]` |
| subject `विज्ञापन` | ratchet `["KAB5-HD-F-devanagari-01"]` |
| subject `sponsored` | ratchet `["VIK-GUARD-HD-F-sponsored-devanagari"]` |
| negator `मत` | ratchet `["KAB5-HD-F-devanagari-01","KAB5-HD-F-devanagari-02","VIK-GUARD-HD-F-sponsored-devanagari"]` |
| negator `नहीं` | ratchet `["VIK-GUARD-HD-F-nahi-devanagari"]` |

**The other R7-A parts, each changed on its own**

| Change | Red |
|---|---|
| lookbehind `(?<!with\s)` removed | zero-FP `["C5"]` |
| `without` restored as a B1 negator | zero-FP `["NC-01","NC-05","C1","C2","C3","C4"]` |
| `an?` changed back to `a` | ratchet `["VIK-GUARD3-HD-F-an-ad"]` |
| B4 `toh` lookahead removed | zero-FP `["C10","C12"]` |
| B5 `तो` lookahead removed | zero-FP `["C11"]` |

**Why the top-level branches need no separate run.** Deleting a whole branch (B1…B5) is covered by the rows above. Each branch has an inner alternative whose own deletion un-catches a row, and nothing else in the pattern matches that row. So deleting the whole branch cannot let the row match either.

**Every row in Priya's R7-A falsify map reproduces on the built rule.** That map is round 7 L989-1000.

## Clause 2: loaded verbatim, and zero flags

**Loaded verbatim.**
- `nishaComplianceResourceMatchesMarkdownVerbatim` runs; it is not skipped, because the markdown is present at `../.proof-os/...`.
- My own check (`fidelity_check.py`) does not split on `|`. It looks for each TSV text as a byte substring `| "<text>" |` on the markdown line with the same id. Result: 14 of 14 texts match, and so do the languages and the expected values. The markdown has exactly 14 NC ids and no duplicates.
- NC-04 keeps Nisha's *decomposed* ज़ (U+091C U+093C) byte for byte, so no silent normalisation happened.

**The fidelity test is not vacuous.** In lane5's copy of the markdown only, I changed that one code point to the precomposed U+095B. The fidelity test went red, and named only NC-04 ("elements not found … NC-04"). Log: `logs\lane5-probe-and-md-falsify.log`.

**The rows are inside the zero-FP assertion, not just loaded.** Restoring `without` makes zero-FP red on `NC-01` and `NC-05`. This is the table row above.

**The round-7 compliance lines.**
- C1-C12 in the test are byte-identical to the R7-A table in RULINGS round 7 (`c_rows_check.py`: 12 of 12 SAME).
- All 12 are NO_FLAG, and they are green at baseline.
- "No #ad, no approval." still flags. It is round 7's recorded residual, left out of C1-C12 by the ruling itself. I did not re-measure it.

## Evasion probe: appending a new exclusion to a real hide ask

This is the built `HIDE_TEXT` (lane5, `KabirR7EvasionProbeTest`, scratch only). It goes through the same `RiskText.matches` the rule calls. **Miss** means the brand escapes.

| # | Text | Result |
|---|---|---|
| E1 | "Don't put #ad at the end or anywhere else, we want it to look organic." | **MISS** |
| E2 | "Please don't add the paid partnership label at the end either, keep it clean." | HIT: "label" sits between the label token and the exclusion |
| E3 | "Don't use #ad in the comments or the caption, client wants it to feel organic." | **MISS** |
| E4 | "No #ad in the comment section or the caption please." | **MISS**: `comments?` also takes the singular |
| E5 | "No #ad only organic vibes on this one" | **MISS** |
| E6 | "No paid partnership only good vibes, keep it natural." | **MISS** |
| E7 | "Don't put the paid partnership label in place for this one, keep it organic." | HIT: same reason as E2 |
| E8 | "No ad label in place for this one please, it should read like your own pick." | **MISS** |
| E9 | "Don't put #ad at the end, in the middle or at the start, no disclosure at all." | **MISS** |
| H1 | "Ad nahi likhna toh better hai, post organic lagegi." | **MISS**: B4's `toh` lookahead |
| H2 | "#ad नहीं डालना तो अच्छा रहेगा, पोस्ट ऑर्गेनिक लगेगी।" | **MISS**: B5's `तो` lookahead |
| W1 | "Let's go with no paid partnership label on this one." | **MISS**: `with` lookbehind (a recorded residual) |

**Result: 7 of 9 English placement evasions escape (E1, E3, E4, E5, E6, E8, E9).** H1, H2 and W1 escape through the other R7-A lookarounds.

**Controls.** All of these HIT:
- "No #ad, only organic vibes…". The comma is enough to stop the exclusion.
- A second occurrence without an exclusion.
- "anywhere".
- "paid partnership tag at the end".
- Two sentences, where the second is plain.
- "Ad mat likhna toh…" and "#ad मत डालना तो…".
- "Don't disclose this as an ad." in plain, curly-apostrophe and all-caps forms.

C6 and C1 stay silent.

**What this confirms.** Constraint A holds: an exclusion switches off only the one label occurrence it follows (K2, K5). So a brand escapes only by writing the exclusion words into its own ask, which is the class round 6 and round 7 conceded.

## Notes (non-blocking, not part of the done_when)

1. **Residuals that are not recorded.** The javadoc and SPEC record "a hide ask rephrased as a placement instruction escapes". E5 and E6 are not placement instructions: a bare `only` after the label switches the flag off. H1 ("nahi … toh") and H2 ("नहीं … तो") are commands that escape through the conditional exclusion. The javadoc lists only their `mat`/`मत` twins, which still flag. Record all four in the javadoc residuals.
2. **Candidates for K-2d. I have not measured these against the corpus.**
   - Replace `only` with `only\s+in\b`. C7 is "only in the comments".
   - Narrow B4's lookahead to past forms, `(?!\s+\w+(?<!na)\s+toh?\b)`: "likha toh" is excluded, "likhna toh" flags.
   - Mirror that in B5 with `(?<!ना)`.
   - Each would need its own guard row and a falsify run.
3. **Untracked files.** In `influora-b0`, `git status` shows `RiskFlagCorpusTest.java`, `src/test/resources/risk-corpus/` and `NISHA-COMPLIANCE-ROWS-0918.md` as **untracked** (`??`). If they are not `git add`ed, the commit ships the rule without its whole guard corpus, and every local gate stays green. This belongs on arjun's pre-commit checklist.
4. **NC-01 duplicates C1.** NC-01 is byte-identical to Priya's C1 ("Please do not post without the paid partnership label."). Priya calls that line agency boilerplate, so a blind writer could plausibly produce it. But it adds no independent evidence for that shape. The other 13 NC rows are distinct.

## SIGN-OFF

SIGN-OFF, kabir, 2026-09-19: my half of the done_when (clauses 1-3) is MET on the built rule at `5bc1394ea061`. Notes 1-4 are follow-ups and do not block it.
