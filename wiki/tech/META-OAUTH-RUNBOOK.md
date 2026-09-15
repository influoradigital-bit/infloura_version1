# Meta / Instagram OAuth — runbook and failure catalogue

**Written 2026-09-13**, the day the Facebook-Login path succeeded in production for the first
time. Before that day the flow had **never once** produced a usable token: the only rows in
`meta_oauth_tokens` were from 2026-08-29, all `revoked=1` with `ig_business_account_id` null.

Three separate defects were stacked on top of each other. Each one hid the next, so fixing one
just moved the failure one step later. That is the main thing to expect when debugging this
flow — **a fix that changes the error is progress, not a regression.**

---

## 1. How the flow actually works

There are **two** paths. The creator chooses before being redirected (`MetaConnectPathDialog`,
added by `971618b`).

| | `FACEBOOK_LOGIN` | `INSTAGRAM_LOGIN` |
|---|---|---|
| For | creator whose IG is linked to a Facebook Page | creator with **no** Facebook Page |
| Dialog host | `www.facebook.com/<ver>/dialog/oauth` | `www.instagram.com/oauth/authorize` |
| Exchange host | `graph.facebook.com/<ver>/oauth/access_token` (GET, query params) | `api.instagram.com/oauth/access_token` (**POST, form body**) |
| Credentials | `META_APP_ID` / `META_APP_SECRET` | `META_INSTAGRAM_APP_ID` / `META_INSTAGRAM_APP_SECRET` |
| Scopes | `instagram_basic`, `instagram_manage_insights`, `pages_show_list` | `instagram_business_basic`, `instagram_business_manage_insights` |

The two app ids are **different numbers**. As of 2026-09-13: Facebook app `850102124044922`,
Instagram app `2118108391929480`. Confusing them is the most common setup mistake — the
Instagram id lives under *Instagram → API setup with Instagram login*, not Basic Settings.

### Request sequence (what a healthy connect looks like in nginx)

```
GET  /api/v1/meta/oauth/authorize?authPath=…   -> 200   creator clicks Connect
       … Meta / Instagram dialog, user consents …
       … browser is redirected to the SPA route, NOT the API …
POST /api/v1/auth/refresh                      -> 200   session restored (see §3.1)
GET  /api/v1/meta/oauth/callback?code=&state=  -> 200   ~7s: code→short→long-lived→store
```

### The redirect target is the SPA, not the API

`META_REDIRECT_URI = https://influora.in/creator/settings/meta/callback`

Meta redirects the **browser**, and a top-level navigation from facebook.com carries no
`Authorization` header. Pointing it at `/api/v1/meta/oauth/callback` makes every connect return
`UNAUTHENTICATED` — that was the pre-`0ac68e2` production state. `MetaRedirectUri.pointsAtApiCallback`
now refuses that configuration at boot rather than letting a creator discover it after consenting.

The SPA route (`creator-meta-callback.tsx`, `App.tsx:541`) reads `code`/`state` off its own query
string and calls the API itself as a normal authenticated request.

---

## 2. Current production configuration (2026-09-13)

```
META_APP_ID                  = 850102124044922
META_APP_SECRET              = <set>
META_INSTAGRAM_APP_ID        = 2118108391929480
META_INSTAGRAM_APP_SECRET    = <set>
META_REDIRECT_URI            = https://influora.in/creator/settings/meta/callback
META_INSTAGRAM_REDIRECT_URI  = (unset — falls back to ${influora.web-base-url} + the same path)
INFLUORA_WEB_BASE_URL        = https://influora.in
META_SYSTEM_IG_USER_ID       = (UNSET — F-0706, see §5)
META_SYSTEM_IG_ACCESS_TOKEN  = (UNSET — F-0706)
META_CREATOR_MARKETPLACE_ENABLED = (absent → false; the scope cannot be applied for)
```

`META_INSTAGRAM_REDIRECT_URI` being unset is **fine** — it defaults to the same SPA path. But it
only works because `INFLUORA_WEB_BASE_URL` is set; the Java default is `http://localhost:5173`,
which Meta would reject.

---

## 3. The failure catalogue

Each entry: what the creator sees → what the logs show → cause → fix.

### 3.1 `callback -> 401`, "Your session has expired. Please sign in again." (F-0812)

**Logs (nginx — the app log will NOT show this):**
```
15:50:23  GET /api/v1/me/creator-profile          401
15:50:23  GET /api/v1/deals?status=all            401
15:50:23  GET /api/v1/meta/oauth/callback?code=…  401
          (no POST /auth/refresh anywhere near it)
```

**Tell:** *everything* 401s, not just the callback, and **no refresh is attempted**.

**Cause — three locally-correct decisions colliding:**
1. F-0551 moved the access token to **memory only** in live mode. Meta's redirect is a top-level
   navigation, so the app reloads and that memory is empty.
2. `fetchWithAuthRetry` gates refresh-and-replay on `hasAuthHeader` (`api.ts:647`). With no token
   there is no header, so the 401 is returned untouched and the retry never runs.
3. The callback route is deliberately **unguarded** (`App.tsx:541`) so the auth guard cannot bounce
   a mid-connect creator to `/creator/login` — which also removed the guard's cold-load
   `api.auth.bootstrap`.

**Fix:** `creator-meta-callback.tsx` calls `api.auth.bootstrap('creator')` before the exchange when
live mode holds no in-memory token.

**Why the message is a lie:** the session had not expired, and signing in again cannot help —
Meta's `code` is single-use. "Try Again" replays a spent code and can only fail. **Always start a
fresh connect from Settings.**

### 3.2 `callback -> 502`, Meta `code 191` (F-0814)

**Logs (app):**
```
ERROR MetaOAuthService: Meta OAuth code-exchange failed: status=400,
  body={"error":{"message":"redirect_uri isn't an absolute URI. Check RFC 3986.",
                 "type":"OAuthException","code":191}}
GET /api/v1/meta/oauth/callback -> 502
```

**Cause:** callers percent-encode query values with `urlEncode` and hand `fetchToken` a finished
URL. `RestClient.uri(String)` treats it as a URI **template** and encodes it again, so
`https%3A%2F%2F…` went on the wire as `https%253A%252F%252F…`.

**Fix:** `fetchToken` passes `URI.create(url)` — a `java.net.URI` is handed to `RestClient`
untouched. Fixed once, covering all five callers.

**Why only the exchange broke:** `buildAuthorizationUrl` hands its URL to the **browser** as a
string and never touches `RestClient`. That is why the dialog always opened and the exchange never
worked.

**Reproduce/verify in one command** (safe — dummy code, Meta's own endpoint):
```bash
# single-encoded  -> code=100 "Invalid verification code format."   (healthy: reached the code check)
# double-encoded  -> code=191 "redirect_uri isn't an absolute URI." (the bug)
curl -s "https://graph.facebook.com/v25.0/oauth/access_token?client_id=$ID&client_secret=$SEC\
&redirect_uri=https%3A%2F%2Finfluora.in%2Fcreator%2Fsettings%2Fmeta%2Fcallback&code=DUMMY"
```

### 3.3 `authorize -> 503` `META_INSTAGRAM_LOGIN_NOT_CONFIGURED` (F-0801)

**Cause:** `META_INSTAGRAM_APP_ID` / `_SECRET` unset, so `isInstagramLoginConfigured()` is false.
The guard is deliberate — it refuses before the creator spends a dialog on it.

**Scale when it was live:** 161 of 336 authorize calls (48%) over five days. Every creator who
answered "no Facebook Page" was refused instantly, and nothing surfaced it.

**Fix:** set both values, then **recreate** the container (see §4).

### 3.4 `authorize -> 200`, then nothing (no callback at all)

The creator never comes back. Our logs show authorize and then silence. The failure is on
Instagram's side and only the creator's screen shows it. Candidates:

- **Not a Business/Creator account.** Instagram Business Login requires one; Personal is rejected.
- **Scopes without Advanced Access** — fails at the consent screen for anyone without a role on
  the Meta app. Works for you, fails for every real creator. *Classic check-passes-for-the-wrong-reason.*
- **redirect_uri not in the Instagram product's OAuth list** — a list **separate** from Facebook
  Login's "Valid OAuth Redirect URIs". Validated at the redirect, i.e. after consent.
- **Instagram anti-abuse.** Observed 2026-09-13: login bounced to `instagram.com/?e=1348020`, then
  a reCAPTCHA at `instagram.com/auth_platform/recaptcha/`, after that account's heavy auth volume
  that day. Not a code defect. Remedy is to stop retrying for a few hours and complete the
  challenge manually in a normal browser. **Do not attempt to bypass it.**

**Check our URL is well-formed** (public endpoint, no credentials):
```bash
curl -s -o /dev/null -w '%{url_effective}\n' -L \
 "https://www.instagram.com/oauth/authorize?client_id=2118108391929480\
&redirect_uri=https%3A%2F%2Finfluora.in%2Fcreator%2Fsettings%2Fmeta%2Fcallback\
&scope=instagram_business_basic%2Cinstagram_business_manage_insights&response_type=code&state=diag"
```
Landing on `accounts/login/` with `platform_app_id=…` and the scopes intact means the URL is
accepted that far. It does **not** prove the redirect_uri is whitelisted — that is checked later.

### 3.5 `callback -> 502`, Instagram `code 100` "Unsupported request - method type: get" (F-0818)

**Logs (app):**
```
ERROR MetaOAuthService: Meta OAuth instagram-long-lived-exchange failed: status=400,
  body={"error":{"message":"Unsupported request - method type: get",
                 "type":"IGApiException","code":100}}

# and the line that actually matters, which is a line that ISN'T there:
docker logs influora-api 2>&1 | grep -c 'instagram-code-exchange failed'   ->  0
```

**Tell:** the long-lived exchange fails on every attempt while the code exchange reports **zero**
failures — on a path that has never once succeeded. A leg with a 100% failure rate and no logged
errors is not a healthy leg; it is a leg whose failure is silent.

**Cause:** Business Login answers the code exchange with **HTTP 200** and the token inside a
single-element `data` array:

```json
{"data":[{"access_token":"IGAA…","user_id":17841400000000001,
          "permissions":"instagram_business_basic,instagram_business_manage_insights"}]}
```

`InstagramShortLivedTokenResponse` bound those three fields at the **top level** — the shape the
older Basic Display exchange returned — with `@JsonIgnoreProperties(ignoreUnknown = true)`. Jackson
found no top-level `access_token`, ignored `data`, and built a record of **all nulls without
throwing**. `CreatorMetaOAuthService` passed that null on, `urlEncode(null)` produced
`access_token=`, and graph.instagram.com answered the 400 above — **one leg later than the defect**.

Two more mismatches in the same body: `permissions` is a **comma-separated string** here (a JSON
array in the old shape), and `user_id` is a **JSON number**. On this path `user_id` is the only
source of the Instagram account id — there is no Page to resolve one from — so losing it loses the
connection even when a token is stored.

**Fix:** a custom deserializer that accepts **both** shapes (unwraps `data[0]` when present, reads
flat otherwise; parses `permissions` from either form; reads `user_id` with `asText()`), plus a
guard in `connectViaInstagramLogin` that throws when the exchange yields no `access_token`, so the
error names the leg that failed.

**Why "the error moved" is the expected outcome here.** This was the third defect stacked on this
path (F-0812 → F-0814 → F-0818). Each fix revealed the next. Do not read a changed error as a
regression.

**The general rule this cost us two days to apply:** when call B fails on a value produced by call
A, and A reports no failures on a path that has never succeeded, **suspect A's parsing before B's
URL.** Lenient deserialization reports success by staying silent. Probing B's endpoint, verb and
API version — all of which were correct — found nothing, because nothing was wrong there.


---

## 4. Diagnostics that actually work

**Use nginx, not `docker logs`, for 401s.** The JWT filter rejects before `CorrelationIdFilter`
runs, so a 401 on the callback is **invisible in the app log** and visible only in nginx. This cost
real debugging time.

```bash
# the connect sequence, with authPath preserved
grep 'meta/oauth' /var/log/nginx/access.log | tail -20

# outcomes tally
docker logs influora-api 2>&1 | grep -oE 'meta/oauth/[a-z]+ -> [0-9]+' | sort | uniq -c

# the real error body behind a 502
docker logs influora-api 2>&1 | grep -A2 'code-exchange failed'

# did a token actually land? (the only proof that matters)
SELECT auth_path, revoked, (ig_business_account_id IS NOT NULL) AS has_ig, created_at
  FROM meta_oauth_tokens ORDER BY created_at DESC LIMIT 5;
```

**`has_ig=1` and `revoked=0` is success.** A `callback -> 200` alone is not — it can also mean
`connected: false` for a personal IG account (F-0116/CR-103).

**Env changes need a container RECREATE, not a restart.** `influora-api` runs with
`docker run --env-file`, which bakes values at *create* time. `docker restart` re-runs the old env
and silently applies nothing.

---

## 5. Known-open at time of writing

- **F-0706** — `META_SYSTEM_IG_USER_ID` / `_ACCESS_TOKEN` unset. Brand-side Instagram handle
  lookup (Business Discovery) falls back to borrowing an arbitrary connected creator's token,
  which the code itself flags as needing a platform-terms ruling. `external_creators` is empty, so
  brand-side Instagram search returns nothing.
- **INSTAGRAM_LOGIN has never completed.** Zero tokens with that `auth_path` as of 2026-09-15.
  F-0818 (below) removed the defect that made it impossible, but **no live connect has been run
  since the fix** — the code path is proven only by tests. The next real creator connect is the
  first evidence that matters: `SELECT auth_path, revoked, (ig_business_account_id IS NOT NULL)
  FROM meta_oauth_tokens WHERE auth_path='INSTAGRAM_LOGIN'`.
- **Advanced Access on the Instagram scopes is unverified.**
- **Long-lived token expiry is assumed.** `CreatorMetaOAuthService` logs *"omitted expires_in;
  falling back to the documented ~60-day default"* — if Meta ever issues a shorter-lived token we
  would consider it valid past its death.

---

## 6. Timeline, 2026-09-13

| | |
|---|---|
| `0ac68e2` (prev. deploy) | redirect target moved from the API path to the SPA route |
| morning | `META_INSTAGRAM_APP_ID/_SECRET` set → `authorize` 503 → 200 |
| `016bcf2` | F-0812 session restore (FE) → callback 401 → 502 |
| `bb3e640` | F-0814 double-encoding (API) → callback 502 → **200** |
| 11:12:00 | first token ever stored `revoked=0, has_ig=1` |

Ledger: **F-0801, F-0812, F-0814**. Related: F-0706, F-0116/CR-103, CR-118, CR-120, T-IGTRUST-0907.

## 7. Timeline, 2026-09-15

| | |
|---|---|
| weekend | 36 `authorize?authPath=INSTAGRAM_LOGIN -> 200`, 19 × `callback -> 502`, 0 tokens |
| | 50 × `instagram-long-lived-exchange failed … code 100`, 0 code-exchange failures |
| F-0818 | code-exchange body binds `data[0]`; missing `access_token` now fails on its own leg |

**Not yet proven live.** F-0818 is closed on tests and on Meta's documented response shape. It has
not been run against Instagram itself. Verify with a real connect before telling a creator the
Instagram-only path works.
