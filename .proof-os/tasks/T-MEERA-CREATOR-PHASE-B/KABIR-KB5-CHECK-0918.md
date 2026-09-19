# KB5 check: F-0765 and F-0766 (kabir, 2026-09-18)

**done_when, verbatim:** "F-0765 and F-0766 closed by RiskFlagCorpusTest rows that go red against the fix removed"

## Verdict

| Record | Verdict | Evidence in one line |
|---|---|---|
| **F-0765** (OFF_PLATFORM_PAYMENT pairs a wallet name and a send word from anywhere in the brief) | **MET** | With the fix removed (M1), `KAB5-N-send-draft` and `KAB5-N-send-files` go red. They also go red when the window is widened to 13 (W13). |
| **F-0766** (HIDE_TEXT branches with no test row) | **MET** | Each of the three unguarded branches, deleted on its own, goes red on its intended row: Hinglish (M3), Devanagari (M4) and "don't mention" (M5). The fifth branch goes red on TRIGGER_TEXT (M6). |

**Ledger bookkeeping is not done yet.** In `.proof-os/ledger/failures.jsonl` (L764-765), both records still read `"status": "open"` with `"fix": ""`. The test side of the done_when holds. Whoever closes the records should set `status` and `fix`, pointing at the row ids below and at this file.

## What I read
- `OffPlatformPaymentRule.java` and `HideDisclosureRule.java` in full, plus `RiskText.java` in full.
- `RiskFlagCorpusTest.java` in full, and all 56 rows of `risk-corpus/nisha-blind-0917.tsv`.
- Ledger records F-0765 and F-0766.
- `KABIR-CONSENT-0917.md` "Last call — K-2b round 5" (L762-948).
- SPEC.md L897-936, including the AMEND-0917-KB5 comment and the OFF_PLATFORM_PAYMENT cell ("WITHIN 6 TOKENS").

## How I ran it
- **Command, every run, unpiped to a log:** `mvn -o -f "C:/Users/Sage world/Downloads/New Influora Ai/influora-b0/influora-api/pom.xml" clean -Dtest=RiskFlagCorpusTest test`. Every log shows `Compiling 870 source files` and `Compiling 345 source files`, so no run used stale bytecode.
- **Mutations:** made in-tree, one at a time, by a harness in the scratchpad (`kabir-kb5check-0918/mutate.py`).
  - Every mutated line was tagged `// FALSIFY-TEMP`.
  - Each file was restored from its original bytes. The harness checked the sha256 and bumped the mtime after every restore, and refused to start a run unless both files were at their original hashes.
- **Probe:** a standalone Java 21 program (`kabir-kb5check-0918/probe/.../Kb5Probe.java`), in the same package. It was compiled against `target/classes` after the final clean build of the restored source. It calls the built `OffPlatformPaymentRule.matches` next to a reconstruction of the pre-KB5-1 logic.
- **Logs:** `C:\Users\Sage world\AppData\Local\Temp\claude\C--Users-Sage-world-Downloads-New-Influora-Ai-New-Influora\ffe4a6e0-c83c-4765-8429-47a384be42aa\scratchpad\kabir-kb5check-0918\logs\<RUN>.log`, with the exact mutated lines in `<RUN>.diff`.

**Baseline:** `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. `Skipped: 0` means the fidelity test ran against Nisha's markdown.

## F-0765: mutations and output

**About M1.** The pre-fix code was never committed; the worktree is uncommitted. M1 is therefore a reconstruction, based on my own K-2b round 5 note (KABIR-CONSENT L866-874 and Q2: strip wallet names with a space, then run `SEND_REQUEST.find` on the rest). W13 does not depend on that reconstruction.

| # | Mutation | Result |
|---|---|---|
| M1 | Fix removed. The token-window block in `matches()` is replaced by `String stripped = WALLET_NAME.matcher(norm).replaceAll(" "); return SEND_REQUEST.matcher(stripped).find(); // FALSIFY-TEMP M1` | **RED.** `RiskFlagCorpusTest.offPlatformPaymentHasNoFalsePositives:368 [...] Expecting empty but was: ["KAB5-N-send-draft", "KAB5-N-send-files"]`. Result: `Tests run: 16, Failures: 1`, BUILD FAILURE. |
| W13 | `PAIRING_WINDOW = 13` (both rows sit exactly 13 tokens apart) | **RED**, with the same assertion: `Expecting empty but was: ["KAB5-N-send-draft", "KAB5-N-send-files"]` |
| W12 | `PAIRING_WINDOW = 12` | green, 16/0/0/0 (see "Not covered" item 2) |
| W3 | `PAIRING_WINDOW = 3` | green, 16/0/0/0 |
| W2 | `PAIRING_WINDOW = 2` | **RED.** `offPlatformPaymentStillCatches:425 [OFF_PLATFORM_PAYMENT must still catch OPP-F-11]` and `offPlatformPaymentSuppressionAttemptStillFires:387 [suppression attempt on OPP-F-11]`. Result: `Failures: 2`. |
| TOK1 | `WALLET_TOKEN = String.valueOf((char) 0x0001)`, the control-character placeholder that `trim()` eats | **RED.** `offPlatformPaymentStillCatches:425 [OFF_PLATFORM_PAYMENT must still catch OPP-F-11] Expecting value to be true but was false` |

- The two NO_FLAG rows are load-bearing: they go red against the fix removed.
- The must-catch side is guarded as well: a window that is too tight (W2) and the trim bug (TOK1) both go red.

## F-0766: mutations and output

| # | Mutation (HideDisclosureRule `HIDE_TEXT`) | Result |
|---|---|---|
| M3 | Hinglish short form (L75) replaced by `// FALSIFY-TEMP M3` | **RED.** `hideDisclosureStillCatches:416 [HIDE_DISCLOSURE must still catch KAB5-HD-F-hinglish-01] Expecting value to be true but was false`. My K-2b mutation (a) on the same line had stayed green (16/0). |
| M4 | Devanagari short form (L76) replaced by `+ "", // FALSIFY-TEMP M4` | **RED.** `hideDisclosureStillCatches:416 [HIDE_DISCLOSURE must still catch KAB5-HD-F-devanagari-01]` |
| M5 | `don't mention it's sponsored` branch (L73) commented out | **RED.** `hideDisclosureStillCatches:416 [HIDE_DISCLOSURE must still catch KAB5-HD-F-dont-mention]` |
| M6 | `don't disclose (this\|it) as ...` branch (L74) commented out | **RED.** `hideDisclosureStillCatches:416 [HIDE_DISCLOSURE must still catch TRIGGER_TEXT]` and `triggerTextFiresBothFlags:432 [HIDE_DISCLOSURE on TRIGGER_TEXT]`. Result: `Failures: 2`. |
| M8 | Devanagari lookarounds replaced by plain `\\b` on both sides (the "simplification" the javadoc warns against) | **RED.** `hideDisclosureStillCatches:416 [HIDE_DISCLOSURE must still catch KAB5-HD-F-devanagari-01]` |
| M7 | All alternatives that no row exercises removed at once (see "Not covered" item 4) | green, 16/0/0/0 |

- Every one of the five `HIDE_TEXT` branches now has at least one row that goes red when the branch is deleted.
- The lookaround choice is guarded too (M8).

## After the last mutation
- **Hashes:** both rule files are back at their originals.
  - `OffPlatformPaymentRule.java`: `4f5e9905…08ff`
  - `HideDisclosureRule.java`: `72c27e65…a53c`
- **Other files:** `RiskText.java`, `RiskFlagCorpusTest.java` and the TSV are unchanged (`d6c0e01f…9e31`, `d89910b8…0001`, `fbe0a922…da14`).
- **Markers:** `grep -rn FALSIFY-TEMP influora-api/src` returns **0**.
- **Staging:** `git diff --cached` names **0** files.
- **Restored run:** a clean build of the restored tree gave `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- **Wider regression run:** a clean run of the five risk-related classes gave `Tests run: 93, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. The classes were `RiskFlagCorpusTest`, `CreatorBriefServiceRealRiskRulesTest`, `DealRiskServiceTest`, `DealRiskServiceEvaluateExtractionTest` and `CreatorBriefServiceTest`.
  - The console shows `Tests run: 0` for the outer `DealRiskServiceTest`. That is a surefire reporting quirk: the `$SeverityScaling` XML holds all 48 testcases (42 outer and 6 nested), which matches the file's 48 `@Test` methods.
- **Files I did not touch:** nothing under `influora-ai/`, `ci/` or the `New Influora` tree. No stash, no commit.

## Not covered by the done_when, but it matters

### 1. MEDIUM: F-0765's defect class survives when the sentences are short
The fix closes the two measured texts, which put the wallet name 13 tokens from "send". It does not stop pairing across a sentence boundary: the window counts tokens straight through a full stop.

Measured on the built rule (probe output):

| Text (ordinary on-platform brief) | Built | Pre-KB5-1 |
|---|---|---|
| "Send the draft by Monday. UPI payouts go through Influora as usual." | **true** | true |
| "Please send the reel link. Your UPI ID in Influora gets the fee." | **true** | true |
| "Draft bhej do kal tak. UPI se payout Influora pe aayega." | **true** | true |
| "Script bhejiye Friday tak. Payment UPI se Influora wallet mein aayega." | **true** | true |
| "ड्राफ्ट भेज दीजिए। भुगतान UPI से Influora पर होगा।" | **true** | true |
| "Kindly send the invoice through the platform. UPI withdrawal is instant." | **true** | true |

- **Why this matters:**
  - "Draft bhej do" and "send the draft" are routine brief language.
  - Each false flag is non-dismissible for the creator.
  - Each one also writes an `OFF_PLATFORM_HINT` audit row against the honest brand's workspace id (`DealRiskService.recordOffPlatformHintIfPresent`, L485).
- **These are not the two "residuals by design".** Those two differ only in the sentence's subject and cannot be passed without keying on "Influora". These six could be told apart by position alone: do not pair across a sentence terminator (`.`, `!`, `?`, `।` followed by whitespace, or a newline).
  - That is still a positive-context-only rule, so Constraint A holds.
  - It is **unmeasured.** Abbreviations such as "Rs. 5,000" would split a real ask ("we'll send Rs. 5,000 to your UPI"), so any measurement has to include them.
- **Recommendation:**
  - Open a new ledger record for this; do not reopen F-0765, whose literal symptom ("anywhere") is fixed.
  - Add XS-3 (Hinglish) and XS-5 (Devanagari) as NO_FLAG rows when the fix lands.
  - The pattern change needs Priya's ruling.

### 2. LOW: the corpus pins the window only to the range [3, 12]
- SPEC.md's OFF_PLATFORM_PAYMENT cell says "WITHIN 6 TOKENS". W12 and W3 both stay green, so the window could drift to 12 unnoticed.
- One NO_FLAG row with the two words 7 to 12 tokens apart would pin the upper bound.

### 3. LOW (recall, for the record): the window drops real asks the old pairing caught
| Text (a real off-platform ask) | Built | Pre-KB5-1 |
|---|---|---|
| "Can you send across, whenever it suits you today, your UPI details so we can settle this?" | false | true |
| "We will transfer the full amount of Rs 20,000 for the reel to your GPay." | false | true |
| "Share your UPI and we'll send it tonight." | true | true |

- No corpus row covers wording like the first two, and Ruling 2b ratchets only named rows.
- This wording belongs in Nisha's fresh blind set for the hint measurement. It is not a blocker.

### 4. LOW: F-0766's defect class persists one level down, in alternatives
M7 removed every alternative that no row exercises, and the suite stayed at 16/0.
- **Hinglish branch:** `sponsored`, `nahi`, `na`, `laga` and `mention` are unguarded.
  - Its `#ad` alternative is effectively unreachable. The `\b` before `#` needs a word character in front of it, so "#ad mat daalna" actually matches through the `ad` alternative.
- **Devanagari branch:** `sponsored` and `नहीं` are unguarded.
- A future edit could delete any of these and stay green. That is the same defect as F-0766, below branch level.
- **Recommendation:** add one caught row per alternative, or record it as a known gap.

### 5. LOW: a red ratchet run under-reports
- `hideDisclosureStillCatches` and `offPlatformPaymentStillCatches` assert inside a loop, so they stop at the first failing id.
  - M3 named only `hinglish-01`. Whether `-02` and `-03` also fail is not visible from one run.
  - TOK1 named only `OPP-F-11`, although `KAB5-F-phonepe-bhejo` guards the same bug.
- Collecting the misses and asserting once, the way the false-positive tests already do, would make a red run list every lost catch.
