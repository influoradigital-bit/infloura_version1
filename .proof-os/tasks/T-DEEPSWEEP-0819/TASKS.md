# T-DEEPSWEEP-0819 — deep sweep: do the deliverable-review defect classes recur elsewhere?

Opened by **priya** 2026-08-19. Follows T-DELIV-REVIEW-0819 (F-0344..F-0354).

**Headline: no new defects. Every class is contained to the deliverable/revision surface.**
That is the finding — the sweep was run to falsify it and could not.

## Task table — what to fix, who solves it

No new rows were opened. The table below is the existing F-set, re-scoped by what the
sweep proved about each class's blast radius.

| ID | Defect | Where | Owner (solves) | Verifier | Blast radius (swept) |
|---|---|---|---|---|---|
| F-0344 | `requestRevision(id,'')` vs blank-feedback guard → always 400 | `brand-chat.tsx:1274` | **ananya** | **neha** | **1 of 434** files — no other hardcoded-empty-arg call |
| F-0345 | Two call sites send feedback with no guard | `contracts-and-deliverables.tsx:819`, `DeliverableViewer.tsx:283` | **ananya** | **kavya** | 2 of 4 `requestRevision` callers |
| F-0346 | Action error and load error share one state → failed action unmounts the list | `brand-chat.tsx:1274` | **ananya** | **kavya** | not swept — see Not checked |
| F-0347 | Submission never published to the deal stream | `CreatorDeliverableService.java:405` | **vikram** · **ananya** | **neha** | only `DealService#sendMessage` publishes at all |
| F-0348 | Role token slot written without `userType` check | `src/lib/api.ts:311` | **⚠ UNASSIGNED — registry gap** | **kabir** | single refresh path, repo-wide |
| F-0349 | Empty state instructs an action with no control | creator deal-room panel | **ananya** | **kavya** | **REPEAT ×2** with F-0278 |
| F-0350 | "Pending review" badge near-invisible | brand deliverables badge | **ananya** | **kavya** | not swept — needs a contrast tool |
| F-0351 | Revision dialog unreachable — setter never called with `true` | `creator-chat.tsx:2929` | **ananya** | **neha** | **1 of 434** files |
| F-0352 | Fabricated `brandFeedback` + hardcoded revision counters in the live path | `creator-chat.tsx:2934-2936` | **ananya** | **kabir** | **3 hits, all one file** of 427 |
| F-0353 | Creator never shown the brand's `reviewNotes` | creator UI (no reader) | **ananya** | **kavya** | see caveat below |
| F-0354 | Creator's revision notes collected and discarded | `creator-chat.tsx:1519` | **ananya** (FE) · **vikram** (DTO) | **kavya** | **1 of 434** files |

## Class sweeps — what was scanned, and what came back

| Class | Detector | Scanned | Hits | Verdict |
|---|---|---|---|---|
| Unreachable dialog (setter never called with `true` outside its own guarded subtree) | `.proof-os/gates/class/unreachable_dialog.py` | 434 files | **1** | contained — F-0351 only |
| Fabricated actor content / hardcoded counters in a live path | regex over `*Feedback\|*Notes\|*Comment\|*Review` literals ≥6 words + revision counters | 427 files | **3** | contained — all `creator-chat.tsx:2934-2936` |
| Hardcoded empty arg to a server-guarded mutation | `.fn(x, '')` over all `src/**` | 434 files | **1** | contained — F-0344 only |
| Collected-then-discarded input (`_`-prefixed handler param) | regex over handler signatures | 434 files | **1** | contained — F-0354 only |
| API field declared but never read in production code | 396 declared fields cross-referenced against all of `src/**` | 396 fields | 45 unread | **NOT a defect list — see below** |

## The 45 unread API fields are NOT 45 defects

Two spot-checks both dissolved on inspection:

- **`pdfUrl`** — unread, but the billing UI downloads invoices through
  `api.billing.downloadInvoicePdf(invoice.id)` instead. An alternative mechanism exists.
- **`gstAmount` / `hsnSacCode` / `creatorGstin`** — not rendered on screen, but the legal
  artifact is the server-generated PDF, which carries them. Absence from the screen is not
  absence from the invoice.

An unread field is a weak signal that needs per-field verification before it becomes a
ticket. Filing 45 rows off this list would have been 45 unproved claims rendering as facts.
The list is kept as a lead, not a finding.

## Method note — the first detector was wrong, and it mattered

The initial unreachable-dialog detector returned **0 hits across 434 files** and would have
concluded "no such class exists." It counted `onOpenChange={setter}` as an open path. But
when JSX is `{state && <Dialog onOpenChange={setter} />}`, the component is **not mounted
while state is false**, so that prop can never open it.

The corrected detector (saved as a gate) catches F-0351 and nothing else. A class detector
that has not been run against a known-bad instance is not evidence of absence — the first
version's clean result was indistinguishable from a real all-clear.

## Not checked

- F-0346's shared-error-state class was **not swept** — no detector was written for it.
- F-0350 contrast was **not measured** — no contrast tool was run; read from a screenshot.
- F-0353's shape (a field read by the *wrong role*) is invisible to the unread-field
  detector, because `reviewNotes` IS read — by the brand's `DeliverableViewer.tsx:441`.
  Role-scoped read coverage was not swept.
- Backend-side classes were not swept at all; every detector here targets `src/**`.
- No fix was written or verified by this task.
