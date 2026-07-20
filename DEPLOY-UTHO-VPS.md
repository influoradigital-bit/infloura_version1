# Deploying Influora (3 languages, 1 server) — Utho VPS Guide

> Single-file merge of `BLUEPRINT/06-DEPLOYMENT-AND-API-KEYS.md` + `BLUEPRINT/10-RUN-ON-SERVER-UTHO.md`,
> verified against code on **2026-07-20** (branch `feat/portfolio-view-tracking`).
> Those two files have more narrative detail per topic — use this one as the linear "start to finish" run sheet.

---

## 0. The three-language problem, and how we solve it

Influora is **three services in three languages**, deployed as **one Docker Compose stack on one VPS**:

| Service | Language | Build tool | Produces | Port |
|---|---|---|---|---|
| Web SPA (`/src`) | **TypeScript** (Vite 6 / React 19) | `npm ci && npm run build` | Static files in `dist/`, served by nginx | 80 (internal 8081) |
| Core API (`/influora-api`) | **Java 21** (Spring Boot 3.3.5) | `mvn clean package` | Executable JAR | 8080 |
| AI service (`/influora-ai`) | **Python 3.13** (FastAPI) | `pip install -r requirements.txt` | Uvicorn app | 8000 |

**You install nothing but Docker on the server.** Each service has a working multi-stage `Dockerfile` ([`Dockerfile`](Dockerfile), [`influora-api/Dockerfile`](influora-api/Dockerfile), [`influora-ai/Dockerfile`](influora-ai/Dockerfile)) that builds its own toolchain inside the container. `influora-api` has **no Maven wrapper**, so Docker's `maven:3.9-eclipse-temurin-21` build stage is the only sane build path — don't try to `mvn package` on the host.

### The one asymmetry that trips people up

Java and Python read config at **container-run time** (`env_file:`). **TypeScript does not.** Vite bakes every `VITE_*` var into the JS bundle at **build time**, passed as `--build-arg`. Change a `VITE_*` value → **rebuild** the frontend image. `docker compose restart frontend` will silently keep serving the old bundle.

### Traffic shape — why you need 3 hostnames

The bundled nginx ([`docker/nginx.conf.template`](docker/nginx.conf.template)) is a **static file server, not a reverse proxy**. The browser calls the API directly at the URL baked into the JS. So each public service needs its own TLS hostname, fronted by a host-level nginx + Certbot:

```
                    ┌── app.yourdomain.com ──► frontend      :8081  (static SPA)
Browser ── HTTPS ───┼── api.yourdomain.com ──► influora-api  :8080  (/api/v1)
                    └── ai.yourdomain.com  ──► influora-ai   :8000  (SSE stream only)
                                                    │
              ┌─────────────────────────────────────┘
   internal:  influora-api ◄──HMAC──► influora-ai ──► MySQL / ClamAV / Redis
```

---

## 1. Provision the Utho instance

Utho dashboard → **Cloud → Deploy Instance**:

| Setting | Value | Why |
|---|---|---|
| OS | Ubuntu 22.04 LTS | Docker-supported LTS |
| Plan | **4 vCPU / 8 GB RAM / 80 GB SSD minimum** | see sizing note below — don't under-size |
| Location | India (Mumbai/Noida) | data residency + latency, matches India-only LLM region rule |
| Backups | Enable | PII keys + DB are unrecoverable without them |

**Don't go below 8 GB.** ClamAV's virus DB alone eats ~2 GB RAM; Playwright's bundled Chromium (`influora-ai`'s Dockerfile runs `playwright install --with-deps chromium`) is ~1 GB; then the JVM + MySQL buffer pool on top. A 2–4 GB box will OOM. If budget is tight, move MySQL to Utho's managed DB before shrinking this box.

**Firewall** — allow only:

| Port | Source | Purpose |
|---|---|---|
| 22 | your IP only | SSH |
| 80 | anywhere | HTTP → redirect to HTTPS |
| 443 | anywhere | HTTPS |

Never expose 3306 (MySQL), 8080, 8000, or 3310 (ClamAV) publicly — they talk over Docker's internal network only.

---

## 2. DNS

Point three A-records at the instance's public IP:

```
app.yourdomain.com   A   <utho-ip>
api.yourdomain.com   A   <utho-ip>
ai.yourdomain.com    A   <utho-ip>
```

Wait for propagation (`dig +short api.yourdomain.com`) before requesting certificates.

---

## 3. Base server setup

```bash
ssh root@<utho-ip>
apt update && apt upgrade -y

# Docker (official repo — Ubuntu's docker.io package is too old for compose v2)
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# Host-level TLS terminator
apt install -y nginx certbot python3-certbot-nginx git

# Non-root deploy user
adduser --disabled-password --gecos "" influora
usermod -aG docker influora
```

Harden SSH (copy your public key to `influora` FIRST or you'll lock yourself out):

```bash
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh
```

Swap (cheap OOM insurance):

```bash
fallocate -l 4G /swapfile && chmod 600 /swapfile
mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

---

## 4. Get the code

```bash
su - influora
git clone <your-repo-url> /home/influora/influora
cd /home/influora/influora
git checkout main          # deploy a real branch, not a WIP one
mkdir -p /home/influora/env && chmod 700 /home/influora/env
```

---

## 5. Generate secrets

Run once on the server — **each secret must be distinct**, the Java validator rejects duplicates:

```bash
# 6 signing secrets, ≥32 bytes each
for k in JWT_ACCESS_SECRET JWT_REFRESH_SECRET MEERA_STREAM_SIGNING_SECRET \
         INTERNAL_SERVICE_TOKEN_SECRET INTERNAL_REQUEST_HMAC_SECRET \
         INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET; do
  echo "$k=$(openssl rand -base64 48 | tr -d '\n')"
done

# AES-256 keys — must base64-decode to exactly 32 bytes
for k in INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY \
         INFLUORA_PII_EMAILPHONEENCRYPTIONKEY \
         INFLUORA_PII_BANKENCRYPTIONKEY; do
  echo "$k=$(openssl rand -base64 32)"
done

# JWKS EC P-256 keypair — ES256 only, NOT RSA
openssl ecparam -name prime256v1 -genkey -noout -out jwks-private.pem
openssl pkcs8 -topk8 -nocrypt -in jwks-private.pem -out jwks-private-pkcs8.pem
openssl ec -in jwks-private.pem -pubout -out jwks-public.pem
INFLUORA_JWKS_PRIVATEKEYPEM=$(awk '{printf "%s\\n", $0}' jwks-private-pkcs8.pem)
```

Back up `INFLUORA_PII_EMAILPHONEENCRYPTIONKEY` and `INFLUORA_PII_BANKENCRYPTIONKEY` **now**, off this server (password manager / vault). Lose them and every encrypted email/phone/bank row in the DB becomes permanently unreadable — there is no recovery path.

> ⚠️ **Do not reuse any secret you find in a committed or untracked `env.example`/`.env.unix` file in this repo.** Generate fresh values here. If a real-looking secret is already sitting in one of those files, treat it as compromised and rotate it at the provider (see the note at the top of this deploy conversation).

---

## 6. Write the env files (outside Git, `chmod 600`)

### 6.1 `/home/influora/env/api.env` (Java)

```bash
# Environment gates — BOTH required, different validators check different ones
APP_ENV=prod
SPRING_PROFILES_ACTIVE=prod

# Database — useSSL=true is mandatory, validator rejects useSSL=false
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/influora?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC
SPRING_DATASOURCE_USERNAME=influora_app
SPRING_DATASOURCE_PASSWORD=<strong-password-not-root>

# Public URLs — INFLUORA_API_PUBLIC_URL has NO default anywhere; boot fails without it
# (CreatorCouponService.java:51, Msg91EmailClient.java:70)
INFLUORA_API_PUBLIC_URL=https://api.yourdomain.com/api/v1
INFLUORA_WEB_BASE_URL=https://app.yourdomain.com
CORS_ALLOWED_ORIGINS=https://app.yourdomain.com

# Behind the host nginx
SERVER_FORWARD_HEADERS_STRATEGY=framework
TRUSTED_PROXIES=172.17.0.1        # verify: docker network inspect bridge
AUTH_REFRESH_COOKIE_SECURE=true   # TLS must be live before login works

# Signing secrets (from step 5, all distinct)
JWT_ACCESS_SECRET=<generated>
JWT_REFRESH_SECRET=<generated>
MEERA_STREAM_SIGNING_SECRET=<generated>
INTERNAL_SERVICE_TOKEN_SECRET=<generated>       # must byte-match Python SERVICE_TOKEN_SIGNING_KEY
INTERNAL_REQUEST_HMAC_SECRET=<generated>        # must byte-match Python INTERNAL_HMAC_KEY
INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET=<generated>

# Keys with no YAML placeholder — must use Spring relaxed-binding names
INFLUORA_JWKS_PRIVATEKEYPEM=<pkcs8-ec-p256-with-\n-escapes>
INFLUORA_JWKS_PUBLICKEYPEM=<ec-public-pem-with-\n-escapes>
INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY=<base64-32-bytes>
INFLUORA_PII_EMAILPHONEENCRYPTIONKEY=<base64-32-bytes>   # BACK THIS UP
INFLUORA_PII_BANKENCRYPTIONKEY=<base64-32-bytes>         # BACK THIS UP

# Internal service URLs — validator rejects localhost
BRAND_SAFETY_AI_BASE_URL=http://influora-ai:8000
TRENDSPARK_AI_BASE_URL=http://influora-ai:8000
MEERA_CHAT_AI_BASE_URL=http://influora-ai:8000
ANALYZE_SITE_AI_BASE_URL=http://influora-ai:8000
INFLUORA_MEERA_STREAM_PUBLICCHATURL=https://ai.yourdomain.com/chat
CLAMAV_HOST=clamav

# Money + comms — NO validator, silently break if wrong
RAZORPAY_KEY_ID=<from-razorpay>
RAZORPAY_KEY_SECRET=<from-razorpay>
RAZORPAY_WEBHOOK_SECRET=<from-razorpay>
RAZORPAYX_ACCOUNT_NUMBER=<from-razorpayx>
MSG91_AUTH_KEY=<from-msg91>
MSG91_TOKEN_AUTH=<from-msg91>
MSG91_WIDGET_ID=<from-msg91>
MSG91_OTP_TEMPLATE_ID=<from-msg91>
MSG91_WELCOME_TEMPLATE_ID=<from-msg91>
MSG91_EMAIL_TRANSACTIONAL_TEMPLATE_ID=<from-msg91>
R2_ACCOUNT_ID=<from-cloudflare>
R2_ACCESS_KEY_ID=<from-cloudflare>
R2_SECRET_ACCESS_KEY=<from-cloudflare>
R2_BUCKET_NAME=<from-cloudflare>
R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
R2_PUBLIC_URL=https://media.yourdomain.com

# GST identity — CompanyTaxStartupValidator rejects placeholders
INFLUORA_COMPANY_GSTIN=<real-gstin>
INFLUORA_COMPANY_ADDRESS=<registered-address>
INFLUORA_LEGAL_NAME=Influora Technologies Pvt. Ltd.
```

### 6.2 `/home/influora/env/ai.env` (Python)

```bash
APP_ENV=prod
LOG_LEVEL=INFO

ANTHROPIC_API_KEY=<from-console.anthropic.com>
GEMINI_API_KEY=<from-aistudio.google.com>
SARVAM_API_KEY=<from-dashboard.sarvam.ai>

# MUST byte-match the Java side — this pair is the #1 integration failure.
# They are DIFFERENT secrets from each other; swapping them breaks internal auth.
INTERNAL_HMAC_KEY=<same as Java INTERNAL_REQUEST_HMAC_SECRET>
SERVICE_TOKEN_SIGNING_KEY=<same as Java INTERNAL_SERVICE_TOKEN_SECRET>

SPRING_JWKS_URL=https://api.yourdomain.com/api/v1/.well-known/jwks.json
SPRING_INTERNAL_BASE_URL=http://influora-api:8080/api/v1   # /api/v1 required or every call 404s

REDIS_URL=redis://redis:6379   # required if you ever run >1 uvicorn worker
AI_DAILY_SPEND_CEILING_USD=15.0
AI_SPEND_KILL_SWITCH=false

# DEV_SHARED_JWT_SECRET must be ABSENT — never set it in prod (bypasses JWKS verification)
```

### 6.3 `/home/influora/env/mysql.env`

```bash
MYSQL_ROOT_PASSWORD=<strong-root-password>
MYSQL_DATABASE=influora
MYSQL_USER=influora_app
MYSQL_PASSWORD=<same as SPRING_DATASOURCE_PASSWORD>
```

### 6.4 `.env.production` (frontend — tracked in Git, holds NO secrets, all `VITE_*` are public)

Check these two before building — both are documented live traps in the repo today:

```bash
VITE_API_MODE=live                                  # build FAILS if not "live"
VITE_API_BASE_URL=https://api.yourdomain.com/api/v1  # build FAILS if unset/localhost — but a
                                                       # wrong non-localhost value (e.g. the
                                                       # committed api.influora.com placeholder)
                                                       # passes the guard silently. Check it by eye.
VITE_MEERA_STREAM_URL=https://ai.yourdomain.com       # NOT validated at all — unset falls back to
                                                       # the browser-unresolvable ai.influora.internal
VITE_ADMIN_WS_ENABLED=false                           # no backend WS endpoint exists yet
```

Lock down the backend env files:

```bash
chmod 600 /home/influora/env/*.env
```

---

## 7. Production compose file

The repo's root `docker-compose.yml` is **local-dev only** (MySQL + Redis + ClamAV, no app containers). Create `docker-compose.prod.yml`:

```yaml
name: influora

services:
  mysql:
    image: mysql:8.0
    restart: unless-stopped
    env_file: /home/influora/env/mysql.env
    ports:
      - "127.0.0.1:3306:3306"   # loopback only, never public
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
      --require-secure-transport=ON
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 10

  clamav:
    image: clamav/clamav:1.3
    restart: unless-stopped
    volumes:
      - clamav_data:/var/lib/clamav
    healthcheck:
      test: ["CMD", "clamdcheck.sh"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 90s

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: ["redis-server", "--save", "", "--appendonly", "no"]

  influora-api:
    build:
      context: ./influora-api
    restart: unless-stopped
    env_file: /home/influora/env/api.env
    ports:
      - "127.0.0.1:8080:8080"
    depends_on:
      mysql: { condition: service_healthy }

  influora-ai:
    build:
      context: ./influora-ai
    restart: unless-stopped
    env_file: /home/influora/env/ai.env
    ports:
      - "127.0.0.1:8000:8000"
    depends_on:
      - influora-api

  frontend:
    build:
      context: .
      args:
        VITE_API_MODE: live
        VITE_API_BASE_URL: https://api.yourdomain.com/api/v1
        VITE_MEERA_STREAM_URL: https://ai.yourdomain.com
    restart: unless-stopped
    ports:
      - "127.0.0.1:8081:80"

volumes:
  mysql_data:
  clamav_data:
```

`--require-secure-transport=ON` is what actually *enforces* TLS on the DB connection — the Java validator only string-checks the JDBC URL for the literal `useSSL=false`, so this compose flag is doing the real work.

---

## 8. TLS first — before you start the app

`AUTH_REFRESH_COOKIE_SECURE=true` means login cookies aren't sent over plain HTTP. Get certs live before you boot the app, not after.

`/etc/nginx/sites-available/influora`:

```nginx
server {
    listen 80;
    server_name app.yourdomain.com api.yourdomain.com ai.yourdomain.com;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl http2;
    server_name app.yourdomain.com;
    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 443 ssl http2;
    server_name api.yourdomain.com;
    client_max_body_size 1G;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}

server {
    listen 443 ssl http2;
    server_name ai.yourdomain.com;
    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE — Meera streams token-by-token. Without these, replies land all
        # at once, or the stream dies at 60s.
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        chunked_transfer_encoding off;
    }
}
```

```bash
ln -s /etc/nginx/sites-available/influora /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
nginx -t
certbot --nginx -d app.yourdomain.com -d api.yourdomain.com -d ai.yourdomain.com
systemctl reload nginx
systemctl list-timers | grep certbot   # confirm auto-renew is installed
```

---

## 9. Build and start

First build takes **10–20 minutes** (Java compiles ~1,000 files, Python downloads Chromium for Playwright).

```bash
cd /home/influora/influora
docker compose -f docker-compose.prod.yml build

# Data layer first — ClamAV needs time to pull its virus DB
docker compose -f docker-compose.prod.yml up -d mysql redis clamav
docker compose -f docker-compose.prod.yml ps    # wait for mysql = healthy

# App next — Flyway runs 56 migrations on first boot, be patient
docker compose -f docker-compose.prod.yml up -d influora-api
docker compose -f docker-compose.prod.yml logs -f influora-api

# Then the rest
docker compose -f docker-compose.prod.yml up -d influora-ai frontend
```

**If Java refuses to boot, that's the design working** — `SecretsStartupValidator` throws an itemised list of what's missing/wrong. Fix each line, restart. **Never set `APP_ENV=dev` to work around it** — that downgrades every check to a warning and would ship dev secrets to prod.

Python's equivalent failure (`app/main.py:65`): `RuntimeError: missing required secrets/config: ...`.

---

## 10. Verify — don't declare success on "containers are up"

```bash
docker compose -f docker-compose.prod.yml ps                       # 1. all healthy?
curl -f http://127.0.0.1:8080/api/v1/health                        # 2. Java internal
curl -f http://127.0.0.1:8000/healthz                              # 3. Python internal
curl -f https://api.yourdomain.com/api/v1/health                   # 4. public API over TLS
curl -f https://api.yourdomain.com/api/v1/.well-known/jwks.json    # 5. JWKS published
curl -fI https://app.yourdomain.com                                # 6. frontend serves

# 7. THE CRITICAL ONE — is the right API URL actually baked into the bundle?
curl -s https://app.yourdomain.com/assets/*.js | grep -o 'https://api[^"]*' | head
#    Must print YOUR domain. If it prints api.influora.com or localhost, the
#    build used the wrong --build-arg — rebuild the frontend, don't restart it.

# 8. Did Flyway apply all migrations?
docker compose -f docker-compose.prod.yml exec mysql \
  mysql -u root -p -e "SELECT COUNT(*), MAX(version) FROM influora.flyway_schema_history WHERE success=1;"
```

Then in a browser: register (proves MSG91), log in (proves TLS + Secure cookie + CORS), send one Meera message (proves the JWKS + HMAC chain end-to-end).

---

## 11. Redeploying

```bash
cd /home/influora/influora
git pull

# Backend/AI change
docker compose -f docker-compose.prod.yml up -d --build influora-api influora-ai

# Frontend change OR any VITE_* value change — restart alone is NOT enough
docker compose -f docker-compose.prod.yml up -d --build frontend

docker image prune -f
```

---

## 12. Backups — before go-live, not after

```bash
cat > /home/influora/backup.sh <<'EOF'
#!/bin/bash
set -euo pipefail
D=/home/influora/backups; mkdir -p "$D"
docker compose -f /home/influora/influora/docker-compose.prod.yml exec -T mysql \
  mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines influora \
  | gzip > "$D/influora-$(date +%F-%H%M).sql.gz"
find "$D" -name '*.sql.gz' -mtime +14 -delete
EOF
chmod +x /home/influora/backup.sh
( crontab -l 2>/dev/null; echo "0 3 * * * /home/influora/backup.sh" ) | crontab -
```

Back up `INFLUORA_PII_EMAILPHONEENCRYPTIONKEY` and `INFLUORA_PII_BANKENCRYPTIONKEY` in a password manager/vault **off this server**, right now. A DB backup without these is worthless — lost keys mean permanently unreadable data, no recovery path. Also enable Utho's own instance snapshot/backup.

---

## 13. Optional — n8n TrendSpark pipeline (external, run only if you want trend nudges)

**Skip this for a first deploy.** TrendSpark is *not production-authorized yet* ([docs/features/trendspark.md:70](docs/features/trendspark.md:70)), and the app boots and runs fine without it — the nudge card simply shows nothing until the `trends` table is populated. Add this once you want live trend data.

### What n8n is (and isn't) here

n8n is **not part of the Influora app** — Java only *reads* the `trends` table; it never talks to n8n. n8n is a separate scheduler that fills that table. There is one workflow file in the repo: [`trendspark/n8n/trend-pull-workflow.json`](trendspark/n8n/trend-pull-workflow.json). It has two schedule triggers:

- **06:00 IST** — pulls TMDb / NewsAPI / YouTube (+ a stubbed Google-Trends node), theme-tags them, and `INSERT`s rows into `trends`.
- **06:30 IST** — `DELETE`s expired rows so nothing stale reaches the nudge engine.

No API keys live in the JSON — every credential is stored in n8n's own encrypted credential store.

### 13.1 Add n8n to the compose stack (same internal network as MySQL)

n8n needs to reach the DB at `mysql:3306`, so it must share the Docker network. Add this service to `docker-compose.prod.yml`:

```yaml
  n8n:
    image: n8nio/n8n:latest
    restart: unless-stopped
    env_file: /home/influora/env/n8n.env
    ports:
      - "127.0.0.1:5678:5678"   # loopback only — reach it via SSH tunnel, never public
    volumes:
      - n8n_data:/home/node/.n8n
    depends_on:
      mysql: { condition: service_healthy }
```

And add `n8n_data:` under the top-level `volumes:` block.

> **Do not expose n8n to the internet.** It runs on cron internally and needs no public traffic. Reach its editor UI from your laptop with an SSH tunnel:
> `ssh -L 5678:127.0.0.1:5678 influora@<utho-ip>` → open `http://localhost:5678`.

### 13.2 `/home/influora/env/n8n.env`

```bash
# Encrypts the credential store on disk — generate once, NEVER lose it or you
# re-enter every credential. Back it up alongside your PII keys.
N8N_ENCRYPTION_KEY=<openssl rand -base64 32>

# Editor login (n8n's own basic auth — you still only reach it via the SSH tunnel)
N8N_BASIC_AUTH_ACTIVE=true
N8N_BASIC_AUTH_USER=influora
N8N_BASIC_AUTH_PASSWORD=<strong-password>

# Cron must fire in IST — the workflow's triggers assume Asia/Kolkata
GENERIC_TIMEZONE=Asia/Kolkata
TZ=Asia/Kolkata

N8N_HOST=127.0.0.1
N8N_PORT=5678
```

`chmod 600 /home/influora/env/n8n.env`.

### 13.3 A restricted DB user for n8n (do NOT reuse the app user)

The workflow only touches one table. Give it a user scoped to exactly that — blast-radius isolation, same principle as the rest of this stack. Run inside the MySQL container:

```sql
CREATE USER 'n8n_trends'@'%' IDENTIFIED BY '<strong-password>' REQUIRE SSL;
GRANT SELECT, INSERT, DELETE ON influora.trends TO 'n8n_trends'@'%';
FLUSH PRIVILEGES;
```

### 13.4 Import the workflow + add credentials

1. Start it: `docker compose -f docker-compose.prod.yml up -d n8n`
2. Tunnel in (13.1) and open `http://localhost:5678`.
3. **Import** → select `trendspark/n8n/trend-pull-workflow.json` from the repo.
4. Create these **four** credentials by the exact names the nodes reference (the workflow won't run until each is filled — the node `notes` document each one):

   | Credential name (must match) | Type | Detail | Value from |
   |---|---|---|---|
   | `TrendSpark TMDb API Key` | HTTP Query Auth | query param `api_key` | themoviedb.org |
   | `TrendSpark NewsAPI Key` | HTTP Header Auth | header `X-Api-Key` | newsapi.org (free: 100 req/day) |
   | `TrendSpark YouTube Data API Key` | HTTP Query Auth | query param `key` | Google Cloud Console → YouTube Data API v3 |
   | `TrendSpark MySQL (influora)` | MySQL | host `mysql`, port `3306`, db `influora`, user `n8n_trends`, SSL on | 13.3 above |

5. **Activate** the workflow (toggle top-right). Both the 06:00 pull and 06:30 expire triggers arm together.

> The **Google Trends** node is a deliberate stub — it emits `[]` so the run proceeds on the other three sources. Leave it as-is; wire pytrends or SerpAPI later only if you want that fourth source. TMDb + NewsAPI + YouTube are enough to populate `trends`.

### 13.5 Verify n8n

```bash
docker compose -f docker-compose.prod.yml logs -f n8n     # watch for the 06:00/06:30 executions
# After a run has fired, confirm rows landed:
docker compose -f docker-compose.prod.yml exec mysql \
  mysql -u root -p -e "SELECT COUNT(*), MAX(created_at) FROM influora.trends;"
```

You don't have to wait until 6 AM — in the n8n editor, open the workflow and click **Execute Workflow** once to trigger a manual run, then re-check the row count.

---

## 14. Known gaps — fine for staging, close before real money flows

1. **Rate limiting is per-instance** (`AuthRateLimitFilter` uses in-process counters). Fine on one box; add a second instance behind a load balancer and the effective limit multiplies. Move to Redis or the edge before scaling out.
2. **AI spend ceiling is per-process unless `REDIS_URL` is set.** Compose above sets it and runs a single worker — covered. Raising worker count without Redis multiplies your daily ceiling.
3. **`TRUSTED_PROXIES=172.17.0.1`** is the default Docker bridge gateway — verify with `docker network inspect bridge`. Wrong value = one abusive user rate-limits everyone.
4. **Single point of failure.** One box, no redundancy, every restart is downtime. For real production, split MySQL to Utho's managed DB and run ≥2 API instances.
5. **ClamAV is `@Profile("prod")` only** — activates because `SPRING_PROFILES_ACTIVE=prod`. If uploads hang, check ClamAV finished its first `freshclam` download.

---

## 15. Quick reference

| Task | Command |
|---|---|
| Status | `docker compose -f docker-compose.prod.yml ps` |
| All logs | `docker compose -f docker-compose.prod.yml logs -f` |
| One service | `docker compose -f docker-compose.prod.yml logs -f influora-api` |
| Restart one | `docker compose -f docker-compose.prod.yml restart influora-api` |
| Rebuild one | `docker compose -f docker-compose.prod.yml up -d --build influora-api` |
| Stop all | `docker compose -f docker-compose.prod.yml down` |
| **Kill AI spend** | Set `AI_SPEND_KILL_SWITCH=true` in `ai.env` → restart `influora-ai` |
| Renew certs | `certbot renew --dry-run` |
| Disk usage | `docker system df` |

---

**Deeper reference on any one topic:** `BLUEPRINT/06-DEPLOYMENT-AND-API-KEYS.md` (every key, blast-radius rationale, pre-flight checklist) and `BLUEPRINT/10-RUN-ON-SERVER-UTHO.md` (this same walkthrough with more narrative). This file is the condensed, linear version of both.
