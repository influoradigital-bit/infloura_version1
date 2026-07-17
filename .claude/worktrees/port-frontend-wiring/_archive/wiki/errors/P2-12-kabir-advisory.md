# P2-12 — Kabir Money-Path Advisory: Payout KYC Fund-Account Lookup

**Reviewer:** Kabir (Red-Team / Offensive Security) · **Date:** 2026-07-13
**Scope:** Code-level security/correctness advisory only. No live Razorpay sandbox smoke test was run or fabricated — this sandbox has no test credentials or outbound network path. That smoke test remains a separate, mandatory pre-production gate for whoever deploys this (already flagged by Meera and Priya).

## Files actually read (not trusted from notes)
- `influora-api/src/main/java/com/influora/service/PayoutService.java`
- `influora-api/src/main/java/com/influora/service/payout/RazorpayFundAccountService.java`
- `influora-api/src/main/java/com/influora/service/payout/CreatorBankAccountService.java`
- `influora-api/src/main/java/com/influora/repository/PayoutRepository.java`, `CreatorBankAccountRepository.java`
- `influora-api/src/main/resources/db/migration/V48__payouts.sql`, `V49__creator_bank_razorpay_fund_account.sql`
- `influora-api/src/main/java/com/influora/service/IdempotencyService.java`, `domain/entity/IdempotencyKeyRecord.java`, `repository/IdempotencyKeyRecordRepository.java`
- `influora-api/src/main/java/com/influora/domain/entity/CreatorBankAccount.java`
- `influora-api/src/main/java/com/influora/service/security/CreatorBankPiiCipher.java`, `AesGcmCipher.java`
- `influora-api/src/main/java/com/influora/config/PiiEncryptionProperties.java`
- `influora-api/src/main/java/com/influora/integration/razorpay/RazorpayXClient.java`, `RazorpayWebhookController.java`
- `influora-api/src/main/java/com/influora/web/EscrowController.java`, `service/BrandContextService.java` (authz path)

## Verdict: **APPROVE WITH CONDITIONS**

The core money-safety properties hold up under direct code inspection. One concrete, verifiable defect exists in the idempotency-retry story that contradicts what the code comments (and Meera's note) claim — it does not create double-pay risk, but it will silently and permanently strand legitimate payouts on any transient RazorpayX failure. Fix or explicitly accept-with-runbook before this is called production-ready on a money path.

---

## What I verified as sound

**1. No double-pay via replay/retry.** `PayoutService.queuePayout` derives `idempotencyKey = "payout:" + milestoneId` server-side (never client-supplied), checks `replayIfPresent` before touching the gateway, then reserves the key via `IdempotencyService.executeOnce` — a plain `INSERT` into `idempotency_keys` whose `UNIQUE(idempotency_key)` constraint (not application check-then-act) is the actual concurrency arbiter. A concurrent duplicate request loses the DB insert race, never reaches `RazorpayXClient.initiatePayout`, and is told to replay the winner's result (`PayoutService.java:146-165`). Belt-and-suspenders: `RazorpayXClient` also passes the same key as RazorpayX's own `X-Payout-Idempotency` header/`reference_id` (`RazorpayXClient.java:72,80`), and `payouts.idempotency_key` / `payouts.razorpay_payout_id` are themselves DB-`UNIQUE` (`V48__payouts.sql:8,13`). Three independent layers would all have to fail for a double payout to occur. **Verified true, not just documented.**

**2. Ownership-before-state / no IDOR.** `validateForPayout` checks the escrow hold's `workspaceId` against the caller's workspace *before* checking RELEASED state (`PayoutService.java:202-213`) — an unauthorized caller always gets `MILESTONE_NOT_FOUND`, never a state oracle. `creatorUserId` passed into fund-account resolution comes from `collaboration.getCreatorId()` — a server-side lookup chained from the milestone, never from client input. `RazorpayFundAccountService.resolveFundAccountId` further scopes the bank-account lookup via `findByIdAndCreatorUserId` (ownership-scoped query, not a bare `findById`). `queuePayout` itself is gated by `BrandContextService.requireMember` (active workspace membership check, `BrandContextService.java:58-66`). I could not construct a path where creator A's milestone resolves to creator B's bank account. **No IDOR found.**

**3. Amount is never client-trusted.** `doQueuePayout` always re-derives the amount from `milestone.getAmount()` (`PayoutService.java:281,291,311`); `RazorpayXClient.initiatePayout` takes the amount as a parameter it never sources from any request DTO. No client-supplied amount field exists on this path at all.

**4. Race conditions are DB-arbitrated, not app-arbitrated.** Confirmed the actual atomicity comes from the single `INSERT` + `UNIQUE` constraint in `IdempotencyService.tryReserveTransactional`, not from `@Transactional` boundaries (the class's own javadoc correctly flags that `@Transactional` is a no-op here due to Spring AOP self-invocation — `IdempotencyService.java:76-85` — and that's fine because the real guarantee doesn't depend on it).

**5. PII handling matches the codebase's existing pattern.** `AesGcmCipher` uses AES-256-GCM with a fresh random 96-bit IV per encryption (`AesGcmCipher.java:51-52`), 256-bit key enforced at construction, distinct key namespace (`influora.pii.bank-encryption-key`, separate from email/phone key) via `PiiEncryptionProperties`. Decrypted account/IFSC values in `RazorpayFundAccountService.resolveFundAccountId` are used only in-memory to build the Razorpay API call and are never logged — I checked every `log.info`/`log.debug` call on this path and none include the decrypted values, only IDs. `CreatorBankAccountService` never persists plaintext (no plaintext columns exist on the entity at all).

**6. 24h cool-down is real and not bypassable via edit.** `CreatorBankAccountService` has no update-in-place method — adding a bank account always creates a brand-new row via `createEncrypted` with a fresh `usableAfter = now + 24h` (`CreatorBankAccountService.java:71-81`). `RazorpayFundAccountService.resolveFundAccountId` checks `bankAccount.isUsableAt(now)` and throws `BANK_COOLDOWN_ACTIVE` (429/`TOO_EARLY`) before any fund-account provisioning or reuse (`RazorpayFundAccountService.java:69-74`) — checked *before* the cached-fund-account-id short-circuit, so even a previously-provisioned account added <24h ago would still block (though in practice a cached account is by definition already past cool-down the first time it was provisioned). An account-takeover attacker who adds a new bank account cannot redirect a payout within the 24h window. This is the fraud control M-K6-C3-2 intended and it holds.

---

## Confirmed defect — not a fund-safety bug, but a real reliability/availability bug on the money path

**FAILED idempotency keys are not actually retryable, contradicting the code's own claims.**

`PayoutService`'s class javadoc (lines 59-71) explicitly claims: *"a transient RazorpayX failure (timeout, 5xx) is also no longer a permanent wedge: the very next legitimate call re-validates, reclaims the FAILED key, and retries the gateway call."* The same "FAILED keys are re-runnable" claim is repeated verbatim across `AffiliateSettlementJob.java:74`, `AffiliateSettlementBatch.java:26`, `AffiliateEarning.java:31`, all pointing back to `IdempotencyService`'s class javadoc as the source of truth.

I read `IdempotencyService.java` directly. It implements no such reclaim:

- `executeOnce` (line 54): on reservation failure, looks up the existing row and throws `AlreadyCompletedException` only if `status == COMPLETED`; **every other status (`IN_PROGRESS` or `FAILED`) throws `AlreadyInProgressException`** — identical handling for both.
- `tryReserveTransactional` (line 88): a bare `repository.save(new row)` inside a try/catch for `DataIntegrityViolationException`. There is no `UPDATE ... SET status = 'IN_PROGRESS' WHERE status = 'FAILED'`, no delete-then-reinsert, no conditional reclaim logic anywhere in this class or in `IdempotencyKeyRecordRepository` (which exposes only `findByIdempotencyKey` — no update/delete query at all).

**Concrete impact on this payout flow:** if `RazorpayXClient.initiatePayout` throws (network timeout, RazorpayX 5xx, or any exception from the subsequent `payoutRepository.save`/`milestoneRepository.save`), `executeOnce`'s catch block marks the key `FAILED` and rethrows. The row is never cleaned up. On the brand's next (entirely legitimate) retry of `queuePayout` for the same milestone:
1. `validateForPayout` passes again (milestone state unchanged).
2. `executeOnce` → `tryReserveTransactional` fails the `INSERT` (PK still exists) → existing status is `FAILED`, not `COMPLETED` → throws `AlreadyInProgressException`.
3. `queuePayout`'s catch calls `replayIfPresent`, which returns `null` because `milestone.markPayoutQueued(...)` was never reached on the failed attempt.
4. Falls through to `throw new ApiException("IDEMPOTENCY_KEY_IN_PROGRESS", "This payout is already being processed — retry shortly", 409)`.

That 409 will repeat **forever** — "retry shortly" is false. The milestone's payout is now permanently stuck with no in-app recovery path; the only fix is a manual DB operation (delete/reset the `idempotency_keys` row for that key). This is exactly the class of bug the code comments say was fixed (E2 audit finding #9) — it was fixed for the *validation-before-reservation* half of the problem (genuinely verified above, real fix), but the *FAILED-is-retryable* half described in the same paragraph does not exist in `IdempotencyService`.

Note this is a stuck-money-in-limbo/availability problem, not a security or double-pay problem: if the RazorpayX call itself actually succeeded before a later step failed (e.g. `payoutRepository.save` throwing after the gateway call went through), Razorpay's own idempotency (`X-Payout-Idempotency`/`reference_id`) means a safe retry at the gateway layer would just return the same payout — but our own `IdempotencyService` never lets that retry happen, so the operational fallout is "creator paid at Razorpay, but our system shows the milestone permanently wedged / no local `payouts` row," requiring manual reconciliation from logs.

### Condition to close before full sign-off
Either:
- (a) Implement an actual FAILED-key reclaim in `IdempotencyService` (e.g., an atomic conditional `UPDATE idempotency_keys SET status='IN_PROGRESS' WHERE idempotency_key=? AND status='FAILED'`, treating a 0-row update the same as today's `AlreadyInProgressException`/`AlreadyCompletedException` branching) so the documented behavior matches reality, **or**
- (b) If shipping as-is, correct the misleading code comments and add an ops runbook + alert for `idempotency_keys` rows stuck in `FAILED` status for payout scope, so a transient RazorpayX blip doesn't quietly strand a creator's payout indefinitely.

This is a repo-wide pattern (same false claim appears in the affiliate settlement code), so fixing `IdempotencyService` once fixes all of them.

## Minor / informational (non-blocking)
- `PayoutService.resolveFundAccountForCreator`'s comment says "primary bank account (first one by creation date)" but the query (`findByCreatorUserIdOrderByCreatedAtDesc`, `.get(0)`) actually picks the **most recently added** account, not a creator-designated primary/default. Not exploitable (still ownership-scoped and cool-down-gated), but worth an explicit `is_primary` flag if creators are ever expected to hold multiple bank accounts — otherwise adding a second account silently redirects all future payouts to it.
- `confirmExecuted`'s correctness depends entirely on `WebhookSignatureVerifier` (not in this review's file list) actually verifying HMAC before dispatch — I did not re-audit that verifier in this pass; flagging as a dependency worth a quick confirm before launch since it's the only gate standing between an attacker-forged webhook and `payout.confirmStatus`.

## Mandatory pre-production gate (already known, restated for completeness)
No live Razorpay sandbox/test-mode smoke test (`createContact` → `createFundAccount` → `initiatePayout`) has been run anywhere in this review chain — not by me, not by Meera (no test credentials/network path in this sandbox). This advisory does not substitute for that. A real smoke test on a networked host with Razorpay test-mode credentials remains required before this path takes real production traffic.

---

## Addendum — 2026-07-13, Vikram: FAILED-key reclaim implemented

The condition-to-close above (real `FAILED`-key reclaim in `IdempotencyService`) is now implemented:

- `IdempotencyKeyRecordRepository.reclaimFailedForRetry(key, failed, inProgress)` — atomic `UPDATE IdempotencyKeyRecord k SET k.status = :inProgress, k.completedAt = null, k.resultDigest = null WHERE k.idempotencyKey = :key AND k.status = :failed`, guarded by affected-row count, not a read-then-write.
- `IdempotencyService.executeOnce` now tries this reclaim when the initial insert-reservation loses to an existing row, before falling back to the `COMPLETED`/`IN_PROGRESS` terminal check. A `FAILED` key gets a fresh attempt; `IN_PROGRESS`/`COMPLETED` keys are never touched by the reclaim query (`WHERE ... status = 'FAILED'` cannot match them) and are still rejected exactly as before.
- Concurrency: two callers racing to reclaim the same `FAILED` key both issue the same `UPDATE`; the DB row lock on that statement means only one can match `status='FAILED'` and get affected-rows `== 1` — the loser's `UPDATE` runs against a row already flipped to `IN_PROGRESS` and matches 0 rows, so it falls through to `AlreadyInProgressException` exactly like a genuine in-flight collision. This mirrors the same DB-arbitration discipline already verified sound for the insert-first-wins reservation in this advisory's "What I verified as sound" §4 above — no new app-level check-then-act was introduced.
- Verified via `IdempotencyServiceTest`: FAILED-reclaim-succeeds-on-retry, two-concurrent-reclaims-exactly-one-proceeds (using sequential Mockito stubbing on `reclaimFailedForRetry`, `1` then `0`, with the wrapped action's call-count asserted `== 1` across both `executeOnce` invocations), reclaimed-key-can-fail-again, plus the pre-existing IN_PROGRESS/COMPLETED-stays-terminal tests still pass unmodified in behavior.
- Full suite: baseline was 890 tests / 11F / 9E; after adding the 3 new tests, 893 tests / 11F / 9E — same pre-existing unrelated failures, zero new regressions.
- The 4 callers (`PayoutService`, `AffiliateSettlementJob`, `AffiliateSettlementBatch`, `AffiliateEarning`) whose javadoc already claimed this behavior needed no changes — their comments now match reality.
- Not addressed here (separate, already-tracked, non-blocking item from the E2 re-review): the partial-failure double-payment window where RazorpayX succeeds but the local save/commit throws after — that still leans on RazorpayX's own `reference_id`/`X-Payout-Idempotency` dedup during a narrow window, same as before this fix; reclaim does not change that risk profile (see `wiki/errors/idempotency-fixes-E2-security-review.md` HIGH-1 residual note).
- This addendum does not itself constitute sign-off — leaving the INDEX.md/tracker status flip to the sign-off owner, per instructions. Live Razorpay smoke test gate above remains unaddressed and still required pre-production.
