# Go-live runbook — 2026-09-18 (T-GOLIVE-0918)

Author: Meera (DB/DevOps), lane DEPLOY. Everything below is cited to file:line. Nothing in
this document was executed against the live Utho box — see "What this document is NOT" below.

**REPAIR ROUND 1 (2026-09-18, this revision):** an independent reviewer failed commit `ba3fa62`
on 9 defects (3 HIGH: wrong jar filename in §3.2, rollback ordering, deploying from a dirty
working tree instead of the reviewed SHA; 3 MEDIUM: wrong env file for
`AI_CREATOR_MONTHLY_CAP_USD`, an unstated hard-fail requirement on `CREATOR_COPILOT_AI_BASE_URL`,
and two evidence files that are untracked/not in `git archive`; 3 LOW: a health-check status
claim, a mischaracterized property-override, and two hardcoded compose values that defeated their
own override instructions). All 9 are fixed below and in the two compose files, each marked
**[REPAIR R1]** at its fix point. See the bottom "REPAIR ROUND 1 — defect-by-defect" section for
the full before/after mapping if you are auditing this revision against the failed one.

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

### [REPAIR R1] A caveat on this document's two biggest evidence citations

`.proof-os/tasks/T-UTHO-DEPLOY-0907/live-state.txt` and
`.proof-os/tasks/T-PHASEB-LIVE-0918/meera-answers.md` — the two files this runbook leans on most
for "what the live box actually runs" — are **both untracked** (`git ls-files -- <path>` returns
nothing for either, confirmed against this revision's parent commit). Neither is inside `git
archive <sha>` of any commit in this repo's history, including this one. A reviewer checking this
runbook purely from a commit checkout cannot open either file to verify the citations to them
(`live-state.txt:91-235`, `meera-answers.md:93-98`, etc.) — they can only trust this document's
quotes of them. That is a real gap, not a formality: **this lane does not own either file** (they
belong to `T-UTHO-DEPLOY-0907` and `T-PHASEB-LIVE-0918`, not `deploy/utho/*` or
`wiki/processes/*`), so committing them is out of scope here — flagged to Arjun below as an open
item. Until one of those task owners commits them, treat every citation to `live-state.txt` or
`meera-answers.md` in this document as **unverifiable from the commit alone**, and re-derive the
load-bearing facts (live box has no Influora compose file; on-box jar path; DB host/user) directly
against the box via §3.0's read-only re-verification step before trusting them for today's deploy.

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
   comment already in both files, e.g. `docker-compose.utho.yml:259-263`).
   **[REPAIR R1 — was wrong]** The original commit claimed `TrendIngestProperties.setPullCron`
   (`TrendIngestProperties.java:108-110`) also guards a blank value, making this
   "belt-and-suspenders, not load-bearing." That guard only runs for the
   `@ConfigurationProperties`-bound bean. `TrendPullJob.java:123` — landed in `af23eb9`, *after*
   `ba3fa62` was cut, on this same branch — schedules itself with its own
   `@Scheduled(cron = "${influora.trend-ingest.pull-cron:0 0 5 * * *}")`, which resolves the
   `influora.trend-ingest.pull-cron` property straight from the Spring `Environment` and never
   touches `setPullCron`. Spring's `${key:default}` only substitutes the default when `key` is
   **absent**, not when it resolves to `""` — so a present-but-blank `TREND_INGEST_PULL_CRON` would
   make `@Scheduled` receive `cron=""` and fail to parse at boot. This compose default is now
   **load-bearing**; the comment in both compose files has been corrected (see the compose diffs).
2. **`AI_CREATOR_MONTHLY_CAP_USD` was present in `docker-compose.utho.yml`'s `influora-ai` block
   but absent from `docker-compose.utho-shared.yml`'s `influora-ai` block** (confirmed by reading
   both files directly). Source: `influora-ai/app/config.py:455-457`
   (`_get_float("AI_CREATOR_MONTHLY_CAP_USD", 0.75)`). Added to `docker-compose.utho-shared.yml`.
   **[REPAIR R1 — was wrong]** Both files originally pinned it as a bare `"0.75"` string literal,
   which Compose passes through unchanged regardless of what is in the host's env file — so §2's
   instruction to "set `AI_CREATOR_MONTHLY_CAP_USD`" would have had **zero effect** on either box.
   Both files now use `${AI_CREATOR_MONTHLY_CAP_USD:-0.75}`; the 0.75 default is unchanged, only
   the (missing) override path is fixed. Note: `.proof-os/gates/utho-compose-keysets-match.sh:63`
   states outright it does not check `influora-ai`'s keys, so neither the original gap nor this
   literal-vs-placeholder defect would have been caught by re-running the gate — both were found by
   reading the `influora-ai` blocks directly.
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
   files at that time, not before. **This name intentionally has no `${}` placeholder in this
   repo** — that absence is the point being documented, not an oversight.
4. **[REPAIR R1 — this whole item was stale]** Trend ingest **is now wired**. At the time `ba3fa62`
   was written, `grep -rn "NewsApiTopHeadlinesClient|TmdbUpcomingClient|YouTubeMostPopularClient"`
   found only the three client classes with no `@Scheduled` consumer. Commit `af23eb9`
   ("feat(trendspark): L10 trend-pull job"), on this same branch, added
   `influora-api/src/main/java/com/influora/job/TrendPullJob.java` — `@Scheduled(cron =
   "${influora.trend-ingest.pull-cron:0 0 5 * * *}", zone = "UTC")` at line 123, gated by
   `props.isEnabled()` (line 126). This changes §4's post-deploy checks below: there is now a real
   log line to check for a trend pull.
5. **Gate re-run against the working tree, after the compose fixes above (original two gaps plus
   the REPAIR R1 literal→placeholder fixes):**
   ```
   $ bash .proof-os/gates/utho-compose-keysets-match.sh
   allowed: JAVA_TOOL_OPTIONS only in deploy/utho/docker-compose.utho-shared.yml
   checked: influora-api environment keys — 87 in deploy/utho/docker-compose.utho.yml, 88 in deploy/utho/docker-compose.utho-shared.yml
   NOT CHECKED: key VALUES/defaults; other services (influora-ai, caddy, mysql); the live box's env file (/usr/local/App/influora/influora.env, not in this repo — F-0842)
   PASS: key sets match (modulo allow-list)
   ```
   (87 vs 88 is expected and allow-listed: `JAVA_TOOL_OPTIONS` only belongs in the shared-box
   file, per the existing `ALLOW="JAVA_TOOL_OPTIONS"` entry, `utho-compose-keysets-match.sh:18` —
   no new allow-list entry was needed. Key *names* are unaffected by the REPAIR R1 literal→
   placeholder value changes, so the count is identical to the original run.)
6. **YAML/interpolation validated locally**, on the REPAIR R1 revision: `yaml.safe_load(...)`
   parsed both files without error, and `docker compose -f docker-compose.utho.yml config` /
   `docker compose -f docker-compose.utho-shared.yml config` (run from `deploy/utho/`, exit code 0
   both times) resolved `TREND_INGEST_PULL_CRON` to the literal `0 0 5 * * *`,
   `AI_CREATOR_MONTHLY_CAP_USD` to `"0.75"`, and `MEERA_CREATOR_ENABLED` to `"true"` in both files
   with no host env set — confirming the new `${VAR:-default}` forms interpolate to the same
   defaults the old literals did, and that an operator-supplied override would now actually reach
   the container. This is config validation, not a database/persistence test — rule 5 does not
   apply to YAML.

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

## §2 — Environment variables to set

**[REPAIR R1 — the single target file below was wrong for one row]** Most of this release's
variables belong in the `influora-api` container's env file,
`/usr/local/App/influora/influora.env` (per `live-state.txt:148-152,228-230`, the API's actual
`--env-file` on the live box). One exception: `AI_CREATOR_MONTHLY_CAP_USD` is read only by the
`influora-ai` (Python) process (`influora-ai/app/config.py:455-457`), which on the live box runs
with its **own, separate** env file, `/usr/local/App/influora-ai/influora-ai/influora-ai.env`
(same source). Setting it in `influora.env` would bind to nothing — the Java process never reads
it and nothing forwards it to the Python one. Every row below targets `influora.env` **except**
`AI_CREATOR_MONTHLY_CAP_USD` in the "Meera for Creators" table, called out again at that row.

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
| `CREATOR_COPILOT_AI_BASE_URL` | no (internal DNS/URL), **REQUIRED, non-loopback** | dev default `http://localhost:8000` at `application.yml:257`, but under the prod profile `application-prod.yml:100` overrides it with **no default** (`base-url: ${CREATOR_COPILOT_AI_BASE_URL}`) — Spring throws `PlaceholderResolutionException` at boot if it is unset at all (`application-prod.yml:55-77` spells this out as deliberate, "*** CRITICAL DEPLOY SEQUENCING ***"). Even if set, `SecretsStartupValidator.validateAiServiceUrls` (`SecretsStartupValidator.java:606-620`, calling `checkNotUnroutableHost` at line 703) rejects a loopback/localhost value outside dev, and `SecretsStartupValidator.java:282-353` throws `IllegalStateException` (not just a warning) for any non-dev environment. Compose hardcodes `http://influora-ai:8000` in both files (`docker-compose.utho.yml:176`, `docker-compose.utho-shared.yml:204`) — on the live box this must instead be the AI container's real reachable address, since there is no shared Docker network without compose (see §READ THIS FIRST). Note `application-prod.yml:74-76`'s own comment claiming this var is "missing" from `docker-compose.utho-shared.yml` is stale — it has been present since before this lane's changes (`docker-compose.utho-shared.yml:204`); that comment lives outside this lane's file scope to correct. |

**Brand-safety client (Spring → influora-ai)**
| Var | Secret? | Source |
|---|---|---|
| `BRAND_SAFETY_AI_BASE_URL` | no | `BrandSafetyAiProperties.java:19-32` (prefix `influora.brand-safety-ai`), `application.yml:243` |
| `BRAND_SAFETY_SERVICE_TOKEN_SECRET` | **yes** | `BrandSafetyServiceTokenProperties.java:35-57` (prefix `influora.brand-safety-service-token`), `application.yml:279` — **this is the one to set.** |
| `BRAND_SAFETY_SCORING_ENABLED` | no | `application.yml:523` (optional) |
| `BRAND_SAFETY_SCORING_MAX_CREATORS_PER_RUN` | no | `application.yml:524` (optional) |

**[REPAIR R1 — do not set `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET`]** This name is
Spring Boot's relaxed-binding form of `influora.brand-safety-service-token.signing-secret` — the
same property `BRAND_SAFETY_SERVICE_TOKEN_SECRET` sets via the `${...}` placeholder at
`application.yml:279` — but it has **no `${}` placeholder anywhere in this repo**; Spring binds it
directly from the OS-environment property source. The original text here said whichever var an
operator exports "wins unpredictably." That was wrong: OS environment variables are a
higher-precedence Spring property source than `application.yml`-derived values, so if both are
exported, `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` **deterministically** wins over
`BRAND_SAFETY_SERVICE_TOKEN_SECRET`'s placeholder-resolved value — not unpredictably. Practical
guidance: export **only** `BRAND_SAFETY_SERVICE_TOKEN_SECRET`; do not also export
`INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` on the live box, since doing so would silently
shadow the intended value with whatever that second var happens to hold (e.g. a stale copy left
in the env file from a previous incident). This pre-existing double-binding path was not
introduced by this lane and fixing it in code is out of this lane's file scope — flagging for
Priya/Vikram as an open item below.

**Meera for Creators**
| Var | Secret? | Source |
|---|---|---|
| `MEERA_CREATOR_ENABLED` | no | `MeeraCreatorFeatureProperties.java:31`, `application.yml:215` (`creator-enabled: ${MEERA_CREATOR_ENABLED:true}`, rollback flag, default `true`) |
| `AI_CREATOR_MONTHLY_CAP_USD` | no | **target file is `/usr/local/App/influora-ai/influora-ai/influora-ai.env`, NOT `influora.env`** — see the note at the top of §2. `influora-ai/app/config.py:455-457`. **[REPAIR R1]** now forwarded via `${AI_CREATOR_MONTHLY_CAP_USD:-0.75}` in both compose files' `influora-ai` blocks (§0.2) — previously a bare `"0.75"` literal in both, which meant this row's instruction to set it had no effect regardless of which env file it landed in. |
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

**[REPAIR R1 — this whole section was wrong]** Three defects fixed here:
1. `scp influora-api/target/influora-api.jar` named a jar the build never produces. Verified fresh
   against this branch's HEAD: `pom.xml:15-16` sets `<artifactId>influora-api</artifactId>` /
   `<version>0.1.0-SNAPSHOT</version>` with **no `<finalName>`**, and running `mvn -o clean package
   -DskipTests` from a `git archive` of this commit (see step 0 below) produced exactly one jar,
   `target/influora-api-0.1.0-SNAPSHOT.jar`, exit code 0. Step 1 below builds and scps that name.
2. Nothing saved the previous jar/image/env file before overwriting them, so §5's rollback had
   nothing to roll back to. Step 0 now backs up all three **before** step 1 touches anything.
3. The jar was built from the local working tree, which at the time of writing has 30+ uncommitted
   modified files and untracked code — not the reviewed commit. Step 1 now builds from a fresh
   `git archive` of the exact SHA that was approved to ship, never from the working tree directly.

```bash
# 0. BACKUP FIRST — before touching anything on the box. Run this before step 1, every deploy.
DEPLOY_SHA=$(git rev-parse HEAD)   # the exact commit Swapnil/Arjun approved to ship — confirm it,
                                    # don't assume HEAD; see §READ THIS FIRST §2 on branch choice
ssh root@150.241.245.242 <<'EOF'
cd /usr/local/App/influora
TS=$(date +%Y%m%d-%H%M%S)
# Tag the running image so §5 has something concrete to roll back to (previously untagged —
# `docker stop && rm` below would have destroyed the only reference to the prior working image).
docker tag influora-api:latest influora-api:pre-0918-$TS 2>/dev/null || true
# Keep the jar that built that image, and a timestamped copy of the env file, matching the
# `*.bak.20260907` convention `live-state.txt:232` already used on this box.
[ -f influora-api.jar ] && cp influora-api.jar influora-api.jar.bak-$TS
cp influora.env influora.env.bak-$TS
echo "Backed up as pre-0918-$TS — record this timestamp for §5."
EOF

# 1. Build from a CLEAN checkout of the approved SHA, not the working tree.
BUILD_DIR=$(mktemp -d)
git archive "$DEPLOY_SHA" | tar -x -C "$BUILD_DIR"
(cd "$BUILD_DIR/influora-api" && mvn -o clean package -DskipTests)
# Produces target/influora-api-0.1.0-SNAPSHOT.jar (pom.xml:15-16, no <finalName> — verified above).
scp "$BUILD_DIR/influora-api/target/influora-api-0.1.0-SNAPSHOT.jar" \
  root@150.241.245.242:/usr/local/App/influora/influora-api.jar

# 2. Rebuild the on-box image and recreate the container (matches live-state.txt:166-170's Dockerfile).
ssh root@150.241.245.242 <<'EOF'
cd /usr/local/App/influora
docker build -t influora-api:latest .
docker stop influora-api && docker rm influora-api
docker run -d --name influora-api --network host --restart unless-stopped \
  --env-file influora.env influora-api:latest
EOF
```
Do the equivalent backup-then-build-then-deploy sequence for `influora-ai` under
`/usr/local/App/influora-ai/` with its own env file
(`/usr/local/App/influora-ai/influora-ai/influora-ai.env`, per `live-state.txt:148-152,228-230`) —
this lane's fix to that image's build source (git archive of the approved SHA, not a local
checkout) applies equally to the Python service; there is no `target/*.jar` step there.

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
# 1. API up. [REPAIR R1] This alone does NOT prove the DB is reachable: HealthController.java:25-31
# returns `Map<String,Object>` from a plain @GetMapping with no ResponseEntity/status logic, so
# Spring answers 200 regardless of the payload's "status" field (DOWN included) — a DB outage
# still curls 200 here. Only check 2 below proves DB health.
curl -s -o /dev/null -w '%{http_code}\n' https://influora.in/api/v1/health
# expect 200 (this only proves the process is up and routing works)

# 2. Full health payload — this is the actual DB-reachability proof.
curl -s https://influora.in/api/v1/health
# expect {"status":"UP","storage":{"r2":"configured"}} — HealthController.java:26-30 delegates to
# HealthEndpoint, which auto-wires a DataSourceHealthIndicator; "status":"DOWN" here means the DB
# is unreachable even though check 1 returned 200.

# 3. workspaces sanity check (brief's requested query)
mysql -u influora_app -p influora -e "SELECT type, COUNT(*) FROM workspaces GROUP BY type;"
# workspaces.type is ENUM('BRAND','AGENCY') NOT NULL DEFAULT 'BRAND' —
# db/migration/V2__core_auth.sql:26. Confirms the DB the freshly-recreated container is pointed
# at is the real, non-empty production database, not a blank one from a bad SPRING_DATASOURCE_URL.
```

**Trend ingest and Creator Co-pilot log checks — read this before waiting on them:**

- **[REPAIR R1 — was stale] Trend ingest now has a real log line.** §0.4's original claim that no
  `@Scheduled` job exists is out of date as of commit `af23eb9` on this branch:
  `TrendPullJob.java:123` runs on `${influora.trend-ingest.pull-cron:0 0 5 * * *}` (UTC).
  ```bash
  docker logs influora-api --since 10m | grep "TrendPullJob"
  # on schedule (05:00 UTC, or TREND_INGEST_PULL_CRON if overridden), expect either:
  # "TrendPullJob: completed run — sourcesOk=N sourcesSkippedNoKey=N fetched=N deduped=N ..."
  #   (TrendPullJob.java:254) or, if no source returned rows, the shorter
  # "TrendPullJob: completed run — no rows fetched from any source (sourcesOk=..." (line 153)
  # if TREND_INGEST_ENABLED is false or unset, expect instead:
  # "TrendPullJob: disabled (influora.trend-ingest.enabled=false), skipping run" (line 127)
  ```
  Same off-schedule-run technique as Creator Co-pilot below applies if same-day confirmation is
  needed before 05:00 UTC — there is no dedicated override cron property for this job in
  `application.yml:554-559`; forcing an early run means editing `TREND_INGEST_PULL_CRON` to a
  near-term cron expression and recreating the container, same mechanism.
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

**[REPAIR R1 — this could not be carried out before]** `influora-api:<previous-sha-or-tag>` was
never actually tagged anywhere in §3.2, and `influora.env.bak-<timestamp>` did not exist until
this rollback section ran *after* §2 had already overwritten `influora.env` — both referenced
artifacts were created too late to use. §3.2 step 0 now creates them **first**, before any
overwrite, every deploy. Use the timestamp step 0 printed:

```bash
ssh root@150.241.245.242 <<'EOF'
cd /usr/local/App/influora
docker stop influora-api && docker rm influora-api
docker run -d --name influora-api --network host --restart unless-stopped \
  --env-file influora.env.bak-<TS-from-§3.2-step-0> influora-api:pre-0918-<TS-from-§3.2-step-0>
EOF
```
If §3.2 step 0 was skipped (should not happen — it is no longer a separate, skippable step, it is
the first thing §3.2 does), there is no rollback target and the only path is rebuilding the
previous commit per §3.2's own steps 0–2 against the last known-good SHA instead.

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
   before it causes a silent secret mismatch. **[REPAIR R1]** confirmed the override is
   deterministic (OS env beats `application.yml`), not "unpredictable" as originally written here
   — the risk is a silent stale-value shadow, not nondeterminism.
4. **[REPAIR R1 — resolved, no longer open]** ~~Trend ingest has no scheduler~~ — `af23eb9`
   (`TrendPullJob.java`) landed this after `ba3fa62` was written; see §0.4 and §4.
5. **Confirm the live box's actual current deploy mechanism** (§3.0) before running any command in
   §3 — the cited evidence is 11 days old.
6. **[REPAIR R1, new]** `live-state.txt` and `meera-answers.md` are untracked in this repo (see the
   caveat under "What this document is NOT") — get their owning tasks to commit them, or this
   runbook's most load-bearing citations stay unverifiable from any commit.
7. **[REPAIR R1, new]** `AI_CREATOR_MONTHLY_CAP_USD`'s Q7-decided design (a value pinned in the
   compose file "so prod never silently runs on the code default") was in tension with making it
   operator-overridable, which §2 already promised. This lane resolved the tension by keeping the
   0.75 default but switching both compose files to `${AI_CREATOR_MONTHLY_CAP_USD:-0.75}` so the
   override path in §2 is real — flagging in case Q7's intent was specifically to forbid runtime
   overrides, which would mean this fix should be reverted and §2's row rewritten instead to say
   "not settable, edit the compose file and redeploy."

---

## REPAIR ROUND 1 — defect-by-defect (commit `ba3fa62` → this revision)

| # | Sev | Finding | Fix |
|---|---|---|---|
| 1 | HIGH | §3.2 scp'd `influora-api/target/influora-api.jar`, which the build never produces | §3.2 step 1 now builds `target/influora-api-0.1.0-SNAPSHOT.jar` (verified via `mvn -o clean package -DskipTests` against `pom.xml:15-16`, exit 0) and scps that name |
| 2 | HIGH | Rollback couldn't run in order — no previous image tag, no env backup until after §2 already edited it, jar/image overwritten first | §3.2 step 0 now tags `influora-api:pre-0918-<TS>`, backs up the jar and `influora.env`, **before** step 1 touches anything; §5 rewritten to use that tag/backup |
| 3 | HIGH | Deploy built the jar from the dirty working tree (30+ modified files, untracked code), not the reviewed commit | §3.2 step 1 now builds from `git archive "$DEPLOY_SHA"` into a fresh temp dir |
| 4 | MEDIUM | `AI_CREATOR_MONTHLY_CAP_USD` listed under `influora.env` (API's file); only `influora-ai` reads it, using a separate env file on the live box | §2 header + row rewritten to name `/usr/local/App/influora-ai/influora-ai/influora-ai.env` explicitly |
| 5 | MEDIUM | `CREATOR_COPILOT_AI_BASE_URL` row didn't mention the prod-profile no-default / `SecretsStartupValidator` fail-closed behavior | Row rewritten citing `application-prod.yml:100`, `SecretsStartupValidator.java:282-353,606-620`, marked REQUIRED/non-loopback |
| 6 | MEDIUM | `live-state.txt` / `meera-answers.md` are untracked, unverifiable from `git archive` of any commit | New caveat section added; flagged as open item 6 |
| 7 | LOW | "expect 200" (check 1) doesn't prove DB health — `HealthController` always returns 200 | §4 check 1 rewritten to say so explicitly; check 2 is the real proof |
| 8 | LOW | `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` row claimed the override "wins unpredictably" | Row rewritten: OS env vars deterministically beat `application.yml`; recommend not setting this name at all |
| 9 | LOW | `AI_CREATOR_MONTHLY_CAP_USD` / `MEERA_CREATOR_ENABLED` hardcoded as literals in one or both compose files, defeating the override instructions in §2/§5 | Both switched to `${VAR:-default}` form in both compose files (same default values); TREND_INGEST_PULL_CRON's now-stale "belt-and-suspenders" comment also corrected in both files |
