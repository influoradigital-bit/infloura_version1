# Staging Deploy Checklist — Admin Panel

**Owner of this doc:** Meera (DevOps) · **Date:** 2026-07-09 · **Cycle:** 7 (final build cycle)
**Task source:** `src/admin/TASK_ASSIGNMENTS.md` line 119 — Meera / "Staging deploy" / P2

## Why this is a checklist, not a deploy report

I do not have staging credentials, a cloud account, a CI deploy secret store, or a running
Docker daemon in this sandboxed environment (same standing gap documented in every prior
cycle's `SHARED_CONTEXT.md` entry — `docker info` is unreachable here). There is no existing
staging environment for this project to deploy into: no `vercel.json`, no `.env.staging`, no
Railway/Fly/Render config, no Dockerfile for the Spring Boot API or the Vite frontend, and the
only GitHub Actions workflow (`.github/workflows/lighthouse-meera.yml`) runs a Lighthouse gate
on push/PR — it does not build a deployable artifact or deploy anywhere.

Faking a "deployed to staging" status would be a lie in `SHARED_CONTEXT.md` that the rest of the
pipeline (Kabir, Kavya, Swapnil) would treat as real. Instead, this is the concrete, actionable
list of what a human with real infra access needs to do. Nothing below is aspirational filler —
each item names the exact file, command, or secret involved.

---

## 1. What currently exists (audited 2026-07-09)

| Artifact | Status |
|---|---|
| `docker-compose.yml` | Local-only: MySQL 8 + Redis 7, for `mvn spring-boot:run` against `localhost`. Not a staging stack (no app containers, no reverse proxy, no TLS). |
| `influora-ai/Dockerfile` | Exists, production-shaped (non-root, read-only FS, healthcheck) — **but only for the Python AI service**. |
| Spring Boot API (`influora-api/`) | **No Dockerfile.** Deploys today only via `mvn spring-boot:run` on a dev machine. |
| Vite frontend (repo root) | **No Dockerfile.** README's "Docker" section (lines 293–304) is a generic unused stub — 3-line `pnpm build && pnpm preview`, no `.dockerignore`, never referenced by CI, doesn't account for the `.env.local` / `VITE_API_BASE_URL` split. |
| `.github/workflows/lighthouse-meera.yml` | Build + Lighthouse gate only. No `deploy` job, no environment secrets block, no artifact push. |
| `.env.staging` | **Does not exist.** Only mentioned as an illustrative example in `docs/UI-UX-IMPROVEMENT-PLAN.md` and `docs/CURSOR-AI-PROMPTS.md` — not a real file anyone has created. |
| `vercel.json` / Railway config | **Does not exist anywhere in the repo.** README's "Vercel (Recommended)" section is a 2-line aspirational stub with no actual project link. |
| `docs/BACKEND-API-SPEC.md:239` | References `https://staging-api.influora.com/api/v1` — this URL is a spec placeholder, not a provisioned host. |

**Conclusion: there is no staging environment to deploy the admin panel *to* yet.** This is an
infrastructure-provisioning gap, not an admin-panel-specific gap — the admin panel is just the
first feature that made the gap block a real task.

---

## 2. Build artifacts the admin panel actually needs staged

- **Frontend:** `npm run build` → static `dist/` (Vite). Admin routes (`src/admin/**`) are
  code-split into the same SPA bundle as brand/creator — no separate build target exists or is
  needed.
- **Backend:** `mvn -o clean package` → Spring Boot fat JAR. All 8 admin controllers
  (`AdminAuthController`, `AdminDashboardController`, `AdminBrandController`,
  `AdminCreatorController`, `ApprovalWorkflowController`, `AdminSupportController`, plus
  `AuditLogController`/`FlagQueue` backend once those land) ship in the same JAR as everything
  else — again, no separate admin deploy unit.
- **DB:** MySQL 8, Flyway-managed, currently at **V39** locally (persistent dev DB `influora_ai`
  confirmed at `flyway_schema_history` v39, `success=1` — see `wiki/processes/schema-changes.md`
  V39 entry). A staging DB has never had any of V1–V39 applied.
- **Cache:** Redis 7 (`docker-compose.yml` `redis` service), backs `spring.cache.type=redis` for
  `AdminDashboardStatsCache.pulseStats()` (45s TTL). **Never actually started or hit in this
  sandbox** (`docker info` unreachable) — config is compile-verified only, not live-verified,
  per the Cycle 7 `SHARED_CONTEXT.md` entry. This is a real open risk, not a formality: the first
  time this Redis config runs against a live daemon should be a deliberate verification step, not
  assumed-working because it compiled.

---

## 3. Migration sequencing for a fresh staging DB (V1→V39)

All 39 migrations are plain, additive, sequential Flyway scripts — no manual/handwritten
out-of-order migrations, no `baseline-on-migrate` gotchas beyond the one already configured
(`flyway.baseline-on-migrate: true` in `application.yml`, needed only if staging's DB already has
non-Flyway-managed tables, which it shouldn't on a fresh instance).

**Procedure for a human with staging DB access:**
1. Provision a fresh MySQL 8 instance (utf8mb4, matches `docker-compose.yml`'s
   `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`).
2. Point `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` at it (do **not** reuse the dev
   `influora`/`influora` credentials — see secrets section below).
3. Run the Spring Boot app once with `SPRING_PROFILES_ACTIVE=staging` (or equivalent) — Flyway
   auto-migrates 1→39 on boot, same mechanism used for every local V-migration to date (no manual
   `flyway migrate` CLI step has been used anywhere in this project's history; it's always
   boot-triggered).
4. Verify `flyway_schema_history` shows all 39 rows `success=1`, no gaps.
5. Spot-check the two migrations with FK/data-integrity weight for admin: **V35** (`mfa_secret`
   encryption columns — confirm `encrypted_mfa_secret` exists, `mfa_secret` plaintext column is
   gone/unused) and **V36+V39** (`workspaces` suspension/KYC columns + the `admin_users(id)` FKs
   for `suspended_by`/`reinstated_by`/`kyc_reviewed_by` — confirm these reject a nonexistent
   admin id, same live FK test Meera ran locally on V36).
6. Confirm Hibernate `ddl-auto=validate` boots clean (it will hard-fail startup if the live schema
   doesn't match every entity — this is the same gate that's been run after every local
   migration).

No migration in V1–V39 requires special staging handling (no `NOT VALID` constraints staged for
later validation, no long-running backfills, no manual data seeding beyond V7's dev-only seed
data — **do not run V7's seed migration's intent against staging** if staging is meant to hold
real-shaped data; it was written for local dev discoverability testing).

---

## 4. Secrets a staging deploy needs (admin-specific + shared)

`influora-api/.env.example` documents every required secret. **All of these currently have
dev-mode fallback defaults committed in `application.yml`** — fine for local dev, unacceptable
for staging if left as-is:

| Secret | Purpose | Fail-closed in non-dev? |
|---|---|---|
| `ADMIN_MFA_SECRET_ENCRYPTION_KEY` | AES-256-GCM key encrypting `admin_users.encrypted_mfa_secret` (V35). Admin-specific, ship-blocking per Kabir's original finding. | **NO — gap.** `SecretsStartupValidator.java` (lines 43–59) validates JWT/Meera-stream/internal-service/brand-safety/JWKS secrets against known-dev-default lists, but `ADMIN_MFA_SECRET_ENCRYPTION_KEY`, `SHOPIFY_TOKEN_ENCRYPTION_KEY`, and `CONVERSION_WEBHOOK_TOKEN_ENCRYPTION_KEY` are **not** in its checked set. Staging could boot silently on the committed dev default (`1FTwBvGuJmF6Q07xw3sMPX0CZEdRWxZx9cIC54HVfUU=`, `application.yml:261`) with no startup failure. **Flagging this to Kabir/Vikram as a real pre-staging fix, not deferring it silently.** |
| `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET` | Session auth | Yes — `SecretsStartupValidator` |
| `MEERA_STREAM_SIGNING_SECRET` | SSE stream tokens | Yes |
| `INTERNAL_SERVICE_TOKEN_SECRET` / `INTERNAL_REQUEST_HMAC_SECRET` | Spring↔Python mesh auth | Yes |
| `JWKS_PRIVATE_KEY_PEM` / `JWKS_PUBLIC_KEY_PEM` | ES256 service-token signing | Yes (dedicated PEM check) |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | Staging MySQL, must NOT be `influora`/`influora` dev creds | No validator — human discipline required |
| `SPRING_DATA_REDIS_HOST/PORT` | Staging Redis, must point at a real reachable instance | No validator |
| `SHOPIFY_TOKEN_ENCRYPTION_KEY`, `CONVERSION_WEBHOOK_TOKEN_ENCRYPTION_KEY` | Not admin-specific but same "committed default risk" pattern | No |
| `VITE_API_BASE_URL` (frontend) | Must point at staging API host, not `localhost:8080` | Build-time env, no runtime validator |
| `CORS_ALLOWED_ORIGINS` | Must include the staging frontend origin or admin login will CORS-fail | No validator |

**Action for whoever provisions staging:** generate fresh values for every row above via
`openssl rand -base64 32` (or the EC-keypair steps documented inline in
`influora-api/.env.example` for the JWKS PEM pair), store them in the staging host's real secret
manager (not committed to the repo), and set `influora.env` (`APP_ENV`) to something other than
`dev` so `SecretsStartupValidator` actually enforces the ones it covers.

---

## 5. CI/CD gap — what exists vs. what's needed

`.github/workflows/lighthouse-meera.yml` is the only workflow. It:
- Triggers on push to `main`, PRs, and manual dispatch.
- Runs `npm ci` + `npm run build` + a Lighthouse perf/CLS gate against `/brand/meera`.
- Has **no deploy job**, **no backend build/test step**, **no Docker build/push step**, **no
  environment secrets block**.

It does not need admin-specific additions to do what it currently does (it's a frontend-only
perf gate and doesn't touch the backend, DB, or Redis). But it is **not** a deploy pipeline and
should not be mistaken for one. A real staging deploy pipeline would need, at minimum:
1. A backend job: `mvn -o clean package` + `mvn -o test` (744 tests as of Cycle 7).
2. Dockerfiles for `influora-api` and the frontend (neither exists — `influora-ai/Dockerfile` is
   the only precedent to model them on: non-root, health-checked, minimal base image).
3. A deploy step (Vercel CLI for frontend / Railway-Render-Fly-ECS for backend — README gestures
   at Vercel + Docker but has no working config for either).
4. A secrets block wiring the table in §4 into GitHub Environments (or whatever secret store the
   chosen host uses) — **not** committed to the repo.
5. A migration-apply step (or confirmation that boot-time Flyway auto-migrate, as used locally,
   is acceptable for staging too — it has been the only mechanism used in this project's history).

---

## 6. What Meera is honestly blocked on

- No cloud account / hosting provider credentials (Vercel, Railway, Render, AWS, etc.) exist in
  this sandbox.
- No Docker daemon reachable (`docker info` fails — standing gap since at least Cycle 6, affects
  Redis live-verification too, see `SHARED_CONTEXT.md` Cycle 7 Redis entry).
- No GitHub repo secrets / environment access to add a real deploy job to the Actions workflow.
- No staging DNS/host to point `VITE_API_BASE_URL` or `CORS_ALLOWED_ORIGINS` at.

None of this is something an agent-only pipeline can self-provision — it requires a human
(Swapnil or whoever holds the hosting account) to create the accounts/hosts, after which the
steps in §3–§5 above are mechanical and can be handed back to an agent to execute.

---

## 7. Immediate next actions (in order)

1. **Swapnil/human DevOps:** provision a staging MySQL instance, a staging Redis instance, and a
   hosting target for the Spring Boot JAR + Vite static build (or a single Docker host running
   both).
2. **Vikram/Kabir:** close the `ADMIN_MFA_SECRET_ENCRYPTION_KEY` validator gap in
   `SecretsStartupValidator.java` before any staging boot — it's the one admin-specific secret
   with zero fail-closed protection today.
3. **Meera (once #1 exists):** write the two missing Dockerfiles (`influora-api/Dockerfile`,
   root `Dockerfile` for the Vite build), extend `.github/workflows/` with a real deploy job, run
   the V1→V39 migration procedure from §3 against the fresh staging DB, and live-verify Redis
   (the thing that's been compile-only-verified since Cycle 7).
4. Re-run the full Cycle-7-style verification (build/test/curl) against the staging host once
   reachable, and only then report "admin panel on staging" as DONE in `SHARED_CONTEXT.md`.
