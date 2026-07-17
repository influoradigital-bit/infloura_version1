# ADR: Phase 2 Time-Series Datastore — MySQL now, TimescaleDB deferred

> **Decision by:** Priya (CTO) — ABSOLUTE authority over technical decisions
> **Date:** 2026-07-06
> **Status:** LOCKED
> **Requested by:** Swapnil (CEO) — "start Phase 2, but Priya's answer is prior"
> **Supersedes:** the "CTO Decision Required" open item at `wiki/tech/VIKRAM_BACKEND_IMPLEMENTATION_SPEC.md` §2.1

---

## Context

Phase 2 of `MASTER_IMPLEMENTATION_PLAN.md` ("Data Pipeline") is specced against **TimescaleDB**, which is a **PostgreSQL** extension. The Influora codebase is **MySQL 8** end-to-end:

- `influora-api/pom.xml`: `mysql-connector-j` + `flyway-mysql`
- `application.yml`: `jdbc:mysql://…`, `org.hibernate.dialect.MySQLDialect`, `ddl-auto: validate`
- 20 Flyway migrations (V1–V20), all MySQL syntax
- The money-core (V8 wallet, V9 escrow, double-entry ledger) is on MySQL and working

TimescaleDB cannot run on MySQL. The spec itself flagged this and blocked on a CTO ruling before Phase 2 could start.

## Options considered

- **Option A — migrate the whole DB to PostgreSQL.** REJECTED. Rewriting a working, money-handling MySQL core (escrow/wallet/ledger) onto Postgres mid-flight and pre-launch is unjustifiable risk for zero near-term benefit.
- **Option B — a second, separate TimescaleDB/Postgres instance** alongside MySQL (spec's recommendation). REJECTED FOR NOW / accepted as the future upgrade path. It is the correct answer at scale but is premature infrastructure today: doubles the ops surface (two engines to run/monitor/back up), forces dual-datasource Spring config, and turns cross-store reads into app-level joins — all to buy hypertable chunking / compression / continuous-aggregates that only pay off at millions of rows we do not have.
- **Option C — build Phase 2 on MySQL now, behind an abstraction. ✅ ADOPTED.**

## Decision

Build the Phase 2 metrics layer on **MySQL**:

1. `creator_metrics` and `media_metrics` become ordinary InnoDB tables with a composite index on `(creator_profile_id, time)` (and `(platform, time)` where the spec indexes it). This is functionally what a hypertable indexes on, minus time-chunking that is irrelevant at our volume.
2. Time columns use `DATETIME(6)` / store UTC (matching the existing `serverTimezone=UTC` convention), NOT Postgres `TIMESTAMPTZ`. Decimal/bigint types map straight across.
3. **All metrics reads/writes go behind a repository interface** (e.g. `CreatorMetricsRepository`) so a TimescaleDB-backed implementation can be introduced later with no service-layer changes.
4. Migrations continue the existing MySQL Flyway sequence — **next number is V21** (V20 is taken by `meta_oauth_tokens` from Phase 1). Ignore the spec's `V20__timescale_hypertables.sql` naming.
5. No new database dependency is added to `pom.xml`. The `org.postgresql` driver in the spec's §2.1 is **not approved** and must not be added.

## Trigger to revisit (Option B upgrade path)

Escalate back to me to stand up a dedicated TimescaleDB instance when EITHER:
- a metrics table sustains **> 5M rows**, or
- a product requirement lands for **real-time continuous aggregates / retention-compression** at launch scale.

Until then, MySQL-native time-series is the system of record and the abstraction keeps the door open.

## Constraints that remain in force

- API keys/secrets in env only (`${ENV_VAR:…}`), never `NEXT_PUBLIC_*` / never committed.
- Workspace isolation on every metrics query (same discipline as every other repo).
- TypeScript strict, WCAG AA, `useReducedMotion()` bypass on the frontend analytics work — unchanged.
