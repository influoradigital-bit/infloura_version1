# Go-live runbook — 2026-09-18 (T-GOLIVE-0918)

Author: Meera (DB/DevOps), lane DEPLOY. Everything below is cited to file:line. Nothing in
this document was executed against the live Utho box — see "What this document is NOT" below.

## READ THIS FIRST — two things that block a same-day go-live as briefed

### 1. The live box does not run docker-compose for Influora. `/usr/local/App/docker-compose.prod.yml` is Snapsby's file, not Influora's.

The go-live brief that produced this document assumed the deploy target is
`docker compose -f /usr/local/App/docker-compose.prod.yml up -d`. That is **not what the most
recent verified capture of the live box shows**:

- `.proof-os/tasks/T-UTHO-DEPLOY-0907/live-state.txt:91-120` — `cat /usr/local/App/docker-compose.prod.yml`
  shows exactly ONE service (`backend` / container `snaps-backend`, port `8081:8080`) — this is
  **Snapsby's** compose file. It never mentions `influora-api`, `influora-ai`, `mysql`, `redis`, or
  `caddy`.
- `live-state.txt:172-185` — `docker inspect influora-api influora-ai` shows both containers with
  `net=host`, `compose labels: project=[] files=[]` **on both** — the live-state.txt's own
  annotation reads "confirmed hand-run, no compose".
- `live-state.txt:166-170` — the API image is built on-box from a hand-copied jar
  (`/usr/local/App/influora/influora-api.jar`) via a 4-line Dockerfile
  (`FROM eclipse-temurin:21-jre` + `COPY` + `ENTRYPOINT java -Xmx1024m -jar ...`), **not** pulled
  from `ghcr.io/influoradigital-bit/influora-api`.
- `deploy/utho/README.md:1-27` carries its own banner: *"THIS DESIGN WAS NEVER BUILT. DO NOT
  FOLLOW IT AS A MAP OF THE LIVE BOX."* — a live inspection on 2026-09-07 found no Caddy
  container, no compose file for Influora at all, and one prior mistake where env vars were
  aimed at the wrong (`/usr/local/App/.env`, Snapsby's) file.

**Section 3 (Deploy) below documents the mechanism `live-state.txt` actually proves is running
today** (SSH + hand-copied jar/image + `docker run --network host --env-file`), not the
compose-based design in `deploy/utho/docker-compose.utho*.yml`. Those two compose files are a
**target design for a future migration**, not today's deploy path — I still fixed the two gaps
in them the brief asked for (§0 below) because the brief explicitly owns them and correctness
there matters whenever that migration happens, but running `docker compose up` against them
today would do nothing to the live box (they were never copied there — same README banner).

**`live-state.txt` is 11 days old (2026-09-07).** I found no newer capture. Before running
anything in Section 3, re-run the read-only checks in Section 3.0 to confirm the box is still in
this state — if someone migrated it to compose in the last 11 days, the mechanism below is stale
and Section 3's compose-based alternative applies instead.

### 2. A parallel/duplicate lane already did most of this analysis today

`.proof-os/tasks/T-PHASEB-LIVE-0918/meera-answers.md` (file mtime 2026-09-18 12:44, i.e. **before
this session started**) is a near-duplicate of this task: same date, same "Meera" byline, same
env-var-gap analysis (it independently found the identical `AI_CREATOR_MONTHLY_CAP_USD` gap in
`docker-compose.utho-shared.yml`'s `influora-ai` block, and additionally found
`CREATOR_COPILOT_AI_BASE_URL` / `MEERA_CREATOR_ENABLED` missing from that file **for the B0
branch's copy** of it), a reproduced keyset-gate run, a deploy checklist, and a top-3-risks list.
It is scoped to `feat/meera-creator-phase-b0`, a **different branch** from this task's
`feat/meera-creator-phase-e` (confirmed: `git status` at session start shows this worktree on
`feat/meera-creator-phase-e`; that file explicitly diffs against
`C:/.../influora-b0`, branch `feat/meera-creator-phase-b0`).

Flagging per hard rule 8 ("stop and report rather than make a business decision the brief does
not give you") rather than silently reconciling two lanes' conclusions: **Swapnil/Arjun should
confirm which branch is actually going live today** (`feat/meera-creator-phase-e`, per this
worktree's `git status`, or `feat/meera-creator-phase-b0`) before following either document's
checklist, since they cover overlapping but not identical scope (B0 adds 4 Flyway migrations and
a creator-briefs feature that this repo's tree does not have — see §5 below).

### What this document is NOT

Not a live-execution report. No SSH session was opened, no command below was run against
`150.241.245.242`. Everything is derived from this repo's source, the 2026-09-07 capture cited
above, and `git`/`docker compose config` run locally against the working tree (Section 0).

---

## §0 — What I actually changed and proved locally (in scope for this lane)

Files owned by this lane: `deploy/utho/docker-compose.utho.yml`,
`deploy/utho/docker-compose.utho-shared.yml`, `.proof-os/gates/utho-compose-keysets-match.sh`
(read, not modified — no new allow-list entry was needed), this file.

1. **`TREND_INGEST_PULL_CRON` was unforwarded in both compose files** (F-0787-class gap — an
   explicit-map `environment:` block silently drops any key not listed). `application.yml:559`
   reads `${TREND_INGEST_PULL_CRON:0 0 5 * * *}`; `TrendIngestProperties.java:33-35` documents the
   same. Added to both files' `influora-api` block, mirroring the code default as the compose-side
   default (`${TREND_INGEST_PULL_CRON:-0 0 5 * * *}`) rather than an empty default, because an
   empty-but-present value is a known foot-gun in this codebase (see the `META_CREATOR_MARKETPLACE_ENABLED`
   comment already in both files, e.g. `docker-compose.utho.yml:259-263`) — though
   `TrendIngestProperties.setPullCron` (`TrendIngestProperties.java:108-110`) also guards a blank
   value back to the same default, so this is belt-and-suspenders, not load-bearing.
2. **`AI_CREATOR_MONTHLY_CAP_USD` was present in `docker-compose.utho.yml`'s `influora-ai` block
   but absent from `docker-compose.utho-shared.yml`'s `influora-ai` block** (confirmed by reading
   both files directly). Source: `influora-ai/app/config.py:455-457`
   (`_get_float("AI_CREATOR_MONTHLY_CAP_USD", 0.75)`). Added `AI_CREATOR_MONTHLY_CAP_USD: "0.75"`
   to `docker-compose.utho-shared.yml`. Note: `.proof-os/gates/utho-compose-keysets-match.sh:63`
   states outright it does not check `influora-ai`'s keys, so this specific gap would not have
   been caught by re-running the gate — I found it by reading both files' `influora-ai` blocks
   side by side.
3. **`MEERA_CREATOR_SEND_ENABLED`, named in the brief, does not exist in this repo.** Confirmed by
   `grep -rn "SEND_ENABLED|send-enabled|sendEnabled"` across the whole New Influora tree (0
   matches in `influora-api/src/main/resources/application.yml` or any Java source) and by
   reading `MeeraCreatorFeatureProperties.java:26-38`, which has only `creatorEnabled`, no send
   flag. It **does** exist in the sibling `influora-b0` worktree's
   `influora-api/src/main/resources/application.yml:243` (`creator-send-enabled:
   ${MEERA_CREATOR_SEND_ENABLED:false}`), gating a `send_routine_reply` creator tool that its own
   comment (`application.yml:238-242` in that tree) says "does not exist yet" even on that branch.
   Per hard rule 8, I did **not** invent this variable in this repo's compose files — wiring an
   env var for a property this deployed code never reads would be dead config that misleads
   whoever reads the compose file next. If B0's phase is part of today's go-live, that merge
   brings its own `application.yml` change and this variable should be added to both compose
   files at that time, not before.
4. **Not wired to anything yet: `TREND_INGEST_PULL_CRON`, and trend ingest generally.**
   `grep -rn "NewsApiTopHeadlinesClient|TmdbUpcomingClient|YouTubeMostPopularClient"` across
   `influora-api/src/main/java/` finds only the three client classes themselves plus
   `TrendSourceClient.java` (their shared interface) — **no `@Scheduled` job, no controller, calls
   any of them.** `grep -rln "@Scheduled"` across the whole `job/` package (23 files) confirms
   there is no `TrendIngestJob` or equivalent. So today, `TREND_INGEST_ENABLED=true` plus valid
   API keys still results in **no automatic trend pull** — the ingest clients are built but not
   scheduled. This affects §4's post-deploy checks below (there is no log line to check for a
   trend pull that cannot run).
5. **Gate re-run against the working tree, after the two fixes above:**
   ```
   $ bash .proof-os/gates/utho-compose-keysets-match.sh
   allowed: JAVA_TOOL_OPTIONS only in deploy/utho/docker-compose.utho-shared.yml
   checked: influora-api environment keys — 87 in deploy/utho/docker-compose.utho.yml, 88 in deploy/utho/docker-compose.utho-shared.yml
   NOT CHECKED: key VALUES/defaults; other services (influora-ai, caddy, mysql); the live box's env file (/usr/local/App/influora/influora.env, not in this repo — F-0842)
   PASS: key sets match (modulo allow-list)
   ```
   (87 vs 88 is expected and allow-listed: `JAVA_TOOL_OPTIONS` only belongs in the shared-box
   file, per the existing `ALLOW="JAVA_TOOL_OPTIONS"` entry, `utho-compose-keysets-match.sh:18` —
   no new allow-list entry was needed.)
6. **YAML/interpolation validated locally**, twice: `python -c "import yaml; yaml.safe_load(...)"`
   parsed both files without error, and `docker compose -f docker-compose.utho.yml config` /
   `docker compose -f docker-compose.utho-shared.yml config` (run from `deploy/utho/`, exit code 0
   both times) resolved `TREND_INGEST_PULL_CRON` to the literal `0 0 5 * * *` and
   `AI_CREATOR_MONTHLY_CAP_USD` to `"0.75"` in both files, confirming the new lines interpolate
   correctly and the cron string's `*` characters do not break Compose's variable substitution.
   This is config validation, not a database/persistence test — rule 5 does not apply to YAML.

---

## §1 — Database backup (run before anything else)

Live DB identity, from `.proof-os/tasks/T-UTHO-DEPLOY-0907/live-state.txt:100-103,187-192`:
database `influora`, user `influora_app`, reachable at `localhost:3306` from the box (the API
connects via `net=host`). Password is in `/usr/local/App/influora/influora.env` — never put it on
a command line where it lands in shell history or `ps`; use `--defaults-extra-file` or an
interactive prompt.

```bash
ssh root@150.241.245.242
mkdir -p /root/backups
mysqldump --single-transaction --routines --triggers \
  -u influora_app -p influora \
  | gzip > /root/backups/influora-$(date +%Y%m%d-%H%M%S).sql.gz
# verify it is non-trivial in size and gzip-valid before proceeding:
gzip -t /root/backups/influora-*.sql.gz && ls -lh /root/backups/
```

Copy the dump off the box before touching anything else (`scp` to a machine you control) — the
box has one disk shared with `mysql_data`/logs (see the log-rotation comment at
`docker-compose.utho.yml:33-45`, which documents this box's disk-full history), so do not leave
the only copy there.

## §2 — Environment variables to set in `/usr/local/App/influora/influora.env`

This list is the `influora-api` environment keyset from `docker-compose.utho.yml` (87 keys after
§0's fix), which is the closest in-repo source of truth for "every var this release needs" even
though the live box is not actually driven by that compose file (§READ THIS FIRST). Names only —
**no values are given here**, per hard rule 8 (no secrets in commits/docs).

Grouped by today's release features, each cited to its file:line origin:

**Trend ingest (T4 ingest job — clients exist, no scheduler yet; see §0.4)**
| Var | Secret? | Source |
|---|---|---|
| `TREND_INGEST_ENABLED` | no | `application.yml:555` |
| `NEWSAPI_KEY` | **yes** | `application.yml:556` |
| `TMDB_API_KEY` | **yes** | `application.yml:557` |
| `YOUTUBE_API_KEY` | **yes** | `application.yml:558` |
| `TREND_INGEST_PULL_CRON` | no | `application.yml:559` (now forwarded — §0.1) |

**Creator AI Co-pilot**
| Var | Secret? | Source |
|---|---|---|
| `CREATOR_COPILOT_ENABLED` | no | `application.yml:542` |
| `CREATOR_COPILOT_SCORE_THRESHOLD` | no | `application.yml:543` (optional, has default) |
| `CREATOR_COPILOT_DAILY_CAP` | no | `application.yml:544` (optional) |
| `CREATOR_COPILOT_PROMPT_VERSION` | no | `application.yml:545` (optional) |
| `CREATOR_COPILOT_CAPTION_SYNC_MEDIA_LIMIT` | no | `application.yml:546` (optional) |
| `CREATOR_COPILOT_CAPTION_SYNC_MAX_CREATORS_PER_RUN` | no | `application.yml:547` (optional) |
| `CREATOR_COPILOT_CAPTION_SYNC_CRON` | no | `application.yml:548` (optional) |
| `CREATOR_COPILOT_THEME_TAG_CRON` | no | `application.yml:549` (optional) |
| `CREATOR_COPILOT_AI_BASE_URL` | no (internal DNS/URL) | `application.yml:257`; compose hardcodes `http://influora-ai:8000` in both files (`docker-compose.utho.yml:176`, `docker-compose.utho-shared.yml:204`) — on the live box this must instead be the AI container's real reachable address, since there is no shared Docker network without compose (see §READ THIS FIRST) |

**Brand-safety client (Spring → influora-ai)**
| Var | Secret? | Source |
|---|---|---|
| `BRAND_SAFETY_AI_BASE_URL` | no | `BrandSafetyAiProperties.java:19-32` (prefix `influora.brand-safety-ai`), `application.yml:243` |
| `BRAND_SAFETY_SERVICE_TOKEN_SECRET` | **yes** | `BrandSafetyServiceTokenProperties.java:35-57` (prefix `influora.brand-safety-service-token`), `application.yml:279` |
| `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` | **yes** | already forwarded in both compose files (`docker-compose.utho.yml:155`) — this is Spring Boot's relaxed-binding env-var form of the same `influora.brand-safety-service-token.signing-secret` property the row above sets via the `${BRAND_SAFETY_SERVICE_TOKEN_SECRET}` YAML placeholder; **set both to the identical value** or whichever one the operator actually exports wins unpredictably by property-source order. This existing double-path was not introduced by this lane and is out of this lane's file scope to simplify — flagging for Priya/Vikram. |
| `BRAND_SAFETY_SCORING_ENABLED` | no | `application.yml:523` (optional) |
| `BRAND_SAFETY_SCORING_MAX_CREATORS_PER_RUN` | no | `application.yml:524` (optional) |

**Meera for Creators**
| Var | Secret? | Source |
|---|---|---|
| `MEERA_CREATOR_ENABLED` | no | `MeeraCreatorFeatureProperties.java:31`, `application.yml` (rollback flag, default `true`) |
| `AI_CREATOR_MONTHLY_CAP_USD` | no | `influora-ai/app/config.py:455-457` (now also forwarded in `docker-compose.utho-shared.yml`'s `influora-ai` block — §0.2) |
| `MEERA_CREATOR_SEND_ENABLED` | — | **not added — does not exist in this repo's code today.** See §0.3. Do not set this on the live box unless B0's `send_routine_reply` feature is confirmed part of today's release. |

Everything else already in `docker-compose.utho.yml`'s `influora-api` block (DB creds, JWT/HMAC
signing secrets, R2, Razorpay, MSG91/SMTP, Meta OAuth, GST identity — lines 84-276) is pre-existing
scope, unrelated to today's three features, and not repeated here; see that file directly for the
full list if `influora.env` is being rebuilt from scratch rather than incrementally updated.

## §3 — Build and deploy

### 3.0 — Re-verify the box before doing anything (live-state.txt is 11 days stale)

```bash
ssh root@150.241.245.242 'docker inspect influora-api influora-ai --format "{{.Name}} compose-project={{index .Config.Labels \"com.docker.compose.project\"}}"'
```
If this still prints an empty `compose-project=` for both (matching `live-state.txt:184-185`),
the box is unchanged and 3.1–3.3 apply. If it now shows a compose project, stop and use
`docker compose -f <that file> pull && docker compose -f <that file> up -d` instead, against
whatever compose file `docker inspect ... --format "{{index .Config.Labels
\"com.docker.compose.project.config_files\"}}"` names — do not assume it is
`docker-compose.prod.yml` (§READ THIS FIRST §1).

### 3.1 — Build images via GitHub Actions from this branch

`.github/workflows/publish-images.yml:57-83` restricts the automatic `push` trigger to `branches:
[main]`. This branch (`feat/meera-creator-phase-e`, or `feat/meera-creator-phase-b0` — confirm
per §READ THIS FIRST §2) will not auto-publish. `workflow_dispatch` (`publish-images.yml:16-55`)
has no branch restriction in its trigger definition, and GitHub's dispatch UI/API lets you pick
the target ref explicitly — but the workflow file used is whatever is committed on the branch you
select, so it must be **pushed to GitHub first**:

```bash
git push origin feat/meera-creator-phase-e   # or whichever branch is going live — confirm first
gh workflow run publish-images.yml --ref feat/meera-creator-phase-e \
  -f publish_web=true \
  -f vite_api_base_url=https://influora.in/api/v1 \
  -f vite_meera_stream_url=https://ai.influora.in
```
(`vite_api_base_url` is `https://influora.in/api/v1`, **not** `https://api.influora.in/api/v1` —
`live-state.txt:214-224` records F-0721: the `api.` subdomain has no working cert on this box, the
live bundle's real base is the apex, and `.env.production:38` was already corrected to match. Do
not regress this on a fresh dispatch.)

This publishes `ghcr.io/influoradigital-bit/influora-api:<sha>` and `...influora-ai:<sha>`. **The
live box does not currently pull these images** (§READ THIS FIRST §1) — getting them onto the box
is a manual step (3.2), not automatic.

### 3.2 — Deploy to the live box (mechanism `live-state.txt` proves is running today)

```bash
# API: rebuild the on-box image from a fresh jar (matches live-state.txt:166-170's Dockerfile)
scp influora-api/target/influora-api.jar root@150.241.245.242:/usr/local/App/influora/influora-api.jar
ssh root@150.241.245.242 <<'EOF'
cd /usr/local/App/influora
docker build -t influora-api:latest .
docker stop influora-api && docker rm influora-api
docker run -d --name influora-api --network host --restart unless-stopped \
  --env-file influora.env influora-api:latest
EOF
```
Do the equivalent for `influora-ai` under `/usr/local/App/influora-ai/` with its own env file
(`/usr/local/App/influora-ai/influora-ai/influora-ai.env`, per `live-state.txt:148-152,228-230`).

**Docker bakes env at container-create time — `docker restart` re-reads nothing**
(`deploy/utho/README.md` banner, `live-state.txt:233-235` reproduces this exact mistake once
already: files were edited correctly on 2026-09-07 but the running containers kept stale values
until recreated). The `stop && rm && run` sequence above is required, not `docker restart`.

### 3.3 — Frontend

Live-state shows the SPA is static files at `/var/www/influora/`, served by nginx directly
(`live-state.txt:59,210`), not a `frontend` container. Deploy by building the `web` image (3.1) and
extracting its static output, or by running the existing frontend build/deploy step this repo
already uses elsewhere for that path — this lane owns compose/DB/runbook files only, not the
frontend deploy script, so citing the mechanism rather than prescribing new steps for it.

## §4 — Post-deploy checks

```bash
# 1. API up, DB reachable (HealthController delegates to the DB health indicator)
curl -s -o /dev/null -w '%{http_code}\n' https://influora.in/api/v1/health
# expect 200 — HealthController.java:25-31 returns actuator-derived status + R2 config state,
# not a hardcoded "ok"

# 2. Full health payload
curl -s https://influora.in/api/v1/health
# expect {"status":"UP","storage":{"r2":"configured"}} — HealthController.java:26-30

# 3. workspaces sanity check (brief's requested query)
mysql -u influora_app -p influora -e "SELECT type, COUNT(*) FROM workspaces GROUP BY type;"
# workspaces.type is ENUM('BRAND','AGENCY') NOT NULL DEFAULT 'BRAND' —
# db/migration/V2__core_auth.sql:26. Confirms the DB the freshly-recreated container is pointed
# at is the real, non-empty production database, not a blank one from a bad SPRING_DATASOURCE_URL.
```

**Trend ingest and Creator Co-pilot log checks — read this before waiting on them:**

- **Trend ingest has no log line to check for today.** §0.4 proved there is no `@Scheduled` job
  consuming `TrendIngestProperties`/`TREND_INGEST_PULL_CRON` — setting `TREND_INGEST_ENABLED=true`
  plus the three API keys does not currently produce any scheduled pull, so there is nothing to
  `docker logs | grep` for. If a trend-pull job is expected to run today, it does not exist in
  this codebase yet and this is a blocker to raise with Priya/Vikram, not a deploy-config gap.
- **Creator Co-pilot** does have a real, previously-observed log line —
  `CreatorCaptionSyncJob.java:124-127` — and `live-state.txt:311-323` shows it firing exactly this
  way in production on 2026-09-08:
  ```bash
  docker logs influora-api --since 10m | grep "CreatorCaptionSyncJob"
  # on schedule (02:00 UTC, or CREATOR_COPILOT_CAPTION_SYNC_CRON if overridden), expect:
  # "CreatorCaptionSyncJob: completed run — N creators processed, N captions inserted, ..."
  # if CREATOR_COPILOT_ENABLED is false or unset, expect instead:
  # "CreatorCaptionSyncJob: disabled (influora.creator-copilot.enabled=false), skipping run"
  ```
  Do not wait until 02:00 UTC to find out the flag was wrong — `live-state.txt:311-320` shows the
  team previously forced an off-schedule run with a temporary 5-minute cron
  (`CREATOR_COPILOT_CAPTION_SYNC_CRON=0 */5 * * * *`) to observe the job same-day, then reverted
  it. Same technique applies here if same-day confirmation is required.

## §5 — Rollback

**Code/containers:**
```bash
ssh root@150.241.245.242
docker stop influora-api && docker rm influora-api
docker run -d --name influora-api --network host --restart unless-stopped \
  --env-file influora.env.bak-<timestamp> influora-api:<previous-sha-or-tag>
```
Keep the previous jar/image tag and a timestamped copy of `influora.env` before overwriting it
(`cp influora.env influora.env.bak-$(date +%Y%m%d-%H%M%S)`) — this mirrors the `*.bak.20260907`
convention `live-state.txt:232` already used on this box.

**Database:** `.proof-os/tasks/T-PHASEB-LIVE-0918/meera-answers.md:93-98` (the parallel lane, §READ
THIS FIRST §2) already proved there is **no rollback migration path** for Flyway on this project —
community-edition Flyway ships no `undo`, and a `grep -n rollback` across
`wiki/processes/schema-changes.md` and the migration files returns nothing. If today's release
includes new migrations (check `influora-api/src/main/resources/db/migration/` for anything
`git log` since the last known-good deploy), the only rollback is:
1. Stop `influora-api` (prevents further writes against the new schema).
2. Restore the §1 `mysqldump` backup into a fresh database.
3. Point `SPRING_DATASOURCE_URL` at the restored DB and recreate the container per §3.2.
This is a full-restore rollback, not a targeted one — get sign-off before starting rather than
mid-incident.

**Feature flags** (fastest rollback, no restart needed if hot-reloadable, otherwise one
recreate-cycle): flip `MEERA_CREATOR_ENABLED=false`, `CREATOR_COPILOT_ENABLED=false`, or
`TREND_INGEST_ENABLED=false` in `influora.env` and recreate the container (§3.2) — each is a
plain boolean gate (`MeeraCreatorFeatureProperties.java:31`, `application.yml:542`,
`application.yml:555`) with no migration dependency.

---

## Open items for Swapnil / Arjun (not decided here — hard rule 8)

1. **Which branch is actually going live** — `feat/meera-creator-phase-e` (this worktree) or
   `feat/meera-creator-phase-b0` (the parallel lane's scope). They are not the same diff (B0 adds
   4 migrations and a creator-briefs feature this tree does not have).
2. **`MEERA_CREATOR_SEND_ENABLED`** — only relevant if B0 is in scope; not wired here (§0.3).
3. **The `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` / `BRAND_SAFETY_SERVICE_TOKEN_SECRET`
   double-path** (§2) — pre-existing, not introduced by this lane, but worth a single-owner fix
   before it causes a silent secret mismatch.
4. **Trend ingest has no scheduler** (§0.4) — confirm whether that is expected for today's release
   or a missing piece.
5. **Confirm the live box's actual current deploy mechanism** (§3.0) before running any command in
   §3 — the cited evidence is 11 days old.
