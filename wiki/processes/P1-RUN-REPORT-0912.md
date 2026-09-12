# P1 Brand-AI Defect Run — Report

**Arjun Kapoor, Engineering Lead** · 2026-09-12
**Run:** `wf_fd8a1bdd-641` · 13 agents, 5 phases, 0 agent errors
**Done_when:** "P1 customer-visible defects solved, checked by tester"

# VERDICT: FAIL — DO NOT PUSH AS-IS

Security: **BLOCK** (1 Critical). Java suite: **3 errors**. Frontend: **1 failure**.
Nothing has been committed, staged or reverted. The working tree holds everything.

---

## Per-defect ruling (tester, independent, evidence-backed)

| ID | Defect | Verdict | Evidence |
|---|---|---|---|
| **P1-5** | analytics tool uncallable | ✅ **FIXED** | `campaign_id` added to both records; consumer verified at `assembler.py:310,361`. Falsification is structural: Jackson serializes only record components, so the pre-fix 3-component record *cannot* emit the key. Seam test run: 4/4 green |
| **P1-8** | UI discarded `analyze_site` | ✅ **FIXED** | Added to `MEERA_FUNCTION_CALLS` + renderer + nested-error branch. 8 tests pass, 7 confirmed RED pre-fix |
| **P1-11** | template dropped fields | ✅ **FIXED** | Template values now pushed through the same allow-lists; money/date fields still untouched. 13/13 green, 2 confirmed RED pre-fix |
| **P1-9** | stale comment | ✅ **FIXED** | New comment checked against `schemas.py:448-452` rather than taken on trust |
| **P1-13** | invisible site analysis | ✅ FIXED (residual) | Wall-clock deadline replaces poll count, raises `analysisTimedOut`; terminal card wins over spinner. 13/19 RED pre-fix. **Residual:** a first-time signup still never sees the result — the readout needs a saved profile that exists only after the step unmounts |
| **P1-14** | unbounded history | ✅ FIXED (residual) | No-cursor branch trims to 100, cursor path untouched. **Residual:** still materialises the whole conversation before trimming — only the response is bounded |
| **P1-7** | dead canvas stages | ⚠️ **PARTIAL** | Money tools confirmed still unreachable (3 ways). Chat column is now honest. **But `src/data/stage-config.ts` is untouched** — the rail still declares triggers that can never fire |
| **P1-6** | credit burned on failure | ⚠️ **PARTIAL → Python good, Java is a REGRESSION** | See below |

---

## The Critical: P1-6's Java sweep reopens a hole Kabir deliberately closed

`MeeraSessionService.releaseStaleUnansweredTurn` (`:392-433`) refunds any turn whose newest row is
a USER message >15 min old that was charged with no writeback row.

**"No writeback row" is not a "nothing was delivered" signal.** Two ways to be in that state with the
reply already delivered:

1. **The deliberate disconnect path.** `chat.py:949-958` returns on `disconnected` *before*
   `persist_assistant_message` and intentionally never releases — that omission **is** Kabir's fix.
   Read every SSE token, abort, wait 15 minutes, send again: the sweep hands the credit back.
2. **Not in the security report — the tester found this one.** `chat.py:1007-1024`
   `if tool_result_delivered:` keeps the charge and returns with nothing persisted. So **every
   ordinary tool-result-only turn** (a `show_creators` or `calculate_budget` answered without
   narration) is silently refunded on the brand's next send.

The three claimed guards cover neither case. Worse, the new tests **encode the vulnerable
behaviour** — `testSendTurnReleasesPriorTurnThatWasChargedAndNeverAnswered` asserts the refund — and
no test asserts that a delivered-then-disconnected turn stays charged.

**This is my brief's fault.** I offered the session lane two designs ("charge on first token, or
guarantee release on every terminal path") without telling it that the missing release on the
disconnect path was a *deliberate security fix*. The agent could not see that and chose the design
that undoes it.

The Python half is genuinely good and independently shippable: `release_early()` wired to five
terminal paths, correctly guarded on a server-minted `messageId` so a client can never name a turn.

---

## Other findings that must not be lost

**Five new test files are UNTRACKED.** A push today ships these fixes with **no tests at all**.
One of them is the *only* test covering P1-5.
```
src/components/brand/onboarding/__tests__/onboarding-steps.site-analysis.test.tsx
src/components/feature/meera/StageSnapshot.site-analysis.test.tsx
src/components/feature/meera/__tests__/MeeraChatPanel.test.tsx
src/hooks/useBrandProfile.poll-budget.test.tsx
influora-api/src/test/java/com/influora/service/meera/MeeraContextCampaignIdSeamTest.java
```

**Four new tests pass against BOTH pre- and post-fix code** — they prove nothing and must not be
cited: `testListMessagesWithAfterCursorIsNotCapped`,
`testSendTurnCreatorDoesNotSweepBrandCreditLedger`,
`test_early_release_never_fires_on_a_client_supplied_turn_id`,
`test_early_release_never_fires_on_a_creator_turn`.

**Two red suites, both broken test tooling rather than broken product code.**
3 Java errors — `MeeraSessionServiceTest.userTurnAged:884` nests `lenient().when()` inside an
incomplete outer `when(...)`, so none of the three P1-6 tests executes a single assertion.
1 frontend failure — `StageSnapshot.site-analysis.test.tsx` lacks the local `IntersectionObserver`
jsdom stub every other framer-motion test file here carries.

**MEDIUM:** `CreateCampaignExecutor.filterAllowed:547-557` uses default-locale `toUpperCase()` on
lowercase SYSTEM template rows. Under a Turkish/Azeri locale every value silently drops. Fails
closed. Needs `Locale.ROOT`.

**`Skipped: 19`** — the 5 Testcontainers classes never ran locally. Not a pass.

---

## My orchestration bug, and the correction

I gave five lanes **one shared working tree** and told each reviewer to review "the changes in lane
X". A tree-wide `git diff` shows every lane at once, so a reviewer raised a **CRITICAL LANE
VIOLATION** and ordered `StageSnapshot.tsx` and `useBrandProfile.ts` reverted.

The tester checked independently and overruled it: every lane stayed inside its own files, the
backend diffs partition cleanly by defect, and `public/site-tags.js` + `RouteAnalytics.tsx` are
**staged in the index** — which no lane agent could have done, because all were forbidden `git add`.
A third concurrent session is working in this repo.

**Executing that revert would have deleted the entire P1-13 fix** and re-broken a defect that 13
falsified tests now cover.

---

## What I recommend

1. **Do not push today.** Security is BLOCK and two suites are red.
2. **Revert only the Java sweep** (`MeeraSessionService.releaseStaleUnansweredTurn` and its three
   tests). Keep the Python half — it is the actual fix for the defect Swapnil cares about.
3. **Redesign P1-6's server side** around a positive terminal marker written by Python at stream
   end (delivered vs disconnected), never on absence of a writeback row, and never firing on the
   `tool_result_delivered` path.
4. **Stage the five test files** before any push.
5. Fix the two test-tooling breaks — both are small and neither is a product bug.
6. **P1-5, P1-8, P1-11 are clean, green and falsified.** If something must ship today, it is these
   three, with their tests staged, and nothing else.

## Still needs a human

- **P1-10** (matching) — blocked on Swapnil's copy-vs-algorithm ruling. Never assigned; not failed.
- **P1-12** (budget vs rate band) — needs Priya. Changes numbers shown to brands.
- **P1-7's remaining half** is a product decision, not a lane fix: delete the two canvas stages,
  mark them "your step", or drive them from campaign/escrow status? Three different products.
  Needs a ruling before anyone edits `stage-config.ts`.
- **The conversation lifecycle behind P1-14** — nothing in `src/main` ever writes any
  `ConversationStatus` but `ACTIVE`. When does a brand's thread end?

*Arjun Kapoor. Nothing committed, staged or reverted — the tree is exactly as the run left it.*

---

# ACTIONED 2026-09-12 — Swapnil ruling: "revert the Java sweep and ship those three"

**Shipped as commit `bee93b8`** (9 files, +1146/-23), NOT pushed.

| Shipped | Test evidence |
|---|---|
| P1-5 campaign id in Meera context | `MeeraContextCampaignIdSeamTest` 4/4 |
| P1-8 + P1-9 analyze_site rendered + comment corrected | `MeeraChatPanel.test.tsx` 8/8 |
| P1-11 template no longer thins the draft | `CreateCampaignExecutorTest` 13/13 |
| (rides along) P1-7 chat half — wallet human-step routing | same file, same suite |

Post-change verification, run by me: `mvn -o clean compile` exit 0; targeted suite
**52 tests, 0 failures, 0 errors** (the 3 pre-existing errors are gone with the sweep's tests);
`MeeraChatPanel.test.tsx` 8/8 green. `graphify update .` run.

**The sweep was never committed**, so "revert" meant removing it from the working tree, which is
done surgically — `releaseStaleUnansweredTurn`, `UNANSWERED_TURN_GRACE`, the call site, the three
broken tests and two now-unused `Duration` imports. **P1-14's history cap survives in the same
file**, which is why a file-level `git checkout` was not used. A comment block at
`MeeraSessionService.java:85` records why the sweep was rejected and what a correct rebuild needs.

**Subset-build check before committing** (the F-0324 trap): every new import in the committed files
— `react-router-dom`, `lucide-react`, `ToolResultRenderer`/`ToolResultWrapper`, `cssVars`,
`formatINR` — resolves to a file already in HEAD and clean in the tree. The commit is self-contained.

**Not swept in:** `public/site-tags.js` and `src/components/site/RouteAnalytics.tsx` were staged in
the index by the concurrent third session. `git commit -- <explicit paths>` was used so they remain
staged and uncommitted, exactly as that session left them.

**Still uncommitted in the tree, pending their own review:** P1-6's Python half (good, wants a
positive terminal marker on the server side before a backstop returns), P1-13 (3 untracked tests,
one of which needs an `IntersectionObserver` jsdom stub), P1-14 (capped, but still materialises the
whole thread before trimming).
