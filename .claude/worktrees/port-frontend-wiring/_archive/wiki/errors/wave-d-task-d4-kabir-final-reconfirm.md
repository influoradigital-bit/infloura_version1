# Wave D Task D4: HIGH-1 Self-Invocation Fix — Kabir Final Re-Confirmation

**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team / Offensive Security)
**Scope:** Adversarial re-verification of Vikram's fix + Kavya's APPROVED re-QA (9.5/10) for my own original HIGH-1 finding (`wiki/errors/wave-d-task-d4-affiliate-earnings-security-review.md`). Task: don't rubber-stamp pattern-matching — probe what Kavya's functional QA structurally cannot see.
**Files re-read directly:** `RedemptionService.java`, `AffiliateEarningsService.java`, `ConfirmLaunchExecutor.java`, `AffiliateEarningReconciliationJob.java`, `CouponRedemptionRepository.java`, `CouponRedemption.java`, `InfluoraApiApplication.java`, `pom.xml`, `RedemptionServiceTest.java`, plus a full-repo grep for `@SpringBootTest`/`@EnableAspectJAutoProxy`/`@EnableTransactionManagement`.

---

## VERDICT: PASS — HIGH-1 is closed. Clears D4 for Meera's live-verify of migration V28.

One residual gap identified (see Probe 2) — not blocking, but it means "closed" rests on structural/framework-default reasoning, not on any booted-Spring-context proof anywhere in this codebase. Recording it explicitly rather than letting Kavya's 9.5/10 imply more certainty than the evidence supports.

---

## Probe 1: Does `@Lazy self` + CGLIB actually make this work in the real running app?

**Yes, with high confidence, for reasons Kavya's Mockito-only QA could not itself establish (she stated this; I independently verified the underlying facts rather than taking her word for it):**

1. **CGLIB proxying is active by default here.** `pom.xml` includes `spring-boot-starter-data-jpa`. Spring Boot's `TransactionAutoConfiguration` + `JpaBaseConfiguration` register `@EnableTransactionManagement` implicitly with `proxyTargetClass` effectively `true` for the JPA-backed transaction manager path — Spring Boot's autoconfigured `@EnableTransactionManagement` defaults to CGLIB (subclass) proxying unless AspectJ mode is explicitly selected, and grepping the entire `influora-api/src/main/java` tree confirms **zero** occurrences of `@EnableAspectJAutoProxy` or any AspectJ weaving configuration. So the proxy mechanism in play is CGLIB subclassing, not interface-based JDK proxies and not compile/load-time weaving.
2. **CGLIB's prerequisites are met.** CGLIB needs the bean class to be non-`final`, with a non-`final` target method, and an accessible (non-private) constructor. Verified directly: `RedemptionService` and `AffiliateEarningsService` are both plain non-`final` classes, `doRedeem`/`doRecordEarning` are `protected` (not `private`, not `final`) — `grep -n final` across both files returns zero class/method-level `final` modifiers. CGLIB can subclass and override both.
3. **`@Lazy` on the self-referencing constructor parameter is the correct, standard fix for the circular-proxy-construction problem** (bean X's constructor needs a reference to X's own proxy, which doesn't exist yet mid-construction) — `@Lazy` defers resolution to a lazy-initialization proxy that resolves to the real fully-constructed singleton (including its transactional-advice wrapper) on first use. This is textbook Spring, not a novel or fragile technique.
4. **Calling `self.doRedeem(...)` (a call *through* the injected proxy reference) is categorically different from `this.doRedeem(...)` (same-bean direct call).** The proxy intercepts any invocation that arrives via the proxy reference, checks for `@Transactional` on the invoked method, and wraps it in `TransactionInterceptor` advice before delegating to the real target. `this.` calls never pass through the proxy at all — they are plain JVM virtual dispatch on the raw target object, which is exactly the bug this fix targets. Routing through `self` restores the property that made the original (false) "still inside the transaction boundary" javadoc claim substantively true, rather than superficially true.

**No reason found in this codebase's actual configuration that would make CGLIB unavailable or defeat this fix.** No custom `BeanFactoryPostProcessor` overriding proxy mode, no `@Transactional(proxyMode=...)` overrides, no manual `ProxyFactory` usage anywhere competing with the autoconfigured setup. I treat this as structurally sound.

---

## Probe 2: Since tests can't prove real AOP interception, is there ANY other evidence this works at runtime? — RESIDUAL GAP, not blocking

This is the one place I push back on the QA chain's confidence level, including my own prior review's framing.

**Finding: there is no integration-level (booted Spring context) test anywhere in this codebase that proves the `@Lazy self` + CGLIB pattern actually intercepts a self-invoked call at runtime — not for `RedemptionService`/`AffiliateEarningsService`, and not for the `ConfirmLaunchExecutor` "precedent" either.**

- Grepped the entire `influora-api/src/test/java` tree for `@SpringBootTest`, `@SpringRunner`, `@ExtendWith(SpringExtension...)`. Exactly **one** hit in the whole codebase (`AuthRateLimitFilterTrackingBucketTest.java`), and it is unrelated to transaction/AOP behavior (rate-limit bucket logic). There is no `@DataJpaTest`, no `@SpringBootTest` anywhere that exercises `ConfirmLaunchExecutor`, `RedemptionService`, or `AffiliateEarningsService` with a real application context and a real CGLIB proxy.
- I checked both wiki documents that reference `ConfirmLaunchExecutor` as precedent (`wiki/errors/idempotency-fixes-E2-security-review.md`, `wiki/errors/idempotency-audit-E2.md`). Both confirm `ConfirmLaunchExecutor.execute()` **calls** `idempotencyService.executeOnce(...)` correctly (a code-reading fact) — **neither documents any integration-level proof that its `self.doExecute(...)` call is actually intercepted by a live proxy in a running app.** The precedent is real as *convention* (same pattern, written earlier, presumably running in whatever environment this app is deployed to without a reported incident) but it is **not** transitive *proof* — it is an unfalsified assumption that has simply been in production longer. Kavya's re-QA report (line 30) calls this "established codebase convention," which is accurate language — convention is not proof, and her report does not claim otherwise. My original HIGH-1 finding text made the same category of claim about the pre-fix code's transactional boundary based on a javadoc's stated intent rather than its runtime truth; I am holding the fix to the same standard I applied to the bug, and by that standard, this specific piece — "does this really intercept at runtime" — remains empirically unverified by anything in this repository.
- This is **not a reason to fail the gate**. The reasoning in Probe 1 is sound first-principles Spring/CGLIB behavior, not a guess, and `@Lazy`-self-proxy-for-self-invocation is a widely-documented, unremarkable pattern in the Spring ecosystem generally (not specific to this codebase). But "sound reasoning + zero repo-local runtime confirmation" is a materially weaker evidence class than "we booted the app and watched it roll back," and I am not willing to characterize this as "verified" when it is "soundly inferred."

**Recommendation (non-blocking, route as follow-up, not a gate on D4):** add one `@SpringBootTest` (or `@DataJpaTest` + a thin `@Import` of just `RedemptionService`/`AffiliateEarningsService`/`IdempotencyService`) that boots a real Spring context against a test DB (H2 or Testcontainers, whichever this codebase's convention favors — I did not find an existing convention either, which is itself worth noting to Meera/Priya as a broader gap), forces `recordEarning` to throw, and asserts the redemption row does NOT exist afterward. That is the one test that would move this from "soundly inferred" to "proven." Filing this as a follow-up, not a blocker — the absence of this test does not mean the fix is wrong, it means the fix's correctness rests on framework-default reasoning that nothing in this repository has ever exercised end-to-end, for this pattern, ever (including the precedent).

---

## Probe 3: Does the reconciliation job introduce new attack surface? Could it backfill a voided/refunded redemption?

**No new attack surface. The premise of the question (voided/refunded redemption) cannot occur in this schema — verified from the entity/table definition, not assumed:**

- Read `CouponRedemption.java` directly: the entity has **no status field, no void flag, no refund flag, no soft-delete column** — `id`, `couponId`, `orderId`, `orderAmount`, `discountApplied`, `customerId`, `redeemedAt`, `idempotencyKey`, `metadataJson`. Nothing else.
- The class javadoc (line 19) explicitly states the table is "Immutable, append-only per spec table §2.7." There is no code path anywhere in `RedemptionService` or elsewhere that updates or deletes a `CouponRedemption` row after creation (confirmed: `CouponRedemptionRepository` only exposes `save`, three `findBy*` queries, and the new orphan-sweep query — no `deleteBy*`, no update method).
- Therefore "a redemption that was legitimately voided/refunded" is not a state this schema can represent today. The reconciliation job's query (`r.redeemedAt < :olderThan AND NOT EXISTS (... AffiliateEarning e WHERE e.redemptionId = r.id)`) has no status/void/refund predicate to get wrong, because there is no such predicate to add — the query is complete relative to the actual schema, not incomplete relative to a richer model that doesn't exist here.
- **Query scoping is otherwise correct:** it only reads `CouponRedemption`/`AffiliateEarning` (no cross-tenant join, no workspace leak potential since neither entity is queried by workspace here — this is a system-wide backend sweep, not a tenant-facing read), and the 30-minute grace window (`RECONCILIATION_GRACE_PERIOD`) correctly excludes anything that could still be legitimately mid-transaction. `AffiliateEarningsService.recordEarning`'s own `UNIQUE(redemption_id)`-backed replay guard makes a double-backfill for the same redemption a structural no-op regardless of how many times the job's query returns the same row across runs (e.g., if `recordEarning` throws and the row remains orphaned, the next hourly run will find it again — correctly retried, not double-credited, since `doRecordEarning` only ever executes once per `redemptionId` thanks to the idempotency key derived purely from `redemptionId`).
- The job's own concurrency guard (`AtomicBoolean running`, single-JVM only, correctly not relied upon as the real safety boundary — mirrors the identical, already-accepted pattern in `AffiliateSettlementJob` from my own prior review) means even if this job ran on two nodes simultaneously, both would independently attempt `recordEarning` for the same orphaned redemption, and the DB-level `UNIQUE(redemption_id)` constraint (not the JVM flag) is what actually arbitrates — exactly the same proven-sound argument from Probe 2 of my original HIGH-1 report, re-applies cleanly here.
- One minor, non-blocking observation: the job logs backfill events at `log.warn` (line 105-110) rather than firing a metric/alert, meaning a human must be watching logs to notice repeated backfills (which would indicate the underlying transactional fix is somehow still failing in practice). This is an observability nicety, not a security gap — the data itself self-heals regardless of whether anyone is watching. Not filing as a finding; noting for completeness since Kavya's re-QA didn't call it out either.

**Verdict on probe 3: clean. No new attack surface. The "voided redemption" concern does not apply to this schema as it exists today.**

---

## Probe 4: Does the ledger-only / no-RazorpayX-disbursement sign-off still stand?

**Yes, unchanged, re-confirmed independently against the current code, not carried forward on faith:**

- This fix touches only `RedemptionService`, `AffiliateEarningsService`, and adds `AffiliateEarningReconciliationJob`. It does not touch `AffiliateEarning.Status`, `AffiliateSettlementBatch`, `AffiliateSettlementJob`, or anything in the RazorpayX/disbursement path. Confirmed via `grep` for `AffiliateEarning.Status` and `SETTLED` across the diff surface — no references introduced by this fix touch the status enum or its javadoc.
- My original sign-off's core concern — `SETTLED` being a misleading terminal-state name for a ledger-only system with no actual money movement — is entirely orthogonal to the self-invocation/transaction-boundary bug this fix closes. The fix makes the *accrual* step atomic and self-healing; it says nothing about, and does not change, what happens (or doesn't happen) after an earning reaches `SETTLED`.
- **Sign-off stands exactly as originally given:** ledger-only scope does not block merge; it blocks *production* deployment until the `SETTLED` naming/labeling ambiguity is addressed (my original report's item 4, minimum-bar fix (a)). Nothing in this fix changes that gate, and nothing in this fix should be read as having implicitly resolved it — I did not find any renaming or labeling change in `AffiliateEarning.java` while re-reading the current fix's diff surface, so that action item remains open and is Rohan/Priya's call on timing, unchanged from my original report.

---

## Consolidated Verdict

| # | Item | Result |
|---|------|--------|
| 1 | CGLIB proxy mechanics actually apply here (not just pattern-matched) | **CONFIRMED** — non-final classes/methods, JPA autoconfig enables CGLIB transaction proxying by default, no AspectJ weaving present to compete with it |
| 2 | Independent runtime proof beyond Mockito (own admission: tests can't prove this) | **RESIDUAL GAP (non-blocking)** — zero `@SpringBootTest`/integration tests anywhere in this repo for this pattern, including the `ConfirmLaunchExecutor` precedent; confidence rests on sound Spring/CGLIB first-principles reasoning, not on any booted-context observation in this codebase. Filing a follow-up recommendation, not a gate. |
| 3 | Reconciliation job's own attack surface / voided-redemption risk | **CLEAN** — `CouponRedemption` is schema-verified immutable/append-only with no void/refund/status concept to mis-scope; query and idempotency guard are correct |
| 4 | Ledger-only/no-disbursement sign-off still stands | **UNCHANGED** — re-confirmed against current code; production gate (item 4, `SETTLED` naming) remains open, untouched by this fix, Rohan/Priya's call |

**GATE BEHAVIOR:** HIGH-1 is CLOSED. D4 clears for Meera's live-verify of migration V28. The one residual item (Probe 2) is filed as a non-blocking follow-up recommendation (add one real `@SpringBootTest`/`@DataJpaTest` proving self-proxy interception end-to-end for this pattern generally — would also retroactively validate the `ConfirmLaunchExecutor` precedent, which has never been proven either) — not a re-open, not a blocker on Meera's migration verification. Production deployment of D4 still requires the previously-flagged, unrelated `SETTLED`-naming fix (my original report, finding #4) before it can go live — that gate is Rohan/Priya's, and is untouched by today's re-confirmation.

**Sign-off:** Kabir, clean. Routing to Meera for V28 live-verify.
