# 🐞 BRAND SURFACE — MASTER BUG TRACKER

> **Owner (document):** Priya Sharma — CTO
> **Routing authority:** Arjun (Eng Lead / COO)
> **Source of findings:** `BrandF.md` — 11-part static audit + CTO verification (Priya) + red-team verification (Kabir) across Dashboard, Campaigns, Deal Room, Discover/Chat, Payments/Wallet, Pipeline/Timeline/Contracts, Analytics/Reviews/Disputes, Settings/Notifications/Help, Two-Way Profiles, Billing/Onboarding, Meera (Brand AI)
> **Branch:** `fix/brand-audit-remediation`
> **Opened:** 2026-08-09
> **Companion doc:** [wiki/errors/CREATOR-BUG-TRACKER.md](CREATOR-BUG-TRACKER.md) — same format, creator-side surface

**THIS IS THE SINGLE SOURCE OF TRUTH FOR ALL BRAND-SIDE DEFECTS FROM `BrandF.md`.**
No brand bug from that audit is worked, closed, or re-opened anywhere else. One file. One status column.

> ⚠️ **Everything below is `believed` unless marked `proved` or `static-certain`.** `BrandF.md` is a static code trace — **no live server was probed, no payment was executed, no Meera turn was run.** `static-certain` means the evidence is a symbol with zero readers or a function with zero callers — no runtime probe can overturn that. `proved` (D-11 only) means a failing test demonstrates it. Everything else needs a live re-test before it moves to `DONE` — see [§4 Status Legend](#4-status-legend).

---

## 1. How defects map to experts (routing rule)

| Owner | Routes here when the fix is… | Agent |
|---|---|---|
| **Vikram** | Backend — Java/Spring API routes, DTOs/mappers, Prisma-style schemas, server-side jobs, idempotency/locking, role gates, the `influora-ai` Python service | Backend Developer |
| **Ananya** | Frontend — React components, client-side data fetching, dead controls, hardcoded fallbacks, realtime (SSE) subscriptions not opened client-side | Frontend Developer |
| **Vikram + Ananya** | Cross-cutting — the field/contract doesn't exist on one side and the other side fakes or drops it | Both, sequenced backend-first |
| **Swapnil** | Needs a product ruling before any code is written (is this a bug or by design?) | CEO — final authority |
| **Priya** | Architecture-level sign-off after Vikram/Ananya land a cross-cutting fix | CTO |
| **Kabir** | Security-sensitive fixes get an adversarial re-check before `DONE` (money path, auth, tenant isolation) | Red-team |

All code changes go **Vikram/Ananya → Kavya (QA) → Meera (local build/run verify) → live re-test** before `DONE`, same pipeline as the creator tracker.

> ⚠️ **Naming collision, resolved:** "Meera" is both the DB/DevOps agent **and** the in-product AI assistant audited in Part 11 of `BrandF.md`. The two `ME-*` rows below are about the **product feature** (the `influora-ai` Python service) and are routed to **Vikram** (backend/service owner), not to the Meera agent.

---

## 2. Status Legend

| Status | Meaning |
|---|---|
| `OPEN` | Not started, **and nobody is named** |
| `ASSIGNED` | Routed to a named owner; not started |
| `IN PROGRESS` | Being worked |
| `IN QA` | Code done → with Kavya |
| `IN VERIFY` | Kavya passed → with Meera for local build/run verify |
| `DONE` | Live re-tested against the deployed build and confirmed fixed |
| `BLOCKED` | Cannot proceed — blocker named in the row |

**A ticket is only `DONE` when it has been re-tested against a live, deployed build.** Code merged ≠ done — every row here is currently `believed`, not observed at runtime.

---

## 3. Summary Board — all 54 live defects

**39 filed through Part 8 (§78) · +5 Part 9 (§87, corrected) · +8 Part 10 (§105, corrected) · +2 Part 11 (§115). 0 CRITICAL, 1 oracle-proved (D-11), 2 struck as false alarms (excluded).**

> ✅ **MEDIUM severity: 19/21 DONE (2026-08-10).** All 19 code-fixable MEDIUM rows are closed — fixed, build/test-verified, and independently sign-off'd by Priya in a fresh-context review. Her independent pass found a real, non-cosmetic defect in nearly every one of the first 15 submissions before landing on ALIGNED — including two cases where the fix itself was actively wrong in a new way (C-2's demo fixtures pointed every contract at a *different* wrong deal room; ME-2's persona.py still told Meera she could propose the exact action the fix removed) — all were sent back and re-verified before being marked DONE. The remaining 2 (`H-1`, `DP-1`) are blocked on a Swapnil product ruling, not code, and were not attempted. Task: `brand-medium-remediation-0809`. Full trace in `.proof-os/journal.jsonl`; ~35 ledger findings opened along the way (F-0090–F-0153 range) — see each row's Evidence column for the ones specific to it. One recurring hazard: a concurrent, out-of-session process editing `DealService.java` silently overwrote one fix mid-task (F-0100) — re-applied and re-verified.
>
> ✅ **LOW severity: 21/21 DONE (2026-08-10).** Every LOW row below is closed — fixed, QA-passed, build-verified, and independently sign-off'd by Priya in a fresh-context review (not a same-thread rubber stamp). Her independent pass rejected 3 of the 21 on first submission (BL-5's correction was itself incomplete twice; OB-2 shipped a cache-invalidation bounce-loop; P-1's evidence-note fix hadn't landed yet) — all three were sent back and re-verified before being marked DONE. Task: `brand-low-remediation-0809`, closed. Full trace in `.proof-os/journal.jsonl`; 9 ledger findings opened along the way (F-0097, F-0098, F-0104, F-0135, F-0136, F-0141–F-0144) — see each row's Evidence column for the ones specific to it. HIGH severity is tracked separately and is **not** covered by this line.

| ID | Severity | Surface | Title | Owner | Status | Evidence |
|---|---|---|---|---|---|---|
| D-11 | 🔴 HIGH · **proved** | Discover → Chat | Invite/offer redirect drops the deal ID (2 failing tests) | Ananya | **DONE** | `BrandF.md` §38 — fixed, Priya-approved |
| P-1′ | 🔴 HIGH | Payments | Meera-funded escrow holds releasable by **neither** path | Vikram | **DONE** | `BrandF.md` §47a — fixed, Priya-approved (1 MEDIUM follow-up logged: F-0088) |
| P-3 | 🔴 HIGH | Contracts | `\|\| 50000` fabricates "₹50,000 secured in escrow" on a zero-value deal | Ananya | **DONE** | `BrandF.md` §57 — fixed, Priya-approved (1 MEDIUM follow-up logged: F-0089) |
| P-4 | 🔴 HIGH | Payments | `withdraw-${Date.now()}` idempotency key defeats server dedupe | Vikram + Ananya | **DONE** | `BrandF.md` §57 — fixed (frontend-only; backend already correct), Priya-approved with negative-control test |
| D-1 | 🔴 HIGH | Dashboard | Null runway renders as red "CRITICAL / 0d" on funded wallets | Ananya | **DONE** | `BrandF.md` §5 — fixed, Priya-approved via mutation-testing (2 same-class follow-ups logged: F-0099) |
| D-2 | 🔴 HIGH | Campaigns | List truncates at 100; pagination meta discarded, no pager | Ananya | **DONE** | `BrandF.md` §11 — fixed, Priya-approved (unblocked a pre-existing branch-red mock gap along the way, fixed by Arjun) |
| D-5 | 🔴 HIGH | Campaigns | `collaboratorsCount` always 0, rendered as fact | Vikram | **DONE** | `BrandF.md` §20 — fixed (batched, no N+1), Priya-approved (1 MEDIUM follow-up logged: F-0129) |
| M-1 | 🔴 HIGH | Deals list + chat (creator view) | Fabricated `brandVerified: true` hardcoded (`creator-deal-mappers.ts:184`) + unconditional badge (`creator-chat.tsx:1774-1777`) | Vikram + Ananya | **DONE** | `BrandF.md` §87 — fixed on top of PR-2, Priya-approved (unrelated tsc blocker logged: F-0149) |
| PR-1 | 🔴 HIGH | Creator profile (brand view) | Every creator renders 6 fabricated zeros under "Based on verified brand collaborations" | Ananya | ASSIGNED | `BrandF.md` §87 |
| PR-2 | 🔴 HIGH | Brand summary (creator view) | `verificationStatus` withheld from `BrandSummary` DTO | Vikram | **DONE** | `BrandF.md` §87 — backend fixed on BrandSummary + DealResponse, Priya-approved; FE consumption lands with M-1 next |
| BL-2 | 🔴 HIGH · static-certain | Billing | Cancellation never terminates — renewal job re-allots Pro credits forever | Vikram | ASSIGNED | `BrandF.md` §105 |
| BL-3 | 🔴 HIGH · static-certain | Billing | No idempotency/lock on checkout — double-submit orphans a live Razorpay subscription | Vikram | ASSIGNED | `BrandF.md` §105 |
| C-2 | 🟡 MEDIUM | Contracts | Demo `deal-1` id breaks "Open in Deal Room" on every real contract | Ananya | **DONE** | `BrandF.md` §62 — fixed on the real `collaborationId`; Priya caught the demo fixtures cross-wiring every contract to a *different* wrong deal room (worse than the original bug) — reverted demo to honest empty + disabled state, Priya-approved |
| M-B | 🟡 MEDIUM · static-certain | Notifications | Bell never refetches — data frozen at page-load for the whole session | Ananya | **DONE** | `BrandF.md` §78 — interval poll + open-triggered refresh; Priya caught the poll blanking an open popover to a spinner every 60s — fixed, Priya-approved |
| M-A | 🟡 MEDIUM · static-certain | Notifications | `NotificationBell.tsx` (204 lines) has zero importers — dead duplicate of the inlined bell | Ananya | **DONE** | `BrandF.md` §78 — dead file deleted (confirmed zero importers repo-wide), Priya-approved |
| H-1 | 🟡 MEDIUM · static-certain | Help | "Take the tour again" is dead — no tour component exists | Swapnil (build vs. cut ruling) | ASSIGNED | `BrandF.md` §74 — **blocked on product ruling, not code; not attempted** |
| N-1 | 🟡 MEDIUM | Notifications | Bell bypasses shared HTTP client — no 401 refresh | Ananya | **DONE** | `BrandF.md` §74 — routed through `notifications.list/markRead/markAllRead` (shared client, 401-refresh-and-retry); also fixed the client's own latent envelope-type bug along the way; Priya-approved (7 follow-ups logged: F-0090–F-0096) |
| PL-1 | 🟡 MEDIUM | Pipeline | SLA "at-risk" feature dead in live mode; filter button never renders | Ananya + Vikram | **DONE** | `BrandF.md` §69 — root-caused one layer deeper than filed: `DealService#toDealResponse` had `deliverablesDone/Total/nextDeadline` hardcoded 0/0/null (also creatorF.md C-4); computed for real from `Deliverable` rows, wired into `slaHoursRemaining`. Priya's first review found the backend half missing entirely — a concurrent process editing the same file had silently overwritten it (F-0100, logged); re-applied and confirmed via a genuine module-wide `mvn compile` this time, Priya-approved |
| PL-2 | 🟡 MEDIUM | Pipeline | 5th copy of the stage switch, in Java, disagrees with the board | Vikram | **DONE** | `BrandF.md` §69 — `DashboardService#bucketFor` now matches `mapCollaborationStatusToPipelineStage` exactly on all 13 `CollaborationStatus` values (verified one-by-one), Priya-approved |
| PL-3 | 🟡 MEDIUM | Dashboard | Pipeline card has no color for the `Completed` bucket | Ananya | **DONE** | `BrandF.md` §69 — color added; Priya's review found the deeper bug the ticket was actually filed against still shipping (white text on pale pastel backgrounds, ~1:1 contrast) — added matching `-fg` tokens per stage, Priya-approved |
| DP-1 | 🟡 MEDIUM* | Disputes | Brand cannot open a dispute — *documented as intentional; product question* | Swapnil (ruling) | ASSIGNED | `BrandF.md` §54.1 — **blocked on product ruling, not code; not attempted** |
| T-1 | 🟡 MEDIUM | Timeline | No realtime — never opens the SSE stream | Ananya | **DONE** | `BrandF.md` §61 — opens `api.messages.stream`, keyed on dealId only (matches brand-chat.tsx's own documented reasoning), Priya-approved |
| D-13 | 🟡 MEDIUM | Messages | `/brand/messages` no realtime — never opens the SSE stream | Ananya | **DONE** | `BrandF.md` §40 — same stream pattern as T-1, Priya-approved |
| D-12 | 🟡 MEDIUM | Messages | 10 enabled controls on `/brand/messages` with no handler | Ananya | **DONE** | `BrandF.md` §39 — all 10 disabled with honest tooltips instead of silent no-ops; Priya caught `disabled` + Tooltip making 5 of the explanations unreachable (`disabled:pointer-events-none` blocks hover) — switched to `aria-disabled` + manual styling, re-verified, Priya-approved |
| P-2 | 🟡 MEDIUM | Payments | Escrow funding reachable only through the Meera AI chat | Ananya | **DONE** | `BrandF.md` §48 — reused the existing, already security-reviewed `FundEscrowButton`/`useEscrowFund` (P11 commit-tier) unmodified, mounted on `/brand/wallet`'s Escrow tab; Priya caught a double-lock risk (re-funding an already-funded campaign) and a state-leak on mid-flight campaign switch — both closed, Priya-approved |
| D-8 | 🟡 MEDIUM | Deal Room | Sidebar "Deals" points at the Deal Room missing the shipment control | Ananya | **DONE** | `BrandF.md` §24 — repointed to `/brand/chat` (confirmed has the shipment control; `DealRoomDashboard` doesn't); Priya caught a second stale comment plus `/brand/deals*` losing sidebar highlighting — both fixed, Priya-approved |
| D-9 | 🟡 MEDIUM | Deal Room | Deliverable **Reject** route unreachable | Ananya + Vikram | **DONE** | `BrandF.md` §25 — added `canReject`/Reject button/modal onto the pre-existing backend route; Priya caught `reject()` missing the `requireNotCancelled` guard `approve()` has, newly reachable from UI as of this change — added, Priya-approved |
| D-3 | 🟡 MEDIUM | Campaigns | Progress bar always 0%; "Sort by Progress" dead — client-side fix doesn't work, needs backend | Ananya | **DONE** | `BrandF.md` §12 — `collaboratorsCount`/`completedCollaborations` were already real (D-5's fix landed since filing); progress now computed as `completed/total`, Priya-approved (1 product-definition question logged: F-0128, denominator includes non-committed applicants) |
| D-6 | 🟡 MEDIUM | Campaigns | `sortBy`/`sortOrder` accepted by backend, never sent by frontend | Ananya | **DONE** | `BrandF.md` §12 — `sortBy=createdAt` now sent for the 'Date' sort option, full-dataset server sort confirmed (not just the loaded page); Budget/Progress remain client-side (no backend Sort field for either), Priya-approved |
| BL-4 | 🟡 MEDIUM · static-certain | Billing | No role gate — any member can start or cancel the subscription | Vikram | **DONE** | `BrandF.md` §105 — `requireMember` + `requireRole(OWNER, ADMIN)` added to both `/billing/checkout` and `/billing/cancel`, matching the established pattern; verified no legitimate OWNER/ADMIN newly blocked, Priya-approved |
| VER-1 | 🟡 MEDIUM | Cross-cutting | `verificationStatus` absent from `DealResponse`/creator-facing DTOs — root cause of M-1/PR-2/OB-1 | Vikram + Ananya | **DONE** | `BrandF.md` §105 — backend (`DealResponse.counterpartyVerificationStatus`, `BrandSummary.verificationStatus`) already landed; Priya's review found the frontend `CreatorCampaignBrandSummary` type never got the matching field (real data on the wire, no way to read it) — added, Priya-approved |
| M-2 | 🟡 MEDIUM | Creator profile portfolio (brand view) | `CreatorMapper.java:47` always sends `Collections.emptyList()` | Vikram | **DONE** | `BrandF.md` §87 — single-profile read now hydrates real pinned posts via `PortfolioService`, respecting the creator's `contentPortfolio` visibility flag (same gate the public portfolio page honors); batch/list paths deliberately still empty (no N+1), Priya-approved (4 follow-ups logged: F-0137–F-0140, incl. no frontend renders the field yet) |
| ME-2 | 🟡 MEDIUM | Meera (AI, `influora-ai`) | Money tools offered but scope-gated out; Meera narrates an improvised apology instead of declining cleanly | Vikram | **DONE** | `BrandF.md` §115 — `get_tool_schemas()` no longer offers `request_payment`/`confirm_launch`; deterministic decline added as defense-in-depth for all 3 on-behalf rejection codes; Priya caught a contradicting persona.py rail still claiming Meera could "propose a payment" — rewritten, `PROMPT_VERSION` bumped, Priya-approved (3 follow-ups logged: F-0145, F-0146, F-0150) |
| N-2 | 🔵 LOW · **proved** | Notifications | `notifications.list`/`markRead` zero callers; no client for `/read-all` | Ananya | **DONE** | `BrandF.md` §75 — fixed, Priya-approved (independent verification, fresh-context) |
| M-C | 🔵 LOW · **proved** | Notifications | "View all notifications" only closes the popover — no `/brand/notifications` route | Ananya | **DONE** | `BrandF.md` §78 — fixed, Priya-approved (1 regression caught+fixed by QA: F-0098) |
| M-D | 🔵 LOW · **proved** | Notifications | Hook emits literal `Authorization: Bearer null` when no token stored | Ananya | **DONE** | `BrandF.md` §78 — fixed, Priya-approved |
| M-E | 🔵 LOW · **proved** | Notifications | Hook sends no `credentials: 'include'` | Ananya | **DONE** | `BrandF.md` §78 — fixed, Priya-approved |
| H-2 | 🔵 LOW · **proved** | Help | Quick-action cards are non-interactive divs — no keyboard path | Ananya | **DONE** | `BrandF.md` §76 — fixed, Priya-approved |
| H-3 | 🔵 LOW · **proved** | Help | Placeholder copy contradicts P-2/P-1′ ("escrow funding triggers automatically") | Ananya | **DONE** | `BrandF.md` §76 — fixed, Priya-approved |
| C-4 | 🔵 LOW · **proved** | Settings | Stale JSDoc claims `POST /me/password` may not exist — it does | Vikram | **DONE** | `BrandF.md` §75 — fixed, Priya-approved |
| DP-2 | 🔵 LOW · **proved** | Disputes | Superseded paginated `GET /brand/disputes` has no caller | Vikram | **DONE** | `BrandF.md` §69 — dead endpoint removed, Priya-approved |
| C-3 | 🔵 LOW · **proved** | Contracts | "Download PDF" disabled behind a factually wrong comment | Ananya | **DONE** | `BrandF.md` §62 — fixed, Priya-approved (1 stale line-citation in the fix's own comment caught+fixed) |
| C-1 | 🔵 LOW · believed | Contracts | `?dealId=` filter silently dropped for brands | Vikram | **DONE** | `BrandF.md` §62 — fixed, Priya-approved (believed: logic verified correct by inspection + tenant-scoping check, no test exercises the filtered branch) |
| PL-4 | 🔵 LOW · **proved** | Pipeline | `platforms: []` / `creatorFollowers: ''` hardcoded empty, undocumented | Vikram | **DONE** | `BrandF.md` §69 — documented (no DTO source exists), Priya-approved |
| PL-5 | 🔵 LOW · **proved** | Pipeline | `isAtRisk` short-circuits truthiness bug at the SLA boundary | Vikram | **DONE** | `BrandF.md` §69 — fixed, Priya-approved |
| D-14 | 🔵 LOW · **proved** | Discover | 4 backend creator endpoints with no caller — **one is PR-1's fix, already on the shelf** | Ananya + Vikram | **DONE** | `BrandF.md` §41 — all 4 wired, Priya-approved (PR-1's fabricated-zero-stats bug fixed as part of this; PR-1 itself still tracked separately above) |
| D-4 | 🔵 LOW · **proved** | Campaigns | Stale backend line-number citations in tracking JSDoc | Vikram | **DONE** | `BrandF.md` §13 — fixed, Priya-approved |
| D-7 | 🔵 LOW · **proved** | Campaigns | `/brand/campaigns/:id/tracking` has no inbound link | Ananya | **DONE** | `BrandF.md` §13 — fixed, Priya-approved |
| P-1 | 🔵 LOW · **proved** | Payments | `/wallet/escrow/release` has a client method with zero callers; `/wallet/escrow/payout` has no client method in either API layer — genuinely dead, LOW confirmed *(correction: the earlier "false alarm — reachable via 2nd API layer" note wrongly cited these two; the endpoint actually reachable via meera-api.ts is the unrelated `GET /wallet/escrow/{id}`)* | Vikram + Ananya | **DONE** | `BrandF.md` §54 — verified (no code change required), evidence note corrected, Priya-approved |
| BL-1 | 🔵 LOW · believed | Billing | `parsePlanCode` silently coerces invalid input to `PRO`; no `@Valid` | Vikram | **DONE** | `BrandF.md` §105 — fixed (invalid codes now rejected 400), Priya-approved (believed: correct by inspection, no test asserts the 400 yet) |
| BL-5 | 🔵 LOW · static-certain · **proved** | Billing | "Only ever written from a verified webhook" is false — **4** non-webhook write paths (re-derived from every write call-site; first correction undercounted as "two" and missed `cancel()` entirely — caught by Priya's review, F-0141) | Vikram | **DONE** | `BrandF.md` §105 — javadoc re-derived from every `subscriptionRepository.save`/`saveAndFlush` call site, not patched in place; Priya-approved |
| OB-2 | 🔵 LOW · static-certain · **proved** | Onboarding | `/brand/dashboard` has no onboarding guard; `onboardingCompleted` never read server-side | Vikram + Ananya | **DONE** | `BrandF.md` §105 — server-side status endpoint + `ProtectedRoute` guard added; Priya caught a post-completion cache-invalidation bounce-loop (F-0142) — freshly-onboarded brands were bounced back to `/brand/onboarding` for up to 5min — fixed with `queryClient.invalidateQueries`, Priya-approved |
| OB-1 | 🔵 LOW · **proved** | Onboarding | KYC prompt re-prompts across devices — dismissal is localStorage-only | Vikram + Ananya | **DONE** | `BrandF.md` §105 — server-side persistence added + test-proven, Priya-approved |
| ME-1 | 🔵 LOW · **proved** | Meera (AI) | `POST …/meera/interactions/option-tapped` has no caller — telemetry never sent | Ananya | **DONE** | `BrandF.md` §115 — fixed, Priya-approved (1 pre-existing MEDIUM gap logged separately: F-0144, the paired `OPTIONS_PRESENTED` event still can't be joined to it) |

*(`~~M-1~~` and `~~D-10~~` from Part 3 are struck false alarms — not counted, not listed.)*

---

## 4. Work queues by expert

### Vikram — Backend (23 rows: 6 HIGH · 8 MEDIUM · 9 LOW)
`P-1′` `D-5` `PR-2` `BL-2` `BL-3` `PL-2` `BL-4` `M-2` `ME-2` `C-4` `DP-2` `C-1` `PL-4` `PL-5` `D-4` `BL-1` `BL-5` `OB-2`
— plus 5 cross-cutting rows below, backend half first.

### Ananya — Frontend (24 rows: 4 HIGH · 12 MEDIUM · 8 LOW)
`D-11` `P-3` `D-1` `D-2` `PR-1` `C-2` `M-B` `M-A` `N-1` `PL-1` `PL-3` `T-1` `D-13` `D-12` `P-2` `D-8` `D-9` `D-6` `N-2` `M-C` `M-D` `M-E` `H-2` `H-3` `C-3` `D-7` `ME-1`
— plus 5 cross-cutting rows below, frontend half after Vikram's.

### Vikram + Ananya — Cross-cutting, backend-first (7 rows: 2 HIGH · 3 MEDIUM · 2 LOW)
`P-4` `M-1` `D-3` `VER-1` `D-14` `P-1` `OB-1`
— **do `VER-1` first**: it adds `verificationStatus` to the DTOs that `M-1`, `PR-2`, and `OB-1` all depend on. Sequencing it last means re-touching three already-closed tickets.

### Swapnil — Product ruling needed before code (2 rows)
`H-1` (build the tour or delete the dead control?) `DP-1` (brand-side dispute open — confirmed intentional, or scope it in?)

### Kabir — Adversarial re-check before `DONE` (money-path/auth rows)
`P-1′` `P-3` `P-4` `BL-2` `BL-3` `BL-4` `M-1` `PR-2` `VER-1` — already red-teamed once each in `BrandF.md`; re-check the shipped fix, not just the plan.

### Priya — Sign-off after cross-cutting fixes land
`VER-1`, `M-1`, `D-3` — same pattern as the creator tracker's CR-18/CR-23.

---

## 5. Sequencing note (do these first)

1. **`VER-1`** unblocks `M-1`, `PR-2`, `OB-1` — one backend DTO field, three tickets depend on it.
2. **`D-14`** is not dead weight — `GET /creators/profile/{usernameOrId}` already carries the real fields `PR-1` fabricates. Wiring the frontend to the existing endpoint is cheaper than building new backend logic.
3. **`BL-2`/`BL-3`** are the two HIGH billing bugs on the path that was wrongly praised as "the disciplined counterexample" in `BrandF.md` §90 — fix before any billing marketing claim ships.
4. **`P-1′`** (escrow exit unreachable) and **`P-3`** (fabricated escrow figure) are both money-path HIGH — treat as a pair, same root surface.

---

## 6. What this tracker does not establish

Every row is `believed`, `static-certain`, or `proved` per `BrandF.md`'s own evidence classing — **no live server was probed while compiling this tracker.** Moving a row to `DONE` requires the same bar as the creator tracker: Kavya QA pass → Meera local build/run verify → a live re-test against the deployed build, not a code-complete claim.

---

*Compiled from `BrandF.md` §78 (Part 8 FINAL register), §87 (Part 9 corrected), §105 (Part 10 corrected), §115 (Part 11 additions) — the four sections that supersede all earlier running totals in that document. 54 live defects, 0 CRITICAL, 1 oracle-proved, 2 struck as false alarms.*
