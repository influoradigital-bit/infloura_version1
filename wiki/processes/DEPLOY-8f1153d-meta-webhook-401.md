# DEPLOY RUNBOOK — restore Meta webhook permitAll
**To:** Meera (DevOps) · **From:** Tejas · **Authorized by:** Swapnil, 2026-09-02
**Deploy:** commit `8f1153d` (already on `origin/main`)

---

## Why

Meta's two platform callbacks are rejected in production:

    POST https://influora.in/api/v1/webhooks/meta/data-deletion  -> 401 UNAUTHENTICATED
    POST https://influora.in/api/v1/webhooks/meta/deauthorize    -> 401 UNAUTHENTICATED

Spring Security rejects Meta before the controller runs — the body is Influora's own
envelope, `"Your session has expired. Please sign in again."` Meta sends `signed_request`
with no JWT, so these endpoints must be public; the trust boundary is the signed_request
HMAC, verified in full before any field is parsed.

**There is nothing to write.** `SecurityConfig.java:134-137` already permitAlls both
(F-0392 Track E), committed at `8f1153d`. `git diff HEAD` is EMPTY for
`config/SecurityConfig.java` and `integration/meta/webhook/`. Production is simply running
a build older than that commit. **This is a deploy, not a code fix. Do not edit app code.**

Impact while broken: Meta cannot deliver deauthorize or data-deletion callbacks — a live
compliance failure — and App Review pings the callback URL on submission.

---

## Constraints — read before touching anything

1. **Deploy from a CLEAN checkout of `8f1153d`. Never from the working tree.**
   The tree currently has **42 modified + 104 untracked** files, including
   `influora-api/src/main/resources/application-prod.yml`, `application.yml`, and BOTH
   `deploy/*/docker-compose*.yml`. Building from disk would ship untested config to prod.
   `8f1153d` is on `origin/main` — build from the ref.
2. **Do not commit, stage, push or stash.** Another Claude Code session edits this repo
   concurrently and has clobbered in-progress work before. Leave the tree alone.
3. **No destructive VPS operations.** No recreate, restore, snapshot rollback, or firewall
   change. Restarting/redeploying app containers is in scope; nothing else is.
4. `-DskipTests` still COMPILES tests in this repo — a broken test source fails the build.

---

## Production facts (probed 2026-09-02 — `deploy/utho/` docs are OUT OF SYNC, trust these)

    https://influora.in       200 OK        <-- apex serves BOTH the SPA and the Java API
    https://www.influora.in   200 OK
    https://app.influora.in   TLS FAILURE   <-- no certificate
    https://api.influora.in   TLS FAILURE   <-- no certificate
    DNS: all three -> 150.241.245.242
    Java API context-path /api/v1, reachable at the APEX
    https://influora.in/actuator/health -> 200

`deploy/` contains BOTH a `hostinger/` and a `utho/` config. Determine which is actually
live before acting. The Hostinger VPS MCP tools are available if it is the Hostinger box.

Separately (do NOT fix as part of this deploy, just report): the TLS cert covers only the
apex + www. `app.` and `api.` have no certificate, and `deploy/utho/generate-env.sh:108`
points META_REDIRECT_URI at `app.influora.in`, which does not resolve over TLS. Production
is evidently not using that file.

---

## Verify — paste actual output, do not assume

    curl -s -o /dev/null -w "%{http_code}\n" -X POST \
      https://influora.in/api/v1/webhooks/meta/data-deletion -d "signed_request=invalid"

    curl -s -o /dev/null -w "%{http_code}\n" -X POST \
      https://influora.in/api/v1/webhooks/meta/deauthorize -d "signed_request=invalid"

**PASS = 400 on both.** The controller was reached and rejected the bad signature.
**FAIL = 401** (still blocked by Spring Security) **or 5xx.**
A 400 is the success condition. Do NOT report success on a 401.

No-regression checks:

    https://influora.in/                        expect 200
    https://influora.in/privacy/                expect 200
    https://influora.in/api/v1/actuator/health  expect 401 (Spring responding)

**CORRECTED — my original check was wrong.** I first listed bare
`https://influora.in/actuator/health` and called its 200 proof the API was healthy. It is
not. The context-path is `/api/v1`, so the bare path is not a Spring route at all — it
falls through to the SPA catch-all and returns `<!doctype html>`. That check passes
vacuously whether or not the API is running. Meera caught this. Use the `/api/v1/` path;
a **401 there is the healthy signal** (Spring is up and enforcing auth).

---

## How to deploy (added after Meera's first pass)

Live target is the **Utho** box at `150.241.245.242`, NOT Hostinger. The Hostinger VPS MCP
sees VPS 1844961 at `200.141.1.6` — a DIFFERENT machine. Do not point Hostinger tooling at
this deploy. Confirmed in `.proof-os/tasks/T-UTHO-0820/TASKS.md`: Swapnil chose Utho,
nameservers moved 2026-08-21, apex now resolves to 150.241.245.242.

**There is no CD workflow.** `.github/workflows/` has only `publish-images.yml` (builds and
pushes GHCR images) and `meera-live-smoke.yml` (read-only probes). Every deploy is manual
SSH + `docker compose`.

`deploy/utho/docker-compose.utho.yml` pins `:latest` tags:

    ghcr.io/influoradigital-bit/influora-api:latest
    ghcr.io/influoradigital-bit/influora-ai:latest
    ghcr.io/influoradigital-bit/influora-web:latest

`publish-images.yml` triggers on push to `main`, and `8f1153d` IS on `origin/main` — so a
`:latest` image containing the fix SHOULD already exist in GHCR. **VERIFY THIS FIRST**; the
API job has failed before (2026-07-25, DealControllerTest — and note `-DskipTests` still
COMPILES tests). If the image is stale, the deploy is a no-op and the 401 will persist.

If the image is current, the deploy on the box is roughly:

    docker compose -f deploy/utho/docker-compose.utho.yml pull influora-api
    docker compose -f deploy/utho/docker-compose.utho.yml up -d influora-api

Only the API image matters for this fix. Do not needlessly cycle web/ai/db containers.

BLOCKER: SSH access to 150.241.245.242. `~/.ssh/known_hosts` has a prior host-key entry for
it, so the box has been reached from this machine before, but the authorized user/key is
unknown. There is no ACCESS-GUIDE on disk (gitignored) and no Utho MCP configured.

---

## Report back

- Which host/orchestration is live, and what you deployed (commit + method)
- Exact before/after status codes
- Any `deploy/` config that does not match reality
- If you cannot complete it (no access, missing credentials, unclear target): STOP, do not
  improvise, and say exactly what is blocking you.

If it did not verify green, say so plainly. Do not overstate.

---

## Blocks

Until this is green, do NOT register any callback URL in the Meta dashboard — Meta
validates the URL on save and a 401 may be rejected. See
`wiki/decisions/2026-09-02-meta-app-settings-sheet.md`.
