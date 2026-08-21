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

> **curl cannot pass this section on its own.** curl never sends an `Origin` header, so **every
> CORS failure returns 200 to curl and only appears in a real browser.** Both breaks found in
> `bd5aa0b` — the apex origin and the empty `MEERA_ALLOWED_ORIGINS` — would have sailed through a
> curl-only check. The browser pass in §5.2 is the gate that actually closes them.

### 5.1 · Reachability and TLS

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

### 5.2 · Browser pass — the CORS gate

Open a real browser with the **devtools console visible** and keep it open throughout. A red
`blocked by CORS policy` line is a failure even if the page looks fine.

1. **Load `https://influora.in`.** Marketing page renders, padlock valid, console clean.
2. **Click through to Login from the apex — do not type the app URL by hand.** This is the exact
   path that was broken: you stay on the `influora.in` origin, so the API calls carry
   `Origin: https://influora.in`. Typing `app.influora.in` directly skips the origin under test
   and the check proves nothing.
3. **Register a new account, then log in.** Watch for `blocked by CORS policy` and for a failed
   `/auth/refresh` — the latter means the session cookie is not being sent.
4. **Land on a dashboard** (`/brand/dashboard` or `/creator/dashboard`), and confirm data loads
   rather than empty cards. Empty panels with a clean console usually mean the API returned an
   error the UI swallowed — check the Network tab, not just the console.
5. **Send one Meera message and watch it stream.** This is the only check that exercises
   `MEERA_ALLOWED_ORIGINS` and the SSE path together. Two distinct failures to tell apart:
   — nothing arrives at all, with a CORS error → the AI allowlist is wrong;
   — the reply arrives complete in one burst after a long pause → CORS is fine but SSE buffering
   is on, i.e. `proxy_buffering off` is missing from the `ai.` nginx block.
6. **Repeat step 2 from `https://app.influora.in`.** Both origins must work, not just one.

Then the Meta check, which no amount of curl will catch either: open **Connect Instagram** and
confirm the dialog URL carries a non-empty `client_id` (F-0374).

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

## 7 · Shared box: use `docker-compose.utho-shared.yml`

`150.241.245.242` is not a clean box. Measured 2026-08-21:

```
Mem:  7.3Gi total, 4.0Gi available     Swap: 0B
Also running: snaps-backend (Java, in a RESTART LOOP), n8n, host mysql.service, nginx
Dead since ~5 weeks: grafana, loki, promtail containers
```

Deploy `docker-compose.utho-shared.yml` here, not `docker-compose.utho.yml`. Do these four
first — the first is not optional.

**1. Add swap.** 4.0 GB available against a ~2.6 GB trimmed stack is a thin margin, and at swap
0 the kernel does not throttle under pressure, it kills the largest RSS. On this box that is
Snapsby or MySQL, not Influora — so an Influora memory spike takes down the neighbour.

```bash
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab
free -h
```

**2. Prepare the host MySQL** (the compose no longer ships one):

```sql
CREATE DATABASE influora CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'influora_app'@'172.%' IDENTIFIED BY '<MYSQL_PASSWORD from .env>';
GRANT ALL ON influora.* TO 'influora_app'@'172.%'; FLUSH PRIVILEGES;
```

Set `bind-address = 0.0.0.0` in `/etc/mysql/mysql.conf.d/mysqld.cnf`, restart mysql, then
**confirm 3306 is closed at the Utho firewall** — binding 0.0.0.0 exposes it to the internet
otherwise. Verify from your own machine, not from the box:

```bash
nc -zv 150.241.245.242 3306     # must FAIL
```

**3. Reclaim the dead containers:**

```bash
docker rm grafana loki promtail
```

**4. Fix `snaps-backend` first.** It was `Restarting (1)` — crash-looping, burning CPU, and
Snapsby is down while it does. Debugging two broken products on one box at once is how a short
outage becomes a long one.

```bash
docker logs --tail 100 snaps-backend
```

### What was NOT trimmed, and why

ClamAV stays, despite being the single largest container at ~1.2 GB.
`ClamAvMalwareScanService` is `@Profile("prod")` and documents itself as *fail-closed on every
non-OK outcome — infected, unparseable clamd reply, AND socket failure*. Remove the container
while `SPRING_PROFILES_ACTIVE=prod` and every upload throws, so deliverable submission stops
working. `NoOpMalwareScanService` cannot cover for it either — it is `@Profile("!prod")`.

The honest framing: this variant makes Influora fit beside a live product, it does not make the
box comfortable. Move to a dedicated server and `docker-compose.utho.yml` once the concept is
proven — before real payment volume, not after.
