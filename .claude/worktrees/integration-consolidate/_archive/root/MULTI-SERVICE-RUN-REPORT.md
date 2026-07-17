# Influora — Multi-Service Local Run Report

| | |
|---|---|
| **Date** | 2026-07-08 |
| **Prepared by** | Tara — Operations & Reporting Lead |
| **Prepared for** | Priya — CTO |
| **Exercise** | Start the three Influora services (Frontend, API, AI) on distinct ports and verify each at runtime |
| **Runtime verification by** | Meera — DB/DevOps Engineer |
| **Sources** | `outputs/meera-run-results.txt`; Meera's handoff block in `SHARED_CONTEXT.md`; repo git log/status |
| **Environment** | Linux sandbox — Node 22.22.3 / npm 10.9.8; Python 3.10.12; JDK 11.0.31 only; no Maven; no MySQL |

---

## 1. Executive Summary

Two of the three Influora services started and passed live health checks in the sandbox; the third is environment-blocked (not a code defect).

- **Frontend (:3000) — RUNS.** Served HTTP 200 with a valid Vite index page after two sandbox-only workarounds that would not be needed on a clean, same-OS install.
- **AI service (:8000) — RUNS WITH EXPECTED ERRORS.** Boots cleanly and answers health routes; real provider calls fail with HTTP 401 because the `.env` holds placeholder keys by design — this is the expected "no real API key" behavior, not a defect.
- **API (:8080) — ENVIRONMENT-BLOCKED.** Cannot be built or run here because the sandbox lacks JDK 21, Maven, and MySQL. No application bug is implicated.

Net result: the run exercise succeeded for everything the sandbox can support. Closing out the API leg and exercising live AI calls both depend on provisioning, not on code changes.

---

## 2. Port Assignment & Status

| Service | Stack | Port | Status | Health check result |
|---|---|---|---|---|
| Frontend | Vite 6 + React 19 | 3000 | RUNS | `curl /` -> HTTP 200, 682-byte valid Vite `index.html`; log: "VITE v6.4.2 ready in 984 ms" |
| AI service | FastAPI / uvicorn (`influora-ai`) | 8000 | RUNS (expected errors) | `curl /healthz` -> 200 `{"status":"ok"}`; `curl /readyz` -> 200, all keys present; real Anthropic call -> **HTTP 401** (placeholder key, by design) |
| API | Spring Boot 3.3.5, Java 21, Maven (`influora-api`) | 8080 | BLOCKED (environment) | Not runnable in sandbox — needs JDK 21 (has 11), Maven, and MySQL |

---

## 3. Per-Service Detail

### 3.1 Frontend — Vite 6 + React 19 (port 3000) — RUNS

- **Directory:** repo root (`/New Influora`)
- **Command:** `npm run dev` (Vite; `vite.config.ts` -> port 3000, host true)
- **Evidence:** `curl http://localhost:3000/` returned HTTP 200 with a 682-byte body — a valid Vite `index.html` (`<!doctype html>`, `/@vite/client` and `/@react-refresh` injected). Startup log: `VITE v6.4.2 ready in 984 ms` bound on `http://localhost:3000/`.
- **Caveats — two sandbox-only workarounds (platform/mount artifacts, not app bugs):**
  1. `node_modules` had been installed on Windows, so the Linux native rollup binary was missing (`Cannot find module @rollup/rollup-linux-x64-gnu` — a known npm optional-deps issue). Resolved with `npm install @rollup/rollup-linux-x64-gnu --no-save`.
  2. Vite could not write its dep-optimizer cache to the read-restricted mounted `node_modules/.vite` (`EPERM ... unlink`). Resolved by redirecting Vite's `cacheDir` to `/tmp` via a temporary config, removed afterward.
  On a normal dev box (a clean `npm install` on the target OS) neither workaround is required.

### 3.2 AI Service — FastAPI / uvicorn (port 8000) — RUNS WITH EXPECTED ERRORS

- **Directory:** `/New Influora/influora-ai` — entry `app.main:app`
- **Command:** `python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --env-file .env`
- **Setup:** `cp .env.example .env` to satisfy the boot-secret presence check; normalized Windows CRLF line endings in `.env` (which had caused `Unknown level: 'INFO\r'`); installed minimal runtime deps (fastapi, uvicorn[standard], pydantic, pyjwt[crypto], httpx, anthropic, google-genai, python-multipart; Playwright skipped as unnecessary here).
- **Boot-secret logic verified both ways** (`app/config.py::require_boot_secrets`): without `.env`, six required secrets are reported missing and the app refuses to boot; with the placeholder `.env`, none are missing and the service boots.
- **Evidence:**
  - `curl /healthz` -> HTTP 200 `{"status":"ok"}`
  - `curl /readyz` -> HTTP 200, reporting key **presence/shape only** (never values): anthropic, gemini, sarvam, internal_hmac, service_token_signing_key, and jwks/dev-secret all `true`; `prompt_version` `meera-2026.07.05`; `claude_model` `claude-sonnet-4-5-20250929`; `gemini_model` `gemini-2.5-flash-lite`.
  - Startup log: `influora-ai booted: env=dev ...`; `Uvicorn running on http://0.0.0.0:8000`.
- **Expected error (not a blocker):** the `.env` values are literal placeholders (e.g. `ANTHROPIC_API_KEY=sk-ant-api03-REPLACE_WITH_YOUR_ANTHROPIC_KEY`). They pass the presence check but are not valid credentials. Proven directly against the live provider: `POST https://api.anthropic.com/v1/messages` with the placeholder key -> **HTTP 401 Unauthorized**. So the service boots and serves unauthenticated health routes, but any real LLM/chat/analyze/voice call will 401 at the provider until real keys are supplied. This is the expected "no API key added" behavior.

### 3.3 API — Spring Boot 3.3.5, Java 21 (port 8080) — ENVIRONMENT-BLOCKED

- **Directory:** `/New Influora/influora-api`
- **Config:** `server.port=${SERVER_PORT:8080}`, servlet context-path `/api/v1`, datasource `jdbc:mysql://localhost:3306/Influora_AI`.
- **Blocking reasons (all confirmed):**
  1. **JDK version.** `pom.xml` pins `<java.version>21</java.version>` and Spring Boot 3.3.5 requires Java 17+. The sandbox has only JDK 11.0.31 — cannot compile or run.
  2. **Maven absent.** `mvn` is not installed and no `mvnw` wrapper is present in `influora-api/` — cannot build or package.
  3. **MySQL absent.** No MySQL server is available for the required `mysql://localhost:3306/Influora_AI` datasource.
  4. **No runnable artifact.** `target/` holds only stale classes from a prior host build; there is no packaged/fat jar to `java -jar`, and JDK 11 could not load Java 21 bytecode regardless.
- A prior JVM attempt in the sandbox also OOM-crashed (`hs_err_pid26588.log`: "insufficient memory for the Java Runtime Environment to continue"). None of these are application defects — they are environment gaps.

---

## 4. Missing API Keys & Expected Errors

Nothing in this section represents a bug. Both remaining gaps are provisioning matters.

**AI service — placeholder credentials.** The AI service's `.env` was created from `.env.example`, whose keys are deliberate placeholders. They satisfy the startup presence check (so the service boots and health routes work) but are not real credentials, so any actual provider call returns HTTP 401 by design. To exercise live chat / analyze / voice flows, replace the following with real values:

| Env var | Provider | Current value |
|---|---|---|
| `ANTHROPIC_API_KEY` | Anthropic (Claude) | placeholder `sk-ant-api03-REPLACE_...` |
| `GEMINI_API_KEY` | Google Gemini | placeholder `AIzaSy-REPLACE_...` |
| `SARVAM_API_KEY` | Sarvam | placeholder `REPLACE_...` |

Note: full end-to-end auth (Spring -> Python service token) additionally requires either `influora-api` to be running or the local `DEV_SHARED_JWT_SECRET` path.

**API — infrastructure prerequisites.** The Spring Boot API is not blocked by keys but by tooling and infrastructure that the sandbox does not provide:

- **JDK 21** (sandbox has only JDK 11; Spring Boot 3.3.5 needs 17+)
- **Maven** (or a committed `mvnw` wrapper) to build and package
- **MySQL** instance for `jdbc:mysql://localhost:3306/Influora_AI` (or a Docker/Testcontainers equivalent)

---

## 5. Recommendations / Next Steps

1. **Supply real AI provider keys.** Populate `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, and `SARVAM_API_KEY` with valid credentials to move the AI service from "boots + health OK" to fully exercising live chat/analyze/voice calls.
2. **Provision the API's runtime.** Install JDK 21 and Maven (or add an `mvnw` wrapper), and stand up a MySQL instance (or Docker/Testcontainers), then re-run the full three-service integration on ports 3000 / 8000 / 8080 together.
3. **Do a clean, same-OS frontend install.** Run a fresh `npm install` on the target OS (dev box or CI) so the two sandbox workarounds — the missing `@rollup/rollup-linux-x64-gnu` native binary and the `/tmp` Vite cache redirect — are not needed; confirm the 200 there.

---

## 6. Sign-off

Report compiled by **Tara (Operations & Reporting Lead)** on **2026-07-08** from Meera's runtime verification for **Priya (CTO)**. Read-only status report — no code, configuration, or decisions were changed in its preparation.
