# Priya last call: U-3 (hiding risk flags for the session) and U-5 ("Ask Meera about this brief")

**From:** Priya (CTO)
**To:** Arjun. Builder: ananya. QA: kavya. Copy: nisha, meera
**Date:** 2026-09-17, written 15:45
**Tree:** `influora-b0`, branch `feat/meera-creator-phase-b0`, uncommitted on `df20091`
**Bar applied:** `RULINGS-U-0917.md`, "Last-call bar": U-3 items 1-8, and U-5's six "Behaviour the frontend tests must prove", as written. Every guard test must be shown failing against a wrong version first.

## Verdicts

| Item | Verdict | Why, in one line |
|---|---|---|
| **U-3** | **FAIL** | The code does all eight things, but bar items 2, 3 and 4 have no test. A wrong version of each passes all 74 targeted tests, and grep finds nothing elsewhere in `src/` that would catch it. |
| **U-5** | **FAIL** | Five of the six behaviours are proven. Item 4's "Decline drops the prompt" is not: its test takes a path that never reads the prompt, so a version that keeps the prompt passes. |

Neither fix needs a source change. U-3 needs one new test file plus one renderer case. U-5 needs one test changed. Kavya's PASS covered U-3 item 1 only. For U-5 it rested on reading the code, with no falsification.

---

## How this was checked

- I read every file the coordinator named, plus `CreatorToolResultRenderer.tsx`, `ConsentScreen.tsx` and `DealRiskServiceTest.java`. I changed nothing in `influora-api/` or `influora-ai/` and did not run Maven. No stash, no commit.
- **Baseline** before any mutation, on the eight test files that cover both items: `Test Files 8 passed (8)`, `Tests 74 passed (74)`. Per file:
  - `deal-risk-card.test.tsx` 7
  - `CreatorToolResultRenderer.test.tsx` 31
  - `PasteBriefCard.test.tsx` 12
  - `creator-copilot-feature-disabled.test.tsx` 3
  - `MeeraCopilotChat.test.tsx` 6
  - `creator-copilot-meera-consent.test.tsx` 2
  - `creator-copilot-paste-brief.test.tsx` 7
  - `creator-chat.risks.test.tsx` 6
- **Eight wrong versions, in four runs.** Before each run I backed up the file byte for byte. Afterwards I restored it and checked its sha256.
- **Restoration:** all five source files I changed match their starting sha256:
  - `useRiskFlagDismissals.ts` `af5070ce…`
  - `creator-chat.tsx` `a5c836a9…`
  - `CreatorToolResultRenderer.tsx` `31bc9724…`
  - `MeeraCopilotChat.tsx` `502ed59c…`
  - `creator-copilot.tsx` `a0468b1e…`

  `grep -rn FALSIFY src` → 0. The longest window any wrong version stayed on disk was about 70 seconds (15:33-15:35).
  The overall `git diff -- src` hash still moved during my run, from `5e5c1bf0…` to `2245cef8…`. The only other files changed after 15:31 are `src/components/meera/ConsentScreen.tsx` and `ConsentScreen.test.tsx`. That is Ananya's U-6 work, not mine.
- **Final runs on the restored tree:** see "Final tool runs" at the end.

## Falsifications

| # | Wrong version | Result | Red line, quoted |
|---|---|---|---|
| U3-D | `creator-chat.tsx` L1015 scope `DEAL:` → `deal:` | **RED** | `creator-chat.risks.test.tsx:327` `expected '{"deal:deal_1":["EXCLUSIVITY_LONG"]}' to be '{"DEAL:deal_1":["EXCLUSIVITY_LONG"]}'` |
| U3-E | `CreatorToolResultRenderer.tsx` L674 scope `${target}:${target_id}` → `${target_id}` | **RED** | `CreatorToolResultRenderer.test.tsx:339` `expected '{"d9":["USAGE_LONG"]}' to be '{"DEAL:d9":["USAGE_LONG"]}'` |
| U5-B | `MeeraCopilotChat.tsx` L253: prefill overwrites the draft instead of appending | **RED**, 2 failed / 11 passed | `MeeraCopilotChat.test.tsx:222` expected `How much did Nykaa pay? Look at brief b_2.`, received `Look at brief b_2.`; `creator-copilot-paste-brief.test.tsx:260` expected `What did my last deal pay? Look at brief brief_u5.`, received `Look at brief brief_u5.` |
| U5-A | `MeeraCopilotChat.tsx`: the auto-send design the ruling rejected. After the prefill, an effect calls the real `handleSend()` once `connecting` is false | **RED**, 5 failed / 8 passed | `creator-copilot-paste-brief.test.tsx:243` `expect(element).toHaveValue(Look at brief brief_u5.)` (the composer had been cleared by the send); the same at `:260` and `:281`; `MeeraCopilotChat.test.tsx:222` and `:251` |
| **U3-A** | `useRiskFlagDismissals.ts` L136 `if (!flag.dismissible) return;` removed, so `dismiss()` accepts a flag that cannot be dismissed | **SURVIVED** | 74 passed |
| **U3-B** | `useRiskFlagDismissals.ts` L73-78 and L88-92: both `try/catch` blocks removed, so a storage failure throws during render | **SURVIVED** | 74 passed |
| **U3-C** | `useRiskFlagDismissals.ts` L121/L134: no scope now falls into one shared `UNSCOPED` bucket, with a live dismiss control | **SURVIVED** | 74 passed |
| **U5-C** | `creator-copilot.tsx` L294 `pendingBriefPromptRef.current = null;` removed from Decline | **SURVIVED** | 74 passed |

- **Survivors are real gaps, not just untested in these eight files.** U3-A, U3-B, U3-C and U5-C were applied together, and grep over every test in `src/` found nothing that could catch any of them:
  - No test makes `sessionStorage` throw. Twelve test files mention it, and none stubs `getItem`/`setItem` to throw (no `spyOn`, `defineProperty` or `throw` against storage).
  - No test imports `useRiskFlagDismissals` directly.
  - Every `check_deal_risks` payload in the tests carries a `target`, at `CreatorToolResultRenderer.test.tsx` L307/328/361 and `MeeraCopilotChat.toolcards.test.tsx` L115.
  - The only copilot-page Decline test is `creator-copilot-paste-brief.test.tsx` L284.
- **I did not run the whole vitest suite against the survivors.** That would have kept wrong versions on disk for about four minutes while Ananya runs vitest on the same tree.
- **A near miss in U5-A.** `MeeraCopilotChat.test.tsx` "fills an empty composer … calls sendTurn zero times" (L180-194) **stayed green** against the auto-send. Its `expect(sendTurnMock).not.toHaveBeenCalled()` at L193 runs before the mocked connect resolves, so it cannot see an async send. Other tests in the same run went red, so U-5 item 2 is still proven. That assertion is not a guard on its own, though. See UF5-2.

---

## U-3 against the bar

| # | Bar item | Status | Evidence |
|---|---|---|---|
| 1 | Non-dismissible flag never hidden, even when seeded in storage; red without `flag.dismissible &&` (L129) | Met | Kavya falsified it: `Unable to find an element with the text: Regulated category`. Test: `CreatorToolResultRenderer.test.tsx` L345-369 |
| 2 | `dismiss()` refuses a non-dismissible flag (L136) | **NOT MET** | U3-A survived. The card never draws a control for such a flag (`deal-risk-card.tsx` L187), so this line only runs on a direct call, and no test makes one |
| 3 | Storage throws on read and write → memory fallback, nothing throws, flags render | **NOT MET** | U3-B survived. The code is right (L73-78, L86-96), but it is unproven. This is a real crash path: with site data blocked, reading `window.sessionStorage` throws `SecurityError` during render, in the deal room and in Meera's chat |
| 4 | No scope → no dismiss control | **NOT MET** | U3-C survived. The card-level test (`deal-risk-card.test.tsx` L69, no `onDismiss`) never goes through the hook. No test renders `check_deal_risks` without a `target`, or `BriefCard` without `riskScope` |
| 5 | Every `DealRiskCard` site wired | Met | Grep, excluding test files: `creator-chat.tsx` L2624 and L2846 (both get `selectedDealRisks.dismiss`/`hiddenCount`/`restore`); `CreatorToolResultRenderer.tsx` L640 (`BriefCard`, hook at L593) and L679 (`DealRisksToolCard`, hook at L676). Four sites, all wired |
| 6 | `DEAL:X` shared between Meera's chat and the deal room | Met | U3-D and U3-E both red: each surface's key is pinned to the same `DEAL:{id}` string in the same store (`STORAGE_KEY`, L37). The chat card builds `${target}:${target_id}` (L672-675) |
| 7 | "hidden for this session" with "Show", never "dismissed" or "removed" | Met | `deal-risk-card.tsx` L217 and L219-226; tests at `deal-risk-card.test.tsx` L97-107 and `creator-chat.risks.test.tsx` L323-325. The only "removed" in these files is `creator-chat.tsx` L1967, about a deal that was not found. LOW, not the bar: the row control's accessible name is still `Dismiss {title}` (L190). "Hide {title} for this session" would match the note |
| 8 | Java test pins the three non-dismissible rules | Met, with a caveat | `DealRiskServiceTest.nonDismissibleFlagsAreTheSpecifiedThree` (L729-736), green in my 14:58 full Maven run. Checked statically: of the 15 `new RiskFlag(` calls across the 14 rules, only `HideDisclosureRule` L45, `OffPlatformPaymentRule` L56 and `RegulatedCategoryRule` L78 pass `false`. **Caveat:** the test filters only the flags that fire in `everythingFires()`, and it asserts only `>= 10` of 14 fire (L716). A rule that does not fire there could turn non-dismissible unnoticed. Ticket for vikram (C-2): assert every rule fires in that fixture, or check each rule on its own. Maven not re-run for this call: Vikram has the tree |

### What has to change before U-3 passes (owner: ananya)

**UF3-1: new `src/hooks/useRiskFlagDismissals.test.ts`, using `renderHook`.**
- **The store is module-level** (`storageUnavailable`, `memoryFallback`, `cachedRaw`, L43-51), and the storage-failure latch never resets. So the storage-failure case must `vi.resetModules()` and import the hook fresh, or it will poison, or be poisoned by, the other cases.
- (a) **Item 2:** call `dismiss({...flag, dismissible: false})`. Assert storage is unchanged and `hiddenCount` is 0. **Red against U3-A.**
- (b) **Item 3:** stub `sessionStorage.getItem` and `setItem` to throw (`vi.spyOn(Storage.prototype, …)`). Assert the hook renders without throwing, the flags are all visible, `dismiss(dismissible)` hides the flag in memory (`hiddenCount` 1), and `restore()` brings it back. **Red against U3-B.**
- (c) **Item 4, hook half:** with `scope === undefined`, `dismiss` and `restore` are `undefined`.

**UF3-2: one case in `CreatorToolResultRenderer.test.tsx`, item 4 through the UI.** A `check_deal_risks` payload with no `target`/`target_id` and one dismissible flag renders no `/^Dismiss /` button. **Red against U3-C.**

**Re-check scope:** kavya runs U3-A, U3-B and U3-C against the new tests, quotes the red lines, then the green file-level counts. Back to me for items 2-4 only.

---

## U-5 against the bar

| # | Behaviour | Status | Evidence |
|---|---|---|---|
| 1 | Button only with `brief_id` **and** a handler | Met | `PasteBriefCard.tsx` L230. Test `PasteBriefCard.test.tsx` L279-300 covers no handler, no result, and both present, and checks the click carries `brief_01` |
| 2 | Consent known, chat closed → composer holds a prompt with the literal id; nothing sent until Send | Met | `creator-copilot.tsx` L176-180. U5-A red at `creator-copilot-paste-brief.test.tsx:243`. The helper (L167-173) waits 20ms, then checks the value, the turn count and `sendTurnMock` |
| 3 | Chat open with typed text → her text stays, prompt appended | Met | `MeeraCopilotChat.tsx` L249-254. U5-B red at `MeeraCopilotChat.test.tsx:222` and `creator-copilot-paste-brief.test.tsx:260` |
| 4 | Consent missing → consent screen; Accept → chat prefilled; Decline → opens nothing **and drops the prompt** | **Partly met** | Screen and Accept: U5-A red at `:281`. Opens nothing on Decline: L297-300. **"Drops the prompt": not proven, U5-C survived.** The test's leak check (L302-307) re-probes with `consent_accepted: true`, so it goes through `openMeera` (L114-117), which never reads `pendingBriefPromptRef`. The stale prompt could only leak through `handleAcceptConsent` (L140-142). That path is Decline → later "Open Meera" while consent is **still missing** → consent screen → Accept. With U5-C in place, that path fills the chat with the brief prompt the creator declined, and no test goes there |
| 5 | `featureDisabled === true` → no card, no button | Met | `creator-copilot.tsx` L274. Test `creator-copilot-paste-brief.test.tsx` L189-196 (not re-falsified; it is U-2's mount test) |
| 6 | Wording: contains the id; promises no drafting or sending; no banned word; hi-IN keyed off `language` | Met | Prompt `Look at brief ${briefId}.` / `Brief ${briefId} dekh lo.` (L176). `PasteBriefCard.test.tsx` L303-320 (no `escrow`, negated draft/send, "you decide") and L322-330 (hi-IN). **Nisha approved it as final** (`NISHA-U4-RECHECK-0917.md` §4) |

### What has to change before U-5 passes (owner: ananya)

**UF5-1 (blocking):** fix the leak test at `creator-copilot-paste-brief.test.tsx` L302-307 so it takes the path where a leak could actually happen:
1. After Decline, stub "Open Meera"'s re-probe as `consent_accepted: false`.
2. Assert the consent screen opens.
3. Click Accept.
4. Assert the chat opens with the composer `''` and `sendTurnMock` not called.

Keep the existing consent-true variant as well. Show it **red against U5-C**.

**UF5-2 (recommended, not blocking):** at `MeeraCopilotChat.test.tsx` L191-193, `await waitFor(() => expect(getHistoryMock).toHaveBeenCalled())` and flush once before asserting `sendTurnMock` was not called. Then that test catches an async auto-send by itself (U5-A), not only through its neighbours.

**LOW, non-blocking:**
- `PasteBriefCard.tsx` L65-67 still calls the wording a "placeholder". Nisha finalised it, so the comment is stale.
- `askMeeraCopy` (L68-76) branches only on `hi`. Nisha already noted a Marathi-preference creator gets English. That is a product-scope call, not B0.
- The Ask button is not disabled while its consent probe runs (L168-195). A fast double-click appends the prompt twice. That is visible and editable, so it is harmless.

**Re-check scope:** kavya runs U5-C against the changed test and quotes the red line. Back to me for item 4 only.

---

## Live checks owed after the Phase A deploy (S-2), one line each

- **U-3 (meera):** on the live stack, hide a dismissible flag on a deal in Meera's `check_deal_risks` card, open that deal's room in the same tab, and confirm the flag is hidden there with "1 flag hidden for this session · Show". Then close the tab, reopen, and confirm it is back.
- **U-3 (meera):** with site data blocked for the domain (Chrome: Settings → Site settings → block), open a deal room that has flags. The page renders, the flags show, and hiding one works until reload.
- **U-5 (meera):** on a live account with consent, paste a brief, tap "Ask Meera about this brief", and confirm the composer holds `Look at brief {id}.`, no turn is sent before tapping Send, and after Send Meera's `get_brief` card shows that brief. Keyboard focus lands somewhere usable after the chat opens (Kavya's U-5-L1).

---

## Final tool runs (restored tree)

Run 15:37-15:44, after every file was restored:
- `npm run typecheck` (tsc): exit 0.
- `npx eslint` on the 13 U-3/U-5 source and test files: `✖ 24 problems (0 errors, 24 warnings)`, exit 0. All are react-hooks and react-refresh warn-level rules (9 `exhaustive-deps`, 10 `set-state-in-effect`, 4 `only-export-components`, 1 `preserve-manual-memoization`). None is on the new hook or on the U-3 lines of `creator-chat.tsx` (L1014-1020). They are warn-level under the react-hooks v7 policy.
- `npx vitest run`, the whole suite: `Test Files 223 passed (223)`, `Tests 1317 passed (1317)`, `Duration 240.18s`, exit 0. No timeout, so no re-run was needed. That is 2 more than Kavya's 1315 because Ananya's tree kept moving.

## Not checked
- U-2 (waits on K-2) and U-6 (waits on Vikram's v1→v2 constant), as the coordinator scoped.
- `DealRiskServiceTest` was not re-run or re-falsified; Vikram has Maven.
- The whole vitest suite was not run against the wrong versions (see Falsifications).


---
---

# Re-check (2026-09-17, 16:07-16:21)

**Scope:** only the items I failed. For U-3 that is bar items 2, 3 and 4. For U-5 it is item 4's "Decline drops the prompt", plus the three non-blocking notes. The coordinator also asked me to confirm that re-asking about the same brief after a send is not blocked. My other passes on U-3 and U-5 stand.
**How:** frontend tools only. I did not touch `influora-api/` (Kavya's Maven) or `influora-ai/` (Vikram).

## Verdicts

| Item | Verdict | Why, in one line |
|---|---|---|
| **U-3** | **PASS** | Items 2, 3 and 4 each now have a test, and every wrong version I built went red. |
| **U-5** | **PASS** | The real leak path is now tested and goes red when Decline keeps the prompt. All three notes are closed. Re-asking the same brief after a send re-fills the composer, and a wrong fix that blocks that goes red. |

## Tree state

- **Baseline** before any mutation, on the nine test files for U-3 and U-5 (the eight from the first call plus the new hook test): `Test Files 9 passed (9)`, `Tests 80 passed (80)`.
- **Restored.** All eight files I backed up or changed match their pre-check sha256. Unchanged since my first call: `useRiskFlagDismissals.ts` `af5070ce…` and `creator-copilot.tsx` `a0468b1e…`. `MeeraCopilotChat.tsx` is now `4a0e8465…`, which is Ananya's (c) change.
- **The two temporary test files are deleted** (copies kept in my scratchpad). A grep of `src` for `FALSIFY` or `PRIYA RE-CHECK (temporary)` → 0.
- **No drift:** `git diff -- src` hashed `12268e07…` before and after, and `git status --short -- src` is identical. Unlike my first call, nobody else changed `src/` during this window.

## Falsifications

Every wrong version was restored from a byte copy, and each file's sha256 was checked after the run.

| # | Wrong version | Result | Red line, quoted |
|---|---|---|---|
| A | `useRiskFlagDismissals.ts` L136 `if (!flag.dismissible) return;` removed | **RED**, 1 failed / 2 passed | `useRiskFlagDismissals.test.ts:64` `AssertionError: expected '{"DEAL:d1":["HIDE_DISCLOSURE"]}' to be null` |
| B-read | Only the **read** `try/catch` (L73-78) removed. Hers removed both; I split them | **RED**, 1 failed / 34 passed | `useRiskFlagDismissals.test.ts:81` `SecurityError: blocked` |
| B-write | Only the **write** `try/catch` (L88-92) removed | **SURVIVED**, 35 passed | See note N1. Outside bar item 3 as written |
| C | No scope falls into a shared `'UNSCOPED'` bucket (L121, L134, L138, L140) | **RED**, 2 failed / 33 passed | `useRiskFlagDismissals.test.ts:102` `AssertionError: expected [Function] to be undefined`; `CreatorToolResultRenderer.test.tsx:389` `expect(element).not.toBeInTheDocument()` (UI half, red on its own) |
| U5C | `creator-copilot.tsx` L294 `pendingBriefPromptRef.current = null;` removed | **RED**, 1 failed / 7 passed | `creator-copilot-paste-brief.test.tsx:347` "Decline drops the prompt: it does not survive a LATER consent round-trip": `expect(element).toHaveValue()`, `Received: Look at brief brief_u5.` |
| U5A | Delayed auto-send after connect (my first-call mutant), run against `MeeraCopilotChat.test.tsx` **alone** | **RED**, 3 failed / 4 passed | `MeeraCopilotChat.test.tsx:199` "fills an empty composer … calls sendTurn zero times": `AssertionError: expected "spy" to not be called at all, but actually been called 1 times`. UF5-2 is closed: that test now catches it by itself. Also red at `:228` and `:257` |
| C-guard | `MeeraCopilotChat.tsx` L260 `if (prev.endsWith(prefillMessage.text)) return prev;` removed | **RED**, 1 failed / 6 passed | `MeeraCopilotChat.test.tsx:298` "a fast double-click … does not append the prompt twice": `expect(element).toHaveValue(Look at brief b_1.)` |
| C-block | A **wrong** double-click fix: skip any prefill whose text equals the last one applied, forever | **RED**, 3 failed / 6 passed | My temporary unit test (prefill → Enter → `sendTurn` called once, composer `''` → token 2, same text): expected `Look at brief b_1.`, received empty. My temporary page test (Ask → Enter → Ask again on the same brief): expected `Look at brief brief_u5.`, received empty. **Ananya's own** `MeeraCopilotChat.test.tsx:270` "re-fires on a new token even with the same text" also went red |

## The re-ask question: confirmed not blocked

- **The guard only looks at the end of the composer.** `MeeraCopilotChat.tsx` L260 skips when the composer ends with the prompt. `handleSend` clears the draft before sending (L283, `setDraft('')`), so after a send the composer is `''`, which does not end with the prompt, and the prompt is filled again.
- **Unit level:** a temporary test on the real code sent the prompt with Enter (`sendTurnMock` called with `('conv_resend', 'Look at brief b_1.', 'creator')`, composer `''`). A new token with the same text then re-filled the composer (`2 passed` together with the page test below).
- **Page level:** a temporary page test did Ask → Enter (a second chat turn appears, composer `''`) → click Ask on the same brief again, and the composer read `Look at brief brief_u5.`.
- **Both go red against C-block,** the wrong fix that would block a deliberate re-ask. So the behaviour is real, not vacuous.
- **Committed tests already guard it.** Ananya's L270 test goes red against the same wrong fix, and clearing the composer by hand before re-asking is the same state as after a send. No new committed test is needed. The two temporary files are deleted.

## Notes (none blocking)

- **N1 (LOW, ananya):**
  - **The gap.** Nothing tests a failure on **write only** (read works, `setItem` throws, e.g. `QuotaExceededError`). In the item-3 test the read fails first and latches `storageUnavailable` (L76), so the write `try/catch` (L88-92) is never reached, and B-write survives. Bar item 3 as written ("throws on both read and write") is met.
  - **What happens without it.** A write-only failure would throw from the Dismiss click handler rather than crash the render.
  - **Suggestion.** Add one case to `useRiskFlagDismissals.test.ts`: `getItem` real, `setItem` throws → `dismiss()` does not throw, and the flag hides from memory. It should go red against B-write.
- **N2 (LOW, ananya):** two comments now contradict the (c) guard:
  - The `prefillMessage` prop doc (`MeeraCopilotChat.tsx` L139-141): "so each request appends once".
  - The effect comment (L246-247): "a second request for the exact same brief still appends (text equality would silently no-op it)".

  Both are now true only when the composer does not already end with that prompt. One sentence each.
- **N3 (pre-existing, out of U-5 scope, ticket for ananya):** the chat's Send button (`MeeraCopilotChat.tsx` L611-619) is icon-only with no `aria-label` or `title`, so a screen reader announces an unnamed button. That fails WCAG 4.1.2. The mic button next to it has a `title` (L604).
- The "placeholder" comment is fixed (`PasteBriefCard.tsx` L64-67 now cites Nisha's approval).

## Final tool runs (restored tree, 16:13-16:20, Kavya's Maven running)

- `npm run typecheck`: exit 0.
- `npx eslint` on the 8 changed U-3/U-5 files: `✖ 3 problems (0 errors, 3 warnings)`, exit 0. The warnings are `MeeraCopilotChat.tsx` 94:17 `react-refresh/only-export-components`, `MeeraCopilotChat.tsx` 230:7 and `PasteBriefCard.tsx` 105:24 `react-hooks/set-state-in-effect`. All three were already there before the fix and sit on lines it did not touch.
- `npx vitest run`, the whole suite: `Test Files 224 passed (224)`, `Tests 1323 passed (1323)`, `Duration 341.28s`, exit 0. No timeouts, so no re-run was needed. This matches Ananya's 224 / 1323.

## Live checks owed after S-2 (owner meera), restated

- **U-3:** hide a flag on a deal in Meera's `check_deal_risks` card, open that deal's room in the same tab, and confirm it shows hidden with "1 flag hidden for this session · Show". Close and reopen the tab, and it is back.
- **U-3:** with site data blocked for the domain, a deal room with flags still renders, and hiding a flag works until reload.
- **U-5:** on a consented live account, paste a brief and tap "Ask Meera about this brief". The composer holds `Look at brief {id}.` with nothing sent. Tap Send, and Meera's `get_brief` card shows that brief. Tap Ask again, and the composer re-fills. Keyboard focus lands somewhere usable after the chat opens.
