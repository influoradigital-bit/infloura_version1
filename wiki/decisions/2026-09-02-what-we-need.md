# What We Actually Need — Consolidated
**2026-09-02** · Everything below was verified live (Meta API + production probes + code),
not inferred from docs. Ordered by what unblocks the most.

---

## The finding that reframes everything

    Graph API calls, last 30 days:  0
    Connected users (effective):    1
    Quota:                          240/day   (0% used)
    Webhook subscriptions:          0
    Deprecations:                   none
    Compliance:                     compliant, 0 violations

**The integration is fully built and completely idle.** Verified:

- `@EnableScheduling` is on (`InfluoraApiApplication.java:111`)
- `MetricsPollingJob` — every 6h, ShedLock'd (`MetricsPollingJob.java:95`)
- `MetaTokenRefreshService` — daily 02:30, refreshes before expiry (`:64`)
- `StaleTokenCleanupJob` — daily 04:00 (`:62`)
- plus AudienceDemographicsJob, CreatorCaptionSyncJob, DeliverableVerificationJob

Every one of them iterates
`tokenRepository.findByRevokedFalseAndExpiresAtAfter(Instant.now())`.

**Zero valid tokens → zero iterations → zero API calls.** The machinery is correct and
starved. We do not have a code problem. We have an activation problem.

---

## 1 · ONLY SWAPNIL CAN DO THESE (nothing else unblocks them)

| # | Need | Why it matters | Effort |
|---|---|---|---|
| 1.1 | **SSH access to Utho `150.241.245.242`** — which key + user | Blocks the deploy, which blocks the callbacks, which blocks App Review | minutes |
| 1.2 | **Cancel stuck submission `887626066959194`** | `can_submit:false`. Gates EVERY future App Review submission. The clock has not started. | 1 click |
| 1.3 | **Verify the contact email; move off `shinde111ms@gmail.com`** | Unverified personal gmail on a live app. Mail already runs on the domain (`noreply@influora.in`) | 10 min |

Note on 1.1: the live box is **Utho**, not Hostinger. The Hostinger MCP sees VPS 1844961
at `200.141.1.6` — a different machine. There is no CD workflow; every deploy is manual
SSH + `docker compose`.

---

## 2 · BLOCKED ON THE DEPLOY

| # | Need | Detail |
|---|---|---|
| 2.1 | Deploy `8f1153d` | Restores permitAll on Meta's webhooks. Code already correct at HEAD; production runs an older build. `docker compose pull influora-api && up -d` — **verify the GHCR `:latest` image is actually fresh first**, that job has failed before. |
| 2.2 | Register Data Deletion callback | `https://influora.in/api/v1/webhooks/meta/data-deletion` — only after 2.1, Meta validates on save |
| 2.3 | Register Deauthorize callback | `https://influora.in/api/v1/webhooks/meta/deauthorize` — same |

Success on 2.1 = both endpoints return **400** to an invalid signed_request. **401 means it
did not take.** Do not accept a green deploy log as proof.

---

## 3 · NOT BLOCKED — START NOW

| # | Need | Owner |
|---|---|---|
| 3.1 | **Get creators connecting Instagram.** This is the whole game. Everything above is plumbing for it. | Everyone |
| 3.2 | Instrument it: signups, connect starts, connect completions, 7/30d retention | Vikram |
| 3.3 | Non-admin connect test — Sage Digital World is an app admin; that is weaker proof than an ordinary creator | Kavya/Neha |
| 3.4 | Remaining dashboard fields: ToS URL `https://influora.in/terms/`, App Domains `influora.in`, Support URL `https://influora.in/support`, description + short description | Vikram |
| 3.5 | Reconnect email to existing registrants — BEFORE the Instagram post | Nisha |
| 3.6 | Carousel slides | Zara |

---

## 4 · NEXT TIER (only once 3.1 produces real volume)

| # | Need | Note |
|---|---|---|
| 4.1 | Instagram webhook subscriptions — `story_insights`, `mentions`, `comments` | **No new permission needed**, we already hold `instagram_manage_insights`. Stories expire in 24h and their insights have a short window; without this we either poll hard or lose the data permanently. Engineering work, not an application. |
| 4.2 | Submit `instagram_creator_marketplace_discovery` | Gated on 1.2 AND on having real usage — App Review asks how you use what you already hold, and today the answer is "we don't" |
| 4.3 | Re-attempt `pages_read_engagement` | Rejected 2026-01. Only worth retrying with a stronger use case. |

---

## 5 · DO NOT DO

- ❌ Chase the "Official Meta Partner" badge. Separate program, needs volume we do not have,
  6-12 months out. We are not one and must not say we are.
- ❌ Change `META_REDIRECT_URI` to the apex. OAuth works; the earlier redirect-mismatch
  theory was wrong and a real connect succeeded 2026-09-02.
- ❌ Point Hostinger tooling at this deploy — wrong machine.
- ❌ Use bare `influora.in/actuator/health` as a health check. Context-path is `/api/v1`,
  so the bare path hits the SPA catch-all and returns 200 + HTML whether or not the API is
  running. Use `/api/v1/actuator/health`; **401 there is the healthy signal.**

---

## Known, logged, not urgent

TLS covers only the apex + `www`. `app.influora.in` and `api.influora.in` have **no
certificate** and fail TLS. Everything works because the apex serves both the SPA and the
API — that is load-bearing accident, not design. `deploy/utho/generate-env.sh` still points
at `app.influora.in`, so it is out of sync with production. Own ticket, not this week.
