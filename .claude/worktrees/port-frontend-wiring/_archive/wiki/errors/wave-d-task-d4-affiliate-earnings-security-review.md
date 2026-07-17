# Wave D Task D4: Affiliate Earnings & Settlement — Kabir Load-Bearing Security Review

**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team / Offensive Security)
**Scope:** Adversarial re-verification of Kavya's QA APPROVAL (`wiki/errors/wave-d-task-d4-affiliate-earnings-qa-review.md`), same rigor as the E2 `PayoutService` rework (HIGH-1 payout wedge, HIGH-2 conversion pre-poisoning — both closed).
**Files re-read directly, not taken on Kavya's trace:** `AffiliateEarning.java`, `AffiliateSettlementBatch.java`, `AffiliateEarningsService.java`, `AffiliateSettlementJob.java`, `IdempotencyService.java`, `RedemptionService.java` (tracking package), `V28__affiliate_earnings_settlement.sql`, `AffiliateEarningsServiceTest.java`, `RedemptionServiceTest.java` (grepped for affiliate coverage).

---

## VERDICT: FAIL — 1 HIGH finding blocks merge until fixed. Ledger-only scope boundary is architecturally acceptable (see sign-off below) but is NOT the reason for the FAIL.

Kavya's QA gates 1, 2, 4, 5 hold up under adversarial re-reading — I independently traced the same line numbers and confirm the validate-before-executeOnce discipline, FAILED-retry discipline, BigDecimal math, and round-trip test are real. Gate 3 (double-payout impossibility) also holds for the settlement job itself. **However, probe #1 (the new coupling point Kavya's review did not stress-test from the redemption side) finds a real, shippable bug: an affiliate-earnings failure during `RedemptionService.doRedeem` silently loses the redemption's transactional integrity guarantee and, depending on exception type, can either corrupt the idempotency contract or silently drop commission accrual forever.**

---

## [SEVERITY: High]
**Title:** `RedemptionService.doRedeem` has no real transaction boundary — `AffiliateEarningsService.recordEarning` failure produces partial, non-atomic state, and non-`RuntimeException` failure modes drop commission accrual permanently

**Where:** `influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java`, `doRedeem` (lines 208-262), specifically line 246 (`affiliateEarningsService.recordEarning(redemption)`), in combination with `IdempotencyService.executeOnce` (`IdempotencyService.java` lines 79-100) and `redeem` (lines 159-202).

**Issue:**

Kavya's review (QA report line 234-248) states `recordEarning` is called "Still inside `doRedeem`'s `@Transactional` boundary" and treats this as confirmed-safe. **This claim is false**, and it is false for exactly the same class of reason `IdempotencyService`'s own javadoc (lines 113-124) already documents as a known Spring AOP pitfall in this codebase:

1. `redeem()` (the public entry point, NOT itself `@Transactional`) calls `idempotencyService.executeOnce(idempotencyKey, null, IDEMPOTENCY_SCOPE, () -> doRedeem(...))` (line 186-190).
2. That lambda closes over `this` and invokes `doRedeem` via **self-invocation** — `this.doRedeem(...)`, not through the Spring-managed proxy.
3. `doRedeem` is annotated `@Transactional` (line 208), but Spring's proxy-based AOP (confirmed: no `@EnableAspectJAutoProxy(proxyTargetClass=...)` or AspectJ weaving config anywhere in `influora-api/src/main/java`) **cannot intercept self-invoked calls**. The annotation is a no-op here, identical to the exact mechanism `IdempotencyService.java` lines 113-124 documents for its own `tryReserveTransactional`/`markCompletedTransactional`/etc.
4. Because `redemptionRepository`, `couponCodeRepository`, and (transitively, inside `AffiliateEarningsService.doRecordEarning`) `affiliateEarningRepository` are all plain `JpaRepository` interfaces (confirmed: `CouponRedemptionRepository extends JpaRepository<CouponRedemption, String>`, no custom transaction demarcation), each `.save()` call inside `doRedeem` runs in its **own** implicit transaction, not a shared one.

**Concretely, `doRedeem`'s actual (not documented) execution is four independently-committing steps:**
- Step A: `redemptionRepository.save(redemption)` — commits.
- Step B: `couponCodeRepository.save(coupon)` (usage-count increment) — commits.
- Step C: `affiliateEarningsService.recordEarning(redemption)` — this itself wraps a **separate** `IdempotencyService.executeOnce` call with its own reservation, and `AffiliateEarningsService.doRecordEarning` is itself `@Transactional` but *also* self-invoked from within `AffiliateEarningsService` (same bug, second instance) — so this step's `save()` + `auditLogService.recordMoneyEvent` also run outside any real transaction.
- Step D: `auditLogService.recordMoneyEvent(...)` (in `RedemptionService`) — commits independently.

There is **no rollback path**. If step C throws:
- The redemption row (step A) and the coupon usage-count increment (step B) are **already durably committed** — they do NOT roll back, because they were never in the same transaction as step C to begin with, self-invocation having already defeated `@Transactional` before this bug is even reached.
- `doRedeem` throws upward, uncaught (no try/catch around line 246).
- `IdempotencyService.executeOnce`'s `runAndFinalize` (line 102-111) catches the `RuntimeException`, calls `markFailedTransactional` for the **redemption's own** idempotency key (`IDEMPOTENCY_SCOPE = "redemption.redeem"`), and rethrows.
- The caller (the webhook controller, not yet built per `RedemptionService` javadoc, but per the class contract) receives an exception for what is, from the brand's commerce platform's point of view, **a redemption that already happened and was already recorded** (the row exists, usage count was incremented) but is reported as **failed**.

**This is worse than "does an affiliate-earnings failure risk rolling back a redemption that would otherwise have succeeded" (the probe as framed) — it's the inverse and arguably worse failure mode: the redemption is NOT rolled back (already committed), but the caller is told it failed.** Two concrete exploitable/harmful consequences:

1. **Retry-triggered `IDEMPOTENCY_KEY_IN_PROGRESS` reclaim, not double-processing, but a confusing 409 loop.** If the webhook sender retries (standard webhook behavior on a 5xx/exception), `redeem()` is called again with the same `idempotencyKey`. `replayIfPresent` (line 171-174) will now find the already-persisted redemption row (step A committed) and return it as a clean replay — **so in this specific instance, `recordEarning` is never retried**, because the caller path short-circuits before reaching `doRedeem` again. The creator's commission for this redemption is **permanently lost** unless `AffiliateEarningsService`'s own `UNIQUE(redemption_id)` guard is separately re-triggered by some other backfill path — which does not exist yet (confirmed: no cron/reconciliation job sweeps `coupon_redemptions` for rows missing a matching `affiliate_earnings` row). This is a **silent, permanent revenue-attribution loss for the creator**, not a crash — the brand's order still succeeded, the discount was still applied, but the affiliate commission is gone with no error surfaced to anyone after the first failed webhook delivery.
2. **Non-`RuntimeException` throwables are not even caught by `IdempotencyService`.** `runAndFinalize`'s catch clause is `catch (RuntimeException ex)` (line 107) — deliberately, per its own contract. `AffiliateEarningsService.recordEarning`'s only documented throw is `ApiException` (a `RuntimeException` subtype, confirmed by grep), so today's code paths stay inside that catch. But this means the "mark FAILED and rethrow" safety net Kavya's review leans on for gate 2 (FAILED-is-retryable) depends entirely on every exception in the whole call chain — including third-party JPA/Hibernate exceptions like `OptimisticLockException`, `LazyInitializationException`, or any `Error` — being a `RuntimeException`. This holds today by luck of what's implemented, not by structural guarantee, and is a latent trap for the next engineer who adds a checked-exception-wrapping call in this chain.

**Why Kavya's trace missed this:** her review confirmed the *textual* nesting ("still inside `doRedeem`'s `@Transactional` boundary") without verifying that the annotation is actually honored by Spring at runtime — i.e., she read the code's stated intent, not its self-invocation semantics. This is precisely the class of gap `IdempotencyService`'s own class javadoc flags for its *own* internal methods (lines 113-124) — the same pitfall exists one layer up, in `RedemptionService`, uncommented and untested. No test exists anywhere (`RedemptionServiceTest.java`, confirmed via grep) that exercises `affiliateEarningsService.recordEarning(...)` throwing — the mock is injected but never configured to fail. Kavya's QA report's "ADDITIONAL CHECKS / RedemptionService integration" section (lines 232-248) is a code-reading exercise, not a test-backed claim, and the review process treated it as PASS without a genuine failure-mode test — exactly the round-trip-test lesson from Wave C4 that gate 5 elsewhere in the same review correctly insists on, but wasn't applied here.

**Impact:**
- Creator commission silently and permanently lost on any transient failure in `AffiliateEarningsService.recordEarning` (DB blip, coupon lookup timeout, deadlock) that occurs after the redemption row is durably committed but the webhook is retried — no error surfaces to any human, no reconciliation path exists to detect or backfill the gap. This is a **money-accuracy bug**, not an availability bug: creators are underpaid with no signal.
- Separately, because there is no actual shared transaction, the documented safety claim ("still inside doRedeem's @Transactional boundary") is misleading for any future engineer relying on it to reason about atomicity of redemption + commission accrual — a latent trust problem in the codebase's own documentation.

**Fix:**
1. **Immediate, minimal:** wrap the `affiliateEarningsService.recordEarning(redemption)` call (line 246) in an explicit try/catch inside `doRedeem` that logs the failure loudly (structured log + metric/alert) and does NOT rethrow — i.e., make commission accrual best-effort-but-observable rather than tying its failure to the redemption's own idempotency key's FAILED state. This matches the actual intended semantics ("the redemption succeeded; the commission bookkeeping is a secondary concern") the code's comments already imply but the control flow doesn't implement.
2. **Structural, before this ships to production:** add a reconciliation job (mirroring `AffiliateSettlementJob`'s own sweep pattern) that periodically finds `coupon_redemptions` rows with no matching `affiliate_earnings.redemption_id` and calls `recordEarning` for them — this closes the silent-loss gap regardless of *why* the original call failed (transient DB error, deploy-time restart mid-request, etc.), and makes the system self-healing rather than dependent on every call site getting exception handling exactly right.
3. **Fix the self-invocation issue properly**, not just for this bug: either inject `RedemptionService` into itself via an `@Lazy`-qualified self-reference (the standard Spring workaround) so `doRedeem`'s `@Transactional` is actually honored by the proxy, or split `doRedeem` into its own `@Service` bean called from `redeem` through the injected proxy. The current code's comments (line 78, 242-246) assert transactional atomicity that does not exist — either make the claim true or remove it and design explicitly around its absence (option 1/2 above do the latter, which is the more honest fix given the additional complexity real cross-repository transactions would add here).
4. Add the missing test: `RedemptionServiceTest` should have a case where `affiliateEarningsService.recordEarning` throws, asserting on what actually happens today (redemption persists, exception propagates, retry short-circuits past `recordEarning` via replay) — this test would have caught the gap and should be treated as the regression guard once fixed.

---

## Probe 2: Double-payout impossibility re-verification (settlement job)

Re-derived the concurrent-interleaving argument independently rather than re-reading the test:

- Two threads T1 (cron trigger) and T2 (manual backfill retry) both call `runSettlementForPeriod("2026-06")` concurrently.
- `running.compareAndSet(false, true)` (line 132) is a single-JVM guard — correctly documented as NOT the real guarantee (line 128-129 javadoc). If T1 and T2 are on the *same* JVM, one of them bails immediately at this line. This is a liveness convenience, not the security boundary — correctly framed.
- Assume T1 and T2 are on different nodes (the actual threat model). Both reach `settleOneCreator(creatorId, "2026-06", batch)` for the same `creatorId`. Both compute `total` independently from the same `findByCreatorIdAndStatusIn` read (line 210-211) — both reads can return the same PENDING rows if timed before either writes.
- Both derive the identical key `"affiliate.settlement:" + creatorId + ":2026-06"` (line 217, 272-274) — deterministic, not batch-scoped, confirmed by direct reading, not just the unit test.
- Both call `idempotencyService.executeOnce(key, ...)`. Inside, `tryReserveTransactional` (line 126-138) does `repository.save(...)` inside `REQUIRES_NEW` — **this one actually works as documented**, because `tryReserveTransactional` is invoked via `this.tryReserveTransactional(...)` from within `executeOnce`, which is itself the entry point called by the *outer* proxy (i.e., `IdempotencyService` is called from `AffiliateSettlementJob` as `idempotencyService.executeOnce(...)`, a genuine cross-bean call through the injected proxy) — self-invocation only defeats `@Transactional` for calls *within the same bean*; here `AffiliateSettlementJob` → `IdempotencyService` is a real proxied call, so `IdempotencyService`'s internal self-invocation issue (documented in its own javadoc) affects only whether `tryReserveTransactional` etc. get their *own* fresh transaction — the outer `executeOnce` call from the job is unaffected and the `UNIQUE(idempotency_key)` constraint is what actually arbitrates, exactly as designed. I confirmed this distinction matters: the job-to-IdempotencyService boundary is a real proxy boundary; the redemption-service-to-itself boundary (the HIGH finding above) is not.
- One of T1/T2's `save()` hits the `UNIQUE(idempotency_key)` constraint and throws `DataIntegrityViolationException`, caught at line 135, returns `false` — the loser gets `AlreadyInProgressException` or proceeds to the FAILED-reclaim branch depending on timing, never both winning.
- `doSettleCreator` (line 256-260, called only by the reservation winner) mutates the earnings and saves. The loser never calls this method. **Double-settlement is genuinely structurally prevented** — I concur with Kavya's gate 3 conclusion, independently re-derived, not rubber-stamped.
- One residual, non-blocking observation: `doSettleCreator`'s `@Transactional` (line 255) is *also* self-invoked (called from `settleOneCreator` within the same class, via the lambda in `executeOnce`'s supplier) — same pattern as the HIGH finding. If it throws partway through iterating `settleable` (e.g., fails on earning #3 of 5), earnings #1-2 are already individually committed (no shared transaction) as SETTLED, earning #3 onward remain PENDING/FAILED. This is **not** a double-payout risk (each earning's own state transition is still idempotent — a retry only re-processes the ones still PENDING/FAILED), but it does mean a partial-batch failure is silently partially-successful rather than atomic, which contradicts the implied all-or-nothing framing of "settling a creator's earnings." Logging this as a **MEDIUM**, not blocking, since it doesn't create double-payment or fund-loss risk, only a batch-accounting granularity mismatch (a batch's `totalAmount`/`total_creators` could undercount if this partial-failure path is hit, since `batch.recordCreatorSettled(total)` at line 244 uses the full pre-computed `total` even though not all earnings in `settleable` may have actually flipped to SETTLED before the throw). Recommend the same self-invocation fix as the HIGH finding, applied here too, plus per-earning save with a running total rather than the pre-computed lump sum.

**Verdict on probe 2: Kavya's gate 3 conclusion (double-payout structurally impossible) holds.** My independent interleaving model confirms the DB-level `UNIQUE(idempotency_key)` constraint is the actual arbiter and does not depend on any of the broken self-invocation transaction assumptions elsewhere in this code. This is genuinely solid.

---

## Probe 3: Ledger-only / no-RazorpayX-disbursement scope boundary — explicit sign-off

Kavya flagged this needing Kabir+Rohan+Priya sign-off before production. My security-specific assessment:

**Is shipping a ledger without a disbursement path itself a security concern?**

Partially yes, but narrowly, and it is fixable with a documentation/status-naming change rather than a code gate:

- `AffiliateEarning.Status.SETTLED` (entity javadoc line 28-33) is described as "paid out in a monthly batch." **This status name is actively misleading given what the code does.** `SETTLED` in this codebase, for every other money-moving flow (`PayoutService`, `IdempotencyKeyRecord.COMPLETED`), has meant "the terminal, no-further-action state." Here, `SETTLED` means "we have decided you are owed this, and closed the book on re-deriving the amount" — but the money has **not** moved. Any downstream consumer (a future creator-facing dashboard, a finance export, a support agent reading the DB directly) that sees `SETTLED` and infers "this creator has been paid" is not misusing the field — they are using the field's own name and javadoc claim as intended, and being deceived by it. I independently confirmed via the frontend file `src/components/creator/AffiliateEarningsView.tsx` exists already — this is a live risk, not hypothetical, since a UI surface for this data already exists in this same wave.
- This is exactly the "trust/reporting gap" the probe asks about, and I confirm it is real: **a `SETTLED` earning could be mistaken downstream for "money sent,"** both by a human reading a report and by any future code that branches on this status (e.g., a tax/1099-equivalent export job built against this schema later, assuming `SETTLED` = paid).

**However, this is a naming/documentation risk, not an injection/access-control/auth-class vulnerability** — it does not let anyone forge, escalate, or extract money or data they shouldn't have. It's a **product-correctness / financial-reporting-integrity risk that happens to route through my desk because the earlier E2 gate set precedent for CFO/CTO sign-off on money-shaped code.**

**My explicit sign-off, as requested:**

1. **This does NOT block Wave D from progressing as a merge-to-feature-branch action.** The code is internally consistent, the javadoc is honest about scope *within the code*, and Kavya's gate on this (require Kabir+Rohan+Priya sign-off before *production* deployment, not before merge) is the correct gate placement — mirrors exactly how I handled the Wave C3 auth gap via ADR (documented gap, explicit sign-off requirement, not a code-level block).
2. **This DOES block production deployment until one of the following is done** (I recommend (a) as the minimum bar, (b)/(c) as the actual fix):
   - (a) **Minimum bar — rename or annotate the status to remove the ambiguity.** Either rename `SETTLED` to something unambiguous (`ACCRUAL_CLOSED`, `LEDGER_SETTLED`, `PENDING_DISBURSEMENT`) or, if renaming is judged too disruptive this late, add a hard-coded, impossible-to-miss note directly on `AffiliateSettlementBatch`/`AffiliateEarningsView.tsx` and any future finance-facing surface: "SETTLED = accrual finalized, NOT YET DISBURSED." This is a cheap, non-architectural fix and I do not consider Wave D production-ready without it, independent of the disbursement-timing decision in (b)/(c).
   - (b)/(c) Rohan/Priya's call on timeline — deferring actual disbursement to a later wave is fine *from a security standpoint* as long as (a) is done first, since (a) is what prevents the trust/reporting gap from being exploited-by-confusion (a support agent telling a creator "you've been paid" when they haven't, a finance report overstating actual cash movement, etc.).
3. I do **not** consider the hardcoded 10% commission rate a security concern — that is purely a product/pricing decision (Rohan's lane), correctly separated in Kavya's review. No sign-off needed from me on that half.

**Summary sign-off:** Ledger-only scope is architecturally fine and does not block merge. It blocks production only insofar as the `SETTLED` status name creates a genuine mistaken-for-paid risk that must be closed (cheaply, via renaming/labeling) before any human or downstream system can read this data and draw a financial conclusion from it.

---

## Consolidated Findings

| # | Severity | Title | Blocking? |
|---|----------|-------|-----------|
| 1 | **High** | `doRedeem` self-invocation defeats `@Transactional`; `recordEarning` failure silently and permanently loses commission accrual with no reconciliation path | **YES — blocks merge** |
| 2 | Medium | `doSettleCreator` same self-invocation pattern; partial-batch failure is silently non-atomic (accounting granularity, not double-payment) | No — fix this sprint |
| 3 | Info/Doc | `AffiliateEarning` entity javadoc references "V27" for its own migration; actual migration is V28 (Shopify took V27) | No — doc nit |
| 4 | Product/Doc (sign-off given above) | `SETTLED` status name creates a "mistaken for paid" trust gap given ledger-only scope | **Blocks production, not merge** — fix via rename/labeling before any finance-facing consumption |

## GATE BEHAVIOR

Per protocol: HIGH finding (#1) blocks deploy. Routing fix to Vikram (backend). Escalating the HIGH to Swapnil per Critical/High escalation rule (money-accuracy bug, silent creator underpayment, judged HIGH not Critical because it requires a failure+retry timing window to trigger, not directly exploitable by an external attacker — but flagging for Swapnil visibility given it's a money-correctness issue, matching the E2 precedent).

**Re-test required:** after Vikram fixes finding #1 (try/catch + reconciliation job, or self-invocation fix), re-run this review before any further sign-off. Findings #2/#3 can be fixed in the same pass. Finding #4 (status naming) is Rohan/Priya/product's call on exact naming but must land before production regardless of disbursement timeline decision.

**PASS/FAIL:** **FAIL** — do not merge until finding #1 is remediated. Findings #2, #3 are non-blocking (log to wiki, fix this sprint). Finding #4 blocks production, not merge, per the explicit sign-off above.
