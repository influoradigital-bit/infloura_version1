# API Keys — Where Each One Goes

Quick reference for **which file** every API key/secret belongs in. There are **three separate homes** depending on which service consumes the key. Do not mix them up.

> **Rule of thumb**
> - **Local dev** → put values in the files below.
> - **Production** → do **not** put secrets in any file. Set them as **environment variables** on the server (the committed `application.yml` / `config.py` already read them via `${VAR}` / `os.getenv`).

---

## 1. Java API keys → `influora-api/src/main/resources/application-dev.yml`

Consumed by the Java `influora-api` service. Paste under the `influora:` block (a commented placeholder section is already in the file — fill in and uncomment the block you need).

| Service | What it's for | YAML keys under `influora:` | Prod env vars |
|---|---|---|---|
| **Razorpay / RazorpayX** | Payments + creator payouts | `razorpay.key-id`, `razorpay.key-secret`, `razorpay.webhook-secret`, `razorpay.payout-account-number` | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAYX_ACCOUNT_NUMBER` |
| **MSG91** | OTP SMS + transactional email | `msg91.auth-key`, `msg91.token-auth`, `msg91.widget-id`, `msg91.template.otp`, `msg91.template.welcome`, `msg91.email.transactional-template-id` | `MSG91_AUTH_KEY`, `MSG91_TOKEN_AUTH`, `MSG91_WIDGET_ID`, `MSG91_OTP_TEMPLATE_ID`, `MSG91_WELCOME_TEMPLATE_ID`, `MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID` |
| **Cloudflare R2** | Object storage (uploads/media) | `r2.account-id`, `r2.access-key-id`, `r2.secret-access-key`, `r2.bucket-name`, `r2.endpoint`, `r2.public-url` | `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET_NAME`, `R2_ENDPOINT`, `R2_PUBLIC_URL` |
| **Meta (Instagram/Facebook)** — *optional* | IG/FB OAuth + insights | `meta.app-id`, `meta.app-secret`, `meta.redirect-uri` | `META_APP_ID`, `META_APP_SECRET`, `META_REDIRECT_URI` |

> ⚠️ **Meta is off by default and must stay that way until real keys are set.** `MetaApiProperties.isConfigured()` is simply "`app-id` and `app-secret` both non-blank" — so leaving them blank keeps Meta disabled. Only fill them in when you actually connect Instagram/Facebook.

---

## 2. Python AI keys → `influora-ai/.env`

Consumed by the Python `influora-ai` service (Meera reasoner). This file is **gitignored** (never committed). The service **refuses to boot** without all three LLM keys.

| Service | What it's for | `.env` variable | Where to get it |
|---|---|---|---|
| **Anthropic (Claude)** | Meera's main reasoning model | `ANTHROPIC_API_KEY` (+ `CLAUDE_MODEL`) | console.anthropic.com/settings/keys |
| **Google Gemini** | Secondary LLM | `GEMINI_API_KEY` | aistudio.google.com/app/apikey |
| **Sarvam** | Speech-to-text / text-to-speech (India) | `SARVAM_API_KEY` | dashboard.sarvam.ai |

> Template: `influora-ai/env.example` — copy to `.env` and fill in.

---

## 3. Trend-data keys → n8n Credentials UI (no file in this repo)

Consumed by the n8n workflow `trendspark/n8n/trend-pull-workflow.json`, which pulls trends and pushes the result into the app. **These keys live in the n8n credential store, NOT in any `.env` or YAML.** The workflow references them by credential name.

| Service | What it's for | n8n credential / env name | Status |
|---|---|---|---|
| **NewsAPI** | Headlines by category | `NEWSAPI_KEY` (n8n cred "TrendSpark NewsAPI Key") | ✅ live in workflow |
| **TMDb** | Movie / OTT release dates | `TMDB_API_KEY` (n8n cred "TrendSpark TMDb API Key") | ✅ live in workflow |
| **YouTube Data API v3** | Trending videos (India) | `YOUTUBE_API_KEY` (n8n cred "TrendSpark YouTube Data API Key") | ✅ live in workflow |
| **Google Trends** (pytrends / SerpAPI) | India search trends | `GOOGLE_TRENDS_*` / `SERPAPI_KEY` | ⚠️ **STUBBED** — node emits `[]`; wire before it does anything |
| **Festival calendar** | Diwali / Eid / cricket dates | *(static JSON file, no key)* | file only |
| **Snapsby catalog** | Our own videos | *(internal DB, existing creds)* | internal |

---

## Internal signing secrets (you generate these yourself)

Not third-party — generated locally (e.g. `openssl rand -base64 32`). Dev values already sit in `application-dev.yml` / `influora-ai/.env`; prod supplies them via env vars. Includes JWT access/refresh secrets, internal HMAC + service-token secrets, JWKS EC keypair, and the PII / token-encryption AES keys. Full generation commands are in `_to_delete/BLUEPRINT/06` (being migrated).

---

## Cheat sheet

| Keys | File / place to edit |
|---|---|
| Razorpay, MSG91, R2, Meta | `influora-api/src/main/resources/application-dev.yml` (dev) · env vars (prod) |
| Claude, Gemini, Sarvam | `influora-ai/.env` (gitignored) |
| NewsAPI, TMDb, YouTube, (Google Trends) | n8n Credentials UI — no file |
