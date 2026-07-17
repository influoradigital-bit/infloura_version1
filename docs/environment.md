# Environment & Configuration

Every environment variable and config property, grouped by concern. Backend config binds `application.yml` via `@ConfigurationProperties`; frontend config is `VITE_*` (public, inlined at build).

> **Rule of thumb**: backend secrets are validated at startup (`SecretsStartupValidator`) — outside `dev` the API refuses to boot if any are missing/weak/duplicated. Frontend `VITE_*` vars are **public by definition** (bundled into client JS) — never put secrets there.

---

## Frontend (`VITE_*`, build-time)

| Var | Default | Purpose |
|---|---|---|
| `VITE_API_MODE` | `mock` (dev) / must be `live` (prod) | Switches the API client between mock and real backend |
| `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | Backend base URL |
| `VITE_MEERA_STREAM_URL` | `https://ai.influora.internal` | Python AI service SSE URL (browser→Python) |

`.env.local.example` (dev) sets `live` + localhost; `.env.production` pins `live` + `https://api.influora.com/api/v1`. `vite build` fails if a prod build isn't `live` with a non-localhost URL.

---

## Backend — datasource & JPA

| Env | Default | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/Influora_AI?...` | MySQL connection |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `root` / `root` | DB creds |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `10` | Connection pool |
| `SERVER_PORT` | `8080` | API port (context `/api/v1`) |
| `APP_ENV` / `influora.env` | `dev` | Drives `SecretsStartupValidator` (independent of `spring.profiles.active`) |

JPA: `open-in-view: false`, `ddl-auto: validate`. Flyway: `out-of-order: true`, `baseline-on-migrate` true (dev) / false (prod). Multipart: `max-file-size: 500MB`, `max-request-size: 1GB`.

---

## Backend — auth & JWT

| Env | Default | Notes |
|---|---|---|
| `JWT_ACCESS_SECRET` | dev-default (rejected in prod) | HS256 user access token key |
| `JWT_REFRESH_SECRET` | dev-default | Refresh secret |
| `JWT_ACCESS_EXPIRY` | `900` (s) | Access token TTL |
| `JWT_REFRESH_EXPIRY` | `2592000` (s, 30d) | Refresh TTL |
| `REQUIRE_EMAIL_OTP_BEFORE_REGISTER` | `false` | Gate registration on OTP |
| `AUTH_REFRESH_COOKIE_NAME` | `influora_refresh` | Cookie name |
| `AUTH_REFRESH_COOKIE_SECURE` | `false` | **Must be true outside dev** (validator enforces) |
| `AUTH_REFRESH_COOKIE_SAMESITE` | `Strict` | |
| `AUTH_REFRESH_COOKIE_PATH` | `/api/v1/auth` | |
| `AUTH_RATE_LIMIT_ENABLED` | `true` | Rate limiting on auth surface |
| `AUTH_RATE_LIMIT_WINDOW_SECONDS` | `60` | + `_SENSITIVE=10`, `_OTP=5`, `_REFRESH=30` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Allowed SPA origins |
| `CONTENT_SECURITY_POLICY` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` | API CSP |

Admin MFA: `influora.admin.mfa-secret-encryption-key` (must decode to exactly 32 bytes), lockout tuning (attempts/cooldowns), `mfa-enforce-on-login` (default true).

---

## Backend — AI / Meera service mesh

| Env | Notes |
|---|---|
| `MEERA_STREAM_SIGNING_SECRET` | ES256 stream-token key (aud=meera-stream, ≤60s) — see note |
| `MEERA_STREAM_TOKEN_TTL_SECONDS` | `60` (hard-capped) |
| `influora.meera.stream.public-chat-url` | Browser→Python `/chat` base (default `http://localhost:8000/chat`) |
| `INTERNAL_SERVICE_TOKEN_SECRET` | HS256 Python→Spring service token |
| `INTERNAL_REQUEST_HMAC_SECRET` | HMAC request-signing key (distinct) |
| `INTERNAL_REQUEST_CLOCK_SKEW_SECONDS` | `30` |
| `influora.jwks.*` | EC private/public PEM (kid default `spring-dev-es256-1`) — **eager bean throws if blank/malformed** |
| `influora.brand-safety-service-token.*` | Service-token config (ES256 mint) |
| `influora.brand-safety-ai.*` | Brand-safety client (base URL, timeouts, max-items=25) |
| `influora.trendspark-ai.*` | TrendSpark client (base URL, timeouts) |

> Note: the AI/JWKS/Meta prefixes are **not present in committed `application*.yml`** — they use hardcoded localhost defaults with empty secrets, and several eager beans throw on blank keys. A real deploy must inject these out-of-band. See [ai.md](ai.md) and [known-limitations.md](known-limitations.md).

---

## Backend — payments (Razorpay/RazorpayX)

| Env | Default | Notes |
|---|---|---|
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | placeholders | Orders/subscriptions |
| `RAZORPAY_WEBHOOK_SECRET` | placeholder | Webhook HMAC (validator rejects placeholder in prod) |
| `RAZORPAYX_ACCOUNT_NUMBER` | placeholder | Payouts |
| `RAZORPAY_API_BASE_URL` / `RAZORPAYX_API_BASE_URL` | `https://api.razorpay.com/v1` | |
| `PLATFORM_FEE_PERCENT` | `15.00` | AI campaign-intent fee percent (distinct from bps fee config) |

---

## Backend — storage (Cloudflare R2)

| Env | Default | Notes |
|---|---|---|
| `R2_ACCOUNT_ID` / `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | placeholders | S3-compatible creds |
| `R2_BUCKET_NAME` | `influora-dev` | Bucket |
| `R2_ENDPOINT` / `R2_PUBLIC_URL` | placeholders / `https://r2.influora.com` | |
| `R2_PRESIGN_EXPIRY_SECONDS` | `900` | Presigned URL TTL |
| `R2_MAX_VIDEO_BYTES` | `524288000` (500MB) | Upload cap |

---

## Backend — email (MSG91)

`MSG91_ENABLED` (`true`), `MSG91_AUTH_KEY` (blank → mock mode), `MSG91_SENDER_ID` (`INFLRA`), `MSG91_FROM_EMAIL` (`noreply@influora.com`), `MSG91_FROM_NAME` (`Influora`), template ids/variables, `MSG91_EMAIL_DOMAIN` (`mail.influora.com`).

---

## Backend — Meta / stores

Meta: `influora.meta.token-encryption-key` (32 bytes; bean throws if blank), client id/secret, `token-refresh-days-before-expiry` (7), rate-limit thresholds (80/90). Shopify: `influora.shopify.webhook-signing-secret`, client id/secret, scopes. WooCommerce/conversion secrets are per-integration (encrypted in DB).

---

## Backend — company / GST

`influora.company.*`: `legal-name` (`Influora Technologies Pvt. Ltd.`), `gstin` (**placeholder** `REPLACE_WITH_REAL_GSTIN` — must be set for correct CGST/SGST vs IGST split), `state-code` (`27` Maharashtra — currently unused by the split logic), `registered-address` (placeholder).

---

## Secrets that MUST be injected outside dev

`JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, `MEERA_STREAM_SIGNING_SECRET`, `INTERNAL_SERVICE_TOKEN_SECRET`, `INTERNAL_REQUEST_HMAC_SECRET`, `influora.brand-safety-service-token.*`, JWKS EC PEMs, admin MFA key (32 bytes), `RAZORPAY_WEBHOOK_SECRET`, `AUTH_REFRESH_COOKIE_SECURE=true`, `influora.meta.token-encryption-key`. Each must be ≥32 bytes (where applicable), not a dev-default, and not duplicated — otherwise `SecretsStartupValidator` aborts startup.
