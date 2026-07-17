# How to Run Influora Locally (Java · Python · Frontend)

> Step-by-step to run all three services on your own machine. Commands verified from `pom.xml`, `package.json`, `requirements.txt`, Dockerfiles, and `application*.yml`.
> Lead: Meera (DevOps).

---

## 0. What you need installed

| Tool | Version | Used by |
|---|---|---|
| **JDK** | **21** (Temurin) | Core API (Spring Boot 3.3.5) |
| **Maven** | 3.9+ | Build/run the API |
| **Python** | **3.13** (3.11+ works) | AI service (FastAPI) |
| **Node.js** | **20** | Frontend (Vite 6) |
| **Docker** | any recent | Local MySQL 8 |

Ports used: **5173** (frontend dev), **8080** (API), **8000** (AI), **3306** (MySQL).

Start order: **MySQL → API → AI → Frontend.**

---

## 1. Database (MySQL 8 via Docker)

The repo ships a compose file that runs MySQL only:

```bash
docker compose up -d mysql
```

This creates database `influora` (user `influora` / pass `influora`, root `root`) on `localhost:3306`.
> Note: `application.yml`'s default datasource URL points at a DB named `Influora_AI`. Either create that DB, or set `SPRING_DATASOURCE_URL` to match the compose DB (`influora`) — see step 2.

Flyway runs all **56 migrations automatically** the first time the API boots — you do not create tables by hand.

---

## 2. Core API — Java / Spring Boot (`/influora-api`)

The API reads secrets from environment variables. For local dev, use the **`dev`** profile (it disables email verification / OTP so you can register freely) and point the datasource at your Docker MySQL.

```bash
cd influora-api

# Minimum env for local dev (export in your shell, or use an .env runner):
export SPRING_PROFILES_ACTIVE=dev
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/influora?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export SPRING_DATASOURCE_USERNAME=influora
export SPRING_DATASOURCE_PASSWORD=influora
# Dev-only signing secrets (any long random strings are fine locally):
export JWT_ACCESS_SECRET=dev-access-secret-please-change-0123456789
export JWT_REFRESH_SECRET=dev-refresh-secret-please-change-0123456789

# Run it:
mvn spring-boot:run
```

- API comes up at **http://localhost:8080**, context path **`/api/v1`**.
- Health check: `curl http://localhost:8080/api/v1/health` → should return OK.
- Third-party features (Razorpay, Meta, MSG91, R2, AI) stay dormant until you add their keys — core auth/campaigns/deals work without them locally.
- Run the test suite anytime: `mvn test` (953 tests).

> If `SecretsStartupValidator` complains at boot, it's telling you a required secret is missing — add it to your env and restart.

---

## 3. AI service — Python / FastAPI (`/influora-ai`)

```bash
cd influora-ai

python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python -m playwright install chromium                  # needed by /analyze_site

# Minimum env (dev). Provide at least one provider key to actually call a model:
export APP_ENV=development
export ANTHROPIC_API_KEY=sk-ant-...        # or GEMINI_API_KEY / SARVAM_API_KEY
export SPRING_INTERNAL_BASE_URL=http://localhost:8080/api/v1
export SPRING_JWKS_URL=http://localhost:8080/api/v1/.well-known/jwks.json
export SPRING_JWT_ISSUER=influora-api
export SERVICE_TOKEN_SIGNING_KEY=dev-internal-secret      # must match API's INTERNAL_SERVICE_TOKEN_SECRET
export INTERNAL_HMAC_KEY=dev-hmac-secret                  # must match API's INTERNAL_REQUEST_HMAC_SECRET

# Run it:
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- AI service at **http://localhost:8000**; health: `curl http://localhost:8000/healthz`.
- **The AI service never talks to the browser directly for privileged work** — the API brokers it. So the two shared secrets above (`SERVICE_TOKEN_SIGNING_KEY`, `INTERNAL_HMAC_KEY`) **must match** the API's values, or every AI call returns 401/403.
- Run AI tests: `pytest` (17 suites).

> You can skip the AI service entirely if you're not testing Meera/TrendSpark — the rest of the app runs fine without it.

---

## 4. Frontend — React / Vite (`/` root)

```bash
# from repo root
cp .env.local.example .env.local
```
`.env.local` (local dev against your API):
```env
VITE_API_MODE=live
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_MEERA_STREAM_URL=http://localhost:8080/api/v1/stream
```
Then:
```bash
npm ci
npm run dev
```
- Opens at **http://localhost:5173**.
- **Mock vs live:** set `VITE_API_MODE=mock` to run the UI with demo data and **no backend at all** (fastest way to click around). Set `live` to hit the real API from steps 2–3.
- Other scripts: `npm run typecheck`, `npm test` (Vitest, 177 cases), `npm run build`, `npm run preview`.

---

## 5. Quick verification

| Check | Command | Expect |
|---|---|---|
| MySQL up | `docker ps` | `influora-mysql` running |
| API up | `curl localhost:8080/api/v1/health` | OK |
| AI up | `curl localhost:8000/healthz` | 200 |
| Frontend | open `localhost:5173` | landing page |
| End-to-end | register a brand in the UI (dev profile skips OTP) | account created |

---

## 6. Common issues

- **`mvn: not found`** → install Maven (or use the Docker build in `10-RUN-ON-SERVER.md`).
- **API won't start / secret error** → `SecretsStartupValidator` needs the missing env var; add it.
- **AI returns 401/403** → the shared `SERVICE_TOKEN_SIGNING_KEY` / `INTERNAL_HMAC_KEY` don't match the API's internal secrets.
- **Frontend shows demo data** → you're in `VITE_API_MODE=mock`; switch to `live` and restart `npm run dev`.
- **DB name mismatch** → align `SPRING_DATASOURCE_URL` with the DB the compose file created (`influora`).
- **Flyway takes a while on first boot** → normal; it runs 56 migrations sequentially.
