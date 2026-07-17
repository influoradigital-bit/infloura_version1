# ADR — Flyway migration versioning under concurrent sessions

> **Author:** Priya (CTO)
> **Date:** 2026-07-09
> **Status:** ACCEPTED — binding on all sessions/agents that add DB migrations
> **Owner:** Priya (architecture). Do not override without CTO sign-off.

## Context

Multiple AI sessions edit this repo concurrently against a shared, uncommitted working
tree. Flyway migrations use sequential integer versions (`V44`, `V45`, …). Sequential
numbering assumes a single writer; with two writers each picking "the next number,"
version collisions are guaranteed. This has already happened twice in one day:

- `V40__reviews.sql` vs `V40__platform_fee_config.sql`
- `V41__reviews.sql` vs `V41__platform_fee_config.sql`

Both were resolved by after-the-fact renames (reviews chain pushed to V43), but the race
is structural and recurs on every new migration.

## Decision

1. **New migrations use UTC-timestamp versions**, not sequential integers:
   `V<yyyyMMddHHmmss>__<snake_description>.sql` — e.g. `V20260709194500__disputes.sql`.
   Generate the timestamp at authoring time (`date -u +%Y%m%d%H%M%S`).

2. **Existing `V1`–`V45` are frozen as-is.** They are already applied to the dev DB and
   recorded in `flyway_schema_history`; renumbering them would break Flyway's checksum/
   ordering history. Flyway orders `45 < 20260709194500`, so timestamped migrations always
   apply after the legacy sequential set — no conflict, no config change required.

3. **Never edit or renumber a migration once it has been applied to any real DB.** Forward-
   only. Corrections ship as a new migration.

4. **Migrations are additive-per-concern.** One migration = one feature's schema. Do not
   bundle unrelated tables; independent concerns must never depend on each other's version
   ordering.

## Complementary practice (not a substitute)

- **Commit frequently.** Git is the coordination point for the *other* conflict class —
  two sessions editing the same entity/file (this is how the `Review.stars` int-vs-TINYINT
  mismatch slipped in). Timestamp versioning fixes number collisions; frequent commits
  surface content collisions.

## Rejected alternatives

- **Shared lock file** — brittle and stateful; goes stale if a session dies mid-hold.
  Adds a coordination dependency to remove a coordination problem. Rejected.
- **"Just commit more often" as the primary fix** — only detects collisions faster; does
  not prevent them. Kept as a complement, not the fix.

## Cross-session note (as of 2026-07-09)

A concurrent session owns the Reviews + Disputes + creator-fee work (`V43__reviews.sql`,
`V45__disputes.sql`, `Review.java`, creator-side `PlatformFeeConfig` usage). Open item
routed to that session, NOT to be fixed cross-session:

- `Review.java:37` declares `private int stars` (Hibernate → INTEGER) but
  `V43__reviews.sql` declares `stars TINYINT`. Fails `ddl-auto=validate` at boot (after
  Flyway migrate, so it does not block migrations or tests). Correct fix: keep `TINYINT`
  (right size for a 1–5 rating), annotate the entity `@Column(columnDefinition = "TINYINT")`
  or map to `Byte`. Do NOT widen the column to INT.
