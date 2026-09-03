# Meta App Settings — Exact Values to Enter
**App:** `App-influora` · app_id `850102124044922`
**Dashboard:** https://developers.facebook.com/apps/850102124044922/settings/basic/
**Date:** 2026-09-02 · Every value below was verified against this repo, not guessed.

> These must be typed by a human. The Meta DevTools MCP is read-only — it can inspect
> settings but has no write action. Values are copy-paste ready.

---

## ⚠️ CORRECTION 2026-09-02, second pass — read this before using the URLs below

Re-probed live. The deployed topology is NOT what `deploy/utho/` describes:

    https://influora.in/         200 OK        <-- everything runs here
    https://www.influora.in/     200 OK
    https://app.influora.in/     TLS FAILURE   <-- no certificate
    https://api.influora.in/     TLS FAILURE   <-- no certificate

The TLS cert covers the apex and www only. The `app.` and `api.` subdomains are dead.
The Java API is served from the APEX under `/api/v1` (`influora.in/actuator/health` = 200).

Consequences:
- **The callback URLs in this sheet were corrected to use the apex.** Anything pointing at
  `api.influora.in` would have been registered dead.
- The P0 OAuth item below is **NOT** the blocker I first called it. Meta's allowlist holds
  the apex, the apex is the only working host, and a real connect succeeded on 2026-09-02.
  OAuth works. Leave it alone — see the revised P0.
- `deploy/utho/generate-env.sh` is out of sync with production. Worth reconciling, separately.

---

## 🔴 P0 (REVISED) — Meta's webhooks are 401ing in production

    POST influora.in/api/v1/webhooks/meta/data-deletion  -> 401 UNAUTHENTICATED
    POST influora.in/api/v1/webhooks/meta/deauthorize    -> 401 UNAUTHENTICATED

That is Spring Security rejecting Meta before the controller runs — Influora's own JSON
envelope, `"Your session has expired. Please sign in again."`

**The code is already correct.** `SecurityConfig.java:134-137` permitAlls both endpoints,
committed at HEAD `8f1153d` (FIX-WAVE-0828). **Production is running a build older than
that commit.** Nothing to write — this is a deploy, not a fix.

Until it ships: Meta cannot deliver deauthorize or data-deletion callbacks. That is a live
compliance failure, and App Review pings the callback URL the moment it is submitted.

ACTION: deploy HEAD (8f1153d or later) to production. Owner: Meera. Before registering
any callback URL in the dashboard.

---

## 🟡 P0-OLD (superseded — kept for the record)

## 🟡 ~~P0~~ — THE LIVE OUTAGE (fix before anything else)

**OAuth redirect URI does not match. Instagram connect is failing for every creator.**

    Meta allowlist  : https://influora.in/creator/settings/meta/callback
    App actually sends: https://app.influora.in/creator/settings/meta/callback
                             ^^^^ deploy/utho/generate-env.sh:108

Meta matches redirect_uri exactly. The app sends the `app.` subdomain, Meta only allows
the apex, so authorization is refused ("URL Blocked: redirect URI is not white-listed").
This is almost certainly why creators cannot connect right now.

**FIX — Settings > Basic > Facebook Login > Valid OAuth Redirect URIs.** Add:

    https://app.influora.in/creator/settings/meta/callback

Keep the apex entry too — both hosts serve the same SPA (deploy/utho/Caddyfile:24-30),
so allowlisting both is correct and costs nothing.

Do NOT "fix" this by editing generate-env.sh to use the apex. The app belongs on
app.influora.in; the dashboard is what is wrong.

---

## 🔴 P1 — Data Deletion Callback (currently a placeholder)

    CURRENT: https://www.facebook.com/          <-- points at Facebook's homepage
    CORRECT: https://influora.in/api/v1/webhooks/meta/data-deletion

The endpoint is already built and correct: MetaPlatformCallbackController.java:206,
`@RequestMapping("/webhooks/meta")` + context-path `/api/v1` (application.yml:112),
Caddy reverse-proxies without rewriting. It verifies the signed_request HMAC before
parsing, is idempotent, derives a deterministic confirmation_code, and returns Meta's
required `{url, confirmation_code}` shape. Nothing to write — just register it.

Use the **Callback URL** field, not "Data Deletion Instructions URL". We have a real callback.

---

## 🟠 P2 — Deauthorize Callback (built, never registered)

    CURRENT: null
    CORRECT: https://influora.in/api/v1/webhooks/meta/deauthorize

Settings > Basic > Facebook Login > Deauthorize Callback URL.
Endpoint exists (MetaPlatformCallbackController.java:138) and revokes tokens. Because it
is unregistered, we currently keep polling Graph for users who removed the app — a
compliance problem, not just a gap.

---

## 🟠 P3 — Missing basic settings

| Field | Current | Enter this | Verified |
|---|---|---|---|
| Terms of Service URL | `null` | `https://influora.in/terms/` | live, HTTP 200 |
| Privacy Policy URL | `https://influora.in/privacy` | `https://influora.in/privacy/` | live, 200 (add trailing slash — bare path 301s) |
| User Data Deletion | placeholder | see P1 | endpoint built |
| App Domains | `null` | `influora.in` | covers apex + app + api |
| Support URL | `null` | `https://influora.in/support` | route App.tsx:798 |
| Description | `null` | see copy below | — |
| Contact email | `shinde111ms@gmail.com`, **unverified** | a verified `@influora.in` address | see note |

### Contact email
A personal, unverified gmail on a live app is a weak signal at review and a blocker for
any partner conversation. Mail already runs on the domain (`MSG91_FROM_EMAIL=noreply@influora.in`,
generate-env.sh:80). Create and verify something like `developers@influora.in`.

### Description (paste into Settings > Basic)
> Influora is an influencer marketing platform that connects brands with Instagram
> creators. Creators connect their Instagram account to share verified reach,
> engagement and audience demographics with brands. Brands use that data to discover
> creators, run campaigns, and verify delivered posts.

### Short description
> Influencer marketing platform connecting brands with verified Instagram creators.

---

## Live status at time of writing (2026-09-02)

    influora.in/privacy/           200  OK
    influora.in/terms/             200  OK
    influora.in/meta-data-policy   200  OK
    api.influora.in/...            000  UNREACHABLE  <-- API is down (mid-upgrade)

DNS is healthy — all three hosts resolve to 150.241.245.242. The API container is simply
not answering. **Bring the API back up BEFORE saving the callback URLs**: Meta pings a
callback URL when you save it, and a dead endpoint can be rejected.

---

## Order of operations (REVISED)

1. **Deploy HEAD to production** — restores permitAll on the Meta webhooks. Without this,
   every callback URL you register below answers 401.
2. Re-probe: both webhook endpoints must return **400** (signature rejected), not 401.
3. Set the data deletion callback (P1).
4. Set the deauthorize callback (P2).
5. Fill the P3 fields.
6. Verify + switch the contact email.
7. Re-run `devtools_app basic_settings` and confirm every field is non-null.

Only then is the App Review submission worth filing — and that is still gated on
cancelling stuck submission `887626066959194`. See 2026-09-02-meta-approval-timeline.md.
