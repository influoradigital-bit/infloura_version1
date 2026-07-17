# How to Run Influora on a Server (Java · Python · Frontend)

> Production/staging deployment for all three services, using the real Dockerfiles in the repo.
> Lead: Meera (DevOps). Security rules by Kabir/Priya. Pairs with `06-DEPLOYMENT-AND-API-KEYS.md` (every key + where it goes).

Three deployable artifacts, each with its own Dockerfile:

| Service | Dockerfile | Runtime | Port | Health |
|---|---|---|---|---|
| Frontend SPA | `./Dockerfile` | nginx (static `dist/`) | 80 | `GET /` |
| Core API | `./influora-api/Dockerfile` | Temurin JRE 21 (layered jar) | 8080 | `GET /api/v1/health` |
| AI service | `./influora-ai/Dockerfile` | Python 3.13 (uvicorn) | 8000 | `GET /healthz` |

Plus **MySQL 8** and **Redis** as backing services.

---

## 1. Golden rules for server deploys

1. **Secrets come from the environment / secret manager** — never from a committed file. `SecretsStartupValidator` fails the API boot if a required secret is missing.
2. **The frontend bundle is built, not configured at runtime.** Vite inlines every `VITE_*` value at **build time**. You must pass them as **`--build-arg`** when you `docker build` the SPA — setting them with `docker run -e` does nothing.
3. **The AI service is private.** Only the API should reach it; restrict egress to Anthropic/Gemini/Sarvam + the API host (the Dockerfile documents this allow-list; enforce it at the network layer).
4. **TLS terminates at your proxy** (Nginx/Cloudflare/ALB). Set HSTS; lock `CORS_ALLOWED_ORIGINS` to your real frontend origin.

---

## 2. Build the three images

```bash
# Frontend — pass prod VITE_* as build args (baked into the bundle)
docker build -t influora-web \
  --build-arg VITE_API_MODE=live \
  --build-arg VITE_API_BASE_URL=https://api.yourdomain.com/api/v1 \
  --build-arg VITE_MEERA_STREAM_URL=https://api.yourdomain.com/api/v1/stream \
  .

# Core API — Maven builds a layered Spring Boot jar, runs as non-root
docker build -t influora-api ./influora-api

# AI service — installs deps + Playwright Chromium, runs as non-root, read-only FS
docker build -t influora-ai ./influora-ai
```

> The API image builds with `-DskipTests` for speed; run `mvn verify` in CI **before** building so you never ship a red build.

---

## 3. Run the services (single-host example)

Use `--env-file` per service (files kept off git, sourced from your secret manager). See `06-DEPLOYMENT-AND-API-KEYS.md` for the full variable list.

```bash
# Backing services
docker run -d --name influora-mysql --network influora \
  -e MYSQL_DATABASE=influora -e MYSQL_USER=influora \
  -e MYSQL_PASSWORD='***' -e MYSQL_ROOT_PASSWORD='***' \
  -v influora_mysql:/var/lib/mysql mysql:8.0
docker run -d --name influora-redis --network influora redis:7-alpine

# Core API (Flyway migrates on boot; generous start-period in its healthcheck)
docker run -d --name influora-api --network influora -p 8080:8080 \
  --env-file ./secrets/api.env influora-api

# AI service — private network only, NOT published to the public internet
docker run -d --name influora-ai --network influora \
  --env-file ./secrets/ai.env influora-ai

# Frontend (static, behind the proxy)
docker run -d --name influora-web --network influora -p 8081:80 influora-web
```

`secrets/api.env` (minimum for prod — full list in doc 06):
```env
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:mysql://influora-mysql:3306/influora?useSSL=true&serverTimezone=UTC
SPRING_DATASOURCE_USERNAME=influora
SPRING_DATASOURCE_PASSWORD=***
API_PUBLIC_URL=https://api.yourdomain.com
JWT_ACCESS_SECRET=***      JWT_REFRESH_SECRET=***
JWKS_KID=***  JWKS_PRIVATE_KEY_PEM=***  JWKS_PUBLIC_KEY_PEM=***
INTERNAL_SERVICE_TOKEN_SECRET=***   INTERNAL_REQUEST_HMAC_SECRET=***
CORS_ALLOWED_ORIGINS=https://app.yourdomain.com
# + Razorpay / Meta / MSG91 / R2 keys as features are enabled
```

`secrets/ai.env`:
```env
APP_ENV=production
ANTHROPIC_API_KEY=***   GEMINI_API_KEY=***   SARVAM_API_KEY=***
SPRING_INTERNAL_BASE_URL=http://influora-api:8080/api/v1
SPRING_JWKS_URL=http://influora-api:8080/api/v1/.well-known/jwks.json
SPRING_JWT_ISSUER=influora-api
SERVICE_TOKEN_SIGNING_KEY=***     # == API INTERNAL_SERVICE_TOKEN_SECRET
INTERNAL_HMAC_KEY=***             # == API INTERNAL_REQUEST_HMAC_SECRET
AI_DAILY_SPEND_CEILING_USD=50     AI_WORKSPACE_DAILY_SOFT_CAP_USD=5
```
The two shared secrets **must match** between `api.env` and `ai.env` or all AI calls 401/403.

---

## 4. Reverse proxy (Nginx / Cloudflare / ALB)

```
app.yourdomain.com  → influora-web:80      (static SPA)
api.yourdomain.com  → influora-api:8080    (Spring; TLS, HSTS, X-Forwarded-*)
                      influora-ai:8000      → reachable ONLY from influora-api (private)
```
- Proxy `/api/v1/*` and the SSE endpoint `/api/v1/stream` (disable buffering for SSE).
- Never expose `influora-ai` publicly.
- Register live webhook URLs + secrets: Razorpay `/api/v1/webhooks/razorpay`, Shopify `/api/v1/webhooks/shopify`, WooCommerce `/api/v1/webhooks/woocommerce`, conversions `/api/v1/webhooks/conversion` & `/webhooks/redemption`.

---

## 5. Database & migrations

- Use a **managed MySQL 8** in production (backups + optionally a read replica).
- **Flyway runs the 56 migrations automatically on API boot** — no manual DDL. Gate schema changes with a CI migration check.
- The API is **stateless (JWT)**, so you can run multiple instances behind a load balancer with no sticky sessions.

---

## 6. Scheduled jobs (important for multi-instance)

The API contains **11 `@Scheduled` jobs** (score calc, metrics polling, Meta token refresh, deliverable verification/cleanup, affiliate reconciliation/settlement, stale-token cleanup, email worker). If you run **more than one API instance**, guard these with a leader lock **or** run a single dedicated "worker" instance so jobs don't double-fire. Redis is a natural place for the lock.

---

## 7. Orchestrated deploy (Kubernetes — advanced)

- 3 Deployments (web / api / ai) + Services; **NetworkPolicy** so only `api` can reach `ai`, and `ai` egress is limited to the LLM hosts + `api`.
- Secrets as K8s `Secret` objects (or external-secrets from Vault/AWS SM), mounted as env.
- Readiness/liveness probes hit the health endpoints in the table at the top.
- API healthcheck `start-period` is generous because Flyway migrates before serving — set readiness `initialDelaySeconds` accordingly.
- Horizontal scale `web` and `api` freely; keep `ai` modest and cost-capped; run scheduled jobs on a single replica (or leader-locked).
- Zero-downtime rolling updates work because auth is stateless.

---

## 8. Go-live checklist

- [ ] CI green: `mvn verify` (API), `npm run typecheck && npm test && npm run build` (web), `pytest` + `pip-audit` (AI).
- [ ] All three images built; SPA built with **prod `--build-arg` VITE_* values**.
- [ ] Secrets in the secret manager; none in git; API boots (SecretsStartupValidator passes).
- [ ] MySQL managed + reachable; Flyway migrated cleanly; Redis reachable.
- [ ] `influora-ai` private; provider keys valid; cost caps + kill switch configured.
- [ ] TLS + HSTS; `CORS_ALLOWED_ORIGINS` locked to the real frontend origin.
- [ ] Webhooks registered (Razorpay/Shopify/Woo/conversion) with matching secrets.
- [ ] Admin MFA enrolled; JWKS keypair is production-only (not the dev keys).
- [ ] Scheduled jobs single-fire (leader lock or dedicated worker).
- [ ] Health checks green for web (80), api (8080 `/api/v1/health`), ai (8000 `/healthz`).
