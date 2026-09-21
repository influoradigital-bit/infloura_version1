# Meera answers — T-PHASEB-LIVE-0918 (Phase B0 server-side, for Priya)

All claims are code/config/git evidence only. Nothing here was run against the live Utho box.

## Q1 — Which workflow publishes images, from which branches?

`.github/workflows/publish-images.yml` builds `influora-api` (job `api`, :90-109), `influora-ai`
(job `ai`, :111-130) and `influora-web` (job `web`, :132-256), pushing all three to
`ghcr.io/influoradigital-bit/*` (:85-87, tags at :107-109/128-130/254-256).

Trigger (:57-83):
```
push:
  branches:
    - main
  paths:
    - 'influora-api/**'
    - 'influora-ai/**'
    - 'src/**'
    - 'Dockerfile'
    - '.github/workflows/publish-images.yml'
```
Push-branch filter is **`main` only**. `workflow_dispatch` (:17-56) has no branch restriction.

**Would a push of `feat/meera-creator-phase-b0` (or a merge into `feat/meera-creator-phase-e`)
publish images?** PROVEN NO for the automatic `push` trigger — neither branch is `main`, so the
`push:` block never fires. `workflow_dispatch` COULD be run manually against either branch (GitHub
lets you pick the branch on dispatch), but only if that branch's `.github/workflows/` on GitHub
already contains this file — see Q6, this repo checkout has 38 unpushed commits, so what's on
GitHub today may not even have the current version of this workflow. UNKNOWN whether it's on GitHub
now: `gh` CLI is not installed here (`gh auth status` → "gh: command not found"), so I could not
query Actions/branch state directly; go by `git branch -r --contains <sha>` instead (Q6 method).

## Q2 — B0's new env vars: defaults, forwarded in both Utho compose files, gate result

Diffed `influora-api/src/main/resources/application*.yml` and `influora-ai/app/config.py` between
the b0 worktree (`C:/.../influora-b0`, branch `feat/meera-creator-phase-b0`) and this tree.

| Var | Default | `utho.yml` (api svc) | `utho-shared.yml` (api svc) | In `influora.env`? |
|---|---|---|---|---|
| `CREATOR_COPILOT_AI_BASE_URL` | `http://localhost:8000` (application.yml:285) | ✅ `http://influora-ai:8000` (utho.yml:171) | ❌ absent | must be set — code default is localhost, wrong inside Docker |
| `CREATOR_COPILOT_AI_CONNECT_TIMEOUT_SECONDS` | `5` (application.yml:294) | ❌ absent | ❌ absent | optional — default is safe |
| `CREATOR_COPILOT_AI_REQUEST_TIMEOUT_SECONDS` | `15` (application.yml:295) | ❌ absent | ❌ absent | optional |
| `AI_CREATOR_MONTHLY_CAP_USD` | `0.75` (config.py:541) | ✅ `"0.75"` (utho.yml:303, under **influora-ai**, not influora-api) | ❌ absent from influora-ai block too | optional, defaults match, but repo convention (utho.yml:298-302 comment) says pin explicitly |
| `BRIEF_EXTRACT_MONTHLY_CAP_USD` | `0.25` (config.py:562) | ❌ absent | ❌ absent | optional |
| `BRIEF_EXTRACT_MODEL` | falls back to `TRENDSPARK_MODEL` (config.py:211) | ❌ absent | ❌ absent | optional |
| `INFLUORA_RATES_*` (5 keys, application.yml:207-211) | 25 / 40 / 75 / 2.5 / 15 | ❌ absent (all 5) | ❌ absent (all 5) | optional — SPEC.md §4.2 benchmarks, fine unset |
| `MEERA_CREATOR_ENABLED` | `true` (application.yml:232, `MeeraCreatorFeatureProperties.java:32`) | ✅ present | ❌ **absent** | should be set for an incident kill switch |
| `CREATOR_COPILOT_ENABLED` | `false` (application.yml:548) | ✅ present | ❌ **absent** | not B0 itself (Tier-1 co-pilot), but same drift |

**Keyset gate result** (`.proof-os/gates/utho-compose-keysets-match.sh` — this file exists only on
`feat/meera-creator-phase-e`, NOT on the b0 branch/worktree, `find $B0/.proof-os/gates -iname
"*keyset*"` returned nothing). I reproduced its exact awk/diff logic by hand against b0's own two
compose files (read-only, wrote only to my scratch dir, nothing written into the b0 tree):

```
NA=79 NB=70
KEY MISSING FROM shared: ADMIN_NOTIFICATION_EMAIL
KEY MISSING FROM shared: CREATOR_COPILOT_AI_BASE_URL
KEY MISSING FROM shared: CREATOR_COPILOT_ENABLED
KEY MISSING FROM shared: CREATOR_INVITE_TOKEN_SECRET
KEY MISSING FROM shared: MEERA_CREATOR_ENABLED
KEY MISSING FROM shared: META_CREATOR_MARKETPLACE_ENABLED
KEY MISSING FROM shared: META_SYSTEM_IG_ACCESS_TOKEN
KEY MISSING FROM shared: META_SYSTEM_IG_USER_ID
KEY MISSING FROM shared: SMTP_SSL_ENABLE
KEY MISSING FROM shared: SMTP_STARTTLS_ENABLE
FAIL=1
```
PROVEN: on the b0 branch, `deploy/utho/docker-compose.utho-shared.yml`'s `influora-api.environment`
is missing 10 keys present in `docker-compose.utho.yml`, including the two that matter for B0:
`CREATOR_COPILOT_AI_BASE_URL` and `MEERA_CREATOR_ENABLED`. This is exactly the F-0787 class the
gate was written to catch (gate script comment, line 2-3), recurring on this branch. The gate
itself only checks the `influora-api` block (script comment line 63: "NOT CHECKED: ... other
services (influora-ai, ...)") — so it would NOT have caught `AI_CREATOR_MONTHLY_CAP_USD` being
absent from the `influora-ai` block in `utho-shared.yml` (confirmed by reading that block directly,
`docker-compose.utho-shared.yml:258-296` — no `AI_CREATOR_MONTHLY_CAP_USD` line at all).

## Q3 — B0 migrations, ordering, rollback

B0 adds 4 migrations not on phase-e (`influora-b0/influora-api/src/main/resources/db/migration/`):
`V20260910100000__meera_phase_b_prefs.sql`, `V20260910100100__creator_briefs.sql`,
`V20260910100300__meera_drafts.sql`, `V20260910100500__deal_offer_history.sql`.

phase-e has since added 5 migrations timestamped LATER (09-12 through 09-18) that b0 does not have.
Both branches set `spring.flyway.out-of-order: true` (`application.yml:61`, identical on both
trees). Because out-of-order is true on both, PROVEN (by Flyway's documented out-of-order semantics
— not independently executed here) that merging b0 into phase-e and running `flyway migrate` would
apply b0's four 09-10-dated scripts even though 09-12/09-17/09-18 scripts already ran; out-of-order
disables Flyway's default refusal to apply a version below the current max. I did not run an actual
migration — this is CODE-ONLY, not PROVEN by execution.

**Rollback story: none.** `grep -n rollback` on the four b0 migration files and on
`wiki/processes/schema-changes.md` returned nothing. Flyway community edition (what's used here,
no `flyway-teams` marker found) ships no automated `undo` migrations. The only rollback path is a
hand-written forward-fix migration or a full DB restore from backup — neither is documented in this
repo for these four scripts. Flag this to Priya/Swapnil before shipping if a fast rollback is
required.

## Q4 — Rate limits and spend caps on the paste path

**Rate limit** (`influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java`, b0
worktree):
- `POST /creator/briefs` (paste) → bucket `creator-brief-paste`, **10 requests/window, USER-keyed**
  (`:337` `@Value("${influora.meera.creator-brief-paste-rate-limit-per-window:10}")`, matched at
  `:630-631`).
- `GET /creator/briefs/{id}` (re-analyses a stale-NEW brief, real ~30s AI call) → bucket
  `creator-brief-get`, **20/window, USER-keyed** (`:356`, matched at `:527`, pattern `:160`).
- Both are Spring `@Value`-overridable env vars but neither is forwarded in either Utho compose
  file (confirmed by grep, both absent) — they'll run on their code defaults (10/20) on the live
  box unless someone edits the compose files.

**Spend caps** (`influora-ai/app/config.py`, b0 worktree):
- `AI_CREATOR_MONTHLY_CAP_USD` default `0.75` (:541) — per-creator, calendar-month, BLOCKING via
  `app.costs.spend_tracker.check_creator_spend_gate` (comment :536-539). Forwarded in `utho.yml`
  under **influora-ai**, `AI_CREATOR_MONTHLY_CAP_USD: "0.75"` (`utho.yml:303`); absent from
  `utho-shared.yml`'s influora-ai block, so it silently runs on the same 0.75 default there too —
  functionally identical today, but per the repo's own stated convention ("PINNED explicitly so it
  can never again silently fall back to a code default", `utho.yml:298-302`) that box is out of
  compliance with its own rule.
- `BRIEF_EXTRACT_MONTHLY_CAP_USD` default `0.25` (:562), a SEPARATE per-creator monthly bucket keyed
  `f"{creator_profile_id}:brief"` (comment :545-547) — not forwarded in either compose file, runs on
  the 0.25 default everywhere.

## Q5 — Live checks that would PROVE B0 works (commands only, NOT run)

Routes confirmed from `CreatorBriefController.java` (b0): `@RequestMapping("/creator/briefs")`
(:54), API context-path `/api/v1` (`application.yml:119`).

```bash
# 1. API is up and this build includes B0
curl -s -o /dev/null -w '%{http_code}\n' https://api.influora.in/api/v1/health
# expect: 200

# 2. Feature flag is actually on for this deploy (public, unauthenticated)
curl -s https://api.influora.in/api/v1/public/config | jq '.meeraCreatorEnabled // .'
# expect: true (or the equivalent key GET /public/config exposes — controller comment
# CreatorBriefController.java:44-45 says the client "learns this from GET /public/config")

# 3. Paste a brief (needs a real creator bearer token) -- the actual money-spending path
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.influora.in/api/v1/creator/briefs \
  -H "Authorization: Bearer $CREATOR_JWT" -H "Content-Type: application/json" \
  -d '{"text":"Brand X wants 2 reels + 1 story, budget 15k, no exclusivity"}'
# expect: 201 (feature on, consent accepted); 404 if flag off (FEATURE_DISABLED,
# CreatorBriefController.java:81-86); 403 if consent missing (:97-102)

# 4. Confirm the paste actually reached influora-ai and not a dead CREATOR_COPILOT_AI_BASE_URL
#    (grep the API container logs on whichever box was deployed)
docker logs influora-api --since 10m | grep "read by the deterministic extractor"
# a HIT here means the AI call degraded/failed (CreatorBriefService.java:453-456,
# degradedReason DEGRADED_CAP or DEGRADED_AI_UNAVAILABLE) -- on the utho-shared box this is the
# expected failure mode from Q2's missing CREATOR_COPILOT_AI_BASE_URL (falls back to
# http://localhost:8000, unreachable from inside the influora-api container)

# 5. Re-fetch the brief and confirm price/risk fields are populated
curl -s https://api.influora.in/api/v1/creator/briefs/$BRIEF_ID -H "Authorization: Bearer $CREATOR_JWT" | jq '.data.quote, .data.riskFlags'
# expect: non-null quote object and a risk-flags array, not both null/empty
```

## Q6 — Has the Testcontainers/Docker CI job (F-0822) ever run on GitHub?

PROVEN NO. `git branch -r --contains e2dba1ce455641e9cb8646b41ba610427b14c9a8` (the commit that
added `.github/workflows/creator-nudge-concurrency-it.yml`, dated 2026-09-17 per
`git log -1 --format=%ai`) returns **no branches** — that commit is on no remote ref at all.
`git rev-list --left-right --count HEAD...origin/feat/meera-creator-phase-e` → `38  0`: this
worktree is 38 commits ahead of, 0 behind, its own upstream. The workflow file has never existed on
any branch pushed to GitHub, so it cannot have run there. This matches `.proof-os/ledger/
failures.jsonl` F-0822, still `"status": "open"`, `"fix": ""` as of 2026-09-15 — the ledger has no
record of it being closed either.

What it would need to cover for B0: the same class of DB-level race the existing test targets
(`CreatorNudgeDailyCapConcurrencyIntegrationTest`, `AbstractIntegrationTest` + real MySQL via
Testcontainers, `creator-nudge-concurrency-it.yml:1-15`), but for B0's own concurrency-sensitive
writes — a creator double-submitting the same paste (unique-ish text within the 10/window rate
limit could still race two inserts before the first completes) and the per-creator monthly spend
gate under concurrent pastes (two simultaneous `POST /creator/briefs` from one creator both
checking `check_creator_spend_gate` before either's spend is recorded — a TOCTOU on the cap, same
shape as the nudge daily-cap race this job already proves for a different table). None of this is
covered today; UNKNOWN/not found in this repo's test tree.

---

## Deploy checklist (in order)

1. Fix `deploy/utho/docker-compose.utho-shared.yml` on the b0 branch: add `CREATOR_COPILOT_AI_BASE_URL`
   and `MEERA_CREATOR_ENABLED` to the `influora-api` block (matches `utho.yml`), and
   `AI_CREATOR_MONTHLY_CAP_USD` to the `influora-ai` block. Re-run the keyset gate (Q2) until it
   passes — it does not exist on the b0 branch yet, so port it over first.
2. Set `CREATOR_COPILOT_AI_BASE_URL`, `MEERA_CREATOR_ENABLED` (and ideally
   `BRIEF_EXTRACT_MONTHLY_CAP_USD`) in `/usr/local/App/influora/influora.env` on the live box —
   this file is not in the repo (F-0842), so this step cannot be verified from here, only asserted
   as required.
3. Push `feat/meera-creator-phase-b0` (or its merge into `feat/meera-creator-phase-e`) to GitHub,
   then trigger `publish-images.yml` via `workflow_dispatch` — the `push` trigger only fires on
   `main` (Q1), so nothing publishes automatically from either branch.
4. Apply the 4 B0 Flyway migrations against a real, out-of-order-enabled prod DB (Q3) with a tested
   restore-from-backup plan ready, since no rollback migrations exist.
5. Deploy, then run the Q5 checks in order 1→5, watching specifically for the "read by the
   deterministic extractor" degraded-fallback log line — its presence means step 1 or 2 was skipped.

## Top 3 risks (evidence)

1. **Shared-box deploy silently loses the brief-extract AI call and the feature kill switch.**
   `docker-compose.utho-shared.yml`'s `influora-api` environment omits `CREATOR_COPILOT_AI_BASE_URL`
   (falls back to `application.yml:285`'s `http://localhost:8000`, unreachable from the container)
   and `MEERA_CREATOR_ENABLED` (still defaults `true` in code, `application.yml:232`, so the feature
   stays on but cannot be killed via env on that box). Reproduced with the keyset-gate logic by hand
   (Q2); the gate itself (`.proof-os/gates/utho-compose-keysets-match.sh`) that exists specifically
   to catch this class of bug (F-0787) is not present on the b0 branch to catch it there.
2. **No rollback for the 4 B0 migrations.** Confirmed by grep: no `rollback` mentions in the
   migration files or `wiki/processes/schema-changes.md`. Flyway community has no `undo`; a bad
   migration on prod means hand-writing a forward fix or a full restore, under live traffic.
3. **F-0822's Testcontainers concurrency job has never run anywhere reachable.** `git branch -r
   --contains <sha>` proves the commit that added it is unpushed (38 commits ahead of upstream);
   `.proof-os/ledger/failures.jsonl` still lists F-0822 `"status": "open"`. The concurrency behavior
   B0 itself needs proven (double-paste races, concurrent spend-gate checks) has no test at all yet,
   proven or not.

Answers file: `C:/Users/Sage world/Downloads/New Influora Ai/New Influora/.proof-os/tasks/T-PHASEB-LIVE-0918/meera-answers.md`
