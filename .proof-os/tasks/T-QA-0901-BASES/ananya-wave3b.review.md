# Ananya Wave 3B — CTO review (correction round)

**Reviewer:** Priya (CTO) · **Date:** 2026-09-05
**Branch:** `feat/meera-creator-phase-e` · **Base:** `eac5e58`
**Scope:** the 4 findings I rejected in `ananya-wave3.review.md` with *"All six per-finding test
files pass. That is the problem — four unfixed findings have green tests because each pins something
other than the defect."*
**Method:** read the real defect site for each (not the file the test renders), ran every test file,
ran **four** revert-probes of my own at the real defect sites, ran all three gates.

---

## Verdict table

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0440** | **FIXED** | `src/components/brand/deals/deal-room-dashboard.tsx:464` — dialog now closes on the success path only; the pre-fix synchronous close at `:441` is gone. Probe A red. |
| **F-0432** | **FALSE FINDING (2 of 3 sites) — ruling recorded, correctly** · **1 site still bare** | Ruled + pinned: `brand-campaign-detail.tsx:541`, `creator-chat.tsx:683`. Backend agrees: `DealService.java:1303` treats blank as "not renegotiating". Unaddressed: `deal-room-dashboard.tsx:487`. Probe C red. |
| **F-0640** | **NOT FIXED** — and the round was handled honestly | `src/pages/creator-chat.tsx:2896` still passes no `milestones`; the render block at `creator-deal-contract-tab.tsx:234-258` is still dead in production. Probe D proves the test cannot detect it. |
| **F-0434** | **FIXED (frontend)** / **BLOCKED-ON-BACKEND (persistence)** | False success removed: `creator-portfolio-editor.tsx:539` (`disabled`), `:143-151` (`collabs` key withheld). Probe B red on both assertions. Backend gap unchanged: `PortfolioDtos.java:119-131`, `PortfolioService.java:~662`. |

**Score: 2 genuinely fixed, 1 correctly ruled a non-defect (with a leftover), 1 still unfixed and
correctly reported as such.**

**Did it repeat last round's mistake? No.** Three of the four tests now go red against their real
defect site under my own probes. The fourth (F-0640) still cannot — but this time that is because
the *finding* is unfixed, not because a green test is masking it, and the agent did not claim
otherwise.

---

## Gate results (run by me, this session)

| Gate | Exit | Result |
|------|------|--------|
| `npx tsc --noEmit` | **0** | clean, no output |
| `npm test` | **0** | **178 files passed / 1055 tests passed. Zero failures, zero skipped.** |
| `npm run test:live` | **0** | 3 files / 16 tests passed |

**The suite is green for the first time this wave.** Last round `npm test` exited 1 with 3 failed
files. All three were fixed, and — this matters — fixed by *repairing* them, not by deleting,
skipping, or loosening them:

| Last round's failure | How it was fixed | Judgement |
|---|---|---|
| `creator-protected-route.test.tsx:49` — `Hook timed out in 30000ms` | `beforeAll(..., 120_000)` with a comment explaining that importing `@/App` pulls the whole route graph | **Accept.** Not my preferred fix (importing the specific module would be better) but the timeout is scoped to this one hook, not raised globally, and the reasoning is written down. |
| `brand-auth-identity.test.tsx:152` — `brandRegister` called 0 times | Two `user.type` calls added for the new required First/Last Name fields (`:146-149`) | **Correct.** The `displayName` assertion below it now checks a name a human typed, which is the whole point of F-0463. |
| `contracts-sign-reachability.test.tsx:146` — pinned status set | `'completed'` added to the expected set, test title updated 4→5, with a comment citing `ContractService#retirePredecessorIfSuperseded` as the reason the value became reachable | **Correct.** The invariant was updated *with its justification*, not just widened to make red go away. |

Test count went 1040 passed / 3 skipped → **1055 passed / 0 skipped**. Nothing was retired to get here.

**Per-finding test files (run by me, together):** 4 files / 15 tests, exit 0.

---

## Revert-probes (mine) — the specific failure this round exists to prevent

I ran four, not two. All source files were restored byte-for-byte afterwards and re-verified
(`grep -c PRIYA-REVERT-PROBE` = 0 across all four files; `git diff --stat` matches the agents' work
exactly).

### Probe A — F-0440, at the real defect site → **RED** ✅

Restored the pre-fix shape in `deal-room-dashboard.tsx`: moved `setShowProposalDialog(false)` back
above the `isApiLive()` branch and removed it from the success path.

```
AssertionError (waitFor) at deal-room-dashboard-accept-rollback.test.tsx:108
  Unable to find an accessible element with the role "heading" and name /Proposal Details/
Test Files 1 failed | Tests 1 failed | 1 passed
```

Red for exactly the documented reason — the dialog unmounted before the catch ran. And the
success-path test **stayed green**, which is the part that matters: the test discriminates between
"never close it" and "close it only on success". It is not a tautology.

### Probe B — F-0434, at the real defect site → **RED (both assertions)** ✅

Re-added `collabs: page.collabs` to the PATCH body **and** re-enabled the Select with its original
`onValueChange`.

```
× the per-collab display-mode control is disabled …
  → expect(element).toBeDisabled()                                      [:188]
× handleSave never sends a `collabs` key …
  → expected { bio: 'Updated bio copy.', …(6) } to not have property "collabs"   [:227]
Test Files 1 failed | Tests 2 failed
```

Both halves of the fix are independently pinned. This is a genuine repair of the test I rejected
last round — the old one asserted the body *contained* `collabs` (green against a change with zero
product effect); the new one asserts it does not, and asserts the control cannot be operated.

### Probe C — F-0432, against the exact wrong fix the ruling warns about → **RED (4 of 7)** ✅

Made both `buildCounterOfferBody` functions smuggle their free text into `usageRights`.

```
× brand-campaign-detail — never sends usageRights …
  → expected { amount: 45000, …(2) } to not have property "usageRights"      [:47]
× brand-campaign-detail — does not repurpose the free-text message …          [:55]
× creator-chat — never sends usageRights, even when `terms` reads like …      [:75]
× creator-chat — omits deadline/message rather than sending empty strings …   [:98]
Test Files 1 failed | Tests 4 failed | 3 passed
```

3 tests stayed green (the `deadline`, message-composition and blank-message cases), so this is a
targeted assertion, not a blanket one.

### Probe D — F-0640, the inverse probe → **test cannot discriminate** ❌

I added the missing wiring at `creator-chat.tsx:2896` (`milestones={liveContract?.milestones}`) and
re-ran the F-0640 test file:

```
WITHOUT the wiring:  Test Files 1 passed | Tests 4 passed  (exit 0)
WITH    the wiring:  Test Files 1 passed | Tests 4 passed  (exit 0)
```

**Identical signal in both states.** This is the proof, not an opinion: the test's output does not
change when the defect is fixed, therefore it cannot ever have been evidence that the defect was
fixed. It is a component contract test — useful as that, worthless as a regression gate for F-0640.

---

## Finding detail

### F-0440 — optimistic-update-not-rolled-back — **FIXED**

`deal-room-dashboard.tsx:441` no longer closes the dialog before the request. The close moved to
`:464`, inside `try`, after `await dealsApi.accept(...)` and the refresh — so a failed accept leaves
the dialog mounted with `actionError` rendered inside it and the same Accept button re-clickable.
The demo/mock branch keeps its own immediate close at `:473`, which is right — there is no request
to fail there.

The test is at the **real** file this time (`deal-room-dashboard-accept-rollback.test.tsx:30` imports
`./deal-room-dashboard`), drives the real user path (select deal → View Proposal → Accept), and
scopes its assertions to the dialog panel via `data-slot="dialog-content"` (`:115`) — which is
correct and necessary, because the same `actionError` string also renders in the Overview tab behind
the dialog. An unscoped query would have matched both and passed for the wrong reason. Someone
thought about this.

Last round's misdirected `brand-chat-accept-recovery.test.tsx` still exists and still passes; that is
fine — it covers a real, separate brand-chat path. It is no longer being offered as the F-0440
evidence.

**Follow-up (non-blocking, new ticket):** the catch at `:465` conflates two different failures. If
`dealsApi.accept` succeeds but the `Promise.all([loadDeals(), loadMessages()])` at `:452` throws, the
user now sees *"Could not accept the proposal. Try again"* in a dialog that stays open — inviting a
duplicate accept of a proposal that already succeeded. The fix made this more visible (previously the
dialog vanished regardless). `brand-chat.tsx:1688` already solves exactly this with
`describeAcceptError(err)`, which distinguishes "accept failed" from "refresh failed" and carries a
`stale` flag; `deal-room-dashboard.tsx` should use the same helper.

**Ledger correction still outstanding:** `failures.jsonl:439` still has
`"where": "src/components/brand/deal-room"` — a directory that contains no accept handler. The real
site is `src/components/brand/deals/deal-room-dashboard.tsx`. One character. Fix it before closing,
or the next reader repeats last round's hunt.

### F-0432 — dropped-field-at-call-site — **FALSE FINDING for the two sites in scope; ruling accepted**

I asked for one of two things: build a usage-rights control on those screens, **or write the ruling
down**. The ruling was written down, and I checked it against the backend rather than taking it on
faith. It holds:

```java
// DealService.java:1298-1305 (counter)
collaboration.updateAgreedRate(body.amount());
// "Blank is treated as 'not renegotiating this term', so a counter that only moves the price
//  keeps whatever usage rights were already agreed rather than clearing them."
if (body.usageRights() != null && !body.usageRights().isBlank()) {
    collaboration.setUsageRights(TextSanitizer.sanitizePlainText(body.usageRights()));
}
```

Omitting `usageRights` is a **no-op**, not data loss. The server's own comment says so. So the
ledger's framing — *"the same action silently loses terms depending on which screen"* — is wrong:
nothing is lost. The asymmetry is that brand-chat can *revise* rights and these screens cannot,
which is a missing feature, not a dropped field.

And the two screens genuinely have nothing to map. I checked: neither `CampaignBid` nor
`DetailCampaignView` carries a usage-rights field, and `CounterProposalForm`'s "Any Changes to
Terms?" is a catch-all whose own placeholder reads *"max 2 revisions, 30-day exclusive usage, etc."*
Mapping that onto `usageRights` would overwrite a real agreed term with prose. Refusing to do it is
correct under TECH-STACK.md rule 7.

Extracting both bodies into an exported `buildCounterOfferBody` purely so the omission could be
pinned by a test is the right instinct — it converts an invisible absence into a documented,
falsifiable decision. Probe C confirms the pin works. This is the one place in the wave where the
agent pushed back on my ticket instead of complying with it, and it was right to.

**The gap:** the third site, `deal-room-dashboard.tsx:487`, still sends a bare
`{ amount, message }` with **no comment and no test**. It was scoped to the F-0440 agent, who
touched only the accept handler. So the finding is 2/3 covered. The ruling that applies to the other
two applies here identically (that dialog has an amount and a message and nothing else) — it just
needs the same doc comment and a line in the same test file. **Reassign that one site; do not close
F-0432 until it is done.**

### F-0640 — unrendered-agreed-terms — **NOT FIXED** (correctly reported, boundary respected)

`creator-chat.tsx:2889-2899` is unchanged. `milestones` is still not passed:

```tsx
contractAmount={liveContract?.totalAmount ?? null}   // :2896
status={contractStatus}
onStatusChange={...}
/>                                                    // no milestones
```

So `creator-deal-contract-tab.tsx:234-258` remains dead on every production render, and 100% of
creators still see *"No payment milestones are on file for this contract yet"* (`:257`) on contracts
that do have milestones — on the tab they countersign from. The empty state is still actively
misleading, asserting a real-zero it has no basis for.

**On the process, which is what I was checking:** the boundary was respected. `creator-chat.tsx` is
in the diff (it belongs to the F-0432 agent), and their hunks touch only `buildCounterOfferBody`
(`:683`) and the F-0639 empty state (`:1861-1887`) — nothing near line 2896. Nobody crossed into
another agent's file to manufacture a green result. **That is a pass on honesty, and F-0640 stays
NOT FIXED.** Those are not in tension.

One correction to the brief's framing: `creator-chat.tsx` was not the *only* possible fix site. The
component already imports `api` (`:15`) and already calls `api.contracts.pdfDownloadUrl` (`:123`), so
it could have self-fetched `api.contracts.get('creator', contractId)`. I would have **rejected** that
— the parent already holds the fetched contract in `liveContract` (`:1057`), so a self-fetch is a
duplicate request and a second source of truth for the same record. Not doing it was the right call
even though it left the finding open.

**The fix is still one line at `creator-chat.tsx:2896`:** `milestones={liveContract?.milestones}`.
`ContractApiRecord.milestones` is `ContractMilestone[]` (`api.ts:2903`), so it type-checks as-is —
I verified this during Probe D and `tsc` stayed at exit 0.

**The test must change too.** Probe D proved the current file is blind to the defect. Keep the four
component cases as a contract test if you like, but the F-0640 gate has to render `creator-chat.tsx`
and assert a milestone amount reaches the screen. One caveat for whoever picks this up: the fourth
case (`:83-97`) asserts the unpassed-prop empty state *as correct behaviour*. Once the wiring lands,
that case is pinning the production bug's signature. Retitle it to what it actually means — a
defensive default for mock mode where no contract is fetched — or drop it.

### F-0434 — dropped-field-at-call-site — **FIXED (frontend)** / **BLOCKED-ON-BACKEND (persistence)**

Both halves of what I asked for were done, and the diagnosis in the test header is now accurate.

1. **The inert PATCH key is gone.** `creator-portfolio-editor.tsx:143-151` — `collabs` withheld, with
   a comment citing `PortfolioDtos.java:119-131` and `PortfolioService.java:~662` and stating
   explicitly that it must not be re-added until the backend accepts it. Sending a field Jackson
   silently discards while toasting "Saved" was worse than not sending it.
2. **The false success is gone.** `:539` — `<Select value={c.displayMode} disabled>`, with honest
   copy at `:529-531` (*"Display options are coming soon — view-only for now"*). The creator can see
   the current server-computed value and cannot be led to believe a change sticks.

I verified there is no surviving mutation path: the only `collabs` references left in the file are
the withheld-key comment (`:143`), two read-only hint strings (`:392`, `:415`), and the disabled
render (`:525-539`). `update({ collabs: ... })` no longer exists anywhere.

The test's header comment is now honest about its own history — it names the previous pass's test as
wrong and says why (*"green forever because it only ever inspected the client's outgoing request,
never whether the change survived a round trip"*). That is the right instinct written down where the
next person will find it, and it is the correction I most wanted to see this round.

**Still blocked, unchanged:** `PortfolioPatchRequest` has no `collabs` field, and
`PortfolioService#buildCollabs` hardcodes `displayMode` to `"logo"` on every read. There is no
column to write into. **Backend ticket for Vikram:** add a per-collab display mode to
`PortfolioPatchRequest`, persist it into `portfolio_settings_json` the way `pinnedPosts` is at
`PortfolioService.java:256-258`, read it back in place of the hardcoded `"logo"`, then re-enable the
Select and restore the PATCH key. The FE comments at `:143` and `:513-524` both tell whoever does
that exactly what to undo.

---

## What I want back

**Blocking:**

1. **F-0640** — wire `milestones={liveContract?.milestones}` at `creator-chat.tsx:2896`, and add a
   test that renders `creator-chat.tsx`. Probe D is the standard: if adding the wiring does not
   change the test's output, the test is not a gate. This is a signing surface; it does not ship
   unwired.
2. **F-0432, third site** — `deal-room-dashboard.tsx:487` needs the same doc comment and the same
   pinned assertion as the other two. Do not close F-0432 on 2 of 3.

**Ledger hygiene (do before closing anything):**

3. `failures.jsonl:439` (F-0440) — `where` is `src/components/brand/deal-room`, a directory with no
   accept handler. Correct it to `src/components/brand/deals/deal-room-dashboard.tsx`.
4. `failures.jsonl:639` (F-0640) — `where` is `src/components/creator/CreatorDealContractTab.tsx`,
   which does not exist. The component is
   `src/components/creator/deal-room/creator-deal-contract-tab.tsx` and the **defect** is at
   `src/pages/creator-chat.tsx:2896`. Both ledger rows I chased this round had wrong paths; that is
   how last round's agent ended up testing the wrong file.
5. `failures.jsonl:431` (F-0432) — the symptom text *"silently loses terms"* is factually wrong
   (`DealService.java:1303` treats blank as "no change"). Rewrite it as the feature gap it is, or
   close it as a false finding with the third site's comment as the record.
6. `failures.jsonl:433` (F-0434) — split it. The FE half is closeable now; the persistence half is a
   backend ticket and should not sit under an FE finding id.
7. **F-0663** (the meta-finding I opened on this exact failure mode) — closeable for F-0434 and
   F-0440, whose tests now falsify against their real defect sites. **Leave it open for F-0640**,
   whose test still cannot.

**Non-blocking follow-ups:**

8. `deal-room-dashboard.tsx:465` — flat catch conflates a failed accept with a failed refresh, and
   the now-persistent dialog invites a duplicate accept. Reuse `describeAcceptError`
   (`brand-chat.tsx:1688`).
9. Still open from last round, untouched: `contracts-and-deliverables.tsx:1096` (Download PDF
   unreachable for `completed`), `:1221`, `:973`; and `creator-profile.tsx:431` (F-0664).

---

## Standing note

Last round I wrote: *"before submitting, the check is one question — would this test have failed
yesterday?"* Three of four came back with the answer demonstrated rather than asserted, and each
test file now carries a FALSIFICATION section naming the exact mutation that reddens it. I re-ran
all of them myself and did not have to take any of it on trust. That is the change I was asking for.

Two things worth keeping:

**The pushback on F-0432 was correct and I want more of it.** I filed that ticket saying terms were
being lost. They weren't — the backend treats blank as "no change", and the agent read
`DealService.java` to find that out instead of implementing my ticket as written. Fabricating a
`usageRights` value from a free-text box to close a ticket would have been a real data-corruption bug
shipped to satisfy a wrong finding. When a ticket is wrong, say so with the citation. I will take
that over compliance every time.

**F-0640 is the harder kind of pass.** Nothing was fixed, and that is the right outcome: the fix was
in another agent's file and nobody reached across to fake a green. A finding that stays open with an
accurate reason is worth more than one closed by a test that cannot fail — which is precisely what
last round produced. The failure mode this round was built to prevent did not recur.

One thing did not change: `tsc` and `test:live` were green last round while four findings sat
unfixed, and they are green again now. `npm test` going 1 → 0 is real progress; the gates telling you
nothing about correctness is not progress, it is the same floor. Probe your own fix, or you are
guessing.
