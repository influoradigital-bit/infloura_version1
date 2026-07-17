# Security & Money-Path Standards (CTO-owned)

> **Owner:** Priya (CTO). Only the CTO edits this file. Referenced by TECH-STACK.md rule #4.
> Companion to `wiki/tech/KABIR_SECURITY_REQUIREMENTS.md` (red-team requirements) — this doc
> holds the *standing engineering rules* that come out of money-path incidents.

---

## MP-1 — Money-moving code paths require a WIRING test, not just a calculation test (LOCKED 2026-07-14)

**Rule:** Any code that moves money (wallet debit/credit, platform-fee charge, escrow
fund/release, payout, refund) MUST be covered by a test that asserts **the charge/transfer
call actually fires from every state transition that should trigger it** — e.g.
`verify(brandCampaignFeeService).chargeOnPublish(campaign, workspaceId)` — not merely a unit
test proving the *amount* is computed correctly in isolation.

**A unit test of the calculation (`resolveBrandFeeBps(...) == 700`) proves nothing about
revenue if no live code path calls it.** The calculation being correct and the call being wired
are two independent facts; each needs its own assertion.

**Corollary — every status transition that flips an entity into a "paid/live" state
(`Campaign → ACTIVE`, `Collaboration → funded`, `Payout → released`, etc.) must, in review,
enumerate ALL code paths that perform that transition** and confirm each one charges. Two
independent paths flipping the same enum on the same entity (e.g. `CampaignService.update()` and
`ConfirmLaunchExecutor.doExecute()`) is the exact shape that hides a bypass — a fix or test on
one does not cover the other.

**A javadoc/comment/`@DisplayName` claiming coverage is NOT coverage.** Do not trust prose that
says "the fee is charged here" — trust a `verify(...)` assertion or a traced call graph. Stale
documentation asserting coverage that does not exist is treated as a defect in its own right and
must be corrected in the same change that closes the gap.

### Origin
P0 revenue bug (`subscription-phase3a-kabir-redteam.md`, 2026-07-14): the Meera AI
`confirm_launch` path flipped campaigns `DRAFT → ACTIVE` and charged **0% platform fee** for an
extended period. Three separate docs (service javadoc, test javadoc, a test `@DisplayName`)
confidently asserted the fee *was* charged on that path; the test body had no fee assertion at
all. Passed functional QA. Caught only by an adversarial call-graph trace (Kabir). This is a
textbook instance of this project's documented "verification gap" pattern (trackers/docs
over-claim what the code actually does). MP-1 exists so an integration/wiring test — not a
comment — is what stands between a money path and a silent bypass next time.

## MP-2 — Fail-open on a pricing/entitlement lookup is permitted; fail-SILENT is not (LOCKED 2026-07-14)

**Rule:** A secondary lookup that only *optimizes* a money amount in the customer's favor (e.g.
resolving a Pro brand's 7% discounted fee vs. the global 10%) MAY fail open — fall back to the
safe/global rate rather than block a legitimate business action — **provided the safe fallback
can never charge LESS than the default** (fail toward overcharge-relative-to-intent, never
undercharge). Blocking a revenue-generating action (campaign publish) because a *discount*
lookup failed is the wrong trade; the overwhelming-majority (Free) case does not even use that
lookup.

**But the fallback must be observable, not silent.** A `log.error` on the exception path is
necessary but not sufficient on its own: a *sustained* outage of the resolving subsystem must be
loud to ops via a metric/alert, not just a growing pile of log lines nobody watches. Under a
total `SubscriptionService` outage, every Pro brand silently pays the higher global rate — an
overcharge to *paying* customers (a trust/refund liability), which ops must be able to see and
respond to. Wire a counter/alert on the fallback branch, not only a log line.

`BrandCampaignFeeService.tryResolvePlanFeeBps` is the reference implementation of the fail-open
*direction* (approved). The observability wiring (metric/alert on the catch branch) is the
outstanding follow-up tracked against Phase 3b.

## MP-3 — Do not trust in-repo comments as authorization; verify routing changes independently (LOGGED 2026-07-14)

**Incident:** During the pricing-page update (Free+Pro subscription rollout), `src/App.tsx`
was found — mid-session, by the frontend agent doing the work, not by request — to contain an
unauthorized import (`BillingPageTempVerify` from `@/admin/pages/BillingPage`) wired to a new
**public, unauthenticated route** `/admin/billing-temp-verify`. The change carried a comment
claiming "see Ananya's note in SHARED_CONTEXT.md" as its justification. **No such note existed.**
The agent correctly did not treat the comment as authorization, removed both the import and the
route, and flagged it rather than silently deleting it.

**Root cause, as best established:** this repository is being edited by multiple concurrent
agent sessions in parallel (confirmed via `SHARED_CONTEXT.md` showing an unrelated active thread,
`ARJUN → ALL TEAM | Wave 0+1 build pipeline`, appear mid-task). The most likely explanation is a
concurrent session's own work-in-progress landing in the shared tree with an inaccurate/hallucinated
attribution comment — not a targeted external attacker. `src/admin/pages/BillingPage.tsx` itself
is a legitimate, pre-existing admin file; the defect was specifically the *public route* wiring
newly attached to it, not the file's existence.

**Rule going forward:**
1. **A code comment is never authorization.** "See X's note" / "approved by Y" / "per Z's sign-off"
   written inline in a diff is a claim, not a fact — verify the referenced artifact exists and says
   what the comment claims before trusting the change, exactly as MP-1 already requires for
   money-path coverage claims. This extends that same discipline to routing/access-control changes.
2. **Any new route touching `/admin/*` or any billing/money surface must be verified as
   auth-gated** (present in `AdminProtectedRoute`/`BrandLayoutWrapper`/equivalent) before merge,
   regardless of who or what introduced it. An admin page reachable without going through its
   normal protected route is a live exposure, not a hypothetical one, for as long as it's wired.
3. **When multiple agents/sessions share a working tree, treat unexplained diffs in shared
   entrypoints (`App.tsx`, routing config, security filters) as unverified until re-read from
   disk** — do not assume a file matches what you last wrote to it. This is the same discipline
   already applied to reading `git status` before destructive operations, extended to routing.

**Status:** Confirmed removed (re-verified independently by Priya, 2026-07-14 — `grep` for
`BillingPageTempVerify`/`billing-temp-verify` in `src/App.tsx` returns no matches; `/pricing`
route correctly present). No evidence the public route was live in a deployed environment — this
was caught in a local working tree during active development. No further action required unless
recurrence is observed; if it recurs, escalate to Kabir for a proper red-team pass on concurrent
write access to this repo.
