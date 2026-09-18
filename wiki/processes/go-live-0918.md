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

**REPAIR ROUND 2 (2026-09-18, this revision):** an independent reviewer failed commit `dac2452`
on 7 more defects (1 HIGH: R1's rollback-ordering fix was only half done — §2 still came before the
backup step, so an operator working top-down would edit `influora.env` before it was backed up, and
the image-tag backup swallowed its own failure; 4 MEDIUM: both compose files forwarded the wrong
brand-safety secret name and left 10 named variables unforwarded despite §2's claim they all came
from the compose keyset, and the `influora-ai` redeploy was one unverified sentence with no AI
round-trip check; 2 LOW: no concrete required values/addresses were given for two hard-fail flags,
and the health-check DOWN explanation ignored two other health indicators this app registers). All
7 are fixed below and in the two compose files, each marked **[REPAIR R2]** at its fix point. See
the bottom "REPAIR ROUND 2 — defect-by-defect" section for the full mapping.

**REPAIR ROUND 3 (2026-09-18, this revision):** an independent reviewer failed commit `a28ae79` on
2 more HIGH and 3 more MEDIUM defects: 1 HIGH the R2 brand-safety-secret fix did not actually close
end to end (`deploy/utho/generate-env.sh:55` still generated the OLD var name, so a freshly
generated env still left `BRAND_SAFETY_SERVICE_TOKEN_SECRET` blank and influora-api still would not
boot outside dev); 1 HIGH the `influora-ai` redeploy step nests instead of replacing on the second
and later deploys; 3 MEDIUM the `influora-ai` backup (§1.5) and rollback (§5) paths disagreed with
each other and with the real on-box layout, the `CREATOR_COPILOT_AI_BASE_URL` guidance told the
operator to overwrite a value the box already runs successfully in prod, and the "every named
variable is a placeholder" clause failed on 3 rows that are intentionally not placeholders but
weren't marked unambiguously as such. All 5 are fixed below, each marked **[REPAIR R3]** at its fix
point. See the bottom "REPAIR ROUND 3 — defect-by-defect" section for the full mapping.

**REPAIR ROUND 4 (2026-09-18, this revision):** an independent reviewer failed commit `39c829b` on
1 HIGH, 3 MEDIUM and 2 LOW defects, plus the "every named variable is a placeholder or explicitly
marked local" clause (2 misses: `ENV_PATH`, `REPLACE_ME`). 1 HIGH the runbook and both Utho compose
files were stale against their own parent commit (`31f2351`), which had already bound
`TREND_INGEST_CLASSIFIER_WORKSPACE_ID`/`_MAX_ROWS_PER_RUN`/`_REGION` in `application.yml` —
`TrendPullJob` fails every headline closed with `reason=classifier_unconfigured` until the first of
those gets a real value, which is a business ruling this lane cannot make, so the runbook now states
it as an explicit go-live blocker rather than omitting it; 3 MEDIUM the §3.2 `influora-ai`
healthcheck-wait loop fell through silently on a container stuck at `starting` instead of failing,
`.proof-os/gates/compose-forwards-what-the-app-binds.sh` (Utho vs Hostinger) had gone red across R2/
R3 without being reported here, and `CREATOR_COPILOT_ENABLED` had no stated required value or
post-recreate check, the same F-0762 failure class already fixed for `TREND_INGEST_ENABLED`; 2 LOW
`ENV_PATH`/`REPLACE_ME` weren't marked as non-Spring-property shell/literal values, and several
`application.yml`/`TrendPullJob.java` line citations had drifted after R3's own additions shifted
line numbers. All are fixed below, each marked **[REPAIR R4]** at its fix point — see the bottom
"REPAIR ROUND 4 — defect-by-defect" section for the full mapping.

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
`deploy/utho/docker-compose.utho-shared.yml`, `deploy/utho/generate-env.sh` (modified this round —
see item 3.5 below), `.proof-os/gates/utho-compose-keysets-match.sh` (read, not modified — no new
allow-list entry was needed), this file. **[REPAIR R4]**
`deploy/hostinger/docker-compose.hostinger.yml` also touched this round, in scope specifically
because `.proof-os/gates/compose-forwards-what-the-app-binds.sh` requires it to stay in parity with
Utho's forwarded keyset (see §0 item 8) — no other Hostinger-specific change was made.

1. **`TREND_INGEST_PULL_CRON` was unforwarded in both compose files** (F-0787-class gap — an
   explicit-map `environment:` block silently drops any key not listed). `application.yml:574`
   (**[REPAIR R4] renumbered from :559 — R3's classifier-workspace-id/max-rows/region additions
   shifted every line below them; re-cited throughout §0/§2/§4 this round**) reads
   `${TREND_INGEST_PULL_CRON:0 0 5 * * *}`; `TrendIngestProperties.java:33-35` documents the
   same. Added to both files' `influora-api` block, mirroring the code default as the compose-side
   default (`${TREND_INGEST_PULL_CRON:-0 0 5 * * *}`) rather than an empty default, because an
   empty-but-present value is a known foot-gun in this codebase (see the `META_CREATOR_MARKETPLACE_ENABLED`
   comment already in both files, e.g. `docker-compose.utho.yml:259-263`).
   **[REPAIR R1 — was wrong]** The original commit claimed `TrendIngestProperties.setPullCron`
   (`TrendIngestProperties.java:108-110`) also guards a blank value, making this
   "belt-and-suspenders, not load-bearing." That guard only runs for the
   `@ConfigurationProperties`-bound bean. `TrendPullJob.java:166` (renumbered from :123, same
   reason) — landed in `af23eb9`, *after*
   `ba3fa62` was cut, on this same branch — schedules itself with its own
   `@Scheduled(cron = "${influora.trend-ingest.pull-cron:0 0 5 * * *}")`, which resolves the
   `influora.trend-ingest.pull-cron` property straight from the Spring `Environment` and never
   touches `setPullCron`. Spring's `${key:default}` only substitutes the default when `key` is
   **absent**, not when it resolves to `""` — so a present-but-blank `TREND_INGEST_PULL_CRON` would
   make `@Scheduled` receive `cron=""` and fail to parse at boot. This compose default is now
   **load-bearing**; the comment in both compose files has been corrected (see the compose diffs).
2. **[REPAIR R2] Both compose files forwarded the wrong brand-safety secret name, and 10 named
   variables were unforwarded despite §2 claiming otherwise.** `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET`
   (Spring's relaxed-binding alias — no `${}` placeholder anywhere in `application.yml`) was forwarded
   in both files; `BRAND_SAFETY_SERVICE_TOKEN_SECRET` (the name `application.yml:279` actually binds
   via `${...}`) was not. `docker compose config` on the pre-fix files confirmed: *"BRAND_SAFETY_SERVICE_TOKEN_SECRET
   variable is not set. Defaulting to a blank string"* was the message for the WRONG var — the real
   one was silently absent — and an unset `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` in the
   `environment:` map still creates a blank container env key, which an OS-environment source
   deterministically outranks over an `application.yml` placeholder-resolved value. Net effect: a
   compose deploy following this runbook's §2 instruction ("set `BRAND_SAFETY_SERVICE_TOKEN_SECRET`")
   would have shadowed that value with `""`. Fixed in both files to forward the correct name
   (`BrandSafetyServiceTokenProperties.java:42-56`, `application.yml:279`). Separately, 9 more
   variables named in §2's tables (`CREATOR_COPILOT_SCORE_THRESHOLD`, `_DAILY_CAP`,
   `_PROMPT_VERSION`, `_CAPTION_SYNC_MEDIA_LIMIT`, `_CAPTION_SYNC_MAX_CREATORS_PER_RUN`,
   `_CAPTION_SYNC_CRON`, `_THEME_TAG_CRON`, `BRAND_SAFETY_SCORING_ENABLED`,
   `BRAND_SAFETY_SCORING_MAX_CREATORS_PER_RUN`) were never in either compose file at all, making
   §2's old claim ("this list is the `influora-api` environment keyset from `docker-compose.utho.yml`")
   false for those rows. Added to both files with defaults mirroring `application.yml:522-524,541-549`
   exactly, so the compose-based path can override every var §2 names, same as today's live
   `--env-file` path already could.
3. **`AI_CREATOR_MONTHLY_CAP_USD` was present in `docker-compose.utho.yml`'s `influora-ai` block
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
3.5. **[REPAIR R3 — HIGH] `deploy/utho/generate-env.sh` still generated the OLD brand-safety var
   name, breaking the R2 compose fix end to end.** R2 (item 2 above) fixed both compose files to
   forward `BRAND_SAFETY_SERVICE_TOKEN_SECRET`, but `generate-env.sh:55` still wrote
   `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET=$(secret)` — the old relaxed-binding name, which
   R2 itself documented as having no `${}` placeholder anywhere. **Reproduced (red):** ran
   `bash generate-env.sh /tmp/gen.env` at commit `a28ae79`, then
   `docker compose --env-file /tmp/gen.env -f docker-compose.utho.yml config` — printed
   `The "BRAND_SAFETY_SERVICE_TOKEN_SECRET" variable is not set. Defaulting to a blank string.`; a
   compose-based deploy on that env would boot with a blank brand-safety secret and fail
   `SecretsStartupValidator` outside dev. Separately, running `generate-env.sh` at `a28ae79`
   unmodified fails outright — `bash generate-env.sh /tmp/gen.env` exits 1 with `line 31: app.:
   command not found` and `line 31: VAR: unbound variable` and writes a 0-byte file. Two pre-existing
   comments inside the unquoted `cat > "$ENV_PATH" <<ENVEOF` heredoc (`ENV_PATH` **[REPAIR R4] is a
   `generate-env.sh` shell-local variable** — set from `$1` at that script's own line 14, not a
   Spring/`application.yml` property, and has no `${}` placeholder to check for that reason; line 31
   has no quotes around
   `ENVEOF`, so every line — comments included — undergoes shell expansion) contained live shell
   syntax: backticks around `` `app.` `` (line 37, parsed as command substitution — runs `app.` as a
   command) and a bare `${VAR:default}` (line 91, a substring expansion on an unset `VAR`, which
   errors under this script's own `set -euo pipefail`). Fixed by (a) renaming line 55's key to
   `BRAND_SAFETY_SERVICE_TOKEN_SECRET`, (b) replacing the backticks at line 37 with plain quotes, and
   (c) rewriting line 91's prose to describe the placeholder syntax without using live `${...}`
   inside the heredoc. **Green:** `bash generate-env.sh /tmp/gen4.env` now exits 0, writes a
   complete 162-line file (the script's own summary line reads `still REPLACE_ME: 31` — **[REPAIR
   R4] `REPLACE_ME` here is the literal placeholder VALUE the script's closing `grep -c` counts
   (`generate-env.sh:198`), not a variable name; it is not a Spring property and has no `${}`
   placeholder to check for that reason**, all 7 AES keys report 32 bytes), and
   `docker compose --env-file /tmp/gen4.env -f docker-compose.utho.yml config` (and the
   `-shared` file) resolve `BRAND_SAFETY_SERVICE_TOKEN_SECRET` to the generated 48-char secret with
   no "not set" warning on either file. `bash .proof-os/gates/utho-compose-keysets-match.sh` still
   exits 0 (96/97 keys, unchanged — this fix touches generate-env.sh only, not either compose file's
   keyset).
4. **`MEERA_CREATOR_SEND_ENABLED`, named in the brief, does not exist in this repo.** Confirmed by
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
5. **[REPAIR R1 — this whole item was stale]** Trend ingest **is now wired**. At the time `ba3fa62`
   was written, `grep -rn "NewsApiTopHeadlinesClient|TmdbUpcomingClient|YouTubeMostPopularClient"`
   found only the three client classes with no `@Scheduled` consumer. Commit `af23eb9`
   ("feat(trendspark): L10 trend-pull job"), on this same branch, added
   `influora-api/src/main/java/com/influora/job/TrendPullJob.java` — `@Scheduled(cron =
   "${influora.trend-ingest.pull-cron:0 0 5 * * *}", zone = "UTC")` at line 123, gated by
   `props.isEnabled()` (line 126). This changes §4's post-deploy checks below: there is now a real
   log line to check for a trend pull.
6. **Gate re-run against the working tree, after all compose fixes above (original two gaps, R1's
   literal→placeholder fixes, R2's brand-safety-var-name fix plus 9 new forwards, and R4's 3 new
   trend-classifier forwards — see item 8):**
   ```
   $ bash .proof-os/gates/utho-compose-keysets-match.sh
   allowed: JAVA_TOOL_OPTIONS only in deploy/utho/docker-compose.utho-shared.yml
   checked: influora-api environment keys — 99 in deploy/utho/docker-compose.utho.yml, 100 in deploy/utho/docker-compose.utho-shared.yml
   NOT CHECKED: key VALUES/defaults; other services (influora-ai, caddy, mysql); the live box's env file (/usr/local/App/influora/influora.env, not in this repo — F-0842)
   PASS: key sets match (modulo allow-list)
   ```
   (99 vs 100 is expected and allow-listed, same as before: `JAVA_TOOL_OPTIONS` — **[REPAIR R3]
   intentionally NOT an `application.yml` `${VAR}` placeholder; it is a JVM launcher flag the `java`
   process reads directly from its OS environment, hardcoded as a literal in
   `docker-compose.utho-shared.yml:149` (`-Xmx900m -XX:MaxMetaspaceSize=256m`), by design not
   operator-overridable per deploy** — only belongs in the
   shared-box file, per the existing `ALLOW="JAVA_TOOL_OPTIONS"` entry, `utho-compose-keysets-match.sh:18`
   — no new allow-list entry was needed. The count rose from 87/88 (pre-R2) to 96/97 (R3) to 99/100
   (this revision): −1 for the renamed brand-safety key (same slot, different name), +10 for the
   Creator Co-pilot/brand-safety-scoring tunables (R2/R3), +3 for the trend-classifier tunables added
   this round (item 8). Mutation-tested: deleting `CREATOR_COPILOT_THEME_TAG_CRON` from one archived
   file gives `KEY MISSING FROM deploy/utho/docker-compose.utho.yml: CREATOR_COPILOT_THEME_TAG_CRON
   (present in deploy/utho/docker-compose.utho-shared.yml)`, exit 1; restoring it returns to
   `PASS`, exit 0 — re-run against a `git archive` of this revision's final commit, not just the
   working tree.)
7. **YAML/interpolation validated locally**, on the REPAIR R2 revision: `yaml.safe_load(...)`
   parsed both files without error, and `docker compose -f docker-compose.utho.yml config` /
   `docker compose -f docker-compose.utho-shared.yml config` (run from `deploy/utho/`, exit code 0
   both times) resolved `TREND_INGEST_PULL_CRON` to the literal `0 0 5 * * *`,
   `AI_CREATOR_MONTHLY_CAP_USD` to `"0.75"`, `MEERA_CREATOR_ENABLED` to `"true"`,
   `CREATOR_COPILOT_SCORE_THRESHOLD` to `"2"`, `CREATOR_COPILOT_THEME_TAG_CRON` to `0 0 3 * * *`,
   `BRAND_SAFETY_SCORING_ENABLED` to `"false"`, and `BRAND_SAFETY_SCORING_MAX_CREATORS_PER_RUN` to
   `"100"` in both files with no host env set. `BRAND_SAFETY_SERVICE_TOKEN_SECRET` now shows
   Compose's own *"variable is not set. Defaulting to a blank string"* warning (correct — it is a
   secret with no default, same convention as `JWT_ACCESS_SECRET` on the line above it), and
   `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` no longer appears in `docker compose config`'s
   output for either file at all. This confirms the new `${VAR:-default}` forms interpolate to the
   same defaults the old literals did, that an operator-supplied override would now actually reach
   the container for every var named in §2, and that the brand-safety secret fix actually took
   (the wrong key is gone, the right one is present). This is config validation, not a database/
   persistence test — rule 5 does not apply to YAML.
8. **[REPAIR R4 — MEDIUM] `.proof-os/gates/compose-forwards-what-the-app-binds.sh` (Utho vs
   Hostinger, not the Utho-vs-Utho-shared gate in item 6 above) went red across R2/R3 and was never
   reported here.** This gate compares `deploy/hostinger/docker-compose.hostinger.yml` against
   `deploy/utho/docker-compose.utho.yml` — Utho is the reference (it is the live production
   surface), so anything Utho forwards that Hostinger does not is a defect. **Red, before this
   round's fix:**
   ```
   $ bash .proof-os/gates/compose-forwards-what-the-app-binds.sh
   BROKEN: docker-compose.hostinger.yml does not forward 13 var(s) that Utho forwards and
           application.yml binds. ...
             BRAND_SAFETY_SCORING_ENABLED
             BRAND_SAFETY_SCORING_MAX_CREATORS_PER_RUN
             BRAND_SAFETY_SERVICE_TOKEN_SECRET
             CREATOR_COPILOT_CAPTION_SYNC_CRON
             CREATOR_COPILOT_CAPTION_SYNC_MAX_CREATORS_PER_RUN
             CREATOR_COPILOT_CAPTION_SYNC_MEDIA_LIMIT
             CREATOR_COPILOT_DAILY_CAP
             CREATOR_COPILOT_PROMPT_VERSION
             CREATOR_COPILOT_SCORE_THRESHOLD
             CREATOR_COPILOT_THEME_TAG_CRON
             TREND_INGEST_MAX_ROWS_PER_RUN
             TREND_INGEST_PULL_CRON
             TREND_INGEST_REGION
   ```
   `BRAND_SAFETY_SERVICE_TOKEN_SECRET` is exactly the fail-closed secret family this gate's own
   header comment (`compose-forwards-what-the-app-binds.sh:5-14`) warns about — a stack missing it
   fails `SecretsStartupValidator` outside dev and does not boot at all, the same class of defect as
   the `META_TOKEN_ENCRYPTION_KEY` incident the gate was written for. Hostinger's copy also still
   forwarded the do-not-set relaxed-binding alias
   `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` instead. Both are this lane's files
   (`deploy/hostinger/*.yml`, in scope here specifically because this gate requires Utho/Hostinger
   parity), so fixed directly rather than left as a gap: the alias line was replaced with
   `BRAND_SAFETY_SERVICE_TOKEN_SECRET: ${BRAND_SAFETY_SERVICE_TOKEN_SECRET}`, and all 13 missing
   vars above (plus `TREND_INGEST_CLASSIFIER_WORKSPACE_ID`, added to all three compose files this
   round — item 8's own change, see §2) were added to `docker-compose.hostinger.yml` with defaults
   mirroring Utho's. **Green, this revision:**
   ```
   $ bash .proof-os/gates/compose-forwards-what-the-app-binds.sh
   PROVED: both deploy compose files forward the same 90 application-bound env vars
           to influora-api; nothing Utho passes is dropped on Hostinger.
   ```
   Re-run against a `git archive` of this revision's final commit, not just the working tree. This
   gate is outside `utho-compose-keysets-match.sh`'s scope (that one only compares Utho's two files
   to each other, never to Hostinger, and this lane does not own `docker-compose.hostinger.yml`
   beyond what parity with this gate requires) — flagging both gates by name here going forward so
   neither goes red silently again.

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

## §1.5 — Back up the running image, jar and env file (run this BEFORE §2, every deploy)

**[REPAIR R2 — HIGH, moved here from §3.2]** R1 put this backup at the start of §3.2 ("step 0"),
*after* §2 ("Environment variables to set"). An operator reading top-down does §1, then §2 (which
means editing `/usr/local/App/influora/influora.env` in place), and only then reaches §3.2's
`cp influora.env influora.env.bak-$TS` — which now backs up the **already-edited** file. §5's
rollback would then restore the new env, not the old one, defeating the point of a rollback. This
step now runs before any edit to `influora.env`, so its backup is always the pre-change file. §3.2
step 0 below is now just a pointer back here, not a second copy of this block.

**[REPAIR R2 — HIGH, tag step now fails loudly]** R1's tag command was
`docker tag influora-api:latest influora-api:pre-0918-$TS 2>/dev/null || true` — a failed tag (no
`influora-api:latest` present, e.g. first deploy after a manual `docker rmi`) was silently
swallowed, and `§5` would then discover mid-incident that no rollback image exists. The heredoc
below runs with `set -e` and checks the tag's own exit status explicitly, so a failure aborts the
whole deploy (nothing in §2/§3 has touched anything yet at this point) with a clear message instead
of proceeding into a deploy with no rollback path.

**[REPAIR R3 — MEDIUM] The `influora-ai` half of this step was prose ("do the equivalent"), not a
runnable script, and it disagreed with §5's rollback on which directory the env-file backup lands
in.** `live-state.txt:148-152` shows `/usr/local/App/influora-ai/` contains a subdirectory
`influora-ai/` (the actual app + env file, confirmed at `live-state.txt:228`:
`/usr/local/App/influora-ai/influora-ai/influora-ai.env`) plus an unrelated `influora-ai.zip` — one
directory level deeper than `influora-api`'s own layout (`/usr/local/App/influora/influora.env`,
flat). "Do the equivalent under `/usr/local/App/influora-ai/`" reads as instructing the SAME flat
`cd`, which would `cp` a file that is not there. §5's rollback (previous revision) compounded this:
it `cd /usr/local/App/influora-ai` then referenced `influora-ai.env.bak-<TS>` as a **relative**
path, which resolves one directory too shallow regardless of where the backup actually landed.
Fixed by writing out the `influora-ai` backup as its own concrete heredoc below, `cd`-ing into the
same directory the env file actually lives in (`/usr/local/App/influora-ai/influora-ai`), and by
making §5's rollback `cd` into that same directory (see §5).

**[REPAIR R3 — same fix, timestamp sharing]** Running each service's backup in its own `ssh ...
<<'EOF' ... TS=$(date ...) ... EOF` (as the previous revision did for `influora-api` and instructed
"same script shape" for `influora-ai`) gives the two services **different** timestamps, one per SSH
round-trip — but §3.2 and §5 both read a single `<TS-from-§1.5>` as if one timestamp covered both
services. Fixed by generating `TS` once, locally, before either `ssh` call, and forwarding it into
each remote script's environment (`ssh ... "TS=$TS bash -s"` — the local `$TS` is substituted into
the double-quoted remote command *before* it is sent over SSH, so the remote shell reading the
heredoc from stdin already has `TS` set; this is different from the unquoted-heredoc pitfall in
`generate-env.sh:31`, item 3.5 above — here `$TS` is deliberately meant to expand, on the operator's
own machine, exactly once).

```bash
# One timestamp, generated locally, shared by BOTH services' backup, §3.2 and §5.
TS=$(date +%Y%m%d-%H%M%S)
echo "Deploy timestamp: $TS — record this now, §3.2 and §5 both need it."

ssh root@150.241.245.242 "TS=$TS bash -s" <<'EOF'
set -e
cd /usr/local/App/influora
# Tag the running image so §5 has something concrete to roll back to. Fails LOUDLY now (no
# `2>/dev/null || true`) — if influora-api:latest does not exist, STOP: find out why before
# deploying, don't proceed into a deploy with no rollback target.
if ! docker tag influora-api:latest "influora-api:pre-0918-$TS"; then
  echo "FATAL: could not tag influora-api:latest — does the running container's image still" >&2
  echo "exist? Aborting before touching influora.env or the jar. Investigate before retrying." >&2
  exit 1
fi
# Keep the jar that built that image, and a timestamped copy of the env file, matching the
# `*.bak.20260907` convention `live-state.txt:232` already used on this box. This MUST happen
# before §2 edits influora.env — that ordering is the point of this section existing separately.
[ -f influora-api.jar ] && cp influora-api.jar "influora-api.jar.bak-$TS"
cp influora.env "influora.env.bak-$TS"
echo "influora-api backed up as pre-0918-$TS"
EOF

# [REPAIR R3] influora-ai's env file and image are one directory level deeper than influora-api's
# (live-state.txt:148-152,228) — cd into the directory the file actually lives in, not its parent.
ssh root@150.241.245.242 "TS=$TS bash -s" <<'EOF'
set -e
cd /usr/local/App/influora-ai/influora-ai
if ! docker tag influora-ai:latest "influora-ai:pre-0918-$TS"; then
  echo "FATAL: could not tag influora-ai:latest — does the running container's image still" >&2
  echo "exist? Aborting before touching influora-ai.env. Investigate before retrying." >&2
  exit 1
fi
cp influora-ai.env "influora-ai.env.bak-$TS"
echo "influora-ai backed up as pre-0918-$TS"
EOF
```

**Record `$TS` somewhere durable (not just your terminal scrollback)** before proceeding to §2 —
§3.2 and §5 both need it and there is no other record of it once this session ends.

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

**[REPAIR R2 — MEDIUM, this claim was false for 10 rows]** R1 said this list "is" the
`docker-compose.utho.yml` keyset. It was not: `CREATOR_COPILOT_SCORE_THRESHOLD`, `_DAILY_CAP`,
`_PROMPT_VERSION`, `_CAPTION_SYNC_MEDIA_LIMIT`, `_CAPTION_SYNC_MAX_CREATORS_PER_RUN`,
`_CAPTION_SYNC_CRON`, `_THEME_TAG_CRON`, `BRAND_SAFETY_SCORING_ENABLED`,
`BRAND_SAFETY_SCORING_MAX_CREATORS_PER_RUN` and `BRAND_SAFETY_SERVICE_TOKEN_SECRET` were named in
the tables below but absent from both compose files (confirmed by `awk`-extracting each file's
`influora-api.environment` keyset and diffing against this table). §0.2 (this revision) added all
10 to both files, so the claim is now true: this **is** the `influora-api` environment keyset from
`docker-compose.utho.yml` (96 keys after §0's fixes), which remains the closest in-repo source of
truth for "every var this release needs" even though the live box is not actually driven by that
compose file today (§READ THIS FIRST). On the live `--env-file` path (today's actual mechanism)
every row below already bound correctly regardless of this gap — this only mattered for the
future compose-based migration path, but it is now closed rather than left as a discrepancy for
whoever runs that migration to rediscover. Names only — **no values are given here**, per hard
rule 8 (no secrets in commits/docs); a "(optional, has default)" row is safe to leave unset either
way and the code default cited applies.

Grouped by today's release features, each cited to its file:line origin:

**Trend ingest (T4 ingest job — see §0.5 and §0.8)**

**[REPAIR R4 — HIGH] `TREND_INGEST_ENABLED=true` plus the 3 API keys alone does NOT start filling
trends — this is a go-live blocker still awaiting a Swapnil/Priya ruling, not a documentation gap.**
`TrendPullJob.java:241` (`if (!props.hasClassifierWorkspaceId())`) fails every headline closed with
`reason=classifier_unconfigured` unless `influora.trend-ingest.classifier-workspace-id`
(`TREND_INGEST_CLASSIFIER_WORKSPACE_ID` below) is set to a real workspace id — and this lane is NOT
authorized to pick one (hard rule 8): `BrandSafetyAiClient#classify` bills a specific workspace's AI
credits per call, and there is no platform/system workspace concept anywhere in this codebase
(verified: grepping the whole tree for a `SYSTEM_WORKSPACE` or `PLATFORM_WORKSPACE` constant — not
an env var and not a Spring property, just naming what does not exist — returns 0 hits). **If
today's release is meant to
actually produce trend rows** (not just exercise the job harmlessly), get that ruling from
Swapnil/Priya before go-live — otherwise every run will "complete" (§4) while writing 0 rows,
which looks like success unless you specifically check for `classifier_unconfigured` in the logs.

| Var | Secret? | Source |
|---|---|---|
| `TREND_INGEST_ENABLED` | no | `application.yml:570` (`${TREND_INGEST_ENABLED:false}` — **defaults OFF**; **[REPAIR R2] must be explicitly set to `true`** in `influora.env` if today's release is meant to turn trend pull on, or `TrendPullJob.java:170`'s `props.isEnabled()` gate keeps it a no-op forever, silently, with only the disabled-log-line in §4 to notice by) |
| `NEWSAPI_KEY` | **yes** | `application.yml:571` |
| `TMDB_API_KEY` | **yes** | `application.yml:572` |
| `YOUTUBE_API_KEY` | **yes** | `application.yml:573` |
| `TREND_INGEST_PULL_CRON` | no | `application.yml:574` (forwarded — §0.1) |
| `TREND_INGEST_MAX_ROWS_PER_RUN` | no | `application.yml:575` (optional, has default) — **[REPAIR R4]** newly forwarded, §0.8 |
| `TREND_INGEST_REGION` | no | `application.yml:576` (optional, has default) — **[REPAIR R4]** newly forwarded, §0.8 |
| `TREND_INGEST_CLASSIFIER_WORKSPACE_ID` | no (a workspace id, not a fresh secret) | `application.yml:577` — **[REPAIR R4] BLOCKER, see the callout above.** Blank by default; forwarded (§0.8) only so it is operator-settable once the ruling lands, not because a value is decided |

**Creator AI Co-pilot**
| Var | Secret? | Source |
|---|---|---|
| `CREATOR_COPILOT_ENABLED` | no | `application.yml:542` (`${CREATOR_COPILOT_ENABLED:false}` — **defaults OFF, same as `TREND_INGEST_ENABLED` above. [REPAIR R4] must be explicitly set to `true`** in `influora.env` if today's release includes turning the Co-pilot on — `generate-env.sh` never writes this var (it only generates secrets/keys, not feature flags), and neither compose file's `:-false` default will turn it on for you. This is the exact F-0762 failure class recorded in `live-state.txt`'s EIGHTH CAPTURE: on a fresh box or any env rebuilt from `generate-env.sh`, the Co-pilot comes up silently off, and nothing catches it until the 02:00 UTC job's disabled-log-line (§4) or an operator report. **Immediately after §3.2's `docker run` recreates the container**, before waiting for the 02:00 UTC job, run `docker exec influora-api env \| grep -E 'CREATOR_COPILOT_ENABLED\|TREND_INGEST_ENABLED'` and confirm both read `true` if that is what this release intends — do not wait for §4's log-line check to discover a flag was left at its default |
| `CREATOR_COPILOT_SCORE_THRESHOLD` | no | `application.yml:543` (optional, has default) |
| `CREATOR_COPILOT_DAILY_CAP` | no | `application.yml:544` (optional) |
| `CREATOR_COPILOT_PROMPT_VERSION` | no | `application.yml:545` (optional) |
| `CREATOR_COPILOT_CAPTION_SYNC_MEDIA_LIMIT` | no | `application.yml:546` (optional) |
| `CREATOR_COPILOT_CAPTION_SYNC_MAX_CREATORS_PER_RUN` | no | `application.yml:547` (optional) |
| `CREATOR_COPILOT_CAPTION_SYNC_CRON` | no | `application.yml:548` (optional) |
| `CREATOR_COPILOT_THEME_TAG_CRON` | no | `application.yml:549` (optional) |
| `CREATOR_COPILOT_AI_BASE_URL` | no (internal DNS/URL), **REQUIRED, non-loopback** | dev default `http://localhost:8000` at `application.yml:257`, but under the prod profile `application-prod.yml:100` overrides it with **no default** (`base-url: ${CREATOR_COPILOT_AI_BASE_URL}`) — Spring throws `PlaceholderResolutionException` at boot if it is unset at all (`application-prod.yml:55-77` spells this out as deliberate, "*** CRITICAL DEPLOY SEQUENCING ***"). Even if set, `SecretsStartupValidator.validateAiServiceUrls` (`SecretsStartupValidator.java:606-620`, calling `checkNotUnroutableHost` at line 703) rejects a loopback/localhost value outside dev — the check is a literal string match on host `localhost`, `127.0.0.1` or `::1` (`SecretsStartupValidator.java:720`) — and `SecretsStartupValidator.java:282-353` throws `IllegalStateException` (not just a warning) for any non-dev environment. Compose hardcodes `http://influora-ai:8000` in both files (`docker-compose.utho.yml:176`, `docker-compose.utho-shared.yml:204`) — that Docker-DNS name only resolves inside a shared Compose network, which the live box does not have (§READ THIS FIRST §1: both containers run `--network host`, not compose). **[REPAIR R3 — MEDIUM, corrects R2] Do NOT blindly set this to a fresh value on a redeploy.** R2 told the operator to unconditionally set this to `http://150.241.245.242:8000`, but `live-state.txt`'s EIGHTH CAPTURE (2026-09-08) shows Creator Co-pilot is **already enabled and running successfully in prod** (`CreatorCaptionSyncJob: completed run — 1 creators processed, 25 captions inserted, 0 skipped`) — since `application-prod.yml` has no default for this var, the box's `influora.env` must already hold a value that works, and this section overwriting it with an unverified new value risks *breaking* a working integration, not fixing a missing one, for no documented reason. **Before touching this var:** (1) if this is a redeploy of an already-running box, first read the CURRENT value (`ssh ... grep CREATOR_COPILOT_AI_BASE_URL /usr/local/App/influora/influora.env`, or `docker exec influora-api env | grep CREATOR_COPILOT_AI_BASE_URL` while the old container is still up) and leave it alone unless §4 check 4 (the `creator_nudge_log.message_source` query) or an operator report shows it is broken; (2) only if this is a FRESH box with no prior value, or the existing value is confirmed broken, set a concrete one. For that fresh-box case: with both containers on `--network host`, each binds directly to the box's own network interfaces, so `influora-ai`'s port 8000 is reachable from `influora-api` at the box's own routable address, not a Docker-internal name. `live-state.txt:33` confirms uvicorn is bound to `0.0.0.0:8000` (not `127.0.0.1:8000`) and `live-state.txt:129` confirms port 8000 is blocked/filtered from the public internet by the box's firewall already, so `http://150.241.245.242:8000` is both reachable from the box itself and not publicly exposed — but re-verify both facts against the live box before relying on them, since `live-state.txt` is 11 days stale (`docker exec influora-ai ss -tlnp` for the bind, the firewall's current rules for the filter). Never use `http://localhost:8000` or `http://127.0.0.1:8000` — both are rejected outside dev by `checkNotUnroutableHost`. Note `application-prod.yml:74-76`'s own comment claiming this var is "missing" from `docker-compose.utho-shared.yml` is stale — it has been present since before this lane's changes (`docker-compose.utho-shared.yml:204`); that comment lives outside this lane's file scope to correct. |

**Brand-safety client (Spring → influora-ai)**
| Var | Secret? | Source |
|---|---|---|
| `BRAND_SAFETY_AI_BASE_URL` | no | `BrandSafetyAiProperties.java:19-32` (prefix `influora.brand-safety-ai`), `application.yml:243` |
| `BRAND_SAFETY_SERVICE_TOKEN_SECRET` | **yes** | `BrandSafetyServiceTokenProperties.java:35-57` (prefix `influora.brand-safety-service-token`), `application.yml:279` — **this is the one to set.** |
| `BRAND_SAFETY_SCORING_ENABLED` | no | `application.yml:523` (optional) |
| `BRAND_SAFETY_SCORING_MAX_CREATORS_PER_RUN` | no | `application.yml:524` (optional) |

**[REPAIR R1 — do not set `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET`; REPAIR R2 — the compose
files themselves were the actual bug; REPAIR R3 — marked explicitly below]**
`INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` **is intentionally NOT a `${VAR}` placeholder
anywhere in this repo — do not set it.** It is named in this document only to tell the operator not
to export it (see below), not as a variable this release needs configured. This name is Spring
Boot's relaxed-binding form of
`influora.brand-safety-service-token.signing-secret` — the same property
`BRAND_SAFETY_SERVICE_TOKEN_SECRET` sets via the `${...}` placeholder at `application.yml:279` —
but it has **no `${}` placeholder anywhere in this repo**; Spring binds it directly from the
OS-environment property source. OS environment variables are a higher-precedence Spring property
source than `application.yml`-derived values, so if both are exported,
`INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` **deterministically** wins over
`BRAND_SAFETY_SERVICE_TOKEN_SECRET`'s placeholder-resolved value — not unpredictably.

**This was not just a live-box operator risk — both compose files had it backwards.** R1's fix
here only warned an operator not to export the wrong name on `influora.env`; it missed that
`docker-compose.utho.yml:155` and `docker-compose.utho-shared.yml:181` both hardcoded
`INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET: ${INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET}`
in their `environment:` maps and forwarded `BRAND_SAFETY_SERVICE_TOKEN_SECRET` **nowhere at all**.
An explicit `environment:` entry creates the container env key even when the host var backing it
is unset (Compose defaults it to `""` and warns), so on a compose-based deploy that key would
always be present-but-blank, deterministically shadowing whatever `BRAND_SAFETY_SERVICE_TOKEN_SECRET`
resolved to. Fixed in both compose files this revision (§0.2): they now forward
`BRAND_SAFETY_SERVICE_TOKEN_SECRET` and no longer mention the relaxed-binding name at all. Practical
guidance for `influora.env` on today's live (non-compose) path is unchanged: export **only**
`BRAND_SAFETY_SERVICE_TOKEN_SECRET`; do not also export
`INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET`, since doing so would silently shadow the intended
value with whatever that second var happens to hold (e.g. a stale copy left in the env file from a
previous incident). Spring's dual-binding of one secret under two independent env var names is a
pre-existing code shape, not introduced by this lane, and fixing that in `application.yml`/
`BrandSafetyServiceTokenProperties.java` is out of this lane's file scope — flagging for
Priya/Vikram as an open item below.

**Meera for Creators**
| Var | Secret? | Source |
|---|---|---|
| `MEERA_CREATOR_ENABLED` | no | `MeeraCreatorFeatureProperties.java:31`, `application.yml:215` (`creator-enabled: ${MEERA_CREATOR_ENABLED:true}`, rollback flag, default `true`) |
| `AI_CREATOR_MONTHLY_CAP_USD` | no | **target file is `/usr/local/App/influora-ai/influora-ai/influora-ai.env`, NOT `influora.env`** — see the note at the top of §2. `influora-ai/app/config.py:455-457`. **[REPAIR R1]** now forwarded via `${AI_CREATOR_MONTHLY_CAP_USD:-0.75}` in both compose files' `influora-ai` blocks (§0.3) — previously a bare `"0.75"` literal in both, which meant this row's instruction to set it had no effect regardless of which env file it landed in. |
| `MEERA_CREATOR_SEND_ENABLED` | — | **[REPAIR R3] Intentionally NOT a `${VAR}` placeholder in this repo — not added, does not exist in this repo's code today.** See §0.4. Do not set this on the live box unless B0's `send_routine_reply` feature is confirmed part of today's release. |

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

**[REPAIR R1 — this whole section was wrong; REPAIR R2 — step 0 de-duplicated, influora-ai steps made concrete]**

1. `scp influora-api/target/influora-api.jar` named a jar the build never produces. Verified fresh
   against this branch's HEAD: `pom.xml:15-16` sets `<artifactId>influora-api</artifactId>` /
   `<version>0.1.0-SNAPSHOT</version>` with **no `<finalName>`**, and running `mvn -o clean package
   -DskipTests` from a `git archive` of this commit produced exactly one jar,
   `target/influora-api-0.1.0-SNAPSHOT.jar`, exit code 0. Step 1 below builds and scps that name.
2. **[REPAIR R2]** The backup step now lives in **§1.5**, run before §2 edits `influora.env` — not
   here. §3.2 no longer duplicates it; it only reminds you it must already be done.
3. The jar was built from the local working tree, which at the time of writing has 30+ uncommitted
   modified files and untracked code — not the reviewed commit. Step 1 now builds from a fresh
   `git archive` of the exact SHA that was approved to ship, never from the working tree directly.
4. **[REPAIR R2]** `influora-ai`'s redeploy was one unverified sentence ("do the equivalent") with
   no build source, Dockerfile, or run flags. This release touches `influora-ai` code
   (`af23eb9` → `app/prompt/creator_suggestion.py`, plus the Meera commits on this branch) — if it
   is skipped or recreated wrong, the Co-pilot silently keeps serving the OLD prompt (or
   `CreatorNudgeService.callAiSafely` quietly falls back to template copy, which looks identical to
   a working deploy from the outside). Step 3 below is now a full build/deploy sequence, matching
   step 1/2's `git archive`-from-approved-SHA discipline, cited to `influora-ai/Dockerfile`.
5. **[REPAIR R3 — HIGH]** Step 3's `scp -r ... influora-ai-src` only replaces the source directory
   on the FIRST deploy. `scp -r localdir user@host:remotedir` copies `localdir` **into** `remotedir`
   when `remotedir` already exists, instead of replacing its contents — reproduced locally: two
   successive `cp -r` runs (same semantics as `scp -r` for an existing destination) into one target
   directory left `influora-ai-src/Dockerfile` holding the FIRST build's content and
   `influora-ai-src/influora-ai/Dockerfile` (one level nested) holding the SECOND build's content.
   `docker build .` run from `influora-ai-src` (as step 3's own next command does) therefore rebuilds
   the STALE first copy forever after the first deploy, and the healthcheck reports healthy either
   way — this is exactly the failure mode this section exists to prevent for the Co-pilot prompt.
   Fixed by `rm -rf`-ing the remote target directory before every `scp -r`, chosen over `rsync
   --delete` so this runbook keeps using only `ssh`/`scp`, its existing toolset, rather than adding a
   dependency on `rsync` being installed on the box (unconfirmed either way in `live-state.txt`).

```bash
# STEP 0 IS §1.5 — confirm it already ran (you should have a "pre-0918-<TS>" tag and
# influora.env.bak-<TS> / influora-ai.env.bak-<TS> for BOTH services) before continuing. If it
# has not run, stop and do it now — do not proceed with no rollback target.

# 1. Pin the exact commit. NEVER default to `git rev-parse HEAD` — this worktree can be on a
#    different, unreviewed commit by the time you run this (dirty tree, later local commits,
#    wrong branch checked out). Paste the SHA Swapnil/Arjun actually approved to ship:
DEPLOY_SHA=<the-approved-sha-swapnil-arjun-confirmed>   # e.g. 5b6ac6c — confirm, don't assume
# Sanity check it exists and matches what you expect before building anything from it:
git log -1 --oneline "$DEPLOY_SHA" || { echo "FATAL: $DEPLOY_SHA not found in this repo"; exit 1; }

# 2. Build influora-api from a CLEAN checkout of the approved SHA, not the working tree.
BUILD_DIR=$(mktemp -d)
git archive "$DEPLOY_SHA" | tar -x -C "$BUILD_DIR"
(cd "$BUILD_DIR/influora-api" && mvn -o clean package -DskipTests)
# Produces target/influora-api-0.1.0-SNAPSHOT.jar (pom.xml:15-16, no <finalName> — verified above).
scp "$BUILD_DIR/influora-api/target/influora-api-0.1.0-SNAPSHOT.jar" \
  root@150.241.245.242:/usr/local/App/influora/influora-api.jar

ssh root@150.241.245.242 <<'EOF'
set -e
cd /usr/local/App/influora
docker build -t influora-api:latest .
docker stop influora-api && docker rm influora-api
docker run -d --name influora-api --network host --restart unless-stopped \
  --env-file influora.env influora-api:latest
# [REPAIR R4] Feature flags (CREATOR_COPILOT_ENABLED, TREND_INGEST_ENABLED) both default to
# `false` and generate-env.sh never writes either — verify the recreated container actually has
# the value THIS RELEASE intends right now, not at 02:00 UTC when the scheduled job's disabled-log
# line is the first thing to notice a flag left at its default (see §2, F-0762 class).
sleep 3
docker exec influora-api env | grep -E 'CREATOR_COPILOT_ENABLED|TREND_INGEST_ENABLED' || {
  echo "FATAL: could not read feature-flag env from the recreated container" >&2; exit 1; }
EOF

# 3. Build and deploy influora-ai from the SAME approved SHA — no separate approval, same commit.
#    influora-ai/Dockerfile: EXPOSE 8000, HEALTHCHECK against /healthz, CMD runs
#    `uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1` — non-root, read-only app dir.
# [REPAIR R3] rm -rf the target FIRST — scp -r into an already-existing directory nests instead of
# replacing (reproduced locally), which would silently rebuild a stale prior deploy on every
# redeploy after the first. Chosen over `rsync --delete` to avoid a new host-tool dependency.
ssh root@150.241.245.242 'rm -rf /usr/local/App/influora-ai/influora-ai-src'
scp -r "$BUILD_DIR/influora-ai" root@150.241.245.242:/usr/local/App/influora-ai/influora-ai-src

ssh root@150.241.245.242 <<'EOF'
set -e
cd /usr/local/App/influora-ai/influora-ai-src
docker build -t influora-ai:latest .
docker stop influora-ai && docker rm influora-ai
docker run -d --name influora-ai --network host --restart unless-stopped \
  --env-file /usr/local/App/influora-ai/influora-ai/influora-ai.env influora-ai:latest
# Wait for the container's own HEALTHCHECK to report healthy before moving on — do not assume
# "docker run succeeded" means the app is serving.
# [REPAIR R4 — MEDIUM] The loop used to just fall through after 30 iterations with no final check:
# a container stuck on "starting" (crash-looping, or slow to fail) printed nothing, reached EOF and
# the heredoc exited 0 — reported as a successful deploy. Reproduced with a fake `docker` that
# always echoes "starting": old loop produced no output and exit=0. influora-ai/Dockerfile:45 sets
# HEALTHCHECK --interval=30s --retries=3, so a broken app needs about 90s to reach "unhealthy" —
# longer than this loop's 60s budget — which is exactly the silent-outage window this step exists
# to catch. Fixed by widening the budget to 45 iterations (90s) and failing loudly if the loop ends
# without ever seeing "healthy".
for i in $(seq 1 45); do
  STATUS=$(docker inspect --format '{{.State.Health.Status}}' influora-ai 2>/dev/null || echo "starting")
  [ "$STATUS" = "healthy" ] && { echo "influora-ai healthy"; break; }
  [ "$STATUS" = "unhealthy" ] && { echo "FATAL: influora-ai unhealthy after redeploy" >&2; exit 1; }
  sleep 2
done
[ "$STATUS" = "healthy" ] || { echo "FATAL: influora-ai never reported healthy after 90s (last status: $STATUS) — do not treat this deploy as successful" >&2; exit 1; }
EOF
```

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

# 2. Full health payload — the actual DB-reachability proof, WITH a caveat (REPAIR R2).
curl -s https://influora.in/api/v1/health
# expect {"status":"UP","storage":{"r2":"configured"}} — HealthController.java:26-30 delegates to
# HealthEndpoint, which auto-wires a DataSourceHealthIndicator; "status":"DOWN" here means SOME
# registered indicator is down, and the DB is not the only one. [REPAIR R2] pom.xml also pulls in
# spring-boot-starter-mail and spring-boot-starter-data-redis (pom.xml:64-65,71-73), which
# auto-register a MailHealthIndicator and a RedisHealthIndicator into the same aggregate status —
# a DOWN here can mean SMTP or Redis is unreachable, NOT necessarily the database. Read the full
# JSON body's per-component breakdown (not just the top-level "status") to tell which one tripped
# before assuming it is the DB.

# 3. workspaces sanity check (brief's requested query)
mysql -u influora_app -p influora -e "SELECT type, COUNT(*) FROM workspaces GROUP BY type;"
# workspaces.type is ENUM('BRAND','AGENCY') NOT NULL DEFAULT 'BRAND' —
# db/migration/V2__core_auth.sql:26. Confirms the DB the freshly-recreated container is pointed
# at is the real, non-empty production database, not a blank one from a bad SPRING_DATASOURCE_URL.

# 4. [REPAIR R2] Creator Co-pilot AI ROUND-TRIP check — the log-line checks below only prove the
#    scheduled JOB ran, not that its AI call to influora-ai actually succeeded. A wrong
#    CREATOR_COPILOT_AI_BASE_URL (see §2) fails SILENTLY: CreatorNudgeService.callAiSafely
#    (CreatorNudgeService.java:530-540) swallows every transport exception and degrades to
#    templatedFallback, and CreatorCaptionSyncJob's own log line looks identical either way. The
#    only place the distinction is recorded is creator_nudge_log.message_source (AI|FALLBACK,
#    NudgeMessageSource — V20260721140000__creator_nudge_log.sql:25). Run this AFTER the Creator
#    Co-pilot job below has fired at least once (on-schedule or forced off-schedule):
mysql -u influora_app -p influora -e \
  "SELECT message_source, COUNT(*) FROM creator_nudge_log WHERE shown_at >= NOW() - INTERVAL 1 DAY GROUP BY message_source;"
# if every row today is FALLBACK and zero are AI, the round trip to influora-ai is not actually
# working (wrong CREATOR_COPILOT_AI_BASE_URL, wrong port, firewall, or influora-ai not healthy) —
# go back to §2's CREATOR_COPILOT_AI_BASE_URL row and §3.2 step 3's healthcheck-wait loop.
```

**Trend ingest and Creator Co-pilot log checks — read this before waiting on them:**

- **[REPAIR R1 — was stale] Trend ingest now has a real log line.** §0.5's original claim that no
  `@Scheduled` job exists is out of date as of commit `af23eb9` on this branch:
  `TrendPullJob.java:166` runs on `${influora.trend-ingest.pull-cron:0 0 5 * * *}` (UTC).
  **[REPAIR R4 — line citations below were stale against `31f2351`/`39c829b`; also see the
  classifier-workspace-id blocker in §2 before reading a `classifier_unconfigured` line as a bug.]**
  ```bash
  docker logs influora-api --since 10m | grep "TrendPullJob"
  # on schedule (05:00 UTC, or TREND_INGEST_PULL_CRON if overridden), expect either:
  # "TrendPullJob: completed run — sourcesOk=N sourcesSkippedNoKey=N fetched=N deduped=N ..."
  #   (TrendPullJob.java:321) or, if no source returned rows, the shorter
  # "TrendPullJob: completed run — no rows fetched from any source (sourcesOk=..." (line 196)
  # if TREND_INGEST_ENABLED is false or unset, expect instead:
  # "TrendPullJob: disabled (influora.trend-ingest.enabled=false), skipping run" (line 170)
  # if TREND_INGEST_CLASSIFIER_WORKSPACE_ID is unset (the §2 blocker, expected until Swapnil/Priya
  # rule on it), every headline is rejected individually and the run still logs "completed run"
  # with written=0 — grep for the per-headline line to tell this apart from a real empty run:
  # "TrendPullJob: rejected id=... source=... reason=classifier_unconfigured — no ..." (line 241)
  ```
  Same off-schedule-run technique as Creator Co-pilot below applies if same-day confirmation is
  needed before 05:00 UTC — there is no dedicated override cron property for this job in
  `application.yml:569-577`; forcing an early run means editing `TREND_INGEST_PULL_CRON` to a
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

**[REPAIR R1 — this could not be carried out before; REPAIR R2 — now points at §1.5, not §3.2]**
`influora-api:<previous-sha-or-tag>` was never actually tagged anywhere, and
`influora.env.bak-<timestamp>` did not exist until this rollback section ran *after* `influora.env`
had already been overwritten — both referenced artifacts were created too late to use. **§1.5 now
creates them first, before §2 touches anything** (moved out of §3.2 this revision so the ordering
holds even if an operator skips straight to §2 without re-reading all of §3). Use the single `$TS`
§1.5 printed for BOTH services below (`<TS-from-§1.5>`):

**[REPAIR R3 — MEDIUM] The `influora-ai` block below previously `cd`'d into
`/usr/local/App/influora-ai` and referenced its backup as a bare relative filename** —
`influora-ai.env.bak-<TS-from-§1.5>` resolves, relative to that directory, to
`/usr/local/App/influora-ai/influora-ai.env.bak-<TS>`, one directory level shallower than where §1.5
actually writes it (`/usr/local/App/influora-ai/influora-ai/influora-ai.env.bak-<TS>` —
`live-state.txt:148-152,228`). `--env-file` on a path that does not exist makes `docker run` fail
outright, mid-incident, with no container recreated — the worst possible time to discover a path
bug. Fixed to `cd` into the same directory §1.5 backs up into.

```bash
ssh root@150.241.245.242 <<'EOF'
cd /usr/local/App/influora
docker stop influora-api && docker rm influora-api
docker run -d --name influora-api --network host --restart unless-stopped \
  --env-file influora.env.bak-<TS-from-§1.5> influora-api:pre-0918-<TS-from-§1.5>
EOF
ssh root@150.241.245.242 <<'EOF'
cd /usr/local/App/influora-ai/influora-ai
docker stop influora-ai && docker rm influora-ai
docker run -d --name influora-ai --network host --restart unless-stopped \
  --env-file influora-ai.env.bak-<TS-from-§1.5> influora-ai:pre-0918-<TS-from-§1.5>
EOF
```
If §1.5 was skipped, there is no rollback target for either service, and the only path is
rebuilding the previous known-good commit per §3.2's own steps against the last known-good SHA
instead — get that SHA from `git log` on this branch, not from guessing.

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
`application.yml:570`) with no migration dependency.

---

## Open items for Swapnil / Arjun (not decided here — hard rule 8)

1. **Which branch is actually going live** — `feat/meera-creator-phase-e` (this worktree) or
   `feat/meera-creator-phase-b0` (the parallel lane's scope). They are not the same diff (B0 adds
   4 migrations and a creator-briefs feature this tree does not have).
2. **`MEERA_CREATOR_SEND_ENABLED`** — only relevant if B0 is in scope; not wired here (§0.4).
3. **The `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` / `BRAND_SAFETY_SERVICE_TOKEN_SECRET`
   double-path** (§2) — pre-existing, not introduced by this lane, but worth a single-owner fix
   before it causes a silent secret mismatch. **[REPAIR R1]** confirmed the override is
   deterministic (OS env beats `application.yml`), not "unpredictable" as originally written here
   — the risk is a silent stale-value shadow, not nondeterminism.
4. **[REPAIR R1 — resolved, no longer open]** ~~Trend ingest has no scheduler~~ — `af23eb9`
   (`TrendPullJob.java`) landed this after `ba3fa62` was written; see §0.5 and §4.
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

---

## REPAIR ROUND 2 — defect-by-defect (commit `dac2452` → this revision)

| # | Sev | Finding | Fix |
|---|---|---|---|
| 1 | HIGH | R1's rollback-ordering fix was only half done: §2 (env edits) still came before §3.2 step 0 (the backup), so a top-down operator edited `influora.env` before backing it up; the image-tag backup also swallowed its own failure (`2>/dev/null \|\| true`), so a missing rollback image would go unnoticed | New **§1.5** runs the tag/jar/env backup for BOTH services before §2 touches anything (moved out of §3.2 entirely); the tag step now runs under `set -e` and checks its own exit status, aborting the deploy with a clear message if `influora-api:latest` cannot be tagged |
| 2 | MEDIUM | Both compose files forwarded `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` (no `${}` placeholder in `application.yml`) and never forwarded `BRAND_SAFETY_SERVICE_TOKEN_SECRET` (the name `application.yml:279` actually binds) — `docker compose config` confirmed the real var defaulted to a blank string while the useless one was set; the blank OS-env value would deterministically shadow a correctly-set secret at Spring boot | Both compose files now forward `BRAND_SAFETY_SERVICE_TOKEN_SECRET` and no longer mention the relaxed-binding alias at all (§0.2); §2's brand-safety-secret guidance rewritten to describe the fix, not just warn an operator |
| 3 | MEDIUM | §2 claimed its variable table "is the `influora-api` environment keyset from `docker-compose.utho.yml`", but 10 named variables (`CREATOR_COPILOT_SCORE_THRESHOLD`, `_DAILY_CAP`, `_PROMPT_VERSION`, `_CAPTION_SYNC_MEDIA_LIMIT`, `_CAPTION_SYNC_MAX_CREATORS_PER_RUN`, `_CAPTION_SYNC_CRON`, `_THEME_TAG_CRON`, `BRAND_SAFETY_SCORING_ENABLED`, `_MAX_CREATORS_PER_RUN`, `BRAND_SAFETY_SERVICE_TOKEN_SECRET`) were absent from both files | All 10 added to both compose files with defaults mirroring `application.yml:522-524,541-549` (§0.2); keyset gate re-run 96/97 keys, still PASS; §2's claim corrected to state this explicitly |
| 4 | MEDIUM | `influora-ai` redeploy was one sentence ("do the equivalent") with no build source, Dockerfile, run flags, or way to tell if the AI call actually round-tripped — `CreatorNudgeService.callAiSafely` silently falls back to template copy on any transport failure, which looks identical to success from the job's own log line | §3.2 step 3 rewritten with a full `git archive`-from-approved-SHA build, `influora-ai/Dockerfile`-cited run flags, and a HEALTHCHECK-wait loop; §4 check 4 added, querying `creator_nudge_log.message_source` to prove real `AI` rows exist, not just `FALLBACK` |
| 5 | LOW | No concrete required value given for `TREND_INGEST_ENABLED` (defaults OFF) or for `CREATOR_COPILOT_AI_BASE_URL` under `--network host` (loopback is hard-rejected by `SecretsStartupValidator`) | `TREND_INGEST_ENABLED` row now states the default is OFF and must be explicitly set `true` this release; `CREATOR_COPILOT_AI_BASE_URL` row now recommends `http://150.241.245.242:8000` (the box's own routable address under `--network host`) with a verification step for the bind address and firewall |
| 6 | LOW | "`status:DOWN` means the DB is unreachable" overstated it — `pom.xml` also pulls in `spring-boot-starter-mail` and `spring-boot-starter-data-redis`, which register their own health indicators into the same aggregate status | §4 check 2 rewritten to name both indicators and say to read the per-component breakdown, not assume DB |
| 7 | LOW | `DEPLOY_SHA=$(git rev-parse HEAD)` contradicted its own adjacent comment ("don't assume HEAD") | §3.2 step 1 now requires pasting the approved SHA literally, with a `git log -1` sanity check that fails loudly if it does not exist |

---

## REPAIR ROUND 3 — defect-by-defect (commit `a28ae79` → this revision)

| # | Sev | Finding | Fix |
|---|---|---|---|
| 1 | HIGH | R2's brand-safety-secret compose fix was not end to end: `deploy/utho/generate-env.sh:55` still wrote the OLD var name (`INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET`), so a freshly generated env still left `BRAND_SAFETY_SERVICE_TOKEN_SECRET` unset — reproduced with `docker compose config` printing "variable is not set. Defaulting to a blank string." on a freshly generated env. Separately, `generate-env.sh` itself failed outright (`exit 1`, 0-byte output) from live shell syntax (backticks, a bare `${VAR:default}`) inside its own unquoted heredoc | §0.3.5. `generate-env.sh:55` renamed to `BRAND_SAFETY_SERVICE_TOKEN_SECRET`; the two live-syntax bugs at lines 37/91 fixed so the script runs at all; both compose files' comment blocks updated to cite the companion fix. Verified: `bash generate-env.sh` now exits 0 and produces a complete 162-line file; `docker compose config` on both compose files resolves the secret to the generated value with no warning; the keyset gate still exits 0 |
| 2 | HIGH | `influora-ai` redeploy (§3.2 step 3) only replaces the source directory on the FIRST deploy — `scp -r localdir host:remotedir` copies INTO an already-existing `remotedir` instead of replacing it, leaving `docker build .` rebuild a permanently stale first copy on every later deploy | §3.2 step 3 now `ssh`es a `rm -rf` of the remote target directory immediately before every `scp -r`; chose rm-then-copy over `rsync --delete` to avoid adding a new host-tool dependency, since this runbook otherwise uses only `ssh`/`scp` |
| 3 | MEDIUM | §1.5's `influora-ai` backup was prose only ("do the equivalent"), not a runnable script, and implied the same flat `cd` as `influora-api`'s backup — but `influora-ai`'s env file lives one directory level deeper (`/usr/local/App/influora-ai/influora-ai/influora-ai.env`, not `/usr/local/App/influora-ai/influora-ai.env`). §5's rollback compounded this: it `cd`'d into the shallower directory and referenced the backup as a bare relative filename, resolving to the wrong path regardless. Also, running each service's backup in its own `ssh` session gave them two DIFFERENT timestamps, while §3.2/§5 both assume ONE shared `<TS-from-§1.5>` | §1.5 rewritten: `TS` is now generated ONCE locally and forwarded into both remote heredocs (`ssh host "TS=$TS bash -s"`), so both services share one timestamp; the `influora-ai` backup is now a concrete script `cd`-ing into `/usr/local/App/influora-ai/influora-ai` (matching the real layout); §5's rollback now `cd`s into that same directory instead of its parent |
| 4 | MEDIUM | §2's `CREATOR_COPILOT_AI_BASE_URL` row unconditionally told the operator to set a fresh value, but `live-state.txt`'s EIGHTH CAPTURE shows Creator Co-pilot already running successfully in prod with whatever value is already in `influora.env` (no code default exists in the prod profile) — overwriting it risks breaking a working integration for no documented reason, and the row also never mentioned the six sibling `*_AI_BASE_URL` vars that are not touched | Row rewritten: check and keep the CURRENT value first (via `ssh`/`docker exec env` or §4 check 4's AI-vs-FALLBACK query) unless it is confirmed broken; the `http://150.241.245.242:8000` guidance is now scoped explicitly to a FRESH box with no prior value, with the same bind/firewall verification steps as before |
| 5 | MEDIUM | The "every named variable is a `${VAR}` placeholder" clause failed on 3 rows that are intentionally NOT placeholders (`INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` — do-not-set; `MEERA_CREATOR_SEND_ENABLED` — not in this repo's code; `JAVA_TOOL_OPTIONS` — a JVM launcher flag, not a Spring property) but weren't marked unambiguously as such at their point of definition | Each of the 3 now carries an explicit "**[REPAIR R3] intentionally NOT a `${VAR}` placeholder**"-style marker at its first substantive mention, not just descriptive prose elsewhere in the document |

---

## REPAIR ROUND 4 — defect-by-defect (commit `39c829b` → this revision)

| # | Sev | Finding | Fix |
|---|---|---|---|
| 1 | HIGH | The runbook (§2 trend table, §4) and both Utho compose files were stale against their own parent commit `31f2351`, which had already bound `TREND_INGEST_CLASSIFIER_WORKSPACE_ID`, `TREND_INGEST_MAX_ROWS_PER_RUN` and `TREND_INGEST_REGION` in `application.yml:575-577` — 0 grep hits for "CLASSIFIER" in the runbook or either compose file. While `classifier-workspace-id` stays blank, `TrendPullJob.java:241` rejects every headline with `reason=classifier_unconfigured` and the job still logs "completed run" with 0 rows written, which §4 presented as plain success | §2 gained 3 new rows plus an explicit go-live-blocker callout: this lane is not authorized to pick a workspace id (hard rule 8 — no platform/system workspace concept exists in this codebase), so `TREND_INGEST_ENABLED=true` plus the 3 API keys is stated as insufficient until Swapnil/Priya rule on it. §4's trend log-check block now shows the `reason=classifier_unconfigured` line so an operator can tell a real empty run from the blocked-by-ruling case. All 3 vars forwarded in both Utho compose files (§0 item 8) and, for keyset parity, in `docker-compose.hostinger.yml` |
| 2 | MEDIUM | §3.2 step 3's `influora-ai` healthcheck-wait loop fell through silently: reproduced with a fake `docker` that always reports `starting` — the loop printed nothing and reached EOF with no failure. `influora-ai/Dockerfile:45`'s `HEALTHCHECK --interval=30s --retries=3` needs about 90s to report `unhealthy` for a genuinely broken app, longer than the loop's old 60s budget, so a slow-to-fail or crash-looping container read as a successful deploy | Loop widened to 45 iterations (90s) and a post-loop check now fails loudly (`exit 1`, FATAL message) unless the last observed status was `healthy`. Verified: a fake-`docker` harness that never reports healthy now exits 1 with an explicit FATAL line, where the unfixed loop had no such check at all |
| 3 | MEDIUM | `.proof-os/gates/compose-forwards-what-the-app-binds.sh` (Utho vs Hostinger — a DIFFERENT gate from `utho-compose-keysets-match.sh` in §0 item 6) had gone red across R2/R3 (13 vars, including the fail-closed `BRAND_SAFETY_SERVICE_TOKEN_SECRET`, that Utho forwards and Hostinger does not; Hostinger also still forwarded the do-not-set alias `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET`) without being reported anywhere in this document | §0 item 8 added, reporting the red-before/green-after gate output by name. `docker-compose.hostinger.yml` (in scope specifically because this gate requires parity) fixed: the alias line replaced with `BRAND_SAFETY_SERVICE_TOKEN_SECRET`, and all missing vars added with defaults mirroring Utho. Gate now prints `PROVED: both deploy compose files forward the same 90 application-bound env vars...` |
| 4 | MEDIUM | `CREATOR_COPILOT_ENABLED` had no stated required value for this release (unlike `TREND_INGEST_ENABLED`, which already had one) and `generate-env.sh` never writes it — same F-0762 failure class already fixed for trend-ingest: on a fresh box, or any env rebuilt from `generate-env.sh`, the Co-pilot comes up silently off, and nothing catches it until the 02:00 UTC job's log line | §2's `CREATOR_COPILOT_ENABLED` row rewritten to mirror `TREND_INGEST_ENABLED`'s wording (must be explicitly set `true` if this release turns it on). §3.2 step 2 now runs `docker exec influora-api env \| grep -E 'CREATOR_COPILOT_ENABLED\|TREND_INGEST_ENABLED'` immediately after the container is recreated, instead of waiting for the scheduled job to reveal a wrong flag |
| 5 | LOW | The done_when varcheck script's own failed run named 2 misses: `` `ENV_PATH` `` (a `generate-env.sh` shell-local variable, not a Spring property) and `` `REPLACE_ME` `` (the literal placeholder value the script counts, not a variable name) — neither was marked as such at its point of mention | Both now carry an explicit inline marker the first time they're named, matching the style already used for the 3 do-not-set vars from R3. Re-run with an equivalent extraction script (not the reviewer's original, which is not in this repo): `TOTAL 34 MISS 0` on this revision vs the un-annotated original text |
| 6 | LOW | Several `application.yml`/`TrendPullJob.java` line citations had drifted: the runbook cited `application.yml:555-559` for the trend vars and `TrendPullJob.java:123/127/153/254`, but R3's own additions (`max-rows-per-run`, `region`, `classifier-workspace-id`) shifted every line below them — the real locations are `application.yml:570-577` and `TrendPullJob.java:166/170/196/241/321` | All citations in §0 item 1, §2's trend table and §4's trend log-check block re-verified against the file as committed and corrected |
