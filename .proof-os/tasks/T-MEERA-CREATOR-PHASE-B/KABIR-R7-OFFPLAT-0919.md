# K-2c.2 last call, OFF_PLATFORM half: F-1776 sentence-boundary guards and the pipe (kabir, 2026-09-19)

## Verdict

| Clause | Result |
|---|---|
| 1. Every OffPlatformPaymentRule sentence-boundary piece has a corpus row or assertion that goes red when that piece alone is removed | **MET.** 13 of 13 removals went red. Each red line is quoted below. |
| 2. Honest lines using `\|` as a danda do not raise OFF_PLATFORM_PAYMENT, and real asks using `\|` do | **MET.** 3 of 3 honest pipe lines are silent, and 2 of 2 real pipe asks flag. Checked on the built classes and on the corpus rows read by reflection. Removing `\|` turns all three honest rows red. |

The check also found four things outside the done_when: F1 to F4 below. None of them fails a clause. F4 is a condition on the commit.

**SIGN-OFF**, on condition that the commit stages the six untracked paths in F4.

## Artifact, by sha256 (real worktree, read-only; re-hashed after the last run, no change)

- `influora-api/src/main/java/com/influora/service/risk/rules/OffPlatformPaymentRule.java`: `94dd374e8ba0e281…dd63e5f0c`
- `influora-api/src/test/java/com/influora/service/risk/rules/OffPlatformPaymentRuleTest.java`: `5c2e2bb7d24e…`
- `influora-api/src/test/java/com/influora/service/risk/rules/RiskFlagCorpusTest.java`: `ab38ff1d534a…`
- `RiskText.java` (read for `norm`): `d6c0e01f…9e31`, the same file round 6 and round 7 measured.

No FALSIFY marker is in the real tree (`grep -c FALSIFY` returns 0). Nothing in `influora-b0` was edited except this file. I ran no stash, made no commit and did not touch `New Influora`.

## What I read

- Ledger F-1776.
- `RULINGS-U-0917.md`, rounds 6 and 7, in full. In particular, round 6 Ruling 1 and its pass bar, and round 7 Ruling 4 and pass-bar items 1 and 4.
- `OffPlatformPaymentRule.java`, `OffPlatformPaymentRuleTest.java` and `RiskFlagCorpusTest.java`, in full.
- `RiskText.norm`.
- `KABIR-K2C-CHECK-0918.md` clause 2, which is the origin of F-1776's six OFF_PLATFORM pieces.

## How I ran it

- **Scratch copies.** The scratch root is `…\scratchpad\kabir-r7-offplat\`.
  - A base copy holds `influora-api\pom.xml` and `src`, but not `target`.
  - It also holds `.proof-os\tasks\T-MEERA-CREATOR-PHASE-B\NISHA-RISK-CORPUS-0917.md` and `NISHA-COMPLIANCE-ROWS-0918.md`, at the same relative path, because both fidelity tests read `../.proof-os/...`.
  - The copied rule file's sha is `94dd374e8ba0`.
- **Four lanes.** Lanes A to D are four more copies of the same tree, and `diff -rq` against the base is clean for each. Each lane ran its removals one at a time.
- **Command.** Every run was `mvn -o -f <lane>/influora-api/pom.xml clean -Dtest=RiskFlagCorpusTest,OffPlatformPaymentRuleTest test` on JDK 21.0.9. Each run compiled 870 main and 347 test sources.
- **One removal per run.**
  - `mutate.py` makes a binary-mode, exact-bytes replacement and refuses to run unless the old bytes occur exactly once.
  - The FALSIFY line of every mutant was grepped into the lane log before its run.
  - After each run, the file was restored from `bak\OffPlatformPaymentRule.java.orig` and touched, its sha re-checked as `94dd374e8ba0` (logged 17 of 17 times), and `mvn clean` was run.
- **Baseline.** Tests run 22, Failures 0, Errors 0, Skipped 0: `OffPlatformPaymentRuleTest` 3/0/0/0 and `RiskFlagCorpusTest` 19/0/0/0. Skipped 0 means both markdown fidelity tests ran and were not assumed away.
- **Restored runs.** Each lane ends with a run on the restored file. All four gave 22/0/0/0.
- **Discarded runs.** My first two lane launches mutated nothing, and none of their output is used:
  - The first started before the lane copy had finished.
  - In the second, Python hit Windows MAX_PATH, because the lane path is 262 characters. `mutate.py` now uses the `\\?\` prefix.
  - Both only showed that an unmutated lane is green.

## Clause 1: each piece removed alone

"Red" means `BUILD FAILURE` (rc=1). The quoted line is from the surefire report.

| # | Piece removed | Mutant (FALSIFY line) | Result | Red test and red line |
|---|---|---|---|---|
| M01 | `.` terminator | `[!?\u2026\u0964\u0965\|]+(?=\\s\|$)` | RED, 2 failures | `offPlatformPaymentHasNoFalsePositives`: `Expecting empty but was: ["XS-1", "XS-2", "XS-3", "XS-4", "XS-6"]`; also `sentenceBoundaryStopsPairingAcrossSentences` (XS-1..4, XS-6 "must not flag: pairing must not cross a sentence end") |
| M02 | `!` | `[.?\u2026\u0964\u0965\|]+…` | RED, 1 | `OffPlatformPaymentRuleTest.eachExtraTerminatorCharacterStopsPairing`: `Expecting empty but was: ["'!' terminator: must not pair the 'send' before it with the UPI after it"]` |
| M03 | `?` | `[.!\u2026\u0964\u0965\|]+…` | RED, 1 | same test: `["'?' terminator: must not pair the 'send' before it with the UPI after it"]` |
| M04 | `…` U+2026 | `[.!?\u0964\u0965\|]+…` | RED, 1 | same test: `["'…' (U+2026, ellipsis) terminator: must not pair across it"]` |
| M05 | `।` U+0964 | `[.!?\u2026\u0965\|]+…` | RED, 2 | `offPlatformPaymentHasNoFalsePositives`: `["XS-5"]`; `sentenceBoundaryStopsPairingAcrossSentences`: `["XS-5 must not flag: pairing must not cross a sentence end"]` |
| M06 | `॥` U+0965 | `[.!?\u2026\u0964\|]+…` | RED, 1 | `eachExtraTerminatorCharacterStopsPairing`: `["'॥' (U+0965, double danda) terminator: must not pair across it"]` |
| M07 | `\|` | `[.!?\u2026\u0964\u0965]+…` | RED, 1 | `offPlatformPaymentHasNoFalsePositives`: `Expecting empty but was: ["PIPE-N-deva", "PIPE-N-hinglish", "PIPE-N-en"]` |
| M08 | blank-line rule (U+2029 kept) | `SENTENCE_BLANK_LINE = Pattern.compile("\u2029")` | RED, 1 | `offPlatformPaymentHasNoFalsePositives`: `["NL-blank"]` |
| M09 | U+2029 (blank line kept) | `Pattern.compile("\\r?\\n[ \\t]*\\r?\\n")` | RED, 1 | `OffPlatformPaymentRuleTest.paragraphSeparatorAloneStopsPairing`: `Expecting value to be false but was true` |
| M10 | list-item line break | `listItem.start();` (cut never added) | RED, 2 | `offPlatformPaymentHasNoFalsePositives`: `["NL-bullets", "NL-crlf-bullets"]`; `sentenceBoundaryStopsPairingAcrossSentences`: `["NL-bullets must not flag: a line break into a list item is a sentence end"]` |
| M11 | digit half of the protection | `if (type == Character.CURRENCY_SYMBOL)` | RED, 3 | `offPlatformPaymentStillCatches`: `["AB-rupee-sym"]`; the boundary test (`"AB-rupee-sym must still flag: not a real sentence end"`); suppression: `["AB-rupee-sym#1", "#2", "#3", "#4"]` |
| M12 | currency half of the protection | `if (type == Character.DECIMAL_DIGIT_NUMBER)` | RED, 1 | `OffPlatformPaymentRuleTest.dotBeforeCurrencySymbolAloneIsProtected`: `Expecting value to be true but was false` |
| M13 | abbreviation protection | `return false;` in place of `SENTENCE_DOT_ABBREVIATIONS.contains(word)` | RED, 3 | `offPlatformPaymentStillCatches`: `["AB-ac-no", "AB-amt", "AB-eg"]`; the boundary test (the same three ids); suppression: 12 wraps (`AB-ac-no#1..4`, `AB-amt#1..4`, `AB-eg#1..4`) |

**Notes on the table:**
- **Named failures.** Every piece in F-1776's symptom (`?` `!` `…` `॥` U+2029, and the dot before a currency symbol) now fails a named assertion of its own. The `OffPlatformPaymentRuleTest` message names the exact character, so one red run shows which piece went.
- **The two protection halves** each go red on their own: M11 through `AB-rupee-sym`, and M12 through the new `₹5000` case. So neither half is carried by the other.
- **No encoding false pass.** `…` and `॥` are raw UTF-8 in the test source (`e2 80 a6`, `e0 a5 a5`), and M04 and M06 go red. So the file compiles to the intended code points. `RiskText.norm` is NFC, not NFKC, so `…` is not folded to `...` before the rule sees it.

## Clause 2: the pipe, checked directly

The texts come from the built `RiskFlagCorpusTest.CORPUS`, read by reflection, so they are not retyped. They were run through the built `OffPlatformPaymentRule.matches` in the scratch base classes (`PipeProbe.java`).

| Row | Expected | Got |
|---|---|---|
| PIPE-N-deva | NO_FLAG | NO_FLAG |
| PIPE-N-hinglish | NO_FLAG | NO_FLAG |
| PIPE-N-en | NO_FLAG | NO_FLAG |
| PIPE-F-rupee | FLAG | FLAG |
| PIPE-F-upi-pay | FLAG | FLAG |

**My extra probes** (non-blind, not corpus rows):
- **Honest lines stay silent:** `||` as a double danda; a pipe glued to the word on its left (`Monday| UPI…`); and a markdown-table paste (`| Deliverable | Send draft by Monday |\n| Payment | UPI via Influora |`).
- **Real asks written with a pipe still flag,** in Devanagari (`अपना UPI नंबर भेज दीजिए | …`), Hinglish (`UPI ID bhej do | …`) and English (`Skip the app | we'll send the fee to your GPay tonight`).
- **Priya's rate-list case still flags** inside its last piece (`Rates: reel 5k | story 2k || send the invoice, UPI payout via Influora`).

**Red-first:** with `|` removed, all three honest rows flag again (M07 above).

## Outside the done_when

### F1: seven boundary alternatives have no dependent row (LOW; F-1776's class one level down)

The pieces are guarded, but some alternatives inside two of them are not. Each alternative below was deleted alone and the whole suite stayed green:

| Run | Alternative deleted | Suite | Realistic honest brief the mutant now flags (built rule: silent) |
|---|---|---|---|
| X01 | list marker `•` (U+2022) | GREEN 22/0/0/0 | `Deliverables:\n• Send draft by Monday\n• UPI payout through Influora within 7 days` |
| X02 | the numbered marker `\p{Nd}{1,2}[.)]` | GREEN 22/0/0/0 | `Timeline:\n1) Send script by Friday\n2) UPI payout via Influora after approval` |
| X03 | CRLF tolerance in the blank line (`\n[ \t]*\n`) | GREEN 22/0/0/0 | `Please send the draft by Friday\r\n\r\nUPI payout goes through Influora` |
| X04 | the "run of exactly one `.`" condition in `isProtectedDot` | GREEN 22/0/0/0 | (low impact: it only lets a `...` run before a digit or after an abbreviation stop cutting) |

**How X01 to X03 were measured.**
- Each mutant was compiled alone and placed ahead of the built classes on the classpath (`mp\ShapeProbe.java`).
- The built rule leaves all 8 shapes I tried silent. Each mutant flags exactly the one shape its alternative covers.
- So this is a gap in the guards, not a live false flag.

**Why no row guards them.**
- `*`, `·`, `▪` and `➤` are the same gap as `•`: the only list rows use `-` (NL-bullets, NL-crlf-bullets).
- NL-bullets-num does not guard the numbered alternative. Its `1.` and `2.` markers are already cut by the `.` terminator, because the word before the dot is a digit, which is not on the abbreviation list, and the next character is a letter. Only the `N)` form needs the alternative.
- No row has a CRLF blank line.

**Why it matters.** Each of these deletions would silently bring back a non-dismissible false flag against an honest brand. That is the precision failure that rounds 6 and 7 treated as blocking U-2.

**The fix:** vikram adds 7 NO_FLAG rows in the NL shape: one per marker `*` `•` `·` `▪` `➤`, one `N)` list and one CRLF blank line. Each deletion must be shown red.

**Why it does not block this done_when.** It is not in F-1776's symptom, not in round 7's pass bar, and not among the pieces this check was scoped to. I recommend a new ledger record, class `pattern-branch-with-no-test-row`, in `OffPlatformPaymentRule.java`.

### F2: the pipe javadoc overstates what still flags (LOW, a comment defect)

The javadoc on `SENTENCE_TERMINATOR` says "a real ask written across a pipe still flags". It does not.

**Measured on the built classes:**

| Text | Result |
|---|---|
| `Your UPI \| we pay today` | silent |
| `Share your UPI \| we pay today` | silent |
| `UPI ID de do \| hum pay kar denge` | silent |
| Each of the three without the pipe | flags |

**Why the rows do not show this.** Both PIPE-F rows flag only through words on the left of the pipe: "Send your UPI", and "send ₹5000 to your UPI". They would flag with or without the pipe cut, so M07 turns only the three NO_FLAG rows red.

This is round 6's accepted price ("Share your UPI. We'll send it tonight.") applied to the pipe. Round 7 Ruling 4 itself says "it can only remove flags". The javadoc sentence should record the price instead of claiming the opposite.

### F3: pipe residuals (INFO)

| Input | Result |
|---|---|
| A pipe with no whitespace after it (`Monday \|UPI…`, `Monday\|UPI…`) | still flags |
| Fullwidth `｜` (U+FF5C) | does not cut |
| Broken bar `¦` (U+00A6) | does not cut |

The first residual inherits the documented no-space residual, but the javadoc states that residual for the full stop only. None of the three is a ruled shape. I record them for the live 50-brief sample's `basis` split.

### F4: the guards have never been in git (condition on the commit)

**What is untracked.** `git status` shows these six paths as untracked (`??`):
- `RiskFlagCorpusTest.java`
- `OffPlatformPaymentRuleTest.java`
- all four `src/test/resources/risk-corpus/*` files (both TSVs and both generators)

**What history shows.** `git log --all` on them is empty, so none of these files has ever been committed. `OffPlatformPaymentRule.java` is tracked (last committed in `a33f07e`) and shows as modified.

**The risk.** A commit that stages only modified files ships the rule with none of the guards this check relied on. The proof-os gate `F-1765-F-1766-risk-corpus.sh` would still pass locally, because it reads the working tree.

**The condition.** At commit time, `git status --short -- influora-api/src/test/java/com/influora/service/risk/rules influora-api/src/test/resources/risk-corpus` must show nothing untracked. The earlier memory note, "Untracked file invisible to local gates", describes this exact failure.

## Files

**Scratch** (under `C:\Users\SAGEWO~1\AppData\Local\Temp\claude\C--Users-Sage-world-Downloads-New-Influora-Ai-New-Influora\ffe4a6e0-c83c-4765-8429-47a384be42aa\scratchpad\kabir-r7-offplat\`):
- `mutate.py`: all 17 mutations, with exact old and new bytes
- `lane.sh`
- `logs\<Mxx|Xxx>\`: `mvn.log` plus the surefire `.txt` for every run
- `logs\lane-A..D.summary`
- `PipeProbe.java`, `AcrossProbe.java`, `mp\ShapeProbe.java`

**Signed off:** SIGN-OFF, on condition that F4's six untracked paths are staged in the commit.
