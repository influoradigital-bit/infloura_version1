# Deploy Influora to Hostinger VPS (registry-images path)

**VPS:** `1844961` · KVM 2 (2 vCPU / 8 GB / 100 GB) · Ubuntu 24.04 · IP `200.141.1.6`
**Mechanism:** pre-built GHCR images pulled via the Hostinger management API (MCP). **No SSH.**
**Related:** `BLUEPRINT/06-DEPLOYMENT-AND-API-KEYS.md` (every secret, how to generate) ·
`BLUEPRINT/10-RUN-ON-SERVER-UTHO.md` (the SSH/build-on-box alternative).

This deploys through Claude's Hostinger MCP tools — no shell required. TLS is handled by the
Caddy container in the compose file, not host nginx.

---

## Prerequisites (hard gates — nothing works without these)

1. **A domain.** The app needs three subdomains with TLS: `app.` `api.` `ai.`. The frontend
   bakes its API URL in at build time and login uses a `Secure` cookie that won't send over
   plain HTTP — so it cannot run on a raw IP. Buy a domain first.
2. **DNS A-records** → `200.141.1.6` for `app.` `api.` `ai.`. Wait for propagation before deploy,
   or Caddy's cert issuance fails.
3. **All secrets generated** (see `BLUEPRINT/06`). You generate/supply these — Claude cannot enter
   API credentials. **Back up the two PII keys off this box** or the DB becomes unreadable forever.
4. **Sizing note:** 2 vCPU / 8 GB is under the 4 vCPU / 8 GB the runbook recommends, and n8n is
   already running. Add swap (Hostinger panel or a post-install script) and consider whether
   ClamAV (~2 GB RAM) is needed at launch.

---

## Step 1 — Publish the images (GitHub Actions)

`.github/workflows/publish-images.yml` builds & pushes to GHCR.

- **api + ai** are domain-independent → publish now: push to `main`, or run the workflow manually.
- **web** bakes the domain → publish *after* DNS is set. In repo **Settings → Variables**, add
  `VITE_API_BASE_URL=https://api.yourdomain.com/api/v1` and `VITE_MEERA_STREAM_URL=https://ai.yourdomain.com`,
  then run the workflow with **publish_web = true**.
- Make the GHCR packages **public**, or add a pull secret on the VPS (public is simplest).

## Step 2 — Deploy via the Hostinger MCP

Ask Claude to call `createNewProject` with:
- `virtualMachineId: 1844961`
- `project_name: influora`
- `content:` the contents of `docker-compose.hostinger.yml`
- `environment:` every `${VAR}` from that compose (domains + all secrets), one per line

Caddy issues certs on first boot. First Java boot runs 56 Flyway migrations — be patient.

## Step 3 — Redeploy after a change

Re-run the image workflow, then ask Claude to call `updateProjectV1(1844961, "influora")`
(pulls latest images, recreates containers, keeps volumes). **A `VITE_*` change means rebuilding
the web image** (Step 1) — a restart re-serves the stale bundle.

---

## Getting errors when something goes wrong

**On-demand (via Claude / Hostinger MCP), any time:**
| Want | Tool |
|---|---|
| Project + per-container status/health | `getProjectListV1` / `getProjectContainersV1` |
| Container logs (stack traces, boot failures) | `getProjectLogsV1` |
| CPU / RAM / disk / uptime (catch OOM) | `getMetricsV1` |
| Restore point before a risky change | `createSnapshotV1` |

**Built into the stack:**
- `restart: unless-stopped` on every service — auto-recovers from crashes.
- Healthchecks on mysql + clamav.
- App health endpoints: `https://api.yourdomain.com/api/v1/health`, `https://ai.yourdomain.com/healthz`.

**Active alerting (recommended — n8n is already on this box):**
- An n8n cron workflow polls the two health endpoints every few minutes and emails/Telegrams you
  on failure. Cheapest path, zero extra infra. (Claude can build this workflow.)
- Or an external uptime monitor (e.g. UptimeRobot) on the public health URLs.
- Optional: add Sentry to influora-api / influora-ai for full stack traces + release tracking.

---

## Security (Kabir) — do these regardless of deploy timing

- **The VPS currently has NO firewall.** Attach one allowing only: `22/SSH` from your IP, `80` +
  `443` from anywhere. Do **not** expose 3306 / 8080 / 8000 / 3310. Decide whether n8n's `5678`
  should stay public (currently it is) or be locked to your IP.
  ⚠️ A Hostinger firewall defaults to drop-all — add the SSH allow rule **before** activating, or
  you lock yourself out of the box.
- Keep secrets out of Git (they live only in the MCP `environment` field / Hostinger panel).
