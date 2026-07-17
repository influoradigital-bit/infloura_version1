# TRACK-1 — Red-Team money-path audit (Kabir)

**Verdict: ✅ PASS** — transaction genuinely rolls back both writes, no double-earning reachable, self-proxy is real. → Kavya.

Scope: P0 restore of synchronous creator-commission creation on coupon redemption + the `@Lazy self` proxy fix that makes `@Transactional` real.
Files audited: `RedemptionService.java`, `AffiliateEarningsService.java`, `IdempotencyService.java`, `AffiliateEarningReconciliationJob.java`, `AffiliateEarning.java` + `AffiliateEarningRepository.java`, `V28__affiliate_earnings_settlement.sql`, `RedemptionServiceTest.java`, `application.yml`.

## Q1 — Is the `@Transactional` proxy genuinely invoked? YES
- `@Lazy RedemptionService self` constructor injection is the standard Spring self-reference-proxy pattern. The `@Lazy` proxy resolves on first use to the **Spring-managed bean = the CGLIB transactional proxy**, never the raw unwrapped instance. `self.doRedeem(...)`/`self.doRedeemScoped(...)` dispatch through it → `@Transactional` advice applies.
- `doRedeem`/`doRedeemScoped` are `protected` — CGLIB (default proxying for concrete `@Service`) **can** advise protected methods (it subclasses and overrides). Would only fail if `private`/`final`; they are neither.
- Proven at code level by `testDoRedeemInvokedThroughSelfProxy` / `testDoRedeemScopedInvokedThroughSelfProxy` (`verify(selfProxy).doRedeem(...)`).

## Q2 — Real rollback on `recordEarning` throw? YES — no split-state reachable
Transaction trace:
1. `redeem()` (not `@Transactional`) → `executeOnce(..., () -> self.doRedeem(...))`.
2. `self.doRedeem` (proxy) opens **TX1** (`REQUIRED`, no ambient tx).
3. `performRedemption`: save redemption → increment usage → audit → `affiliateEarningsService.recordEarning(redemption)`, **all in TX1**.
4. `recordEarning` itself is not `@Transactional`; it runs in ambient TX1 and calls `self.doRecordEarning(...)`, which is `@Transactional` **default `REQUIRED` → JOINS TX1** (it is NOT `REQUIRES_NEW`). Earning row saved in TX1.
5. If `doRecordEarning` throws → exception propagates uncaught out through `recordEarning` → `performRedemption` → `doRedeem`'s proxy → **TX1 marked rollback-only → redemption INSERT + `coupon.incrementUsageCount()` + audit + earning ALL roll back together.**

The only `REQUIRES_NEW` annotations in the path are on `IdempotencyService`'s internal `tryReserve/markCompleted/markFailed`, which are **self-invocation no-ops** (documented in that class). Their effect is actually correct here:
- **Outer** redemption-key reservation runs *before* TX1 exists → commits independently → survives rollback, gets marked `FAILED` → reclaimable by the webhook retry (`reclaimFailedForRetry`).
- **Inner** earning-key reservation runs *inside* TX1 → rolls back with everything → no orphaned `IN_PROGRESS`/`FAILED` earning key wedging the retry.

No `REQUIRES_NEW` actually takes effect to split the earning into a separately-committed transaction. **The committed-redemption-no-earning split-state is not reachable via the sync path.** Confirmed at code-contract level by `testRecordEarningFailurePropagatesForTransactionalRollback` (failure propagates uncaught, never swallowed into a degraded-success return).

## Q3 — Double-earning via sync + cron? NOT reachable
- Both paths call the **same** `recordEarning(redemption)` → same `replayIfPresent(findByRedemptionId)` guard + same derived key `"affearn:"+redemptionId`.
- DB backstops verified in **V28** (`V28__affiliate_earnings_settlement.sql`): `UNIQUE KEY uq_affiliate_earning_redemption (redemption_id)` **and** `UNIQUE KEY uq_affiliate_earning_idempotency_key (idempotency_key)`, plus FK to `coupon_redemptions(id)`.
- Post-fix, redemption + earning commit **atomically in TX1**, so a "redemption committed, earning missing" state never exists at commit. The cron's `findOrphanedWithoutAffiliateEarning(olderThan 30min)` therefore cannot observe a sync-created redemption without its earning; and even if it did, `recordEarning` short-circuits on the existing row. Cron is a pure idempotent caller (job javadoc + `AffiliateEarningReconciliationJobTest`).

## Q4 — Re-delivered webhook (same idempotency key)? Single redemption, single earning
- `redeem()` checks `replayIfPresent(idempotencyKey)` **first**, before any validation/mutation → returns the existing redemption; `doRedeem` and thus `recordEarning` never re-run. Proven by `testDuplicateIdempotencyKeyDoesNotDoubleCreateEarnings` (`recordEarning` invoked exactly once across two deliveries).
- If the first delivery rolled back (no redemption row, redemption key `FAILED`), retry reclaims the FAILED key and runs `doRedeem` fresh → exactly one redemption + one earning (new redemption ULID each attempt; the rolled-back one left nothing behind).

## Q5 — `recordEarning` after persist AND inside tx — contradictory? NO
- Called at line 369, **after** `redemptionRepository.save(redemption)` (line 343) so `redemption.getId()` (an in-memory ULID from line 335) and the persisted parent row exist within TX1, but **before** TX1 commits. Persisted-in-tx ≠ committed; the two are not in tension.
- FK `fk_affiliate_earning_redemption` is satisfiable within TX1: `application.yml` does **not** set `hibernate.order_inserts`, so Hibernate default (`false`) preserves `persist()` call order → redemption INSERT precedes earning INSERT at flush → FK satisfied, both commit atomically.

## Non-blocking notes for Vikram (documentation-only, money path is correct)
1. **Wrong migration version in javadoc.** `AffiliateEarning.java`, `AffiliateEarningsService.java`, `AffiliateEarningRepository.java`, and the reconciliation job all cite **V27** for the `affiliate_earnings` UNIQUE constraints; the real migration is **V28** (`V28__affiliate_earnings_settlement.sql` — V27 is `V27__shopify_integrations.sql`). Constraints do exist; only the cited version is wrong.
2. **Stale method name.** `AffiliateEarningReconciliationJob` javadoc references `IdempotencyService#runAndFinalize`; the actual method is `executeOnce`.
3. **Low-risk, verify when Testcontainers lands:** the earning-vs-redemption INSERT ordering relies on Hibernate default `order_inserts=false` (no ORM-level `@ManyToOne` exists since `redemptionId` is a plain column, so Hibernate can't topologically reorder them for you). If anyone ever sets `order_inserts=true`, add an explicit flush after the redemption save. Even today's worst case is **fail-closed** (FK violation → whole TX1 rolls back → retried), never a split-state, so it does not block.
