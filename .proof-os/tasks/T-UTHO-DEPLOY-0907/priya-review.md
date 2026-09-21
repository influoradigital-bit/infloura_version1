# Priya — CTO review: deploying Influora to the Utho box (150.241.245.242)

**Task:** T-UTHO-DEPLOY-0907
**Date:** 2026-09-07
**Evidence read:** `.proof-os/tasks/T-UTHO-DEPLOY-0907/live-state.txt` (all 136 lines), every file in
`deploy/utho/`, `wiki/processes/OPS-F0697-instagram-system-caller.md`, `deploy/hostinger/` listing,
plus the repo sources the above reference.
**Rule applied:** every claim below cites `file:line` or `live-state.txt:NN`. Anything I could not
establish from those is labelled **UNVERIFIED** with the command that would settle it. I ran nothing
against 150.241.245.242.

---

## The one-paragraph version

The box and the repo describe two different systems. `deploy/utho/` is written for a box where Caddy
owns `127.0.0.1:8080` and a full seven-service compose stack runs from `/opt/influora`. On the actual
box there is no Caddy, `8080` is held by an unrelated Java process, three Influora containers run that
no file in `deploy/utho/` could have created, and the apex is served from a static directory with its
own `/api/` proxy that none of the repo's nginx files reproduce. Applying `deploy/utho/` as it stands
either fails at `nginx -t` (the lucky outcome) or takes the currently-working apex API path down.

---

## Q1 — Is `deploy/utho/` safe to apply to this box as it stands?

**No.** Seven specific ways it would change or break what is serving traffic today.

### 1.1 Caddy cannot start: `8080` is already taken

Both compose files publish Caddy on `127.0.0.1:8080`:

- `deploy/utho/docker-compose.utho.yml:62-64` — `ports:` / `- "127.0.0.1:8080:8080"`
- `deploy/utho/docker-compose.utho-shared.yml:78-79` — identical

The box already has a wildcard listener on that port:

> `live-state.txt:37` — `LISTEN 0 100 *:8080  users:(("java",pid=2658,fd=45))`

A wildcard (`*:8080`) bind conflicts with a subsequent specific (`127.0.0.1:8080`) bind, so
`docker-proxy` fails with `EADDRINUSE` and the `caddy` service never comes up. That java process is
not Influora: `live-state.txt:38` shows the same PID holding `127.0.0.1:8005`, Tomcat's shutdown
port, and `live-state.txt:89` shows a `tomcat9` install under `/usr/local/App/`. `docker ps`
(`live-state.txt:22-30`) lists no Caddy container at all.

This matters beyond Caddy, because **every** nginx file in `deploy/utho/` proxies to
`http://127.0.0.1:8080` — `nginx-influora.conf:65,86,111,138`, `nginx-influora-app.conf:41,66,93`,
`nginx-ai-influora.conf:47`, `install-ai-vhost.sh:180`. On this box every one of those would land in
the foreign Tomcat.

The Caddyfile's premise is stated at `deploy/utho/Caddyfile:16` — *"TLS therefore terminates at nginx
(certbot), which proxies plain HTTP to Caddy on 127.0.0.1:8080"* — and `Caddyfile:13-17` sets
`http_port 8080`. That premise is false on the current box.

### 1.2 `nginx-influora.conf` can never pass `nginx -t`

It references three per-host certificate directories:

- `deploy/utho/nginx-influora.conf:82-83` — `/etc/letsencrypt/live/app.influora.in/`
- `deploy/utho/nginx-influora.conf:103-104` — `/etc/letsencrypt/live/api.influora.in/`
- `deploy/utho/nginx-influora.conf:134-135` — `/etc/letsencrypt/live/ai.influora.in/`

The box has none of them:

> `live-state.txt:19` — `bysnaps.com   influora.in   README   snapsby.com`

And following the runbook does not create them. `deploy/utho/README.md:55-58` issues **one**
certificate with five SANs, and `README.md:61-62` says so explicitly — *"certbot writes that pair
under `/etc/letsencrypt/live/influora.in/`"*. A five-SAN cert under `live/influora.in/` does not
create `live/app.influora.in/`. So `README.md` §2 and `nginx-influora.conf` contradict each other,
and the file cannot be enabled by its own instructions. Failure is at `nginx -t`, which is the safe
direction, but it means §2→§3 as written simply does not work.

### 1.3 If those certs existed, this file would take the apex away from what is serving it

`deploy/utho/nginx-influora.conf:59` declares `server_name influora.in`. The box already has a block
for that name:

> `live-state.txt:52` — `server_name influora.in www.influora.in;` (the `:80` block)
> `live-state.txt:58` — `server_name influora.in www.influora.in;` (the `:443` block)
> `live-state.txt:6` — `influora.in -> /etc/nginx/sites-available/influora.in` (enabled)

nginx includes `sites-enabled/*` in sorted glob order. `influora.conf` sorts before `influora.in`
(common prefix `influora.`, then `c` < `i`), so the **new** file's block is defined first and wins the
hostname. nginx logs a conflicting-server-name warning and continues.

### 1.4 …and that silently kills the live API path

The block that is serving `influora.in` today has an API location:

> `live-state.txt:64-65` — `location /api/ { proxy_pass http://127.0.0.1:8082;`
> `live-state.txt:59` — `root /var/www/influora;`
> `live-state.txt:126` — `$ curl -sI https://influora.in/api/v1/health | head -1` → `live-state.txt:126` `HTTP/2 200`

`deploy/utho/nginx-influora.conf:64-72` has **only** `location /` → `127.0.0.1:8080`. There is no
`/api/` location anywhere in that file. So the moment it wins the apex, every API call the currently
served bundle makes stops reaching `127.0.0.1:8082` (`live-state.txt:36` — `LISTEN 0 100 *:8082
users:(("java",pid=1609031,fd=14))`) and instead goes to the foreign Tomcat on 8080. The site keeps
returning 200 for HTML (`live-state.txt:123`) while every logged-in action fails. That is the worst
failure shape available: green front page, dead product.

### 1.5 `README.md` §7 step 3 deletes containers that are running

> `deploy/utho/README.md:301` — *"Dead since ~5 weeks: grafana, loki, promtail containers"*
> `deploy/utho/README.md:332` — `docker rm grafana loki promtail`

That was measured 2026-08-21 (`README.md:296`). Today:

> `live-state.txt:28` — `grafana   grafana/grafana:latest   0.0.0.0:3000->3000/tcp   Up 9 days`
> `live-state.txt:29` — `loki      grafana/loki:latest      0.0.0.0:3100->3100/tcp   Up 9 days`
> `live-state.txt:30` — `promtail  grafana/promtail:latest  (none)                   Up 9 days`

All three are up. The runbook command destroys running observability. `README.md:300` is stale in the
other direction too — it says `snaps-backend` is *"in a RESTART LOOP"*, while `live-state.txt:26`
shows `Up 10 days (healthy)`.

### 1.6 The compose files would create a second, parallel stack — not update the running one

Neither compose file sets `container_name` for any service (searched both files; no `container_name`
key present). Compose therefore names containers `<project>-<service>-<n>`. The live names are bare:

> `live-state.txt:23` — `influora-api     influora-api:latest   (none)   Up 24 hours`
> `live-state.txt:24` — `influora-ai      influora-ai:latest    (none)   Up 12 hours (healthy)`
> `live-state.txt:25` — `influora-redis   redis:7-alpine        127.0.0.1:6380->6379/tcp   Up 13 days`

Note also the **image** names: `influora-api:latest` / `influora-ai:latest` — locally built tags. The
compose files pull from GHCR: `docker-compose.utho-shared.yml:136` (`ghcr.io/influoradigital-bit/influora-api:latest`),
`:259` (`influora-ai`), `:299` (`influora-web`). Different registry, different naming. Nothing in
`deploy/utho/` created what is running.

Running `docker compose up -d` from `deploy/utho/` would therefore stand up a **second** api, a
**second** ai, a **third** redis (host redis at `live-state.txt:39`, container redis at
`live-state.txt:25`), a `mysql:8.0` container (`docker-compose.utho-shared.yml:96`), ClamAV
(`:123`) and a frontend (`:299`) alongside the live ones — on a box `README.md:299` measured at
4.0 GB available with zero swap.

### 1.7 The new API would point at an empty database

`docker-compose.utho-shared.yml:147` hardcodes
`jdbc:mysql://mysql:3306/influora?useSSL=true&...` — the compose's own fresh MySQL container. The box
runs a host MySQL (`live-state.txt:34` — `LISTEN 0 151 0.0.0.0:3306 users:(("mysqld",pid=1029,fd=42))`).
Which database the **running** `influora-api` uses is **UNVERIFIED**. Command:
`docker inspect influora-api --format '{{json .Config.Env}}' | tr ',' '\n' | grep -i datasource`.
If it is the host MySQL, the compose stack would Flyway-migrate an empty schema while the real data
sits untouched elsewhere — and `README.md:317-327` records exactly why the host instance is
dangerous anyway (host is MySQL 8.4.7; Flyway 10.10.0 supports up to 8.1; 107 migrations proven only
against 8.0).

**Q1 answer: not safe. Do not apply `deploy/utho/` as a unit.**

---

## Q2 — What is the actual deploy target and restart mechanism, and where does the repo disagree?

### 2.1 What the box shows

**Front door.** nginx, three enabled sites (`live-state.txt:5-7`): `grafana-test`, `influora.in`,
`snapsby.com`. nginx holds `0.0.0.0:80` and `0.0.0.0:443` (`live-state.txt:45-46`). The only Influora
hostnames declared anywhere are `influora.in` and `www.influora.in` (`live-state.txt:15-16`). There is
**no** `app.`, `api.` or `ai.influora.in` server block in the `server_name` sweep
(`live-state.txt:9-16`).

**Frontend.** Static, from disk: `root /var/www/influora;` (`live-state.txt:59`) with
`try_files $uri $uri/ /index.html;` (`live-state.txt:74`). Not a container. No `frontend`/`influora-web`
container exists in `docker ps` (`live-state.txt:22-30`).

**API.** `location /api/ { proxy_pass http://127.0.0.1:8082; }` (`live-state.txt:64-65`), answered by
`java pid=1609031` on `*:8082` (`live-state.txt:36`), returning 200 (`live-state.txt:126`). The API is
reached at `influora.in/api/`, **same origin as the SPA** — there is no `api.influora.in` in play.

**AI.** `influora-ai` container `Up 12 hours (healthy)` (`live-state.txt:24`); `uvicorn pid=4031544` on
`0.0.0.0:8000` (`live-state.txt:33`). No nginx route to it (see Q3).

**Restart mechanism — UNVERIFIED.** `influora-api` and `influora-ai` show `PORTS (none)`
(`live-state.txt:23-24`) yet their processes appear in the **host's** `ss` output
(`live-state.txt:33,36`) rather than behind a `docker-proxy` entry the way every published port does
(`live-state.txt:40-44`). The consistent reading is `network_mode: host`, but the capture cannot
distinguish that from "the java/uvicorn on the host are separate processes and the containers are
idle". Commands that settle it:

```
docker inspect influora-api influora-ai --format '{{.Name}} netmode={{.HostConfig.NetworkMode}} pid={{.State.Pid}}'
docker inspect influora-api --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
ls -la /opt/influora /usr/local/App/influora /usr/local/App/influora-ai
```

The third is the important one: it names the compose file that actually owns these containers. Until
it is run, **nobody knows which file `docker compose up -d influora-api` should be run against**, and
that is the single largest gap in this evidence pack.

### 2.2 Where the repo disagrees with the box, and what it costs

| Repo says | Box shows | Cost of believing the repo |
|---|---|---|
| `install-ai-vhost.sh:234-236` — `cd /opt/influora` … `docker compose up -d influora-api` | `/opt/influora` appears nowhere in the capture; `live-state.txt:84-85` show `/usr/local/App/influora` and `/usr/local/App/influora-ai` | `cd` fails, or worse, an empty `/opt/influora` gets a stray compose file and a duplicate stack |
| `OPS-F0697:54-55` — *"Live stack is `/usr/local/App/docker-compose.prod.yml` on Utho (NOT `deploy/utho/`…). Add to the `.env` beside it"* | `live-state.txt:120` — *"this file declares exactly ONE service; no other service is present in it"*; `live-state.txt:93,97` show that service is `backend` / `container_name: snaps-backend`, with `CORS_ORIGINS` defaulting to `https://snapsby.com` (`live-state.txt:105`) and `ports: - "8081:8080"` (`live-state.txt:111`) | **This is Snapsby's compose, not Influora's.** OPS-F0697 step 5 writes a live Meta access token into a neighbouring product's `.env`; step 7 ("restart the API container") restarts `snaps-backend` — an unrelated production service — and the Influora API never sees the variable. Step 7's own check ("the WARN block must be absent") would correctly report failure, after an unnecessary outage on someone else's product. |
| `install-ai-vhost.sh:236` — set `MEERA_PUBLIC_CHAT_URL=…` in the `.env` | Neither compose file interpolates `${MEERA_PUBLIC_CHAT_URL}`; both set `INFLUORA_MEERA_STREAM_PUBLICCHATURL: https://${AI_DOMAIN}/chat` (`docker-compose.utho.yml:180`, `docker-compose.utho-shared.yml:190`), and `generate-env.sh` never emits it either | The documented remediation is a **no-op**. The operator edits `.env`, restarts, and the URL is unchanged — with no error anywhere. |
| `README.md:96-109` §4 — a whole security section on setting `TRUSTED_PROXIES` correctly, invoking Kabir's CR-11 Blocker-1 | `influora-api/src/main/resources/application.yml:184` — *"The `TRUSTED_PROXIES` env var is still passed by deploy/hostinger/\*.yml and **is inert**"*; the live knob is `server.tomcat.remoteip.internal-proxies` (`application.yml:111,117`), which **no file under `deploy/` forwards** (searched `deploy/` for `TOMCAT_INTERNAL_PROXIES`: no matches) | An operator follows §4, sets `TRUSTED_PROXIES`, and believes IP-keyed rate limiting is now correctly scoped. Nothing was configured. See §4.5 below — the outcome happens to be safe, the belief is false, and the obvious "fix" is harmful. |
| `README.md:300-301` §7 — snaps-backend crash-looping, grafana/loki/promtail dead | `live-state.txt:26,28-30` — all four up | Runbook step 3 deletes running containers; step 4 sends the operator debugging a healthy service |
| `README.md:5-6` / `Caddyfile:16` — Caddy on `127.0.0.1:8080` is the routing layer | No Caddy container (`live-state.txt:22-30`); `*:8080` held by foreign java (`live-state.txt:37`) | Every nginx vhost in the directory points at the wrong process |

**Q2 answer:** the deploy target is nginx + a static `/var/www/influora` root + a Java process on
`:8082`, with the Influora container set owned by an as-yet-unidentified compose file. The repo names
`/opt/influora` and `/usr/local/App/docker-compose.prod.yml`; the first is not in evidence and the
second is Snapsby's. Acting on either restarts the wrong product or does nothing while appearing to
succeed.

---

## Q3 — What would have to be true for Meera to have a working public route?

### 3.1 Current state, from evidence

The service is **running and healthy** but **has no public route**:

- `live-state.txt:24` — `influora-ai  influora-ai:latest  (none)  Up 12 hours (healthy)`
- `live-state.txt:33` — `LISTEN 0 2048 0.0.0.0:8000 users:(("uvicorn",pid=4031544,fd=11))`
- `live-state.txt:129` — external probe: `port 8000  blocked/filtered`
- `live-state.txt:5-7` — enabled sites are `grafana-test`, `influora.in`, `snapsby.com`. No `ai.` vhost.
- `live-state.txt:9-16` — no `ai.influora.in` in any `server_name`.
- `live-state.txt:19` — no `ai.influora.in` under `/etc/letsencrypt/live/`.

So `https://ai.influora.in` resolves to this box (per `README.md:20-21`, verified 2026-08-21 — **not**
re-confirmed in this capture; `dig +short ai.influora.in` would confirm) and falls through to the
default vhost. `deploy/utho/nginx-ai-influora.conf:5-8` records that this was checked live on
2026-09-06 and served *snapsby HTML under a CN=snapsby.com certificate*. `live-state.txt` contains no
independent re-test; the command is
`echo | openssl s_client -connect ai.influora.in:443 -servername ai.influora.in 2>/dev/null | openssl x509 -noout -subject`.

### 3.2 The six conditions, all of which must hold

1. **A certificate at `/etc/letsencrypt/live/ai.influora.in/`.** Absent today (`live-state.txt:19`).
   Requires a `:80` vhost serving `/var/www/html` for the ACME challenge *before* any `:443` block
   referencing the cert exists — `install-ai-vhost.sh:103-127` gets this two-phase order right, and
   `nginx-ai-influora.conf:17-19` explains why a one-shot install cannot work.

2. **An enabled `ai.influora.in` vhost whose upstream actually reaches uvicorn.** Every repo file
   points at `127.0.0.1:8080` (`nginx-ai-influora.conf:47`, `install-ai-vhost.sh:180`). On this box
   `8080` is the foreign Tomcat (`live-state.txt:37`) and there is no Caddy. **The correct upstream
   here is `127.0.0.1:8000`** (`live-state.txt:33`). This one-line difference is the whole gap between
   "the vhost installs cleanly" and "Meera works".

3. **The SSE set on that location.** `proxy_buffering off; proxy_cache off; proxy_set_header
   Connection ''; proxy_read_timeout 3600s;` — `nginx-ai-influora.conf:62-68`. Correct as written;
   keep it verbatim. Without it the symptom is a hang followed by a burst, which reads as an app bug
   (`README.md:158-161`).

4. **`influora.meera.stream.public-chat-url = https://ai.influora.in/chat` on the API.** Spring's env
   form for that property is `INFLUORA_MEERA_STREAM_PUBLICCHATURL` (relaxed binding strips the
   hyphens), which is what the compose files set (`docker-compose.utho.yml:180`). `application-prod.yml:79`
   binds the same property to `${MEERA_PUBLIC_CHAT_URL}`, so both names exist in the codebase and only
   one is wired into the compose — this is the source of the `install-ai-vhost.sh:236` no-op.
   Critically, the boot validator **cannot** catch the current wrong value:
   `SecretsStartupValidator.java:617-635` only rejects loopback and non-HTTPS, and
   `https://influora.in/meera/chat` (the value recorded at `nginx-ai-influora.conf:7-8`) is neither —
   it is HTTPS and non-loopback, so the API boots clean and hands browsers a URL where the static SPA
   vhost answers `POST` with nginx's own 405. Live value is **UNVERIFIED**; command:
   `docker exec influora-api env | grep -iE 'PUBLICCHATURL|MEERA_PUBLIC_CHAT_URL'`.

5. **`MEERA_ALLOWED_ORIGINS` on the Python service must list the origin actually serving the SPA.**
   That origin today is `https://influora.in` (`live-state.txt:59` — the SPA is served from the apex
   root), not `app.influora.in`. Compose derives it from `APP_DOMAIN`/`ROOT_DOMAIN`
   (`docker-compose.utho-shared.yml:292`). `README.md:194-197` records that this var was previously
   absent entirely, which `config.py` defaults to `""` — an empty allowlist, every browser origin
   refused. Live value **UNVERIFIED**; command:
   `docker exec influora-ai env | grep MEERA_ALLOWED_ORIGINS`.

6. **The served bundle must carry `VITE_MEERA_STREAM_URL=https://ai.influora.in`.** The browser calls
   the AI host directly — `src/lib/meera-api.ts:39` reads `import.meta.env?.VITE_MEERA_STREAM_URL`
   with fallback `'https://ai.influora.internal'`, a host that does not resolve. `.env.production`
   sets only four VITE vars (lines 11, 33, 60, 68) and **`VITE_MEERA_STREAM_URL` is not among them**.
   So any locally-built production bundle has Meera dead in the browser regardless of server config.
   Whether the bundle currently at `/var/www/influora` was built with it is **UNVERIFIED**; command:
   `grep -rho 'https://ai\.influora\.[a-z]*' /var/www/influora/assets/*.js | sort -u`.

**Q3 answer:** all six. Conditions 2 and 6 are the ones the repo will actively lead you away from, and
`README.md:113-116` makes the governing point itself — curl sends no `Origin`, so a curl-only check
passes while a browser fails. Only the browser pass at `README.md:157-161` closes conditions 5 and 6.

---

## Q4 — Severity ranking

### Security-blocking

**S1. Root SSH by password, no key.**
> `live-state.txt:135` — *"authentication succeeded by PASSWORD; no public key in ~/.ssh reachable this box"*

Password-only root on a host that runs two products' databases and holds R2/Anthropic/Razorpay/MSG91
credentials (`live-state.txt:108` — the env block enumerates them). This is the top item because it is
the only finding whose exploitation grants everything else, and because every remediation step below is
performed over this same channel. It is also the finding most likely to already be compromised: the
root password for this class of box has previously lived in a tracked repo file.

**S2. n8n exposed to the open internet on `:5678`.**
> `live-state.txt:42` — `LISTEN 0 4096 0.0.0.0:5678 users:(("docker-proxy",pid=1881,fd=7))`
> `live-state.txt:132` — `port 5678  REACHABLE`

The only externally reachable port in the probe besides 80/443. n8n stores workflow credentials for
every service it automates, is not behind nginx (no `server_name` or proxy for it in
`live-state.txt:9-16`), and therefore has no TLS — credentials transit in cleartext. Security-blocking
because it is confirmed reachable *right now*, not merely bindable.

**S3. Wide binds defended only by the perimeter firewall.**
> `live-state.txt:33` — uvicorn on `0.0.0.0:8000`
> `live-state.txt:34-35` — mysqld on `0.0.0.0:3306` and `0.0.0.0:33060`
> `live-state.txt:43-44` — grafana `0.0.0.0:3000`, loki `0.0.0.0:3100`
> `live-state.txt:130-131` — those ports currently `blocked/filtered`

Every one of these should be bound to `127.0.0.1` (the box already does this correctly for redis —
`live-state.txt:39-40`). Today a single firewall-rule edit exposes the production database and an
LLM-spending endpoint. Security-blocking rather than operational because the failure mode is
data exfiltration with no application-layer control behind it — S3 is what turns any future firewall
mistake into an incident instead of an inconvenience.

**S4. §4 of the runbook creates false assurance about IP-keyed rate limiting.**
> `README.md:96-109` instructs setting `TRUSTED_PROXIES` and invokes Kabir's CR-11 Blocker-1
> `application.yml:184` — *"The `TRUSTED_PROXIES` env var … is inert"*
> `docker-compose.utho-shared.yml:166` — `TRUSTED_PROXIES: ${TRUSTED_PROXIES}   # verify: docker network inspect`
> `generate-env.sh:40` — `TRUSTED_PROXIES=172.16.0.0/12`
> `application.yml:117` — the real knob, `internal-proxies`, defaulting to a regex covering
> `127.\d+`, `10.`, `192.168.`, `172.16-31.` — and **no `deploy/` file forwards `TOMCAT_INTERNAL_PROXIES`**

The current posture is accidentally correct: the default regex already trusts both `127.0.0.1` (nginx)
and the Docker bridge. It is security-blocking as *documentation* because an operator who follows §4
believes brute-force protection was tuned when nothing was, and the natural "fix" — narrowing
`internal-proxies` to only the bridge subnet, as §4 implies — would collapse every client to the proxy
IP and let one abusive client rate-limit the whole platform (`README.md:99-100` describes that exact
failure, while pointing at the wrong variable).

**S5. OPS-F0697 step 5 writes a live Meta token into the wrong product's `.env`.**
> `OPS-F0697:54-58` — add `META_SYSTEM_IG_ACCESS_TOKEN` to the `.env` beside `/usr/local/App/docker-compose.prod.yml`
> `live-state.txt:120` — that compose declares exactly one service
> `live-state.txt:97` — `container_name: snaps-backend`
> `live-state.txt:82` — `-rw------- 1 root root 3295 Aug 21 19:42 .env`

A long-lived Instagram Business token would land in a file scoped to a different product, reachable by
anything that can read that container's environment, while the service that needs it never receives
it. The file mode is at least `600` today (`live-state.txt:82`), which is the one thing OPS-F0697
step 6 asks for and the box already satisfies.

### Operational risk

**O1. Port 8080 collision — Caddy cannot start, every repo vhost misroutes.** §1.1 above.
Blocks the entire `deploy/utho/` topology. Highest-ranked operational item because it invalidates
seven files at once.

**O2. `install-ai-vhost.sh` preflight misses the collision and then misdiagnoses it.**
> `install-ai-vhost.sh:66` — `if ss -lnt 2>/dev/null | grep -q '127\.0\.0\.1:8080'; then`
> `live-state.txt:37` — the listener prints as `*:8080`, not `127.0.0.1:8080`

The grep does not match, so the script reports *"NOTHING is listening on 127.0.0.1:8080"*
(`install-ai-vhost.sh:69`) — which is false; the port is taken, by something that is not Caddy. Run
non-interactively as documented (`install-ai-vhost.sh:4-5`), it dies at line 85. Run with
`ASSUME_YES=1`, it installs a vhost pointing at the foreign Tomcat, and its own verify step maps the
resulting status to *"still 405 — nginx is still not routing this host to the app"*
(`install-ai-vhost.sh:224`), sending the operator to debug nginx when the fault is the upstream. The
script's safety contract (`install-ai-vhost.sh:7-17`) is otherwise sound and its rollback
(`:33-48`, `:242-244`) is real.

**O3. Apex takeover with no `/api/` location.** §1.3–1.4. Gated behind O4 today.

**O4. `nginx-influora.conf` references certs its own README does not create.** §1.2. Fails closed.

**O5. `README.md` §7 step 3 deletes running containers.** §1.5.

**O6. Compose would create a parallel stack against an empty database.** §1.6–1.7.

**O7. `.env.production` bakes an unreachable API host.**
> `.env.production:33` — `VITE_API_BASE_URL=https://api.influora.in/api/v1`
> `live-state.txt:5-7,9-16,19` — `api.influora.in` has no server block and no certificate

A local `npm run build` deployed to `/var/www/influora` would break the app completely, even though the
box's own same-origin `/api/` proxy works fine (`live-state.txt:64-65,126`). The comment block at
`.env.production:13-27` documents that this line has been wrong before in a different way; the value is
right for the *intended* topology and wrong for the *current* one.

**O8. `VITE_MEERA_STREAM_URL` unset; fallback is a non-resolvable host.** `src/lib/meera-api.ts:39`;
absent from `.env.production` (only lines 11, 33, 60, 68 set VITE vars).

**O9. Swap and disk headroom unknown.** `README.md:299` recorded `Swap: 0B` on 2026-08-21 and
`README.md:307-310` explains why that matters — at swap 0 the kernel OOM-kills the largest RSS, which
on this box is Snapsby or MySQL, not Influora. `live-state.txt` captures no `free -h`. Disk is also
unknown, and `live-state.txt:87-88` shows roughly 800 MB of dated `snapsbyugc-backend.jar` copies plus
a 13 MB Tomcat zip (`live-state.txt:86`) sitting in `/usr/local/App/`. Commands:
`free -h; swapon --show; df -h /`.

**O10. Container provenance unknown.** §2.1. Until `docker inspect … config_files` is run, there is no
known-correct way to restart `influora-api` with a changed environment variable — which is a
precondition for the Meera fix (Q3 condition 4).

### Cosmetic

**C1.** `README.md:8-9` claims the Utho files differ from `deploy/hostinger/` *"in exactly two
places"*. `deploy/utho/` additionally contains four nginx configs and two shell scripts that
`deploy/hostinger/` does not (directory listings: hostinger holds only `Caddyfile`,
`docker-compose.hostinger.yml`, `docker-compose.test.yml`). Harmless, but the claim is false and
invites a wrong mental model.

**C2.** `nginx-influora-app.conf:87` and `nginx-ai-influora.conf:41` both declare `ai.influora.in`;
only one may ever be enabled. This is already documented at `nginx-ai-influora.conf:10-14` and guarded
at `install-ai-vhost.sh:90-93`, so it is a labelled hazard rather than a defect.

**C3.** `README.md:296` dates §7's box inventory but nothing in the section tells the reader to
re-measure. Given O5, it should.

---

## Q5 — A safe, step-wise, individually reversible ordering

Each step below is independently reversible and independently verifiable. Do not batch them.

**Step 0 — Establish the unknowns. No change to the box.**
```
docker inspect influora-api influora-ai --format '{{.Name}} netmode={{.HostConfig.NetworkMode}} img={{.Config.Image}}'
docker inspect influora-api --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}'
docker inspect influora-api --format '{{json .Config.Env}}' | tr ',' '\n' | grep -iE 'datasource|publicchaturl|allowed_origins'
docker exec influora-ai env | grep -iE 'MEERA_ALLOWED_ORIGINS|PORT'
ls -la /opt/influora /usr/local/App/influora /usr/local/App/influora-ai
free -h; swapon --show; df -h /
dig +short ai.influora.in app.influora.in api.influora.in
grep -rho 'https://ai\.influora\.[a-z]*' /var/www/influora/assets/*.js | sort -u
```
*Rollback:* none needed — read-only. **This step is mandatory. Steps 4 and 5 are not executable
without its output.**

**Step 1 — Snapshot the VM before any write.** Utho panel snapshot, or `mcp__hostinger-vps__` is the
wrong provider here — this is Utho, not Hostinger, so it is the Utho console.
*Rollback:* restore the snapshot. This is the only rollback that covers a mistake in steps 2–9
simultaneously.

**Step 2 — Security, before anything else, because every later step rides this channel.**
(a) Install an SSH key, confirm key login in a *second* session, then set
`PasswordAuthentication no` and reload sshd (S1).
(b) Close `5678` at the firewall, or put n8n behind an nginx vhost with TLS and auth (S2).
*Rollback (a):* the second session is still open — revert `PasswordAuthentication yes`, reload.
Never close the first session until key login is proven.
*Rollback (b):* re-open the firewall rule / `rm` the vhost symlink and reload.

**Step 3 — Add swap, only if Step 0 confirms it is still 0.** `README.md:311-315`.
*Rollback:* `swapoff /swapfile && rm -f /swapfile` and delete the `/etc/fstab` line.

**Step 4 — The `ai.influora.in` vhost, two-phase, with the upstream corrected.**
Use `install-ai-vhost.sh` for its phase ordering, certbot dry-run and conflict check
(`:90-93`, `:135-141`), but **first change `proxy_pass http://127.0.0.1:8080;` to
`http://127.0.0.1:8000;`** at `install-ai-vhost.sh:180`, and either fix the preflight at
`install-ai-vhost.sh:66` to grep `:8000` or run with `ASSUME_YES=1` having confirmed the upstream by
hand. Keep the SSE set (`:194-200`) verbatim.
*Rollback:* `rm -f /etc/nginx/sites-enabled/ai-influora.conf && nginx -t && systemctl reload nginx`
(`install-ai-vhost.sh:243-244`). The issued certificate is harmless if left in place. `nginx -t` gates
every reload (`:125`, `:205`), so a failed test leaves `influora.in` and `snapsby.com` on the config
they already had.

**Step 5 — Point the API at the new host.** Set `INFLUORA_MEERA_STREAM_PUBLICCHATURL=https://ai.influora.in/chat`
(**not** `MEERA_PUBLIC_CHAT_URL` — see §2.2) in the env file Step 0 identified, back the file up first,
and recreate **only** the `influora-api` container.
*Rollback:* restore the `.bak` env file, recreate the container. Self-limiting: a loopback or `http://`
value fails the container closed at boot (`SecretsStartupValidator.java:617-635`), so a typo of that
class is loud. A wrong-but-HTTPS value is silent — which is why Step 6 is not optional.

**Step 6 — Browser proof of Meera.** `README.md:157-161`, step 5 of the browser pass, with devtools
open. Distinguish the two failures it names: nothing arrives + CORS error → allowlist wrong (Q3
condition 5); reply arrives in one burst after a pause → SSE buffering (Q3 condition 3). Also confirm
the neighbour is untouched: `curl -sS -o /dev/null -w "snapsby %{http_code}\n" https://snapsby.com`
(`README.md:170`).
*Rollback:* Steps 5 then 4, in that order.

**Step 7 — `app.` / `api.` vhosts, only when their certificates exist.** Use
`nginx-influora-app.conf`, which explicitly refuses to touch the apex
(`nginx-influora-app.conf:3-10`) — never `nginx-influora.conf` at this stage. Issue per-host certs
first; do **not** rely on `README.md:55-58`, whose single five-SAN cert does not populate the paths
`nginx-influora-app.conf:37-38,58-59` expect. Remove the `ai.influora.in` blocks from that file before
enabling it, or you collide with Step 4 (`nginx-influora-app.conf:87` vs the file installed in Step 4).
*Rollback:* `rm -f /etc/nginx/sites-enabled/influora-app.conf && nginx -t && systemctl reload nginx`.

**Step 8 — Rebuild and stage the bundle, do not overwrite.** Build with `VITE_API_BASE_URL` matching
whatever Step 7 actually made reachable and `VITE_MEERA_STREAM_URL=https://ai.influora.in`. Deploy to
`/var/www/influora-<date>`, then change only the `root` line in the apex vhost
(`live-state.txt:59`) and reload.
*Rollback:* point `root` back at `/var/www/influora` and reload. Keep the old directory for at least a
week. This is why the new build goes to a new directory: an in-place `rsync` over `/var/www/influora`
has no rollback.

**Step 9 — Apex cutover to the container, last, and only if it is actually wanted.**
`nginx-influora.conf` removes the `/api/` location the current bundle depends on
(`live-state.txt:64-65` vs `nginx-influora.conf:64-72`), so it can only follow a Step 8 build that
calls `api.influora.in` instead. Before enabling, confirm no duplicate `server_name influora.in`
remains enabled (`live-state.txt:6` vs `nginx-influora.conf:59`) — disable
`/etc/nginx/sites-enabled/influora.in` in the same reload, do not leave both.
*Rollback:* `rm -f /etc/nginx/sites-enabled/influora.conf`, re-link `influora.in`, `nginx -t &&
systemctl reload nginx`. The `sites-available/influora.in` file is never edited, so it is always
available to re-enable.

**Never, on this box, in this state:**
- `docker compose -f deploy/utho/docker-compose.utho.yml up -d` or the `-shared` variant (O1, O6).
- `docker rm grafana loki promtail` (`README.md:332`) — they are running (`live-state.txt:28-30`).
- `OPS-F0697` steps 5 and 7 against `/usr/local/App/docker-compose.prod.yml` (S5).

---

## Verdict

# NO-GO

**for applying `deploy/utho/` to 150.241.245.242 as it stands.** The directory describes a Caddy-fronted
seven-service stack at `/opt/influora`; the box runs no Caddy, has `8080` held by an unrelated Tomcat
(`live-state.txt:37`), serves the SPA from a static root with its own `/api/` proxy
(`live-state.txt:59,64-65`), and runs three Influora containers no file in that directory could have
created (`live-state.txt:23-25` vs `docker-compose.utho-shared.yml:136,259,299`). Two files in the pack
are actively destructive against current state (`README.md:332`; `OPS-F0697:54-58`), one cannot pass
`nginx -t` under its own runbook (`nginx-influora.conf:82-83` vs `README.md:61-62`), and the security
section that reads most authoritatively is aimed at an inert variable (`application.yml:184`).

**Conditional GO** for the narrow path in Q5 — Steps 0 through 6 only — under all of these conditions:

1. **Step 0 runs first and its output is recorded in this task directory.** Steps 4 and 5 are not
   executable without it, and a deploy performed without knowing which compose file owns
   `influora-api` is a guess, not a deploy.
2. **Step 2 (SSH keys, n8n port) lands before any application change.** S1 and S2 are live today and
   independent of anything Influora ships.
3. **The `ai` vhost upstream is `127.0.0.1:8000`, not `:8080`.** Every repo file says `8080`; every
   repo file is wrong for this box. This is the single most important correction in the review.
4. **Step 5 sets `INFLUORA_MEERA_STREAM_PUBLICCHATURL`, not `MEERA_PUBLIC_CHAT_URL`.** The documented
   variable is a no-op against these compose files.
5. **Step 6's browser pass is run by a human, with devtools open, on the `https://influora.in` origin.**
   `README.md:113-116` is right: curl cannot pass this section, and both prior breaks would have
   sailed through a curl-only check.
6. **Nothing in Steps 7–9 begins until Steps 0–6 are green and Meera has streamed once in a real
   browser.** Steps 7–9 touch the vhost that is currently serving real traffic; Steps 0–6 do not.
7. **`README.md` §7 and `OPS-F0697` §Checklist step 5 are corrected in the repo before the next
   operator reads them.** Leaving `docker rm grafana loki promtail` and "add the token to
   `/usr/local/App/.env`" in place is how this review's findings get re-lost.

Conditions 3 and 4 are technical corrections I am making as CTO and they are not negotiable at the
working-member level. Conditions 1, 2 and 5 are process gates. Condition 7 is the one that decides
whether this review was worth writing.

*Escalation to Swapnil:* S1 (root password SSH) and S2 (n8n publicly reachable) are live security
exposures on a box carrying production credentials for two products. They are not blocked on the
Influora deploy and should be fixed regardless of whether this deploy proceeds.
