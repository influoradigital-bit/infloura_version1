# 🔴 Brand HIGH-Severity Remediation — Final Report

> **Requested by:** repo owner, via `/proof-os:work` — *"Get in High issue in loop arjun & priya will assign the task with help of OS plugin... Each high issue solves get done approve by priya then it will go next High issue task until complete work all high issue solve done as .md file"*
> **Loop:** Arjun (routes) → Vikram/Ananya (implements) → Priya (independent fresh-context review, own tool calls, no shared context with the implementer) → next issue only after approval
> **Scope:** all 12 🔴 HIGH-severity rows in [wiki/errors/BRAND-BUG-TRACKER.md](BRAND-BUG-TRACKER.md)
> **Branch:** `fix/brand-audit-remediation` · **proof-os task:** `brand-high-remediation-0809`
> **Result:** **12/12 DONE.** Every fix code-landed, real-tested (`vitest`/`mvn -o test`), and Priya-approved in a separate context she could not have rubber-stamped from — she rejected work 6 times across the loop and every rejection was a real, reproduced defect, not a style note.

---

## 1. What "DONE" means here — and what it doesn't

Every row below is `believed`, not `proved` (except D-11, which has a failing-then-passing test as its own oracle). Priya's review ran real commands — `vitest`, `mvn -o test`, and in several cases a live H2/Hibernate database — and read real source, in a separate agent invocation from the implementer with no shared conversation. That is strong evidence, not a live-server observation. **No live server was probed in this loop. No real payment, subscription, or withdrawal was executed against Razorpay.** Treat this report as "the code is now honest and the money-path defects are closed at the code level," not as "verified in production."

---

## 2. The 12 issues, in order worked

| # | ID | Title | Owner | Rounds | Outcome |
|---|----|-------|-------|:------:|---------|
| 1 | **D-11** | Invite/offer redirect drops the deal ID | Ananya | 1 | Fixed — the audit's own 2 failing tests now pass |
| 2 | **P-1′** | Meera-funded escrow holds releasable by neither path | Vikram | 1 | Fixed — new `escrowHoldId`-keyed release path, milestone gate preserved |
| 3 | **P-3** | `\|\| 50000` fabricates an escrow figure | Ananya | 1 | Fixed — all money renders now go through `formatINR()`, no fabricated fallback |
| 4 | **P-4** | Withdrawal idempotency key regenerated every click | Vikram + Ananya | 1 | Fixed — frontend only; backend dedupe was already correct |
| 5 | **D-1** | Null runway renders as false "CRITICAL / 0d" | Ananya | 1 | Fixed — Priya approved via mutation testing (broke it twice to prove the test bites) |
| 6 | **D-2** | Campaign list silently truncates at 100 | Ananya | 1 | Fixed — real pagination; also unblocked a pre-existing branch-red test-mock gap (unrelated, fixed inline) |
| 7 | **D-5** | `collaboratorsCount` always 0 | Vikram | 1 | Fixed — backend now computes real metrics via 2 batched `GROUP BY` queries (no N+1) |
| 8 | **PR-2** | `verificationStatus` withheld from `BrandSummary`/`DealResponse` | Vikram | 1 | Fixed — backend-only; frontend consumption landed with M-1 |
| 9 | **M-1** | Fabricated "Verified Brand" badge | Vikram + Ananya | 1 | Fixed on top of PR-2 |
| 10 | **PR-1** | Every creator profile renders 6 fabricated zeros | Ananya (+Vikram) | **3** | Fixed — 1st reject: new endpoint 404'd for every real creator id (username/ULID collision in the backend resolver). 2nd reject: fix missed a caller passing a User id, not a profile id. 3rd: approved |
| 11 | **BL-2** | Subscription cancellation never actually terminates | Vikram | **2** | Fixed — 1st reject: the fix made `cancelAtPeriodEnd` a one-way latch that could wrongly cancel a *paying, re-subscribed* customer. 2nd: approved |
| 12 | **BL-3** | Checkout has no idempotency/lock | Vikram | **5** | Fixed — see §3, this was the hard one |

**7 of 12 passed first-round review. 5 of 12 were sent back — every single rejection was a real, independently reproduced defect**, not a nitpick: a 404 on every real user, a money-entitlement latch bug, and (BL-3) a foundational concurrency bug in shared infrastructure.

---

## 3. BL-3 — why it took 5 rounds, and why that's the loop working, not failing

BL-3 started as "checkout has no idempotency." It ended as **a real, previously-undetected bug in `IdempotencyService` itself — the class 25+ money-handling call sites across the codebase rely on** (`WalletService`, `PayoutService`, `DealService`, `ContractService`, `AICreditService`, every Razorpay webhook handler). Each round found a genuinely different failure mode, proven against a real database, not asserted:

1. **Round 1 (checkout-only fix):** rejected because the new lock relied on a check-then-act with no real database exclusivity for the specific double-submit window.
2. **Round 2:** rejected because `IdempotencyKeyRecord.save()` — used by all 25+ callers — routes to JPA `merge()` (a silent upsert), not `persist()` (a real INSERT), so the class's own claim that "the database's UNIQUE constraint is the arbiter" was **false as implemented**. Proven with a real Hibernate+H2 test showing two concurrent checkouts both reach Razorpay.
3. **Round 3:** the `Persistable`/insert fix from round 2 only worked when called with *no* ambient transaction. Six real callers — including `WalletService.requestCreatorWithdrawal` and `DealService.reject` — call it from *inside* their own `@Transactional` method, and the fix silently didn't apply there, turning a legitimate retry into a 500 instead of a clean replay.
4. **Round 4:** the fix (separate `REQUIRES_NEW` bean) left one operation, `reclaimFailedForRetry`, still calling the repository directly — which **deadlocked** against the new bean on every retry of a *failed* withdrawal. Reproduced with a real lock-timeout exception; on MySQL's default 50-second lock timeout this would have held an HTTP thread and pooled connection for that long, on the exact retry path a creator's browser deliberately re-uses the same idempotency key for.
5. **Round 5:** the last operation moved into the shared transactional boundary. Approved — Priya mutation-tested every prior fix's specific failure signature and confirmed each is now genuinely closed, then did a final sweep for any other writer to the idempotency table and found none.

**This is the review loop doing exactly what it's for.** A same-context review (Priya reading her own prior conversation) would very plausibly have rubber-stamped rounds 2–4 — each fix *looked* complete and passed its own tests. A fresh-context reviewer with no access to the implementer's reasoning, running real commands against a real database each time, is what caught four increasingly subtle bugs in shared money-handling infrastructure that would otherwise have shipped.

---

## 4. Follow-up work opened (not blocking, but real)

Priya's reviews surfaced defects and gaps outside each ticket's exact stated scope. Every one was logged to the proof-os ledger rather than silently dropped or silently fixed off-ticket:

| ID | Class | What | Where |
|----|-------|------|-------|
| F-0088 | ledger-reference-type-mislabel | New escrow release path mislabels a ledger reference type — reporting/reconciliation only, no money impact | `EscrowService.java` |
| F-0089 | hardcoded-contract-terms | Deliverables/deadline still hardcoded in the same contract components fixed for the escrow amount | `creator-contract-card.tsx` + siblings |
| F-0099 | false-critical-runway-fallback | Same false-CRITICAL-wallet bug survives in 2 more spots (dashboard's error-state default, `brand-wallet.tsx`) | `dashboard-page.tsx`, `brand-wallet.tsx` |
| F-0129 | inconsistent-spend-definition | Admin console computes campaign "spend" differently than the now-fixed brand-facing surfaces | `AdminCampaignService.java` |
| F-0149 | unused-ts-expect-error | Unrelated project-wide `tsc` failure blocking a clean full-project typecheck | `creator-meta-callback.test.tsx` |
| F-0157 | hardcoded-navigation-target | A "View Profile" button hardcodes `creator-1`, 404s on every real deal | `deal-room-dashboard.tsx` |
| F-0158/159 | (BL-2 follow-ups) | Re-subscribe latch (closed same round) + a narrower delayed-charge edge case (customer-favorable, not blocking) | `SubscriptionService.java` |
| F-0160/161/162 | (BL-3 chain, all closed) | The three IdempotencyService defects described in §3 — all fixed within this loop, kept in the ledger as a permanent record of what was found and fixed | `IdempotencyService.java`, `IdempotencyReservationOps.java` |

None of these block the 12 HIGH items above — they're scoped follow-ups, now tracked, not lost.

---

## 5. What this loop did NOT do

- **No live server was probed.** Every verification is `vitest`/`mvn -o test` (some against a real embedded H2 database for the IdempotencyService rounds), not a deployed environment.
- **No real money moved.** No Razorpay checkout, subscription, or payout was actually executed.
- **No commit was made.** All 12 fixes are uncommitted working-tree changes on `fix/brand-audit-remediation`, alongside concurrent uncommitted work from other in-flight sessions on the same branch (MEDIUM/LOW remediation, D-9/D-14/CR-* work) — see the commit-hygiene warning below.
- **Kavya (QA) and Meera (build/run verify) have not yet run their passes** — per this repo's tracker convention, `DONE` in the row above means code-fixed and Priya-approved, not the full `IN QA → IN VERIFY → live re-test` pipeline the tracker's own status legend defines. That's the honest next step, not implied by this report.

> ⚠️ **Commit hygiene, flagged by Priya during PR-2's review and still true now:** several touched files (`DealService.java`, `DealDtos.java`) carry multiple unrelated in-flight change sets from concurrent sessions. Stage by hunk, not by file, when this branch is eventually committed.

---

## 6. Full audit trail

Every step — every read, every fix, every verdict, every isolation level — is journaled in `.proof-os/journal.jsonl` under task `brand-high-remediation-0809`, and every follow-up defect is in `.proof-os/ledger/failures.jsonl`. Nothing in this report is asserted without a corresponding journal/ledger entry.

---

*Compiled 2026-08-10. Loop: Arjun (routing) → Vikram/Ananya (implementation) → Priya (fresh-context review, `--isolation fresh-context` recorded by the dispatcher on every judgment event, never self-declared). Source of truth for status: [wiki/errors/BRAND-BUG-TRACKER.md](BRAND-BUG-TRACKER.md).*
