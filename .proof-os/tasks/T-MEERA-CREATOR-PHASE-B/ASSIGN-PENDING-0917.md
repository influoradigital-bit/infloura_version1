# Pending Phase B work — owners and last call

**For:** Swapnil
**From:** Arjun (Engineering Lead)
**Date:** 2026-09-17
**Branch:** `feat/meera-creator-phase-b0` @ `df20091`, in the `influora-b0` worktree
**Source:** a code survey of that commit, not the board. Every "missing" below was confirmed absent from the files.

"Last call" is the one person whose check closes the item. Nothing closes on the builder's own word.

---

## Wave U — make it usable. RUNNING NOW.

Today "Paste and Read" can neither be pasted into by a person nor read by Meera. These close that gap, are independent of each other, and need no ruling.

| # | Item | Builder | Reviewed by | Last call | Stage |
|---|---|---|---|---|---|
| U-1 | `GetBriefExecutor` and its route, so Meera can read a pasted brief in chat. Wires the fifth tool. | vikram | kavya → meera | **priya** | Running |
| U-2 | `PasteBriefCard`, so a creator can actually paste | ananya | kavya → meera | **priya** | Running |
| U-3 | Supply a dismiss handler to the risk card on the screens that render it. Three flags stay non-dismissible. | ananya | kavya | **priya** | Running |
| U-4 | Marketing page: remove the four promises about drafting and labelling replies that do not exist | ananya (code) | nisha (copy) | **nisha** | Running |

### Wave U — review results, round 1

Combined tree before review: backend 3079 run, 0 failed, 25 skipped (Docker-gated); Python 941 passed; typecheck 0; frontend 1277 passed. All against mocks.

| # | Reviewer | Verdict | What it found | Sent back to | Stage |
|---|---|---|---|---|---|
| U-1 | kavya | **FAIL** | HIGH: the AI service gives up on the API after 5s and retries `get_brief`; the retry finds a brief saved but not yet read and returns it as a clean result: no flags, no price. Meera could call a deal safe that she never read. MEDIUM: the model has no way to learn a pasted brief's id, so only deals can be read. LOW: the tool description doesn't say both ids are refused; the result has no degraded reason; no card is drawn. | vikram (HIGH, both LOWs on the backend); ananya (card); priya (MEDIUM ruling) | Fixing |
| U-2 | kavya | PASS | LOW: a local rupee formatter duplicates the shared one | ananya | Fixing |
| U-3 | kavya | PASS | — | — | Waiting on Priya |
| U-4 | kavya (code) | PASS | MEDIUM: the demo video still shows "Drafted with Meera · approved by Riya" | ananya | Fixing |
| U-4 | nisha (copy) | **CHANGES REQUIRED** | Four lines on the page still promise approvals and drafts; the demo film has a whole draft, approve and "Sent" scene | ananya | Fixing |

### Priya's rulings, 2026-09-17 (`RULINGS-U-0917.md`)

- **U-1 fix design approved, with three additions:** also refuse a read whose extraction or flags came back empty for any reason; give the AI service's wait a named 40-second setting; add a test that fails if the brief reader becomes transactional again.
- **New item U-5: "Open in Meera".** The model had no way to find a pasted brief, so the paste card gets the "Open in Meera" button the spec already designed. The chat opens with "Look at brief …" filled in, and the creator taps Send. Wave U cannot close without it.
- **New item U-6: consent covers pasted briefs, and every creator consents again.** Must ship before B0.

| # | Item | Builder | Reviewed by | Last call | Stage |
|---|---|---|---|---|---|
| U-5 | "Open in Meera" on the paste card (frontend); a true tool description and a prompt-version bump (AI service) | ananya, vikram | kavya | **priya** | Queued behind current fixes |
| U-6 | Consent paragraph for pasted briefs, English and Hindi; consent version v1 → v2 in the same change | nisha (words), ananya + vikram (build) | kabir (security) | **kabir** on the words, **priya** on the build | Words with nisha and kabir now |

### Consent review, 2026-09-17 (`NISHA-CONSENT-0917.md`, `KABIR-CONSENT-0917.md`)

Nisha finalised English and Hindi; the screen has no Marathi version. Kabir: **approve with changes.** The sentence must add addresses and bank/UPI details, say "Influora saves a copy" (no screen lists saved briefs), and say deleting conversations does not delete the brief. Consent v1 → v2 approved; re-consent verified on paste, chat, tools and voice.

Kabir's findings beyond the notice. K-2 was confirmed in code by Arjun: `DealRiskService.java:287-288` passes null text, and `HideDisclosureRule:39`, `OffPlatformPaymentRule:50`, `UsagePerpetualRule:42` and `VagueDeliverablesRule:62` read it.

| # | Finding | Severity | Builder | Last call | Stage |
|---|---|---|---|---|---|
| K-2 | On a pasted brief the risk rules get no text, so four keyword checks never run. That includes the two flags a creator cannot dismiss, off-platform payment and hidden ad label, which then rest only on the model's reading of brand-written text. | **HIGH**, blocks U-2 | vikram | **kabir**, then priya | Queued behind the get_brief fix |
| U-7 | A pasted brief can't be erased by any route. Build a hard delete for one brief (checks flag and identity, not consent, so a creator who withdrew can still erase) and a button for it. | **HIGH**, blocks going live | vikram (route), ananya (button) | **kabir** | Assigned; Swapnil can skip it (see below) |
| K-3 | `get_brief` puts brand-written text into chat without the untrusted-content wrapper | MEDIUM; HIGH before Wave D | vikram | **kabir** | Must land before Wave D |
| K-4 | `consent.py` counts any consent timestamp as consent, whatever its version | LOW | vikram | **priya** | Queued |
| K-5 | Log redaction does not list `raw_text` or `summary_lines` | LOW | vikram | **kabir** | Queued |
| K-6 | Nothing in the app calls withdraw-consent, and the notice doesn't say how to withdraw or complain. Predates B0. | MEDIUM | ticket | **priya** | Ticket, not Wave U |

Consent copy follows U-7: with the delete route and button shipped, the notice uses Kabir's Case B ("Influora saves a copy until you delete it"); without them, Case A. Nisha finalises the Hindi once the case is settled.

### Round 2 results, 2026-09-17

| # | Item | Reviewer | Verdict | Open findings | Stage |
|---|---|---|---|---|---|
| U-1 | get_brief retry fix + Priya's A/B/C | kavya (`KAVYA-U1-RECHECK-0917.md`) | **FAIL** | No test covers a missing extraction with flags present (the extraction half of the guard could be deleted with all tests green). A GET route that can re-analyse has no rate limit. The tool description omits the second 409 code. A stale comment in `loop.py`. | vikram fixing → kavya → **priya** |
| K-4 | consent.py accepts only the version-aware boolean | (builder: vikram, falsified) | awaiting | none | **priya** |
| K-5 | pasted brief text and summary redacted from logs | (builder: vikram, falsified) | awaiting | Java has no key-based redaction, only regex; reported, not changed | **kabir** |
| U-4 | page + film copy | nisha (`NISHA-U4-RECHECK-0917.md`) | **CHANGES REQUIRED** | One surviving promise in the film: "I will send the 72-hour reach to the brand". All 7 of Ananya's own lines and the Ask Meera button approved. | ananya fixing |
| U-4 | film voice | arjun | **finding** | The film plays on the live page, and 12 recordings still speak removed promises. Silenced until Swapnil approves paid regeneration. | ananya fixing |
| U-5 | Ask Meera about this brief | (builder: ananya) | awaiting | The "nothing sent" assertion was never shown failing (the falsification died on an earlier line); being re-falsified | ananya → kavya → **priya** |

Verified by Arjun, not only reported: Maven 3090 run, 0 failed, 25 skipped, compiled from current source. pytest 949 passed (Vikram). Frontend 1294 passed and tsc 0 (Ananya, before this round's follow-ups).

Residual accepted: `api.creatorBriefs.get` has no 409 handling, but nothing in `src/` calls it today.

### Round 3, 2026-09-17

**U-1:** Kavya round 2 **PASS**. With **priya** for the last call, together with K-4.
**Frontend:** Kavya (`KAVYA-FE-RECHECK-0917.md`) passed U-3, U-4 film and page, U-5 and F7. F6: the brief card rejects a brief with no stored price and renders nothing. Arjun rated it MEDIUM rather than CRITICAL: the backend now refuses a brief with no analysis, so only a missing price reaches the card. With ananya, together with quantifying the film's silenced-beat timing.

**Priya's rulings on Vikram's plan** (`RULINGS-U-0917.md` round 3):

| # | Ruling |
|---|---|
| K-2 | Pass the stored raw text into the risk rules at the single call site. No backfill: the briefs table is on no remote branch, so no real rows exist. Meera checks the first deploy. Added: the mocked tests must assert the text argument. **Now a condition of U-2's last call.** |
| U-7 route | A repeat DELETE stays 404 (a 204 would report an erasure that never happened). Gate on flag + identity, not consent, on delete **and** list. Bucket 20/window, not 5, matched on method. |
| U-7 UI | A saved-briefs table cloned from "My Meera Conversations" in Meera settings, not a new screen and not a button on the paste card. Fetches up to 100 and says when older ones exist. Delete button disabled while running; a 404 on delete removes the row silently. |
| Consent | Wave U ships Kabir's **Case A** wording at **v2**. The U-7 UI commit switches to **Case B** at **v3**. |
| K-3 | Neutralise only the model's copy, never the browser card. **Every string in every creator tool result**, not 5 fields (brand names reach risk flags and get_my_deals too). One pure function; Kabir picks the neutraliser. Persona bullet approved, with a PROMPT_VERSION bump in the same commit. Must pass kabir and priya before D-1. |
| D-1 note | Deleting a brief must also delete its unsent drafts. |

**Kabir's plan review** (`KABIR-CONSENT-0917.md`, "Plan review"), with **Priya's follow-up ruling** (round 3 §7):

- **K-3 mechanism:** use the existing `wrap_untrusted("brand_written", …)` block, not angle-bracket escaping. In the model's copy only, wrap the whole `extraction` object and the whole `flags` array, for `get_brief`, `check_deal_risks` and `get_my_deals`. The card data is untouched. Extend the existing persona bullet and bump PROMPT_VERSION.
- **U-7 new required fix, resurrection:** an analysis that finishes after the brief was deleted re-inserts it (a detached merge with no `@Version`). Re-read the row inside the write transaction and skip the write if it's gone. A brief dismissed during analysis must stay dismissed (`applyAnalysis` forces ANALYZED). Prove both on real JPA with H2.
- **U-7 gate:** list and delete skip the feature flag as well as consent (Priya chose (a)). The saved-briefs table must render even when the Meera settings section is hidden by `FEATURE_DISABLED`. A guard test covers flag off and consent withdrawn: list and delete work; paste, get and dismiss refuse.
- **Case B text** adds "Your briefs and your conversations are deleted separately." Bucket stays 20/window.

**Priya's last call** (`PRIYA-LASTCALL-U1-K4-0917.md`):
- **K-4 PASS.** Restoring the timestamp branch turned 6 tests red; no consented creator is locked out.
- **U-1 FAIL, on tests only; the code is correct.** Nothing tests the 40s timeout or no-retry in the HTTP client itself (each broken separately, all 949 still green). The deal_id still-reading test doesn't assert that no AI call happened. Her own bar item 8 is amended: no degraded-reason field for B0, a description sentence about rule-based summaries instead, and storing the reason becomes a C-2 ticket. On re-check she looks only at items 1, 3 and 8.
- **U-1 does not wait on K-2;** K-2 stays U-2's blocker. Staging briefs analysed before K-2 must be deleted before any live check.
- **CI gap fixed now, not ticketed:** `creator_schemas.py` goes into PROMPT_SOURCES. Blocks committing Wave U.
- **U-1 live check** (meera, after S-2, on a build with K-2): one cold `get_brief` by deal_id in a real chat turn returns ANALYZED with extraction and flags on the first call, no 409, the stream reaches `done`, and `created_at` is correct in UTC.

### Closed

| # | Item | Checked by | Last call | Closed |
|---|---|---|---|---|
| U-4 | Marketing page and demo film: no draft or send promises; 12 stale recordings silenced with a readable text floor | kavya (code, 2 rounds) | **nisha: APPROVE** (`NISHA-U4-RECHECK-0917.md` Final) | 2026-09-17, uncommitted |
| K-4 | influora-ai consent accepts only the version-aware boolean | priya (6 red on falsification) | **priya: PASS** | 2026-09-17, uncommitted |
| U-3 | Risk-flag dismiss: non-dismissible flags stay visible, storage failure doesn't crash, unscoped cards can't dismiss | kavya (2 rounds) | **priya: PASS** on re-check (`PRIYA-LASTCALL-U3-U5-0917.md`) | 2026-09-17, uncommitted |
| U-5 | "Ask Meera about this brief": pre-fills and never sends, Decline drops the prompt, no double append | kavya (2 rounds) | **priya: PASS** on re-check | 2026-09-17, uncommitted |
| U-6 | Consent notice covers pasted briefs (Case A, en + hi), consent version v2 end to end, version-sync test, scrollable dialog | kavya (3 rounds), arjun browser check | **kabir: APPROVE** (words, G-2, G-3); **priya: PASS** build re-check | 2026-09-17, uncommitted. G-1 release gate: text and backend v2 in one deploy |
| U-1 | get_brief: Meera reads a pasted or deal brief; still-reading / unreadable refusals, no retry, 40s read, rate-limited GET | kavya (2 rounds + batch 1) | **priya: PASS** on re-check (`PRIYA-LASTCALL-U1R-U6-0917.md`) | 2026-09-17, uncommitted |

Owed after S-2 for U-1 (meera): one cold `get_brief` by deal id in a real chat turn returns ANALYZED on the first call, on a build with K-2 and K-2b.

**U-6 build: priya FAIL. Arjun's sequencing error, not the builder's.** Arjun told ananya to switch the consent text to Case B + v3 "in the same change" as the U-7 table. With the whole tree uncommitted, that overwrote the reviewed Case A + v2 build: frontend at v3, backend at v2, and Case B promising a delete route that doesn't exist.
- **UF6-1 (ananya):** save Case B + v3 as `U7-caseB-v3.patch`, restore Case A + v2 exactly, and re-apply Case B only in the U-7 change after the Wave U commit. Kavya then re-verifies Case A character for character and shows it red twice.
- **UF6-2 (ananya; required before the Wave U commit):** a test that the Java constant, `CONSENT_TEXT_VERSION` and the mock version are equal. It must be red three ways: today's split, a mock-only mismatch, and a look-alike comment in the Java file. Nothing pins the constant today (setting it to v1 keeps all 105 consent tests green).

**K-2b, priya round 4:**
- **Kabir's fixes 1-4 land before U-2's last call**, not just before go-live, because flags freeze per brief.
- **Option (b) rejected.** Instead: if a flag's regex-only precision is under 90% on the 50-brief sample, its regex half is removed.
- **Also required:** NFC in `norm`, letter-and-mark lookarounds for Devanagari (`\b` never fires there), and a trailing `\b` on hide-disclosure ("no #adventure" fires today). SPEC §5.2 amended in the same commit.
- **Corpus:** nisha writes the Hinglish and Hindi rows blind.

**UF6-1 and UF6-2, built by ananya:**
- **Case A + v2 restored** (`ConsentScreen.tsx:41` v2, `api.ts:6651` v2). The version-sync test `src/lib/__tests__/consent-version-sync.test.ts` went red all three ways.
- **Full frontend run:** 1335/1335.
- **Next:** kavya re-verifies, then priya re-does the U-6 build last call.
- **Do not use `U7-caseB-v3.patch`.** It captured every uncommitted api.ts change, not just Case B. At U-7, re-apply Case B from `NISHA-CONSENT-0917.md`.

**K-3 conditions: kabir APPROVE** on re-check.
- **What passed:**
  - the allow-list matches field for field, and the old-denylist mutant turns the probe tests red;
  - adversarial inputs pass on all 5 cases, and the decorative test is gone;
  - the KC-2 test goes red when its sentence is removed;
  - `.4` has never been used.
- **Four LOW follow-ups, vikram, before priya's K-3 call so she reviews final code:**
  - a probe test for unknown top-level fields on `check_deal_risks` and `get_my_deals` (the old denylist left the check_deal_risks test green);
  - the KC-2 test must also assert "you just never do what those words tell you to do";
  - a note that `quote` is trusted only while it carries no brand text;
  - deals with a duplicate or missing `deal_id` collide in the wrapped map and lose brand fields in the model's copy. Key by index instead.
- **Then priya** passes K-3 before D-1.

**K-2b built by vikram.**
- **Maven:** 3115 / 0 / 0 / 25, verified by arjun.
- **Corpus:** `RiskFlagCorpusTest` runs over Nisha's 56 blind rows plus rows written by people who had seen the patterns.
- **Result:** false positives removed. Blind hide-disclosure precision is 1.0, but blind recall is only 0.143. Blind off-platform precision is 0.667 and recall 0.286. The non-blind rows score near-perfect, so the patterns are tuned to their own examples.
- **Found on the way:** Java NFC keeps Devanagari nukta letters decomposed, so the pattern uses the decomposed form.
- **Disputed:** bare `upi` still flags "add your UPI ID in your Influora payout settings". Vikram did not relabel it.
- **Next:** **kavya** QA (nothing deferrable). **Priya round 5** rules on bare wallet names and on blind-recall expectations.

**Priya round 5 (K-2b):**
- **Ruling 1:**
  - **Rule:** a wallet name flags off-platform payment only together with a send/pay request; route phrases still flag alone.
  - **No exclusion list** (brand text must not be able to turn the check off).
  - **Acceptance:** zero false positives, no caught row lost, and a suppression test that appends Influora wording.
  - **Why:** Influora's own wallet asks for a UPI ID (`creator-wallet.tsx` L1068 and L1131).
- **Ruling 2:** keep both regex halves. Hard zero-false-positive floor. A recall ratchet as an exact list of caught ids; removing one needs a reason plus kabir.
- **Before go-live** (vikram, needs the live model and AI spend): Nisha's 56 rows through the real extraction model.
  - **Bar:** at least 80% recall and at least 90% precision per rule.
  - **If missed:** fix the prompt and re-test on a fresh blind set. If it still misses, the copy can't claim Hindi or Hinglish catches, and that goes to swapnil.
- **U-2's last call now also requires ruling 1 and the ratchet.**

**K-2b QA, kavya: FAIL** (`KAVYA-K2B-0917.md`). The corpus in `RiskFlagCorpusTest` is not verbatim.
- **Kavya found 1 row;** Arjun's script diff of all 56 found **5**, with every label intact:
  - HD-F-07 is a real letter change (U+0911 → U+0913);
  - OPP-F-07, OPP-F-08, OPP-F-13 and HD-N-07 have Nisha's decomposed nukta swapped for the precomposed U+095B.
- **Fix, vikram, folded into round 5:** load the rows from a mechanically extracted test resource, plus a fidelity test against her markdown.
- **Kavya's checks:** she held checks 4-7. Checks 5 (`norm()` regressions with ZWJ/ZWNJ) and 6 (ReDoS) run now. Checks 4 (mutations) and 7 (SPEC wording) run after round 5.
- **Session restart, 2026-09-17 evening.** Kavya had left a scratch test inside `influora-api/src/test/java/scratchpad/`, which every Maven run would compile. Arjun moved it to the session scratchpad.
- **Kavya checks 5-6: PASS.**
  - Check 5 used the real `RiskText.norm` in a temporary JUnit class, since deleted. ZWJ/ZWNJ/NBSP/NFC cause no regressions, and diacritics are preserved.
  - Check 6 is by structural analysis only, not a timed run. Her check-4 re-run will include a timed 8,000-character run.
- **K-2b round 5 built** (vikram). Arjun verified Maven 3121 / 0 / 0 / 25, compiled 18:50, zero markers.
  - **Ruling 1:** a wallet name needs a send/pay request found in text with the wallet names stripped. Stripping fixed "google pay" satisfying its own "pay".
  - **Ratchets:** zero false positives on both rules; exact-id recall lists; a suppression test.
  - **Corpus:** loaded from a generated TSV, with a verbatim test shown red. Metrics unchanged on the verbatim rows.
  - **Found and fixed:** a `DealRiskServiceTest` fixture regression; the old text is now pinned as a silent case.
  - **Kavya re-QA:**
    - **Passed:** fidelity (byte for byte, TSV loaded at runtime), the `norm()` path, the SPEC wording.
    - **Not done, for the second time:** the mutations (check 4) and a timed ReDoS run (analysed structurally only).
    - **Handled instead:** Arjun moved both to **kabir**'s last call, with Maven authorised, rather than a third round with kavya or having the builder prove his own fix.
  - **Kabir last call on round 5: APPROVE WITH CHANGES.**
    - **Mutations:** (b), (c) and (d) go red. (a), dropping the Hinglish short form, **stayed green**: no test row exercises it.
    - **Timed:** at most 3.2 ms median on 8,000 characters, worst case 11.9 ms. No ReDoS.
    - **Suppression:** adding text can't switch off a flag that already fires. The misses by design ("UPI kar dena", "GPay pe daal do") belong in Nisha's fresh blind set for the offline model recall run.
    - **KB5-1 (vikram):** a wallet name and a request word must be within 6 tokens. Today "send the draft … UPI ID in your Influora payout settings" flags. Add 2 NO_FLAG rows and "PhonePe number bhejo payment ke liye" as must-catch. 2 known false positives remain, left to the live sample.
    - **KB5-2 (vikram):** 6 must-catch rows covering every HIDE_TEXT branch (Hinglish, Devanagari, "don't mention it's sponsored"). Each branch deletion must go red.
    - **Process trap he found:** restore-by-copy kept the old mtime, and `mvn test` served the mutant bytecode. Saved to memory.
  - **Next:** vikram KB5-1/2 → kabir re-check → **priya** → U-2.
- **CI-1 round 2 built** (vikram). Each fix was shown red before and green or loud after, in scratch repos:
  - **Reused version:** checked against the path-limited history of config.py reachable from HEAD.
  - **Duplicate definition:** an `ast` parse that requires exactly one top-level string assignment.
  - **First push:** resolves against the merge-base with origin/main (the workflow now fetches origin/main), and fails loudly if unresolvable.
  - **CRLF-only changes:** ignored.
  - **Real tree:** OK.
  - **Meera re-proof round 2: PROVED.** Every round-1 gap is closed, re-confirmed in fresh repos, including a real bare-origin first push. `ast` evasions fail loudly. The fetch works while main is checked out. Rule 3 is linear.
  - **Residuals, for priya:**
    - (1) **Cross-branch reuse:** phase-e's tip holds a version not in b0's history.
    - (2) **Push to main with a zero `before`:** merge-base = HEAD gives an empty range, a vacuous pass (reproduced; near-impossible precondition).
    - (3) **Runtime `global` override:** invisible to any static gate.
  - **Priya last call: FAIL** (`PRIYA-LASTCALL-CI1-0917.md`, both findings reproduced in `scratchpad/ci1/`).
    - **F1, force-push passes silently.** CI passes the old tip, which is gone in a fresh clone, so the gate falls back to `HEAD~1` (~L297). Round 2 removed that shortcut only for first pushes.
    - **F2, the Wave U push itself would be red.** b0 has never been pushed, so rule 3 walks all 44 commits since origin/main. Four historical commits changed prompts without a bump: `1792c37`, `8c7b18b`, `a33f07e`, `df20091`. `--since HEAD` hid this.
  - **Rulings (round 3, vikram builds, meera proves, priya re-checks):**
    - **F2:** an explicit exemption file with those 4 full SHAs and a reason for each. Every skipped commit is printed. The list grows only via a reviewed diff. No date or epoch cutoff.
    - **F1 + residual 2:** remove the `HEAD~1` fallback entirely. A missing or unreachable base resolves via merge-base with origin/main. Fail loudly if it is unresolvable, or if it equals HEAD on a non-PR event.
    - **Residual 1:** check reuse against all `origin/*` branch heads. The workflow fetches all heads, and the gate fails loudly if none resolve. Why: `prompt_version` is written to every assistant message (`chat.py` L605, L900), so reuse would merge two prompts in the B0 metrics.
    - **Residual 3:** an `ast` pytest that forbids writes, `global` statements and env reads of PROMPT_VERSION outside `config.py`. Not a Wave U blocker; due before any env-driven rollback.
    - **Bar:** `forcepush.sh` and the merge-base-equals-HEAD case go red; normal pushes and PRs stay green. The Wave U simulation goes green with exemptions; an unlisted fifth commit, a removed SHA or a garbage line each go red. Cross-branch reuse goes red. No origin refs fails loudly. Rule 3 runs in single-digit seconds on 44 commits.
  - **`.4` safe to commit:** never in any branch's committed history. Arjun checked every worktree's working copy (phase-e incl. uncommitted, dmj, hotfix-f0818, loving-williamson): all at `meera-2026.08.10.1`.

**U-7 UI, ananya:** built per brief.
- **Behaviour:** lists up to 100; on delete, a 404 removes the row silently, a 429 shows a message, and any other error keeps the row. The table is visible with the flag off and with consent withdrawn. No consent edits.
- **Tests:** frontend full run 1341/1341, tsc 0.
- **Found on restart:** an un-reverted falsification in her own file. The delete-confirm button had lost `disabled={!!deletingId}`, with a `// SABOTAGE (TEMPORARY)` comment left behind, and no test caught it. She restored it.
- **Swept:** arjun searched the whole worktree for sabotage and falsify markers; nothing is left in source.
- **Copy:** nisha CHANGES REQUIRED on 3 strings. Ananya applied them, and arjun confirmed all three match `NISHA-U7-COPY-0917.md` verbatim.
- **Tests:** exact dialog bodies per source type. The in-flight state is tested on the per-row trash button: the confirm action can never render open and disabled at once, because Radix closes the dialog in the same batch.
- **Proof:** the `FALSIFY-TEMP` marker rule was followed, and her first falsification was caught matching its own comment. Frontend 1346/1346.
- **Remaining for U-7 UI:** kavya review, then **kabir** last call, both once vikram's DELETE route exists.

### 2026-09-18: release request and commit plan

**Swapnil's answer in this session:** commit **only work that has passed its final call**. The other session (a Priya/Arjun lane) relayed that B0 ships in today's release with the switch OFF. It agreed to the plan below: `.4` comes from this worktree first, then its brief_extract prompt change gets its own `.5` bump. No push or merge before `.5` exists.

**Why the commit can't be cut item by item:** `creator-copilot.tsx` (U-5) mounts `PasteBriefCard` (U-2). `CreatorBriefService` (U-1) feeds pasted text to the risk rules (K-2/K-2b/K-2c). `loop.py` and `config.py` `.4` carry both U-1 and K-3. Cutting "closed items only" would mean committing hand-built partial files nobody reviewed. The plan is therefore to **close the open items, then commit Wave U whole, minus U-7.**

**Priya, 2026-09-18 (`PRIYA-LASTCALL-K3R3-CI1-0918.md`): both checks FAIL.**

**K-3:** the code behaves correctly at every depth (31 probes), but 7 pieces can be removed with the full suite green:
- 4 `_is_fully_trusted_quote` checks;
- the empty or missing `deals` wrapper;
- 2 in-place edits in that same branch;

and a tag rename passes as a substring. F-0770 can close; F-0771 stays open. Vikram is on round 4, test-only.

**CI-1: FAILS on design.** The rule asks "has this value appeared before?" instead of "does one value name two prompt contents?", so:
- **PRs:** a PR that bumps correctly goes red on GitHub's `refs/pull/N/merge` (F-0774).
- **Main:** landing on main goes red.
- **Reuse:** a reused value arriving through a config-only commit is missed (F-0775).
- **Timing:** 10-17.5 s under Maven load.

**Decision (arjun, under swapnil's "closed only" rule): CI-1 is excluded from the Wave U commit** and redesigned separately. The main-branch gate stays in force, so there is no regression.
- **Out:** `ci/stale-comment-check.py`, the `frontend-checks.yml` change, `f0150-prompt-version-exempt.txt` and the F-0767/F-0768 gate stay uncommitted.
- **Honest note:** F-0767 and F-0768 were closed via promote.py against a CI-1 gate that is not landing. Their detection gate exists and works (it caught cb30e87), but the fix is not shipping. Treat them as open in substance until the redesign lands.

**The other lane committed to this branch:** `cb30e87`, `1d4660e`, `34808c0` (brief_extract, T-PHASEB-LIVE-0918).
- Only `cb30e87` touches a prompt source, and it is unbumped.
- The gate in force checks only for a version change across the whole range, and Wave U's `.4` covers it, so no `.5` is needed.
- When CI-1 lands, `cb30e87` needs a rewrite (while unpushed) or an exemption.
- The other session has been told.

**Kabir's K-2c check** (`KABIR-K2C-CHECK-0918.md`, 73 clean-build mutations): 5 of 6 done_when clauses MET.
- **Clause 5 NOT MET → F-0776.** 8 of 36 HIDE_TEXT alternatives, plus 6 sentence terminators, have no guard row.
- **F-0777 (MEDIUM product risk).** On FALLBACK the text rules are now the only check, and some real asks go unflagged.
- **Pipe `|` in place of a danda** gives a false flag (LOW).
- **Closed:** **F-0769 and F-0773** on arjun's `.proof-os/gates/F-0769-F-0773-sentence-and-fallback.sh`. That gate is falsified on BOTH subjects: sentence cut removed → 2 red; fallback hint set true → 3 red. Both files restored by sha256; the gate is green again (18/5/15 tests, 0 failed).
- **Vikram** (a second Java instance) is adding guard rows for 7 alternatives plus the 6 terminators.
- **Priya round 7** rules on: the "as ad" alternative, F-0777, Nisha's natural rows, and the pipe. Then she takes U-2's last call.

**Priya round 7 plus the U-2 last call** (`RULINGS-U-0917.md` round 7, `PRIYA-LASTCALL-U2-0918.md`). **U-2 does not pass yet.**

**New blocker, F-0778 (R7-A, HIGH).** The hide-the-ad flag fires on brands that FOLLOW the ad-label rules: 13 of 15 compliance lines ("Please do not post without the paid partnership label.").
- **Fix:** measured by priya. It clears 12 of the 13, loses no ratchet row, and adds no false positive. Cost: "Post it without the #ad tag." is no longer caught by text.
- **Nisha** writes at least 12 blind compliance rows; zero may flag.

**The other round-7 rulings:**
- **"As ad" alternative:** widen to `an?`.
- **Pipe typed as a danda:** fix it now.
- **Nisha's natural rows:** accept Vikram's mechanical guards. Her 7 natural rows stay known misses. She answers yes or no on whether brands actually write each short form; a no prunes it.
- **F-0777:** blocks go-live, not U-2.
  - Before go-live: `# ad`, `G Pay`/`G-Pay` and `U.P.I` spelling variants, plus a notice that the risk check can miss asks when there is no AI. `skip`, `via`, `payment`, `share` and `phone pe` are rejected, because each flags honest text.
- **F-0780 (payout statements like "You'll be paid to your UPI ID through Influora"):** recorded; a passive-voice fix to be measured before go-live.

**U-2's own tests, F-0779.** Four behaviours are correct but no test fails when they break: consent on CONSENT_REQUIRED, no `maxLength`, the `BRIEF:` scope, and hiding on FEATURE_DISABLED. **ananya** is adding T1-T4.

**U-2 passes without another full last call when:** T1-T4 land red-first; ONE Java commit carries F-0776 + F-0778 + `an?` + the pipe fix, signed off by kabir, with nisha's blind rows at zero flags; and no falsification markers remain.

**Nisha, done** (`NISHA-COMPLIANCE-ROWS-0918.md`):
- **Task A:** 14 blind compliance rows (NC-01 to NC-14; en, Hinglish, hi, including two long formal agency emails). All NO_FLAG, written before she opened any pattern or ruling.
- **Task B:** 6 of 7 short forms are real. **Prune `na`** ("ad na likhna"): brands use "na" only as a trailing tag, never as the negator. Its guard row goes too.
- All of this feeds vikram's F-0776/F-0778 commit.

**F-0776 rows built** (vikram).
- **Hide rows:** 7 `VIK-GUARD2-HD-F-*` rows (`RiskFlagCorpusTest.java:365-371`), each red alone on its own alternative with exactly 1 failure.
- **Boundary rows:** a new `OffPlatformPaymentRuleTest.java` covers the 6 sentence-boundary pieces (`?`, `!`, `…`, `॥`, U+2029, dot before currency), each red alone.
- **Checks:** both gates PROVED; the rule files are back at kabir's baseline sha256; Maven **3131 / 0 / 0 / 25**, isolated run.
- **Next:** vikram adds F-0778 + `an?` + pipe + prune `na` + Nisha's 14 blind rows as ONE change. Then kabir signs off, then U-2 closes along with ananya's T1-T4.

**F-0779: T1-T4 built** (ananya, tests only; no defect in the card).
- **Red-first on a scratch mirror:** T1 consent-required (component and page), T2 no `maxLength`, T3 `BRIEF:` scope (red under both of Priya's M7 and M8), T4 feature-disabled (red under M9 and M10).
- **Checks:** tsc clean, eslint clean. vitest 1348 passed, with `creator-protected-route` timing out in `beforeAll` under load (the known F-0217 flake; 3/3 passed alone).
- **Next:** kabir independently re-checks T1-T4 in the same pass as his sign-off on vikram's F-0778 change.

**K-3 round 4, priya re-check** (`PRIYA-LASTCALL-K3R4-0918.md`).
- **Result:** 4 of 5 clauses MET. F-0770 and F-0771 are both MET and **closed** on arjun's `.proof-os/gates/F-0770-F-0771-k3-brand-wrapper.sh`.
  - The gate checks the guarding tests by name and requires collected tests > 0 and 0 failed.
  - It was falsified on a scratch copy with byte-identical `loop.py`: F-0771 mutant → 10 failed; F-0770 in-place pop → 2 failed. Restored; the real tree was never mutated.
- **Clause "each piece has a red test" NOT MET:** B13 (zero-deal payload, which every new creator sends) and B14 (a no-brand-fields deal) change the browser copy with all 1043 tests green. They sit on untested branches around L968 and L1010. vikram (the K-3 instance) is adding the tests and fixing the stale comment at L762 and two docstrings.
- **Checked:** the "9 unrelated files changed" priya noticed. Arjun found the content unchanged on every closed item: ConsentScreen sha `a0383ecf` matches kavya's re-verify, and all three consent versions are still v2. Only the mtimes moved, from checkers' restore-by-copy.

**Round 7 (K-2c.2) built** (vikram).
- **F-0778:**
  - `without` pruned from B1, `(?<!with\s)no`, and a lookahead excluding "at the end", "in the comments", "only" and "in place".
  - A B4/B5 `nahi … toh` exclusion.
  - Nisha's 14 blind rows loaded mechanically (`nisha-compliance-r7.tsv`, with a fidelity test) at zero flags.
  - C1-C12 are NO_FLAG rows; each part of the fix went red when removed alone.
  - `VIK-GUARD2-HD-F-without` is now a report-only known miss, which is the accepted cost.
- **The other fixes:**
  - `an?` widened, with a guard row.
  - `|` added as a sentence terminator: 3 honest pipe lines no longer flag, and 2 real asks sit in the ratchet.
  - `na` pruned, with its row.
  - SPEC `AMEND-0918-R7`.
- **Self-reported:** his first pass of 11 falsifications ran without markers. He redid all 11 with markers on disk; results identical.
- **Arjun verified:** Maven **3132 / 0 / 0 / 25**, compiled 18:29, no newer source; rule sha256 `5bc1394e` and `94dd374e` match his report; 0 markers.
- **Next:** **kabir** signs off (fresh-context), covering this change AND ananya's T1-T4. That closes U-2 per priya's round-7 conditions.

**K-3:** vikram added the B13/B14 browser-copy tests, each red-first; pytest 1045. The gate now requires both by name (63 tests, PROVED). **priya** is doing a narrow re-check of the last clause.
- **Priya round 5: NOT MET.** B13 and B14 are fixed, and all 60 earlier mutants stay red. But 5 in-place list **reversals** pass all 1045 tests (flags in get_brief and check_deal_risks, quote.lines, quote.add_ons, deals), because every test list has exactly 1 element. With Spring's real multi-flag payload, the blocked-brand flag would move to last on the risk card.
- **Priya owns the miss:** her round-4 sweep never tried reordering.
- **Arjun's call:** use the class-level fix rather than more instance rows. The test double hands Spring's payload over **deeply frozen**, so any in-place mutation raises. It also uses lists of 2 or more elements. Vikram (the K-3 instance) is building it.

**2026-09-19 restart.** The session ended mid-run, stopping kabir's sign-off and priya's K-3 re-check. Neither wrote a report.
- **Tree check:** 0 markers. Rule files at `5bc1394e` and `94dd374e`; `loop.py` at `5a6a3d1b`. `creator-copilot.tsx` (touched 20:22) and the paste-card files match priya's `pk3r3\fe` mirror byte for byte, so only mtimes moved. No java process running.
- **Other lane** added commits `7e42954` and `67017a8` (brief_extract). Neither touches a prompt source, so `.4` still covers the branch.
- **Checks re-dispatched as 4 smaller parallel jobs,** so each can finish inside one session:
  - **kabir:** round-7 Java (F-0776, F-0778), Maven.
  - **meera:** U-2 T1-T4 (F-0779), vitest.
  - **priya:** the last K-3 clause with frozen payloads, pytest on a scratch copy.
  - **kabir:** **K-5** log redaction. It was never given its security last call; found missing on 09-18.

**F-0779 CLOSED.**
- **Meera, fresh-context: PROVED** (`MEERA-U2-TESTS-PROOF-0919.md`). All 4 behaviours were removed on the real file and each turned its test red; restored byte for byte (sha `8dbc20e6`).
- **Gate:** arjun wrote `.proof-os/gates/F-0779-paste-brief-card-behaviours.sh`. It runs vitest on both files, checks the 5 guarding tests by name, and requires more than 0 tests with 0 failed.
- **Falsified:** FEATURE_DISABLED check broken → exit 1 (3 failed). Restored by sha256; re-run green, 25/0.

**K-3 round 6, priya: NOT MET.**
- **Caught now:** every ordinary in-place mutation (L01-L27, D01-D09, S01-S23, 60 regressions, SALL).
- **The hole:** the freeze raises `TypeError`. A mutation inside `except`/`suppress` is swallowed, so it never happens in the test but does in production. Her X01-X04, X06, X07 and X08 all pass 1153 tests. `loop.py` already has 3 `except Exception` blocks.
- **Fix, test-only** (proven by her in scratch): blocked mutators record the attempt, and an autouse fixture fails the test at teardown. Also freeze the 2 get_brief retry/timeout payloads (turns B11 red) and fix a docstring.
- **Status:** vikram (K-3 instance) building.
- **Noted:** HEAD moved to `d81a789` (brief_extract lane). None of its commits touch K-3 files.

**K-3 round 7 (vikram):** mutation recording plus a teardown fixture; the 2 retry/timeout payloads frozen; the docstring fixed.
- **Y02:** closed with fixture lists that are non-monotonic on every key (3 flags, lines, add-ons, deals).
- **A self-inflicted D04 regression** was caught and fixed.
- **Results:** 50/50 of round 6 red; K-3 files 63 passed; full pytest 1205.
- **Pending:** the 60-mutant regression result, then **priya**'s re-check.

**Kabir's round-7 Java check was cut off by a session end, twice.**
- **Tree check afterwards:** rule files are at baseline sha with 0 markers; only the mtimes moved.
- **Re-dispatched as 2 parallel kabir instances**, each on its **own scratch copy of `influora-api`**, so the real tree is never mutated and each job is half the size:
  - HideDisclosureRule + Nisha's rows + `an?` + a lookahead-evasion probe;
  - OffPlatformPaymentRule sentence boundaries + the pipe fix.
- **Other lane:** commits `d81a789` and `3d88d58` are on the branch; neither touches a prompt source.
- **Warning:** the real `target/` may hold mutant classes, so the pre-commit build must run `clean`.

**Round 7: kabir SIGN-OFF on both halves, fresh-context. F-0776 and F-0778 CLOSED; U-2 CLOSED.**
- **HideDisclosureRule** (`KABIR-R7-HIDE-0919.md`, sha `5bc1394e`):
  - All three clauses MET: each of the 38 patterns and 5 round-7 changes goes red when removed alone.
  - Nisha's rows are loaded verbatim and none flags; "Don't disclose this as an ad." flags.
  - NC-01 is byte-identical to C1, so it adds no independent evidence.
  - **New, F-0781 (open):** "No #ad only organic vibes" and the Hinglish/Devanagari "nahi … toh" command forms escape. The round-7 exclusions conceded only placement rephrasings.
- **OffPlatformPaymentRule** (`KABIR-R7-OFFPLAT-0919.md`, sha `94dd374e`):
  - Both clauses MET: 13 of 13 boundary pieces each go red alone, and the honest pipe lines stay silent.
  - **New, F-0782 (open, LOW):** 7 list-item or newline alternatives have no guard row (`•` `*` `·` `▪` `➤`, `1)`, CRLF blank line).
  - The rule comment wrongly says a real ask across a pipe still flags.
  - A pipe with no following space, `｜` and `¦` do not split.
  - Both findings go to the next rule round, not this commit, so the signed sha stands.
- **Condition from both halves:** the guard tests and risk-corpus files were never committed. They are item 0 of the checklist.
- **Gate:** arjun wrote `.proof-os/gates/F-0776-F-0778-round7-guards.sh`. It runs `clean` on both test classes, checks every guard row and test by name, and requires 14 NC rows and more than 0 tests per class.
  - **Falsified against wrong fixes on the real tree, each with a `FALSIFY-TEMP` marker and a sha-checked restore:**
    - one NC row hidden → count broken;
    - `without` put back as a negator → NC-01, NC-05, C1-C4 flag;
    - placement lookahead dropped → C6-C9 flag;
    - `|` dropped → the 3 PIPE-N rows flag;
    - `?` dropped → `eachExtraTerminatorCharacterStopsPairing` red.
  - Control PROVED 19/0/0 + 3/0/0; 0 markers.
  - The first attempt never mutated anything (the heredoc halved the backslashes, then subprocess got a Windows path). Those runs were thrown away and rerun.
- **Closed via promote.py** (by arjun).
- **U-2:** per priya's round-7 conditions (T1-T4 red-first via F-0779, the round-7 Java signed off, Nisha's blind rows at zero flags, 0 markers), **U-2 is CLOSED**.
- **Wave U now waits only on priya's K-3 round-7 re-check.**

**K-3 round 7, priya (fresh-context): PASS. K-3 CLOSED** (`PRIYA-LASTCALL-K3R4-0918.md`, "Round 7 re-check").
- **Round-6 mutants:** all 50 fail now. X01-X09 fail at teardown on the recorded attempt; Y01-Y05 fail on the before/after compare, Y02 included.
- **The recording mechanism:** 16 mutators each wrapped in `except BaseException: pass` still record the attempt.
- **Earlier sets:** B13, B14, S01-S23, the 60 regressions and gSALL (6 failed / 1205) all fail; B11 now fails too.
- **Informational LOW:** Y06 and Y07 pass because the fixtures happen to be sorted by `title` and `deal_id`, so the "non-monotonic on every key" description overstates it. Optional fixture tweak.

**Wave U pre-commit verification (arjun), on a `git archive` of the exact commit tree, built in a PRIVATE index (`GIT_INDEX_FILE`); the shared index was never touched:**
- **Tree:** 117 paths, 62 of them previously untracked. U-7 and CI-1 excluded; `api.ts` staged without the `creatorBriefs.delete` block (the diff against the working copy is exactly that block).
- **Caught on the way:** the first tree swept in the other lane's in-flight `brief_extract.py` and `test_brief_extract.py` (dirty since 16:09), which gave 16 red `rr3` tests. On pure HEAD that file passes 224/224, so the red came from their unfinished work, not from Wave U.
  - Staging switched to a frozen allow-list: a dirty path nobody attributed is reported and never staged.
- **Results on the corrected tree:**
  - Maven `clean test`: **3132 / 0 / 0 / 25**, BUILD SUCCESS. Run on the first tree, whose `influora-api/` is byte-identical.
  - pytest: **1205 passed**.
  - tsc: clean. eslint on the 60 changed `src/` files: 0 errors, 24 warnings (the react-hooks v7 warn policy).
  - vitest: **1337 / 1337**. The earlier 1348 included the 11 tests in the excluded U-7 saved-briefs file.
  - Committed `ci/stale-comment-check.py`: OK with `--since` at both CI bases (`origin/feat/meera-creator-phase-b0` = `3d88d58`, and `origin/main`). It was non-vacuous: it saw 117 files and the `.2` → `.4` bump.
  - 0 markers in any staged source file.

**K-5 PASS** (kabir, fresh-context, `KABIR-K5-LASTCALL-0919.md`).
- **Mutations:** removing either key, or disabling the key check, turned red; everything restored by sha.
- **Weak tests (not blocking):**
  - The leak assertions never bite: the sample data holds only phone and PAN, which the regex already strips. The end-to-end test stays green under every mutation.
  - Fix: add a plain name and a UPI id to both samples.
- **LOW:** at DEBUG, the `anthropic` client logs the full outgoing request, including the pasted brief. It is off at INFO in every compose file. Fix: pin the `anthropic` logger to INFO in `configure_logging`.
- **Not blocking the commit.** Ticket these to vikram with the F-0963 follow-up.

**F-0963** (from the feature-audit session; the id lives in that session's ledger and is not duplicated here):
- **The defect:** `MeeraContextService.java` ~L244-248 reads the newest `creator_metrics` row from ANY data_source (`PageRequest.of(0,1)` + `findFirst`). A creator-declared CREATOR_REPORTED row newer than the last Meta sync then becomes Meera's "followers", with null reach, and the persona tells Meera to quote it as given. Arjun confirmed it is present in b0 and in phase-e.
- **Owner:** b0. Scheduled **after the Wave U commit** (not in it, per the "closed only" rule).
- **Fix pattern (from F-0961 in AnalyticsService):** filter `isPlatformVerified` after a small lookback, plus a test with a newer declared row.
- **Also sweep:** every other creator-Meera `creator_metrics` read, especially `estimate_my_rate` and `get_my_metrics`, for pricing off a declared count.
- **Agreed:** the other session will not also fix it in phase-e.
- **The sweep must also cover date-window reads, not only latest-row lookbacks.** Per that session's F-0961 fix, a declared row inside a window can otherwise become a growth or trend point.
- **Arjun's grep of b0 `src/main`, every newest-row-from-any-source reader:**
  - **F-0963 scope (b0):**
    - `MeeraContextService` (1)
    - `CreatorAgentPreferencesService` L200-205 (1): the agent's default rate
    - `RateQuoteService` (2): **prices pasted briefs and `estimate_my_rate`, so a declared follower count can inflate a quote**
    - `GetMyMetricsExecutor` (2)
  - **The other session's:** `AnalyticsService` (F-0961), `PublicCreatorService` (F-0964, its `VerifiedMetrics` record).
  - **Unclaimed:** `ScoreCalculationJob` (2), asked. **Resolved:** it is the other session's, F-0965, and out of b0's sweep.
- **Second source, `PlatformStatsAggregationJob`:** sums followers across ALL sources, including CREATOR_REPORTED, into `CreatorProfile.totalFollowers`, so every fallback to `getTotalFollowers()` or `getEngagementRate()` inherits declared numbers.
  - **b0 readers, creator side:** `MeeraContextService` (4), `GetMyMetricsExecutor` (3), `RateQuoteService` (2), `BelowFloorRule` (1).
  - **b0 readers, brand side (shown TO brands):** `ShowCreatorsExecutor` (2), `BrandContextAssembler` (1).
  - **Arjun proposed:** fix this at the source (aggregate verified rows only; any declared total gets its own field), owned by the other session. b0's F-0963 then covers the per-row reads in its 4 creator files. Awaiting agreement.
  - **Agreed split:** b0's F-0963 = per-row reads in its 4 creator files. The source = the other session's F-0965.
  - **Three writers of `totalFollowers`:** `PlatformStatsAggregationJob`, `ExternalCreatorLinkService:354`, and `PortfolioService:574`.
  - **The source fix waits on a product ruling from swapnil**, raised in the other session. `totalFollowers` drives discovery ranking, and declared platforms exist on purpose. The choice: verified-only (changes ranking) or a separate declared field (changes the API shape).
  - **Until then:** treat `profile.getTotalFollowers()` and `getEngagementRate()` as "may include declared" in b0 readers. The brand-side `ShowCreatorsExecutor` and `BrandContextAssembler` reads wait for the source fix, with no per-reader patch.
- **Sequencing:** reuse the other session's source-filtered finder `findByCreatorProfileIdAndDataSourceOrderByTimeDesc` (phase-e, pending commit; SHA to come). F-0963 is built after b0 merges into phase-e, so the finder exists once and the merge doesn't collide.

**Marker warning:** priya saw `ad\s*tag` deleted from HideDisclosureRule at 17:04 with no marker (vikram's in-flight falsification). Arjun confirmed it is restored at L79 by 17:11, with 0 markers in `influora-api/src`. Remind vikram: every mutation is marked.

**Gates before the commit:**
- **kabir:** K-2c.
- **priya:** K-3 round 4 re-check. (CI-1 is out of this commit.)
- **priya:** U-2 last call, after kabir.
- **Exclude as well:** CI-1 (`ci/stale-comment-check.py`, `.github/workflows/frontend-checks.yml`, `.proof-os/gates/f0150-prompt-version-exempt.txt`, `.proof-os/gates/F-0767-F-0768-prompt-version-ci.py`).
- **Keep:** `ai-tests.yml`, which is K-3.

**Exclusions (U-7 and other lanes):**
- `src/components/creator/MeeraSettingsSection.tsx` (all 4 hunks are U-7) and `MeeraSettingsSection.saved-briefs.test.tsx`.
- The `creatorBriefs` stubs in `src/pages/creator-settings-{change-password,connected-accounts,logout,phone-409}.test.tsx`.
- `U7-caseB-v3.patch`.
- `src/lib/api.ts`: stage a blob without the `creatorBriefs.delete` block (~L7092-7105). `list` and `BriefListItem` are U-2 and stay; they were present before U-7 started.
- The other session's brief_extract files (`influora-ai/app/routes/brief_extract.py`, `influora-ai/app/prompt/brief_extract.py` and their tests) belong to its lane.

**PRE-COMMIT CHECKLIST for Wave U (arjun, before any `git add`):**
0. **Stage every UNTRACKED file the change depends on**: `RiskFlagCorpusTest.java`, `OffPlatformPaymentRuleTest.java`, `influora-api/src/test/resources/risk-corpus/`, the new `BriefFallbackExtractorRealRiskRulesTest.java`, `GetBriefExecutor(.java|Test.java)`, `influora-ai/tests/clients/`, `test_k3_dto_field_classification_drift.py`, the new `src/` tests, and the task docs the TSV generators read (`NISHA-RISK-CORPUS-0917.md`, `NISHA-COMPLIANCE-ROWS-0918.md`).
   - **Why:** kabir flagged that the guard tests are untracked. A commit without them ships the rule with every local gate still green.
   - **How to verify:** build from `git archive` of the index, which catches a missing file.
1. Grep the worktree for `SABOTAGE`, `FALSIFY`, `FALSIFICATION TEMP` and `MUTANT`. Zero hits allowed outside docs. From now on every agent's falsification must carry one of those markers, so leftovers are findable.
2. Confirm no scratch files in `src/` or `influora-api/src/test/java/scratchpad`, and no stray `*.patch` or `*-scratch.*` staged.
3. Exclude U-7: `SavedBriefsSection` in `MeeraSettingsSection.tsx`, `MeeraSettingsSection.saved-briefs.test.tsx`, the `creatorBriefs.list`/`delete`/`BriefListItem` hunks in `api.ts` (check whether `list` is also used by Wave U code before excluding it), the `creatorBriefs` stubs in 4 settings tests, and `U7-caseB-v3.patch`.
4. Compile a `git archive` of the index (not the working tree) and run the suites against it.
5. Stale-comment gate green on the staged tree, run with `--since origin/main --event push` exactly as CI will run it, not `--since HEAD`.
6. Commit every task document that a committed file cites. At minimum `MEERA-CI1-PROOF-0917.md` and `PRIYA-LASTCALL-CI1-0917.md` (cited by `frontend-checks.yml`); otherwise rule 4 fails the push.
7. PROMPT_VERSION: `.4` covers the get_brief description change and the K-3 persona change. If they are split across commits, the first needs `.3`.
8. Run `.proof-os/gates/F-0765-F-0766-risk-corpus.sh` and `.proof-os/gates/F-0767-F-0768-prompt-version-ci.py` on the staged tree.

**COMMIT RULE for Wave U (arjun): the U-7 saved-briefs UI must NOT go into the Wave U commit.** It is already in the tree: `SavedBriefsSection` in `MeeraSettingsSection.tsx`, `api.creatorBriefs.delete`, its test, and `creatorBriefs` stubs in 4 settings test files. The backend DELETE route doesn't exist. A 404 on delete silently removes the row, so shipping the UI alone would show erasure that never happened. Kavya is listing the exact files and hunks, and the api.ts hunks will need partial staging.

Owners: vikram builds; kavya QA; **kabir + priya** last call. Vikram's order: KC-1/2/3 report → K-2b → CI-1 → U-7 backend.

Owed after S-2 for U-3 and U-5 (meera): a hidden flag stays hidden in that deal's room for the session; with site data blocked the deal room still renders; Ask Meera fills `Look at brief {id}.` without sending, and Send brings back that brief's card.
Follow-ups from Priya, not blocking (ananya): N1, a test for storage failing on write only; N2, two stale "always appends" comments in `MeeraCopilotChat.tsx`; N3, the chat Send button has no accessible name (WCAG 4.1.2).

**U-6 words: kabir APPROVE** (`KABIR-CONSENT-0917.md` "Last call — U-6 words"). U-6 is not closed; three conditions remain:
- **G-1, a release gate, not a build item.** The backend `CURRENT_CONSENT_VERSION = "v2"` and the new consent text go out in the **same deploy**, never one alone. If the text ships on a v1 backend, consented creators paste without seeing it. If the backend moves to v2 with the old text, everyone re-consents to a notice that lacks it. Proof at deploy: a v1-stamped account gets `consent_accepted: false` and sees all three paragraphs. Owner: meera, at S-2.
- **G-2 (ananya).** The consent test must assert the whole body, not substrings.
- **G-3 (ananya, then arjun's visual check).** The dialog can't scroll, and the taller Hindi notice may clip Accept on a 375px phone.
  - **Built:** ananya added `max-h-[calc(100dvh-2rem)] overflow-y-auto`.
  - **Visual check (arjun, real browser, vite dev server run from the b0 worktree, mock mode), hi-IN at 375×553:** the dialog is 470px tall under a 521.6px max. The title, all three paragraphs, Accept and Not now are on screen with no clipping; screenshot taken.
  - **At 200% root font:** the dialog scrolls internally (980px of content in 488px), and Accept and Not now are reachable by scrolling it.
  - **Caveat:** at 200% the page itself overflows horizontally, which makes viewport coordinates imprecise. That overflow predates B0 and is app-wide.
  - **Found on the way:** a vite server started from another folder does not generate Tailwind classes that exist only in this worktree. The first run showed `max-height: none`, which was a false failure.
  - **Kabir final, G-2/G-3: APPROVE.**
    - The Hindi exact-equality check is as strict as the English one (both bodies match byte for byte).
    - The close button scrolling away pushes no one toward Accept: Not now always sits next to Accept, and Escape and a backdrop tap both decline.
    - Residuals, LOW: Accept is solid and Not now is ghost-styled (unchanged since v1), and a mouse user loses the close button at high zoom.
  - **U-6 now waits only on priya's build last call.** That follows Kavya's batch-1 review of the backend v2 constant. G-1 stays a release gate.
  - **Side effect ananya flagged:** the close (X) button now scrolls with the content instead of staying pinned. It matters only when the notice overflows.

Owed on U-4 and not a build item: Swapnil decides whether to regenerate the 12 recordings on the paid voice service. Until then those four beats play silent.

**K-3, built by vikram** (brand-written tool-result content wrapped in the model's copy only, for get_brief, check_deal_risks and get_my_deals): **kabir APPROVE WITH CHANGES**.
- **KC-1:** invert to an allow-list of trusted keys, so unknown future fields are wrapped. A probe showed new fields landing outside the wrapper.
- **KC-2:** a persona sentence letting Meera still name the brand to her creator. Live check after S-2: a blocked brand gets named.
- **KC-3:** PROMPT_VERSION → `.4` in the same change. Held until kavya's CI-gate review.
- **Also:** delete or rewrite a decorative falsification test.
- **Next:** priya must pass K-3 before D-1.

Kavya's batch-1 review, round 1: A1, A2, A3 and C pass. B and D were marked "deferred" without any ruling, so they were sent back. Added to B: the CI gate passes vacuously once a branch has bumped PROMPT_VERSION (found by vikram), and a possibly flaky no-retry client test.

**Kavya's batch-1 review, round 2** (`KAVYA-BATCH1-0917.md`):
- **D / K-2: PASS.** Passing null text at the call site turned all three path tests red. Per-path mutations, Hinglish coverage and an 8,000-character load test were deferred again.
- **B: CRITICAL finding.** The gate passes vacuously after a branch's first PROMPT_VERSION bump. Her red/green proof of the gate was also deferred.
- **The no-retry client test is not flaky** by construction.
- **Arjun did the Hinglish check himself** instead of sending her back a third time. With Python `re` approximating Java's, the two non-dismissible keyword rules:
  - **Miss:** every Hinglish and Hindi hide-disclosure phrasing ("ad mat likhna", "विज्ञापन मत लिखना", "Don't tag it as an ad"), plus "platform ke bahar pay karenge" and "pay outside the platform".
  - **False-fire:** "Payment after delivery of the reel" and "Add your UPI ID in your Influora profile" (off-platform), and "no disclosure issues expected" (hidden disclosure).
  - **Why it matters now:** K-2 exposes every paste to these patterns. Gate metric 2 requires under 10% wrong on these two flags.
  - **Next:** kabir, then a **priya ruling**. U-2 depends on it.

| # | Item | Builder | Proves | Last call | Stage |
|---|---|---|---|---|---|
| CI-1 | stale-comment gate: per-commit PROMPT_VERSION rule plus working tree vs HEAD, with a CI checkout that has the history | vikram | **meera** (author and prover kept separate) | **priya** | Built. Vikram's scratch-repo proofs are red/green for a later unbumped commit and an uncommitted unbumped edit; the empty range is not vacuous; a non-ancestor base exits 2; `fetch-depth: 0` added; the real tree is OK. **Meera: NOT PROVED** (`MEERA-CI1-PROOF-0917.md`, every scenario executed). **Held:** reformat without a value change, multi-commit PRs, merge commits via first-parent, renames and deletes, the PR `base.sha` path, the real tree. Unusual syntax fails loudly.
- **Gaps to fix (vikram, CI-1 round 2):**
  - **(1) Reused version passes:** reverting to an older, already-cached value differs from the parent, so it passes. That is the cache-poisoning harm.
  - **(2) Duplicate definition defeats the gate:** `re.search` takes the first match while Python uses the last. A decoy bump went green while the runtime version was stale.
  - **(3) First push of a new branch:** `event.before` is forty zeros, so the gate falls back to `HEAD~1` and checks only the tip commit.
- **Noise:** CRLF-only edits go red.

Still blocks the Wave U commit. |
| RX-1 | Keyword rules for off-platform payment and hidden disclosure on pasted text | per priya's ruling | kavya | **priya** after **kabir** | Kabir done (K-2 APPROVE WITH CHANGES: real Java 21 shows 9 false positives and 15 misses. Fix before U-2 goes live; merging not blocked). Priya ruling queued |

### 2026-09-18, run under proof-os `/work`

- **Ledger:** F-0765 (proximity), F-0766 (untested HIDE_TEXT branches), F-0767 (force-push passes the CI gate), F-0768 (4 unbumped historical commits).
- **Confirm:** admissible, scope 9 files.
- **Isolation, recorded from now on:** checkers get the artifact paths and the verbatim done_when only. Earlier reviews today were separate agent invocations but carried the builder's report, so they were weaker than fresh-context.
- **KB5 built** (vikram, across two interrupted sessions).
  - A wallet name is replaced by a single private-use token, and a request word must be within 6 tokens of it.
  - 6 HIDE_TEXT rows added.
  - Maven 3121 / 0 / 0 / 25, compiled 11:00, verified by arjun.
  - Red-when-removed is unproven, so **kabir** is checking it (fresh-context).
- **K-3 low items built** (vikram).
  - Probe keys land inside the wrapper, the persona clause is asserted, the quote comment was already there, and deals are keyed by index. Each was shown red first. pytest 959.
  - **Next:** **priya** K-3 last call (fresh-context) before D-1.
- **CI-1 round 3 built** (vikram). Proven in scratch repos:
  - the `HEAD~1` fallback is removed;
  - a base equal to HEAD on a non-PR event is loud;
  - the exemption file `.proof-os/gates/f0150-prompt-version-exempt.txt` takes the Wave U first push from 4 findings to 0, and a fifth unlisted commit, a removed SHA or a garbage line each go red or loud;
  - cross-branch reuse is checked across all `origin/*` heads, and a checkout with no origin refs is loud;
  - the workflow fetches all heads and passes `--event`.
  - **Two real bugs found by running against a real clone:** (1) the `origin/*` glob skipped every branch name containing a slash; (2) a pushed branch matched its own fresh value as "reused".
  - **Arjun's brief was wrong:** it said a listed SHA not in range should fail. Priya's ruling says it is ignored, and vikram followed the ruling. Correct: otherwise every push after Wave U would stay red.
  - **Timing:** rule 3 took 4-6 s, with one 10.7 s outlier on this Windows box.
  - **Meera, fresh-context: all 10 done_when clauses MET** (`MEERA-CI1R3-PROOF-0918.md`). Built from this repo's real 44-commit history with a real bare origin and `--no-local` clones. Rule 3 took 3.8–5.2 s over 15 runs.
  - **Meera finding 1, blocks the push:** the workflow's new comments cite `MEERA-CI1-PROOF-0917.md` and `PRIYA-LASTCALL-CI1-0917.md`, which are untracked, so rule 4 fails a real push. Both must be committed with Wave U. Added to the pre-commit checklist.
  - **Meera finding 2 → F-0772:** a duplicate SHA in the exemption file is accepted silently. LOW; vikram.
  - **Gate:** arjun wrote `.proof-os/gates/F-0767-F-0768-prompt-version-ci.py`.
    - **Part A:** the force-push scenario in a temp repo must go red naming the unbumped commit, with a control that a normal bumped push goes green.
    - **Part B:** rule 3 on real `origin/main..HEAD` must be clean with the exemptions, and must name exactly the 4 SHAs without them.
    - **Falsified** by putting the `HEAD~1` fallback back → exit 1, "F-0767 regressed".
  - **Restore incident:** the falsify/restore went through Python text mode and turned 967 CRLF endings into LF (content equal, sha256 changed). The exact bytes were recovered from meera's verbatim scratch copy (sha256 `916cad25…`, the value priya recorded) and the gate re-ran green. Saved to memory.
  - **Closed:** **F-0767 and F-0768 via promote.py.** Priya's re-check of CI-1 is still owed as the last call.
- **KB5 closed.** kabir's fresh-context check found both done_whens MET (`KABIR-KB5-CHECK-0918.md`): every fix removed turned its row red, and each HIDE_TEXT branch deleted went red on its own row.
  - **Gate:** arjun wrote `.proof-os/gates/F-0765-F-0766-risk-corpus.sh`. It runs `clean`, checks the guarding rows and `PAIRING_WINDOW` exist, and needs tests > 0.
  - **Falsified twice:** window 13 → exit 1 with "Expecting empty but was: [KAB5-N-send-draft, KAB5-N-send-files]"; guard row renamed → exit 1. Restored by sha256 and rebuilt green (16/0/0).
  - **Closed:** **F-0765 and F-0766 via promote.py.**
- **New, F-0769 (MEDIUM, kabir):** the 6-token window counts straight through a sentence end. "Draft bhej do kal tak. UPI se payout Influora pe aayega." still raises a flag she cannot dismiss; 6 of 6 short on-platform briefs tried still flag. Needs a **priya** ruling (do not pair across a sentence end? "Rs. 5,000" splits on the dot).
  - **Kabir's LOW notes, also for priya:** the corpus pins the window only between 3 and 12; two long-winded real asks are now missed (for Nisha's fresh blind set); single untested words inside the Hinglish and Devanagari branches; the must-catch tests stop at the first missing row.
- **K-3, priya last call (fresh-context): NOT MET** (`PRIYA-LASTCALL-K3-0918.md`).
  - **Met:** the wrapper (12 mutants red), the persona (5 red), PROMPT_VERSION `.4` never committed (14 historical values checked).
  - **F-0770 HIGH:** the tests compare Spring's object to itself, so in-place mutation is undetected. Test-only fix: a deep copy.
  - **F-0771 MEDIUM, latent:** unknown nested fields inside trusted containers, and a non-list `deals`, stay outside the wrapper. Needs a drift test against the Java records.
  - **Both with vikram now** (pytest only).
  - **Before D-1:** `draft_reply` and every other tool outside the three are unwrapped, so D-1's done_when must put `draft_reply` behind the wrapper or make wrapping the default. **kabir** picks.
  - **Commit plan:** `.4` covers both the get_brief description change and the persona change. If split into two commits, the first needs `.3` (still free).
  - **Priya found:** PROMPT_VERSION is guarded only by the CI gate; pytest stays green on a revert.
  - **Arjun's error:** the K-3 artifact list named `app/security/untrusted.py`; the real path is `app/prompt/untrusted.py`.
- **Priya round 6** (`RULINGS-U-0917.md` L632+), measured in a Java 21 probe on verbatim copies of the code:
  - **F-0769 rule:** a wallet name and a request word pair only inside one sentence, still within 6 tokens.
    - **Sentence ends:** a run of `. ! ? … । ॥` followed by a space or the end of text, a blank line, or a line break that starts a list item.
    - **Not an end:** a single dot followed by a digit or currency sign ("Rs. 5,000"), or a dot after a short fixed abbreviation list. A single line break is not an end either.
    - **Measured:** all 6 of Kabir's short briefs stop flagging; 13/13 real asks containing a dot still flag (the naive split lost 7); no loss on Nisha's blind rows or the must-catch rows.
    - **Cost:** a real ask split across two sentences is left to the model hint.
  - **Blocks U-2's last call.**
  - **LOW notes:**
    - (a) Pin the window in its own test, with the distances written as the literals 6 and 7.
    - (b) The two long-winded asks become report-only rows, not in Nisha's blind set.
    - (c) Nisha writes one natural sentence per untested word, or the word is pruned. Prune the Hinglish `#ad` now; keep the Devanagari `#ad`.
    - (d) Report every missing row, not just the first.
- **New, F-0773:** on the AI-down or cap path, `BriefFallbackExtractor` sets both risk hints from its old patterns (bare "upi" sets the off-platform hint at L103-104; "TECNO #ad" sets the hide hint), so rounds 4, 5 and 6 are void there.
  - **Ruling:** on that path both hints are false and the two summary lines are dropped; the rules' own text checks still run.
  - `BriefFallbackExtractorTest` L108 must be rewritten.
- **F-0770 and F-0771 built** (vikram). pytest 967 = 959 + 8 new drift tests, 2 clean full runs.
  - **F-0770:** a deep-copy snapshot is taken before the run and compared after, in all 4 browser-copy tests. Priya's B1, B4, B5 and B6b are now red.
  - **F-0771:** nested allow-lists for `PackageQuote`, `QuoteLine` and `AddOnLine`. An unknown key anywhere in `quote` wraps the whole container. A non-list `deals` and non-dict deals are wrapped. A new drift test parses `CreatorToolDtos.java` and was shown red on a scratch copy carrying a fake field.
  - **Minor:** the comment and the citation are fixed.
  - **Next:** **priya** re-check (fresh-context).
  - **Priya re-check: NOT MET** (`PRIYA-LASTCALL-K3-RECHECK-0918.md`).
    - **F-0770:** the 4 original mutants are now red, but 5 others stay green. The L186 pass-through test still compares Spring's object to itself; worst case, the creator's card gets `{}`. `sort_keys=True` also hides an in-place key reorder.
    - **F-0771:** a dict or list nested under any trusted field still reaches the model outside the wrapper (9 probes, latent). All six ways of removing the fix leave the suite green, because the proof was a scratch script, not a test.
    - **Met:** persona, `.4`.
    - **Vikram (a second instance, Python only) now fixing:** scalars only in trusted fields, probes turned into tests, L186 snapshot, drop `sort_keys`, `ai-tests.yml` triggers on the Java DTO path, two comments.
    - **Carried into D-1's done_when:** the drift test must parse every record field and its type, not only `@JsonProperty` (`DealTermsDto` is unannotated and has a brand-written `exclusivityBrands`); an unlisted tool is trusted by default; the `.4` split-commit note; the brief-delete condition.
- **"K-2c" (one commit, since all of it touches `RiskFlagCorpusTest`):**
  - **vikram builds**, after F-0770 and F-0771: F-0769, F-0773, and LOW (a), (c), (d).
  - **nisha** wrote the (c) rows (`NISHA-HIDE-WORD-ROWS-0918.md`). The ruling lists **7** alternatives, not 6: `sponsored` has a Hinglish and a Devanagari branch. Each got a FLAG and a NO_FLAG sentence; none needed `CANNOT`, so none is pruned. She read the ruling, so these rows are **non-blind** and must be tagged that way.
  - **Proof:** each listed mutation shown red. Per the reviewer-defers memory this goes to **kabir**, not kavya.
  - **Last call:** kabir + priya.
- **Recurrence check:** BLOCK on `dead-metric` (×5) and `doc-stale-doc-claim` (×11). Both are other lanes' classes, not this task's. `ci-gate-inert` is REPEAT ×2 (F-0763, F-0767); F-0767 closes on the CI-1 round-3 gate.

**Build order.** Vikram, batch 1: U-1 test gaps + CI gap + consent v2 constant + K-2 → kavya/kabir/priya. Then U-7 backend, then K-3. Ananya: F6 fix → U-6 consent screen (Case A, once Nisha finalises) → U-7 UI against a mocked client.

| # | Item | Builder | Last call | Stage |
|---|---|---|---|---|
| U-6 | Consent paragraph Case A, en + hi; `CURRENT_CONSENT_VERSION` v1 → v2 and the `api.ts` mock in the same change | nisha (words), ananya (screen), vikram (constant) | **kabir** words, **priya** build | Nisha finalising |
| U-7 | Brief delete: route (vikram), saved-briefs table and Case B + v3 (ananya) | vikram, ananya | **kabir** | Queued; blocks go-live |
| — | `creator_schemas.py` not in PROMPT_SOURCES, so tool-description changes never force a version bump | to be ticketed | **priya** | Raised by Kavya |

**Swapnil:** Kabir rules that B0 cannot go live to real creators while a pasted brief has no erase path (merging and staging are fine). U-7 is assigned so that holds by default. Say so if you would rather ship without it: the risk gets recorded, the notice uses Case A, and requests to info@influora.in are handled by hand.

Nothing in Wave U closes until Priya (U-1, U-2, U-3, U-5), Nisha (U-4) and Kabir (U-6) sign off on the fixed code. Priya will not pass U-2 until the consent change is in the branch.

---

## Wave D — drafts. ASSIGNED, starts after Wave U closes.

| # | Item | Builder | Reviewed by | Last call |
|---|---|---|---|---|
| D-1 | `DraftReplyExecutor` | vikram | kavya → meera | **priya** |
| D-2 | `CreatorMeeraDraftController` | vikram | kavya → meera | **priya** |
| D-3 | Brand-visible metadata split (the helper at three viewer-facing points) | vikram | kabir | **kabir** |
| D-4 | `DraftCard` and the approve flow | ananya | kavya → meera | **priya** |
| D-5 | Brand-chat Meera label | ananya | kabir | **kabir** |

D-3 and D-5 carry a security last call, because they decide what a brand sees on a message Meera wrote.

---

## Wave C — config and cost. ASSIGNED.

| # | Item | Builder | Last call |
|---|---|---|---|
| C-1 | Raise the creator cap from $0.75 to $2.00; add the reset date to the cap error | meera | **rohan** |
| C-2 | Four medium audit defects: malformed-quote render, blank deal rows, risk pricing audit row, rate-limit bucket collapse | vikram, ananya | **kavya** |

---

## Owed to Swapnil — nothing below moves without you

| # | Item | Last call |
|---|---|---|
| S-1 | **Push the branch.** 9 commits on no remote | **swapnil** |
| S-2 | **Deploy Phase A** and run the live smoke. Blocks all measurement and every database-dependent proof | **swapnil** |
| S-3 | The seven open rulings in `PENDING-0912.md` | **swapnil** |
| S-4 | B1 go or no-go, after B0's live metrics | **swapnil** |

---

## Phase B1 — assigned, deliberately not started

Gated on B0's live metrics by your own ruling. Assigning now so ownership is settled, not so work begins.

| Item | Builder | Last call |
|---|---|---|
| Secure links, the strip, and the redeem flow | vikram | **kabir** |
| Routine-reply service and the delayed send job | vikram | **kabir** |
| Media kit | vikram, ananya | **priya** |
| Campaign fit | vikram | **priya** |
| The secure-link page with the brand email claim | ananya | **kabir** |

Every B1 item that puts creator data in front of a brand has a security last call.

---

## Owed before Wave D, not a build item

Priya and Kavya signed off on Phase 1 code that then changed underneath them during the repair. They re-check it before Wave D starts.
