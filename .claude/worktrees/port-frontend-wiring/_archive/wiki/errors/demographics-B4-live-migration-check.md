# Live-MySQL Migration Check: Wave B Task B4 — audience_demographics (V25)

**Date:** 2026-07-07
**Verifier:** Meera (DB/DevOps)
**Prior sign-offs:** Kavya QA (`wiki/errors/demographics-B4-review.md`) — APPROVED; Kabir security (`wiki/errors/demographics-B4-security-review.md`) — SIGN-OFF
**Trigger:** New migration (V25) ⇒ standing rule requires live-MySQL throwaway-DB check before this ships (`REMAINING_WORK_PLAN.md:14`). Prior run of this exact check was lost to a process crash; this is a fresh, complete run.
**Verdict: PASS — all 5 probes green, independent `mvn test` re-run confirms 359/359.**

## Setup

- Server: local MySQL 8.0.40 (Community, `mysql.exe` at `C:\Program Files\MySQL\MySQL Server 8.0\bin`), reachable at `127.0.0.1:3306`, `root`/`root` — matches `application.yml`'s `SPRING_DATASOURCE_URL` default (`jdbc:mysql://localhost:3306/Influora_AI`).
- `@@sql_mode`: `ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`; `@@explicit_defaults_for_timestamp = 1`.
- Throwaway schema: `CREATE DATABASE influora_meera_check_b4 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;` — dev DB (`Influora_AI`) untouched.
- Migration runner: no `flyway-maven-plugin` execution declared in `pom.xml` and offline plugin resolution failed on a missing transitive (`jackson-dataformat-toml:2.15.2`), so ran Flyway programmatically instead — a tiny `FlywayRunner.java` compiled/run against the app's own resolved classpath (`mvn -o dependency:build-classpath`, includes the exact `flyway-core`/`flyway-mysql`/`mysql-connector-j` versions pinned in `influora-api/pom.xml`), pointed at `filesystem:src/main/resources/db/migration`. This executes the real Flyway engine against live MySQL — equivalent DDL execution to `flyway:migrate`, no Spring Boot boot involved.
- Result: **all 25 migrations applied cleanly, V1→V25, `Success: true`**. V25 (`audience_demographics`) applied last, no errors.

## Probe results (Kabir's 5, from demographics-B4-security-review.md)

1. **FK is RESTRICT, not CASCADE — PASS.** Inserted a `users` + `creator_profiles` row (`01MEERACREATORCHECKB4TX1`) + an `audience_demographics` row referencing it, then `DELETE FROM creator_profiles WHERE id='01MEERACREATORCHECKB4TX1'` → **actual: `ERROR 1451 (23000): Cannot delete or update a parent row: a foreign key constraint fails (...audience_demographics, CONSTRAINT fk_audience_demographics_creator FOREIGN KEY (creator_profile_id) REFERENCES creator_profiles (id))`**. Delete blocked, not cascaded. Cross-check: `information_schema.REFERENTIAL_CONSTRAINTS` for `fk_audience_demographics_creator` → **`DELETE_RULE = 'NO ACTION'`, `UPDATE_RULE = 'NO ACTION'`** — matches sibling FKs (V21/V22), consistent with Kabir's finding.
2. **JSON typing — PASS.** `information_schema.COLUMNS WHERE TABLE_NAME='audience_demographics' AND DATA_TYPE='json'` → **actual: exactly 4 rows** — `age_gender_breakdown`, `country_breakdown`, `city_breakdown`, `locale_breakdown`, all `DATA_TYPE=json`.
3. **Index-supported read, not full scan — PASS.** Inserted a 2nd snapshot row for the same creator, then `EXPLAIN SELECT * FROM audience_demographics WHERE creator_profile_id=? ORDER BY time DESC LIMIT 1` → **actual: `type=ref`, `key=idx_audience_demographics_creator_time`, `key_len=106`, `ref=const`, `rows=2`, `Extra=Backward index scan`**. No `ALL`/full table scan.
4. **`explicit_defaults_for_timestamp` / `created_at` default — PASS.** Column def: `created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP` (`COLUMN_DEFAULT=CURRENT_TIMESTAMP`, `EXTRA=DEFAULT_GENERATED`). Inserted a row omitting `created_at` entirely → **actual: auto-populated to the exact current server time, matching a concurrent `NOW()` call** (`2026-07-07 12:22:31` both). Behaves correctly under this server's `explicit_defaults_for_timestamp=1` and current `sql_mode` — not just an H2/test assumption.
5. **utf8mb4 round-trip — PASS.** Inserted `city_breakdown = {"São Paulo, BR": 12, "日本": 5}`, `locale_breakdown = {"pt_BR": 12, "ja_JP": 5}` via `JSON_OBJECT(...)` under `SET NAMES utf8mb4`. Read back: **`city_breakdown` returned as `{"日本": 5, "São Paulo, BR": 12}`** — `JSON_EXTRACT(..., '$."São Paulo, BR"')` → `12`, `JSON_EXTRACT(..., '$."日本"')` → `5`, `CHAR_LENGTH('日本')` (unquoted) → `2` (correct character count, not mangled byte count). Table collation confirmed `utf8mb4_unicode_ci`. No mojibame, no truncation.

## Independent `mvn test` re-run

`mvn -o -f influora-api test` → **BUILD SUCCESS, Tests run: 359, Failures: 0, Errors: 0, Skipped: 0** (19.068s). Confirms the 359/359 green claim from the post-crash E2 rework independently, on this machine, this run — not carried over from a prior report.

## Cleanup

`DROP DATABASE influora_meera_check_b4;` — confirmed gone (`SHOW DATABASES LIKE ...` empty). Dev schema (`Influora_AI`) was never touched. No git commit made.

## Summary table

| Probe | Result |
|---|---|
| 1. FK RESTRICT (1451) + REFERENTIAL_CONSTRAINTS.DELETE_RULE | PASS — 1451 raised; DELETE_RULE=NO ACTION |
| 2. 4× JSON columns | PASS — exactly 4 rows, all DATA_TYPE=json |
| 3. EXPLAIN uses idx_audience_demographics_creator_time | PASS — ref/backward index scan, not ALL |
| 4. created_at DEFAULT CURRENT_TIMESTAMP on real server | PASS — auto-populated correctly under explicit_defaults_for_timestamp=1 |
| 5. utf8mb4 non-ASCII round-trip | PASS — São Paulo/日本 keys readable, no mojibake/truncation |
| Independent mvn test re-run | PASS — 359/359, BUILD SUCCESS |

**No blockers. V25 is cleared for live MySQL.**
