# Priya last call: U-2 (`PasteBriefCard` and its paste path)

**From:** Priya (CTO)
**To:** Arjun. Builder: ananya. QA: kavya. Rules: vikram, kabir, nisha
**Date:** 2026-09-18, 17:25
**Tree:** `influora-b0`, branch `feat/meera-creator-phase-b0`, uncommitted on `34808c0`
**Bar:** `RULINGS-U-0917.md` "Last-call bar → U-2" items 1-10, as extended by rounds 3-7. This includes the general rule: "A test that has only ever been green does not count."

## Verdict: **NOT PASSED. Passes on three conditions, listed below, without another full last call.**

**Is F-1776 the only thing outstanding? No.** Three things are:

| # | Outstanding | Owner | Where it is specified |
|---|---|---|---|
| 1 | **K-2c.2**: F-1776's guard rows plus round 7's rulings 1, 3 and 4 | vikram, nisha | `RULINGS-U-0917.md` round 7, "K-2c.2 pass bar" |
| 2 | **R7-A (new, HIGH)**: HIDE_DISCLOSURE raises its non-dismissible "Breaks ASCI guidelines" flag on compliant brands. 13 of 15 lines telling the creator to **keep** the label flag, e.g. "Please do not post without the paid partnership label." It goes in the same commit as item 1 | vikram, nisha | round 7, "R7-A" |
| 3 | **Four U-2 bar items have no test that goes red** when their behaviour is removed | ananya | this file, below |

- **F-1777 does not block U-2.** It blocks go-live (round 7, Ruling 2).
- **The OFF_PLATFORM payout-statement residual does not block U-2** either (round 7, last "New" section).

## How this was checked

- **Read in full:**
  - `PasteBriefCard.tsx` (`8dbc20e6…`) and `PasteBriefCard.test.tsx`
  - the paste and consent wiring in `creator-copilot.tsx` (L64-195, L225-300)
  - the U-2 describe block in `creator-copilot-paste-brief.test.tsx` (L175-224)
  - `BriefCard` and `degradedLabelFor` (`CreatorToolResultRenderer.tsx` L60-100, L455-650)
  - the empty-state contract of `deal-risk-card.tsx` (L120-150)
- **Consent version, by grep:**
  - `CreatorAgentPreferences.CURRENT_CONSENT_VERSION = "v2"` (L56)
  - `ConsentScreen.CONSENT_TEXT_VERSION = 'v2'` (L41)
  - the `api.ts` mock `consent_version: 'v2'` (L6651)
- **Executed:**
  - Vitest ran on a scratch copy of `src/` with `node_modules` junctioned, at `scratchpad/pk3r3/fe/`. `CreatorAgentPreferences.java` was copied beside it so the version-sync test can read it.
  - Baseline, U-2 test set of 6 files: **`Test Files 6 passed (6)`, `Tests 68 passed (68)`**. The files are `PasteBriefCard.test.tsx`, `creator-copilot-paste-brief.test.tsx`, `ConsentScreen.test.tsx`, `consent-version-sync.test.ts`, `useRiskFlagDismissals.test.ts` and `CreatorToolResultRenderer.test.tsx`.
- **Mutants:** `scratchpad/pk3r3/u2mut.py`. Each mutant asserts its anchor occurs exactly once, applies it to the scratch file, runs 4 test files (56 tests), restores the file and checks its sha256. The run ended `restored byte-identical: True`.
- **The tree was not modified.** No Maven, no stash. Nothing in `src/` or `influora-api/` was edited.
- **The risk-rule measurements** behind items 1 and 2 of the table above are round 7's (probes 1-5 on the built classes).

## Bar, item by item

| # | Bar item | Verdict | Evidence |
|---|---|---|---|
| 1 | R-U2 in the branch, and K-2, K-2b, round 5 R1 + 2b, K-2c closed | **NOT MET** | R-U2: MET. The consent text and version are v2 on all three sides and the sync test passes 7/7; Kabir APPROVE and priya PASS on U-6. K-2, K-2b and round 5: closed (F-1765, F-1766). K-2c: F-1769 and F-1773 closed, **F-1776 open**. **R7-A open** (round 7 made it a condition) |
| 2a | Consent known missing: Analyse sends nothing and opens consent | MET | M1 (short-circuit removed): **RED**, 2 failed, both "asks for consent instead of sending the paste…" tests |
| 2b | Accepting from the paste card does not open the chat | MET | M3 (paste consent tagged as chat consent): **RED**, 1 failed, "…and accepting does not open the chat" |
| 2c | Consent unknown: the paste is sent, and a `CONSENT_REQUIRED` refusal is handled | **NOT MET (no test)** | The code handles it (`PasteBriefCard.tsx` L139-141). M2, with that branch replaced by `if (false)`: **GREEN, `Tests 56 passed (56)`**. No test in either file makes `paste` reject with `CONSENT_REQUIRED` |
| 3a | An 8,001-character paste keeps 8,000 and says so | MET | M4 (slice removed): **RED**, "counts characters and says so when a paste is cut at the 8,000 cap" |
| 3b | No `maxLength` attribute | **NOT MET (no test)** | The code has none. M5 (`maxLength={PASTE_BRIEF_MAX_CHARS}` added): **GREEN, 56/56**. The cap test sets the value through `fireEvent.change`, which jsdom does not truncate at `maxLength`, so it cannot see the regression. In a browser, a long paste would then be cut silently and the "Only the first 8,000 characters were kept." line would never show, which is exactly what the item exists to prevent |
| 4 | Errors inline, never a toast; `text-destructive-foreground` on `bg-destructive` | MET | M6 (class changed to `text-destructive`): **RED**, "renders an error inline, and never as a toast". The toast mock is asserted uncalled |
| 5 | `cap` and `ai_unavailable` get different, honest text | MET | M11 (outage sentence set to the cap sentence): **RED**, "uses a different sentence when Meera could not be reached" |
| 6 | Flags use scope `BRIEF:{brief_id}` | **NOT MET (no test)** | The code is right (L224). M7 (no prefix) and M8 (`DEAL:` prefix): both **GREEN, 56/56**. The dismissal tests pass under any non-empty scope. So a flag hidden on this card and the same brief's `get_brief` card in chat (`BRIEF:${payload.brief_id}`, renderer L744) could silently stop sharing hidden state |
| 7a | `FEATURE_DISABLED` hides the card: page probe | MET | Test "does not mount it in the calm FEATURE_DISABLED state" |
| 7b | `FEATURE_DISABLED` hides the card: returned by the paste call itself (flag turned off after the page loaded) | **NOT MET (no test)** | M9 (page's `onFeatureDisabled` removed) and M10 (card's `FEATURE_DISABLED` branch disabled): both **GREEN, 56/56** |
| 8 | No dead controls: no "Create secure link"; "Ask Meera" only as U-5 specifies | MET | Read: no secure-link control (the javadoc L17-19 says why). U-5 passed (`PRIYA-LASTCALL-U3-U5-0917.md`); its 5 page tests pass here |
| 9 | Kavya's LOW: the shared rupee formatter | MET | The chips use `formatINR` from `@/lib/utils` (renderer L2, L516, L520). `PasteBriefCard`'s only formatter counts characters |
| 10 | Live after S-2: one real paste renders a summary, flags and a quote | Not part of the code pass | Still owed after S-2. The round 3 §1 deploy check (delete every `creator_briefs` row from before the deploy) now also covers rows analysed before K-2c.2 |

**Why items 2c, 3b, 6 and 7b fail on the bar, not on behaviour.** The code does the right thing on all four today. The bar requires a test shown red against a wrong version, and none of these four has a test that can go red. This matches `kavya`'s round-1 PASS, which ran no mutations.

## The four tests (ananya; kavya shows each red on its mutant first, quoting the red line)

| Test | What it asserts | Must go red on |
|---|---|---|
| **T1** (item 2c) | With `needsConsent` false, `paste` rejects with `ApiError('CONSENT_REQUIRED', …)`. `onConsentRequired` is called once, the consent line shows inline, and no brief card renders. At page level, the consent screen opens | M2 |
| **T2** (item 3b) | The textarea has no `maxlength` attribute | M5 |
| **T3** (item 6) | A flag dismissed on the paste card for `brief_01` is also hidden, in the same session, on the `get_brief` tool card for `brief_01`. That is the real behaviour, and U-3 item 6 is the deal-room analogue. A storage-key assertion alone is weaker but acceptable | M7 **and** M8 |
| **T4** (item 7b) | On the page, with consent true, `paste` rejects with `ApiError('FEATURE_DISABLED', …)`. The paste card unmounts and the calm disabled state shows | M9 **and** M10 |

The mutants are in `scratchpad/pk3r3/u2mut.py`, with exact anchors. Vitest counts are quoted per file.

## Conditional pass: what closes U-2

U-2 passes, without a new full last call, when all three hold:
1. **T1-T4** are in `src/`, each shown red on its mutant by kavya (red lines quoted), then green, with vitest per-file counts.
2. **K-2c.2** lands exactly as round 7's pass bar says:
   - F-1776 closed;
   - R7-A built, including Nisha's 12 or more blind compliance rows with **0 flagged**;
   - Nisha's yes or no on the terse forms, with any "no" pruned;
   - the pipe fix;
   - surefire's Tests run / Failures / Errors / Skipped line quoted;
   - Kabir's re-probe signed.
3. The tree carries **no FALSIFY or SABOTAGE marker** when the Wave U commit is cut. At 17:04, `HideDisclosureRule.java` had `ad\s*tag` deleted with no marker on the line, presumably vikram's in-flight falsification. arjun confirms it is restored, by sha256, before staging.

**How I confirm.** I read those three pieces of evidence and write a one-page confirmation. I re-run a probe only if Nisha's blind rows flag something, or if the built `HIDE_TEXT` differs from round 7's measured candidate in a way the falsify map does not cover.

## Not U-2, recorded so it is not lost

- **F-1777 (go-live):**
  - the FALLBACK notice must also say that the risk check can miss asks written in other words;
  - the offline recall run gains a FALLBACK column;
  - K-2d adds `#\s?ad`, `g[\s-]?pay` and `u\.p\.i\.?`.
- **OFF_PLATFORM payout statements (go-live, new ledger record).** 7 of 7 lines describing Influora paying the creator's UPI flag, e.g. "You'll be paid to your UPI ID through Influora within 7 days of approval." Vikram measures a passive-voice exclusion, then I rule. Until then, round 4 item 5 governs.
- **To be ledgered (arjun, next free ids):**
  - R7-A, in `HideDisclosureRule.java`;
  - the OFF_PLATFORM payout-statement residual, in `OffPlatformPaymentRule.java`;
  - U-2's four untested bar items, in `PasteBriefCard.test.tsx` and `creator-copilot-paste-brief.test.tsx`. Its `missed_by`: "The round-1 QA pass ran no mutations; three of the four behaviours are invisible to jsdom or to a single-card render."

## Evidence (scratch, not in the tree)

All under `C:\Users\SAGEWO~1\AppData\Local\Temp\claude\C--Users-Sage-world-Downloads-New-Influora-Ai-New-Influora\ffe4a6e0-c83c-4765-8429-47a384be42aa\scratchpad\pk3r3\`:

- **U-2:**
  - harness: `u2mut.py`
  - output: `u2-run.txt`
  - scratch frontend: `fe\`. Its `node_modules` is a **junction** to the worktree's. Remove it with `rmdir`, never `rm -rf`.
- **Round 7:**
  - `r7\run.txt`: probe 1, corpus, variants and texts
  - `r7\run2.txt`: payout statements and the first candidate
  - `r7\run3.txt`: trimmed candidate and falsify map
  - `r7\run4.txt`, `r7\run5.txt`: the conditional lookahead, scoped vs compact
  - `r7\built-patterns.tsv`: the built pattern strings
  - `r7\src*\`: probe sources

---

**U-2 last call: NOT PASSED.** U-2's own code passes bar items 2a, 2b, 3a, 4, 5, 7a, 8 and 9. Items 2c, 3b, 6 and 7b have no test that goes red. Item 1 is open on K-2c.2 (F-1776 plus round 7) and on R7-A. U-2 passes on the three conditions above, and I confirm it on evidence, without a new full last call.
