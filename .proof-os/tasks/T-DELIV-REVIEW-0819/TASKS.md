# T-DELIV-REVIEW-0819 — deliverable review chain: defects and owners

Opened by **priya** 2026-08-19 (revision rows F-0351..F-0354 added after review). Source: live walkthrough of the sign → fund → submit → review
chain on http://200.141.1.6 (deploy `7ea38f6`), deal `01KZ1TXGC97APN7YT0MKK0N0P1`.

Every row below was observed against a running system or read at the cited line — none inferred.

## Tasks

| ID | Defect | Where | Owner (solves) | Verifier | Done_When (gate) |
|---|---|---|---|---|---|
| F-0344 | `Request changes` posts `requestRevision(id, '')`; server rejects blank feedback → **every** click 400s | `src/pages/brand-chat.tsx:1274` → `BrandDeliverableService.java:223` | **ananya** | **neha** (live click) | A rendered click-test asserts `requestRevision` is called with non-empty feedback, and a live click on a SUBMITTED deliverable returns 200 |
| F-0345 | Two more `requestRevision` sites send feedback with no non-empty guard — empty textarea 400s | `contracts-and-deliverables.tsx:819`, `DeliverableViewer.tsx:283` | **ananya** | **kavya** | Both sites refuse to submit blank feedback client-side, mirroring `deliverable-review-panel.tsx:102` |
| F-0346 | `deliverablesError` is shared by load-failure and action-failure, so a failed action unmounts the whole list | `src/pages/brand-chat.tsx:1274` | **ananya** | **kavya** | A test asserts the deliverables list still renders after a rejected mutation |
| F-0347 | `submitForReview` never publishes to the deal stream, so an open brand room never sees a submission | `CreatorDeliverableService.java:405` + `brand-chat.tsx` `loadDeliverables` | **vikram** (BE publish) · **ananya** (FE handler) | **neha** (two-window live) | Creator submit emits a deal-stream event AND an already-open brand room updates without reload |
| F-0348 | `refreshAccessToken(role)` stores the returned token in the caller's role slot with no `userType` check → a BRAND login fills `creator_token` | `src/lib/api.ts:297-311` | **UNASSIGNED — see Blocker** | **kabir** (security) | A BRAND-userType token is refused for the creator slot; creator route guards validate the claim, not mere presence |
| F-0349 | Deliverables empty state tells the creator to submit on a 0-slot deal where no Submit control exists | `src/pages/creator-chat.tsx` deal-room panel | **ananya** | **kavya** | The 0-slot state names what actually unblocks it and never instructs the creator to submit. **REPEAT ×2 — needs a class gate, not a one-file fix** |
| F-0350 | "Pending review" badge renders in a near-invisible pale token on white | brand deliverables panel badge | **ananya** | **kavya** | Status badge tokens pass a contrast check in both themes |
| F-0351 | Creator's revision dialog is **unreachable** — `setShowRevisionHandler(true)` is never called | `src/pages/creator-chat.tsx:2929` | **ananya** | **neha** (live) | A creator whose deliverable is REVISION_REQUESTED can open the revision dialog from the UI |
| F-0352 | `brandFeedback` is a **hardcoded mock sentence**; `currentRevision`/`maxRevisions` hardcoded 1/2 — in the live path | `src/pages/creator-chat.tsx:2935` | **ananya** | **kabir** | No literal prose is passed to a `*Feedback`/`*Notes` prop outside a mock branch. **REPEAT of closed F-0236 mock-in-live — needs a class gate** |
| F-0353 | Creator is never shown the brand's feedback: API returns `reviewNotes`, no creator component reads it | `CreatorDeliverableDtos:42` → creator UI (no reader) | **ananya** | **kavya** | The creator revision surface renders the `reviewNotes` the API returns |
| F-0354 | Creator's 500-char revision notes are collected and **silently discarded** | `src/pages/creator-chat.tsx:1527` | **ananya** (FE) · **vikram** (DTO field) | **kavya** | Text entered in the revision dialog reaches a request body, or the field is removed |

## Blocker — registry gap (F-0348)

`src/lib/api.ts` matches **no producer's jurisdiction**. Services with coverage are `kavya`
and `kabir` (both `may_claim: echo` — judgment only, and echo can never render green),
`model-scheduler-audit`, `tester`, and `swapnil` (root). `ananya`'s jurisdiction is
`src/components/**` + `**/*.tsx`, which excludes `src/lib/*.ts`.

Assigning `ananya` would be a jurisdiction violation. Resolve by **either** widening
`ananya` to `src/lib/**` in `registry.json`, **or** routing F-0348 to `swapnil`. This is a
decision for the registry owner, not something this task may assume.

## Revision flow — the whole leg is broken, not just the request

`Request changes` (F-0344) is the entry point, and every step after it is also defective:

1. **F-0344** — the brand cannot successfully request a revision at all (always 400).
2. **F-0351** — if it succeeded, the creator's revision dialog cannot be opened.
3. **F-0352** — if it opened, it would show a hardcoded fake brand comment.
4. **F-0353** — the real feedback the API does return is read by no creator component.
5. **F-0354** — what the creator types back is dropped before any request.

The backend is not the problem: `applyRevision` stores the feedback (`Deliverable:253`),
`CreatorDeliverableDtos:42` exposes it, `CreatorDeliverableService:1148` populates it.
Every defect above is frontend.

## Similar-logic sweep — what was checked and what was found

| Class | Sweep run | Result |
|---|---|---|
| Hardcoded empty arg to a guarded mutation | all `src/**` calls of the form `.fn(x, '')` | **1 hit** — F-0344 only |
| `requestRevision` callers missing a guard | all 4 call sites read | 1 correct (`deliverable-review-panel.tsx`), 1 always-broken, 2 unguarded |
| Backend `... is required` guards vs FE callers | 13 guards enumerated | `deliverablesApi.reject` has **no FE caller** — its `feedback is required` guard is dormant, not a live bug |
| Mutation without live refresh | deal-stream publishers | only `DealService#sendMessage` publishes; deliverable events never reach the stream |

## Not checked

- Whether `contracts-and-deliverables.tsx` / `DeliverableViewer.tsx` disable submit on empty
  input in the DOM — only that no guard exists before the API call.
- Whether F-0350 fails a formal WCAG ratio — read from a screenshot, not a contrast tool.
- Any non-deliverable surface for the F-0348 token pattern.
