# T-ADMINMAIL-0903 — round 5 work list

## Already closed — do not redo

- **A1** (soft-deleted users broke every send) — `AND u.deletedAt IS NULL` on both audience queries
  and the connect-nudge query, plus a defensive null-email skip at enqueue. Now covered by a REAL
  behavioural test (`UserRepositoryAudienceSoftDeleteTest`, `@DataJpaTest` + H2) that persists a
  genuinely soft-deleted user and runs the actual queries. Falsified both ways.
- **A2** (stale rate-limit snapshot) — `@Transactional(isolation = Isolation.READ_COMMITTED)` on
  `send()`, comments corrected.

Baseline: **100 tests, 0 failures, BUILD SUCCESS.**

## Remaining — A3 to A6, in priority order

### A3 · `cancel` reports success for mail it did not stop
`markClaimed` moves `nextRetryAt` only and leaves `status = PENDING`, so rows already claimed by the
in-flight `EmailWorker` batch still match `cancel`'s `status = PENDING` filter and are counted as
cancelled. The worker holds those rows detached in memory, `processOne` re-checks *unsubscribe* but
never *cancellation*, so it sends them — and `markSent()` then overwrites CANCELLED back to SENT.

An admin is told `cancelled: 4300` while up to `BATCH_SIZE = 50` of those emails go out anyway,
leaving no trace. For a control whose only job is stopping a mistake, reporting a success it did not
achieve is the wrong direction to fail in. Re-check cancellation in `processOne` the way unsubscribe
already is, and/or move claimed rows out of PENDING so the count is honest.

### A4 · A degraded batch outlives its own leases — duplicate-mail risk
`spring.mail` connect/read/write timeouts are 10s each. A 50-row batch all timing out is ~500s,
which exceeds both `CLAIM_LEASE = 3 min` and `@SchedulerLock(lockAtMostFor = "PT5M")`. Leases expire
mid-batch and a second instance may re-claim rows the first has already dispatched — duplicate mail,
precisely under the conditions a 5,000-mail blast induces. The same arithmetic means a login code can
wait ~8.5 min against an OTP that says it expires in 5.

**`EmailWorker` is shared with OTP and password-reset delivery — the two flows that must never
regress.** Read its class javadoc before touching anything: `BATCH_SIZE`, the sequential-call shape
and the claim/send/mark split are all load-bearing and documented. Prefer the smallest change that
makes the arithmetic safe (a per-batch cap on `admin.custom` rows, or bounding batch wall-clock
against `CLAIM_LEASE`) over restructuring the worker.

### A5 · Preview is a walkable address oracle, and the audit cannot reconstruct what leaked
`findForCustomEmailAudience` has only a *lower* bound on registration and orders ascending;
`resolveSample` takes the first row. Sweeping `registeredWithinDays` walks the user table in
registration order, one real address per day-bucket, × 4 for the userType/onlyVerified combinations.
`preview` takes no lock and is not rate-limited. The audit entry records `audienceUserType`,
`recipientCount`, `capped`, `hasSample` — but **not** `registeredWithinDays` or `onlyVerified`, so
the trail cannot reconstruct which addresses were returned. Rate-limit preview and log the full
audience descriptor.

### A6 · The 409 leaks the count it exists to confirm
The mismatch message interpolates the live count ("confirmed 0, now 4312"), so two requests defeat
control #3: send with `0`, read the count out of the error, resend with it. Return the mismatch
without the actual number.

## Bar for done

Every fix gets a test that goes RED when the fix is reverted — report the injection and the exact
assertion text. Three false-greens have already happened on this task, all the same shape: a test
asserting what the code *says* (a query string, a captured mock argument) rather than what it *does*.
Where a real behavioural test is possible, `UserRepositoryAudienceSoftDeleteTest` and
`AdminEmailSendLockRepositoryConcurrencyTest` are the two working `@DataJpaTest` + H2 patterns in
this repo — copy one. Where it genuinely is not possible, say so in the test's javadoc rather than
letting a string assertion imply behavioural coverage.
