# Influora — Run Locally

> Updated 2026-07-20. The steps below are the ones **verified working on a Windows + Docker Desktop
> dev machine** this session. Where the old `mvn spring-boot:run` path fails on Windows (JDK 21
> loopback bug — see §3), the Docker path is the reliable one and is what's documented here.
> For production/server deploy use `BLUEPRINT/10-RUN-ON-SERVER-UTHO.md`. Library list: `DEPENDENCIES.md`.

Influora = **3 services in 3 languages** + Docker data services. Each runs in its own terminal.

```
Frontend (TypeScript/Vite)   http://localhost:3000
Core API (Java/Spring Boot)  http://localhost:8080/api/v1      ← run via Docker on Windows
AI service (Python/FastAPI)  http://localhost:8000             ← bind 0.0.0.0 so Docker can reach it
Data (Docker):  MySQL :3307   Redis :6379   ClamAV :3310 (prod only)
```

---

## 0. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **Docker Desktop** | latest, Compose v2 | Backend + data services run here |
| **Node.js** | 20+ | Frontend |
| **Java (JDK)** | **21** (Temurin) | Only needed if you build the backend image / run tests locally |
| **Maven** | 3.9+ | **No `mvnw` wrapper in this repo.** Only needed for local Java builds/tests |
| **Python** | **3.13** | AI service |

Windows: run commands in **PowerShell** or **Git Bash**. Where a step says `source .venv/bin/activate`,
use `.venv\Scripts\activate`.

> **JDK 21 / Maven aren't on PATH by default on this machine.** JDK 21 is at
> `C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot` and Maven at `~/tools/apache-maven-3.9.6`.
> The system PATH may resolve an old Java 8 first — before any `mvn` command, set them explicitly:
> ```bash
> export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
> export PATH="$JAVA_HOME/bin:/c/Users/$USER/tools/apache-maven-3.9.6/bin:$PATH"
> ```

---

## 1. Data services (Docker)

From the repo root:

```bash
docker compose up -d
docker compose ps          # wait until mysql shows "healthy"
```

Starts **MySQL 8** on host port **3307** (container 3306), **Redis 7** on 6379, and **ClamAV** on 3310
(prod profile only — not needed for normal local work). MySQL is created with database
`influora_local`, root password `root` (dev only). Flyway runs its migrations on first backend boot.

---

## 2. AI service — Python / FastAPI (`influora-ai/`)

**Terminal 1.**

```bash
cd influora-ai

python -m venv .venv
source .venv/Scripts/activate        # Windows; macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
python -m playwright install --with-deps chromium   # ~1 GB, one time

# .env already has working dev keys locally. If starting fresh: copy env.example -> .env
# and fill ANTHROPIC_API_KEY / GEMINI_API_KEY / SARVAM_API_KEY.
# Load .env into the shell (config.py reads os.getenv with no load_dotenv):
set -a && source <(tr -d '\r' < .env) && set +a

# BIND 0.0.0.0 — not just localhost. The Dockerized backend calls this service via
# host.docker.internal, which does NOT reach a 127.0.0.1-only bind.
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Required in `.env` before it boots: `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `SARVAM_API_KEY`
(presence-checked; features need real keys). The shared-secret defaults already match the Java dev
defaults — leave them as-is for local dev. Also set `MEERA_ALLOWED_ORIGINS=http://localhost:3000`
so the browser's direct SSE call to `/chat` isn't CORS-blocked.

Verify: `curl http://localhost:8000/healthz` → `{"status":"ok"}`

> **Gemini model:** must be a current model. `gemini-2.5-flash-lite` was retired by Google (404) and
> is already updated to `gemini-2.5-flash` in `app/config.py`. If Gemini calls start 404-ing, that's
> the first thing to check.

---

## 3. Core API — Java / Spring Boot (`influora-api/`) — via Docker

**Terminal 2.** On this Windows machine, `mvn spring-boot:run` fails at startup with
`Unable to establish loopback connection` / `java.net.ConnectException` — a JDK 21-on-Windows NIO
loopback issue (antivirus/VPN/EDR interfering with the internal Unix-domain-socket the JVM uses).
**Run the backend in Docker instead** — it sidesteps the bug entirely and is the verified path.

```bash
# from the repo root — build once (re-run after backend code changes)
docker build -t influora-api ./influora-api

# run, overriding the container-internal hostnames + AI base URLs.
# NOTE the INFLUORA_ prefix on the *_AI_BASE_URL vars — those keys only exist in
# application-prod.yml, so in the dev profile only Spring's relaxed-binding form
# (INFLUORA_VOICE_AI_BASE_URL, not VOICE_AI_BASE_URL) actually overrides them.
docker rm -f influora-api 2>/dev/null
docker run -d --name influora-api \
  --env-file influora-api/.env \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://host.docker.internal:3307/influora_local?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  -e SPRING_DATA_REDIS_HOST=host.docker.internal \
  -e SPRING_DATA_REDIS_PORT=6379 \
  -e INFLUORA_VOICE_AI_BASE_URL=http://host.docker.internal:8000 \
  -e INFLUORA_BRAND_SAFETY_AI_BASE_URL=http://host.docker.internal:8000 \
  -e INFLUORA_TRENDSPARK_AI_BASE_URL=http://host.docker.internal:8000 \
  -e INFLUORA_MEERA_CHAT_AI_BASE_URL=http://host.docker.internal:8000 \
  -e INFLUORA_ANALYZE_SITE_AI_BASE_URL=http://host.docker.internal:8000 \
  -p 8080:8080 influora-api

docker logs -f influora-api      # wait for "Started InfluoraApiApplication"
```

Why the overrides: inside the container, `localhost` is the container itself, not your host — so
MySQL (3307), Redis (6379) and the Python AI service (8000) must be reached via
`host.docker.internal`.

Serves on **http://localhost:8080**, context path **`/api/v1`** (health at `/api/v1/health`).
Flyway applies its migrations on first boot (be patient). Dev profile uses safe dev-default secrets.

Verify: `curl http://localhost:8080/api/v1/health`

> The aggregate health can read `"status":"DOWN"` purely because the **mail** health check fails
> (`535 ... Ip is not whitelisted`) — see the troubleshooting note. The API itself (DB, R2, chat,
> voice) is fully working in that state; only outbound email is blocked.

**To restart later without rebuilding:** `docker start influora-api`
**After backend code changes:** `docker build -t influora-api ./influora-api` then re-run the
`docker run` command above (with the same `-e` overrides).

> If your machine does NOT hit the loopback bug, you can run it natively instead:
> set JAVA_HOME/Maven (see §0), `cd influora-api`, load `.env`
> (`set -a && source <(tr -d '\r' < .env) && set +a`), then `mvn spring-boot:run`. Same result.

---

## 4. Frontend — TypeScript / Vite (repo root)

**Terminal 3.**

```bash
npm ci                                # from package-lock.json (use npm, not pnpm)
cp .env.local.example .env.local      # Windows: copy .env.local.example .env.local
npm run dev
```

Serves on **http://localhost:3000** (`vite.config.ts` overrides Vite's own 5173 default).
`.env.local` points `VITE_API_MODE=live` and `VITE_API_BASE_URL=http://localhost:8080/api/v1`.
Vite bakes `VITE_*` in at start — **restart `npm run dev`** after editing `.env.local`.

---

## 5. Start order & the full local loop

1. `docker compose up -d` → MySQL healthy.
2. Terminal 1: AI service (`uvicorn ... --host 0.0.0.0 --port 8000`).
3. Terminal 2: backend Docker container (§3) → wait for "Started InfluoraApiApplication".
4. Terminal 3: frontend (`npm run dev`).
5. Open http://localhost:3000.

Quick E2E: register/log in as a brand → open **Meera** → paste a product URL (she runs the
`analyze_site` tool and reads the real page) or click the **voice** button for a hands-free chat.

---

## 6. Ports reference

| Service | Host port | URL |
|---|---|---|
| Frontend (Vite) | 3000 | http://localhost:3000 |
| Core API (Java, Docker) | 8080 | http://localhost:8080/api/v1 |
| AI service (Python) | 8000 | http://localhost:8000 |
| MySQL | **3307** | (host 3307 → container 3306) |
| Redis | 6379 | (internal) |
| ClamAV | 3310 | (prod profile only) |

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Backend `mvn spring-boot:run` dies with `Unable to establish loopback connection` / `ConnectException` | JDK 21-on-Windows NIO loopback bug. **Run the backend in Docker** (§3) instead. |
| Backend health `"status":"DOWN"` but everything works | The **mail** health check fails because MSG91/SMTP requires your machine's **public IP to be whitelisted** — `535 5.7.8 <ip> - Ip is not whitelisted`. Your IP is dynamic, so it changes; add the new IP at dashboard.msg91.com. The rest of the API is unaffected. |
| Meera voice / brand-safety / analyze-site 500 or "transport failure" from the Docker backend | The `*_AI_BASE_URL` env vars must use the **`INFLUORA_` prefix** (relaxed binding) AND point at `host.docker.internal:8000`, and the AI service must be bound to `--host 0.0.0.0`. See §2 and §3. |
| Meera can't reach the AI at all / `POST /chat` fails from the browser | Set `MEERA_ALLOWED_ORIGINS=http://localhost:3000` in `influora-ai/.env` (CORS for the browser→Python SSE call), restart the AI service. |
| Gemini calls 404 / analyze-site "could not classify" | The configured Gemini model was retired. Current working model is `gemini-2.5-flash` (`influora-ai/app/config.py`). |
| `mvn: command not found` / wrong Java version | Maven isn't on PATH and the default Java may be 8. Export `JAVA_HOME` (JDK 21) + Maven bin — see §0. No `mvnw` wrapper exists. |
| DB connection refused from the container | MySQL is on host **3307** (not 3306) and must be reached via `host.docker.internal` from inside the container (§3). |
| Frontend loads but API calls fail / CORS | Backend not up on :8080, or `.env.local` `VITE_API_BASE_URL` mismatched. Restart `npm run dev` after any `.env.local` edit. |
| Flyway fails on boot | Local reset (dev only, erases data): `docker compose down -v` then `docker compose up -d` and re-run the backend. |
| Port already in use (8080/8000/3000/3307) | Stop the holder or change the port (`--port` for uvicorn/vite, `-p` for the docker run). |

---

## 8. Run each service in Docker instead (optional)

Every service has a working `Dockerfile`. See `RUN-LOCAL.md` §3 for the backend; the AI service and
frontend images build the same way (`docker build -t influora-ai ./influora-ai`,
`docker build -t influora-web --build-arg VITE_API_BASE_URL=... .`). For a single-command
production-style stack, use `docker-compose.prod.yml` in `BLUEPRINT/10-RUN-ON-SERVER-UTHO.md`.
