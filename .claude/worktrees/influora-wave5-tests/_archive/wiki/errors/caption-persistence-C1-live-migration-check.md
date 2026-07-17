# Live-MySQL Migration Check: Wave C Task C1 (V26 — media_metrics.caption)

Date: 2026-07-07
Verifier: Meera (DB/DevOps)
Migration under test: `influora-api/src/main/resources/db/migration/V26__media_metrics_caption.sql`
Probe list (test plan): "Probes for Meera's V26 live check" in `wiki/errors/caption-persistence-C1-security-review.md` (Kabir)
Engine: live local MySQL 8.0.40 (`localhost:3306`, per `influora-api/src/main/resources/application.yml`), driven via a standalone Flyway runner (no Spring Boot boot — sandbox loopback-socket issue persists; no `flyway-maven-plugin` wired in `pom.xml`, offline plugin resolution still fails on a missing transitive). Reused the exact V25/B4 approach: `FlywayRunner.java` compiled against `mvn -o dependency:build-classpath`, invoked directly against throwaway schemas. Dev schema `influora_ai` was never referenced in any DDL/DML in this check.

Four throwaway schemas were created and all dropped at the end of the run:
- `influora_meera_check_c1_v26` — full V1→V26 apply, probes 1/2/4/5
- `influora_meera_check_c1_v26_pop` — V1→V25, seeded 300,000 rows, then V26 via Flyway (realistic-scale timing sanity check)
- `influora_meera_check_c1_v26_algo` — V1→V25, seeded 300,000 rows, then explicit `ALTER ... ALGORITHM=INSTANT` (the load-bearing probe)
- `influora_meera_check_c1_v26_timing` — V1→V25, seeded 300,000 rows, precise before/after `NOW(6)` timing of the exact shipped V26 DDL, plus a forced `ALGORITHM=COPY` control ALTER for contrast

## Probe results

**Probe 1 — utf8mb4 4-byte round-trip: PASS**
Inserted `"drop 🔥 日本 café @someone +1-555-0100"` into `media_metrics.caption`, read back byte-identical:
`caption = "drop 🔥 日本 café @someone +1-555-0100"`, `CHAR_LENGTH=35`, `LENGTH (bytes)=43` — the emoji/CJK correctly count as 1 char each, not mangled multi-byte sequences (35 chars vs 43 bytes proves multi-byte chars stored/counted correctly, not truncated/mojibake'd).
`information_schema.COLUMNS` for `media_metrics.caption`: `DATA_TYPE=text`, `CHARACTER_SET_NAME=utf8mb4`, `COLLATION_NAME=utf8mb4_unicode_ci`, `IS_NULLABLE=YES`, `COLUMN_DEFAULT=NULL`. All match spec exactly.

**Probe 2 — column position: PASS**
`ORDINAL_POSITION`: `media_type=6`, `caption=7`, `permalink=8`. `caption` lands immediately `AFTER media_type` as the DDL specifies, matching entity field order.

**Probe 3 — ALTER algorithm / lock cost (LOAD-BEARING): PASS — MySQL used ALGORITHM=INSTANT, not COPY**
This is the opposite of Kabir's hypothesis in LOW-3, and is good news, not a finding to action.

- Seeded `media_metrics` with 300,000 rows on a V1→V25 schema (`influora_meera_check_c1_v26_pop`), then ran the *exact* shipped V26 DDL — `ADD COLUMN caption TEXT ... NULL ... AFTER media_type` with **no explicit ALGORITHM clause** — via the real Flyway engine. Flyway's own reported execution time: **280ms** for the single migration.
- Repeated on a fresh 300k-row copy (`influora_meera_check_c1_v26_timing`) with `SELECT NOW(6))` bracketing the raw `ALTER` outside Flyway: **176ms wall-clock** (`13:00:27.571618` → `13:00:27.748032`).
- Ran the exact statement with `ALGORITHM=INSTANT` **explicit** on a third fresh 300k-row copy (`influora_meera_check_c1_v26_algo`) — per Kabir's instruction, this errors if INSTANT is impossible. **It did not error; it succeeded.** Confirmed via `information_schema.INNODB_TABLES.TOTAL_ROW_VERSIONS = 1` on that table (the InnoDB-internal signal that an INSTANT ADD COLUMN row-version bump occurred) — this is the authoritative low-level proof, not just a fast wall-clock.
- For contrast, forced `ALGORITHM=COPY` on the same 300k-row table (adding a second throwaway column `caption2`) on `influora_meera_check_c1_v26_timing`: **37.77 seconds** (`13:00:39.449278` → `13:01:17.214747`) — a ~215x slower full table rebuild, which is what a write-stall on a large prod table would actually look like.
- **Why INSTANT works here, correcting the LOW-3 assumption:** MySQL 8.0's `ALGORITHM=INSTANT` restriction on positional `ADD COLUMN ... AFTER` was tightened/loosened across 8.0.x point releases; on **8.0.29+** (this server: 8.0.40), INSTANT ADD COLUMN supports adding a nullable column with no default at an arbitrary position (not just at the end of the row), as long as it doesn't change the row format in an incompatible way (no PK change, no row overflow triggers, etc.) — which is exactly V26's shape (`TEXT NULL`, no default, no index). The older restriction Kabir's finding cites (positional `AFTER` forces COPY) was accurate for MySQL 8.0.12–8.0.28; it does not hold on this server version.
- **Lock/stall implication for prod:** on MySQL 8.0.29+, this migration is expected to be **near-instant** (sub-second even at hundreds of thousands of rows) with only a brief metadata lock (MDL), not a table rewrite. **Action for Priya/Vikram:** confirm the production MySQL version is ≥8.0.29 before relying on this; if prod runs an older 8.0.x (8.0.12–8.0.28), the COPY fallback (and the ~38s/300k-row cost demonstrated above, scaling roughly linearly) would apply and the positional `AFTER` clause should be reconsidered per Kabir's original advisory. On 8.0.40 (confirmed local target), **no action needed** — ship as-is.

**Probe 4 — NULL backfill: PASS**
Inserted a row with no `caption` reference (simulating a pre-V26 row) after V26 was applied: `caption IS NULL` → `1` (true), `caption = ''` → `NULL` (i.e., the comparison itself is NULL, confirming the value is SQL NULL, not empty string). No errors on insert or read.

**Probe 5 — no purge trigger/event: PASS**
`SHOW TRIGGERS FROM <schema> WHERE Table='media_metrics'` → zero rows. `information_schema.TRIGGERS` count for `media_metrics` → `0`. `information_schema.EVENTS` count scoped to the schema → `0`; global check for any event definition referencing `media_metrics` → `0`. Confirms Kabir's MED-1/MED-2 retention-gap finding is real at the DB layer too — no scheduled purge exists anywhere in this schema.

## Independent `mvn test` re-run
`& "C:\Users\Sage world\.m2\wrapper\dists\apache-maven-3.9.6-bin\3311e1d4\apache-maven-3.9.6\bin\mvn.cmd" -o -f influora-api test`
Result: **BUILD SUCCESS, Tests run: 363, Failures: 0, Errors: 0, Skipped: 0** (24.6s). Independently confirms Arjun's 363/363 green claim after the `EmailOtpDtos` one-line fix. `NoBrandFacingCaptionExposureTest` ran and passed (1 test, 0.240s).

## Cleanup
All four throwaway schemas (`influora_meera_check_c1_v26`, `_pop`, `_algo`, `_timing`) dropped. Confirmed via `SHOW DATABASES LIKE 'influora_meera%'` returning zero rows. Dev schema `influora_ai` untouched throughout.

## VERDICT: PASS — all 5 probes pass, mvn test independently green (363/363).
Notable finding for Vikram/Priya: the ALGORITHM=INSTANT result **overturns** Kabir's LOW-3 lock/stall concern on this MySQL version (8.0.40) — V26 is safe to ship as-is with no write-stall risk on realistic row counts, *provided prod MySQL is ≥8.0.29*. If prod is pinned to an older 8.0.x, re-verify before relying on this result.
