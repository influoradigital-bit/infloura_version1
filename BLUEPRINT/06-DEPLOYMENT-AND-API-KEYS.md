# 06 — API Keys & Secrets: Which File, and How

> **Owner:** Priya (CTO) · **Verified against code:** 15 Jul 2026 · **Branch:** `fix/remaining-partial-broken`
> **Companion doc:** `10-RUN-ON-SERVER-UTHO.md` (step-by-step Utho deploy)
> **Source of truth:** the code cited on every row. Where an old comment or audit disagrees with the code, the code wins.

---

## 0. The one rule

**You never put a real secret in a file that Git tracks.**

Every secret in this system is read from an **environment variable**. The `application.yml` / `.env.production` files in Git contain only *placeholders and dev defaults* — they exist to document the shape of the config, not to hold values.

There are three services, and each one takes its config from a **different place**. Getting this wrong is the single most common deploy failure:

| Service | Language | Config goes in | Read at | Secrets allowed? |
|---|---|---|---|---|
| **Web SPA** (`/src`) | TypeScript (Vite 6 / React 19) | `.env.production` + `--build-arg` | **Build time** (baked into JS) | ❌ **NEVER** |
| **Core API** (`/influora-api`) | Java 21 (Spring Boot 3.3.5) | **Environment variables** | Boot time | ✅ Yes — all money/DB keys |
| **AI service** (`/influora-ai`) | Python 3.13 (FastAPI) | `influora-ai/.env` or env vars | Boot time | ✅ Yes — LLM keys only |

### Why the frontend is different (read this twice)

Vite **inlines every `VITE_*` variable into the JavaScript bundle at build time**. It is static text in a file any user can download.

> Setting `VITE_API_BASE_URL` with `docker run -e` does **nothing** — by then the bundle is already built.
> — this is why `Dockerfile` uses `ARG`/`--build-arg`, not runtime env.

**Anything you put in a `VITE_*` var is public.** There are currently **zero** secrets in the frontend, and it must stay that way. All five `VITE_*` vars are URLs or mode flags. The browser never holds a provider key — Spring brokers every privileged call.

---

## 1. Blast-radius isolation (why keys are split this way)

This split is deliberate (Kabir guardrail #6) and must be preserved:

```
Browser  ── no keys at all ─────────────────────────────┐
                                                        │
Python AI service  ── LLM keys + internal HMAC only ────┤
   (never Razorpay, never DB, never user JWT key)       │
                                                        ▼
Java Core API  ── DB, Razorpay, R2, MSG91, all JWT ── MySQL
```

The Python service is the larger attack surface (it fetches brand-supplied URLs), so it is **never** given money or database credentials, and it only ever holds Spring's **public** key — never key material that could forge Spring's identity.

---

## 2. ⚠️ Three boot-blockers you WILL hit

These are verified in the code today and are not documented anywhere else. Fix them before your first deploy.

### 2.1 `influora.api.public-url` has no default and is in no YAML file

`CreatorCouponService.java:51` binds:

```java
@Value("${influora.api.public-url}") String apiPublicUrl
```

There is **no default** and **no `influora.api.public-url` key in any `application*.yml`**. The Spring context **cannot start in any profile — including dev** — until you supply it. Its own javadoc claims it "lives in application.yml / `API_PUBLIC_URL`"; that comment is stale and wrong.

**Set:** `INFLUORA_API_PUBLIC_URL=https://api.yourdomain.com/api/v1`

### 2.2 Five secrets have no YAML placeholder at all

`SecretsStartupValidator` says its constants "must match the literal defaults in application.yml exactly". **They don't — those YAML blocks don't exist.** These properties fall back to their Java field default of `""`, so the failure you actually get is *"is missing"*, not *"is still the dev default"*. Two of them (`JwksSigningKeyProperties`, `AdminMfaProperties`) throw at bean construction in **every** environment, dev included.

Because they have no `${ENV_VAR}` placeholder, you must use **Spring relaxed binding** env var names (uppercase, dots → `_`, dashes removed):

| Property | Env var to set |
|---|---|
| `influora.jwks.private-key-pem` | `INFLUORA_JWKS_PRIVATEKEYPEM` |
| `influora.jwks.public-key-pem` | `INFLUORA_JWKS_PUBLICKEYPEM` |
| `influora.admin.mfa-secret-encryption-key` | `INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY` |
| `influora.brand-safety-service-token.signing-secret` | `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` |
| `influora.pii.email-phone-encryption-key` | `INFLUORA_PII_EMAILPHONEENCRYPTIONKEY` |
| `influora.pii.bank-encryption-key` | `INFLUORA_PII_BANKENCRYPTIONKEY` |

> **PII keys:** these have real values in `application-dev.yml` (throwaway, safe in Git, `dev` profile only). Prod has **nothing** — `AesGcmCipher` fails closed on a blank key. You must generate fresh ones. **If you ever lose or rotate these, every encrypted email/phone/bank row becomes unreadable.** Back them up before go-live.

### 2.3 You must set **BOTH** `APP_ENV` and `SPRING_PROFILES_ACTIVE`

This trips everyone. The two validators gate on **different things**:

| Validator | Gates on | If you forget it |
|---|---|---|
| `SecretsStartupValidator` | `influora.env` (`APP_ENV`) | Dev-default secrets **only log a WARNing** and boot silently |
| `CompanyTaxStartupValidator` | Spring active profile | GSTIN placeholder is not enforced |

Setting only `SPRING_PROFILES_ACTIVE=prod` gets you GSTIN enforcement but **WARN-level secret validation** — every dev-default signing secret would boot silently in production. Set both:

```bash
APP_ENV=prod
SPRING_PROFILES_ACTIVE=prod
```

---

## 3. Generate your secrets

Run this once on the server. It prints every value you need. **Each secret must be distinct** — `SecretsStartupValidator` explicitly rejects any secret that duplicates another.

```bash
# --- 32-byte signing secrets (6 distinct values) ---
for k in JWT_ACCESS_SECRET JWT_REFRESH_SECRET MEERA_STREAM_SIGNING_SECRET \
         INTERNAL_SERVICE_TOKEN_SECRET INTERNAL_REQUEST_HMAC_SECRET \
         INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET; do
  echo "$k=$(openssl rand -base64 48 | tr -d '\n')"
done

# --- AES-256 keys: must base64-decode to EXACTLY 32 bytes ---
for k in INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY \
         INFLUORA_PII_EMAILPHONEENCRYPTIONKEY \
         INFLUORA_PII_BANKENCRYPTIONKEY; do
  echo "$k=$(openssl rand -base64 32)"
done

# --- JWKS EC P-256 keypair (ES256 — NOT RSA; Python only accepts RS256/ES256) ---
openssl ecparam -name prime256v1 -genkey -noout -out jwks-private.pem
openssl pkcs8 -topk8 -nocrypt -in jwks-private.pem -out jwks-private-pkcs8.pem
openssl ec -in jwks-private.pem -pubout -out jwks-public.pem
```

The JWKS key **must be PKCS#8 EC (P-256)** — the validator parses it with `KeyFactory.getInstance("EC")` and `PKCS8EncodedKeySpec`, and rejects anything that isn't an `ECPrivateKey`.

To put a multi-line PEM into a single env var, use literal `\n` escapes (the validator handles `.replace("\\n", "\n")`):

```bash
INFLUORA_JWKS_PRIVATEKEYPEM=$(awk '{printf "%s\\n", $0}' jwks-private-pkcs8.pem)
```

---

## 4. Java Core API — `/influora-api`

**File to add:** `/opt/influora/env/api.env` (chmod 600, **never** in Git)
**How it's read:** `env_file:` in docker-compose → environment variables → Spring
**Never edit `application.yml` to add secrets.** It is tracked in Git.

### 4.1 Secrets — boot FAILS without these

| Env var | Get it from | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | You | **Must contain `useSSL=true`** — validator rejects `useSSL=false` |
| `SPRING_DATASOURCE_USERNAME` | You | Must **not** be `root` |
| `SPRING_DATASOURCE_PASSWORD` | You | Must **not** be `root` |
| `JWT_ACCESS_SECRET` | `openssl` above | ≥32 bytes, distinct |
| `JWT_REFRESH_SECRET` | `openssl` above | ≥32 bytes, distinct |
| `MEERA_STREAM_SIGNING_SECRET` | `openssl` above | ≥32 bytes, distinct |
| `INTERNAL_SERVICE_TOKEN_SECRET` | `openssl` above | **Must byte-match Python's `SERVICE_TOKEN_SIGNING_KEY`** |
| `INTERNAL_REQUEST_HMAC_SECRET` | `openssl` above | **Must byte-match Python's `INTERNAL_HMAC_KEY`** |
| `INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET` | `openssl` above | No YAML placeholder (§2.2) |
| `INFLUORA_JWKS_PRIVATEKEYPEM` | `openssl` above | PKCS#8 EC P-256 (§2.2) |
| `INFLUORA_JWKS_PUBLICKEYPEM` | `openssl` above | Served at `/.well-known/jwks.json` |
| `INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY` | `openssl` above | base64 → exactly 32 bytes |
| `INFLUORA_PII_EMAILPHONEENCRYPTIONKEY` | `openssl` above | **Back this up — data loss if lost** |
| `INFLUORA_PII_BANKENCRYPTIONKEY` | `openssl` above | **Back this up — data loss if lost** |
| `RAZORPAY_WEBHOOK_SECRET` | Razorpay Dashboard → Settings → Webhooks | Validator rejects the placeholder |
| `AUTH_REFRESH_COOKIE_SECURE` | Set to `true` | Validator **requires** `true` outside dev → **TLS is mandatory** |
| `INFLUORA_COMPANY_GSTIN` | Your CA / GST portal | `CompanyTaxStartupValidator` rejects the placeholder |
| `INFLUORA_API_PUBLIC_URL` | You | **No default anywhere** (§2.1) |

### 4.2 Secrets — boot SUCCEEDS but the feature is silently broken

**There is no validator for these.** The app starts happily and the feature fails in production. This is the dangerous category.

| Env var | Get it from | Breaks if wrong |
|---|---|---|
| `RAZORPAY_KEY_ID` | Razorpay → Settings → API Keys | All payments |
| `RAZORPAY_KEY_SECRET` | Razorpay → Settings → API Keys | All payments |
| `RAZORPAYX_ACCOUNT_NUMBER` | RazorpayX Dashboard | Creator payouts |
| `MSG91_AUTH_KEY` | MSG91 → Settings → API | All OTP + email |
| `MSG91_TOKEN_AUTH` | MSG91 → Settings | OTP widget |
| `MSG91_WIDGET_ID` | MSG91 → OTP Widget | OTP widget |
| `MSG91_OTP_TEMPLATE_ID` | MSG91 → Templates | OTP SMS |
| `MSG91_WELCOME_TEMPLATE_ID` | MSG91 → Templates | Welcome SMS |
| `MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID` | MSG91 → Email Templates | Password-reset email |
| `R2_ACCOUNT_ID` | Cloudflare → R2 | All uploads |
| `R2_ACCESS_KEY_ID` | Cloudflare → R2 → API Tokens | All uploads |
| `R2_SECRET_ACCESS_KEY` | Cloudflare → R2 → API Tokens | All uploads |
| `R2_BUCKET_NAME` | Cloudflare → R2 | All uploads |
| `R2_ENDPOINT` | Cloudflare → R2 | All uploads |
| `INFLUORA_COMPANY_ADDRESS` | You | Prints verbatim on GST invoices |

### 4.3 Non-secret config you must still set

| Env var | Set to | Why |
|---|---|---|
| `APP_ENV` | `prod` | Arms `SecretsStartupValidator` (§2.3) |
| `SPRING_PROFILES_ACTIVE` | `prod` | Arms GSTIN check + real DB config (§2.3) |
| `CORS_ALLOWED_ORIGINS` | `https://yourdomain.com` | No `@Value` default — browser blocked otherwise |
| `INFLUORA_WEB_BASE_URL` | `https://yourdomain.com` | Password-reset links point at localhost otherwise |
| `BRAND_SAFETY_AI_BASE_URL` | `http://influora-ai:8000` | Validator rejects localhost |
| `TRENDSPARK_AI_BASE_URL` | `http://influora-ai:8000` | Validator rejects localhost |
| `MEERA_CHAT_AI_BASE_URL` | `http://influora-ai:8000` | Validator rejects localhost |
| `ANALYZE_SITE_AI_BASE_URL` | `http://influora-ai:8000` | Validator rejects localhost |
| `INFLUORA_MEERA_STREAM_PUBLICCHATURL` | `https://ai.yourdomain.com/chat` | ⚠️ **Not validated** — defaults to `http://localhost:8000/chat` and is handed to browsers |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | Only when behind the Nginx proxy |
| `TRUSTED_PROXIES` | Your proxy IP | Otherwise rate-limiting keys on the proxy IP and one user can lock out everyone |
| `CLAMAV_HOST` | `clamav` | Required when `prod` profile is active |

> **Optional integrations** (`influora.meta.*`, `influora.shopify.*`, `influora.woocommerce.*`) all default to `""` and are gated by an `isConfigured()` check. Leave them unset unless you use them. If you do enable them, their `token-encryption-key` values are secrets.

---

## 5. Python AI service — `/influora-ai`

**File to add:** `influora-ai/.env` (copy from the committed `.env.example`)
**How it's read:** every var flows through `app/config.py` — there are **zero** `os.getenv` calls anywhere else in `app/`.

`app/main.py:65` **refuses to boot** if any required secret is missing:

```python
missing = settings.require_boot_secrets()
if missing:
    raise RuntimeError(f"missing required secrets/config: {', '.join(missing)}")
```

> ⚠️ It only checks **presence, not strength**. `ANTHROPIC_API_KEY=x` passes the check. The `min-32-chars` convention is *not* enforced here — unlike Java, which enforces ≥32 bytes.

### 5.1 Required — boot fails without these

| Env var | Get it from |
|---|---|
| `ANTHROPIC_API_KEY` | https://console.anthropic.com/settings/keys |
| `GEMINI_API_KEY` | https://aistudio.google.com/app/apikey |
| `SARVAM_API_KEY` | https://dashboard.sarvam.ai/ |
| `INTERNAL_HMAC_KEY` | **Copy Java's `INTERNAL_REQUEST_HMAC_SECRET` byte-for-byte** |
| `SERVICE_TOKEN_SIGNING_KEY` | **Copy Java's `INTERNAL_SERVICE_TOKEN_SECRET` byte-for-byte** |
| `SPRING_JWKS_URL` | `https://api.yourdomain.com/api/v1/.well-known/jwks.json` |

**The two shared secrets are the #1 integration failure.** They are *different from each other* — `INTERNAL_HMAC_KEY` signs the request HMAC (`X-Meera-Signature`); `SERVICE_TOKEN_SIGNING_KEY` signs the bearer token. Swap them and every Python→Spring call fails with `SIGNATURE_MISMATCH` or a signature error.

> ❌ **Never set `DEV_SHARED_JWT_SECRET` in production.** It is the local-dev symmetric fallback, consulted only when `SPRING_JWKS_URL` is unset. Setting it satisfies the boot check while bypassing the asymmetric JWKS path the design depends on.

### 5.2 Config

| Env var | Set to | Notes |
|---|---|---|
| `APP_ENV` | `prod` | |
| `SPRING_INTERNAL_BASE_URL` | `http://influora-api:8080/api/v1` | **Must include `/api/v1`** or every internal call 404s |
| `AI_DAILY_SPEND_CEILING_USD` | `15.0` | Hard ceiling (Rohan's budget) |
| `AI_SPEND_KILL_SWITCH` | `false` | Set `true` to instantly stop all AI spend |
| `REDIS_URL` | `redis://redis:6379` | **Set this if you run >1 worker** — without it the spend ceiling is counted *per process*, so N workers = N× your ceiling |

> `.env.example` has drifted behind `config.py` — it documents the 5 boot secrets but **not** `REDIS_URL`, `AI_DAILY_SPEND_CEILING_USD`, `AI_SPEND_KILL_SWITCH`, `AI_WORKSPACE_DAILY_SOFT_CAP_USD`, or the `TRENDSPARK_*` / `BRAND_SAFETY_*` knobs. Use this doc, not that file, as the list.

---

## 6. Frontend SPA — `/src`

**File to add:** `.env.production` (already tracked in Git — this is fine, **it holds no secrets**)
**How it's read:** `vite.config.ts` → baked into `dist/` at build time.

| Var | Set to | Validated? |
|---|---|---|
| `VITE_API_MODE` | `live` | ✅ Build **fails** if ≠ `live` |
| `VITE_API_BASE_URL` | `https://api.yourdomain.com/api/v1` | ✅ Build **fails** if unset or localhost |
| `VITE_MEERA_STREAM_URL` | `https://ai.yourdomain.com` | ❌ **Not validated** |
| `VITE_ADMIN_WS_ENABLED` | `false` | No backend WS endpoint exists yet |

`vite.config.ts:6-31` hard-fails the build (non-zero exit, no bundle) — but **only** when `command === 'build' && mode === 'production'`. A `--mode staging` build skips validation entirely.

### Two live traps

1. **`.env.production:14` still holds a placeholder:** `https://api.influora.com/api/v1`, self-labelled *"replace with the real deployed influora-api URL before release"*. It passes the guard (it isn't localhost), so **the build will not catch it.** Change it.
2. **`VITE_MEERA_STREAM_URL` is unvalidated and absent from `.env.production`.** A prod build silently bakes in the `https://ai.influora.internal` fallback — a mesh-internal hostname **a browser cannot resolve**. The build guard covers `VITE_API_BASE_URL` but not this one, so the exact failure that guard exists to prevent can still ship through the SSE stream URL. Set it explicitly to a public hostname.

---

## 7. Pre-flight checklist

Before `docker compose up`:

- [ ] `APP_ENV=prod` **and** `SPRING_PROFILES_ACTIVE=prod` both set (§2.3)
- [ ] `INFLUORA_API_PUBLIC_URL` set (§2.1 — boot blocker)
- [ ] All 6 signing secrets generated, ≥32 bytes, **all distinct**
- [ ] JWKS keypair is **EC P-256 PKCS#8**, not RSA
- [ ] PII + MFA keys base64-decode to exactly 32 bytes — **and are backed up**
- [ ] `INTERNAL_REQUEST_HMAC_SECRET` == Python `INTERNAL_HMAC_KEY`
- [ ] `INTERNAL_SERVICE_TOKEN_SECRET` == Python `SERVICE_TOKEN_SIGNING_KEY`
- [ ] `SPRING_DATASOURCE_URL` contains `useSSL=true`, user is not `root`
- [ ] `AUTH_REFRESH_COOKIE_SECURE=true` → **TLS must be live first**
- [ ] `DEV_SHARED_JWT_SECRET` is **NOT** set in the Python env
- [ ] `.env.production` `VITE_API_BASE_URL` is your real domain, not `api.influora.com`
- [ ] `VITE_MEERA_STREAM_URL` set to a browser-resolvable host
- [ ] `chmod 600` on every `.env` file; none are tracked by Git

---

## 8. Recommended fixes (CTO sign-off required)

These are config-layer gaps, not app code. I'm flagging them rather than silently patching:

1. **Add the missing `${ENV_VAR}` placeholders to `application.yml`** for the six §2.2 properties. Relaxed-binding names like `INFLUORA_JWKS_PRIVATEKEYPEM` work but are undiscoverable and easy to typo.
2. **Give `influora.api.public-url` a placeholder** — a property with no default in no YAML file that hard-fails dev boot is a trap.
3. **Add `influora.meera.stream.public-chat-url` to `validateAiServiceUrls`** — it defaults to localhost and is handed to browsers, the exact failure W0-5 exists to stop.
4. **Add a strength check to `require_boot_secrets()`** — Python accepts a 1-char API key; Java enforces 32 bytes.
5. **Fix `src/vite-env.d.ts`** — it declares two dead vars (`VITE_API_URL`, `VITE_USE_MOCK`) and none of the five real ones, which is why every call site launders through `(import.meta as any).env` and loses typo protection.
6. **Unify the two validator gates** — one env signal, not `influora.env` and `spring.profiles.active` disagreeing.

---

**Next:** `10-RUN-ON-SERVER-UTHO.md` — provisioning and deploying all three services on Utho.
