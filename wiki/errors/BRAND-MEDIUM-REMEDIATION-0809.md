# Brand MEDIUM Remediation — 2026-08-10

> **Task:** `brand-medium-remediation-0809` (proof-os) · **Branch:** `fix/brand-audit-remediation`
> **Source tracker:** [wiki/errors/BRAND-BUG-TRACKER.md](BRAND-BUG-TRACKER.md) — this file is a work-log companion to it, not a replacement. The tracker's Status/Evidence columns are the source of truth; this doc explains *how* each row got there.
> **Scope:** all 21 🟡 MEDIUM rows in the tracker. HIGH and LOW severities were handled in separate sessions (`brand-high-remediation-0809`, `brand-low-remediation-0809`) and are not covered here.

## Result

**19 of 21 fixed, verified, and independently Priya-approved. 2 blocked on a product ruling, not code — not attempted.**

| Status | Count | Tickets |
|---|---|---|
| ✅ DONE | 19 | C-2, M-B, M-A, N-1, PL-1, PL-2, PL-3, T-1, D-13, D-12, P-2, D-8, D-9, D-3, D-6, BL-4, VER-1, M-2, ME-2 |
| ⏸ Blocked on product ruling | 2 | H-1 (build the tour or delete it?), DP-1 (is brand-side dispute-opening in scope?) |

## The loop, as it actually ran

Per-ticket: **implement → run the real oracle (`mvn compile`/`test`, `tsc --noEmit`, `pytest`) → dispatch Priya as a genuinely separate subagent, given only the changed files and the done_when — nothing of the implementer's reasoning → fix what she found → re-verify → journal → next ticket.**

That isolation mattered. Priya's fresh-context reviews were not rubber stamps — she found a real, non-cosmetic defect in nearly every submission before landing on ALIGNED, including two cases where the *fix itself* was actively wrong in a new way, not just incomplete:

- **C-2**: the first fix replaced "every contract links to one wrong deal room" with "each contract links to its own *different* wrong deal room" — the demo fixtures were cross-wired to unrelated creators. Reverted to an honest empty/disabled state instead of a plausible-looking guess.
- **ME-2**: the tool was correctly hidden from Meera's tool list, but `persona.py` still told her — 73 lines earlier in the same prompt — that she could "PROPOSE a payment request." Same failure the ticket exists to fix, in a different rail.

Two H-1/DP-1-adjacent risks were also caught and closed before they shipped:
- **D-12**: `disabled` + a Radix Tooltip on the same element silences the tooltip (`disabled:pointer-events-none`) — five of the ten "honest disabled state with an explanation" fixes had unreachable explanations. Switched to `aria-disabled` + manual styling.
- **P-2**: reusing the existing, already security-reviewed `FundEscrowButton` (P11 commit-tier control) was correct and it was left untouched — but the new wallet-page wiring around it had no guard against re-funding an already-funded campaign, and no protection against switching campaigns mid-payment. Both closed without touching the reviewed control itself.

## Notable non-ticket finding: concurrent-write collision

A process outside this session was independently editing `influora-api/.../service/DealService.java` — the same file PL-1 and VER-1 needed — for most of this run. It silently overwrote one of this session's fixes once (had to be redone), and its own in-progress, unrelated change (a counter-proposal feature) left the whole `influora-api` module non-compiling for a stretch, blocking a clean oracle run through no fault of this session's changes. Logged to the ledger as `F-0100`. Confirmed resolved by the end of the run — full `mvn compile` and `mvn test` both exit clean.

Also discovered mid-task: **VER-1's entire backend implementation, and most of its frontend, were already done** by that same concurrent process — this session's contribution there was verification plus one real gap it found (the `CreatorCampaignBrandSummary` frontend type never got the new field), not a from-scratch build.

## Per-ticket summary

| ID | What was actually wrong | What shipped | Priya's independent finding |
|---|---|---|---|
| **C-2** | Contracts page's "Open in Deal Room" fell back to a hardcoded `'deal-1'` for any contract not in a 3-entry demo lookup table | Real `collaborationId` threaded end-to-end (`ContractApiRecord` → `ApiContractRow` → `Contract`) for live mode | Demo fixtures cross-wired to wrong creators — reverted to honest empty + disabled buttons |
| **M-B** | Notification bell fetched once at mount, never again for the session | Interval poll (60s) + refetch-on-popover-open | Poll was blanking an *open* popover to a spinner on every tick — separated first-load `loading` from background refresh |
| **M-A** | `NotificationBell.tsx` (204 lines), zero importers, dead duplicate of the real inline bell | Deleted | — |
| **N-1** | Bell used raw `fetch` with a hand-rolled `Authorization` header — no 401 refresh, no credentials | Routed through `api.ts`'s shared `notifications` client (401-refresh-and-retry) | Client's own `list()`/`markRead()` were themselves mistyped/never-called dead code — fixed as part of wiring them in; 7 secondary findings logged |
| **PL-1** | SLA "at-risk" filter structurally always empty in live mode | Traced one layer deeper than filed: `DealService#toDealResponse` had deliverable done/total/deadline hardcoded 0/0/null | First backend fix was silently lost to the concurrent-write collision (F-0100) — redone, this time verified against a genuine full-module compile |
| **PL-2** | A 5th, independent copy of the pipeline-stage switch (in Java) disagreed with the frontend board on 3 of 13 statuses | `DashboardService#bucketFor` now matches the board's canonical mapping exactly | Verified all 13 `CollaborationStatus` values individually — no disagreement |
| **PL-3** | Dashboard pipeline card had no color for the `Completed` bucket | Color added | The *actual* bug the ticket was filed against was still shipping: white text on pale pastel backgrounds (~1:1 contrast) — added the matching `-fg` tokens |
| **T-1** | Brand collaboration timeline never opened a realtime connection | `api.messages.stream`, modeled on `brand-chat.tsx`'s proven pattern | Correctly keyed on `dealId` only, not the whole collaboration object — verified against the reference's own documented reasoning |
| **D-13** | `/brand/messages` never opened a realtime connection | Same stream pattern as T-1 | Aligned on first pass |
| **D-12** | 10 controls on `/brand/messages` rendered fully clickable with no handler — worst was a destructive-styled "Delete conversation" that did nothing | All 10 disabled with an honest tooltip/label | `disabled` + Tooltip silenced 5 of the 10 explanations — fixed with `aria-disabled` |
| **P-2** | Escrow funding reachable only through Meera's AI chat | Mounted the existing, unmodified `FundEscrowButton` (P11 security control) on `/brand/wallet`'s Escrow tab | Double-lock risk (re-funding an already-funded campaign) and a state-leak on mid-flight campaign switch — both closed at the integration layer, not the reused control |
| **D-8** | Sidebar "Deals" routed to the thinner Deal Room page missing the shipment control | Repointed to the full-featured page; corrected the comment that claimed the opposite | A second, adjacent stale comment plus a sidebar-highlighting regression on the old route — both fixed |
| **D-9** | Deliverable Reject had a working backend route with no UI path to reach it | Reject button + modal, wired to the pre-existing endpoint | `reject()` was missing the same-collaboration-not-cancelled guard `approve()` has — newly exposed to the UI by this change — added |
| **D-3** | Campaign progress bar hardcoded to 0% | `completed/total` computed from `collaboratorsCount`/`completedCollaborations`, which turned out to already be real (a HIGH ticket's fix landed since this was filed) | Aligned; flagged a product-definition question about whether the denominator should exclude pre-commit applicants |
| **D-6** | Backend accepted `sortBy`/`sortOrder`, frontend never sent them | Wired for the 'Date' option (the only one with a real backend Sort field) | Confirmed a genuine full-dataset server sort, not just a re-sort of the loaded page |
| **BL-4** | Any workspace member, including VIEWER, could start or cancel the paid subscription | `requireMember` + `requireRole(OWNER, ADMIN)` added to both endpoints | Traced the role check runs before the mutation in both methods; confirmed no legitimate OWNER/ADMIN newly blocked |
| **VER-1** | `verificationStatus` absent from brand-facing DTOs | Backend was already fully wired by the concurrent process; frontend `CreatorCampaignBrandSummary` type was still missing the field despite real data already on the wire | Found and closed the one real gap |
| **M-2** | Creator portfolio always sent `Collections.emptyList()` to brands | Single-profile read now hydrates real pinned posts, respecting the creator's visibility setting | Verified the visibility check is the *identical* flag the creator's own public portfolio page honors, not a looser one |
| **ME-2** | Meera offered money tools Spring would always reject on scope, then improvised an apology around the 403 | Tools removed from what's offered to Claude; deterministic decline added as a second layer for all 3 on-behalf rejection codes | `persona.py` still told Meera she could propose a payment — same failure in the prompt layer — rewritten, `PROMPT_VERSION` bumped |

## What's still open

- **H-1 / DP-1** — genuinely blocked on Swapnil, not code. Not attempted.
- **~35 ledger findings** opened during review (F-0090 through F-0153 range) — real but out-of-scope-for-this-ticket issues Priya's independent passes surfaced: missing test coverage on several new code paths, a handful of stale comments, an ambiguous product metric definition (D-3's progress denominator), a couple of accessibility gaps, and the concurrent-write-collision hazard itself. None of them block the DONE status above; they're tracked in `.proof-os/ledger/failures.jsonl` for whoever picks them up next.
- **No live deployed re-test.** Per the tracker's own §2 status legend, DONE here means fixed + build/test-verified + Priya fresh-context-approved — the same bar the HIGH and LOW remediation passes used. It does not mean re-tested against a live, deployed build.

---
*Compiled from `.proof-os/journal.jsonl` (task `brand-medium-remediation-0809`) and the ledger entries opened during this run.*
