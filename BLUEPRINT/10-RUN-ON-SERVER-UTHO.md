# 10 — Deploy Influora on an Utho Server (Step by Step)

> **Owner:** Priya (CTO) with Meera (DevOps) · **Verified against code:** 15 Jul 2026
> **Read `06-DEPLOYMENT-AND-API-KEYS.md` FIRST.** This guide assumes your keys are already generated.
> **Scope:** a single Utho Cloud instance running all three services. Good for staging and early production.

---

## 0. The three-language problem, and how we solve it

Influora is **three services in three languages**:

| Service | Language | Build tool | Produces | Port |
|---|---|---|---|---|
| Web SPA (`/src`) | **TypeScript** (Vite 6 / React 19) | `npm ci && npm run build` | Static files in `dist/` | 80 (nginx) |
| Core API (`/influora-api`) | **Java 21** (Spring Boot 3.3.5) | `mvn clean package` | Executable JAR | 8080 |
| AI service (`/influora-ai`) | **Python 3.13** (FastAPI) | `pip install -r requirements.txt` | Uvicorn app | 8000 |

**You do not install Node, Java, Maven, or Python on the server.** Each service already has a working multi-stage `Dockerfile` that installs its own toolchain inside the build container. The server needs **Docker only**.

> This matters: `influora-api/` has **no Maven wrapper** (`mvnw`), so a host build would need Maven installed manually. The Dockerfile sidesteps this by using the `maven:3.9-eclipse-temurin-21` image.

### The one asymmetry you must respect

Java and Python read config at **container-run time** (`env_file:` works). **TypeScript does not.** Vite bakes `VITE_*` into the JS bundle at **build time**, so those values are passed as `--build-arg`. Change a `VITE_*` value → you must **rebuild** the frontend image. `docker compose restart` will not pick it up.

### Traffic shape (why you need 3 hostnames)

`docker/nginx.conf` is a **static file server, not a reverse proxy** — its own comment says so. The browser talks to the API **directly**, at the URL baked into the bundle. So each public service needs its own TLS hostname:

```
                    ┌── app.yourdomain.com ──► frontend  :80   (static SPA)
Browser ── HTTPS ───┼── api.yourdomain.com ──► influora-api :8080 (/api/v1)
                    └── ai.yourdomain.com  ──► influora-ai  :8000 (SSE stream only)
                                                    │
              ┌─────────────────────────────────────┘
   internal:  influora-api ◄──HMAC──► influora-ai ──► MySQL / ClamAV / Redis
```

---

## 1. Provision the Utho instance

In the Utho dashboard → **Cloud → Deploy Instance**:

| Setting | Value | Why |
|---|---|---|
| **OS** | Ubuntu 22.04 LTS | Docker-supported, long-term support |
| **Plan** | **4 vCPU / 8 GB RAM / 80 GB SSD** minimum | See sizing note below |
| **Location** | India (Mumbai / Noida) | Data residency + latency; matches the India-only LLM region rule |
| **Backups** | Enable | Your PII keys and DB are unrecoverable otherwise |

### ⚠️ Do not under-size this

8 GB is a floor, not a suggestion. The memory is genuinely consumed:

- **ClamAV** loads its full virus database into RAM — **~2 GB on its own**, and its first `freshclam` download is slow (the healthcheck allows a 90s `start_period`).
- **Python + Playwright Chromium** — the Dockerfile runs `playwright install --with-deps chromium`, which is ~1 GB of browser plus system libs.
- **JVM** — Spring Boot with a 20-connection Hikari pool.
- **MySQL 8** — its own buffer pool.

A 2 GB or 4 GB instance will OOM. If budget is tight, the honest fix is to move **MySQL to Utho's managed database** and drop ClamAV to a smaller instance — not to shrink this box.

### Firewall (Utho dashboard → Firewall)

Allow **only**:

| Port | Source | Purpose |
|---|---|---|
| 22 | **Your IP only** | SSH |
| 80 | Anywhere | HTTP → redirects to HTTPS |
| 443 | Anywhere | HTTPS |

**Never expose 3306 (MySQL), 8080, 8000, or 3310 (ClamAV) to the internet.** They talk over Docker's internal network. The compose file below deliberately binds MySQL to `127.0.0.1` only.

---

## 2. DNS

Point three A-records at your instance's public IP:

```
app.yourdomain.com   A   <utho-ip>
api.yourdomain.com   A   <utho-ip>
ai.yourdomain.com    A   <utho-ip>
```

Wait for propagation (`dig +short api.yourdomain.com`) **before** requesting certificates — Certbot's HTTP-01 challenge will fail otherwise.

---

## 3. Base server setup

```bash
ssh root@<utho-ip>

# Patch first
apt update && apt upgrade -y

# Docker (official repo — Ubuntu's packaged docker.io is too old for compose v2)
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker
docker --version && docker compose version

# Nginx (host-level TLS terminator) + Certbot
apt install -y nginx certbot python3-certbot-nginx git

# A non-root deploy user
adduser --disabled-password --gecos "" influora
usermod -aG docker influora
```

### Harden SSH

```bash
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh
```

> Copy your SSH public key to the `influora` user **before** you run this, or you will lock yourself out.

### Swap (cheap OOM insurance)

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
git checkout main          # deploy a real branch, not a work-in-progress one
mkdir -p /home/influora/env && chmod 700 /home/influora/env
```

---

## 5. Write the env files

These are the files from doc 06. **They live outside Git.**

### 5.1 `/home/influora/env/api.env` (Java)

```bash
# ---- Environment gates: BOTH are required (doc 06 §2.3) ----
APP_ENV=prod
SPRING_PROFILES_ACTIVE=prod

# ---- Database (useSSL=true is mandatory — validator rejects useSSL=false) ----
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/influora?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC
SPRING_DATASOURCE_USERNAME=influora_app
SPRING_DATASOURCE_PASSWORD=<strong-password-not-root>

# ---- Public URLs ----
INFLUORA_API_PUBLIC_URL=https://api.yourdomain.com/api/v1
INFLUORA_WEB_BASE_URL=https://app.yourdomain.com
CORS_ALLOWED_ORIGINS=https://app.yourdomain.com

# ---- Behind the host Nginx ----
SERVER_FORWARD_HEADERS_STRATEGY=framework
TRUSTED_PROXIES=172.17.0.1
AUTH_REFRESH_COOKIE_SECURE=true

# ---- Signing secrets (all distinct, >=32 bytes) ----
JWT_ACCESS_SECRET=<generated>
JWT_REFRESH_SECRET=<generated>
MEERA_STREAM_SIGNING_SECRET=<generated>
INTERNAL_SERVICE_TOKEN_SECRET=<generated>       # == Python SERVICE_TOKEN_SIGNING_KEY
INTERNAL_REQUEST_HMAC_SECRET=<generated>        # == Python INTERNAL_HMAC_KEY
INFLUORA_BRANDSAFETYSERVICETOKEN_SIGNINGSECRET=<generated>

# ---- Keys with no YAML placeholder (doc 06 §2.2) ----
INFLUORA_JWKS_PRIVATEKEYPEM=<pkcs8-ec-p256-with-\n-escapes>
INFLUORA_JWKS_PUBLICKEYPEM=<ec-public-pem-with-\n-escapes>
INFLUORA_ADMIN_MFASECRETENCRYPTIONKEY=<base64-32-bytes>
INFLUORA_PII_EMAILPHONEENCRYPTIONKEY=<base64-32-bytes>   # BACK THIS UP
INFLUORA_PII_BANKENCRYPTIONKEY=<base64-32-bytes>         # BACK THIS UP

# ---- Internal service URLs (validator rejects localhost) ----
BRAND_SAFETY_AI_BASE_URL=http://influora-ai:8000
TRENDSPARK_AI_BASE_URL=http://influora-ai:8000
MEERA_CHAT_AI_BASE_URL=http://influora-ai:8000
ANALYZE_SITE_AI_BASE_URL=http://influora-ai:8000
INFLUORA_MEERA_STREAM_PUBLICCHATURL=https://ai.yourdomain.com/chat
CLAMAV_HOST=clamav

# ---- Money + comms (no validator — silently break if wrong) ----
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

# ---- GST identity (CompanyTaxStartupValidator rejects placeholders) ----
INFLUORA_COMPANY_GSTIN=<real-gstin>
INFLUORA_COMPANY_ADDRESS=<registered-address>
INFLUORA_LEGAL_NAME=Influora Technologies Pvt. Ltd.
```

### 5.2 `/home/influora/env/ai.env` (Python)

```bash
APP_ENV=prod
LOG_LEVEL=INFO

ANTHROPIC_API_KEY=<from-console.anthropic.com>
GEMINI_API_KEY=<from-aistudio.google.com>
SARVAM_API_KEY=<from-dashboard.sarvam.ai>

# MUST byte-match the Java side, and must NOT be swapped with each other
INTERNAL_HMAC_KEY=<same as Java INTERNAL_REQUEST_HMAC_SECRET>
SERVICE_TOKEN_SIGNING_KEY=<same as Java INTERNAL_SERVICE_TOKEN_SECRET>

SPRING_JWKS_URL=https://api.yourdomain.com/api/v1/.well-known/jwks.json
SPRING_INTERNAL_BASE_URL=http://influora-api:8080/api/v1   # /api/v1 required or every call 404s

REDIS_URL=redis://redis:6379
AI_DAILY_SPEND_CEILING_USD=15.0
AI_SPEND_KILL_SWITCH=false

# DEV_SHARED_JWT_SECRET is deliberately absent — never set it in prod
```

### 5.3 `/home/influora/env/mysql.env`

```bash
MYSQL_ROOT_PASSWORD=<strong-root-password>
MYSQL_DATABASE=influora
MYSQL_USER=influora_app
MYSQL_PASSWORD=<same as SPRING_DATASOURCE_PASSWORD>
```

### 5.4 Lock them down

```bash
chmod 600 /home/influora/env/*.env
```

---

## 6. Production compose file

The repo's `docker-compose.yml` only runs MySQL + ClamAV for **local dev**. Create `docker-compose.prod.yml` for the full stack:

```yaml
name: influora

services:
  mysql:
    image: mysql:8.0
    restart: unless-stopped
    env_file: /home/influora/env/mysql.env
    ports:
      - "127.0.0.1:3306:3306"   # loopback only — never public
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
        # BUILD-TIME ONLY. Changing these requires a rebuild, not a restart.
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

> **`--require-secure-transport=ON`** makes MySQL refuse non-TLS connections, which is what actually enforces the `useSSL=true` you put in the JDBC URL. Be aware: `SecretsStartupValidator` only greps the URL string for the literal `useSSL=false` — it is a **string check, not proof of TLS**. This MySQL flag is what makes it real.

---

## 7. TLS first — before you start the app

`AUTH_REFRESH_COOKIE_SECURE=true` is mandatory outside dev, and a `Secure` cookie is **not sent over plain HTTP**. Log in will not work until TLS is live. So do this now, not later.

Create `/etc/nginx/sites-available/influora`:

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
    client_max_body_size 1G;          # matches Spring's 1GB max-request-size
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;      # large deliverable uploads
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

        # SSE — Meera streams token-by-token. Without these, replies arrive
        # all at once at the end, or the stream dies at 60s.
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
```

Certbot installs a renewal timer automatically. Confirm: `systemctl list-timers | grep certbot`.

> **`proxy_buffering off` on the AI host is not optional.** Nginx buffers responses by default, which breaks Server-Sent Events — the whole point of Meera's streaming UX.

---

## 8. Build and start

Build order matters only for speed, not correctness. The Java image compiles ~1,000 files and Python downloads Chromium — **expect 10–20 minutes on the first build.**

```bash
cd /home/influora/influora

# Build all three languages. --no-cache only on the first run.
docker compose -f docker-compose.prod.yml build

# Data layer first — ClamAV needs time to pull its virus DB
docker compose -f docker-compose.prod.yml up -d mysql redis clamav
docker compose -f docker-compose.prod.yml ps    # wait for mysql = healthy

# Then the app. Flyway runs 56 migrations on first boot.
docker compose -f docker-compose.prod.yml up -d influora-api
docker compose -f docker-compose.prod.yml logs -f influora-api

# Then the rest
docker compose -f docker-compose.prod.yml up -d influora-ai frontend
```

### What a good Java boot looks like

Flyway applies **56 migrations sequentially** before the app serves traffic — this is why the healthcheck allows a 45s `start-period`. Be patient on first boot.

> **`baseline-on-migrate` is `false` in prod** (deliberately — `application-prod.yml`). If the schema-history table is missing or misconfigured, Flyway **fails loudly** instead of silently skipping migrations. That is correct. If you hit it, fix the history table; do **not** flip the flag.

### If it refuses to boot — that's the design working

`SecretsStartupValidator` throws with an itemised list:

```
Secrets startup validation failed (env=prod):
  - influora.jwt.access-secret is still the committed dev default
  - spring.datasource.url disables TLS (useSSL=false); SSL is required outside dev
```

Fix each line and restart. **Never work around this by setting `APP_ENV=dev`** — that downgrades every check to a warning and ships dev secrets to production.

Python's equivalent (`app/main.py:65`):

```
RuntimeError: missing required secrets/config: ANTHROPIC_API_KEY, INTERNAL_HMAC_KEY
```

---

## 9. Verify

Do not declare success on "the containers are up".

```bash
# 1. All healthy?
docker compose -f docker-compose.prod.yml ps

# 2. Java health (internal)
curl -f http://127.0.0.1:8080/api/v1/health

# 3. Python health (internal)
curl -f http://127.0.0.1:8000/healthz

# 4. Public API over TLS
curl -f https://api.yourdomain.com/api/v1/health

# 5. JWKS is published — Python cannot verify tokens without this
curl -f https://api.yourdomain.com/api/v1/.well-known/jwks.json

# 6. Frontend serves
curl -fI https://app.yourdomain.com

# 7. THE CRITICAL ONE: is the right API URL baked into the bundle?
curl -s https://app.yourdomain.com/assets/*.js | grep -o 'https://api[^"]*' | head
#    Must print YOUR domain. If it prints api.influora.com or localhost,
#    the build used the wrong --build-arg. Rebuild the frontend.

# 8. Did Flyway apply everything?
docker compose -f docker-compose.prod.yml exec mysql \
  mysql -u root -p -e "SELECT COUNT(*), MAX(version) FROM influora.flyway_schema_history WHERE success=1;"
```

Then, in a browser: register an account (proves MSG91), log in (proves TLS + `Secure` cookie + CORS), and send one Meera message (proves the JWKS + HMAC chain end-to-end).

---

## 10. Redeploying

```bash
cd /home/influora/influora
git pull

# Backend/AI change — rebuild + restart
docker compose -f docker-compose.prod.yml up -d --build influora-api influora-ai

# Frontend change, OR any VITE_* value change — MUST rebuild
docker compose -f docker-compose.prod.yml up -d --build frontend

docker image prune -f
```

> Restating the trap because it costs people hours: **a `VITE_*` change needs a rebuild.** Restarting the frontend container re-serves the same stale bundle.

---

## 11. Backups — do this before go-live, not after

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

### 🔑 Back up your encryption keys separately — and now

`INFLUORA_PII_EMAILPHONEENCRYPTIONKEY` and `INFLUORA_PII_BANKENCRYPTIONKEY` encrypt every email, phone, and bank row in the database.

**A database backup is worthless without these keys.** Lose them and the restored data is permanently unreadable — there is no recovery path. Store them in a password manager or a secrets vault, **off this server**, before you accept a single real user.

Also enable Utho's snapshot/backup on the instance itself.

---

## 12. Known gaps to close before real money flows

Verified in code today — this deploy is sound for staging, but these are open for production:

1. **Rate limiting is per-instance.** `influora.auth.rate-limit` uses in-process counters (`AuthRateLimitFilter`). It works on this single box; the moment you add a second instance behind a load balancer, the limit becomes N× looser. Move to Redis or the edge before scaling out.
2. **AI spend ceiling is per-process unless `REDIS_URL` is set.** The compose above sets it and runs `--workers 1`, so you're covered — but raising worker count without Redis multiplies your daily ceiling by the worker count.
3. **`TRUSTED_PROXIES=172.17.0.1` is the default Docker bridge gateway.** Verify yours (`docker network inspect bridge`). Get it wrong and every request looks like it comes from the proxy, so one abusive user rate-limits everyone.
4. **Single point of failure.** One box: no redundancy, and every restart is downtime. Fine for staging. For production, split MySQL onto Utho's managed database and run the API on ≥2 instances.
5. **ClamAV is `@Profile("prod")` only.** It activates *because* you set `SPRING_PROFILES_ACTIVE=prod`. If uploads start hanging, check ClamAV finished its first `freshclam` download.

---

## 13. Quick reference

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

**Previous:** `06-DEPLOYMENT-AND-API-KEYS.md` — every key, which file, and how.
