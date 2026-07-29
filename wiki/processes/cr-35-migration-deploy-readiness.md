# CR-35 migration — deploy readiness (Meera, DB/DevOps)

> **Migration:** `influora-api/src/main/resources/db/migration/V20260728120000__backfill_escrow_hold_collaboration_id.sql`
> **Scope of this doc:** the migration only. Not a re-review of the Java fixes (Fix 1–3) — those already
> went through Kabir's mandatory money-path gate (`wiki/errors/CR-35-escrow-fix-redteam.md`, PASS
> conditional on HIGH-1, which the tracker records as landed in the same commit `d3a22da`).
> **Verdict up front: GO.** Details and the one operational condition below.

---

## 0. Did I get a real database? Yes — read this before trusting anything below.

`docker ps` initially failed (`npipe:////./pipe/dockerDesktopLinuxEngine` — Docker Desktop wasn't
running). I started Docker Desktop and it came up with this repo's own `docker-compose.yml` stack
already running from a previous session, **including `influora-mysql` on `127.0.0.1:3307`** (root/root,
db `influora_local`) — a real MySQL 8.0 server, not a mock.

That database's `flyway_schema_history` tops out at `20260718190000` — it's ~10 days behind the
migration chain and its `escrow_holds` table is empty (0 rows). Since it's the shared dev DB other
agents may be pointing a running `influora-api` instance at, I did **not** run the app or Flyway
against it directly (that would apply the entire pending chain, not just this one migration, and
could collide with someone else's session). Instead, inside the **same MySQL server**, I created an
isolated scratch database `escrow_dryrun` and rebuilt just the tables this migration touches —
`escrow_holds` and `payment_milestones` **verbatim from `V9__escrow_holds.sql` / `V10__contracts_and_milestones.sql`**
(copy-pasted DDL, not paraphrased), plus minimal stub parent tables (`workspaces`, `collaborations`,
`campaigns`, `wallet_transactions`, `contracts`) only to satisfy the real foreign keys. I seeded
representative rows, ran the **actual migration file** against it with the real `mysql` CLI, inspected
results, and dropped the scratch DB when done (`influora_local` verified untouched throughout — see
§8 for the raw commands/output if anyone wants to reproduce this).

So: **this is a real dry run**, on real InnoDB, with the real DDL and the real migration file — just
against a purpose-built clone of the two tables rather than the shared dev DB or production. Two
things it can't tell me and I'm not claiming it can: the actual production row count, and production's
disk/IO profile (see §4).

---

## 1. Claim-by-claim verification

| Claim | Status | Evidence |
|---|---|---|
| Idempotent on re-run | **Confirmed, executed** | Ran the migration twice against identical seed data; second run's `mysql --verbose` output shows the statement executes with the join producing zero matches, and a row-by-row diff of `escrow_holds` before/after run 2 is byte-identical to after run 1. Repeated again at 200k-row scale (§4): re-run took 0.109s vs. 1.9s for the real run. |
| Only touches rows where `collaboration_id IS NULL` | **Confirmed, executed** | Seeded a control row (`EH00000000000000000EH02`) already bound to `COLLAB...C002` with no milestone at all — untouched by both runs. `EXPLAIN` (below) shows the `WHERE` uses an index on `collaboration_id`, not a full scan, so this isn't just an unenforced comment. |
| Never binds a WRONG collaboration | **Confirmed, executed — including the adversarial case** | Seeded `EH00000000000000000EH05`: `collaboration_id` already set to `COLLAB...C003`, but `milestone_id` pointing at a milestone that belongs to `COLLAB...C001` (i.e. constructed so the milestone walk would suggest a *different* collaboration than what's already stored — the exact shape of Kabir's flagged pre-existing `ConfirmLaunchExecutor` mis-binding window). Both runs left it at `C003`, untouched. The `WHERE eh.collaboration_id IS NULL` guard makes this true by construction — the migration is structurally incapable of overwriting a non-null value, correct or not. It also **does not fix** an already-wrong binding; that's out of scope for a NULL-only backfill, correctly. |
| Atomic | **Reasoned + indirectly confirmed** | Single `UPDATE` statement — InnoDB gives single-statement atomicity for free; there's no multi-statement sequence that could leave a partial state. I didn't fabricate a mid-statement failure (there's no realistic one to fabricate: `pm.collaboration_id` is `NOT NULL` with its own FK, so the joined value is always a valid, non-null collaboration id — traced in `payment_milestones`' V10 DDL). Two full runs left no partial rows (every backfilled row got a value; nothing came back half-set). |
| Correct for MySQL specifically | **Confirmed, executed** | `UPDATE t1 INNER JOIN t2 ON ... SET ... WHERE ...` is MySQL multi-table UPDATE syntax (not valid Postgres, which was the thing to check) and it ran without error on real MySQL 8.0.43 (the pinned image version). `EXPLAIN` output: `eh` accessed via `ref` on the auto-created FK index `fk_escrow_collab` (confirms an index lookup on `collaboration_id`, not a table scan — MySQL indexes `NULL` as a value so `col IS NULL` can use this index), `pm` accessed via `eq_ref` on `PRIMARY`. Same plan shape at 5-row scale and 200k-row scale. |

The one syntax nit Kabir already flagged — `AND eh.milestone_id IS NOT NULL` is redundant given the
`INNER JOIN` (a row can only match the join if `milestone_id` equals some `pm.id`, which can't be
NULL) — confirmed harmless by the `EXPLAIN` output; it doesn't change the plan.

---

## 2. `DeliverableCleanupJob` — traced independently, verdict: safer, confirmed

Kabir's MEDIUM-2 says all three affected call sites move in the safe direction. I re-derived the
`DeliverableCleanupJob` one from source rather than taking that on faith, since it's the destructive
one.

`DeliverableCleanupJob.java:253-260`:
```java
private boolean canDelete(Deliverable deliverable) {
    if (disputeRepository.existsByCollaborationIdAndStatusIn(
            deliverable.getCollaborationId(), ACTIVE_DISPUTE_STATUSES)) {
        return false;
    }
    return !escrowHoldRepository.existsByCollaborationIdAndStatusIn(
            deliverable.getCollaborationId(), UNRELEASED_ESCROW_STATUSES);
}
```
`UNRELEASED_ESCROW_STATUSES` = `{FUNDED, FROZEN, PENDING}` — deliberately the *full* unreleased set
(a prior fix, H-DPF8-1, per the class javadoc), checked directly against `escrow_holds.collaboration_id`
with **no milestone fallback**.

**Pre-migration:** an ordinary-flow hold (funded via `POST /wallet/escrow/fund`, no AI launch tool
involved) has `collaboration_id = NULL`. `existsByCollaborationIdAndStatusIn(realCollabId, {...})`
can never match a row whose `collaboration_id` is `NULL` — SQL/JPQL equality doesn't match NULL — so
this returns `false` even when that collaboration has a live `FUNDED` or `FROZEN` hold. `canDelete`
then returns `!false = true`. **The job deletes deliverable media on a collaboration with live,
unreleased escrow.** This is a real, currently-live data-destruction bug, exactly as Kabir found —
`cleanupSupersededRevisions` (30-day-old approved versions) and `cleanupAbandonedDrafts` (90-day-old
unapproved drafts) both call this guard before deleting R2 objects.

**Post-migration:** the same hold's `collaboration_id` is now backfilled to the real value. The exists
check now correctly finds it, returns `true`, `canDelete` returns `!true = false`, and the job skips
that deliverable — logging `"Skipping deliverable {} — active dispute or unreleased escrow"` instead of
deleting.

**Direction check, done generally rather than by example:** the migration is exclusively an
NULL→non-null write (never null→null, never value→different-value, confirmed in §1). `existsBy...` is
monotonic in that direction — turning a previously-invisible-because-NULL row into a matchable row can
only make `exists` go `false→true`, never the reverse, for any collaboration whose real escrow state
was already `FUNDED`/`FROZEN`/`PENDING`. So `canDelete` can only go `true→false` for every affected
collaboration; it can never newly *permit* a deletion that was previously blocked. Confirmed
independently of Kabir's writeup, from the source and the schema, not by re-stating his conclusion.

**Net effect of the migration on this job: strictly safer.** It closes a live media-deletion bug for
every historical row it can resolve. It does not touch, and cannot help, holds with no `milestone_id`
(campaign-level pool funding) — those remain invisible to this guard exactly as before, which is a
pre-existing gap in the job's own query (MEDIUM-3 in Kabir's doc), not something this migration
introduces or is responsible for closing.

---

## 3. Other two call sites (traced briefly, not the destructive one)

- `ContractService.java:624` `promptEscrowFundingIfNeeded` — `existsByCollaborationIdAndStatus(id, FUNDED)`, used only to decide whether to send a "please fund escrow" notification. Pre-migration: false positive notification (tells a brand to fund escrow it already funded). Post-migration: stops. Cosmetic, safe direction, non-money-moving.
- `DealService.java:975` `escrowFunded` — same `existsBy...` pattern, feeds a read-only response flag to the frontend. Pre-migration: always `false` for ordinary-flow holds. Post-migration: correct. No write path involved.

Both move the same NULL→non-null direction as §2 and can't regress.

---

## 4. Blast radius

**What I do not know:** the real production row count of `escrow_holds`, or how many of those rows are
NULL-and-resolvable (the actual backlog this migration will touch). I have no access to the production
database from this session — anyone running this must get that number first (exact query in §6, step 1).

**What I measured, honestly labeled as a local synthetic benchmark:** built a 200,005-row `escrow_holds`
table in the scratch DB (170,004 already bound / 25,000 NULL+resolvable / 5,001 NULL+unresolvable —
proportions I chose to be a plausible historical mix, not derived from any real count) on this laptop's
local Docker MySQL:

- `EXPLAIN` at this scale: same plan as the 5-row test — `ref` access on the FK-index on
  `collaboration_id`, `rows: 59590` estimate, then `eq_ref` on the milestone PK. **Cost scales with the
  size of the NULL backlog, not the size of the table** — it does not scan the 170k already-bound rows.
- Wall-clock for the real migration statement (client-observed, includes connection overhead):
  **1.9s** to backfill 25,000 rows. Re-run (idempotency check at scale): **0.109s**.
- **Locking, directly observed** (not just reasoned about — held the migration's `UPDATE` open in an
  uncommitted transaction from one session and probed from a second, concurrent one):
  - A targeted `UPDATE` against a specific row inside the matched set **blocked** and timed out
    (`innodb_lock_wait_timeout`) — confirms exclusive row locks are held on every matched row for the
    statement's full duration.
  - An `INSERT` of a **brand-new row with `collaboration_id = NULL`** (e.g., a new campaign-level pool
    escrow funded concurrently) **also blocked and timed out** — this is InnoDB next-key locking on the
    `collaboration_id` index range for the value `NULL`, taken under the default `REPEATABLE READ`
    isolation (confirmed MySQL per `application.yml`, per Kabir's review) to prevent phantom inserts
    into the range the `UPDATE` is scanning.
  - An `INSERT` of a new row with a **non-NULL** `collaboration_id` did **not** block (0.12s) — isolates
    that the block above is specifically about the NULL-valued gap, not general table contention.

**What this means for "inline on boot vs. maintenance window":** Flyway runs during Spring context
refresh, before the app is marked ready and before it accepts any traffic — so on a genuine
single-instance restart there is no concurrent writer to block, and the whole thing is bounded by the
UPDATE's own duration (sub-2s at 25k rows locally; unknown at real backlog size — get the count first).
**The one scenario the observed locking behavior actually threatens** is a rolling/blue-green deploy
where an **old instance keeps serving live traffic against the same database** while the **new
instance's Flyway migration is in flight**: any brand funding a new campaign-level (milestone-less)
escrow hold on the old instance during that window will stall for the migration's full duration before
its `INSERT` completes. That's a real, verified risk, not a hypothetical — but it is a "one brand's
request pauses for N seconds," not a corruption risk, and N is bounded by the backlog size which is
almost certainly small (this is a one-time historical cleanup of pre-Fix-2 rows; Fix 2 means the
backlog stops growing the moment the app-layer fix is live).

**Recommendation:** run the count in §6 step 1 before deploying. If the NULL-resolvable count is in the
thousands-to-low-tens-of-thousands range (consistent with "however many ordinary-flow holds existed
before this fix shipped"), inline-on-boot is safe with no maintenance window, **provided the deploy is
not a rolling/dual-instance cutover** (confirm with whoever owns the Hostinger deploy runbook, §9 of the
tracker — a `docker compose pull && up -d` single-container restart, which is what that runbook
actually describes, is fine). If the count is unexpectedly large (six-plus figures — would imply the
defect existed for far longer / higher volume than the spec assumed), re-time it against a real snapshot
before shipping rather than trusting the 200k/25k number above, since that number is a synthetic local
benchmark, not a production measurement.

---

## 5. Go / no-go

**GO**, conditional only on running the pre-deploy count (§6 step 1) first and confirming the deploy
path is the documented single-container restart, not a live rolling cutover. Reasons:

- Every claim made about the migration in the spec and Kabir's review checked out under actual
  execution, not just reading — idempotency, the NULL-only guard, the impossibility of a wrong bind
  (including an adversarial case constructed to try to break it), and MySQL-specific syntax all ran
  clean.
- It closes a **currently-live, real data-destruction bug** in `DeliverableCleanupJob` (independently
  re-derived, not taken on faith) — leaving it un-run is strictly worse than running it.
- The only genuine operational risk found (concurrent INSERT stalls during a rolling deploy) is bounded,
  reasoned from directly-observed lock behavior rather than assumed, and inapplicable to the deploy
  method this repo's own runbook (§9) actually uses.
- The Java-side fixes this migration depends on (Fix 1–3) already went through Kabir's mandatory
  money-path gate; that gate is not mine to re-run, and this doc doesn't re-litigate it.

---

## 6. Runbook addendum

### Pre-deploy check (run against the real target DB, read-only, before deploying)
```sql
SELECT
  COUNT(*) AS total_holds,
  SUM(collaboration_id IS NULL AND milestone_id IS NOT NULL) AS will_be_backfilled,
  SUM(collaboration_id IS NULL AND milestone_id IS NULL)     AS stays_null_by_design
FROM escrow_holds;
```
`will_be_backfilled` is your real blast radius — compare it against the 25,000-row / 1.9s local number
in §4 to sanity-check timing before trusting "inline on boot is fine."

### Dry-run command (exact, reproducible, isolated — does not touch the target DB)
Point this at a **restored copy** of the target schema (a snapshot, not the live DB) if one is
available; otherwise reproduce the isolated-clone method from §0 (verbatim `V9`/`V10` DDL + stub parent
tables) against a scratch schema on the same server:
```bash
mysql -h <host> -P <port> -u <user> -p <scratch_db> \
  < influora-api/src/main/resources/db/migration/V20260728120000__backfill_escrow_hold_collaboration_id.sql
```
Then re-run it a second time and confirm it reports 0 additional rows changed (idempotency, live on
your actual data shape).

### Post-run verification (against the real target DB, after Flyway applies it)
```sql
-- 1. flyway_schema_history shows it applied and succeeded
SELECT version, description, success FROM flyway_schema_history
WHERE version = '20260728120000';

-- 2. no NULL-resolvable rows remain
SELECT COUNT(*) FROM escrow_holds WHERE collaboration_id IS NULL AND milestone_id IS NOT NULL;
-- expect 0

-- 3. spot-check a few previously-NULL holds now carry the right collaboration
SELECT eh.id, eh.collaboration_id, pm.collaboration_id AS via_milestone
FROM escrow_holds eh JOIN payment_milestones pm ON pm.id = eh.milestone_id
WHERE eh.updated_at > <deploy_timestamp> LIMIT 20;
-- expect eh.collaboration_id = via_milestone for every row
```
Also watch the `DeliverableCleanupJob` logs at its next 2:00/2:30 AM run — expect to start seeing
`"Skipping deliverable {} — active dispute or unreleased escrow"` for deliverables that were
previously (silently, wrongly) eligible for cleanup. That's the fix working, not a new problem.

### Rollback — this is not a `DOWN` migration, and running one would be wrong
A backfill has no natural inverse that's actually safe to run: the values it writes are *correct*
(derived from `payment_milestones.collaboration_id`, which is `NOT NULL` and FK-enforced — there is no
"wrong" value for it to have produced, per §1's adversarial test). If something goes wrong after this
ships, the fix is almost never "undo the backfill" — it's "revert whatever downstream code just started
seeing non-null `collaboration_id` and didn't handle it," because the recovery path *is* the source
data:
1. **If a downstream consumer misbehaves on the new non-null values** (unlikely — `DeliverableCleanupJob` /
   `ContractService` / `DealService` only ever read it to decide `false`-adjacent guards more correctly,
   per §2–3), fix or revert that consumer's code. Do not touch the data.
2. **If you must literally reverse the write** (e.g. to reproduce a pre-migration state for debugging),
   it's a one-line, equally-safe UPDATE, since the pre-image is fully recoverable from the same join:
   ```sql
   UPDATE escrow_holds eh
   INNER JOIN payment_milestones pm ON pm.id = eh.milestone_id
   SET eh.collaboration_id = NULL
   WHERE eh.collaboration_id = pm.collaboration_id
     AND eh.milestone_id IS NOT NULL;
   ```
   This is offered for completeness/debugging only — running it in production would re-open the
   `DeliverableCleanupJob` data-destruction bug from §2. There is no scenario in this analysis where
   that trade is the right call.
3. Flyway itself has no automatic down-migration mechanism for this project (no `undo` scripts observed
   in `db/migration`); a failed apply is caught by Flyway's checksum/failure tracking and blocks
   subsequent migrations until resolved — it does not partially apply (single statement, §1).

---

## 7. What I did not verify (explicit)

- Real production `escrow_holds` row count / actual NULL-backlog size — no prod DB access this session.
- Real production disk/IO characteristics — the timing numbers in §4 are from a laptop's local Docker
  volume, not representative of the production host.
- The Java-side Fix 1–3 changes and Kabir's HIGH-1/HIGH-2/MEDIUM-1 findings — out of scope for this doc
  by the task's own framing (Kabir's gate, not mine), and I did not re-run `mvn` per the task's explicit
  instruction not to (a concurrent suite run was in progress).
- Whether the actual Hostinger deploy will be a single-container restart or something else — §4's
  conditional recommendation assumes the documented `docker compose pull && up -d` path in the
  tracker's §9; confirm before relying on "no maintenance window needed."

---

## 8. Raw commands, for anyone who wants to reproduce this

Scratch-DB setup, seed data, migration runs, `EXPLAIN` output, the 200k-row scale test, and the
concurrent-lock probes were all run via the `mysql` CLI against `127.0.0.1:3307` (this repo's own
`docker-compose.yml` `mysql` service) inside a throwaway database named `escrow_dryrun`, which was
dropped at the end of the session. `influora_local` (the shared dev DB) was read once (a row-count
check, confirming its `escrow_holds` is empty) and never written to. No Maven was run. No files under
`influora-api/` or `src/` were modified. No git commands beyond what's already reflected in this
document were run.
