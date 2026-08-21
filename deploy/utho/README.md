# Deploying Influora on Utho — Option A (Caddy behind nginx)

The Utho box at `150.241.245.242` is **not empty**. nginx/1.26.3 runs there as the Cloudflare
origin for **snapsby.com**, holding ports 80 and 443. Caddy cannot bind them, and stopping nginx
would take snapsby.com down. Option A keeps nginx as the front door and puts Caddy behind it on
`127.0.0.1:8080`.

Files here differ from `deploy/hostinger/` in exactly two places: Caddy's `ports:` and the
Caddyfile's global block. Every service, image, env var, volume and site block is unchanged.

| file | goes to |
|---|---|
| `docker-compose.utho.yml` | `/opt/influora/docker-compose.yml` |
| `Caddyfile` | `/opt/influora/Caddyfile` |
| `nginx-influora.conf` | `/etc/nginx/sites-available/influora.conf` |
| *(you write it)* `.env` | `/opt/influora/.env`, root-owned, `chmod 600` |

## 0 · Before you start

- DNS is done: `app` / `api` / `ai.influora.in` all resolve to `150.241.245.242` (verified
  2026-08-21). Records live in the **GoDaddy** panel — nameservers are `ns35/ns36.domaincontrol.com`.
- **Rebuild the web image first.** `influora-web` bakes `VITE_API_BASE_URL` into the JS bundle at
  build time and currently defaults to `http://200.141.1.6/api/v1`. Run `publish-images.yml` via
  `workflow_dispatch` with `vite_api_base_url=https://api.influora.in/api/v1` and
  `vite_meera_stream_url=https://ai.influora.in`. No env var on the box can fix this after the fact.
- The `.env` needs `ROOT_DOMAIN=influora.in` (new — drives the apex/www site block), the other
  29 `${VAR}` placeholders in the compose, **plus the 7 `META_*` vars**
  (F-0374). Without `META_APP_ID`/`META_APP_SECRET`, `MetaApiProperties.isConfigured()` is false,
  Connect Instagram is dead at boot, and Meta App Review fails on that alone.

## 1 · Copy the files

```bash
ssh root@150.241.245.242 'mkdir -p /opt/influora'
scp deploy/utho/docker-compose.utho.yml root@150.241.245.242:/opt/influora/docker-compose.yml
scp deploy/utho/Caddyfile               root@150.241.245.242:/opt/influora/Caddyfile
scp deploy/utho/nginx-influora.conf     root@150.241.245.242:/etc/nginx/sites-available/influora.conf
```

The Caddyfile must sit **next to** the compose file — the compose bind-mounts `./Caddyfile`. If it
is missing, Docker creates a *directory* with that name and Caddy starts with no config.

## 2 · Certificates, before nginx points anywhere

certbot needs port 80, which nginx already serves. Issue first, then enable the site:

```bash
certbot certonly --webroot -w /var/www/html \
  -d influora.in -d www.influora.in \
  -d app.influora.in -d api.influora.in -d ai.influora.in
```

The apex and `www` are included because this deployment also serves the marketing site — see
§6. certbot writes that pair under `/etc/letsencrypt/live/influora.in/`, the path the
`influora.in` and `www.influora.in` server blocks expect.

Then enable and reload:

```bash
ln -s ../sites-available/influora.conf /etc/nginx/sites-enabled/influora.conf
nginx -t && systemctl reload nginx
```

`nginx -t` must pass before the reload. A failed reload leaves snapsby.com serving the old config,
which is the safe outcome, but do not skip the test.

## 3 · Bring the stack up

```bash
cd /opt/influora
docker login ghcr.io          # PAT with read:packages, if the packages are private
docker compose pull
docker compose up -d
docker compose logs -f caddy
```

## 4 · The X-Forwarded-For trap — read this, it is security-relevant

There are now **two** proxies in front of the API (nginx, then Caddy), where the Hostinger design
had one. The compose sets `SERVER_FORWARD_HEADERS_STRATEGY: native` so Tomcat's `RemoteIpValve`
walks `X-Forwarded-For` right-to-left, skipping entries that match `TRUSTED_PROXIES`.

With Option A the header arriving at the API looks like:

```
X-Forwarded-For: <real client>, <nginx>
```

`TRUSTED_PROXIES` must therefore match **both** the Docker bridge gateway (Caddy's peer address)
and nginx's address as it appears in the chain. Get it wrong in the permissive direction and the
valve trusts a client-supplied entry — which is Kabir's CR-11 Blocker-1: every IP-keyed rate limit,
login brute-force protection included, is defeated. Get it wrong in the restrictive direction and
every request appears to come from the proxy, so one abusive client rate-limits everyone.

Find the real values on the box and set them explicitly:

```bash
docker network inspect influora_default -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

Verify afterwards that the API sees real client IPs, not `127.0.0.1` or `172.x`, before trusting
any rate limit.

## 5 · Exit test — run from a machine that is NOT the server

```bash
curl -sS -o /dev/null -w "apex %{http_code}  tls=%{ssl_verify_result}\n" https://influora.in/
curl -sS -o /dev/null -w "www  %{http_code}  tls=%{ssl_verify_result}\n" https://www.influora.in/
curl -sS -o /dev/null -w "api  %{http_code}  tls=%{ssl_verify_result}\n" https://api.influora.in/actuator/health
curl -sS -o /dev/null -w "app  %{http_code}  tls=%{ssl_verify_result}\n" https://app.influora.in/
```

`www` returns **301**; every other line must be **200**, all with `tls=0`. A non-zero `tls` is
exactly the apex symptom this fixes — a certificate issued for the wrong domain.

Both `200` with `tls=0`. Then by hand: register, log in, land on a dashboard
(`app.influora.in/brand/dashboard` or `/creator/dashboard`). A green health check with a broken
registration is not a deploy.

Then the one that catches F-0374: open **Connect Instagram** and confirm the Meta dialog URL
carries a non-empty `client_id`. A blank one renders Meta's "Invalid app ID" screen — exactly what
a reviewer would see.

Finally confirm you did not break the neighbour:

```bash
curl -sS -o /dev/null -w "snapsby %{http_code} tls=%{ssl_verify_result}\n" https://snapsby.com
```

## 5b · Cross-origin: two allowlists, both must list all three origins

Serving the apex from the same bundle as `app.` has a consequence that is easy to miss until the
browser console fills with CORS errors: a visitor who lands on `influora.in` and clicks Login
**stays on that origin**. Their API calls carry `Origin: https://influora.in`, not
`https://app.influora.in`.

There are **two independent allowlists**, and they are enforced by different services:

| var | service | enforced by |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | Java API | `CorsConfig.java` |
| `MEERA_ALLOWED_ORIGINS` | Python AI | `config.py` |

Both are set in the compose to `https://${APP_DOMAIN},https://${ROOT_DOMAIN},https://www.${ROOT_DOMAIN}`.

Two things to know:

- **A wildcard is not an option.** `CorsConfig` calls `setAllowCredentials(true)`, and the AI side
  echoes an explicit origin for bearer auth. `*` is rejected by the browser in both cases, so every
  origin must be listed verbatim.
- **`MEERA_ALLOWED_ORIGINS` was previously absent entirely**, which `config.py` defaults to `""` —
  an empty allowlist, i.e. every browser origin refused. Meera streams to the browser
  (`meera-api.ts` calls `${VITE_MEERA_STREAM_URL}/stream` directly), so this would have broken
  Meera in production independently of the apex change. It is now set.

**The session cookie is fine and needs no change.** `AuthCookieService` issues it
`HttpOnly; Secure; SameSite=Strict` with `Path=/auth` and no explicit `Domain`, so it is host-only
to the API. `SameSite=Strict` is judged on the registrable domain, and `influora.in`,
`www.influora.in`, `app.influora.in` and `api.influora.in` all share `influora.in` — so the cookie
is still sent from every one of them. Only CORS needed widening, not the cookie scope.

## 6 · The apex, and why there is no WordPress to migrate

`influora.in` resolves to this box but nginx had no server block for it, so it fell through to the
default vhost: **502** on `:80` and a `CN=snapsby.com` certificate on `:443`. Visitors got an error
page or a browser warning. This runbook now fixes that.

The fix needs no new hosting, because the SPA already *is* the marketing site: `/` renders
`LandingPage`, with `/pricing`, `/about`, `/contact` and `/blog` alongside it, and `npm run build`
prerenders those routes to static HTML for SEO. So the apex proxies to the same Caddy -> frontend
container as `app.influora.in`.

Two consequences worth stating plainly:

- **Retiring Hostinger costs nothing here.** The marketing site lives in this repo and deploys with
  the app — no WordPress content to migrate, no second host to keep paying for. Export the old
  WordPress copy first if any of its wording is worth keeping; once the account closes it is gone.
- **`www` 301s to the bare domain**, so one canonical hostname owns the SEO instead of two
  hostnames serving identical content and splitting it.
