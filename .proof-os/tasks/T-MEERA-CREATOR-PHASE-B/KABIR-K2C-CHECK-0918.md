# K-2c check: F-1769 and F-1773 (kabir, 2026-09-18)

**done_when, verbatim:** "F-1769 and F-1773 are closed by tests that go red when their fix is removed; a wallet name and a request word pair only within one sentence as RULINGS-U-0917.md round 6 defines it, with 'Rs. 5,000'-style dots not ending a sentence; on the fallback path neither risk hint is set by the extractor; the pairing window is pinned at exactly 6 by literal distances; every HideDisclosureRule alternative has a row that goes red when that alternative alone is deleted; and a red run of a looped corpus assertion names every failing row."

## Verdict: NOT MET (one clause of six)

| # | Clause | Verdict | Evidence in one line |
|---|---|---|---|
| 1 | F-1769 and F-1773 closed by tests that go red with the fix removed | **MET** | Sentence cut removed: red on XS-1..XS-6 plus 4 NL rows. Either old FALLBACK pattern restored: red in 3 tests. The whole pre-fix extractor from HEAD: red in 6. |
| 2 | Pairing only within one sentence as round 6 defines it; "Rs. 5,000" dots do not end a sentence | **MET** (behaviour) | The probe ran 69 cases on the built classes, clause by clause, with 0 differences from Ruling 1. All 7 round-6 pass-bar mutations go red. **Gap:** 6 parts of the definition can each be deleted while the suite stays green (see §2). |
| 3 | On FALLBACK, the extractor sets neither risk hint | **MET** | Both hints are the literal `false` (L175-176), and no other writer exists in `src/main`. Restoring either old pattern goes red. |
| 4 | Window pinned at exactly 6 by literal distances | **MET** | `PAIRING_WINDOW` = 5, = 7 and `<=` changed to `<` each turn `pairingWindowIsExactlySix` red. The test's gaps are fixed filler words and never read the constant. |
| 5 | Every HideDisclosureRule alternative has a row that goes red when that alternative alone is deleted | **NOT MET** | **8 of 36 alternatives survive deletion with the suite green (18/0/0/0):** `without`, `put`, `#collab`, `ad\s*tag`, `sponsored\s+tag`, B2 `this\s+is`, B3 `it`, B3 `ad`. The other 28, all 5 whole branches and 4 optional groups go red. |
| 6 | A red run of a looped corpus assertion names every failing row | **MET** | Every loop test collects into a list and asserts `isEmpty()` once. Examples: two alternatives deleted together named both guard rows; the bare-wallet mutation named all 9 names; an exclusion named all 96 failing row-and-wrap pairs. |

**Ledger bookkeeping is not done.** In `.proof-os/ledger/failures.jsonl`, F-1769 (L768) and F-1773 (L772) still read `"status": "open"` with `"fix": ""`.

**Numbering:** round 6 names the FALLBACK record "F-1772", but the ledger's F-1772 is meera's exemption-list record. The FALLBACK defect is ledgered as **F-1773**, and the code comments cite "F-1773 / … 'New: F-1772'", so the reference is consistent. Whoever closes it should use F-1773.

## What I read
- **Ledger:** F-1769 and F-1773 in full, plus F-1766 and F-1772 for context.
- **RULINGS-U-0917.md:** rounds 4, 5 and 6 in full (L416-849).
- **The six artifact files, in full:**
  - `OffPlatformPaymentRule.java`
  - `HideDisclosureRule.java`
  - `BriefFallbackExtractor.java`
  - `RiskFlagCorpusTest.java`
  - `BriefFallbackExtractorTest.java`
  - `BriefFallbackExtractorRealRiskRulesTest.java`
- **Supporting code:** `git diff HEAD` of the three main files, plus `RiskText.java`.
- **Other sources:**
  - `CreatorBriefService.analyse` (L433-475)
  - every `offPlatformPaymentHint` / `disclosureHiddenHint` reader in `src/main` (only the two rules read them, and only `BriefFallbackExtractor` and the AI DTO produce them)
  - all 28 `HIDE_DISCLOSURE` rows of `nisha-blind-0917.tsv`
  - `NISHA-HIDE-WORD-ROWS-0918.md`
  - SPEC.md's `AMEND-0918` and `AMEND-0918-FALLBACK` markers (L923, L945, L965, L975); present, not reviewed in depth
  - the gate `.proof-os/gates/F-1765-F-1766-risk-corpus.sh`

## How I ran it
- **Every Maven run** was `mvn -o -f "C:/Users/Sage world/Downloads/New Influora Ai/influora-b0/influora-api/pom.xml" clean -Dtest=<classes> -Dsurefire.failIfNoSpecifiedTests=true test`, with stdout sent to a log file and the exit code taken from Maven itself, not through a pipe.
- **No stale bytecode.** All 73 mutation logs show `Compiling 870 source files` and `Compiling 346 source files`.
- **Harness:** `scratchpad/k2c/mutate.py`, working in binary mode.
  - Each mutation's anchor text was asserted to occur exactly once.
  - Every changed line got `// FALSIFY-TEMP`.
  - After each run, the file was restored from its in-memory original, and the sha256 was checked against the baseline before the next mutation.
  - The harness refused to start unless all files were at baseline.
- **One interruption:** the driver stopped once, on a cp1252 console error while printing a Devanagari mutation id. That happened after the record was written and the file restored. I checked all 7 hashes by hand and resumed with UTF-8 output. No mutation was lost or repeated.
- **Probe:** `scratchpad/k2c/probe/com/influora/service/risk/rules/KabirK2cProbe.java`.
  - It lives in the rules' own package, so the package-private `matches`, `sentences` and `HIDE_TEXT` it calls are the real ones.
  - It was compiled against a copy of `target/classes` taken straight after the green baseline, before any mutation. The JDK was 21.0.9, the build JDK.
- **Where the evidence is.** All paths are under `C:\Users\SAGEWO~1\AppData\Local\Temp\claude\C--Users-Sage-world-Downloads-New-Influora-Ai-New-Influora\ffe4a6e0-c83c-4765-8429-47a384be42aa\scratchpad\k2c\`:
  - per-run logs: `logs\<id>.log`
  - every result with its full failure messages: `results.jsonl`
  - the readable digest: `summary.txt`
  - the probe's output: `probe.out`

**Baseline** (three target classes): `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- RiskFlagCorpusTest: 18.
- BriefFallbackExtractorTest: 15.
- BriefFallbackExtractorRealRiskRulesTest: 5.
- `Skipped: 0` means the fidelity test ran against Nisha's markdown.

## Clause 1: F-1769 and F-1773 go red with their fix removed

| Mutation | Result (quoted from surefire) |
|---|---|
| **F-1769 fix removed:** `for (String sentence : sentences(norm))` → `List.of(norm)` (L243) | **RED**, 23 run / 2 failed. `offPlatformPaymentHasNoFalsePositives`: `Expecting empty but was: ["XS-1", "XS-2", "XS-3", "XS-4", "XS-5", "XS-6", "NL-bullets", "NL-bullets-num", "NL-blank", "NL-crlf-bullets"]`. `sentenceBoundaryStopsPairingAcrossSentences` names XS-1..XS-6 and NL-bullets. XS-3 and XS-5 are the ledger's two example texts. |
| **F-1773, OFF hint restored:** HEAD's `OFF_PLATFORM` regex put back as `off_platform_payment_hint` (L175) | **RED**, 20 run / 3 failed. `onPlatformPayoutInstructionDoesNotFlag`: `Expecting ["OFF_PLATFORM_PAYMENT", "VAGUE_DELIVERABLES"] not to contain ["OFF_PLATFORM_PAYMENT"]`. `realOffPlatformAskStillFlagsFromText`: `["basis"="STATED" (expected: "BRIEF_TEXT")]`. `offPlatformPaymentHintIsAlwaysFalse`: `expected: <false> but was: <true>`. |
| **F-1773, HIDE hint restored:** HEAD's `DISCLOSURE_HIDDEN` regex put back (L176) | **RED**, 20 run / 3 failed. `ordinaryAdCaptionDoesNotFlag`: `Expecting ["HIDE_DISCLOSURE", "VAGUE_DELIVERABLES"] not to contain ["HIDE_DISCLOSURE"]`. `realHideDisclosureAskStillFlagsFromText`: `["basis"="STATED" (expected: "BRIEF_TEXT")]`. `disclosureHiddenHintIsAlwaysFalse`: `expected: <false> but was: <true>`. |
| **F-1773, whole file:** `git show HEAD:…/BriefFallbackExtractor.java`, the complete pre-fix extractor | **RED**, 20 run / 6 failed. All six hint and FALLBACK tests above. |
| Same, with the corpus in the run | 38 run / 6 failed. The 18 corpus tests stay green, as expected: the corpus never runs the FALLBACK path. That is exactly why F-1773 needed its own end-to-end test. |

## Clause 2: the sentence rule matches Ruling 1

**Behaviour: 0 differences in 69 probe cases** against the built `OffPlatformPaymentRule.matches`. Full output is in `probe.out`.
- **Cuts** at `.`, `!`, `?`, `…`, `...`, `!?`, `।` and `॥` followed by a space, a tab or a NBSP (norm maps it to a space). Also at blank lines (LF, CRLF, and blank lines with spaces or a tab) and at U+2029.
- **List items cut** after `-`, `*`, `•`, `·`, `▪`, `➤`, `1.`, `12)`, `3)` and Devanagari `१.`, indented or not.
- **Not cut**, as the ruling says:
  - a single line break
  - a list marker with no space after it
  - `123)`, which has three digits
  - a dot glued to the next word (the recorded residual)
  - a dot followed by a closing quote
- **Protected dots.** A single `.` does not cut when the next character on the same line is:
  - an ASCII digit or a Devanagari digit (`५०००`)
  - `₹` or `$`
  - a digit after extra spaces

  It still cuts before a digit on the next line, after `..`, and after `!`.
- **Abbreviations.** All 20 on the list protect (`rs re inr amt approx appx no nos a/c acc acct e.g i.e vs mr mrs ms dr pvt रु`), including uppercase `RS.` and after `(` or `“`. `etc.`, `ltd.`, `co.` and `foo.` cut.
- **Minor extra.** `isProtectedDot` also strips a leading `~` from the word before the dot, which the ruling does not list. It can only keep a pairing, never remove one, so Constraint A is unaffected.

**Round 6 pass-bar mutations, each red:**

| Mutation | Result |
|---|---|
| Digit and currency protection removed (`if (false)`) | RED. `offPlatformPaymentStillCatches: Expecting empty but was: ["AB-rupee-sym"]`, plus the boundary test and 4 suppression wraps. (AB-rs and AB-inr stay caught through the abbreviation list, so the ruling's expected ids are only partly right. The mutation still goes red.) |
| Abbreviation list emptied | RED. `Expecting empty but was: ["AB-ac-no", "AB-amt", "AB-eg"]` in the ratchet and the boundary test, plus 12 suppression wraps. |
| Every single line break made a cut | RED. `["NL-hardwrap-ask", "NL-hardwrap-ask-2"]` |
| List-item cut removed | RED. No-false-positive test: `["NL-bullets", "NL-crlf-bullets"]` |
| Blank-line cut removed (extra) | RED. `["NL-blank"]` |
| `।` removed from the terminator set | RED. `["XS-5"]` |
| `.` removed from the terminator set (extra) | RED. `["XS-1", "XS-2", "XS-3", "XS-4", "XS-6"]` |
| Influora exclusion added, before the route phrases | RED. The suppression test names 96 row-and-wrap pairs (24 rows × 4 wraps); the ratchet names `["OPP-F-04"]`. |
| Payout exclusion added | RED. The suppression test names 72 pairs. |
| Influora exclusion on the pairing only (spares `ROUTE_PHRASES`) (extra) | RED. The suppression test names 80 pairs. |

**Gap: 6 parts of the definition are not guarded by any test.** Each of these was deleted on its own and the suite stayed at `Tests run: 23, Failures: 0`:
- `?` removed from the terminator set
- `!` removed
- `…` (U+2026) removed
- `॥` removed
- U+2029 removed from the blank-line pattern
- currency-symbol protection removed, keeping digit protection

The built code implements all six correctly (probe above). But a later edit could drop any of them and nothing would go red. XR-q is the only row with a `?` between the two words, and it is report-only.

**Fix (LOW, vikram):** add one `NO_FLAG` row each for `?`, `!`, `…` and `॥` in the XS shape, one for U+2029, and one `SHOULD_FLAG` ratchet row with a single dot before `₹` on a non-list word, e.g. "We'll send it tonight. ₹5000 to your UPI". I did not add them. This check is read-only on the artifact.

## Clause 3: FALLBACK sets neither hint
- `BriefFallbackExtractor.extract` passes the literal `false, // off_platform_payment_hint` and `false, // disclosure_hidden_hint` (L175-176).
- The `OFF_PLATFORM` and `DISCLOSURE_HIDDEN` patterns are deleted, not left unused. The two summary lines that read the hints are gone (L229-231).
- `grep` over `src/main` finds no other producer of either hint. `CreatorBriefService.analyse` L446-448 uses this extractor's output unchanged on FALLBACK and passes the same raw text to `evaluateExtraction`, so the rules' text checks still run.
- The clause-1 mutations prove the tests guard it.

## Clause 4: window pinned at exactly 6

| Mutation | Result |
|---|---|
| `PAIRING_WINDOW = 5` | RED. `pairingWindowIsExactlySix: Expecting empty but was: ["wallet..request at distance 6 must flag", "request..wallet at distance 6 must flag", "two-word wallet name at distance 6 must flag"]`. Also the ratchet: `["AB-inr", "AB-eg", "NL-hardwrap-ask"]`. |
| `PAIRING_WINDOW = 7` | RED. `pairingWindowIsExactlySix: Expecting empty but was: ["wallet..request at distance 7 must not flag", "request..wallet at distance 7 must not flag", "two-word wallet name at distance 7 must not flag"]`. This is the only failing test, so the pin test alone holds the upper bound. |
| `<=` changed to `<` in `pairsWithinSentence` (extra) | RED, same three distance-6 lines as window 5. |

The distances are built from 5 and 6 fixed filler words (`alpha`…`foxtrot`). They are never derived from `PAIRING_WINDOW`, so the test pins the value rather than echoing it. My KB5 note 2, "pinned only to [3, 12]", is closed.

## Clause 5: HideDisclosureRule alternatives, one deleted at a time

Every run was `-Dtest=RiskFlagCorpusTest`; "green" means `Tests run: 18, Failures: 0`. Branches: B1 is the label-omission branch, B2 "don't mention it's sponsored", B3 "don't disclose this as a …", B4 Hinglish, B5 Devanagari.

| Alternative deleted | Result (the row(s) named) |
|---|---|
| B1 negator `do not` | RED: `["KAB-HD-F-02", "RND-HD-F-nbsp"]` |
| B1 negator `don'?t` | RED: `["KAB-HD-F-01", "KAB-HD-F-03"]` |
| B1 negator `no` | RED: `["HD-F-02", "HD-F-10", "KAB-HD-F-04", "RND-HD-F-zwsp"]` |
| **B1 negator `without`** | **green** |
| B1 verb `use` | RED: `["KAB-HD-F-01"]` |
| B1 verb `add` | RED: `["KAB-HD-F-02"]` |
| **B1 verb `put`** | **green** |
| B1 label `#ad` | RED: `["HD-F-02", "KAB-HD-F-01", "KAB-HD-F-03", "KAB-HD-F-04", "RND-HD-F-nbsp"]` |
| **B1 label `#collab`** | **green** |
| B1 label `#sponsored` | RED: `["RND-HD-F-zwsp"]` |
| **B1 label `ad\s*tag`** | **green** (HD-F-02 "No #ad tag" matches through `#ad` first) |
| B1 label `ad\s*label` | RED: `["HD-F-10"]` |
| B1 label `paid\s+partnership` | RED: `["KAB-HD-F-02"]` |
| **B1 label `sponsored\s+tag`** | **green** (RND-HD-F-zwsp matches through `#sponsored`) |
| B2 `it'?s` | RED: `["KAB5-HD-F-dont-mention"]` |
| **B2 `this\s+is`** | **green** |
| B3 `this` | RED: `["TRIGGER_TEXT"]` |
| **B3 `it`** | **green** |
| B3 `paid\s+partnership` | RED: `["TRIGGER_TEXT"]` |
| **B3 `ad`** | **green** |
| B4 subj `ad` / `sponsored` / `paid\s+partnership` | RED each: `["KAB5-HD-F-hinglish-01", "KAB5-HD-F-hinglish-02", "VIK-GUARD-HD-F-nahi", "VIK-GUARD-HD-F-na", "VIK-GUARD-HD-F-laga"]` / `["VIK-GUARD-HD-F-sponsored-hinglish"]` / `["KAB5-HD-F-hinglish-03", "VIK-GUARD-HD-F-mention"]` |
| B4 neg `mat` / `nahi` / `na` | RED each: 6 rows / `["VIK-GUARD-HD-F-nahi"]` / `["VIK-GUARD-HD-F-na"]` |
| B4 verb `likh` / `daal` / `laga` / `dikha` / `mention` | RED each: 4 rows / `["KAB5-HD-F-hinglish-02"]` / `["VIK-GUARD-HD-F-laga"]` / `["KAB5-HD-F-hinglish-03"]` / `["VIK-GUARD-HD-F-mention"]` |
| B5 subj `#ad` / `विज्ञापन` / `sponsored` | RED each: `["KAB5-HD-F-devanagari-02", "VIK-GUARD-HD-F-nahi-devanagari"]` / `["KAB5-HD-F-devanagari-01"]` / `["VIK-GUARD-HD-F-sponsored-devanagari"]` |
| B5 neg `मत` / `नहीं` | RED each: 3 rows / `["VIK-GUARD-HD-F-nahi-devanagari"]` |
| Whole branch B1 / B2 / B3 / B4 / B5 | RED each (8 / 1 / 1 / 8 / 4 rows named) |
| Optional `(?:the\s+)?`, `(?:a\s+)?`, the verb group, B4's `\w*` (not alternatives; extra) | RED each |

**Why this is NOT MET.**
- The 7 alternatives round 6 §3c named (Hinglish `sponsored`, `nahi`, `na`, `laga`, `mention`; Devanagari `sponsored`, `नहीं`) are now guarded.
- The done_when says **every** alternative, and 8 others have no row that depends on them. Each can be deleted with the corpus green.
- This is F-1766's defect class, one level below the branch.
- **I missed these in my KB5 check.** My KB5 note 4 listed only the Hinglish and Devanagari alternatives; these 8 were unguarded then too.

**B3's `ad` is dead in grammatical English.**
- It matches only "don't disclose this as ad" or "as a ad" (probe: true).
- It does not match the natural "Don't disclose this as an ad." (probe: false), because `(?:a\s+)?` never admits `an`.
- A row cannot guard it naturally. Per round 6 §3c ("one natural caught row per alternative, or prune it"), it should be pruned, or changed to `an?\s+`, which needs Priya's ruling because it widens the pattern.

**Fix (vikram, then kavya red-first):** for each of the 8, add one caught row whose match depends on that alternative alone, e.g.:
- "Post it without #ad this time"
- "Don't put #ad on it"
- "No #collab tag please"
- "No ad tag on this one"
- "No sponsored tag please"
- "Don't mention this is sponsored"
- "Don't disclose it as a paid partnership"

Alternatively, prune an alternative under §3c. For B3 `ad`, the choice is prune it or have Priya rule on `an?`.

**Ruling-conformance note (not in the done_when, for the last call).**
- §3c asked for Nisha-written natural rows, pruning any alternative she "cannot write naturally".
- Nisha wrote 7 natural rows (NISHA-HIDE-WORD-ROWS-0918.md). **None of them fires** (printFullReport FN ids).
- vikram added 7 mechanical `VIK-GUARD-*` rows ("sponsored mat likhna", "#ad नहीं डालना।") to the ratchet instead. The javadoc (L558-577) records this as "NOT yet closed".
- **This inverts §3c's test:**
  - What §3c asks: does anyone write this naturally?
  - What was measured: Nisha wrote natural hide-the-ad sentences using each word, and the pattern catches none of them.
  - What that shows: those alternatives, as written, catch only the mechanical form. The rows guard against deleting them. They do not show that the alternatives catch real asks.
- Priya should rule: keep them as guarded but low-recall, or prune them.

## Clause 6: a red loop run names every failing row
- **Loop tests:**
  - both no-false-positive tests
  - both ratchets
  - suppression
  - `bareWalletNameAloneStaysSilent`
  - `pairingWindowIsExactlySix`
  - `sentenceBoundaryStopsPairingAcrossSentences`

  Each collects its misses into a `List<String>` and asserts `isEmpty()` once.
- **Shown red with multiple ids in one run:**
  - two alternatives deleted at once (`laga` and `विज्ञापन`): `Expecting empty but was: ["KAB5-HD-F-devanagari-01", "VIK-GUARD-HD-F-laga"]`
  - two wallet names deleted (`phonepe`, `neft`): `["KAB5-F-phonepe-bhejo", "AB-approx", "AB-ac-no", "AB-amt"]`, plus 16 suppression pairs
  - `mentioning` added to `SEND_REQUEST`: `bareWalletNameAloneStaysSilent: Expecting empty but was: ["upi", "gpay", "phonepe", "paytm", "google pay", "neft", "imps", "rtgs", "bank transfer"]`
  - the Influora exclusion: all 96 wrap pairs named
- My KB5 note 5 (the ratchet stops at its first miss) is closed.

**LOW, outside the clause (these are not loops).**
- Several tests make sequential asserts and stop at the first failure:
  - `nonBreakingSpaceBypassFixed`
  - `zeroWidthSpaceBypassFixed`
  - `leadingBoundaryProtectsBrandNamesEndingInNo`
  - `bareDisclosRemoved`
  - `paymentAfterRemoved`
  - `BriefFallbackExtractorTest`'s two `…IsAlwaysFalse` tests
- The first two report a bare `Expecting value to be true but was false` that names no text (seen in 6 runs above).
- **Fix:** give each assert an `.as(text)`, or fold them into the list shape.

## After the last mutation
- **Hashes:** all 7 files match the baseline sha256.
  - `OffPlatformPaymentRule.java` `6b7c7655…9136`
  - `HideDisclosureRule.java` `e9c38087…b041`
  - `BriefFallbackExtractor.java` `a7050a53…0262`
  - `RiskText.java` `d6c0e01f…9e31`
  - `RiskFlagCorpusTest.java` `64d3690d…2d3d`
  - `BriefFallbackExtractorTest.java` `d4445d4d…3cda`
  - `BriefFallbackExtractorRealRiskRulesTest.java` `1a4dc498…a738`
- **Markers:** `grep -rn FALSIFY-TEMP influora-api/src` finds **0**.
- **Staging:** `git diff --cached` names **0** files.
- **No new stash:** the one stash entry is from 2026-08-17. No commit. Nothing under `influora-ai/` or the `New Influora` tree was touched.
- **Gate:** `bash .proof-os/gates/F-1765-F-1766-risk-corpus.sh` printed `PROVED: RiskFlagCorpusTest tests=18 failures=0 errors=0 skipped=0; guarding rows present; PAIRING_WINDOW present` and exited **0**.
  - LOW: its header comment is now stale. It still says F-1769 is open, and that "the corpus only pins it between 3 and 12".
- **Restored regression run**, clean, 870 + 346 files compiled: `Tests run: 130, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. The classes were:
  - RiskFlagCorpusTest 18
  - BriefFallbackExtractorTest 15
  - BriefFallbackExtractorRealRiskRulesTest 5
  - CreatorBriefServiceRealRiskRulesTest 3
  - CreatorBriefServiceTest 21
  - DealRiskServiceTest 42, plus `$SeverityScaling` 6. The CompetitorConflict and ExclusivityLong rule tests live here, including `nonDismissibleFlagsAreTheSpecifiedThree`.
  - DealRiskServiceEvaluateExtractionTest 5
  - DealRiskServiceEvaluateDealTest 15

## Can a real ask slip past both the text rules and the FALLBACK path? Yes.

**Why "both" means one control on FALLBACK.**
- On FALLBACK there is no model, and the extractor's hints are now always `false`. So the rules' text checks are the only thing between the brief and the creator.
- Any ask the text rules miss gets **no flag at all** on a FALLBACK brief.
- FALLBACK is not rare. It is the normal state once a creator's monthly brief allowance is spent, as well as during an AI outage.
- Rounds 5 §2a and 6 place recall on "the hint". On this path there is no hint.

Measured with the probe on the built classes. "Flag" is the extractor's hint OR the rule's text check, as `apply` computes it. "Pre-F-1773" is HEAD's extractor pattern.

**Off-platform, real asks, no flag on FALLBACK now:**

| Ask | Pre-F-1773 extractor |
|---|---|
| "Kindly share your UPI ID for payment." | flagged |
| "Can we do this via GPay instead? Saves the commission." | flagged |
| "Hum aapko seedha Paytm kar denge, platform fee bach jayegi." | flagged |
| "Let's settle the fee over UPI and skip the platform cut." | flagged |
| "Drop your UPI ID in my DMs, we'll clear the payment today." | flagged |
| "Whatsapp me your GPay number for the fee." | flagged |
| "We'll do a direct bank transfer to your account, no need for the app." | flagged |
| "UPI pe le lo, invoice ki zaroorat nahi." / "Humein apna UPI ID DM kar do." | flagged |
| "We'll pay 5k. GPay ok?" / "We'll send the fee, taxes etc. directly to your UPI." (cut by the new boundary) | flagged |
| "We'll pay you on G Pay, quicker than the app." / "…on G-Pay…" / "Share your Phone Pe number, we'll pay there directly." | missed |
| "We'll send it to your U.P.I tonight." | missed |
| "We'll transfer the amount to your bank account directly, no need to go through the app." | missed |
| "Payment will be made directly to your account, not via Influora." | missed |
| "Commission bachane ke liye seedha account mein daal denge." / "हम सीधे आपके खाते में पैसे डाल देंगे।" | missed |

**Hide-the-ad, real asks, no flag on FALLBACK now:**

| Ask | Pre-F-1773 extractor |
|---|---|
| "Please skip the #ad tag on this one." | flagged |
| "Keep it looking organic, no hashtags like #ad." | flagged |
| "Don't use # ad on this one." | flagged |
| "No need to add #ad on this one." / "Please don't add any #ad." / "Please don't add an ad tag." | missed |
| "Don't disclose this as an ad." (see clause 5, B3 `ad`) | missed |
| "Remove the paid partnership label before posting." / "Avoid adding #ad or the paid partnership label." / "Don't use the paid-partnership label." | missed |
| "#ad hata dena." / "Ad ka tag mat lagana." / "Paid partnership ka label mat lagana." / "विज्ञापन का टैग मत लगाना।" | missed |
| "Don't mention it's paid." / "Don't use ＃ad" (full-width hash) | missed |

Controls that do flag: "Send me your UPI and we'll G-Pay you the balance.", "Paying via UPI outside Influora saves us 10%.", "We can pay you directly, skip the platform.", "Post it without the #ad tag.", "Don’t use #ad" (curly apostrophe) and "#ad नहीं लगाना".

**Assessment.**
- **F-1773's trade.** Round 6 accepted the loss of vocabulary the ruled patterns leave out on purpose, "such as bare wallet names, 'keep it organic' and 'avoid disclosure'". Most of the off-platform losses above are that trade.
- **Two losses are not on the ruling's list:**
  - `skip` as a negator ("skip the #ad tag")
  - `#\s*ad`, a hash then a space ("# ad")

  Both were in the old FALLBACK pattern and neither is in `HIDE_TEXT`.
- **"Kindly share your UPI ID for payment." is the one I would raise first.** It is probably the most common real off-platform wording in Indian brand DMs, and it is now silent on every FALLBACK brief.
- **Spelling gaps, independent of FALLBACK.** `WALLET_NAME` misses "G Pay", "G-Pay", "Phone Pe" and "U.P.I" (the probe shows them silent). This applies on the AI path too, wherever the hint misses.
- **This is not a done_when failure.** Clause 3 asks for exactly this behaviour, and the ruling priced it in. It is a MEDIUM product risk for Priya.
  - **One option (copy):** the FALLBACK summary or degraded notice could say that risk checks without AI catch only plain wording.
  - **Another (vocabulary):** a small precision-safe addition to `WALLET_NAME` (`g[\s-]?pay`, `phone\s?pe`). That needs a ruling and corpus rows.
- **Precision residual, LOW.** A pipe `|` typed as a danda substitute is not a terminator. "ड्राफ्ट भेज दीजिए | भुगतान UPI से Influora पर होगा" therefore still raises the non-dismissible flag against an honest brand. This belongs with the live sample's `basis` split, like the no-space residual.
