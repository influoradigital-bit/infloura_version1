# Ananya Wave 3 — CTO review (fresh context)

**Reviewer:** Priya (CTO) · **Date:** 2026-09-05
**Branch:** `feat/meera-creator-phase-e` · **Base:** `eac5e58`
**Scope:** 8 finding IDs across 6 assignments
**Method:** read the actual diff, read the real render/call path for each, ran every test file, ran
one revert-probe, ran `npx tsc --noEmit` and `npm run test:live`.

---

## Verdict table

| ID | Verdict | Citation |
|----|---------|----------|
| **F-0432** | **NOT FIXED** — no code changed anywhere | `src/pages/brand-chat.tsx:1602` is the one site that was ALREADY correct; the three that drop `usageRights` are untouched |
| **F-0440** | **NOT FIXED** — defect located, then declared out of scope | `src/components/brand/deals/deal-room-dashboard.tsx:442` |
| **F-0434** | **NOT FIXED** — the added field does not exist on the server DTO | `src/pages/creator-portfolio-editor.tsx:146` vs `influora-api/.../PortfolioDtos.java:119-131` |
| **F-0463** | **FIXED** | `src/pages/brand-register.tsx:127-128` (+ inputs `:313-346`, validation `:77-82`) |
| **F-0640** | **NOT FIXED** — the render path never reaches the new block | `src/components/creator/deal-room/creator-deal-contract-tab.tsx:229-259` is dead; `src/pages/creator-chat.tsx:2873-2883` never passes `milestones` |
| **F-0659** | **FIXED**, with a self-inflicted sibling regression | `src/components/brand/contracts/contracts-and-deliverables.tsx:423`, `:632`; regression at `:1096` |
| **F-0278** | **FALSE FINDING (for this wave)** — already fixed pre-wave, ledger is stale | `src/components/brand/dashboard/dashboard-page.tsx:128-135`, `:363`; commit `535e024` |
| **F-0662** | **FIXED** | `src/pages/creator-profile.tsx:549-553` |

**Score: 3 genuinely fixed / 1 stale ticket / 4 not fixed.**

---

## Gate results (run by me, this session)

| Gate | Exit | Notes |
|------|------|-------|
| `npx tsc --noEmit` | **0** | clean, no output |
| `npm run test:live` | **0** | 3 files / 16 tests passed |
| `npm test` (full suite) | **1** | **3 files failed / 173 passed; 2 tests failed / 1040 passed.** See §Full-suite failures — two are regressions this wave caused. |

> **The two gates I was asked to report are green, but the wave is still red.** `tsc` and
> `test:live` both pass while `npm test` exits 1. Reporting only the two requested gates would have
> shipped three broken suites. The "3 files failed / 2 tests failed" arithmetic is the tell — one
> file failed with **zero** failing assertions.

**Live-config check (explicitly requested):** none of the six Wave 3 test files is `*.live.test.ts`,
so none is silently excluded by `vitest.config.ts`'s `'**/*.live.test.ts'` exclusion. The two
`.live` files in this branch (`api-client-resilience.live.test.ts`,
`creator-discovery-dto-fidelity.live.test.ts`) belong to other findings (F-0438, F-0408/F-0431) and
**do** execute under `vitest.live.config.ts` — confirmed in the run above. No hidden-test problem
this wave.

**Per-file test results (all run by me):**

| File | Result |
|------|--------|
| `creator-deal-contract-tab-milestones.test.tsx` | 4 passed |
| `creator-profile.engagement-rate-null.test.tsx` | 2 passed |
| `contracts-and-deliverables.status-label.test.tsx` | 3 passed |
| `creator-portfolio-editor.f0434-collabs-drop.test.tsx` | 1 passed |
| `brand-register.no-name-fabrication.test.tsx` | 2 passed |
| `brand-register.test.tsx` | 3 passed |
| `dashboard-page-f0278.test.tsx` | (pre-existing) passed |
| `brand-chat-accept-recovery.test.tsx` | 2 passed |

**Every test passes. That is exactly the problem.** Four of the eight findings are unfixed and their
test files are green, because each pins something other than the defect.

---

## Revert-probe (mine)

Two probes, both on findings I was about to pass, to check the tests actually discriminate.

**Probe A — F-0463, payload-level (the informative variant).** I deliberately did *not* revert the
whole file: I kept the new First/Last Name inputs and restored only the email-derived fabrication in
the payload, so the probe tests the assertion rather than the mere presence of the fields.

```
firstName: (email.split('@')[0].split(/[._-]/)[0] || 'Brand').replace(/^./, c => c.toUpperCase()),
lastName: 'User',
```

→ `AssertionError: expected 'Sales' to be 'Priya'` — **red**. The test discriminates on the payload.

**Probe B — F-0659.** Reverted `case 'COMPLETED': return 'completed'` → `'signed'`.
→ 3/3 red, including the behavioral one:
`expected [ <span…>, <span…> ] to have a length of 1 but got 2` — **red**.

Both files restored byte-for-byte and re-verified (`brand-register.tsx:127`,
`contracts-and-deliverables.tsx:422-423`). Working tree is back to Ananya's state.

---

## Finding detail

### F-0432 — dropped-field-at-call-site — **NOT FIXED**

Nothing changed. `git diff HEAD` touches neither `brand-chat.tsx` nor any other counter call site,
and `src/lib/api.ts`'s 280-line diff contains no `counter`/`usageRights` hunk.

The assignment header said "F-0432 in `src/pages/brand-chat.tsx`", but the ledger symptom says the
opposite: *"of the four screens that counter, **only brand chat maps a real value**; the other three
drop it."* brand-chat is the **reference implementation**, not the defect. This is the
fix-one-site-while-siblings-stay-broken trap, and the assignment sheet walked straight into it.

All four sites, verified:

| Call site | sends `usageRights`? |
|---|---|
| `src/pages/brand-chat.tsx:1602` | **yes** — `data.usageRightsDuration.replace(/-/g, ' ')` |
| `src/components/brand/deals/deal-room-dashboard.tsx:479` | **no** — `{ amount, message }` only |
| `src/pages/brand-campaign-detail.tsx:786` | **no** — `{ amount, message }` only |
| `src/pages/creator-chat.tsx:1480-1485` | **no** — `{ amount, message, deadline }` |

`creator-chat.tsx:1474-1477` carries a defensible reason (its free-text "Any Changes to Terms?" is
not the usage-rights term, so mapping it would *overwrite* real rights with prose). The other two
have no such note — their counter dialogs simply have no usage-rights control at all. Either build
the field on those two screens or write the ruling down; neither was done.

**No test was written for F-0432.**

### F-0440 — optimistic-update-not-rolled-back — **NOT FIXED**

The defect is real and still present, verbatim as the ticket describes:

```
deal-room-dashboard.tsx:442   setShowProposalDialog(false);   // dismissed BEFORE the request
deal-room-dashboard.tsx:449   await dealsApi.accept(selectedDeal.id, 'brand');
deal-room-dashboard.tsx:460   setActionError('Could not accept the proposal. Try again.');  // never reopens
```

On failure the dialog is gone, the error lands in `actionError`, and the Accept control the user was
using no longer exists. Exactly "no way back to the action they attempted."

Credit where due: `brand-chat-accept-recovery.test.tsx:14-17` **correctly identifies** this file and
this line as the true location. It then declares it out of scope and pins `brand-chat.tsx` instead —
a file that was never modified in this wave. A regression test against unmodified source passes
against the old code by construction; it proves nothing about a fix because there is no fix.

The ledger `where` is `src/components/brand/deal-room` (a directory). I checked it:
`src/components/brand/deal-room/` contains no accept handler at all. The only handler matching the
symptom is `src/components/brand/deals/deal-room-dashboard.tsx:440` — a one-character directory-name
difference. Ananya found this and stopped. **Fix the ledger's `where` and reassign.**

### F-0434 — dropped-field-at-call-site — **NOT FIXED** (fix cannot work)

`creator-portfolio-editor.tsx:146` now adds `collabs: page.collabs` to the PATCH body. The Select at
`:514-530` does genuinely edit `page.collabs`, so the FE half of the diagnosis is right.

But the server's PATCH DTO has no such field:

```java
// influora-api/src/main/java/com/influora/web/dto/portfolio/PortfolioDtos.java:119-131
public record PortfolioPatchRequest(
        String username, String displayName, String bio, String city,
        List<String> niches, String avatarUrl, String coverUrl, List<String> languages,
        List<PortfolioCustomLink> customLinks, List<PortfolioPinnedPost> pinnedPosts,
        List<PortfolioRateRow> rateCard, PortfolioVisibility visibility) {}
```

Twelve fields; `collabs` is not one of them. `collabs` appears only on the **response** record
`PortfolioPageResponse` (`:66-79`). Spring Boot's default `FAIL_ON_UNKNOWN_PROPERTIES=false` means
Jackson silently drops the key — no 400, no warning, and the toast still says "Saved".

Worse, `displayMode` is **hardcoded** on read-back:

```java
// PortfolioService.java:651-662 — every collab, unconditionally
new PortfolioCollab(..., "logo");
```

`displayMode` exists on exactly one record in the backend (`PortfolioDtos.java:45`, the response) and
is read from no request anywhere. The Select is a dead control end-to-end. The fix cannot persist
anything; it only makes the request body larger.

The test (`creator-portfolio-editor.f0434-collabs-drop.test.tsx:186-189`) asserts only that the FE
*sends* the key. It mocks `api.portfolio.update` and never touches the contract, so it is green
against a change that has zero effect on the product.

Separately, the test's header comment (`:22-25`) claims the other six DTO fields "have no input
control anywhere in this file." That is right for `displayName`/`city`/`avatarUrl`/`username`, but
`languages` and `pinnedPosts` are surfaced in the editor at `:416` and `:428-429` (as visibility
toggles, not value editors) — worth a second look before F-0434 is closed on that reasoning.

**Correct fix:** add `collabs` (or a narrower `collabDisplayModes`) to `PortfolioPatchRequest`,
persist it into `portfolio_settings_json` the way `pinnedPosts` is at `PortfolioService.java:256-258`,
and read it back in place of the hardcoded `"logo"`. Backend work — reassign to Vikram.

### F-0463 — fabricated-data-in-ui — **FIXED**

The email-splitting fabrication is gone (`brand-register.tsx:121-128`), replaced by real required
inputs (`:313-346`) with real validation (`:77-82`). Confirmed by my own revert-probe (§Revert-probe A).

**Whole-path check, as requested — no fabricated human name can be persisted or greeted anywhere in
this flow:**

1. **Registration payload** — `brand-register.tsx:127-128`: `firstName.trim()` / `lastName.trim()`,
   both blocked empty by `validateStep2`.
2. **The sibling registration path** — `api.auth.brandRegister` has a second caller,
   `src/pages/brand-onboarding.tsx:73-95`. This is where the trap would have been. It is **already
   clean**: `data.firstName`/`data.lastName` come from real inputs at
   `onboarding-steps.tsx:680-699`, required at `:463-464`. No email derivation.
3. **What gets persisted as the display name** — `AuthService.java:160`:
   `displayName = (req.firstName() + " " + req.lastName()).trim()`. Server-side it is exactly what
   the user typed; nothing is synthesised.
4. **The greeting** — `dashboard-page.tsx:239` reads `user.displayName` via
   `getBrandDisplayName()` → `localStorage brand_display_name`, written from the server response at
   `auth-session.ts:67,77`. `buildBrandUser` (`auth-session.ts:133`) uses
   `seed.displayName?.trim() || ''` — it explicitly refuses to invent. Fallback is the literal
   `'there'` (`dashboard-page.tsx:239`), which is honest.
5. **The onboarding welcome screen** — `brand-onboarding.tsx:249`:
   `firstName?.trim() || 'there'`. Honest fallback, no fabrication.
6. **Repo-wide sweep** for the fabrication idiom: the only surviving `email.split('@')` in `src/` is
   `src/pages/admin-console.tsx:58`, which derives a console label for an admin session — a
   different surface, not persisted, and outside this ticket. Flagging it, not blocking on it.

No remaining path can persist or greet a name the user did not type.

### F-0640 — unrendered-agreed-terms — **NOT FIXED**

This is the one I want on the record most clearly, because it is the tab a creator **countersigns**
from.

The component now accepts an optional `milestones` prop (`creator-deal-contract-tab.tsx:35-43`) and
renders a real schedule block (`:229-259`). The rendering code is correct and honest.

**But no caller ever passes it.** `CreatorDealContractTab` has exactly one production call site:

```tsx
// src/pages/creator-chat.tsx:2873-2883
<CreatorDealContractTab
  contractId={contractId}
  brandName={selectedDeal.brandName}
  campaignName={selectedDeal.campaignName}
  amount={selectedDeal.dealAmount}
  contractAmount={liveContract?.totalAmount ?? null}   // ← :2880
  status={contractStatus}
  onStatusChange={...}
/>                                                      // ← no `milestones`
```

Because the prop is optional, `milestones` is `undefined` on **every** real render, so the ternary at
`:240` always takes the else branch. In production, 100% of creators see:

> "No payment milestones are on file for this contract yet."

…on contracts that do have milestones. The ticket said "no prop wired"; the prop now exists but is
still not wired, so nothing observable changed. The empty state is now *actively misleading* — it
asserts a real-zero ("none on file") that the component has no basis for, which is the
`empty-state-misleads` class this repo already tracks (F-0278, F-0349, F-0388).

The fix is one line, and the data is already in scope on the very line above: `liveContract` is a
`ContractApiRecord | null` (`creator-chat.tsx:1029`) and `ContractApiRecord.milestones` is
`ContractMilestone[]` (`api.ts:2903`). Add `milestones={liveContract?.milestones}`.

The test is the exact shape the brief warned about: all four cases render
`<CreatorDealContractTab>` in isolation with `milestones` handed in directly
(`creator-deal-contract-tab-milestones.test.tsx:26-37, 46-57, 66-77`). The fourth case
(`:83-97`) even asserts the unpassed-prop empty state — i.e. it pins the production behavior as
*correct*. Nothing in the file renders `creator-chat.tsx`, so nothing can see that the wiring is
absent.

**Milestone values themselves (as requested):** where they *are* passed, the render is honest —
`milestone.sequenceNo`, `milestone.description`, `formatINR(milestone.amount)` and
`milestone.dueDate` are read straight off the record with no derivation, no defaulting and no
placeholder; `dueDate` renders only when present (`:246`), and `formatMilestoneDueDate` (`:46-52`)
falls back to the raw string rather than hiding an unparseable real date. Nothing is fabricated.
Once the prop is wired this render is correct — it is only the wiring that is missing.

### F-0659 — new-status-unhandled-in-ui — **FIXED** (with a regression to clean up)

`COMPLETED` now maps to its own `'completed'` UI status (`contracts-and-deliverables.tsx:423`) with a
distinct label and treatment — "Superseded", `stage-paused` tokens (`:632`). I verified the tokens
actually exist and resolve (`src/app/globals.css:87-89, 260-262`), so the badge renders visibly
rather than transparent-on-transparent. The test is behavioral (renders the real component with an
ACTIVE + COMPLETED pair and counts badges, `:96-125`) and my revert-probe put it red on all three
cases. Good work.

**But widening the union left three `=== 'signed'` siblings behind**, and one is a real regression:

- `:1096` — the **Download PDF** button is gated on `status === 'signed'`. A superseded contract can
  no longer be downloaded. This is a *new* defect introduced by this fix, and it bites hardest on
  exactly the contracts you most want a PDF of.
- `:1221` — the "Contract Fully Executed / Signed on {date}" panel is likewise hidden. A superseded
  contract *was* fully executed; that fact is now invisible.
- `:973` — the status filter `<select>` gained no `completed` option, so a brand cannot filter to
  superseded contracts (they still appear under "All Status" via `:786`).

None is fatal, none blocks the F-0659 verdict, but `:1096` should be `(status === 'signed' || status
=== 'completed')` before this ships. **Log as a follow-up ticket.**

### F-0278 — empty-state-misleads — **FALSE FINDING for this wave** (stale ledger row)

Already fixed, before Wave 3 started. `dashboard-page.tsx` is not in this wave's diff at all.

- `:128-135` — `isGenuinelyEmpty` requires all three sources `'ready'` **and** empty, so a
  still-loading or failed fetch can never render as a real zero.
- `:262-269` — the subtitle branches to "Your brand workspace is ready — create your first
  campaign…" instead of "You're all caught up."
- `:363-374` — the green-tick "All caught up!" card is replaced by a "Ready to launch your first
  campaign?" onboarding card with a real CTA. The green tick at `:378-384` is now reachable only for
  an established account with nothing pending — which is what it always meant.

Landed in `535e024` (`isGenuinelyEmpty`, confirmed via `git log -S`) and extended in `969edcc`
(`BrandFirstRunChecklist`, `:283-289`). Ananya wrote nothing here, correctly — there was nothing to
write. **The ledger row is stale: `failures.jsonl:277` still says `"status": "open"`. Close it and
credit `535e024`.**

### F-0662 — dishonest-null-render — **FIXED**

`creator-profile.tsx:549-553` guards on `!= null` and renders "Not available yet" instead of a bare
"%". The block sits in the unconditionally-rendered "Profile Stats" card (`:527-566`) — no outer
guard keeps a never-synced creator from reaching it, so the fix is on the real render path. The test
drives the actual page with `engagementRate: null` and with `4.2`, asserting both branches
(`creator-profile.engagement-rate-null.test.tsx:86-108`), and explicitly forbids the fabricated-`0%`
fallback. Correct, and the honest-empty idiom matches `creator-verified-metrics.tsx`.

**Adjacent risk, out of scope but worth a ticket:** `creator-profile.tsx:431` interpolates
`{social.engagementRate}%` raw for each connected platform. The FE type says that one is
non-nullable (`api.ts:3579  engagementRate: number`), but the backend record is a nullable
`BigDecimal` (`CreatorDtos.java:17`). That is the same FE-type-asserts-a-field-the-record-may-not-send
class, one line-of-sight away from the site just fixed. F-0464 nullified the profile-level field and
stopped; the per-platform one was missed.

---

## Gate detail

```
npx tsc --noEmit                    → exit 0   (no output)
npm run test:live                   → exit 0   (3 files, 16 tests passed)
npm test  /  npx vitest run         → exit 1   (3 files failed | 173 passed)
                                               (2 tests failed | 1040 passed | 3 skipped)
```

### Full-suite failures

Each of the six per-finding test files passes on its own. The suite as a whole does not. All three
failures trace to this wave.

**1. `src/__tests__/creator-protected-route.test.tsx` — Failed *Suite*, 0 assertions ran.**
This is the assertions-pass-but-process-exits-nonzero pattern the brief warned about, and it is why
"3 files failed" but only "2 tests failed."

```
Error: Hook timed out in 30000ms.
❯ src/__tests__/creator-protected-route.test.tsx:49:1
  49| beforeAll(async () => {
  50|   ({ CreatorProtectedRoute } = await import('@/App'));
```

`beforeAll` dynamically imports `@/App` — the whole route tree, and transitively every page module.
In isolation this file **passes, 3/3, in 13.3s**. Under full-suite parallel load it exceeds the 30s
`hookTimeout` and the file is scored as a failed suite with no test results at all. A file added by
this wave (`A src/__tests__/creator-protected-route.test.tsx`, F-0459 territory, not one of my eight
IDs) that reliably reddens `npm test`.

"It passes when I run it alone" is not a defence — CI is at least as loaded as this machine. Import
the specific module under test rather than all of `@/App`, or hoist the import out of `beforeAll`.

**2. `src/pages/__tests__/brand-auth-identity.test.tsx:152` — broken by the F-0463 fix.**

```
AssertionError: expected "spy" to be called 1 times, but got 0 times
```

This pre-existing test fills step 2 (`:145-149`: email, password, confirm, terms) and clicks Create
Account. It never fills the two fields F-0463 added, so the new `validateStep2` guard
(`brand-register.tsx:77-82`) correctly blocks submission and `brandRegister` is never called.

**The fix is right; the sibling test was not updated.** Add the two `user.type` calls and assert the
resulting `displayName`. Ananya wrote a brand-new test file for F-0463 and did not run the existing
one that drives the same form.

**3. `src/components/brand/contracts/__tests__/contracts-sign-reachability.test.tsx:146` — broken by
the F-0659 fix.**

```
AssertionError: expected Set{ 'draft', …(4) } to deeply equal Set{ 'draft', …(3) }
+   "completed",
```

A deliberate invariant test (F-0248/F-0252/F-0321) pinning the exact set of UI statuses the mapper
can emit. F-0659 added a fifth, `'completed'`. **The fix is right; the invariant needed updating in
the same commit** — that is the entire point of pinning a closed set. Add `'completed'` to the
expected set at `:146` and extend the F-0267 reachability loop to cover it (which would also have
caught the Download-PDF regression at `contracts-and-deliverables.tsx:1096`).

Neither #2 nor #3 means the underlying fix is wrong — both are correct fixes that broke a sibling
invariant and left it broken. But a red `npm test` is a shipping blocker, and a wave that turns the
suite red without noticing was not run before submission.

**Cosmetic, not blocking:** a pre-existing esbuild warning (`Duplicate key "baseUrl"`,
`tsconfig.json:20-21`) and React `act(...)` warnings in the brand-register and brand-chat suites.

---

## What I want back

**Blocking — the suite is red, fix before anything else:**

0. **Green `npm test`.** Three failures, all from this wave:
   (a) `creator-protected-route.test.tsx:49` — stop importing all of `@/App` in `beforeAll`;
   (b) `brand-auth-identity.test.tsx:145-149` — fill the two new name fields;
   (c) `contracts-sign-reachability.test.tsx:146` — add `'completed'` to the pinned status set.
   (b) and (c) are consequences of the two fixes that *were* correct; they should have shipped in
   the same commit as those fixes.

**Blocking, must be redone:**

1. **F-0640** — wire `milestones={liveContract?.milestones}` at `creator-chat.tsx:2880`, and change
   the test to render `creator-chat.tsx` (or add one that does). A component-level test can never
   catch an unwired prop. This one is a signing surface; it does not ship unwired.
2. **F-0434** — reassign to backend. Add `collabs` to `PortfolioPatchRequest`, persist it, and stop
   hardcoding `"logo"` at `PortfolioService.java:662`. Revert the FE line or leave it pending the
   DTO — but do not close the ticket on a key the server discards.
3. **F-0440** — fix `deal-room-dashboard.tsx:440-468`: either don't dismiss until the request
   settles, or reopen on the catch path. Correct the ledger `where` to
   `src/components/brand/deals/deal-room-dashboard.tsx`.
4. **F-0432** — decide and record: build a usage-rights control on `deal-room-dashboard.tsx` and
   `brand-campaign-detail.tsx`, or document why those two counters legitimately carry no rights
   term. Right now the same action loses terms depending on which screen it was taken from.

**Non-blocking follow-ups:**

5. New ticket: `contracts-and-deliverables.tsx:1096` — Download PDF unreachable for `completed`
   contracts (regression introduced by the F-0659 fix). Also `:1221` and the `:973` filter option.
6. New ticket: `creator-profile.tsx:431` — per-platform `engagementRate` raw interpolation; FE type
   `api.ts:3579` claims non-null against a nullable `BigDecimal`.
7. Close **F-0278** in `failures.jsonl:277`, credit `535e024`.
8. `tsconfig.json:20-21` — duplicate `baseUrl` key.

---

## Standing note for the team

Eight green test files, four unfixed findings. Every one of the four failed the same way: **the test
was written against something other than the defect.** F-0640 tested a component instead of the page
that mounts it. F-0434 tested that the client sends a key, never that the server has one. F-0440
tested a file that was never broken and never modified. F-0432 has no test at all.

A regression test on a file the wave did not touch is not evidence. Before submitting, the check is
one question: **would this test have failed yesterday?** If the source file has no diff, the answer
is no, and the finding is not closed. Run the revert-probe yourselves — it takes two minutes and it
is the difference between a fix and a green checkmark.

Second, and just as important: **run the whole suite, not your own file.** Six green files were
submitted on top of a red `npm test`. Two of the three failures are pre-existing invariant tests
that the *correct* fixes broke — a closed-set assertion and a form-filling helper. Those tests
existed precisely to fire on this kind of change; firing is them working. Updating them belongs in
the same commit as the fix, and noticing them requires one full-suite run before submission.

Third: `npx tsc --noEmit` and `npm run test:live` both pass on this wave. Neither would have caught
any of the eight problems in this report. Green gates are a floor, not a verdict.
