# INFLUORA — Local Setup, API Keys & Run Guide

> **Author:** Priya (CTO) · **Date:** 2026-07-08
> One-stop reference: where every secret is stored, exact commands to run all 3 services, and how OTP/email actually behaves locally today.

---

## 1. The system is 3 separate services

| Service | Folder | Runtime | Port | Env file |
|---|---|---|---|---|
| **Frontend** (brand + creator UI) | repo root | Vite + React | 5173 (or auto-assigned) | `.env.local` |
| **Backend API** (Spring Boot + MySQL) | `influora-api/` | Java 21 | 8080 | `influora-api/.env` |
| **AI service** (Meera reasoner + BrandSafety) | `influora-ai/` | Python FastAPI | 8000 | `influora-ai/.env` |

**Why 3 separate `.env` files, not one:** blast-radius isolation (a security rule the team enforces). The Python AI service must NEVER hold DB, payment, or user-JWT keys — only its own LLM keys. Do not merge these files or copy secrets between them beyond the specific cross-service pairs listed in §7.

**Golden rule:** secrets go in `.env` files only, never in frontend `VITE_*` variables (those ship straight to the browser) — except deliberately-public values like the API base URL.

---

## 2. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Java | 21 | `influora-api` |
| Node | 18+ | frontend |
| Python | 3.11+ | `influora-ai` |
| Docker Desktop | any recent | easiest way to run MySQL 8 |
| Maven | 3.9+ | building/running `influora-api` |

If `mvn` isn't on your PATH, this repo already has a cached copy checked into your local Maven wrapper cache — use the full path shown in §4 instead of installing Maven separately.

---

## 3. Step 1 — Start MySQL

```bash
docker compose up -d mysql
```

This starts MySQL 8 on `localhost:3306` with database `influora`, user `influora`, password `influora` — matching `influora-api/.env.example`'s defaults exactly. No manual schema setup needed: Flyway auto-runs all migrations (V1 through V31) the first time Spring Boot starts.

**No Docker?** Install MySQL 8 locally and create the matching database/user yourself:
```sql
CREATE DATABASE influora CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'influora'@'%' IDENTIFIED BY 'influora';
GRANT ALL PRIVILEGES ON influora.* TO 'influora'@'%';
```

---

## 4. Step 2 — Backend API (`influora-api/`)

### 4.1 Create your env file
```bash
cp influora-api/.env.example influora-api/.env
```

### 4.2 What to fill in — full key-by-key breakdown

**Tier 1 — signing/encryption secrets.** Already have working dev defaults, so the app boots as-is locally. `SecretsStartupValidator` refuses to boot in any non-`dev` profile until every one of these is a real, distinct value — so change them before deploying anywhere shared:

| Key | Purpose | How to generate |
|---|---|---|
| `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` | User login session tokens | `openssl rand -base64 32` (run twice, two different values) |
| `MEERA_STREAM_SIGNING_SECRET` | Meera chat SSE stream token | `openssl rand -base64 32` |
| `INTERNAL_SERVICE_TOKEN_SECRET`, `INTERNAL_REQUEST_HMAC_SECRET` | Python→Spring internal mesh auth (must match `influora-ai/.env`, see §7) | `openssl rand -base64 32` each |
| `BRAND_SAFETY_SERVICE_TOKEN_SECRET` | Legacy field, kept only for boot-validation continuity — signing now happens via the JWKS keypair below | leave the committed dev value, or any ≥32-char string |
| `JWKS_PRIVATE_KEY_PEM` / `JWKS_PUBLIC_KEY_PEM` / `JWKS_KEY_ID` | Spring's real signing keypair for ALL Spring→Python auth (brand-safety + Meera stream) | committed dev keypair works out of the box locally; generate your own for anything shared: |
```bash
openssl ecparam -name prime256v1 -genkey -noout -out ec-private-sec1.pem
openssl pkcs8 -topk8 -nocrypt -in ec-private-sec1.pem -out ec-private-pkcs8.pem
openssl ec -in ec-private-sec1.pem -pubout -out ec-public.pem
```
Then paste each PEM's contents as ONE line in `.env`, replacing real newlines with the literal two characters `\n` (see the example already in `.env.example` for the exact format).

`SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` — leave as committed if you used the docker-compose MySQL above.

**Tier 2 — real 3rd-party keys, add only for the feature you want to exercise:**

| Key(s) | Feature | Where to get it | If left blank |
|---|---|---|---|
| `MSG91_*` (see §6 below — full OTP section) | Email OTP / notifications | msg91.com dashboard | **Doesn't matter for local dev — see §6, this isn't actually called yet.** |
| `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_ENDPOINT`, `R2_BUCKET_NAME` | File uploads, contract PDFs | Cloudflare dashboard → R2 → Manage R2 API Tokens | Upload/PDF-storage endpoints will error |
| `SHOPIFY_API_KEY`, `SHOPIFY_API_SECRET`, `SHOPIFY_WEBHOOK_SECRET`, `SHOPIFY_TOKEN_ENCRYPTION_KEY` | Shopify store connect | Shopify Partners dashboard → create a **custom app** (the free path, no $99/mo fee). Encryption key = `openssl rand -base64 32` | Shopify connect UI will error on submit |
| `CONVERSION_WEBHOOK_TOKEN_ENCRYPTION_KEY` | Signing secret encryption for brand commerce-backend webhooks | `openssl rand -base64 32` — must be a real random value, not a placeholder | Webhook secret generation endpoint will error |

Razorpay (payments) and Meta (Instagram OAuth) keys are configured in `application.yml` with placeholders — these are real-launch (E5) items, not needed to run the app locally with mock/demo data.

### 4.3 Build & run — exact commands

```bash
cd influora-api

# Option A: mvn is on PATH
mvn spring-boot:run

# Option B: use the cached wrapper Maven (no install needed)
"/c/Users/Sage world/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn.cmd" spring-boot:run
```

Other useful commands:
```bash
mvn -o clean test          # run the full backend test suite offline
mvn -o clean compile       # just check it compiles
mvn -o spring-boot:run -Dspring-boot.run.profiles=dev   # explicit dev profile
```

**Confirm it's up:**
```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/api/v1/.well-known/jwks.json   # public signing key, should return JSON
```

---

## 5. Step 3 — AI service (`influora-ai/`)

### 5.1 Create your env file
```bash
cp influora-ai/.env.example influora-ai/.env
```

### 5.2 The one key that actually matters
| Key | Purpose | Where to get it |
|---|---|---|
| `ANTHROPIC_API_KEY` | Powers Meera (AI assistant) + BrandSafety scoring — the core AI feature | https://console.anthropic.com/settings/keys |

Optional, only needed for specific fallback/voice features:
| Key | Purpose | Where to get it |
|---|---|---|
| `GEMINI_API_KEY` | Fallback LLM provider | https://aistudio.google.com/app/apikey |
| `SARVAM_API_KEY` | Voice / Indic-language features | https://dashboard.sarvam.ai/ |

### 5.3 Leave these alone (already correct for local dev)
- `SPRING_JWKS_URL=http://localhost:8080/api/v1/.well-known/jwks.json` — points at your locally-running backend's public key endpoint. This is the exact same verification path used in production.
- `INTERNAL_HMAC_KEY`, `SERVICE_TOKEN_SIGNING_KEY` — must byte-for-byte match the backend's `.env` (see §7). Committed defaults on both sides already match.
- `SPRING_INTERNAL_BASE_URL=http://localhost:8080/api/v1`

### 5.4 Build & run — exact commands

```bash
cd influora-ai

# create + activate a virtual environment (only once)
python -m venv .venv

# Windows (Git Bash / PowerShell):
.venv/Scripts/activate
# macOS/Linux:
source .venv/bin/activate

# install dependencies
pip install -r requirements.txt

# run the dev server (auto-reloads on file changes)
uvicorn app.main:app --port 8000 --reload
```

Other useful commands:
```bash
# run tests
.venv/Scripts/python.exe -m pytest -q          # Windows
./.venv/bin/python -m pytest -q                # macOS/Linux

# quick boot sanity check without starting the server
python -c "import app.main"
```

**Confirm it's up:**
```bash
curl http://localhost:8000/health
```

---

## 6. Step 4 — Frontend (repo root)

### 6.1 Create your env file
```bash
cp .env.local.example .env.local
```
Defaults are already correct for local dev:
```
VITE_API_MODE=live                              # or 'mock' to run the UI with zero backend
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_MEERA_STREAM_URL=https://ai.influora.internal   # only used for direct-to-Python SSE, optional locally
```
**No secrets go here** — this file ships to the browser.

### 6.2 Build & run — exact commands
```bash
npm install
npm run dev          # dev server with hot reload
npm run build         # production build
npm run preview       # preview a production build locally
npm run lint          # eslint
```

The dev server picks its own port (Vite prints it to the console, e.g. `http://localhost:5173/`).

---

## 7. Email OTP — how it ACTUALLY behaves right now (read this before testing registration)

There are **two completely different OTP mechanisms** in this codebase — don't confuse them:

### 7.1 Brand registration email OTP — **console-log stub, MSG91 NOT wired yet**

When a brand registers (`POST /auth/brand/send-email-otp` or the 3-step register form), the backend:
1. Generates a real 6-digit OTP and stores its hash in MySQL (`email_otp_challenges` table, 5-minute expiry).
2. **Prints the OTP to the Spring Boot console log** — literally: `BrandEmailOtpService.java` logs `[dev] Brand email OTP for <email>: <code>`.
3. Has a `// TODO: MSG91 Email API` comment right where the real send would go — **no email is actually sent today, regardless of what you put in `MSG91_*` env vars.**

**To test brand registration locally:**
1. Fill out the register form in the browser as normal.
2. Watch the **terminal running `mvn spring-boot:run`** — the moment you submit, you'll see a line like:
   ```
   INFO ... [dev] Brand email OTP for you@example.com: 482910
   ```
3. Copy that 6-digit code into the OTP field in the UI.

This means you can fully test the registration flow with **zero MSG91 configuration** — just keep an eye on the backend console.

### 7.2 Creator phone/SMS OTP — separate MSG91 SMS flow

Creator login uses a different channel entirely (SMS/widget via MSG91's SMS API, `msg91.auth-key`/`sender-id`/`widget-id`) — not the email flow above, and not yet wired to the local dev-log stub in the same way. See `docs/MSG91-EMAIL-OTP.md` for the full spec if/when you need to wire this up for real.

### 7.3 If you want REAL email sending to actually work

As of this build, it doesn't — `BrandEmailOtpService` needs the `// TODO` completed (calling `Msg91EmailClient`, which already exists and is implemented, just not called from this service yet). If you need real emails locally:
1. Get MSG91 dashboard access → Email → API Keys → copy the **`token-auth`** value (NOT the SMS `auth-key`, they're different credentials) → set `MSG91_TOKEN_AUTH` in `influora-api/.env`.
2. Create/confirm an email template named `otpman` (or set `MSG91_EMAIL_TEMPLATE_ID`) with a `{{otp}}` variable in the MSG91 dashboard.
3. Set `MSG91_EMAIL_DOMAIN`, `MSG91_FROM_EMAIL`, `MSG91_FROM_NAME`.
4. Wire `BrandEmailOtpService.sendOtp()` to actually call `Msg91EmailClient` instead of (or in addition to) the console log — this is a real code change, not just config, since the call is currently commented out as a TODO.

Full field reference: `docs/MSG91-EMAIL-OTP.md`.

---

## 8. Cross-service secret pairs that MUST match exactly

These are the only values shared between `influora-api/.env` and `influora-ai/.env` — get any of these mismatched and the two services silently reject each other's internal calls:

| `influora-ai/.env` | `influora-api/.env` | Breaks if mismatched |
|---|---|---|
| `INTERNAL_HMAC_KEY` | `INTERNAL_REQUEST_HMAC_SECRET` | Every Python→Spring internal call → `SIGNATURE_MISMATCH` |
| `SERVICE_TOKEN_SIGNING_KEY` | `INTERNAL_SERVICE_TOKEN_SECRET` | Python→Spring service token rejected before any request runs |
| `SPRING_JWKS_URL` (points AT) | `GET /.well-known/jwks.json` (served FROM `JWKS_PRIVATE_KEY_PEM`/`JWKS_PUBLIC_KEY_PEM`) | Spring→Python auth (brand-safety scoring, Meera stream token) fails closed |

The committed dev defaults on both sides already align — this table only matters once you start rotating secrets for a shared/staging/prod environment.

---

## 9. Fastest path — minimum steps to see the app running

```bash
# 1. Database
docker compose up -d mysql

# 2. Backend (new terminal)
cp influora-api/.env.example influora-api/.env
cd influora-api && mvn spring-boot:run
#    watch this terminal for OTP codes when testing registration

# 3. Frontend (new terminal)
cp .env.local.example .env.local
npm install && npm run dev

# 4. (optional, new terminal) AI service — only needed for Meera chat / BrandSafety
cp influora-ai/.env.example influora-ai/.env
#    edit influora-ai/.env and set ANTHROPIC_API_KEY
cd influora-ai
python -m venv .venv && .venv/Scripts/activate
pip install -r requirements.txt
uvicorn app.main:app --port 8000 --reload
```

That's the full stack: brand + creator UI, real MySQL-backed API, and (with one key added) the AI assistant — all running locally with committed dev secrets for everything except the one Anthropic key.

---

## 10. Quick troubleshooting

| Symptom | Likely cause |
|---|---|
| Backend won't boot, error about secrets | You're not in `dev` profile and a Tier-1 secret is still the committed placeholder — set `SPRING_PROFILES_ACTIVE=dev` locally, or replace the secret |
| "Can't connect to MySQL" | `docker compose up -d mysql` not run, or a different MySQL is already using port 3306 |
| Registration OTP never arrives by email | Expected — see §7.1, check the backend console instead |
| Python service 401s calling Spring, or vice versa | Check the 3 paired secrets in §8 match exactly |
| `mvn: command not found` | Use the full cached-wrapper path shown in §4.3 |
| Frontend shows no real data | Confirm `VITE_API_MODE=live` (not `mock`) in `.env.local` and that the backend is actually running on port 8080 |
