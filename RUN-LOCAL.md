# Influora — Run Locally (All Three Languages)

> Generated 2026-07-16. How to install and run the full stack on your own machine for development.
> For **production/server** deploy, use `BLUEPRINT/10-RUN-ON-SERVER-UTHO.md` instead.
> For the library list, see `DEPENDENCIES.md`.

Influora = **3 services in 3 languages** + Docker data services. You run each service in its own terminal.

```
Frontend (TypeScript/Vite)  http://localhost:3000  ─► talks to ─┐
Core API (Java/Spring)      http://localhost:8080/api/v1  ◄──────┤
AI service (Python/FastAPI) http://localhost:8000  ◄────────────┘
                                    │
Data (Docker):  MySQL :3306   ClamAV :3310   (Redis :6379 optional)
```

> `vite.config.ts` serves on **3000** by default, not Vite's own framework-default 5173 — this doc
> previously said 5173 everywhere, which also meant `influora-api`'s default CORS/web-base-url
> config (5173-only) silently rejected the real frontend. See step 2's `.env` for the fix.

---

## 0. Prerequisites — install these first

| Tool | Version | Check | Get it |
|---|---|---|---|
| **Docker Desktop** | latest, with Compose v2 | `docker compose version` | docker.com |
| **Node.js** | 20+ | `node -v` | nodejs.org |
| **Java (JDK)** | **21** (Temurin) | `java -version` | adoptium.net |
| **Maven** | 3.9+ | `mvn -v` | maven.apache.org — **required, there is no `mvnw` wrapper in this repo** |
| **Python** | **3.13** | `python --version` | python.org |
| **Git** | any | `git --version` | git-scm.com |

> Windows users: run the commands below in **PowerShell** or **Git Bash**. Where a step says `source .venv/bin/activate`, use `.venv\Scripts\activate` instead.

---

## 1. Start the data services (Docker)

From the repo root:

```bash
docker compose up -d
docker compose ps          # wait until mysql shows "healthy"
```

This starts **MySQL 8** (port 3306) and **ClamAV** (port 3310). MySQL is created with database `influora`, user `influora` / password `influora` (dev only — see `docker-compose.yml`).

> ClamAV downloads its virus database on first start and can take ~90s to become healthy. You don't need it for most local work — the Java service uses a **no-op** malware scanner unless you run the `prod` profile.

---

## 2. Core API — Java / Spring Boot (`influora-api/`)

**Terminal 1.** This must be up before the frontend can log in.

```bash
cd influora-api

# 1. Create your env file from the template
cp .env.example .env        # Windows: copy .env.example .env

# 2. Load .env into the shell — there is no spring-dotenv/envFile wiring, so plain
#    `mvn spring-boot:run` does NOT read .env on its own. Without this, SPRING_PROFILES_ACTIVE
#    never reaches the JVM, the app boots with no active profile, and CompanyTaxStartupValidator/
#    SecretsStartupValidator fail closed as if this were a real deploy.
set -a && source .env && set +a   # Windows Git Bash; if .env has CRLF endings strip \r first

# 3. Run it (dev profile). Maven downloads dependencies on first run.
mvn spring-boot:run
```

What happens on boot:
- Flyway applies **56 migrations** to the MySQL database (first boot only — be patient).
- The dev profile uses safe dev-default secrets, so it starts without you generating anything.
- Serves on **http://localhost:8080**, context path **`/api/v1`** (so health is at `/api/v1/health`).

Verify:
```bash
curl http://localhost:8080/api/v1/health
```

> If `mvn` is "not found": Maven isn't installed (this repo has no wrapper). Install it, or run the service via Docker: `docker build -t influora-api ./influora-api && docker run --env-file influora-api/.env -p 8080:8080 influora-api`.

---

## 3. AI service — Python / FastAPI (`influora-ai/`)

**Terminal 2.**

```bash
cd influora-ai

# 1. Create an isolated environment
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate

# 2. Install libraries
pip install -r requirements.txt

# 3. Install the Chromium browser Playwright needs (~1 GB, one time)
python -m playwright install --with-deps chromium

# 4. Create your env file
cp .env.example .env               # Windows: copy .env.example .env

# 5. Load .env into the shell — app/config.py reads secrets via plain os.getenv() with no
#    load_dotenv() call, so a bare `uvicorn` run hits a false "missing secrets" error even with
#    a fully populated .env.
set -a && source .env && set +a

# 6. Run it
uvicorn app.main:app --reload --port 8000
```

**Before it will boot**, `app/main.py` requires these to be present in `.env` (any non-empty value passes the presence check, but the AI features only actually work with real keys):

- `ANTHROPIC_API_KEY` — from https://console.anthropic.com/settings/keys
- `GEMINI_API_KEY` — from https://aistudio.google.com/app/apikey
- `SARVAM_API_KEY` — from https://dashboard.sarvam.ai/

The shared-secret defaults in `.env.example` (`INTERNAL_HMAC_KEY`, `SERVICE_TOKEN_SIGNING_KEY`, `DEV_SHARED_JWT_SECRET`) **already match the Java dev defaults** — leave them as-is for local dev so Python↔Java calls authenticate.

Verify:
```bash
curl http://localhost:8000/healthz
```

> For dev/test tooling (the eval harness), also `pip install -r requirements-dev.txt` (adds pytest).

---

## 4. Frontend — TypeScript / Vite (`src/`, root project)

**Terminal 3.**

```bash
# from the repo root
npm ci                              # installs from package-lock.json (use npm, not pnpm)

cp .env.local.example .env.local    # Windows: copy .env.local.example .env.local

npm run dev
```

The dev server prints a local URL — **http://localhost:3000** (this project's `vite.config.ts` overrides Vite's own 5173 default). `.env.local` is preconfigured to point `VITE_API_BASE_URL` at `http://localhost:8080/api/v1`, so it talks to your local Java API.

> Reminder: Vite bakes `VITE_*` values in at build/start time. If you change `.env.local`, **restart `npm run dev`** for it to take effect.

---

## 5. Start order & the full local loop

1. `docker compose up -d` → wait for MySQL healthy.
2. Terminal 1: `influora-api` (Java) — wait for Flyway + "Started" log.
3. Terminal 2: `influora-ai` (Python).
4. Terminal 3: frontend (`npm run dev`).
5. Open the Vite URL in your browser.

A quick end-to-end check: register → log in → send one Meera chat message. That exercises the DB, the JWT/JWKS chain, and the Python↔Java internal call all at once.

---

## 6. Ports reference

| Service | Port | URL |
|---|---|---|
| Frontend (Vite dev) | 3000 | http://localhost:3000 |
| Core API (Java) | 8080 | http://localhost:8080/api/v1 |
| AI service (Python) | 8000 | http://localhost:8000 |
| MySQL | 3306 | (internal) |
| ClamAV | 3310 | (internal, prod profile) |
| Redis | 6379 | (optional / prod) |

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `mvn: command not found` | Maven not installed. This repo has **no `mvnw`**. Install Maven 3.9+, or use the Docker build. |
| Java starts then exits with a secrets error | You set `APP_ENV=prod` locally. For local dev keep `APP_ENV=dev` (default in `.env.example`) — prod turns on strict secret validation. |
| Python exits: `missing required secrets/config: ...` | Fill `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `SARVAM_API_KEY` in `influora-ai/.env`. |
| Frontend loads but every API call fails / CORS error | The Java API isn't running, or `.env.local` `VITE_API_BASE_URL` doesn't match where it's serving. Restart `npm run dev` after any `.env.local` edit. |
| Meera chat returns 401 / `SIGNATURE_MISMATCH` | The three shared secrets in `influora-ai/.env` don't match the Java side. For dev, use the untouched `.env.example` defaults on both sides. |
| Flyway fails on boot | The `influora` database/schema is in a bad state. Easiest local reset: `docker compose down -v` (deletes the MySQL volume) then `docker compose up -d` and restart the API. **Only do this locally — `-v` erases the database.** |
| `playwright` errors about a missing browser | You skipped `python -m playwright install --with-deps chromium`. Run it inside the activated venv. |
| Port already in use | Something else holds 8080/8000/5173/3306. Stop it, or change the port (`--port` for uvicorn, `SERVER_PORT` env for Java, `--port` for `vite`). |

---

## 8. Run each service in Docker instead (optional)

Every service has a working `Dockerfile`, so you can skip installing Node/Java/Python locally and just build images:

```bash
# Core API
docker build -t influora-api ./influora-api
docker run --env-file influora-api/.env -p 8080:8080 influora-api

# AI service
docker build -t influora-ai ./influora-ai
docker run --env-file influora-ai/.env -p 8000:8000 influora-ai

# Frontend (VITE_* values are build args, not runtime env!)
docker build -t influora-web \
  --build-arg VITE_API_BASE_URL=http://localhost:8080/api/v1 \
  --build-arg VITE_API_MODE=live .
docker run -p 8081:80 influora-web
```

For a single-command full production-style stack, use the `docker-compose.prod.yml` template in `BLUEPRINT/10-RUN-ON-SERVER-UTHO.md`.
