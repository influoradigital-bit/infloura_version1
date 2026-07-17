# Deployment & API Keys — Basic to Advanced

> How to add every API key, where each one lives, and how to deploy all three services.
> Lead: Meera (DevOps). Security rules by Kabir/Priya: **secrets ONLY via environment variables — never committed, never in frontend code.**

---

## 0. Golden rules (non-negotiable)

1. **No secret goes in the frontend.** `VITE_*` values are baked into the browser bundle and are public. Only put the API base URL and public IDs there — never a private key/secret.
2. **All backend secrets come from environment variables** (`${VAR}` placeholders in `application.yml`). Nothing hardcoded (verified: zero hardcoded secrets in the tree).
3. On boot, `SecretsStartupValidator` **fails closed** — the API will not start with missing/weak required secrets.
4. Rotate signing keys (`JWT_*`, `JWKS_*`, HMAC) on a schedule; never reuse dev secrets in prod.

---

## 1. Where each config lives

| Service | File | Contains |
|---|---|---|
| Web SPA | `.env.local` (root) | `VITE_API_MODE`, `VITE_API_BASE_URL`, `VITE_MEERA_STREAM_URL` — **public only** |
| Core API | env vars read by `application.yml` | DB, JWT/JWKS, Razorpay, Meta, MSG91, R2, HMAC, rate limits |
| AI service | env vars read by `app/config.py` | Anthropic/Gemini/Sarvam keys, service-token keys, SSRF/cost caps |

In production you set the API/AI variables in your host's secret manager (systemd `EnvironmentFile`, Docker `--env-file`, Kubernetes `Secret`, Railway/Render/Fly dashboard), **not** in a committed file.

---

## 2. Frontend `.env.local` (public config only)

```env
# mock = demo data; live = call the real API
VITE_API_MODE=live
VITE_API_BASE_URL=https://api.yourdomain.com/api/v1
VITE_MEERA_STREAM_URL=https://api.yourdomain.com/api/v1/stream
```
That's it. No keys here.

---

## 3. Core API environment variables (grouped)

### 3a. Database (required)
```env
SPRING_DATASOURCE_URL=jdbc:mysql://db-host:3306/influora
SPRING_DATASOURCE_USERNAME=influora
SPRING_DATASOURCE_PASSWORD=<strong-password>
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=10
SERVER_PORT=8080
API_PUBLIC_URL=https://api.yourdomain.com
```

### 3b. Auth / JWT / JWKS (required — the crown jewels)
```env
JWT_ACCESS_SECRET=<random 32+ bytes>
JWT_REFRESH_SECRET=<different random 32+ bytes>
JWT_ACCESS_EXPIRY=900          # seconds
JWT_REFRESH_EXPIRY=1209600
JWKS_KID=<key id>
JWKS_PRIVATE_KEY_PEM=<RSA private key PEM>
JWKS_PUBLIC_KEY_PEM=<RSA public key PEM>
AUTH_REFRESH_COOKIE_NAME=influora_rt
AUTH_REFRESH_COOKIE_PATH=/api/v1/auth
AUTH_REFRESH_COOKIE_SAMESITE=Strict
AUTH_REFRESH_COOKIE_SECURE=true
```
Generate an RSA keypair:
```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwks_private.pem
openssl rsa -pubout -in jwks_private.pem -out jwks_public.pem
# paste PEM contents (with \n) into JWKS_PRIVATE_KEY_PEM / JWKS_PUBLIC_KEY_PEM
```

### 3c. Internal service auth (Spring ↔ AI)
```env
INTERNAL_SERVICE_TOKEN_SECRET=<random>
INTERNAL_REQUEST_HMAC_SECRET=<random>
INTERNAL_REQUEST_CLOCK_SKEW_SECONDS=30
MEERA_STREAM_SIGNING_SECRET=<random>
MEERA_STREAM_TOKEN_TTL_SECONDS=300
```

### 3d. Payments — Razorpay
```env
RAZORPAY_KEY_ID=<from Razorpay dashboard>
RAZORPAY_KEY_SECRET=<from Razorpay dashboard>
RAZORPAY_WEBHOOK_SECRET=<set when creating the webhook>
RAZORPAY_API_BASE_URL=https://api.razorpay.com
RAZORPAYX_API_BASE_URL=https://api.razorpay.com   # payouts
RAZORPAYX_ACCOUNT_NUMBER=<RazorpayX account no.>
PLATFORM_FEE_PERCENT=10
```
Where to get them: Razorpay Dashboard → Settings → API Keys (live mode) and Settings → Webhooks (set the secret + point it at `https://api.yourdomain.com/api/v1/webhooks/razorpay`).

### 3e. Meta (Instagram/Facebook) OAuth
```env
META_APP_ID=<Meta app id>
META_APP_SECRET=<Meta app secret>
META_REDIRECT_URI=https://api.yourdomain.com/api/v1/meta/oauth/callback
META_TOKEN_ENCRYPTION_KEY=<32-byte key to encrypt stored creator tokens>
```
Where: Meta for Developers → your app → App settings → Basic (ID/secret) and Instagram/Facebook Login → Valid OAuth redirect URIs (must match `META_REDIRECT_URI`).

### 3f. MSG91 (SMS / email OTP)
```env
MSG91_ENABLED=true
MSG91_AUTH_KEY=<msg91 auth key>
MSG91_SENDER_ID=<6-char sender id>
MSG91_OTP_TEMPLATE_ID=<template id>
MSG91_WELCOME_TEMPLATE_ID=<...>
MSG91_ROUTE=4
MSG91_WIDGET_ID=<...>
MSG91_TOKEN_AUTH=<...>
MSG91_FROM_EMAIL=noreply@yourdomain.com
MSG91_FROM_NAME=Influora
MSG91_EMAIL_DOMAIN=yourdomain.com
MSG91_EMAIL_COMPANY_NAME=Influora
MSG91_EMAIL_TEMPLATE_ID=<...>
MSG91_EMAIL_TEMPLATE_OTP_VARIABLE=otp
MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID=<...>
MSG91_EMAIL_TRANSACTIONAL_LINK_VARIABLE=link
```
Where: MSG91 dashboard → API keys + Templates.

### 3g. Storage — Cloudflare R2 (S3-compatible)
```env
R2_ACCOUNT_ID=<cloudflare account id>
R2_ACCESS_KEY_ID=<r2 access key>
R2_SECRET_ACCESS_KEY=<r2 secret>
R2_BUCKET_NAME=influora-media
R2_ENDPOINT=https://<accountid>.r2.cloudflarestorage.com
R2_PUBLIC_URL=https://cdn.yourdomain.com
R2_PRESIGN_EXPIRY_SECONDS=900
R2_MAX_VIDEO_BYTES=524288000
```
Where: Cloudflare Dashboard → R2 → Manage R2 API Tokens.

### 3h. CORS / CSP / rate limits
```env
CORS_ALLOWED_ORIGINS=https://app.yourdomain.com
CONTENT_SECURITY_POLICY=default-src 'self'; ...
AUTH_RATE_LIMIT_ENABLED=true
AUTH_RATE_LIMIT_WINDOW_SECONDS=60
AUTH_RATE_LIMIT_OTP=5
AUTH_RATE_LIMIT_SENSITIVE=10
AUTH_RATE_LIMIT_CREATOR_WITHDRAW=3
# ...plus per-action limits: CAMPAIGN_APPLY, CONTRACT_SIGN, DISPUTE_OPEN,
#    REVIEW_WRITE/FLAG, DISCOVERY_SEARCH/INVITE, TRACKING, REFRESH,
#    BRAND_DELIVERABLE_REVIEW, CREATOR_DELIVERABLE_WRITE
```

---

## 4. AI service environment variables (`app/config.py`)

```env
APP_ENV=production
# LLM provider keys (SECRET — server only)
ANTHROPIC_API_KEY=<claude key>
GEMINI_API_KEY=<gemini key>
SARVAM_API_KEY=<sarvam key>
CLAUDE_MODEL=claude-...           # pin the model
GEMINI_MODEL=gemini-...
TRENDSPARK_MODEL=...
APPROVED_LLM_REGIONS=in,us
# Trust Spring's identity
SPRING_JWKS_URL=https://api.yourdomain.com/api/v1/.well-known/jwks.json
SPRING_JWT_ISSUER=influora-api
SPRING_INTERNAL_BASE_URL=https://api.yourdomain.com/api/v1
SERVICE_TOKEN_AUD=influora-internal
SERVICE_TOKEN_SIGNING_KEY=<matches INTERNAL_SERVICE_TOKEN_SECRET>
STREAM_TOKEN_AUD=chat:stream
INTERNAL_HMAC_KEY=<matches INTERNAL_REQUEST_HMAC_SECRET>
INTERNAL_HMAC_KEY_ID=<...>
# Cost controls
AI_DAILY_SPEND_CEILING_USD=50
AI_WORKSPACE_DAILY_SOFT_CAP_USD=5
AI_SPEND_KILL_SWITCH=false
# SSRF / limits
SSRF_FETCH_TIMEOUT_SECONDS=5
SSRF_MAX_REDIRECTS=2
SSRF_MAX_RESPONSE_BYTES=2000000
TOOL_LOOP_MAX_ITERATIONS=8
SSE_HEARTBEAT_SECONDS=15
LOG_LEVEL=INFO
```
Where to get provider keys: Anthropic Console, Google AI Studio, Sarvam dashboard. **These live only on the AI server** — never in Spring, never in the browser.

---

## 5. Deploy — BASIC (single box, Docker Compose)

Dockerfiles exist at repo root (SPA), `influora-api/Dockerfile`, `influora-ai/Dockerfile`. The bundled `docker-compose.yml` currently provisions **MySQL only** — extend it to run all three services.

```bash
# 1. Database
docker compose up -d mysql        # MySQL 8, db "influora"

# 2. Core API (Flyway auto-migrates on boot — 56 migrations)
cd influora-api
mvn clean verify                  # build + run 953 tests (must be green)
java -jar target/influora-api-*.jar --spring.profiles.active=prod   # env vars from §3

# 3. AI service
cd ../influora-ai
pip install -r requirements.txt
playwright install --with-deps    # analyze_site needs a browser
uvicorn app.main:app --host 0.0.0.0 --port 8000   # env vars from §4

# 4. Frontend
npm ci
npm run build                     # outputs dist/
# serve dist/ behind Nginx / Cloudflare Pages / any static host
```

---

## 6. Deploy — INTERMEDIATE (containers + reverse proxy)

```
                   ┌──────────── Nginx / Cloudflare ────────────┐
 app.yourdomain →  │ static dist/  (SPA)                         │
 api.yourdomain →  │ → influora-api:8080  (Spring)              │
                   │ → influora-ai:8000   (only reachable from   │
                   │     Spring's network, NOT the public web)   │
                   └────────────────────────────────────────────┘
                              │
                          MySQL 8 + Redis
```
- Build each image: `docker build -t influora-api ./influora-api` (same for `-ai` and root SPA).
- Put the **AI service on a private network** — only Spring should reach it. It authenticates callers by service token anyway (defense in depth).
- Terminate TLS at the proxy; set HSTS; forward `X-Forwarded-*`.
- Redis is used for caching/rate-limit state (`RedisCacheConfig`).

---

## 7. Deploy — ADVANCED (production hardening)

- **Managed DB:** point `SPRING_DATASOURCE_URL` at a managed MySQL 8 with automated backups + read replica. Flyway runs migrations on deploy; gate with a migration check in CI.
- **Secrets manager:** inject all `${VAR}` from Vault / AWS Secrets Manager / Doppler — never an env file on disk in prod. `SecretsStartupValidator` blocks boot if any required secret is missing.
- **Zero-downtime:** blue/green or rolling; because sessions are stateless JWT, any instance can serve any request.
- **Webhooks:** register live URLs and secrets for Razorpay (`/webhooks/razorpay`), Shopify (`/webhooks/shopify`), WooCommerce (`/webhooks/woocommerce`), and conversion tracking (`/webhooks/conversion`, `/webhooks/redemption`). Each verifies its signing secret.
- **Scheduled jobs:** 11 `@Scheduled` jobs run inside the API (score calc, metrics polling, Meta token refresh, deliverable verification/cleanup, affiliate reconciliation/settlement, stale-token cleanup, email worker). For multi-instance, guard them with a leader lock (or run a single "worker" profile instance).
- **Observability:** ship `com.influora` logs + the AI service `LOG_LEVEL` to your log store; alert on `SecretsStartupValidator` failures, webhook signature failures, and AI kill-switch / cost-ceiling hits.
- **Rotation drill:** rotate `JWT_*`, `JWKS_*`, HMAC and provider keys; JWKS supports `kid` so you can roll public keys without downtime.
- **CI gates:** `mvn verify` (backend), `npm run typecheck && npm test && npm run build` (frontend), `pytest` + `pip-audit` (AI). Playwright E2E + Lighthouse scripts are already wired.

---

## 8. Pre-launch checklist

- [ ] All §3/§4 secrets set in the secret manager (none in git).
- [ ] `VITE_API_MODE=live` and `VITE_API_BASE_URL` correct in the SPA build.
- [ ] `mvn verify` green; jar produced; Flyway migrated cleanly.
- [ ] Razorpay/Meta/Shopify/Woo webhooks registered with matching secrets.
- [ ] AI service private, provider keys valid, cost caps set.
- [ ] TLS + HSTS + CORS locked to your real origins.
- [ ] Admin MFA enrolled; JWKS keypair is production-only.
