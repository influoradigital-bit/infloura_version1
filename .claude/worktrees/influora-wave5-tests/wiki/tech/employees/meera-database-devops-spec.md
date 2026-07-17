# Meera — Database & DevOps Spec

> **Reports to:** Priya (CTO) · **Wave:** 1–2 · **Blocks:** Vikram, then Ananya
> **Read first:** `wiki/tech/employees/00-AI-FEATURES-ARCHITECTURE.md` §3, §4

You are first in the chain. Nothing downstream starts until V48 is merged and green.

---

## Non-negotiable conventions (lifted from V1–V47, not invented)

| Rule | Value | Precedent |
|---|---|---|
| Primary keys | `VARCHAR(26)` ULID via `Ulids.newUlid()` | every table |
| Never | `BIGINT AUTO_INCREMENT` | `BACKEND-ARCHITECTURE-DECISION.md:95` |
| Engine | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` | every migration |
| Snapshot time cols | `DATETIME(6)` UTC, set by `Instant.now()` in app, **no DB-side default** | V22, V25 |
| Money | `DECIMAL(12,2)` line, `DECIMAL(14,2)` balance | V8 |
| Percentages | `DECIMAL(5,2)` | V22 `quality_score` |
| JSON | plain MySQL `JSON`, never JSONB | V22, V25 ruling |

---

## T1 — V48: `creator_reliability_stats` (Wave 2)

**Pattern decision (mine to make, stated explicitly):** this is an **upsert-latest** table, one
row per creator — *not* an immutable snapshot like `creator_scores` (V22) or
`audience_demographics` (V25).

Why the difference: those two are periodic samples of an external system (Meta), where a bad
fetch must not destroy the prior good value. Reliability stats are a **pure derivation of our own
`collaborations` rows**. They are recomputable from scratch at any time, carry no history value,
and are always queried as "the current number." One row, `ON DUPLICATE KEY UPDATE`, `computed_at`
stamps freshness. If you find yourself wanting history here, you want a query over
`collaborations`, not a second table.

```sql
-- V48__creator_reliability_stats.sql
--
-- Derived, recomputable rollup of collaborations + reviews, one row per creator.
-- Feeds CreatorFitProfile (00-AI-FEATURES-ARCHITECTURE.md §4) so Meera can explain
-- WHY a creator fits a campaign instead of dumping raw stats.
--
-- [PATTERN — upsert-latest, NOT snapshot] Unlike creator_scores (V22) and
-- audience_demographics (V25), every column here is a pure function of rows we own.
-- Truncate + recompute is always safe. No time-series, no history, no immutability.
CREATE TABLE creator_reliability_stats (
  creator_profile_id       VARCHAR(26) PRIMARY KEY,
  completed_deals          INT            NOT NULL DEFAULT 0,
  terminal_deals           INT            NOT NULL DEFAULT 0,   -- denominator; see §Definitions
  completion_rate          DECIMAL(5,2)   NULL,                 -- NULL when terminal_deals = 0
  on_time_rate             DECIMAL(5,2)   NULL,
  avg_response_minutes     INT            NULL,
  revision_rate            DECIMAL(5,2)   NULL,
  avg_rate_per_deliverable DECIMAL(12,2)  NULL,
  currency                 VARCHAR(3)     NOT NULL DEFAULT 'INR',
  computed_at              DATETIME(6)    NOT NULL,
  INDEX idx_crs_computed (computed_at),
  CONSTRAINT fk_crs_creator FOREIGN KEY (creator_profile_id)
    REFERENCES creator_profiles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### Definitions — write these down or three agents will compute three different numbers

From `V6__creators_collaborations.sql:57`, the status enum is:

```
INVITED, APPLIED, SHORTLISTED, IN_NEGOTIATION, TERMS_AGREED,
CONTRACT_PENDING, CONTRACTED, IN_PROGRESS, REVIEW_PENDING,
REVISION_REQUESTED, COMPLETED, CANCELLED, DISPUTED
```

| Metric | Formula | Trap |
|---|---|---|
| `terminal_deals` | count where status ∈ {`COMPLETED`, `CANCELLED`, `DISPUTED`} | **Do not** count `INVITED`/`APPLIED` — a creator who was invited 200 times and did 8 deals is not "4% reliable." She never agreed to 192 of them. |
| `completed_deals` | count where status = `COMPLETED` | |
| `completion_rate` | `completed_deals / terminal_deals × 100` | `NULL` when `terminal_deals = 0`. **Never 0.00.** A new creator is unknown, not bad. |
| `on_time_rate` | completed where `submitted_at <= deadline` / `completed_deals` | needs `submitted_at`; if absent from `collaborations`, this column ships `NULL` and Vikram opens a follow-up. Do **not** invent the column in V48. |
| `revision_rate` | count ever-`REVISION_REQUESTED` / `completed_deals` | status is current-state; you need the status-history table or an event log. **If we don't have one, ship `NULL`.** Say so, don't fake it. |
| `avg_response_minutes` | mean(first creator message − invite sent) | same — needs message timestamps. `NULL` if unavailable. |
| `avg_rate_per_deliverable` | mean(`agreed_rate`) over `COMPLETED` | `agreed_rate` is per-collaboration, not per-deliverable. Until deliverable count is joinable, name it honestly: `avg_agreed_rate`. **Rename the column if the data doesn't support the name.** |

> **Priya's rule 5 applies:** ship the columns nullable. A `NULL` that renders as "not enough
> data yet" is correct. A `0.00` that renders as "0% reliable" is a lie that will lose us a creator.

**Report back to me in `SHARED_CONTEXT.md`** which of `on_time_rate`, `revision_rate`,
`avg_response_minutes` are actually derivable today. I expect at least one is not. That answer
changes Ash's prompt and Ananya's card, so it comes before either starts.

---

## T2 — Backfill for S3 (brand-safety scores)

`creator_scores.brand_safety_score`, `.garm_flags`, `.content_sentiment` exist since V22 and are
`NULL` for every row. Vikram builds `BrandSafetyScoreService`; you run the backfill.

**No new migration.** V22's header explicitly says *"no other schema change should be needed."*
Honor that.

Backfill job requirements:

- Batch through creators with `brand_safety_score IS NULL`, oldest first.
- Chunk at **≤ 25 content items per call** — hard cap from
  `influora-ai/app/config.py` `brand_safety_max_items_per_call`. Java chunks; Python fails closed.
- Rate-limit the loop. This calls Claude per batch. Coordinate a ceiling with **Rohan** before
  the first production run — a naive full backfill over every creator is an unbounded spend.
- Idempotent: re-running must not double-write. Key on `(creator_profile_id, time::date)`.
- Log tokens consumed per batch. Rohan needs `input_tokens`, `output_tokens`, `cache_read_input_tokens`.

**Dry-run first.** 20 creators, report cost, then Swapnil approves the full run.

---

## T3 — `ReliabilityStatsJob` (nightly)

Mirror `ScoreCalculationJob`'s shape exactly — same package (`com.influora.job`), same scheduling
annotation, same audit logging. Vikram writes the class; you own the schedule, the runbook, and
the failure alarm.

- Cadence: nightly, offset ≥ 30 min from `ScoreCalculationJob` (don't stack two full-table scans).
- Failure mode: job dies → stats go stale, `computed_at` ages. **This is safe.** Stale reliability
  data degrades a recommendation; it never moves money. No pager. Daily digest is enough.
- Alarm if `MAX(computed_at) < NOW() - INTERVAL 48 HOUR`.

---

## T4 — CI (the one that actually matters)

We have **one** GitHub workflow: Lighthouse perf on `/brand/meera`. That is not CI.

Add, in priority order:

1. **Shared-schema diff-check.** Ash found `goal` drift: `influora-ai/app/tools/schemas.py:82`
   emits `awareness|launch|conversion|review`; `02-API-CONTRACT-BRAND.md:156` documents
   `goal:"HYPE"`. `schemas.py:8` claims *"A CI shared-schema diff-check compares this file's output
   against the Spring executor DTOs."* **That check does not exist.** Build it. It must fail the
   build on drift between `TOOL_SCHEMAS` (Python) and `MeeraToolDtos` (Java) + `MeeraToolName`.
   Fixing the drift without this check is not a fix — Priya, rule 6.
2. **Backend test job.** `mvn test` — ~100 Java test files run nowhere.
3. **AI service test job.** `pytest` — `influora-ai/tests/**` runs nowhere. Includes Kabir's new
   injection regressions.
4. **Flyway validate** on every PR touching `db/migration/`.

---

## Definition of Done

- [ ] V48 merged; `mvn flyway:validate` green
- [ ] Nullability report posted to `SHARED_CONTEXT.md` (T1) — **blocks Ash and Ananya**
- [ ] Backfill dry-run cost report to Rohan; Swapnil approves before full run
- [ ] `ReliabilityStatsJob` scheduled, staleness alarm live
- [ ] Shared-schema diff-check merged and **demonstrated failing** on the current `goal` drift
- [ ] `mvn test` + `pytest` running in CI

Handoff: `FROM Meera → TO Vikram | V48 + nullability report | wiki/tech/employees/ | STATUS | NEXT`
