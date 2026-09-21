# Priya — Utho deploy plan v2 (executable)

Task: T-UTHO-DEPLOY-0907 · box `150.241.245.242` · 2026-09-07
Supersedes the *ordering* section of `priya-review.md` (that document is a review; this one
is the runbook). Sources read for this document, in full and first-hand:
`.proof-os/tasks/T-UTHO-DEPLOY-0907/live-state.txt` (all six captures), every file in
`deploy/utho/`, the four new files in `.proof-os/gates/`, and `.env.production`.

Every command below is copy-pasteable. Every step names its rollback. Where the box and the
repo disagree, the box wins and both citations are given.

---

## 0 · The shape of the problem, in four facts

1. **The running containers are hand-run, not compose-managed.**
   `live-state.txt:185` — *"compose labels: project=[] files=[] on BOTH -> confirmed hand-run,
   no compose"*, corroborated by `live-state.txt:154-155` — *"(no output — no compose file
   exists under either directory at depth <= 2)"*. So there is no `docker compose up -d` that
   will pick up the corrected env. Recreation is a literal `docker run`.

2. **Docker froze the old env into the running processes.**
   `live-state.txt:233-235` — *"NO container was restarted. Docker bakes env at container
   CREATION, so the two running processes STILL HOLD THE OLD VALUES. The files are correct;
   the runtime is not."* The three fixes at `live-state.txt:223-230` are file-only.

3. **`127.0.0.1:8080` on this box is a foreign Java process, not Influora and not Caddy.**
   `live-state.txt:37` — `LISTEN 0 100 *:8080 users:(("java",pid=2658,fd=45))`, which also
   holds Tomcat's shutdown port `live-state.txt:38` — `[::ffff:127.0.0.1]:8005 ... pid=2658`.
   Influora's API is a *different* pid on a *different* port: `live-state.txt:36` —
   `*:8082 users:(("java",pid=1609031,fd=14))`, matching `live-state.txt:188` —
   `SERVER_PORT=8082`. **Every nginx file in `deploy/utho/` proxies to `127.0.0.1:8080`**
   (`nginx-ai-influora.conf:47`, `nginx-influora.conf:65,86,111,138`,
   `nginx-influora-app.conf:41,66,93`, `install-ai-vhost.sh:180`). All of them are pointed at
   the co-tenant.

4. **The AI service is already listening, on 8000, and is not reachable from outside.**
   `live-state.txt:33` — `LISTEN 0 2048 0.0.0.0:8000 users:(("uvicorn",pid=4031544,fd=11))`,
   with `live-state.txt:129` — *"port 8000 blocked/filtered"* from a residential connection.
   `ai.influora.in` resolves here (`live-state.txt:206`) but has no server block, so it falls
   through to the co-tenant default vhost: `live-state.txt:219` —
   `subject=CN=snapsby.com  <-- confirmed fall-through`.

That `uvicorn` on `0.0.0.0:8000` is the `influora-ai` container follows from
`live-state.txt:184` — `/influora-ai net=host img=influora-ai:latest` — a host-network
container's listeners appear directly on the host. Corroborated by `live-state.txt:24`, where
`influora-ai` shows `PORTS (none)` while every bridge-networked container on the box shows a
mapping (`live-state.txt:25-28`). Confirmed by precondition **P5** below before it is relied on.

---

## BLOCKING PRECONDITIONS

These exist because values this plan needs are genuinely absent from the artifact. Each is a
single command that produces a **file or a pass/fail gate** — no step later in this plan ever
asks the operator to read a value out and paste it somewhere.

Run all of P0–P6 in one session, immediately before Step 1. If any gate fails, **stop**; the
box is not the box this plan was written against.

### P0 · Workspace

```bash
mkdir -p /root/utho-0907 && chmod 700 /root/utho-0907
```

Rollback: `rm -rf /root/utho-0907`

### P1 · Capture the full running env of both containers

**Why:** `live-state.txt:187` records only *"(75 vars; selected)"* for `influora-api` — five of
them (`:188-192`) — and only four for `influora-ai` (`live-state.txt:195-198`). The other
seventy are unrecorded. Rebuilding from the on-disk env file alone would silently drop any var
that was passed with `-e` and is not in that file.

```bash
docker inspect influora-api --format '{{range .Config.Env}}{{println .}}{{end}}' > /root/utho-0907/api.running.env
docker inspect influora-ai  --format '{{range .Config.Env}}{{println .}}{{end}}' > /root/utho-0907/ai.running.env
chmod 600 /root/utho-0907/api.running.env /root/utho-0907/ai.running.env
wc -l /root/utho-0907/api.running.env /root/utho-0907/ai.running.env
```

**Gate:** `api.running.env` must have **at least 75 lines** (`live-state.txt:187`).

**Gate (line integrity):** every line must be a `KEY=` assignment — a value containing a raw
newline would corrupt an `--env-file`:

```bash
test "$(grep -c '' /root/utho-0907/api.running.env)" = "$(grep -cE '^[A-Za-z_][A-Za-z0-9_]*=' /root/utho-0907/api.running.env)" && echo API-ENV-CLEAN || echo API-ENV-DIRTY-STOP
test "$(grep -c '' /root/utho-0907/ai.running.env)"  = "$(grep -cE '^[A-Za-z_][A-Za-z0-9_]*=' /root/utho-0907/ai.running.env)"  && echo AI-ENV-CLEAN  || echo AI-ENV-DIRTY-STOP
```

Rollback: `rm -f /root/utho-0907/*.running.env` (nothing on the box changed).

### P2 · Entrypoint / command parity with the image

**Why:** `live-state.txt:172-177` records that the third capture's inspect failed and that
*"NetworkMode, Image, RestartPolicy and Binds remain UNREAD"*; the fourth capture
(`live-state.txt:183-184`) recovered exactly those four and nothing else. Whether the original
`docker run` overrode the entrypoint is not recorded anywhere.

```bash
docker inspect --format 'CTR {{.Name}} ENTRYPOINT={{json .Config.Entrypoint}} CMD={{json .Config.Cmd}} WORKDIR={{.Config.WorkingDir}} USER={{printf "%q" .Config.User}}' influora-api influora-ai
docker inspect --format 'IMG {{.RepoTags}} ENTRYPOINT={{json .Config.Entrypoint}} CMD={{json .Config.Cmd}} WORKDIR={{.Config.WorkingDir}} USER={{printf "%q" .Config.User}}' influora-api:latest influora-ai:latest
```

**Gate:** the `ENTRYPOINT`/`CMD`/`WORKDIR`/`USER` values on the `CTR` line for `influora-api`
must equal those on the `IMG` line for `influora-api:latest`, and likewise for `influora-ai`.
If they differ, the container carries a run-time override this plan's `docker run` does not
reproduce — **stop**.

For `influora-api` the expected value is known independently:
`live-state.txt:170` — `ENTRYPOINT ["java","-Xmx1024m","-jar","/app/influora-api.jar"]`.

Rollback: none (read-only).

### P3 · HostConfig flags with no other record

**Why:** `live-state.txt:183-184` records only `net`, `img`, `restart` and `binds`. Flags such
as `--memory`, `--cpus`, `--log-opt`, `--add-host`, `--dns` and `--ulimit` leave no trace in
that capture and are not reproduced by this plan's `docker run`.

```bash
docker inspect --format '{{.Name}} Memory={{.HostConfig.Memory}} NanoCpus={{.HostConfig.NanoCpus}} Log={{.HostConfig.LogConfig.Type}}/{{json .HostConfig.LogConfig.Config}} ExtraHosts={{json .HostConfig.ExtraHosts}} Dns={{json .HostConfig.Dns}} Ulimits={{json .HostConfig.Ulimits}} Binds={{json .HostConfig.Binds}} Net={{.HostConfig.NetworkMode}} Restart={{.HostConfig.RestartPolicy.Name}}' influora-api influora-ai
```

**Gate:** for both containers, `Memory=0`, `NanoCpus=0`, `ExtraHosts=null`, `Dns=[]`,
`Ulimits=null`, `Binds=null`, `Net=host`, `Restart=unless-stopped`, and
`Log=json-file/{}` (or `Log=json-file/null`). `Net`, `Restart` and `Binds` must match
`live-state.txt:183-184`. Any other value means a flag is in play that this plan drops —
**stop** and add it to the `docker run` lines by hand.

Rollback: none (read-only).

### P4 · certbot webroot exists

**Why:** `install-ai-vhost.sh:61` aborts if `/var/www/html` is missing, and the artifact never
shows that directory. `live-state.txt:210` shows only `/var/www/influora/`.

```bash
ls -ld /var/www/html && command -v certbot
```

**Gate:** both must succeed. If `certbot` is absent, `apt-get install -y certbot`
(`install-ai-vhost.sh:55`). If `/var/www/html` is absent: `mkdir -p /var/www/html`.

Rollback (only if you created it): `rmdir /var/www/html`

### P5 · Confirm 8000 is the AI container and 8082 is the API

```bash
ss -ltnp | grep -E ':(8000|8080|8082) '
curl -s -o /dev/null -w 'jwks-on-8082 %{http_code}\n' http://127.0.0.1:8082/api/v1/.well-known/jwks.json
curl -s -o /dev/null -w 'jwks-on-8080 %{http_code}\n' http://127.0.0.1:8080/api/v1/.well-known/jwks.json
curl -s -o /dev/null -w 'ai-chat-on-8000 %{http_code}\n' -m 20 -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8000/chat
```

**Gate, all four:**
- `:8000` is `uvicorn` (`live-state.txt:33`), `:8082` is java pid matching `influora-api`
  (`live-state.txt:36`), `:8080` is a *different* java pid (`live-state.txt:37`).
- `jwks-on-8082` = **200**.
- `jwks-on-8080` = **not 200**. If 8080 *also* answers 200 on that path, stop — the model of
  this box in section 0 is wrong.
- `ai-chat-on-8000` = **401, 422 or 400**. `install-ai-vhost.sh:223` states this mapping:
  *"FastAPI is answering - this is the correct result (auth/validation rejection)"*.

Rollback: none (read-only).

### P6 · Baseline the co-tenant and the live product, before touching anything

```bash
nginx -T > /root/utho-0907/nginx.before.txt 2>&1
cp -a /etc/nginx/sites-enabled /root/utho-0907/sites-enabled.before
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' > /root/utho-0907/docker-ps.before.txt
curl -sS -o /dev/null -w 'snapsby      %{http_code} tls=%{ssl_verify_result}\n' https://snapsby.com
curl -sS -o /dev/null -w 'www.snapsby  %{http_code} tls=%{ssl_verify_result}\n' https://www.snapsby.com
curl -sS -o /dev/null -w 'apex         %{http_code} tls=%{ssl_verify_result}\n' https://influora.in/
curl -sS -o /dev/null -w 'apex-api     %{http_code} tls=%{ssl_verify_result}\n' https://influora.in/api/v1/health
echo | openssl s_client -connect 150.241.245.242:443 -servername unknown-host.invalid 2>/dev/null | openssl x509 -noout -subject
```

**Expected, from the artifact:** `snapsby 200` and `www.snapsby 200` (`live-state.txt:259-260`);
`apex 200` (`live-state.txt:123`); `apex-api 200` (`live-state.txt:126`, re-confirmed
`live-state.txt:237`); the unknown-SNI probe prints `subject=CN=snapsby.com`
(`live-state.txt:255`, because snapsby.com holds `default_server` — `live-state.txt:242-245`).

**Gate:** if any of these already differs from the expected value, **stop** — the box moved
between the capture and now, and this plan's premises need re-verifying. Note that
`live-state.txt:24` reports `influora-ai ... Up 12 hours` while the later
`live-state.txt:236` reports `influora-ai Up 12 days`; the two captures disagree, so a second
operator or an automatic restart is in play. Compare `docker-ps.before.txt` against
`live-state.txt:22-30` before continuing.

Rollback: none (read-only).

---

## Q2 · The ordering constraint, answered from evidence

**Two constraints pull in opposite directions, and together they fix the order exactly.**

**Constraint A — the AI container must be recreated BEFORE the vhost is installed.**
The running `influora-ai` process holds `MEERA_ALLOWED_ORIGINS=https://app.influora.com`
(`live-state.txt:198`, and `live-state.txt:233-235` confirms it still does). That host is not
under a domain this product owns — `.proof-os/gates/owned-domains.txt:9-13` records
`influora.com` as *"NOT OURS ... Third-party infrastructure"*. So a browser arriving from
`https://influora.in` is refused by the AI service's own allowlist.

*What breaks if reversed:* the vhost installs cleanly and its own verification passes —
`install-ai-vhost.sh:219-228` probes with `curl`, and `README.md:113-116` states plainly that
*"curl never sends an `Origin` header, so **every CORS failure returns 200 to curl** and only
appears in a real browser."* You therefore get a green install script and a red browser. The
script's failure text for that situation (`install-ai-vhost.sh:224` — *"still 405 - nginx is
still not routing this host"*) points at nginx, which is now correct. You would be debugging a
working vhost against a stale container, with the script's own diagnostics steering you wrong.

**Constraint B — the API container must be recreated AFTER the vhost is installed.**
`MeeraController.java:145` hands the browser `streamProperties.getPublicChatUrl()` — the
comment at `:141-144` says *"Browser-reachable Python /chat SSE URL — the browser connects here
DIRECTLY ... Config-driven, never hardcoded"*. That value comes from
`application-prod.yml:79` — `public-chat-url: ${MEERA_PUBLIC_CHAT_URL}` — with **no default**,
deliberately (`application-prod.yml:71-76`). `install-ai-vhost.sh:230-241` states this is the
one step the script does not do. So the API should only be pointed at `ai.influora.in` once
that host is proven to serve, or the API spends the window handing browsers a URL that falls
through to the co-tenant (`live-state.txt:219`).

**Resulting order: AI container → ai vhost → API container.** Each container is recreated
exactly once. The API's own `CORS_ALLOWED_ORIGINS` fix (`live-state.txt:226-227`) rides along
in that single recreate; deferring it costs nothing today because the SPA is same-origin —
`.proof-os/gates/cors-origin-wellformed.py:11-13` — *"the SPA is same-origin today, so no
preflight is ever issued and the allowlist is never consulted."* Confirmed by the live bundle
baking `https://influora.in/api/v1` (`live-state.txt:215`), same origin as the page.

---

## THE PLAN

### Step 1 · Build `influora-ai`'s merged env

```bash
grep -vE '^[[:space:]]*(#|$)' /usr/local/App/influora-ai/influora-ai/influora-ai.env > /root/utho-0907/ai.file.env
awk -F= 'NF && !seen[$1]++' /root/utho-0907/ai.file.env /root/utho-0907/ai.running.env > /root/utho-0907/ai.merged.env
chmod 600 /root/utho-0907/ai.file.env /root/utho-0907/ai.merged.env
```

`awk` keeps the **first** occurrence of each key, and the corrected on-disk file is listed
first — so the file's values win and any var that exists only in the running process is
carried forward. Path from `live-state.txt:228`.

**Verify:**

```bash
grep '^MEERA_ALLOWED_ORIGINS=' /root/utho-0907/ai.merged.env
grep -E '^(SPRING_JWKS_URL|SPRING_INTERNAL_BASE_URL|REDIS_URL)=' /root/utho-0907/ai.merged.env
test "$(grep -c '' /root/utho-0907/ai.merged.env)" -ge "$(grep -c '' /root/utho-0907/ai.running.env)" && echo NO-VAR-LOST || echo VARS-LOST-STOP
```

`MEERA_ALLOWED_ORIGINS` must print exactly
`MEERA_ALLOWED_ORIGINS=https://influora.in,https://www.influora.in` — `live-state.txt:230`.
`REDIS_URL` must print `redis://localhost:6380` — `live-state.txt:197`, which matches the
live listener at `live-state.txt:40` (`127.0.0.1:6380 docker-proxy`).

Rollback: `rm -f /root/utho-0907/ai.merged.env /root/utho-0907/ai.file.env`

### Step 2 · Correct the AI service's two internal API URLs (NEW finding)

**Finding.** `live-state.txt:195-196` records:

```
SPRING_JWKS_URL=http://localhost:8080/api/v1/.well-known/jwks.json
SPRING_INTERNAL_BASE_URL=http://localhost:8080/api/v1
```

Both name port **8080**. On this box `127.0.0.1:8080` is the foreign Java process
(`live-state.txt:37`, pid 2658), not the Influora API — which is on **8082**
(`live-state.txt:36`, pid 1609031; `live-state.txt:188` — `SERVER_PORT=8082`; and the apex
nginx block proxies `/api/` to `127.0.0.1:8082` at `live-state.txt:64-65`). The `/api/v1`
path prefix in both values shows they are meant for the Influora API. So the AI service
verifies its JWTs against, and makes its internal calls to, a co-tenant application.

This is not covered by the three fixes at `live-state.txt:223-230`. Recreating `influora-ai`
without correcting it re-freezes the wrong value.

**Gate — do not apply this edit unless P5 passed** (`jwks-on-8082` = 200 and
`jwks-on-8080` ≠ 200).

```bash
cp -a /usr/local/App/influora-ai/influora-ai/influora-ai.env /usr/local/App/influora-ai/influora-ai/influora-ai.env.bak.20260907b
chmod 600 /usr/local/App/influora-ai/influora-ai/influora-ai.env.bak.20260907b
sed -i 's#^SPRING_JWKS_URL=http://localhost:8080/#SPRING_JWKS_URL=http://localhost:8082/#' /usr/local/App/influora-ai/influora-ai/influora-ai.env
sed -i 's#^SPRING_INTERNAL_BASE_URL=http://localhost:8080/#SPRING_INTERNAL_BASE_URL=http://localhost:8082/#' /usr/local/App/influora-ai/influora-ai/influora-ai.env
sed -i 's#^SPRING_JWKS_URL=http://localhost:8080/#SPRING_JWKS_URL=http://localhost:8082/#' /root/utho-0907/ai.merged.env
sed -i 's#^SPRING_INTERNAL_BASE_URL=http://localhost:8080/#SPRING_INTERNAL_BASE_URL=http://localhost:8082/#' /root/utho-0907/ai.merged.env
```

The `.bak.20260907b` suffix follows the convention already used on this box —
`live-state.txt:232` — *"Backups: `*.bak.20260907` beside each, mode 600"* — with a `b` so it
does not overwrite the F-0722 backup.

**Verify:**

```bash
grep -E '^SPRING_(JWKS_URL|INTERNAL_BASE_URL)=' /usr/local/App/influora-ai/influora-ai/influora-ai.env /root/utho-0907/ai.merged.env
```

All four lines must say `localhost:8082`.

**Rollback:**

```bash
cp -a /usr/local/App/influora-ai/influora-ai/influora-ai.env.bak.20260907b /usr/local/App/influora-ai/influora-ai/influora-ai.env
sed -i 's#^SPRING_JWKS_URL=http://localhost:8082/#SPRING_JWKS_URL=http://localhost:8080/#' /root/utho-0907/ai.merged.env
sed -i 's#^SPRING_INTERNAL_BASE_URL=http://localhost:8082/#SPRING_INTERNAL_BASE_URL=http://localhost:8080/#' /root/utho-0907/ai.merged.env
```

### Step 3 · Recreate `influora-ai`

Blast radius: the Python AI service is unreachable from the internet today
(`live-state.txt:219` — the hostname falls through to the co-tenant; `live-state.txt:129` —
port 8000 filtered from outside), so no browser traffic is affected. Server-to-server calls
from the Java API are interrupted for the duration.

```bash
docker rename influora-ai influora-ai-old-20260907
docker stop influora-ai-old-20260907
docker run -d \
  --name influora-ai \
  --network host \
  --restart unless-stopped \
  --env-file /root/utho-0907/ai.merged.env \
  influora-ai:latest
```

Every flag is derived from `live-state.txt:184` — `/influora-ai net=host img=influora-ai:latest
restart=unless-stopped binds=[]`: `binds=[]` is why there is no `-v`, `net=host` is why there
is no `-p` (and why `docker ps` shows `PORTS (none)` at `live-state.txt:24`), and no
`--entrypoint` is passed because P2 proved the container's entrypoint is the image's.

**Verify — the check that proves it:**

```bash
docker inspect influora-ai --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^(MEERA_ALLOWED_ORIGINS|SPRING_JWKS_URL|SPRING_INTERNAL_BASE_URL)='
```

Must print the three corrected values. This is the direct proof against
`live-state.txt:233-235` — it reads the *runtime*, not the file.

**Verify — the check that merely fails to disprove:**

```bash
docker ps --filter name=influora-ai --format '{{.Status}}'
ss -ltnp | grep ':8000 '
curl -s -o /dev/null -w 'ai-chat %{http_code}\n' -m 20 -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:8000/chat
```

`healthy` and a 401/422/400 mean the process came up. They say nothing about whether the new
env is correct — the container was `Up ... (healthy)` with the wrong env for twelve days
(`live-state.txt:236`).

**Rollback:**

```bash
docker stop influora-ai && docker rm influora-ai
docker rename influora-ai-old-20260907 influora-ai
docker start influora-ai
```

### Step 4 · Edit the two vhost files in the repo — before anything is copied

These are the **named edits** referenced in Q4. Make them locally, in the checkout, and commit
them; do not hand-patch on the box.

**Edit 4a — `deploy/utho/nginx-ai-influora.conf:47`**

```diff
-        proxy_pass http://127.0.0.1:8080;
+        proxy_pass http://127.0.0.1:8000;
```

**Edit 4b — `deploy/utho/install-ai-vhost.sh:180`** (inside the `PHASE2` heredoc)

```diff
-        proxy_pass http://127.0.0.1:8080;
+        proxy_pass http://127.0.0.1:8000;
```

Both because `127.0.0.1:8080` is the co-tenant Java process (`live-state.txt:37`) and the AI
service is on 8000 (`live-state.txt:33`). The file's own header
(`nginx-ai-influora.conf:36`, *"proxy to Caddy on 127.0.0.1:8080"*) and the Caddyfile it names
(`Caddyfile:16` — `http_port 8080`, `Caddyfile:48-49` — `{$AI_DOMAIN} { reverse_proxy
influora-ai:8000`) describe a Caddy that does not exist on this box: `docker ps` at
`live-state.txt:22-30` lists no Caddy container. Removing Caddy from the path also makes the
`Host` header comment at `nginx-ai-influora.conf:50-51` moot — but `proxy_set_header Host
$host` is harmless and is left alone.

**Edit 4c — `deploy/utho/install-ai-vhost.sh:66`**

```diff
-if ss -lnt 2>/dev/null | grep -q '127\.0\.0\.1:8080'; then
+if ss -lnt 2>/dev/null | awk 'NR>1 {print $4}' | grep -qE ':8000$'; then
```

Without this the script is unrunnable as documented. `ss -lnt` prints the AI listener as
`0.0.0.0:8000` (`live-state.txt:33`) and the 8080 listener as `*:8080` (`live-state.txt:37`) —
neither contains the literal string `127.0.0.1:8080`. The `grep -q` therefore fails, the script
takes the WARN branch at `:69`, and because the documented invocation
(`install-ai-vhost.sh:5` — `ssh root@... 'bash /root/install-ai-vhost.sh'`) has no tty, it
falls to `:85` and dies. Setting `ASSUME_YES=1` to get past it would install a vhost pointing
at the co-tenant — the worse of the two outcomes.

**Edit 4d (SHOULD, non-functional) — `install-ai-vhost.sh:73-76`, `:225`, `:231-241`.**
These message blocks tell the operator to run `cd /opt/influora && docker compose up -d`.
There is no `/opt/influora` and no compose file for these containers on this box
(`live-state.txt:154-155`, `live-state.txt:185`; the actual layout is `/usr/local/App/influora`
per `live-state.txt:139-146`). The commands are inert rather than dangerous, but they will send
the next operator to the wrong place. Replace with a pointer to this file. The `:231-241`
"Remaining step" block should say: *set `MEERA_PUBLIC_CHAT_URL` in
`/usr/local/App/influora/influora.env` and recreate `influora-api` — see Step 6 of
`priya-plan-v2.md`.*

**Verify the edits:**

```bash
grep -n '127.0.0.1:8000' deploy/utho/nginx-ai-influora.conf deploy/utho/install-ai-vhost.sh
grep -n '127.0.0.1:8080' deploy/utho/nginx-ai-influora.conf deploy/utho/install-ai-vhost.sh   # must print NOTHING
bash -n deploy/utho/install-ai-vhost.sh && echo SYNTAX-OK
```

**Rollback:** `git checkout -- deploy/utho/nginx-ai-influora.conf deploy/utho/install-ai-vhost.sh`

### Step 5 · Install the `ai.influora.in` vhost

```bash
scp deploy/utho/install-ai-vhost.sh root@150.241.245.242:/root/utho-0907/install-ai-vhost.sh
ssh root@150.241.245.242 'bash /root/utho-0907/install-ai-vhost.sh'
```

What the script does, in its own two phases: writes an HTTP-only block, `nginx -t`, reload
(`:107-127`); `certbot certonly --webroot -w /var/www/html -d ai.influora.in`, dry-run first
(`:132-150`); rewrites the file with the HTTPS/SSE block, `nginx -t`, reload (`:155-207`).
The two-phase split is load-bearing: `nginx-ai-influora.conf:17-19` explains that a one-shot
install cannot work because the HTTPS block references a certificate that does not exist yet.
`/etc/letsencrypt/live/` currently holds only `bysnaps.com`, `influora.in`, `README`,
`snapsby.com` (`live-state.txt:19`) — there is no `ai.influora.in` cert, so the certbot branch
will run.

The ACME challenge will reach the webroot because Phase 1 gives `ai.influora.in` its own `:80`
block; without it the hostname falls to snapsby.com's `default_server`
(`live-state.txt:242-243`). DNS is already correct: `live-state.txt:206` — *"ai.influora.in
app.influora.in api.influora.in influora.in -> 150.241.245.242"*.

**Verify — the check that proves it:**

```bash
echo | openssl s_client -connect ai.influora.in:443 -servername ai.influora.in 2>/dev/null | openssl x509 -noout -subject
```

Must print `subject=CN=ai.influora.in`. If it prints `subject=CN=snapsby.com`, the vhost is not
matching — that is the exact recorded fall-through at `live-state.txt:219`, unchanged.

```bash
curl -s -o /dev/null -w 'ai-public-chat %{http_code}\n' -m 20 -X POST -H 'Content-Type: application/json' -d '{}' https://ai.influora.in/chat
```

Must be **401, 422 or 400** — and must equal what P5's `ai-chat-on-8000` returned. Identical
codes through the public route and the loopback route is end-to-end proof that nginx reached
the AI container. `install-ai-vhost.sh:222-227` gives the failure readings: `405` = nginx is
not routing this host to the app; `502/503` = vhost correct, upstream down; `200` on an empty
unauthenticated body = suspicious, you are probably still on the co-tenant.

**Verify — the check that merely fails to disprove:**

```bash
curl -sI https://ai.influora.in/ | head -1
```

A `200` here proves nothing at all. snapsby.com's default vhost also answers `200`
(`live-state.txt:259`), which is precisely how this failure hid.

**Verify — the co-tenant is untouched (Q3's invariant):**

```bash
curl -sS -o /dev/null -w 'snapsby     %{http_code} tls=%{ssl_verify_result}\n' https://snapsby.com
curl -sS -o /dev/null -w 'www.snapsby %{http_code} tls=%{ssl_verify_result}\n' https://www.snapsby.com
curl -sS -o /dev/null -w 'apex        %{http_code} tls=%{ssl_verify_result}\n' https://influora.in/
echo | openssl s_client -connect 150.241.245.242:443 -servername unknown-host.invalid 2>/dev/null | openssl x509 -noout -subject
diff <(ls -1 /etc/nginx/sites-enabled/) <(printf 'ai-influora.conf\ngrafana-test\ninfluora.in\nsnapsby.com\n')
```

All three curls must match the P6 baseline. The unknown-SNI probe must **still** print
`subject=CN=snapsby.com` — that is the positive proof that no new file stole `default_server`.
The `diff` must show only the added `ai-influora.conf` against the recorded set at
`live-state.txt:249-252`.

**Rollback** (the script's own, `install-ai-vhost.sh:242-244`):

```bash
rm -f /etc/nginx/sites-enabled/ai-influora.conf
nginx -t && systemctl reload nginx
```

Leave the issued certificate in place — deleting it costs a fresh Let's Encrypt issuance on
retry, and `install-ai-vhost.sh:14-15` notes the five-failures-per-domain-per-week budget.
The script's `rollback()` (`:33-48`) fires automatically on any internal failure and restores
the previous file state, then reloads only if `nginx -t` passes.

### Step 6 · Recreate `influora-api` with both corrections

**6a — point Spring at the now-proven AI host.** Do not run this until Step 5's
`CN=ai.influora.in` check has passed.

```bash
cp -a /usr/local/App/influora/influora.env /usr/local/App/influora/influora.env.bak.20260907b
chmod 600 /usr/local/App/influora/influora.env.bak.20260907b
grep -n '^MEERA_PUBLIC_CHAT_URL=' /usr/local/App/influora/influora.env || echo 'MEERA_PUBLIC_CHAT_URL is ABSENT from the file'
sed -i 's#^MEERA_PUBLIC_CHAT_URL=.*#MEERA_PUBLIC_CHAT_URL=https://ai.influora.in/chat#' /usr/local/App/influora/influora.env
grep -c '^MEERA_PUBLIC_CHAT_URL=https://ai.influora.in/chat$' /usr/local/App/influora/influora.env
```

If that last count is `0`, the key was absent from the file (it may have been passed with `-e`
instead) — append it:

```bash
printf 'MEERA_PUBLIC_CHAT_URL=https://ai.influora.in/chat\n' >> /usr/local/App/influora/influora.env
```

The value is fixed, not a placeholder: `install-ai-vhost.sh:236` states it verbatim, and
`application-prod.yml:79` has no default so the API will not boot without it.

**6b — build the merged env.**

```bash
grep -vE '^[[:space:]]*(#|$)' /usr/local/App/influora/influora.env > /root/utho-0907/api.file.env
awk -F= 'NF && !seen[$1]++' /root/utho-0907/api.file.env /root/utho-0907/api.running.env > /root/utho-0907/api.merged.env
chmod 600 /root/utho-0907/api.file.env /root/utho-0907/api.merged.env
grep -E '^(CORS_ALLOWED_ORIGINS|SERVER_PORT|MEERA_PUBLIC_CHAT_URL|VOICE_AI_BASE_URL|SPRING_DATASOURCE_URL|SPRING_DATASOURCE_USERNAME)=' /root/utho-0907/api.merged.env
test "$(grep -c '' /root/utho-0907/api.merged.env)" -ge 75 && echo COUNT-OK || echo COUNT-LOW-STOP
```

**Gate, each line:**
- `CORS_ALLOWED_ORIGINS=https://influora.in,https://www.influora.in` — `live-state.txt:227`.
  Note this replaces the malformed `https://influora.in/` at `live-state.txt:189`; the trailing
  separator is the F-0723 defect that `.proof-os/gates/cors-origin-wellformed.py:6-9` was
  written for.
- `SERVER_PORT=8082` — `live-state.txt:188`. If this is missing or different, the apex's
  `proxy_pass http://127.0.0.1:8082` (`live-state.txt:65`) breaks and the whole product goes
  down. Stop.
- `MEERA_PUBLIC_CHAT_URL=https://ai.influora.in/chat`.
- `VOICE_AI_BASE_URL` present and non-empty — `application-prod.yml:88` has no default and
  `:82-87` says prod fails to boot rather than serve a dead voice feature.
- `SPRING_DATASOURCE_URL` and `SPRING_DATASOURCE_USERNAME` match `live-state.txt:190-191`.

**6c — recreate.** This one has a real blast radius: `influora-api` serves the live product's
API through the apex (`live-state.txt:64-65`), currently `HTTP/2 200`
(`live-state.txt:126`, `:237`). Expect a short 502 window on `https://influora.in/api/v1/*`.
Do it in a low-traffic window.

```bash
docker rename influora-api influora-api-old-20260907
docker stop influora-api-old-20260907
docker run -d \
  --name influora-api \
  --network host \
  --restart unless-stopped \
  --env-file /root/utho-0907/api.merged.env \
  influora-api:latest
```

Derived from `live-state.txt:183` — `/influora-api net=host img=influora-api:latest
restart=unless-stopped binds=[]` — and `live-state.txt:167-170`, the Dockerfile whose
`ENTRYPOINT` runs the jar, which is why no command is passed.

**Verify — the checks that prove it:**

```bash
docker inspect influora-api --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^(CORS_ALLOWED_ORIGINS|MEERA_PUBLIC_CHAT_URL|SERVER_PORT)='
curl -sS -o /dev/null -D - -H 'Origin: https://www.influora.in' https://influora.in/api/v1/health | grep -i '^access-control-allow-origin'
```

The first reads the runtime, closing `live-state.txt:233-235`. The second is the only curl
check in this plan that exercises CORS at all — `README.md:113-116` warns that curl sends no
`Origin` by default, so supplying one explicitly is what makes it evidence. It must echo
`access-control-allow-origin: https://www.influora.in`. With the old value
(`https://influora.in/`, `live-state.txt:189`) that header is absent for **every** origin,
because Spring's comparison is exact (`cors-origin-wellformed.py:6-9`).

**Verify — the checks that merely fail to disprove:**

```bash
curl -sS -o /dev/null -w 'apex-api %{http_code}\n' https://influora.in/api/v1/health
docker logs --since 10m influora-api 2>&1 | grep -iE 'PlaceholderResolutionException|APPLICATION FAILED TO START|SecretsStartupValidator' || echo 'no startup-failure signature'
```

A `200` here proves nothing about the fix: it was already `200` with the broken value
(`live-state.txt:237`). It only proves the container booted and the datasource password in the
merged env is still valid — which matters, because `live-state.txt:192` marks that password
`***REDACTED - SEE F-0724, ROTATE***`. If it was rotated between P1 and now, this is where you
find out.

**Rollback:**

```bash
docker stop influora-api && docker rm influora-api
docker rename influora-api-old-20260907 influora-api
docker start influora-api
cp -a /usr/local/App/influora/influora.env.bak.20260907b /usr/local/App/influora/influora.env
curl -sS -o /dev/null -w 'apex-api %{http_code}\n' https://influora.in/api/v1/health
```

### Step 7 · The browser pass — the only gate that closes CORS

`README.md:113-116`: *"curl cannot pass this section on its own ... every CORS failure returns
200 to curl and only appears in a real browser."* Run `README.md:138-165` §5.2 with devtools
open, and in particular step 5 (`README.md:158-161`): send one Meera message and watch it.
Two distinct failures to tell apart, both of which a green Step 6 permits:

- nothing arrives, with a CORS error in the console → the AI allowlist is still wrong
  (Step 3 did not take, or a third origin is in play);
- the reply arrives complete in one burst after a long pause → CORS is fine, SSE buffering is
  on, i.e. `proxy_buffering off` did not land. That directive is at
  `nginx-ai-influora.conf:62` and `install-ai-vhost.sh:194`; check the installed file with
  `grep -n proxy_buffering /etc/nginx/sites-available/ai-influora.conf`.

Also do `README.md:150-153` §5.2 step 3b — log in, press F5, stay logged in. The plan does not
change any domain var, so the `SameSite=Strict` precondition at `README.md:206-274` still
holds; this confirms it.

Rollback: Steps 6, 5, 3 in that order, each with its own rollback block above.

### Step 8 · Clean up, only after Step 7 passes

```bash
docker rm influora-ai-old-20260907 influora-api-old-20260907
```

Rollback: none — this is irreversible, which is why it is last. Keep
`/root/utho-0907/` and the `.bak.20260907b` files.

---

## Q4 · Verdict on every file in `deploy/utho/`

| file | verdict | why, with citations |
|---|---|---|
| `install-ai-vhost.sh` | **SAFE AFTER EDITS 4a–4c** | Correct two-phase certbot design (`:104-150`), `nginx -t` gates every reload (`:125`, `:205`), automatic `rollback()` (`:33-48`), duplicate-`server_name` preflight (`:89-93`). Fails only on the upstream port and its own preflight grep. |
| `nginx-ai-influora.conf` | **SAFE AFTER EDIT 4a** | Reference copy of what the script writes. Sole defect is `:47`. Correctly declares one hostname only (`:25`, `:41`) and carries the SSE set (`:62-68`). |
| `nginx-influora.conf` | **DO NOT INSTALL** | `:19` declares `influora.in www.influora.in` on `:80`, and `:47`/`:59` again on `:443`. Both are already owned by the enabled `/etc/nginx/sites-available/influora.in` (`live-state.txt:6`, `:50-58`). Two enabled files with one `server_name` makes nginx silently pick one — the fault class `install-ai-vhost.sh:92` names as *"the 2026-08-21 fault"*. It also routes the apex to `127.0.0.1:8080` (`:65`) and has **no** `location /api/` block, so the live API path at `live-state.txt:64-65` would vanish. |
| `nginx-influora-app.conf` | **DO NOT INSTALL** | `:37-38`, `:58-59`, `:89-90` reference certs for `app.`/`api.`/`ai.influora.in`; `live-state.txt:19` shows none exist, so `nginx -t` fails outright. `:20` also claims `ai.influora.in` on `:80`, which would collide with `ai-influora.conf` and trip `install-ai-vhost.sh:90-93`. All three upstreams are `127.0.0.1:8080` (`:41`, `:66`, `:93`). |
| `docker-compose.utho.yml` | **DO NOT RUN** | `:63` publishes Caddy on `127.0.0.1:8080`, already held by the foreign java (`live-state.txt:37` — a wildcard `*:8080` bind blocks a subsequent specific bind), so the stack cannot start. Even if it could, it declares no `container_name` anywhere in the file and pulls `ghcr.io/influoradigital-bit/influora-api:latest` (`:120`) and `.../influora-ai:latest` (`:275`), so it would build a **second, parallel** stack rather than update the local-tag containers at `live-state.txt:183-184`. |
| `docker-compose.utho-shared.yml` | **DO NOT RUN** | Same collision at `:79`, same image/naming mismatch (`:136`, `:259`, `:299`; no `container_name` in the file). Its `CORS_ALLOWED_ORIGINS` (`:158`) and `MEERA_ALLOWED_ORIGINS` (`:292`) are correct in shape but reach nothing on this box. |
| `Caddyfile` | **DO NOT RUN** | `:16` sets `http_port 8080` (taken, `live-state.txt:37`); `:25`, `:30`, `:39`, `:49` reverse-proxy to compose service names `frontend`, `influora-api`, `influora-ai` that exist only inside a compose network. `docker ps` (`live-state.txt:22-30`) shows no Caddy container. Its whole premise (`:8-11`, *"nginx ... proxies plain HTTP to Caddy on 127.0.0.1:8080"*) is false here. |
| `README.md` | **DO NOT FOLLOW AS A RUNBOOK** | It describes `/opt/influora` + compose + Caddy throughout (`:13-16`, `:41-44`, `:76-81`); none of that exists (`live-state.txt:154-155`, `:185`, `:139-146`). Two lines are actively dangerous: `:299` states *"Swap: 0B"*, contradicted by `live-state.txt:202` — *"Swap 4.0Gi total, 1.5Gi USED (/swapfile exists - README's 'Swap: 0B' is stale)"* — so `:311-314` would add a **second** swapfile; and `:332` — `docker rm grafana loki promtail` — is written on the premise `:301` that they are *"Dead since ~5 weeks"*, but `live-state.txt:28-30` shows all three `Up 9 days`, and `grafana-test` is an enabled nginx vhost (`live-state.txt:5`). Running `:332` deletes live monitoring. **§5, §5b and §5b.1 (`:111-274`) remain correct and are used verbatim in Step 7.** |
| `generate-env.sh` | **DO NOT RUN** | Defaults to `/opt/influora/.env` (`:14`) for the compose topology. Worse, `:43-71` mint fresh AES keys and signing secrets on every run, including `INFLUORA_PII_EMAILPHONEENCRYPTIONKEY` and `INFLUORA_PII_BANKENCRYPTIONKEY` (`:51-52`); adopting a regenerated key makes existing encrypted PII undecryptable. It also still ships `RAZORPAY_KEY_ID=rzp_test_REPLACE_WITH_YOUR_KEY` (`:77`), the caveat `.env.production:61-64` records against `VITE_PAYMENTS_IN_ENABLED=true`. Useful as a **reference for which vars exist**; not a command to run. |

Nothing in `deploy/utho/` is safe to use as-is. Two files are safe after three named one-line
edits; the other seven must not be executed against this box.

---

## Q3 · What must be true of every new nginx file

**The directive is `default_server` on `listen`. It must appear in no new file.**

Load-bearing because the co-tenant already owns it, on all four listen addresses:

> `live-state.txt:242-245` —
> `/etc/nginx/sites-enabled/snapsby.com:26:    listen 80 default_server;`
> `/etc/nginx/sites-enabled/snapsby.com:27:    listen [::]:80 default_server;`
> `/etc/nginx/sites-enabled/snapsby.com:42:    listen 443 ssl http2 default_server;`
> `/etc/nginx/sites-enabled/snapsby.com:43:    listen [::]:443 ssl http2 default_server;`

nginx permits exactly one `default_server` per `address:port`. A second one makes `nginx -t`
fail. Inside `install-ai-vhost.sh` that is survivable — `:125` and `:205` gate every reload and
call `rollback()` (`:33-48`), leaving snapsby.com on the config it already had. Outside it, a
hand-installed file followed by `systemctl restart nginx` leaves **nginx down**: a reload keeps
the old config on a bad test, a restart does not, and `live-state.txt:45-46` shows this one
nginx (`pid=1143`) owns both `0.0.0.0:80` and `0.0.0.0:443` for the whole box. Losing it takes
snapsby.com, the Influora apex, and grafana-test down together.

`/etc/nginx/nginx.conf:61` includes `sites-enabled/*` unconditionally
(`live-state.txt:247-248`), so any file dropped there is loaded — there is no staging area.

All four repo nginx sources are clean on this point: `grep -c default_server` returns 0 for
`nginx-ai-influora.conf`, `install-ai-vhost.sh`, `nginx-influora.conf` and
`nginx-influora-app.conf`. So installing one cannot displace the catch-all; the failure mode
is a duplicate that `nginx -t` rejects, which is why the reload gating matters more than the
grep.

**Two further invariants of the same class:**

- **`server_name` must name `ai.influora.in` and nothing else, and must never be `_`.**
  `live-state.txt:11` shows a `server_name _;` already present among the enabled files and
  `live-state.txt:10` a bare-IP block; a second catch-all collides. `install-ai-vhost.sh:89-93`
  encodes the rule and refuses to install over a conflicting name;
  `nginx-ai-influora.conf:11-14` gives the reasoning and is why `ai` ships alone rather than
  with `app`/`api`.
- **`proxy_pass` must not point at `127.0.0.1:8080`.** That is the co-tenant's own process
  (`live-state.txt:37`, pid 2658 — distinct from Influora's pid 1609031 on `:8082` at
  `live-state.txt:36`). A vhost that does so does not crash anything, but it serves a
  neighbouring product's application under an Influora hostname and TLS certificate. Edits 4a
  and 4b exist for this.

**The check that proves the invariant held** is in Step 5: an unknown-SNI handshake against the
IP must still return `subject=CN=snapsby.com` (`live-state.txt:255`). A `curl https://snapsby.com`
returning 200 only fails to disprove — it would keep returning 200 right up until the moment a
hostname nobody tested got captured.

---

## Q5 · Proves vs fails-to-disprove, gathered

| after | check that **proves** | check that only **fails to disprove** |
|---|---|---|
| Step 3, 6 (container recreate) | `docker inspect <ctr> --format '{{range .Config.Env}}{{println .}}{{end}}' \| grep '^KEY='` shows the new value — reads the runtime, closing `live-state.txt:233-235` | `docker ps` says `Up ... (healthy)`; it said that for 12 days with the wrong env (`live-state.txt:236`) |
| Step 5 (vhost) | `openssl s_client -servername ai.influora.in` prints `CN=ai.influora.in`; and `POST https://ai.influora.in/chat` returns the *same* code as `POST http://127.0.0.1:8000/chat` did in P5 | `curl -sI https://ai.influora.in/` returns 200 — snapsby's default vhost returns 200 too (`live-state.txt:259`) |
| Step 5 (co-tenant safety) | unknown-SNI handshake still prints `CN=snapsby.com`; `ls /etc/nginx/sites-enabled/` differs from `live-state.txt:249-252` by exactly one added file | `curl https://snapsby.com` = 200 |
| Step 6 (CORS) | `curl -H 'Origin: https://www.influora.in' ... \| grep access-control-allow-origin` echoes the origin back | `curl https://influora.in/api/v1/health` = 200; it was 200 with the broken value (`live-state.txt:237`) |
| Step 6 (API alive) | login + F5 in a real browser (`README.md:150-153`) | `/api/v1/health` 200 |
| Step 7 (Meera) | a token-by-token stream visible in the browser | any curl at all — `README.md:113-116` |
| any nginx change | `nginx -t` passes → the config is *valid* | `nginx -t` passes → says nothing about which server block a request matches |

---

## Q6 · What this plan does NOT prove, and what would prove it

1. **The original `docker run` argv.** `live-state.txt:185` establishes only that it was
   hand-run, so the command exists nowhere but root's shell history. P1–P3 reconstruct the
   observable end state (env, network, restart policy, image, binds, entrypoint, HostConfig),
   which is what determines behaviour — but a flag with no inspect surface would be lost.
   *Proof:* `history 0 | grep 'docker run'` in root's interactive shell, or
   `grep -r 'docker run' /root /usr/local/App --include='*.sh'`.

2. **Whether `MEERA_PUBLIC_CHAT_URL` is currently set at all, and to what.**
   `nginx-ai-influora.conf:6-8` asserts it *"had been pointed at the apex instead
   (https://influora.in/meera/chat)"*, but that is a repo claim dated 2026-09-06 and appears in
   no capture. `live-state.txt:187` shows only 5 of 75 vars. Step 6a handles both the
   present-and-wrong and the absent cases, but the starting value is **UNVERIFIED**.
   *Proof:* `grep -E '^MEERA_PUBLIC_CHAT_URL=' /root/utho-0907/api.running.env` after P1.

3. **Whether the Java API's five server-to-server AI base URLs are configured.**
   `application-prod.yml:61-70` defaults `BRAND_SAFETY_AI_BASE_URL`, `TRENDSPARK_AI_BASE_URL`,
   `MEERA_CHAT_AI_BASE_URL`, `ANALYZE_SITE_AI_BASE_URL` and `CREATOR_COPILOT_AI_BASE_URL` to
   `https://ai.influora.internal`, which does not resolve. If they are unset, those five
   integrations are dead independently of everything in this plan.
   *Proof:* `grep -E '_AI_BASE_URL=' /root/utho-0907/api.running.env`.

4. **Whether the live JS bundle ever calls `ai.influora.in`.** `live-state.txt:211-213` greps
   exactly one asset — `index-Dl65C8NZ.js` — and finds `ai.influora.in = 0`. That is consistent
   with the code rather than alarming: `src/lib/meera-api.ts:547` constructs a stream URL from
   `VITE_MEERA_STREAM_URL` only inside the `if (!isApiLive())` mock branch (`:541-549`), and in
   live mode the URL comes from Spring (`MeeraController.java:145`). So no frontend rebuild is
   required for the AI route. But the grep covered one file, and the Meera code may live in a
   lazily-loaded chunk that was never searched.
   *Proof:* `grep -l 'ai\.influora\.' /var/www/influora/assets/*.js` and
   `grep -c 'streamUrl' /var/www/influora/assets/*.js`.

5. **`VITE_MEERA_STREAM_URL` is declared in no build input, and neither new gate catches it.**
   It is absent from `.env.production` (which declares only `VITE_API_MODE`,
   `VITE_API_BASE_URL:38`, `VITE_PAYMENTS_IN_ENABLED:65`, `VITE_PAYOUTS_ENABLED:73`).
   `api-base-url-served.py:37` lists it in `KEYS`, but `declared_urls()` at `:55-69` reads only
   `.env.production` / `.env.production.local` — absent means unchecked, not failed.
   `allowlist-domain-owned.py:35` lists it too, but its fallback value
   `https://ai.influora.internal` (`meera-api.ts:39`) ends in a reserved suffix that `:57` and
   `:139-141` route to `out_of_remit`. Both gates are green while the value is undeclared. Per
   (4) this is currently harmless; it stops being harmless the moment anything reads that
   constant outside the mock branch. *Proof:* a gate that fails when a `VITE_*` key referenced
   in `src/` has no assignment in any build input.

6. **No rollback in this plan has been executed.** Every rollback block is written from the
   command's documented inverse, not from a test. *Proof:* run Step 3 and its rollback on a
   scratch container built from `influora-ai:latest` first.

7. **The box may have moved since the captures.** `live-state.txt:24` says `influora-ai ... Up
   12 hours`; the later `live-state.txt:236` says `Up 12 days`. Both are dated 2026-09-07. They
   cannot both be true of one uninterrupted container, so either a restart happened between
   captures or one transcription is wrong. P6's `docker-ps.before.txt` diff is the mitigation,
   not a resolution. *Proof:* `docker inspect influora-ai --format '{{.State.StartedAt}}'`.

8. **The datasource password.** `live-state.txt:192` marks it
   `***REDACTED - SEE F-0724, ROTATE***`. If it is rotated between P1 and Step 6c, the merged
   env carries the stale one and the API will not boot. The plan detects this (Step 6c's log
   grep) but cannot prevent it. *Proof:* rotate **before** P1, or not at all during this
   window.

9. **Let's Encrypt budget for `ai.influora.in`.** `install-ai-vhost.sh:14-15` notes five
   failures per domain per week and mitigates with `--dry-run` (`:135-140`), but how much of
   that budget is already spent is unknowable from the box. *Proof:* the dry-run at `:136`
   passing is the only available signal.

10. **`awk -F= 'NF && !seen[$1]++'` producing a file Docker accepts.** The line-integrity gate
    in P1 and the count gate in Step 6b are indirect. *Proof:* the container reaching `healthy`
    with the expected `docker inspect` env — i.e. Step 3 and Step 6c's own verifications.

---

## VERDICT

# GO — conditional

**GO** on the sequence above: preconditions P0–P6, then Steps 1→8 in order, given three
conditions.

1. **All seven precondition gates pass.** Any failure means the box is not the box these six
   captures describe, and every derived `docker run` becomes a guess.
2. **Edits 4a, 4b and 4c are made before anything is copied to the box.** Unedited,
   `install-ai-vhost.sh` either aborts (`:85`, because its preflight grep cannot match
   `*:8080` — `live-state.txt:37`) or, with `ASSUME_YES=1`, publishes a co-tenant Java
   application at `https://ai.influora.in`. The second outcome is worse than the current
   fall-through.
3. **Step 7's browser pass is treated as the gate, not a formality.** Steps 3 and 6 fix values
   that only a browser can exercise; `README.md:113-116` is explicit that curl returns 200 to
   every CORS failure. A green Step 6 with no browser pass is not a deploy.

**NO-GO, separately and unconditionally, on everything else in `deploy/utho/`.** The two
compose files, the Caddyfile, `nginx-influora.conf`, `nginx-influora-app.conf`,
`generate-env.sh`, and `README.md` §§0–4 and §7 describe a `/opt/influora` + Caddy + seven-
service topology that does not exist on this box and cannot be made to exist while
`127.0.0.1:8080` is held (`live-state.txt:37`). `README.md:332` in particular deletes three
running containers (`live-state.txt:28-30`). None of it should be run, and the directory should
carry a header saying so before the next operator opens it.

**One new defect found while writing this**, not covered by F-0721/F-0722/F-0723 and not fixed
by any file on disk: `influora-ai` reaches the Influora API at `localhost:8080`
(`live-state.txt:195-196`) while the API listens on `8082` (`live-state.txt:36`, `:188`) and
`8080` belongs to a co-tenant process (`live-state.txt:37`). Step 2 corrects it, gated on the
P5 probe. It needs an F-number.
