# T-ADMINMAIL-0903 — CTO verdict, round 3: DO NOT SHIP

Real progress: 4 SAFE (idempotency, token/CTA validation, serialization abort, unsubscribe round-trip),
4 RISK, **2 BROKEN**. The two BROKEN items are both small fixes with large consequences.

## A1 · BROKEN — every send dies on a real production dataset

**Verified independently before this file was written.** `User.softDelete()` (`User.java:302-315`)
nulls `email`, `firstName`, `displayName` — and deliberately leaves `status` untouched
(`V61__user_soft_delete.sql` says so, to keep FKs resolving). `UserRepository`'s two audience queries
contain **zero** references to `deletedAt` (grep count: 0).

So a soft-deleted account is still `status = ACTIVE`, is counted in `recipientCount`, is returned by
`findForCustomEmailAudience`, and reaches `.toEmail(recipient.getEmail())` as `null` — against
`EmailOutbox.to_email VARCHAR(255) NOT NULL`. The `flush()` throws, and the whole transaction rolls
back as an opaque **409 DATA_INTEGRITY_VIOLATION**.

Consequence: one deleted account anywhere in the audience makes **every send fail, permanently and
unrecoverably** — no campaign row is written, so there is no replay, and a retry reproduces it
exactly. `preview()` succeeds in the same state (it returns `null` for the sample address), so the
operator sees "4,312 recipients, looks great" and then an unexplained data conflict with no
actionable message. Account deletion shipped 2026-07-14, so this fires on day one on the default
`ALL` audience.

Fix: add `AND u.deletedAt IS NULL` to **both** `countForCustomEmailAudience` and
`findForCustomEmailAudience` — they must stay predicate-identical or the 409 count check drifts.
Belt and braces: skip a recipient whose email is null/blank at enqueue rather than trusting the
query, so this can never again turn into a whole-batch failure.

**Also fix the same blindness in `findCreatorsWithoutConnectedAccount`** (the connect-nudge job).
It degrades more gracefully — `NotificationService` drops a null address with a warning rather than
crashing — but it is the same defect and should not be left as a trap for the next person.

## A2 · BROKEN — the lock serialises sends without making the rate limit exclusive

`send()` is a bare `@Transactional` (no isolation override anywhere in the codebase) on MySQL/InnoDB,
so it runs at **REPEATABLE READ** and its read view is pinned at the first plain SELECT — which is
`requireRoleWithMfaSatisfied` on `admin_users`, *before* `acquireSendLock()`.

`SELECT ... FOR UPDATE` reads the latest committed version **of the rows it locks**. It does not
refresh the transaction's snapshot for later plain SELECTs on other tables. `enforceRateLimit()` is a
plain `findTopByOrderByCreatedAtDesc()` on `admin_email_campaigns`, so it still reads the pre-lock
snapshot.

`AdminEmailSendLock.java:14-20` and the migration header both assert the opposite — "a locking read
always sees the latest COMMITTED data … **so** by the time a serialized caller's own read of
admin_email_campaigns runs, it is guaranteed to see whatever the previous holder just committed."
The first clause is true; the "so" does not follow. A comment that reasons its way to a false
guarantee is worse than no comment, because it stops the next reader checking.

Concretely: A and B overlap. B wins the lock, writes 5,000 rows, commits, releases. A acquires,
re-checks replay (misses — different copy, different `campaignId`), then reads its **stale** snapshot,
sees no recent campaign, and passes the throttle. Two full blasts inside the 10-minute window. The
send transaction is long (see A4), so the overlap window is seconds wide.

Fix: `@Transactional(isolation = Isolation.READ_COMMITTED)` on `send`, or make the last-campaign read
locking/native. Then correct both comments.

Neither existing test can see this: the concurrency test is on **H2** (`MODE=MySQL` is not MySQL's
MVCC) and only proves `lockForUpdate` blocks — it never re-reads `admin_email_campaigns` after the
lock, which is the actual claim. The service test is Mockito asserting a method was called.

## A3 · RISK — `cancel` over-reports, and mail still goes out after it returns

`markClaimed` moves `nextRetryAt` only and leaves `status = PENDING`, so rows already claimed by the
in-flight `EmailWorker` batch still match `cancel`'s `status = PENDING` filter and are counted as
cancelled. But the worker holds those rows detached in memory and `processOne` re-checks *unsubscribe*
and never *cancellation* — so it sends them, and `markSent()` then overwrites CANCELLED back to SENT.

An admin is told `cancelled: 4300` while up to `BATCH_SIZE = 50` of those emails are delivered anyway,
leaving no trace. For a control whose entire purpose is stopping a mistake, reporting a success it did
not achieve is the wrong failure direction. Re-check cancellation in `processOne` (as unsubscribe
already is), and/or have `markClaimed` move the row out of PENDING so the count is honest.

## A4 · RISK — under degraded SMTP the batch outlives its own leases

`application.yml` sets connect/read/write timeouts to 10s each. A 50-row batch all timing out is
50 × 10s = **~500s**, which exceeds both `CLAIM_LEASE = 3 min` and `@SchedulerLock(lockAtMostFor =
"PT5M")`. Leases expire mid-batch and a second instance may re-claim rows the first has already
dispatched — **duplicate mail, precisely under the degraded conditions a 5,000-mail blast induces.**
The worker's javadoc reasons about `CLAIM_LEASE` vs `lockAtMostFor` but never against the batch's own
worst-case wall-clock. Same arithmetic means a login code can wait ~8.5 min against an OTP that says
it expires in 5 — arriving already expired.

Also: `EmailOutbox` has an assigned `@Id` and no `@Version`, so `saveAll` goes through `merge` — a
SELECT-then-INSERT per row, 5,000 of each, inside the transaction holding the global send lock. That
widens A2's window and pins a pool connection. Consider `persist` semantics or batching.

## A5 · RISK — preview is now a walkable oracle, and the audit cannot reconstruct what leaked

B2 removed the arbitrary-id lookup, but `findForCustomEmailAudience` has only a *lower* bound on
registration and orders ascending, and `resolveSample` takes the first row. Sweeping
`registeredWithinDays` walks the user table in registration order, one real address per day-bucket,
× 4 for the userType/onlyVerified combinations. `preview` takes no lock and is not rate-limited.
The audit entry records `audienceUserType`, `recipientCount`, `capped`, `hasSample` — but **not**
`registeredWithinDays` or `onlyVerified`, so the trail cannot reconstruct which addresses were
returned. Rate-limit preview and log the full audience.

## A6 · RISK — the 409 leaks the count it is meant to confirm

The mismatch message interpolates the live count ("confirmed 0, now 4312"), so two requests defeat the
control: send with `0`, read the count out of the error, resend with it. Return the mismatch without
the actual number.

## Bar for done

A1 and A2 are the ship-blockers. For each fix, revert it, show the test RED with its assertion text,
restore, show GREEN. **A1 needs a test with a soft-deleted user in the audience** — that is the case
that would have taken production down, and no existing test covers it.
