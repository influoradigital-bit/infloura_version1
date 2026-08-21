# T-UTHO-0820 — Deploy the stack to Utho and put it behind influora.in

Opened 2026-08-20. Owner: meera. Reviewer: kabir. Decision by Swapnil: **Utho is the server.**

**done_when:** `https://api.influora.in/actuator/health` returns 200 over valid TLS, AND a real
person can register, log in and reach a dashboard at `https://app.influora.in` — verified from
outside the server, not from localhost on the box.

This is **Tier -1** of `T-ROADMAP-0820`. That roadmap goes straight to "fix the Meta URLs and
submit App Review" and never deploys anything. App Review cannot be submitted against URLs no
deployment serves, so this task runs first and everything Meta-shaped queues behind it.

---

## 1 · Verified starting state (2026-08-20)

| Fact | Evidence |
|---|---|
| `influora.in` returns **200** — WordPress on Hostinger | `curl -o /dev/null -w %{http_code}` |
| `api.influora.in` returns **000** — nothing there | same |
| Nameservers are `ns1/ns2.dns-parking.com` — **Hostinger DNS** | `nslookup -type=NS influora.in 8.8.8.8` |
| Root A records: `147.79.69.252`, `91.108.106.154` (+ AAAA) | `nslookup influora.in 8.8.8.8` |
| Stack is 7 services, all domains env-driven | `deploy/hostinger/docker-compose.hostinger.yml` |
| Images published to GHCR by `publish-images.yml` | `.github/workflows/publish-images.yml` |
| **No CD workflow exists** — CI tests and publishes images, nothing deploys | workflow scan |

### The DNS trap — INVERTED on 2026-08-21, read this before editing records
The advice below was correct when written and is now **backwards**. The nameservers have been
repointed to GoDaddy, so **records go in the GoDaddy panel** and edits in Hostinger's panel will
do nothing. Superseded text, kept so nobody re-applies it: *"nameservers point at Hostinger …
records go in Hostinger's DNS panel … leave nameservers at Hostinger while WordPress still lives
there."*

## 1b · Actual DNS state, measured 2026-08-21

| Name | 2026-08-20 (opened) | 2026-08-21 (measured) |
|---|---|---|
| Nameservers | `ns1/ns2.dns-parking.com` (Hostinger) | **`ns35/ns36.domaincontrol.com` (GoDaddy)** |
| `influora.in` | `147.79.69.252`, `91.108.106.154` | **`150.241.245.242`** |
| `www.influora.in` | — | **`150.241.245.242`** |
| `app.influora.in` | none | **still NO RECORD** |
| `api.influora.in` | none | **still NO RECORD** |
| `ai.influora.in` | none | **still NO RECORD** |

Measured with `nslookup -type=A <host> 8.8.8.8` from outside the box.

**Step 2 is therefore half-done.** The root and `www` moved to Utho, but the three names the
stack actually routes on do not exist. `APP_DOMAIN`/`API_DOMAIN`/`AI_DOMAIN` are what the
Caddyfile matches and what `INFLUORA_API_PUBLIC_URL`, `CORS_ALLOWED_ORIGINS`, `SPRING_JWKS_URL`
and `INFLUORA_MEERA_STREAM_PUBLICCHATURL` are all derived from. Until they resolve there is
nothing to deploy against, and step 3 must not be run — Caddy issuing against a non-resolving
name burns one of five Let's Encrypt failures per domain per week.

### F-0375 (new, BLOCKING) — nginx already owns ports 80 and 443

Probing `150.241.245.242` on 2026-08-21:

```
http://150.241.245.242/    -> 502 Bad Gateway    Server: nginx/1.26.3 (Ubuntu)
https://150.241.245.242/   -> 200 OK (143 KB)    Server: nginx/1.26.3 (Ubuntu)
https://influora.in        -> TLS FAILS: SEC_E_WRONG_PRINCIPAL (cert is for another name)
```

Three consequences:

1. **The compose stack is not deployed.** Caddy is not what is answering; nginx is, and its
   port-80 upstream is down (502).
2. **`docker compose up -d` will fail on a port conflict.** The compose binds `80:80`, `443:443`
   and `443:443/udp`. Decide first: stop/disable nginx, or move Caddy to alternate ports and have
   nginx proxy to it. Doing nothing means Caddy never starts.
3. **TLS on the apex is currently broken** — the served certificate does not match
   `influora.in`, so browsers warn today.

---

## 2 · A gap that breaks the App Review chain — F-0374

The production compose declares **40 environment variables and not one is a Meta credential.**
No `META_APP_ID`, no `META_APP_SECRET`, no `META_REDIRECT_URI`, no `META_TOKEN_ENCRYPTION_KEY`.

`MetaApiProperties.isConfigured()` is "app-id AND app-secret both non-blank", so on this stack
**Meta OAuth is off at boot** and Connect Instagram does nothing.

Why it matters here and not later: a Meta reviewer tests the submitted app. If the deployment
under review has the login flow disabled, the review fails — regardless of privacy policy URLs,
callbacks or anything else in Tier 0. **This must be fixed as part of the deploy, not after it.**

Add all seven (four Facebook-Login, three Instagram-Login from commit c186477):
```
META_APP_ID  META_APP_SECRET  META_REDIRECT_URI  META_TOKEN_ENCRYPTION_KEY
META_INSTAGRAM_APP_ID  META_INSTAGRAM_APP_SECRET  META_INSTAGRAM_REDIRECT_URI
```

---

## 3 · What the Utho box needs

- Ubuntu LTS, **8 GB RAM** recommended (4 GB is the floor — mysql + clamav + JVM + Python + Caddy;
  ClamAV alone holds ~1 GB of signatures)
- Docker Engine + Compose v2
- Ports **80** and **443** open to the world; **3306 and 6379 closed** — the compose exposes them
  on the host, so check the Utho firewall does not leave MySQL reachable
- `docker login ghcr.io` with a PAT carrying `read:packages` if the packages are private
- Disk for `mysql_data` and `caddy_data` volumes — certs live in `caddy_data`, so never wipe it or
  you will re-issue and hit Let's Encrypt rate limits

### Deployment method changes, nothing else
`deploy/hostinger/` is only Hostinger-named. The Caddyfile and compose are provider-agnostic —
domains come from `APP_DOMAIN` / `API_DOMAIN` / `AI_DOMAIN` / `ACME_EMAIL`, and Caddy issues
Let's Encrypt certs automatically once DNS points at the box. Hostinger deployed through its
management API with no SSH; on Utho it is plain SSH + `docker compose`. Simpler.

Rename `deploy/hostinger/` to `deploy/vps/` (or add `deploy/utho/`) so the directory stops naming
a provider that is no longer the target.

---

## 4 · Steps

1. **Secrets.** Assemble all 40 compose vars plus the 7 Meta vars from §2 into `/opt/influora/.env`
   on the box, root-owned, `chmod 600`. Generate fresh values for anything that ever lived in a
   repo or a doc. Never commit this file.
2. **DNS (GoDaddy panel — NOT Hostinger, see §1b).** `A app -> 150.241.245.242`,
   `A api -> 150.241.245.242`, `A ai -> 150.241.245.242`. The root and `www` already point at
   Utho as of 2026-08-21; leave them alone until the subdomains are proven. Confirm all three
   resolve from off-box before step 3.
3. **Bring it up.** `docker compose -f docker-compose.hostinger.yml --env-file .env up -d` with
   `APP_DOMAIN=app.influora.in`, `API_DOMAIN=api.influora.in`, `AI_DOMAIN=ai.influora.in`.
4. **Let Caddy issue certs.** Watch `docker compose logs -f caddy`. Certs need port 80 reachable
   and DNS already propagated — issuing before DNS resolves burns a rate-limit attempt.
5. **Flyway.** Confirm migrations applied cleanly, including
   `V20260820120000__meta_oauth_auth_path.sql` from commit c186477.
6. **Verify from outside the box** (see §5).
7. **Repoint the WordPress CTAs** to `https://app.influora.in` — closes **F-0370**, stops the live
   signup leak, and needs no cutover.
8. **Then** Tier 0: Meta URLs, callbacks, App Review.
9. Root-domain cutover and WordPress retirement: `T-DOMAIN-0820`.

---

## 5 · Exit test

Run from a machine that is NOT the server:

```bash
curl -sS -o /dev/null -w "api  %{http_code}  tls=%{ssl_verify_result}\n" https://api.influora.in/actuator/health
curl -sS -o /dev/null -w "app  %{http_code}  tls=%{ssl_verify_result}\n" https://app.influora.in/
```

Both must be `200` with `tls=0`. Then, by hand: register a new account, log in, land on a
dashboard. A green health check with a broken registration is not a deploy.

Additionally, and this is the one that catches F-0374: **open Connect Instagram and confirm it
produces a Meta dialog URL with a non-empty `client_id`.** A blank `client_id` renders as Meta's
"Invalid app ID" screen, which is what a reviewer would see.

---

## 6 · Risks

- **Secrets sprawl.** 47 variables is a large surface. One wrong value fails at runtime, not at
  boot, and often silently — the Meta gap in §2 is exactly that shape.
- **No CD.** Every deploy is manual SSH until a workflow exists. Acceptable now; write one before
  the third manual deploy.
- **ClamAV memory.** It is the most common OOM cause in this stack on a small box.
- **Cert rate limits.** Five failed issuances per domain per week. Get DNS right before step 3.
- **MySQL exposed.** The compose publishes 3306 on the host. Verify the Utho firewall blocks it.

## 7 · NOT CHECKED

**RESOLVED 2026-08-21 — GHCR images are current.** `Publish Images (GHCR)` succeeded on
`88e249a`, the current head of `main`, so all three images (`influora-api`, `influora-ai`,
`influora-web`) are built from the consolidated trunk. Caveat that still stands: `influora-web`
bakes `VITE_API_BASE_URL` at build time and the workflow default is `http://200.141.1.6/api/v1`.
Re-run `publish-images.yml` via `workflow_dispatch` with `vite_api_base_url=https://api.influora.in`
before go-live, or the frontend calls the wrong API.

**RESOLVED 2026-08-21 — Flyway applies cleanly to a fresh database.** This was the open risk
"they have only ever been run against existing ones". `flyway-validate.yml` had never once passed
(6 runs, 6 failures) and was repaired in `802d9f7`/`b25fb98`; it now runs `flyway:migrate` against
a clean MySQL 8 container on every push. Evidence: *Successfully applied 107 migrations to schema
`influora_flyway`, now at version v20260820120000*, followed by a post-migrate `flyway:validate`
with no exemptions. Includes `V20260820120000__meta_oauth_auth_path.sql`, so step 5 is
pre-verified.

Still not checked: whether the Utho instance size is adequate — no specification was provided.
Whether all 40 compose variables have known-good values anywhere, or whether some must be
regenerated. What nginx on the box is currently serving and whether anything depends on it
before it is stopped (see F-0375).
