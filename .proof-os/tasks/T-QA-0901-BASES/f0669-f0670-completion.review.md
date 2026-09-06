# Priya (CTO) — completion review: F-0669 / F-0670

Task: T-QA-0901-BASES · Date: 2026-09-05 · Branch: `feat/meera-creator-phase-e` (HEAD `eac5e58`)

Context: this is the **second** review of these two findings. My previous pass
(`leak-and-breaks.review.md` §6, B-2 and B-3) marked both "FIXED at the cited surface only" and
named the remaining surfaces. This round is specifically about whether that was closed everywhere.
I enumerated the callers myself and did not accept the caller list in the previous report either.

---

## 1. Verdicts

| Finding | Verdict | Anchor |
|---|---|---|
| **F-0669** — fabricated legal terms | **STILL PARTIAL** | PDF path clean at all 5 call sites; the same four terms still render on-screen at `contract-panel.tsx:182-185`, `creator-contract-panel.tsx:266-274`, `creator-contract-card.tsx:159-167` |
| **F-0670** — Accept offered-and-broken on a rate-less invite | **FIXED** | `creator-chat.tsx:1998`, `:2332`, `:2352`, `:2381`, `:659` |

`creator-contract-card.tsx` is a **new** surface — it appears in no prior review of this finding.

---

## 2. Exit codes

| Command | Exit | Result |
|---|---|---|
| `npx tsc --noEmit` | **0** | clean, no output |
| `npm test` (`vitest run`) — run 1 | **1** | 187 files / 1100 tests **passed**, 1 unhandled rejection |
| `npm test` — run 2 | **0** | 187 files / 1100 tests passed, no error |
| `npm run test:live` | **0** | 6 files / 28 tests passed |

**`npm test` is non-deterministic and exited 1 on my first run.** No assertion failed; the non-zero
exit came from an unhandled rejection that escaped environment teardown:

```
ReferenceError: window is not defined
  ❯ resolveUpdatePriority react-dom-client.development.js:1308
  ❯ src/components/shared/collaboration-reviews-panel.tsx:149   setLoadingReceived(false);
This error originated in "src/pages/creator-reviews.test.tsx"
```

`npx vitest run src/pages/creator-reviews.test.tsx` on its own → **exit 0** (2/2). So this is a
`setState` landing after the jsdom environment is gone, only under full-suite parallelism. It is
unrelated to F-0669/F-0670, but it is a **real gate regression** against the previous round's clean
exit 0, and a required gate that fails half the time is not a gate. Fix the un-awaited promise in
`collaboration-reviews-panel.tsx:149` (guard the `finally` on a mounted/abort flag) — do not paper
over it by re-running until green.

---

## 3. F-0669 — every caller of `downloadContractPDF`, enumerated by me

Five call sites in four production files (plus one test):

| # | Call site | Four terms in the PDF payload? | Verdict |
|---|---|---|---|
| 1 | `src/components/brand/deal-room/deal-contract-tab.tsx:164` (demo-only) | none passed | clean |
| 2 | `src/components/brand/timeline/panels/contract-panel.tsx:76` | `deadline: meta?.deadline` only | clean *(caveat §3.3)* |
| 3 | `src/components/creator/deal-room/creator-contract-panel.tsx:94` | `deadline: meta?.deadline` only | clean *(caveat §3.3)* |
| 4 | `src/components/creator/deal-room/creator-deal-contract-tab.tsx:133` (live fallback) | none passed | clean |
| 5 | `src/components/creator/deal-room/creator-deal-contract-tab.tsx:144` (demo) | same object as #4 | clean |

### 3.1 The named literals cannot reach a downloaded document — confirmed

- **`'Priya Sharma'`** — `grep -rn "Priya Sharma" src/` returns **zero** hits in any contract
  component. The only surviving occurrence on the contract path is prose in a comment
  (`contract-panel.tsx:60`, "never an invented person like 'Priya Sharma'"). The other hits are
  unrelated demo/discovery/admin fixtures and test data.
- **`'2024-02-15'`** — reaches **no** `ContractData`. Both panels now pass `deadline: meta?.deadline`
  (`contract-panel.tsx:71`, `creator-contract-panel.tsx:86`); `generateContractHTML` renders
  `Not specified` when it is undefined (`contract-generator.ts:148` + `formatOptionalDeadline` at
  `:41-47`).
- **`usageRights` / `exclusivity` / `revisionCap`** — not passed by any of the five call sites.
  `ContractData` marks all four optional (`contract-generator.ts:28-31`) and
  `generateContractHTML` renders `Not specified` for each (`:150`, `:152`, `:154`). `tsc` exit 0
  confirms the optionality is real, not a cast.

### 3.2 Callers that legitimately hold a real value — these are fine

- `contract-panel.tsx:61` `creatorName: meta?.creatorName || 'Creator'` — real name when the event
  carries one (`types.ts:596`), an honest role label otherwise. Correct.
- `contract-panel.tsx:71` / `creator-contract-panel.tsx:86` `deadline: meta?.deadline` — real
  server value when present (`types.ts:616`), `undefined` otherwise. Correct.
- `creator-contract-panel.tsx:79` `creatorName: meta?.creatorName || 'You (Creator)'` — real name,
  else an honest self-view label. Correct.
- `creator-contract-panel.tsx:75` `brandName: meta?.brandName || …` — real value when present
  (`types.ts:594`). The *fallback* is the problem, not the field (§3.3).
- `deal-contract-tab.tsx` (`brandName: 'Your Brand'`, `creatorName` prop) and
  `creator-deal-contract-tab.tsx:103-104` (`brandName` prop, `creatorName: 'You'`) — real props and
  role labels, no invented person. Correct.

### 3.3 Why the verdict is STILL PARTIAL — the terms survive on screen, at three surfaces

The fix removed the four terms from the **PDF payload** and left them **rendered in the panel**.
Both shipped honest-terms specs assert only against `capturedHtml` (the Blob), never against the
DOM — so they render the very component that is displaying the fabricated strings and still pass.

I wrote my own probe that asserts on `document.body.textContent` of the same components with the
same props, each with a positive control proving the panel actually rendered. **Both failed.**
Verbatim from the probe output:

- `CreatorContractPanel` — `…Key TermsPayment Terms50% upfront (secured), 50% on
  completionDeliverables2 Instagram Reels, 1 Instagram StoryDeadline2024-02-15Usage Rights6 months
  on social media platformsRevision Cap2 revisions per deliverable…`
  → `creator-contract-panel.tsx:266`, `:270`, `:274`
- `ContractPanel` — `…Contract Terms (Read-only)Terms & Conditions…Content must be delivered by
  2024-02-15Brand retains usage rights for 6 months from delivery…Either party may request
  revisions up to 2 times…`
  → `contract-panel.tsx:182`, `:183`, `:185`

Three consequences, all live:

1. **The original F-0669 symptom is intact.** The finding is "both parties see a document containing
   invented terms for the same agreement, with different invented wording on each side." The brand
   reads *"Brand retains usage rights for 6 months from delivery"*; the creator reads *"6 months on
   social media platforms"*. Different invented wording, same agreement, both still on screen —
   and the brand's block is captioned **"Contract Terms (Read-only)"**, which asserts these are the
   contract's terms.
2. **Each panel now contradicts its own download.** The creator reads `Deadline 2024-02-15` and
   `Usage Rights 6 months on social media platforms`, clicks Download PDF **in that same panel**,
   and gets `Delivery Deadline: Not specified` / `Usage Rights: Not specified`. That is a new,
   fix-introduced inconsistency, not a pre-existing one.
3. **A third, previously unreported surface.** `src/components/creator/deal-room/creator-contract-card.tsx`
   carries a "Key Terms" block with `{meta?.deadline || '2024-02-15'}` (`:159`), `6 months` (`:163`)
   and `Up to 2` (`:167`). It is live-reachable — imported at `src/pages/creator-chat.tsx:71`. It
   appears in no prior review of F-0669 or F-0666. Note the same file already had two sibling
   inventions fixed with the honest-value pattern (`deliverableCountLabel(meta, 'item') ?? 'Not
   specified'`, `:155`), so the remedy is established in-file and simply was not applied to these
   three.

### 3.4 Residual fabricated party identity in the PDF — not in the brief, flagged anyway

`contract-panel.tsx:57` sets `brandName: 'Influora Brand'` **unconditionally**, so every brand-side
PDF from the timeline panel names the platform as the contracting brand. This is not a missing-value
fallback: `TimelineEvent.metadata.brandName` exists (`types.ts:594`) and the creator twin already
reads it (`creator-contract-panel.tsx:75`). My previous pass named this literal in B-2 alongside
`'Priya Sharma'`; only the creator name was fixed.

Same class, lower severity, both panels: `contractId: meta?.contractId || 'CONT-001'`
(`contract-panel.tsx:56`, `creator-contract-panel.tsx:74`) and
`campaignName: meta?.campaignName || 'Summer Fashion'` (`:62`, `:80`) put an invented contract
identifier and campaign name into a downloadable contract.

### 3.5 Revert-probe (mine) — the shipped PDF test does bite

Restored the four literals into `creator-contract-panel.tsx`'s `contractData`. →
`creator-contract-panel-honest-terms.test.tsx` **5 failed / 3 passed**, with the two
known-real-value controls and the "real fields still present" control correctly staying green.
Restored → 8/8 pass. The spec is sound; its **scope** is the problem, not its rigour.

---

## 4. F-0670 in `creator-chat.tsx` — **FIXED**

### 4.1 Is the bare Accept genuinely no longer offered-and-broken?

Yes. `hasAgreedRate = selectedDeal.dealAmount > 0` (`creator-chat.tsx:1998`) gates
`disabled={isAcceptingProposal || isDecliningProposal || !hasAgreedRate}` (`:2332`), with a
`title` giving the reason on hover (`:2334`).

I re-verified the load-bearing chain rather than trusting the comment:
`dealAmount: parseDealAmount(deal.dealValue)` (`creator-deal-mappers.ts:244`), and `dealValue` is
`collaboration.getAgreedRate()` verbatim (`DealService.toDealResponse`). Every real propose/counter
amount is `@DecimalMin("0.01")` server-side, so `dealAmount > 0` ⟺ "has an agreed rate" — it is not
a heuristic, and a genuinely priced invite can never read 0. **Not over-restricted**, and the test
proves it with a live positive control (§4.4).

### 4.2 Is the replacement reachable and explained?

Yes, on both counts, and I checked reachability rather than assuming it:

- **Reachable.** `:2352-2362` renders a `Counter` button (only when `!hasAgreedRate`, so a priced
  invite doesn't get a redundant control). Its `onClick` sets `showCounterForm`, and
  `CounterProposalForm` is mounted at `creator-chat.tsx:3234-3244` at the **component's top level**,
  outside the message-list conditionals — so it is not gated behind the proposal-card branch this
  card replaces. The shipped test proves this end-to-end by clicking Counter and asserting the
  form's step-1 heading `Original Proposal Details` appears.
- **Explained twice.** The card body copy branches at `:2323-2325` (*"No offer amount has been set
  yet — propose a rate with Counter before this can be accepted…"*) and a persistent line sits under
  the buttons at `:2381-2385` (*"No rate proposed yet — use Counter to propose one before
  accepting."*). Not a silently-disabled control.

### 4.3 Does `describeProposalActionError` give actionable copy?

Yes. `creator-chat.tsx:659-663` adds:

```
case 'AGREED_RATE_REQUIRED':
  return {
    message: 'This invite has no agreed rate yet. Use Counter to propose one, then accept.',
    stale: false,
  };
```

`stale: false` is the load-bearing half — the previous `default` branch returned `stale: true`,
whose "Refresh" affordance re-fetches the identical rate-less deal and 409s again forever. The copy
now names the action that actually resolves it. `ApiError`'s constructor is `(code, message, status)`
(`api.ts:315-318`), which is how the spec builds the 409 — so the case is keyed on a code that the
real client genuinely produces, not on a shape invented by the test.

### 4.4 Revert-probe (mine)

Restored `disabled={isAcceptingProposal || isDecliningProposal}` **and** deleted the
`AGREED_RATE_REQUIRED` case, then ran `creator-chat-bare-invite.test.tsx`:

- **2 failed / 8 passed** — exactly the two F-0670 assertions
  (`disables Accept on a rate-less INVITED deal…`, `gives AGREED_RATE_REQUIRED actionable copy…`).
- The positive control `still offers a working Accept on a priced bare invite (dealValue set)`
  correctly stayed **green**, so the fix is not a blanket disable.

Restored (md5 identical to backup); re-ran → 10/10 pass.

---

## 5. Untracked new test files — **YES. Fourth consecutive wave.**

```
?? src/components/brand/timeline/panels/__tests__/          (whole dir, holds contract-panel-honest-terms.test.tsx)
?? src/components/creator/deal-room/__tests__/creator-contract-panel-honest-terms.test.tsx
```

`git ls-files --error-unmatch` fails on both. The previous round's two offenders
(`deal-contract-tab-honest-terms.test.tsx`, `PortfolioServicePublicVisibilityTest.java`) **are now
tracked** — so the B-1 instruction was followed for the old files and repeated for the new ones.
`creator-chat-bare-invite.test.tsx` and `creator-deals.test.tsx` (the F-0670 tests) **are tracked**.

Every gate in §2 passed because these two files exist on my disk. Commit as-is and the only
regression tests for the F-0669 PDF fix at two of its callers ship absent — the F-0324 pattern,
four waves running. This is now a process defect, not an oversight: add a pre-commit check that
fails when a file matching `*.test.tsx`/`*Test.java` exists in the working tree untracked.

Also loose in the tree, unrelated but worth sweeping: `.f0248-backup.tsx`, `.ts`, `.scratch3.txt`,
`class2.tsv` at the repo root, and `.proof-os/_tmp_*` / `.proof-os/rc/citations.*.rc` scratch.

---

## 6. What must happen before F-0669 can be closed

1. Strip the four fabricated terms from the **rendered** blocks at `contract-panel.tsx:182-185`,
   `creator-contract-panel.tsx:266-274`, `creator-contract-card.tsx:159-167` — render
   `Not specified` (or omit the row) exactly as the PDF now does. The two panels must agree with
   the document their own button produces.
2. Replace `brandName: 'Influora Brand'` (`contract-panel.tsx:57`) with `meta?.brandName`, and drop
   the `'CONT-001'` / `'Summer Fashion'` fallbacks at `:56`/`:62` and `:74`/`:80`.
3. Extend both honest-terms specs with **DOM** assertions, not only `capturedHtml`. A spec that
   renders the panel and asserts the string is absent from the Blob while it is visible on screen is
   the exact shape of gate that let this through twice.
4. `git add` the two untracked test files.
5. Open the ledger entries: the `creator-contract-card.tsx` surface is new, and the
   `npm test` teardown flake is its own defect.

## 7. Working tree

Both probes reverted from backups (`creator-chat.tsx` md5 `168b4801…` matches its pre-probe backup);
my probe spec `PriyaOnScreenTermsProbe.test.tsx` deleted. Post-review re-run of
`creator-contract-panel-honest-terms` + `contract-panel-honest-terms` + `creator-chat-bare-invite`
→ **3 files / 27 tests passed, exit 0**. No probe residue remains.
