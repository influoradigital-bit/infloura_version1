# CTO answers — CEO store/affiliate audit, questions 1–34 (sections A, B, C)

**From:** Priya (CTO) · **To:** Swapnil Maruti (CEO) · **Date:** 2026-09-08
**Scope:** Q1–Q34 only. Q35–50 are another reader's.
**Method:** every file below was opened in this session at the cited line. Where a comment,
javadoc or doc contradicts the code, the code wins and I say so.
Paths are relative to the repo root.

---

## A. Shopify — can a brand actually connect? (1–13)

### 1. If a brand clicks "Connect Shopify" in production right now, what happens?

**Verdict: ABSENT (the flow stops at the first hop and cannot start).**

Trace, each hop opened:

1. `src/pages/brand-settings.tsx:937` mounts `<StoreIntegrationSetup />`.
2. `src/components/brand/settings/StoreIntegrationSetup.tsx:250` — the "Connect Shopify" button calls
   `startShopifyOAuth` (`:81`).
3. `StoreIntegrationSetup.tsx:90` → `api.storeIntegrations.authorizeShopify(shop)`.
4. `src/lib/api.ts:5182-5184` → `GET /shopify/oauth/authorize?shop=…` in live mode.
5. `influora-api/src/main/java/com/influora/web/ShopifyConnectController.java:79` —
   `requireBrand(principal)` passes for a brand.
6. `ShopifyConnectController.java:94` — `if (!shopifyProperties.isConfigured())` → **throws
   `SHOPIFY_NOT_CONFIGURED`, HTTP 503.**
7. `StoreIntegrationSetup.tsx:93` renders the server message: "Connecting a Shopify store is not
   available on this environment."

**It stops at step 6, in every environment, on the first click.** `isConfigured()`
(`influora-api/src/main/java/com/influora/config/ShopifyProperties.java:33-35`) requires a non-blank
`apiKey` **and** `apiSecret`; both default to `""` (`ShopifyProperties.java:25-26`) and nothing sets
them (Q2).

Nothing after step 6 has ever executed: no authorize URL, no state token, no token exchange, no
webhook registration, no order. Everything in Q4–Q13 is code that is correct on paper and has never
met Shopify.

### 2. Are `influora.shopify.api-key` / `api-secret` bound to any real value in any environment?

**Verdict: ABSENT.**

- `influora-api/src/main/resources/application.yml:457-469` — the `shopify:` block binds exactly two
  keys: `token-encryption-key` (`:458`) and `redirect-uri` (`:469`). **There is no `api-key`,
  `api-secret`, `webhook-signing-secret`, `scopes` or `api-version` line in it.**
- `influora-api/src/main/resources/application-dev.yml:29-30` — dev binds only
  `token-encryption-key`.
- `deploy/utho/docker-compose.utho.yml:162`, `deploy/utho/docker-compose.utho-shared.yml:181`,
  `deploy/hostinger/docker-compose.hostinger.yml:141` — each passes only
  `INFLUORA_SHOPIFY_TOKENENCRYPTIONKEY`.
- `deploy/utho/generate-env.sh:55` — generates only `INFLUORA_SHOPIFY_TOKENENCRYPTIONKEY`.
- A repo-wide grep for `SHOPIFY_APIKEY` / `SHOPIFY_API_KEY` / `shopify.api-key` outside test sources
  returns one hit: the *comment* at `application.yml:466` saying they are unbound.

**Which file would set them:** either a new `api-key:`/`api-secret:` pair under `influora.shopify` in
`application.yml`, or — via Spring relaxed binding, which needs no yaml line at all — env vars
`INFLUORA_SHOPIFY_APIKEY` / `INFLUORA_SHOPIFY_APISECRET` supplied by the compose files and
`generate-env.sh`. **Neither exists.**

One correction to our own comment. `ShopifyConnectController.java:82-84` claims apiKey/apiSecret are
unbound because there is "no yaml placeholder". `application.yml:440-453` — same file, 20 lines
above the shopify block — documents at length that Spring relaxed binding *does* bind a
`@ConfigurationProperties` field with no yaml entry at all. So the stated *reason* is wrong; an
operator who exported `INFLUORA_SHOPIFY_APIKEY` on the box today would bind it. What is true and
decisive is that **no file in this repo sets that env var**, so `isConfigured()` is false on every
box this repo deploys.

### 3. Does `ShopifyProperties.isConfigured()` return true anywhere today?

**Verdict: ABSENT — it returns false everywhere.**

`config/ShopifyProperties.java:33-35` returns true only when both `apiKey` and `apiSecret` are
non-blank; `:25-26` default both to `""`; Q2 shows nothing assigns them.

**User-visible consequence:** the brand clicks "Connect Shopify" and gets a 503 with "Connecting a
Shopify store is not available on this environment" (`ShopifyConnectController.java:96-98`, rendered
at `StoreIntegrationSetup.tsx:93`). That is the honest outcome and is the improvement shipped
2026-09-08 — before it, the same click handed the brand an authorize URL with an empty `client_id`
(`integration/shopify/oauth/ShopifyOAuthService.java:96-97` interpolates `props.getApiKey()`
unconditionally) and Shopify's own error page took the blame for our missing app.

Second-order consequence worth naming: the Shopify half of the store-integration product is
**advertised but unavailable**. `src/content/festival-box.ts:225` tells brands "Connect Shopify or
WooCommerce once — orders report themselves, no spreadsheets." Half of that sentence is false today.

### 4. Does `/authorize` refuse BEFORE any OAuth state is minted?

**Verdict: WORKS (ordering is correct).**

`ShopifyConnectController.java:94-99` is the `isConfigured()` guard; `:102` (`stateStore.issue(...)`)
is the mint; `:101` (`validateShopDomain`) also sits after the guard. The guard throws, so nothing
below it runs.

**Why the ordering matters — more than the comment says.**
`integration/shopify/oauth/ShopifyOAuthStateStore.java:29` is a plain `ConcurrentHashMap`; `:34` puts
an entry with a 10-minute TTL. **Nothing sweeps it.** The only removal is `pending.remove(state)`
inside `consume` (`:46`), and expiry is checked *after* that removal (`:50`) — so an entry that is
minted and never consumed lives **forever**, not ten minutes. Had the guard sat after the mint, every
`/authorize` call in a permanently-unconfigurable deploy would leak one unreclaimable map entry, on
an endpoint any authenticated brand can call in a loop. Guard-before-mint turns an unbounded memory
leak into a cheap 503. That reason is independent of the UX reason the comment gives, and it is the
stronger one.

I disagree with one line of the comment at `ShopifyConnectController.java:91-93`: "/callback is
unreachable with a valid state." True of a *valid* state; but "unreachable" and "reachable and always
refused" are different claims and only the second is accurate — see Q5.

### 5. Can `/shopify/oauth/callback` be reached and completed while `isConfigured()` is false?

**Reached: yes. Completed: no.** Verdict on the security property: **WORKS**. Verdict on the comment
describing it: **overstated**.

`ShopifyConnectController.java:112-117` maps `/callback` with no configuration guard of its own. Any
authenticated brand can call it directly. Order of operations:

- `:118` `requireBrandWorkspace` — passes for a brand.
- `:119` `validateShopDomain` — 400 for a non-`*.myshopify.com` value
  (`ShopifyOAuthService.java:80-88`).
- `:121` `stateStore.consume(state, userId, validatedShop)` → `ShopifyOAuthStateStore.java:46`
  `pending.remove(state)` returns `null` for any state, because the only writer is `issue()` (`:34`)
  and its only caller is `ShopifyConnectController.java:102`, which the Q4 guard makes unreachable →
  `false` → **400 `SHOPIFY_OAUTH_STATE_INVALID`** (`:122-126`).

The token exchange at `:128` is therefore never reached. The endpoint **is** reachable — a live,
mapped, brand-authenticated route that answers requests — it simply always refuses.

**One genuine consequence of the missing second guard that the comment's reasoning misses.** The
state store is a **per-JVM** map (`ShopifyOAuthStateStore.java:18-19, :29`; its own javadoc says
"move to a shared store (Redis) if/when horizontally scaled"). On the day credentials land, if the
API runs more than one replica behind a load balancer, `/authorize` on replica A and `/callback` on
replica B produce this exact same 400 — an intermittent, unfixable-from-the-brand's-side "OAuth state
is invalid" on roughly (N−1)/N of connect attempts. **That is a Shopify go-live blocker that has
nothing to do with credentials**, and no unit test can see it.

### 6. Where does the authorize URL's `redirect_uri` come from, and does it point at something real?

**Verdict: WIRED (correct in code, never exercised).**

- Consumed at `integration/shopify/oauth/ShopifyOAuthService.java:100-101` (`props.getRedirectUri()`).
- Bound at `application.yml:469`:
  `redirect-uri: ${INFLUORA_SHOPIFY_REDIRECTURI:${influora.web-base-url}/brand/settings/shopify/callback}`.
- The target now exists: `src/App.tsx:394` registers `path="/brand/settings/shopify/callback"`
  rendering `BrandShopifyCallbackPage` (`src/App.tsx:26` import;
  `src/pages/brand-shopify-callback.tsx:38`).

So yes, as of commit `f3ae0dc` (2026-09-08) it points at a real SPA route. It has never been used,
because Q1 stops the flow before the URL is ever built.

### 7. Is there a frontend route serving Shopify's post-approval redirect? What did a browser see before 2026-09-08?

**Verdict: WIRED as of today; ABSENT before today.**

`git log --diff-filter=A -- src/pages/brand-shopify-callback.tsx` returns exactly one commit:
`f3ae0dc 2026-09-08`. Before that commit the route did not exist.

What a browser landing there saw before: nothing usable — an SPA 404 for the configured path, or, if
`redirect-uri` had been pointed at the API instead, raw JSON.
`src/pages/brand-shopify-callback.tsx:15-19` says it plainly: `GET /shopify/oauth/callback` answers
with a JSON `ApiResponse`, not a 302, so the browser "landed on the API host looking at raw JSON with
no link back into the app. The merchant's only way home was to retype the URL."

Note also that `ShopifyConnectController.java:36-37` has claimed since D1 that "the frontend route at
`redirect-uri` reads the result and forwards the user". **That javadoc was false for the entire life
of the class until today.** Cost: zero in practice, because Q1/Q2 mean no merchant has ever reached
Shopify's approval screen. It was a latent defect that would have fired on day one of the credentials
landing, not one that has been losing us customers.

### 8. Do the redirect-uri config value and the registered SPA route agree?

**Verdict: WORKS today; unguarded against drift.**

- Config default path: `/brand/settings/shopify/callback` (`application.yml:469`).
- SPA route path: `/brand/settings/shopify/callback` (`src/App.tsx:394`).

They agree byte for byte.

**What breaks if they drift:** Shopify pins `redirect_uri` at app-registration time and refuses any
authorize request whose value is not on the app's allow-list; and if they drift *after* registration,
the merchant approves the install and is dropped on a SPA 404 holding a live single-use `code`, with
the grant stranded at Shopify and no way to complete. The connect fails and, from the merchant's
side, silently.

**Would a test catch it?** No. The two values live in different languages and build systems with no
shared constant, and `INFLUORA_SHOPIFY_REDIRECTURI` (`application.yml:469`) lets an operator override
the yaml default at deploy time with a path no test ever sees. **A drift introduced on the deploy box
is invisible to every test in this repo.** This is the most likely single cause of a failed Shopify
go-live day.

### 9. Does anything subscribe a connected store to `orders/paid`?

**Verdict: WIRED (mechanism now exists; never run against Shopify).**

**Mechanism:** `influora-api/src/main/java/com/influora/integration/shopify/ShopifyWebhookRegistrar.java`.
Topics at `:63` (`List.of("orders/paid", "orders/create")`); the subscription call at `:128-138`
(`POST https://{shop}/admin/api/{version}/webhooks.json` with an `X-Shopify-Access-Token` header);
invoked from `ShopifyConnectController.java:147` as the last step of a successful callback.

The class javadoc at `ShopifyWebhookRegistrar.java:24-31` is correct and answers the CEO's real
question: **before commit `f3ae0dc` today, nothing in this codebase ever told Shopify to send us
anything.** `ShopifyWebhookController` was built, HMAC-verified, idempotency-guarded and unit-tested,
and could not have received a single delivery. I re-verified rather than trusting it: a repo-wide
search for `webhooks.json`, `registerWebhook`, `createWebhook` and any `*.toml` finds
`ShopifyWebhookRegistrar.java:129` as the only `webhooks.json` in main source, and `./.gitleaks.toml`
as the only `.toml` in the repository.

### 10. Is there a `shopify.app.toml` manifest anywhere?

**Verdict: ABSENT.**

`find . -name "*.toml"` (excluding `node_modules`, `.git`, `target`, worktrees) returns exactly one
file: `./.gitleaks.toml`. There is no `shopify.app.toml` and no Shopify CLI scaffolding of any kind.

**Therefore the only remaining way this app can receive a webhook is the Admin API subscription
call** at `ShopifyWebhookRegistrar.java:128-138`, made per-store at connect time with that store's own
access token. There is no declarative fallback: any store whose registration call is skipped or lost
receives nothing, forever, silently.

### 11. Where does the webhook delivery address come from? Hardcoded? Context path included?

**Verdict: WIRED. Not hardcoded. Context path included — by the deploy value, not by construction.**

- Base: `ShopifyWebhookRegistrar.java:74` — `@Value("${influora.api.public-url:http://localhost:8080}")`.
- Bound at `application.yml:145`: `public-url: ${INFLUORA_API_PUBLIC_URL:http://localhost:8080}`.
- Deploys set it *including* the context path: `deploy/utho/docker-compose.utho.yml:132`,
  `deploy/utho/docker-compose.utho-shared.yml:151`, `deploy/hostinger/docker-compose.hostinger.yml:116`
  all set `INFLUORA_API_PUBLIC_URL: https://${API_DOMAIN}/api/v1`.
- Suffix: `ShopifyWebhookRegistrar.java:66` — `WEBHOOK_PATH = "/webhooks/shopify"`, appended at `:125`.
  It must equal `web/ShopifyWebhookController.java:91` (`@RequestMapping("/webhooks/shopify")`).
  Today it does.
- The context path is real: `application.yml:119` — `server.servlet.context-path: /api/v1`.
- Non-https is refused before any subscription is created (`ShopifyWebhookRegistrar.java:117-124`),
  which is right — Shopify will not deliver to plaintext.

Two things for the record:

1. **The `/api/v1` is present only because the operator put it in the env var.** The code never reads
   `server.servlet.context-path` (`application.yml:119`) and appends it. If anyone sets
   `INFLUORA_API_PUBLIC_URL` to a bare origin — which the property *name* invites — the address
   silently becomes `https://host/webhooks/shopify` and every delivery 404s with nothing reporting it.
   `deploy/hostinger/docker-compose.test.yml:91` already carries a hand-written, plaintext
   `http://200.141.1.6/api/v1`; the value is maintained by hand, per file.
2. `WEBHOOK_PATH` (`:66`) and the controller mapping (`ShopifyWebhookController.java:91`) are two
   independent string literals with a comment asserting they match. Nothing enforces it.

### 12. What happens if webhook registration fails midway through connect?

**Verdict: the brand is left DISCONNECTED on our side — the right call — but something is left behind
at Shopify, and two worse latent bugs sit on the same path.**

`ShopifyConnectController.java:146-157`: `registerOrderWebhooks` is wrapped; on any
`RuntimeException`, `tokenStorage.revoke(workspace.getId())` runs (`:149`) and the exception is
rethrown (`:156`). The brand sees an error, `shopify_integrations.revoked` becomes true
(`integration/shopify/oauth/ShopifyTokenStorage.java:128-133`), status reads not-connected. The
reasoning in the comment (`:140-145`) is correct: a silent half-connected store is worse than a loud
failure.

Four things that are not clean:

**(a) An orphaned subscription at Shopify.** `ShopifyWebhookRegistrar.java:100-102` loops the two
topics sequentially. If `orders/paid` succeeds and `orders/create` then fails, we revoke our row but
the `orders/paid` subscription **still exists on the merchant's store, pointing at us**. Shopify then
delivers to `ShopifyWebhookController`, which 404s `SHOP_NOT_CONNECTED`
(`ShopifyWebhookController.java:155-160`) because the row is revoked; non-2xx; Shopify retries for
~48h and then deletes the subscription. Self-healing, but a failed connect leaves a live inbound
webhook and two days of retry noise, and nothing deletes it deliberately.

**(b) `revoke()` is workspace-wide, not connection-specific.** `ShopifyTokenStorage.java:128-131`
revokes `findByWorkspaceIdAndRevokedFalse(workspaceId)` — whatever that workspace's active connection
happens to be. Combined with (c), a brand already connected to store A who attempts store B loses A
when B's registration fails.

**(c) A real defect: `storeToken` rotates in place and never updates the shop domain.**
`ShopifyTokenStorage.java:85-90` — when an active row exists for the workspace it calls
`existing.get().rotateToken(encrypted, scopesJson)`. I opened
`domain/entity/ShopifyIntegration.java`: `rotateToken` is at `:104-107` and sets only the token, the
scopes and `revoked=false`. **It does not set `shopDomain`.** So a brand who connects store B while
store A is active ends up with B's token stored under **A's** `shop_domain`. Webhook resolution
(`ShopifyWebhookController.java:154`, `findByShopDomainAndRevokedFalse`) then attributes B's orders to
the row labelled A, and the F-0726 ownership check (`:188`) calls A's Admin API with B's token and
fails every time. Latent only because nobody can connect at all.

**(d) A second real defect: reconnecting after a disconnect will 500.**
`influora-api/src/main/resources/db/migration/V27__shopify_integrations.sql:29` declares
`UNIQUE KEY uq_shopify_shop_domain (shop_domain)` — **not** conditioned on `revoked`. Disconnect keeps
the row and flips `revoked=true` (`ShopifyTokenStorage.java:128-133`; the migration comment at `:23`
confirms rows are never deleted). On reconnect, `storeToken`'s `findByWorkspaceIdAndRevokedFalse`
(`:85`) returns empty, so it takes the **insert** branch (`:91-99`) with the same `shop_domain` →
duplicate-key violation → `common/GlobalExceptionHandler.java:110-115` → 409
`DATA_INTEGRITY_VIOLATION`. This is the Shopify twin of Q19 and fails the same way; neither is
handled at the point where a useful message could be produced.

### 13. Is the Shopify access token encrypted at rest, and with which key? Is that key distinct?

**Verdict: WORKS as code; WIRED in the sense that no real token has ever passed through it.**

- Encryption: AES-256-GCM, 12-byte random IV prepended, base64. `ShopifyTokenStorage.java:41-44`
  (algorithm and parameters), `:147-163` (`encrypt`), `:82` (encrypt-then-store; plaintext is never
  persisted and never logged — the audit detail map at `:110-112` carries only the shop domain and a
  scope count).
- Column: `V27__shopify_integrations.sql:21` — `encrypted_access_token TEXT NOT NULL`, documented as
  AES-256-GCM ciphertext.
- Key: `influora.shopify.token-encryption-key`, read at `ShopifyTokenStorage.java:54`; must decode to
  exactly 32 bytes or the bean refuses to start (`:57-67`) — fail-closed, no silent plaintext path.
- Binding: `application.yml:458` → `INFLUORA_SHOPIFY_TOKENENCRYPTIONKEY`; supplied by
  `deploy/utho/docker-compose.utho.yml:162`, `docker-compose.utho-shared.yml:181`,
  `deploy/hostinger/docker-compose.hostinger.yml:141`.

**Distinct from every other secret: yes.** `deploy/utho/generate-env.sh:51-57` calls `aes32` seven
separate times, one per key — PII email/phone, PII bank, admin MFA, Meta, **Shopify**, WooCommerce,
conversion-webhook — each an independent value; `:174` verifies all seven decode to 32 bytes. It is
not shared with the JWT secrets (`:45-46`), the Meera stream secret (`:47`), the internal service
token (`:48-49`) or JWKS (`:58-59`).

One caveat, not about this key's distinctness: `application-dev.yml:30` commits a real, working
32-byte AES key for the `dev` profile. It is documented as throwaway and dev-only (`:23-26`) and prod
config wins, so I accept it — but it is a live key in git history and must never be copied forward.

---

## B. WooCommerce — the one that is live-reachable (14–22)

### 14. Could a brand connect a WooCommerce store successfully today, right now?

**Verdict: the CONNECT step WORKS. End-to-end attribution is WIRED and has never been proven
against a real WooCommerce store.**

You are right that no app credentials are needed. The whole connect path is:

- `src/pages/brand-settings.tsx:937` → `StoreIntegrationSetup.tsx:300` ("Verify & Save") →
  `submitWooCommerce` (`:98`) → `src/lib/api.ts:5205-5207` → `POST /woocommerce/connect`.
- `influora-api/src/main/java/com/influora/web/WooCommerceConnectController.java:61-80`. It needs
  exactly two things from the brand and nothing from us: a site URL (`:66`) and a non-blank webhook
  secret (`:68-75`). No `isConfigured()` guard exists here at all — correctly, because there is
  nothing to configure.
- The only server-side prerequisite is the storage key.
  `integration/woocommerce/WooCommerceIntegrationService.java:55` reads
  `influora.woocommerce.token-encryption-key`, and `:58-68` refuses to start the bean if it is blank
  or not 32 bytes. That key **is** bound: `application.yml:471` →
  `INFLUORA_WOOCOMMERCE_TOKENENCRYPTIONKEY`, generated at `deploy/utho/generate-env.sh:56` and passed
  at `deploy/utho/docker-compose.utho.yml` / `docker-compose.utho-shared.yml` /
  `deploy/hostinger/docker-compose.hostinger.yml` (the same env-var list checked at
  `generate-env.sh:174`).

So a first-time connect for a workspace stores a row and returns `connected: true`.

Two hard caveats, both of which I would want closed before telling a customer this works:

1. **The button lies about what it does.** `StoreIntegrationSetup.tsx:302` reads "Verify & Save", and
   `WooCommerceDtos.java:17` describes the response as "once the secret has been validated".
   **Nothing is verified.** `submitWooCommerce` (`:98-118`) posts and reports success; the controller
   (`WooCommerceConnectController.java:66-79`) normalizes the URL, checks the secret is non-blank,
   encrypts and stores. There is no ping, no test delivery, no round trip to the brand's site — the
   integration is receive-only by design (`WooCommerceSiteUrl.java:14-17`). A brand who mistypes the
   secret is told "Connected to https://their-store.com" and finds out months later that no order was
   ever attributed.
2. **Nothing in this repository shows this path having run against a real WooCommerce install.**
   Every test is a unit test with a fabricated fixture. See Q16 for what that cost us once already.

### 15. What exactly does a brand paste into the connect form, and where do those values come from?

Two fields, `StoreIntegrationSetup.tsx:279-299`:

- **"Your Store URL"** (`:280-287`, placeholder `https://your-store.com`) → `siteUrl`. From the
  brand's own site. Normalized server-side to `scheme://host[:port]`, lower-cased, path/query/
  fragment and trailing slash stripped (`integration/woocommerce/WooCommerceSiteUrl.java:37-55`).
  That normalization is load-bearing, not cosmetic: the same function normalizes the inbound
  `X-WC-Webhook-Source` header at `WooCommerceWebhookController.java:213`, so if the two ever
  disagreed every delivery would 404 (`:216-222`).
- **"Webhook Secret"** (`:289-298`, masked, placeholder `whsec_...`) → `webhookSecret`. From the
  brand's WooCommerce admin: the on-screen instructions at `:265-276` walk them through
  **Settings → Advanced → Webhooks → Add webhook**, set the Topic to an order event (`:268`), paste
  our Delivery URL (`:270-274`), then copy the webhook's "Secret" field (`:275`). That is WooCommerce's
  own per-webhook secret, which the merchant sets or WooCommerce generates. It is the HMAC key for
  every delivery from that site (`integration/woocommerce/webhook/WooCommerceWebhookSignatureVerifier.java:54-63`).

One gap in those instructions: they never tell the brand which Topic values we actually act on.
`WooCommerceWebhookController.java:129-131` acts only on `order.created` and `order.updated`;
`:231-235` acknowledges every other topic with a 200 and silently drops it. The UI says "any order
event (e.g. 'Order updated')" (`StoreIntegrationSetup.tsx:268`) — a brand who picks
`order.deleted`, or the Action/Coupon resources, gets a green "connected" and zero attribution.

### 16. Is the webhook Delivery URL shown to brands correct? What was it before, and what did that cost?

**Verdict: correct NOW (as of commit `db21a8c`); it was wrong for the entire prior life of the
feature.**

Now: `StoreIntegrationSetup.tsx:37-39` derives it —
`` `${import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'}/webhooks/woocommerce` ``.
That matches `WooCommerceWebhookController.java:121` (`@RequestMapping("/webhooks/woocommerce")`)
beneath `server.servlet.context-path: /api/v1` (`application.yml:119`).

Before: I did not take the comment's word for this — `git log -S` on the file shows the literal
`https://api.influora.com/webhooks/woocommerce` present in commit `8900bbc` and removed in
`db21a8c` ("fix(brand): derive the WooCommerce webhook URL instead of printing a dead one"). It was
wrong twice over:

1. **Wrong TLD.** Our API is `api.influora.**in**` (`.github/workflows/publish-images.yml:250`
   bakes `https://api.influora.in/api/v1`). `.com` is not ours.
2. **Missing the context path.** Even on the right host, `/webhooks/woocommerce` without `/api/v1`
   404s.

**What it cost:** every brand who ever followed those instructions created a webhook in their own
store pointing at a host we do not own. WooCommerce accepts any URL without checking it, so the
brand saw a saved webhook and a green "Connected to …" from us, and deliveries failed out of sight
in their store's own webhook log. **Influora recorded no orders while the brand believed tracking was
live, and nothing on either side reported a problem.** That is the precise failure mode: not an
outage, a lie. I cannot bound the customer count from the code; that needs a query against
`woocommerce_integrations` on the live box, and I would want that run before we tell anyone this
works now.

One residual I want on the record: the repo carries **two disagreeing values** for the API origin,
and the derivation only moved the second source of truth, it did not remove it.

- `.env.production:38` — `https://influora.in/api/v1`. Per `.env.production:30-32` this file governs
  a local `npm run build`, **which is what feeds `/var/www/influora` on the Utho box** — i.e. the
  live production site. So the Delivery URL a brand is shown in production today is
  `https://influora.in/api/v1/webhooks/woocommerce`, which matches the box. **Correct.**
- `.github/workflows/publish-images.yml:250` — the published `influora-web` image still defaults to
  `https://api.influora.in/api/v1`. `.env.production:15-21` records that this host was verified on
  2026-09-07 to resolve to the box but have no vhost and no certificate, presenting
  `CN=snapsby.com`. Any deployment served from that image would therefore print a Delivery URL on a
  host that **fails TLS**, and the instructions would be dead a third time, in a third way.

`Dockerfile:15-20` confirms the precedence: the `--build-arg`-promoted `ENV` (`:55, :59-60`) wins in
Vite's `loadEnv` over `.env.production`. So which of the two values a brand is shown depends entirely
on which build path produced their bundle, and nothing reconciles them.

### 17. Does anything verify the brand actually owns the site URL they claim?

**Verdict: ABSENT. Nothing verifies ownership.**

`WooCommerceConnectController.java:66-79` is the complete validation: normalize the URL
(`WooCommerceSiteUrl.java:37-55` — well-formedness and `http`/`https` only) and require a non-blank
secret. No DNS check, no file-drop challenge, no meta-tag challenge, no outbound call at all —
`WooCommerceSiteUrl.java:14-17` states the integration never dials the submitted host.

**What stops you registering a competitor's domain: nothing.** You can POST
`{"siteUrl":"https://competitor.com","webhookSecret":"anything-you-choose"}` and it is stored against
*your* workspace.

The damage is narrower than it first looks, and I want to be precise about it rather than alarmist:

- You do **not** gain the ability to write into the competitor's workspace. Attribution follows the
  stored row: `WooCommerceWebhookController.java:214-216` resolves the site to **your** integration,
  and `:250, :257` scope every redemption to **your** `workspaceId`.
- What you gain is (a) the ability to **fabricate orders into your own workspace** — you chose the
  secret, so you can sign anything (see Q44's territory), and (b) **denial of registration**: the
  real owner of that domain can now never connect. That is Q18, and it is the sharp end.

There is also a dead half-fix sitting right there:
`repository/WooCommerceIntegrationRepository.java:25` declares
`existsBySiteUrlAndRevokedFalse(String)` and
`repository/ShopifyIntegrationRepository.java:23` declares `existsByShopDomainAndRevokedFalse(String)`.
I grepped all of `influora-api/src` for both: **neither is called from anywhere.** The pre-connect
duplicate/ownership check they were plainly written for was never wired up.

### 18. `site_url` is globally unique. What happens to the real owner of a domain someone else registered first?

**Verdict: the real owner is locked out, and gets a 500 rather than an explanation.**

`influora-api/src/main/resources/db/migration/V29__woocommerce_integrations.sql:32` —
`UNIQUE KEY uq_woocommerce_site_url (site_url)`, on the column alone, **not** scoped by workspace and
**not** conditioned on `revoked`.

Trace what the real owner experiences. `WooCommerceIntegrationService.connect`
(`:84-102`) looks up `findByWorkspaceIdAndRevokedFalse(workspaceId)` (`:87`) — that is **their own**
workspace, which has no connection, so it returns empty and takes the **insert** branch (`:94-101`)
with `site_url` already taken by the squatter's row. MySQL raises a duplicate-key error on
`uq_woocommerce_site_url`. There is no `try`/`catch` around the save in that method, so it reaches
`common/GlobalExceptionHandler.java:110-115`, which maps every
`DataIntegrityViolationException` to a **409 `DATA_INTEGRITY_VIOLATION`** — "The request could not be
completed due to a data conflict". Better than a 500 (I checked, expecting one), but still opaque:
`StoreIntegrationSetup.tsx:114` renders that generic sentence, which tells the brand nothing about
what is actually wrong.

So: the legitimate owner cannot connect, cannot see why, and there is **no self-service remedy** —
nothing in the API lets them claim the domain, and nothing tells support which workspace holds it
without a manual DB query. Combined with Q17 (no ownership proof) this is a working
domain-squatting denial-of-service against any brand we have, costing the attacker one authenticated
API call.

### 19. If a brand disconnects and reconnects the same store, does it work?

**Verdict: NO. It fails with a 409 and no usable explanation. This is a live defect on the one
integration that is reachable today.**

Full trace of the unique constraint:

1. **Disconnect.** `web/StoreIntegrationStatusController.java:132-140` →
   `integration.revoke()` then `save`. `WooCommerceIntegrationService.revoke` (`:119-135`) does the
   same. `revoke()` sets `revoked = true` — `domain/entity/WooCommerceIntegration.java:98-100`. The
   row is **kept**: the javadoc at `WooCommerceIntegrationService.java:117` and the migration comment
   at `V29:26` both say soft-delete, audit-trail-preserving, never a hard delete.
2. **Reconnect.** `WooCommerceIntegrationService.connect:87` calls
   `findByWorkspaceIdAndRevokedFalse(workspaceId)`. The old row has `revoked = true`, so this returns
   **empty**.
3. `:94-101` therefore takes the **insert** branch and builds a brand-new row with the same
   `site_url`.
4. `V29:32`'s `UNIQUE KEY uq_woocommerce_site_url (site_url)` is not conditioned on `revoked`, and
   `site_url` is `NOT NULL` (`V29:25`) so MySQL's "NULLs are distinct" escape does not apply. →
   **duplicate-key violation → `GlobalExceptionHandler.java:110-115` → 409
   `DATA_INTEGRITY_VIOLATION`.**

The brand is told "Could not connect WooCommerce." (`StoreIntegrationSetup.tsx:114`, which renders
the generic server message) and is permanently unable to reconnect their own store through the UI.
Nothing in the response says *why*, and there is no self-service remedy.

The migration comment at `V29:32` says the constraint means "one active connection per site". **That
comment is wrong and the code wins:** the constraint means one connection per site *ever*, active or
revoked. The identical defect exists on Shopify at
`V27__shopify_integrations.sql:29` (`uq_shopify_shop_domain`), where it is currently masked only by
the fact that nobody can connect at all (Q1). I would fix both with the same change.

### 20. If a brand connects a DIFFERENT store URL to a workspace that already has one, what is stored and what is reported back?

**Verdict: the WRONG thing is stored and the response is a lie. This is the most serious live defect
I found in section B.**

1. `WooCommerceIntegrationService.connect:87` finds the workspace's existing active row (store A).
2. `:90-92` takes the rotate branch: `existing.get().rotateSecret(encrypted)`.
3. I opened `domain/entity/WooCommerceIntegration.java`. `rotateSecret` is at `:92-96` and sets
   **only** `encryptedWebhookSecret`, `revoked = false`, and `touch()`. **It never assigns
   `siteUrl`.** The `siteUrl` parameter passed into `connect` at `:84` is used on the insert branch
   (`:98`) and in the audit log (`:112`) — and on the rotate branch it is silently discarded.
4. **Stored:** store A's `site_url` carrying store B's webhook secret.
5. **Reported back:** `WooCommerceConnectController.java:79` returns
   `new WooCommerceConnectResponse(true, normalizedSiteUrl)` — the **new** URL, store B, which was
   never persisted. `StoreIntegrationSetup.tsx:110` renders "Connected to https://store-B.com."

The brand is told, in writing, that store B is connected. The database says store A. Both stores are
now dead:

- **Store A** still delivers to us and still resolves (`WooCommerceWebhookController.java:216`), but
  `:224-228` decrypts the rotated secret — store B's — and every signature check fails with 401. Woo
  retries and eventually gives up.
- **Store B** delivers with `X-WC-Webhook-Source: https://store-B.com`, which resolves to nothing
  (`:216-222`) → 404 `SITE_NOT_CONNECTED`. No attribution, ever.
- `GET /integrations/store/status` (`StoreIntegrationStatusController.java:97-101`) reports
  `siteUrl = store A`, contradicting the success message the brand just read.

Note this is also the exact Shopify defect from Q12(c) — `ShopifyTokenStorage.java:85-89` calling
`ShopifyIntegration.rotateToken` (`:104-107`), which likewise never sets `shopDomain`. Same bug,
written twice, in the two classes whose javadocs each say they "mirror" the other
(`WooCommerceIntegrationService.java:75-77`). The mirroring copied the bug.

### 21. Is the per-site webhook secret verified before or after the payload is parsed?

**Verdict: WORKS — verified strictly before parsing.**

Exact ordering in `WooCommerceWebhookController.receive`:

| line | action |
|---|---|
| `:199-202` | reject a missing `X-WC-Webhook-Source` (400) |
| `:213` | normalize the header (`WooCommerceSiteUrl.normalize`) |
| `:214-222` | resolve the integration by `site_url`, else 404 |
| `:224` | `integrationService.decryptSecret(integration)` |
| **`:225-228`** | **`signatureVerifier.verify(rawPayload, signature, secret)` — 401 on failure** |
| `:231-235` | topic routing |
| **`:237`** | **`WooCommerceOrderWebhookPayload.parse(rawPayload)` — the first time JSON is parsed** |

`rawPayload` arrives as a `String` (`:197`), not a bound object, so Spring never parses it either.
The verifier itself fails closed on a null/blank signature or secret and uses constant-time
comparison (`WooCommerceWebhookSignatureVerifier.java:54-63, :77-86`).

The ordering difference from Shopify (resolve-then-verify rather than verify-then-resolve) is forced
and correctly reasoned at `WooCommerceWebhookController.java:58-70`: WooCommerce has no app-level
secret, so we cannot know which key to check until we have resolved the site. A header read plus an
indexed lookup is not parsing. I accept that argument — with the consequence in Q22.

### 22. Can a caller distinguish "site not connected" from "bad signature"? What does that leak?

**Verdict: YES, trivially — and the javadoc claiming otherwise is wrong.**

- Unknown/unconnected site: **404**, code `SITE_NOT_CONNECTED`
  (`WooCommerceWebhookController.java:219-222`).
- Known site, bad signature: **401**, code `INVALID_WEBHOOK_SIGNATURE` (`:226-227`).

Different status *and* different error code, and per Q21 the site lookup runs **first**, so an
unauthenticated caller who possesses no secret at all can send garbage in
`X-WC-Webhook-Signature` and read the answer off the status line.

**`WooCommerceWebhookController.java:79-82` claims: "this also means a request cannot be used to
enumerate which sites ARE connected." That claim is false, and the code wins.** The javadoc even
contradicts itself three lines later (`:83-85`, "for a KNOWN site the signature check still runs and
can independently reject with 401") — which is exactly the oracle. The sentence at `:80-82` was
copied from `ShopifyWebhookController.java:44-47`, where it *is* true, because there the signature is
checked before the shop is resolved (`ShopifyWebhookController.java:138` before `:152`), so a caller
without the app secret gets 401 for every domain and learns nothing. Inverting the order inverted the
property; the comment did not follow.

**What it leaks:** a free, unauthenticated, unlimited-cardinality oracle answering "is this domain an
Influora customer?" for any domain you can name. That is our customer list — competitive intelligence
about who is running influencer campaigns, and a target list for the Q17/Q18 squatting attack
(register the domains that answer 404 today and you have pre-blocked our future customers). The only
brake is `AuthRateLimitFilter`'s per-IP `"tracking"` bucket, which
`WooCommerceWebhookController.java:103-112` already documents as IP-keyed and therefore trivially
distributed.

The fix is cheap and does not disturb Q21's ordering: return the same 401
`INVALID_WEBHOOK_SIGNATURE` for an unresolvable site as for a bad signature, and log the distinction
server-side.

---

## C. Webhooks, trust and attribution (23–34)

*Standing caveat for all of section C: per Q1–Q3, no Shopify store has ever connected, so every
Shopify answer below is at best WIRED. WooCommerce is genuinely reachable.*

### 23. For a Shopify delivery, what exactly does the HMAC prove — and what does it NOT prove?

**Verdict: WIRED. The proof is narrower than the class name suggests.**

`integration/shopify/webhook/ShopifyWebhookSignatureVerifier.java:45-58`: the header
`X-Shopify-Hmac-Sha256` is compared, in constant time (`:73-82`), against
`base64(HMAC-SHA256(rawPayload, secret))` (`:61-70`) where `secret` is
`props.getWebhookSigningSecret()` (`:51`) — **the single app-level client secret**, since no per-shop
secret is ever issued (`ShopifyWebhookController.java:132-137`, and `V27:23` marks
`webhook_secret` reserved-for-future).

**What it proves:** the request body is byte-identical to something produced by a party holding our
app's client secret — in practice, Shopify, on behalf of *some* store that has our app installed.

**What it does NOT prove, and this is the whole of Q24–Q26:**

1. **Which shop.** The shop name appears only in `X-Shopify-Shop-Domain`, a header the HMAC does not
   cover.
2. **Which workspace.** Follows from (1).
3. **That any header at all is genuine** — topic, timestamp, API version, all uncovered.
4. **Freshness.** There is no timestamp check and no nonce anywhere in the controller. A body
   captured once is replayable forever, subject only to the idempotency layers in Q34.
5. **That the order is real.** The body is data Shopify relayed; the HMAC attests transport, not
   truth. That is what the Q26 Admin API call is for.

There is also a live fail-closed condition worth stating: `:53-56` returns `false` when the secret is
blank. Per Q2, `influora.shopify.webhook-signing-secret` is bound nowhere, so **today this verifier
rejects 100% of deliveries**. Correct behaviour, and another reason nothing has ever been attributed
through this path.

### 24. Which header selects the workspace, and is it covered by the signature?

**Header: `X-Shopify-Shop-Domain`. Covered by the signature: NO.**

`ShopifyWebhookController.java:125` binds it; `:152-160` resolves it via
`ShopifyIntegrationRepository#findByShopDomainAndRevokedFalse` to a `ShopifyIntegration`, whose
`workspaceId` then scopes the idempotency reservation (`:201`), the redemption
(`:205`, `:276`) and every downstream write.

It is not covered because the HMAC is computed over `rawPayload` only —
`ShopifyWebhookSignatureVerifier.java:57` passes `rawPayload` and nothing else into
`computeHmacBase64`. No header is in the signed material.

**A doc in this repository asserted the opposite, and the code wins.**
`ShopifyOrderOwnershipVerifier.java:29-32` and `ShopifyWebhookController.java:53-55` both quote
`wiki/errors/wave-d1-shopify-integration-redteam.md:95` describing that header as "a real,
HMAC-authenticated workspace identity". It is not, and the original Wave D1 cross-tenant fix was
built on that sentence — which is why that fix, on its own, only moved the attacker's choice from
"which coupon" to "which workspace".

### 25. Describe the attack concretely. Does the 2026-09-08 fix close it or merely narrow it?

**The attack (pre-fix):**

1. Attacker installs our public Shopify app on a store **they own**. Legitimate install, no bypass.
2. In their own store they add a second webhook subscription for `orders/paid` pointing at a
   collector they control. Shopify signs deliveries for that subscription with the **same app-level
   client secret** (Q23), so the attacker now holds `(body, valid HMAC)` for an order whose contents
   they authored — including `discount_codes[0].code` set to any string they like.
3. They place a €1 order in their own store using the victim's coupon code, capture the
   signed body, and POST it to `https://<us>/api/v1/webhooks/shopify` with
   `X-Shopify-Shop-Domain` rewritten to the victim's shop.
4. Signature verifies (the body is untouched). `:152` resolves to the **victim's** workspace.
   `:276` redeems the **victim's** coupon: a fabricated redemption row, an inflated `usage_count`,
   and — per Q35's chain — an affiliate commission accrued against a campaign for an order that never
   touched the victim's store. Repeat to exhaust the coupon's `usage_limit` and the victim's real
   customers start getting `CODE_LIMIT_REACHED`.

**Does the fix close it?** Substantially yes, and I tried to break it.

`ShopifyWebhookController.java:188-194` now calls
`ShopifyOrderOwnershipVerifier#orderBelongsToShop(integration.getShopDomain(),
integration.getWorkspaceId(), order.orderId())` before any write. That issues
`GET https://{victim-shop}/admin/api/{ver}/orders/{id}.json?fields=id` with the **victim's own stored
token** (`ShopifyOrderOwnershipVerifier.java:107-137`). The attacker's order id does not exist in the
victim's store, Shopify answers 404, `:140-147` returns `false`, and the controller rejects 401
`SHOP_ORDER_MISMATCH`.

Note the two arguments passed at `:189` are taken from the **resolved database row**, not from the
request — so the attacker cannot steer the check at the shop it queries. That is the right choice and
I checked it specifically.

**Where it is narrowed rather than closed** — three residual paths:

1. **Order-id collision.** The check asks only "does an order with this id exist in the victim's
   store?" (`?fields=id`, `:129`). It never compares the *body* to the order. An attacker who learns
   or guesses a real order id in the victim's store can attach **their own** `discount_codes` and
   `total_price` to it and the check passes — the redemption is then fabricated against a real order
   with an attacker-chosen coupon and an attacker-chosen amount. Shopify order ids are large and not
   sequential across stores, so this is not trivial, but "not trivial" is not "closed". A brand's own
   order ids are visible to that brand's own staff and to anyone who has ever bought from them.
2. **Same-shop replay with a different amount.** Nothing binds `total_price` either, and
   `discountApplied` is computed from it (`RedemptionWriter.java:253-260`). A signed body for a real
   order in the attacker's OWN store, replayed against their own workspace, is not a cross-tenant
   attack — but it is Q44's self-dealing, and this check does not touch it.
3. **The check is bypassed for every delivery with no discount code** (`:171-175` returns 200 first),
   which is correct for cost but means the control protects the write path only.

**Verdict: WIRED, and materially better than what it replaced. Never once executed against Shopify.**

### 26. What happens to that Admin API call during a Shopify outage — dropped sale, or retried?

**Verdict: retried. The asymmetry is correct and deliberate — but it creates a new failure mode I
would not ship without a mitigation.**

`ShopifyOrderOwnershipVerifier.java:139-156`:

- **404 only** → `return false` (`:147`) → the caller rejects 401 `SHOP_ORDER_MISMATCH`. The single
  rejecting branch.
- **Every other response status** — 401, 403, 429, all 5xx — → `ShopifyApiException` (`:151-153`).
- **Any transport error** (timeout, DNS, connection reset) → `ShopifyApiException` (`:154-156`).

`integration/shopify/exception/ShopifyApiException.java:7-15` extends `ApiException` with
`HttpStatus.BAD_GATEWAY`, so it surfaces as **502** — non-2xx — and Shopify retries. Nothing swallows
it: the call at `ShopifyWebhookController.java:188` sits **outside** the `try` block that begins at
`:198`, so it can never reach the F-0725 catch at `:219`; and `SHOPIFY_API_ERROR` is not in
`StoreWebhookRedemptionOutcome.java:70`'s `TERMINAL_CODES` even if it did. **A Shopify outage
therefore retries, it does not drop the sale.** That is the right call and the class documents the
reasoning at `:51-56`.

**The new failure mode.** The rejection is `SHOP_ORDER_MISMATCH` **401** (`:190-193`), thrown at
`:188`, also outside the `try`. So a *false* 404 — an order archived or deleted since delivery, an
id outside whatever window the granted `read_orders` scope covers, an ordinary Shopify
eventual-consistency lag between the `orders/create` webhook firing and the order being readable via
the Admin API — produces a permanent non-2xx on a **legitimate** delivery. Shopify retries it, gets
401 every time, and per `StoreWebhookRedemptionOutcome.java:22-23` removes the subscription after
roughly 48h. **The F-0725 fix exists to stop a store disconnecting itself; the F-0726 fix
reintroduces exactly that outcome on a narrower trigger.** I would want `SHOP_ORDER_MISMATCH` treated
as terminal-and-acknowledged (with a loud ERROR log and an alert), not as a retryable non-2xx —
retrying it can never turn a rejection into an acceptance anyway.

### 27. Does the Admin API check run on every delivery, or only ones about to write? Cost?

**Verdict: only on deliveries about to write — but "about to write" is broader than it sounds, and
that is the cost problem.**

Ordering in `ShopifyWebhookController.receive`, all four gates ahead of it:

| line | gate | effect |
|---|---|---|
| `:138` | HMAC | 401 |
| `:152` | shop resolution | 404 |
| `:163-167` | topic not `orders/paid`/`orders/create` | **200, no call** |
| `:171-175` | order carries no discount code | **200, no call** |
| **`:188`** | **Admin API ownership check** | one outbound `GET` |

The placement is deliberate and the comment at `:185-187` states the reason. So a store's ordinary
traffic — most orders carry no coupon — costs us nothing.

**Cost implication, and it is real.** The gate is "carries *any* discount code", not "carries *our*
coupon". On a store running its own promotions — a sale code, `FREESHIP`, a loyalty code — **a large
share of orders pay for one outbound Shopify Admin API call that we then throw away**, because the
code resolves to `INVALID_CODE` moments later (Q29). Concretely, per qualifying order we spend:
one Admin API request against that shop's REST rate-limit bucket, one full HTTPS round trip inside
the webhook request (`ShopifyWebhookController` is synchronous — the connection to Shopify is held
open while we call Shopify back), and the latency that adds to our own response.

That last point compounds badly: Shopify treats a slow response as a failed delivery
(`ShopifyWebhookController.java:63-65` says so). During a flash sale, the shop's own rate-limit
bucket is already under pressure from the merchant's other apps; a 429 to us is **not** the 404
branch, so it becomes a 502, which Shopify retries, which issues another Admin API call, which is
more pressure on the same bucket. **A retry storm that feeds itself, ending in subscription removal
after 48h.** Nothing in the code rate-limits, batches, caches or circuit-breaks these calls. If we
ship this, it needs at minimum: a short-TTL negative cache, a circuit breaker on repeated non-404
failures, and the Q26 change to stop retrying a definitive rejection.

A cheaper design exists and was considered and rejected at
`ShopifyOrderOwnershipVerifier.java:43-49` — binding a shop identifier inside the signed body. The
reasoning given (we pin only `id` and `total_price` of Shopify's shape, so any other field is an
unverified assumption, and a control that no-ops on a missing field fails open) is sound *given that
we have never seen a real payload*. Once we have, `order.order_status_url` or the `shop_id` in
Shopify's own delivery would make this a free string comparison instead of a network call. I would
revisit it on day one of a real connection.

### 28. Has any part of that check ever run against a real Shopify store?

**Verdict: NO. ABSENT — never once.**

Proof from the code rather than from a report: `ShopifyOrderOwnershipVerifier.java:107-115` requires
a stored access token via `ShopifyTokenStorage#getValidToken`; tokens only ever come from
`ShopifyConnectController.java:134`, which is unreachable per Q1/Q5. `git log --diff-filter=A` puts
the class's creation at `f3a30c5` (2026-09-07), with its call site wired only in `3358608`
(2026-09-08). The only exercise it has ever had is
`src/test/java/com/influora/integration/shopify/ShopifyOrderOwnershipVerifierTest.java` against a
mocked `RestClient`.

**What is assumed, every one of which is a coin-flip until a real store proves it:**

1. **That "not in this shop" is a 404**, and that 401/403 is only ever a token problem. If Shopify
   answers a cross-store order id with 403 or 200-with-empty rather than 404, this control either
   fails open (200) or turns **every** delivery into a 502 retry loop (403) — the Q26 self-deleting
   subscription, on 100% of traffic instead of an edge case.
2. **That `read_orders` is sufficient for a single-order `GET`.** `ShopifyProperties.java:31`
   requests `read_orders,read_products`. Shopify distinguishes `read_orders` from `read_all_orders`
   and applies an age restriction to order reads for apps without the latter. If that restriction
   applies here, every order older than the window returns not-found → false rejection → Q26's
   48h disconnect. **This is the assumption I consider most likely to be wrong**, and it is
   invisible to every test we have.
3. **That `?fields=id` is honoured** (`:129`) and does not change the status semantics.
4. **That API version `2025-01` is still served.** `ShopifyProperties.java:30` hardcodes the default
   and nothing binds an override (Q2). Shopify retires versions on a schedule; nothing in this repo
   watches for that, and a retired version would fail as a non-404 → 502 → retry loop.
5. **That the order is readable at the instant `orders/create` fires.** Read-after-write lag on
   Shopify's side is a 404 to us, i.e. a rejection of a real sale.

**Where it would break first:** assumption 2 or 5, on the very first real order, and the symptom
would be indistinguishable from the attack it defends against — a 401 in our logs and a merchant
whose tracking silently stops 48 hours later.

### 29. An order arrives carrying the merchant's OWN code (`FREESHIP`). What status, and what next?

**Verdict: WORKS as designed (in code) — the platform gets 200 and does nothing further.**

Step by step, all in `ShopifyWebhookController.receive`:

1. `:138` HMAC verifies.
2. `:152` shop resolves.
3. `:163` topic is `orders/paid` — acted on.
4. `:169` body parses; `order.discountCode()` is `"FREESHIP"`, non-blank, so `:171` does **not**
   short-circuit.
5. `:188` the ownership check runs and **passes** — it is a genuine order in that shop. (This is the
   wasted API call from Q27.)
6. `:199` `executeOnce` reserves, and the action calls
   `RedemptionService#redeem(workspaceId, "FREESHIP", …)` (`:276`).
7. `RedemptionService.java:196` → `RedemptionWriter#doRedeem` → `validateCode`
   (`RedemptionWriter.java:232-234`) → `resolveScoped` (`:199-203`) →
   `couponCodeRepository.findByWorkspaceIdAndCode(workspaceId, "FREESHIP")` → empty →
   **`ApiException("INVALID_CODE", 404)`**.
8. That propagates out through `executeOnce`, which marks the reservation FAILED and rethrows
   (`IdempotencyService.java:161-164`).
9. `ShopifyWebhookController.java:219` catches it. `StoreWebhookRedemptionOutcome.isTerminal`
   (`:78-80`) returns true because `INVALID_CODE` is in `TERMINAL_CODES`
   (`StoreWebhookRedemptionOutcome.java:69-70`). Not a data defect (`:87-89`), so `:237-242` logs at
   INFO: "discount code is not a redeemable Influora coupon … acknowledged, nothing to attribute".
10. `:246` — **`200 OK`**.

**What the platform does next: nothing.** Shopify treats 2xx as delivered, does not retry, and does
not count it toward the failure budget that removes a subscription. Correct outcome. The identical
path exists for WooCommerce at `WooCommerceWebhookController.java:270-296`.

### 30. What did that same case do before 2026-09-08, and how long until the integration deleted itself?

**Verdict: 404 → retried → subscription removed after roughly 48 hours.**

I verified the date rather than trusting the ticket: `git log -S "StoreWebhookRedemptionOutcome"` on
both controllers returns exactly one commit, `3358608` (**2026-09-08**), "fix(webhooks): stop store
deliveries retrying forever…". Before it, neither controller had a `catch (ApiException)` — the
class javadoc at `StoreWebhookRedemptionOutcome.java:13-15` says the `try` caught only
`AlreadyCompleted`/`AlreadyInProgress`, and I confirmed that is what the commit changed. So step 9
above did not exist: the `INVALID_CODE` escaped to `GlobalExceptionHandler` and became a **404**
response.

The consequences, each verified in code rather than inferred:

- **Both platforms retry any non-2xx.** `StoreWebhookRedemptionOutcome.java:22-23`; Shopify removes a
  subscription after ~48h of continuous failures.
- **Nothing dampened the loop.** `IdempotencyService.java:141-153` — a reservation left FAILED is
  atomically reclaimed to IN_PROGRESS by `reclaimFailedForRetry` on the next attempt, so every retry
  genuinely re-ran the redemption and re-failed identically. This is the detail that turns a bad
  response into a self-destructing integration, and I checked that code path myself: `:146`, then
  `:156-164` re-executing the action.
- **It was the ordinary case, not an edge case.** Any store running its own promotions produces
  these constantly.

**So: roughly 48 hours from the first order carrying a non-Influora discount code to a Shopify store
silently disconnecting itself.** For WooCommerce there is no fixed budget — it retries per its own
schedule — so the symptom there is an endless failure log rather than a clean disconnect.

The saving grace, and it is only luck: per Q1/Q9 no Shopify store has ever connected, and per Q16 the
WooCommerce Delivery URL was pointed at a host we do not own, so **no delivery has ever reached
either controller in production.** This defect was fully live and cost us nothing, because a
different defect upstream prevented traffic entirely.

### 31. Is a genuinely transient failure still surfaced as non-2xx? Prove it is not swallowed.

**Verdict: WORKS for the cases it enumerates — proven below. But it is an allowlist, and I found
terminal-but-retried cases it misses.**

**Proof it is not a blanket catch.** `ShopifyWebhookController.java:219` catches `ApiException` only,
then `:227-229`:

```
if (!StoreWebhookRedemptionOutcome.isTerminal(redemptionFailure)) {
    throw redemptionFailure;
}
```

`isTerminal` (`StoreWebhookRedemptionOutcome.java:78-80`) is a **membership test against a
four-element allowlist** (`:69-70`: `INVALID_CODE`, `CODE_EXPIRED`, `CODE_LIMIT_REACHED`,
`UNSUPPORTED_DISCOUNT_TYPE`). Anything else is rethrown. That is the opposite of a blanket catch, and
it is why I accept the fix's shape.

Two concrete transient cases traced end to end:

- **Database down.** `RedemptionWriter#doRedeem`'s `save` (`:109`) throws a Spring
  `DataAccessException`. That is **not** an `ApiException`, so it matches neither `:210` nor `:219`;
  it escapes `receive` entirely → `GlobalExceptionHandler` → 5xx → **non-2xx, retried.**
- **Concurrency race.** `RedemptionService.java:203-206` throws
  `ApiException("IDEMPOTENCY_KEY_IN_PROGRESS", CONFLICT)`. It is an `ApiException`, so `:219` catches
  it — but `IDEMPOTENCY_KEY_IN_PROGRESS` is **not** in `TERMINAL_CODES`, so `:228` rethrows → 409 →
  **non-2xx, retried.** The class calls this out explicitly at
  `StoreWebhookRedemptionOutcome.java:39-41`, and the code matches the comment.

**The gaps, which are the reverse error — terminal outcomes still driving the retry loop the fix was
built to stop:**

1. **A malformed body.** `ShopifyOrderWebhookPayload.parse` throws
   `ApiException("INVALID_WEBHOOK_PAYLOAD", 400)` for unparseable JSON, a missing `id` or a missing/
   non-numeric `total_price` (`:57, :61, :68, :73, :82, :88`). It is thrown at
   `ShopifyWebhookController.java:169` — **before** the `try` opens at `:198` — so it can never be
   classified. A body that fails to parse will fail identically forever: **terminal, retried,
   48h disconnect.**
2. **`SHOP_ORDER_MISMATCH`** (`:190-193`), also outside the `try`. See Q26 — a false 404 disconnects
   an honest merchant.
3. **`ORDER_AMOUNT_INVALID`** (`RedemptionService.java:132-133`) is inside the `try` but absent from
   `TERMINAL_CODES`, so a negative or null order total is rethrown and retried forever.

All three are pure functions of the request body — exactly the definition
`StoreWebhookRedemptionOutcome.java:33-38` uses for TERMINAL. The classifier is right; its
placement (`:169`, `:188` outside the guarded block) and its list are both incomplete.

### 32. Two brands both create the coupon code `SUMMER20`. Whose sale is attributed, and to whom?

**Verdict: WORKS — correctly attributed as of 2026-09-08 (commit `9707725`). It was broken in both
directions before.**

Coupon codes are unique **per workspace**, not globally:
`db/migration/influora-api/src/main/resources/db/migration/V24__coupon_codes.sql:41` — `UNIQUE KEY uq_coupon_workspace_code (workspace_id, code)`. Two brands
holding `SUMMER20` is entirely legal.

**Today.** Both store webhooks resolve the workspace from the signed delivery *before* redeeming, so
they call the 6-arg overload (`ShopifyWebhookController.java:276`,
`WooCommerceWebhookController.java:322`) → `RedemptionWriter#validateCode:234` → `resolveScoped`
(`:199-203`) → `couponCodeRepository.findByWorkspaceIdAndCode(workspaceId, "SUMMER20")`. The unique
constraint guarantees at most one row. **Brand A's order redeems Brand A's `SUMMER20`; Brand B's
redeems B's.** No ambiguity exists.

**The third caller.** `ConversionWebhookController` — a brand's own custom-checkout webhook — has no
workspace identity at all, so it takes `resolveUnscoped` (`RedemptionWriter.java:217-230`). With two
matches it now throws `AMBIGUOUS_COUPON_CODE` (409, `:222-228`) rather than guessing. That is the
honest answer: nobody is attributed, and the operator can see which code collided. Note the cost —
**a brand using the custom-checkout path with a colliding code gets no attribution at all**, and the
error text tells them to use a store integration instead. That is a real product limitation, not just
a technicality.

**Before `9707725`.** The failure was worse than "attributed to the wrong brand", and I confirmed it
from the finder signatures rather than the commit message. The old finder was
`Optional<CouponCode> findByCode`, and per `CouponCodeRepository.java:69-75`:

- Spring Data raises `IncorrectResultSizeDataAccessException` when an `Optional` query matches more
  than one row, and `GlobalExceptionHandler` has no handler for it → **a bare 500 on every delivery
  carrying that code**, for both brands.
- Where a single row did come back first, the post-hoc ownership check rejected it as
  `INVALID_CODE` — so **Brand B creating `SUMMER20` made Brand A's own `SUMMER20` permanently
  unredeemable** (`RedemptionWriter.java:188-193`).

The javadoc on the old finder claimed it "returns whichever row matches first"
(`CouponCodeRepository.java:70-71`). **That claim was false** — the doc lied and the code threw.

### 33. Is the coupon lookup workspace-scoped at the QUERY, or filtered after a global fetch?

**Verdict: at the QUERY. WORKS.**

`RedemptionWriter.java:199-203`:

```
private CouponCode resolveScoped(String workspaceId, String normalizedCode) {
    return couponCodeRepository
            .findByWorkspaceIdAndCode(workspaceId, normalizedCode)
            .orElseThrow(() -> new ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND));
}
```

Spring Data derives `WHERE workspace_id = ? AND code = ?` from that method name
(`CouponCodeRepository.java:59`). There is no post-fetch filter left on this path — `findAllByCode`
(`:81`) survives for exactly one caller, `resolveUnscoped` (`RedemptionWriter.java:217-230`).

**Why the difference matters in production — three distinct reasons, only one of which is security:**

1. **Correctness, and this is the one that bit us.** A global fetch returns rows in whatever order
   the database chooses. Rejecting a foreign row *after* the fact means the rejection depends on
   which row came back first, so a second workspace registering the same string could **shadow the
   first brand's own valid coupon into `INVALID_CODE` permanently** (Q32). Scoping the query makes
   `UNIQUE(workspace_id, code)` guarantee at most one row, so the shadowing is structurally
   impossible rather than defended against.
2. **Availability.** The global `Optional` finder threw `IncorrectResultSizeDataAccessException` on
   two matches — an unhandled 500 for *both* brands (Q32).
3. **Security posture.** A post-hoc check is a control you can forget to apply; a scoped query is a
   control you cannot bypass by calling the wrong overload. The Wave D1 cross-tenant fix *was* the
   post-hoc check, and it is what caused defect (1) — a control that created a new outage while
   closing a leak.
4. **Cost and blast radius at scale.** `findAllByCode` on a hot code fetches every workspace's row
   into memory on every delivery. A scoped query hits the unique index and returns one row.

The no-enumeration property is preserved: a code that exists only in another workspace returns the
same `INVALID_CODE` 404 as "no such code" (`:195-197`, `:202`).

### 34. Is a delivery replayed twice counted twice? Name the two independent dedup layers and their keys.

**Verdict: NO for a successful redemption — WORKS, and there are actually three layers, two of them
independent by design.**

**Layer 1 — the controller's whole-operation reservation.**
`ShopifyWebhookController.java:199-209` wraps shop resolution, parsing and the redemption call in
`IdempotencyService#executeOnce`.
Key: `deriveIdempotencyKey` (`:298-307`) = `"shopify:" + hex(SHA-256(shopDomain + "|" + orderId))`,
which `IdempotencyService.compositeKey` (`:207-209`) further namespaces to
`"shopify.webhook" + ":" + workspaceId + ":" + <that key>`, persisted in `idempotency_keys` (V15) with
a `UNIQUE` primary key. Woo's twin is `WooCommerceWebhookController.java:341-350`, `"woocommerce:" +
hex(SHA-256(siteUrl + "|" + orderId))`, scope `woocommerce.webhook`.

**Layer 2 — the redemption row's own key.**
`RedemptionService.java:177` checks `CouponRedemptionRepository#findByIdempotencyKey` **before** any
validation or mutation and returns the existing row if present; `:192-196` then reserves the *same*
raw key under a different scope (`redemption.redeem`, `workspaceId = null` at `:194`, so the composite
cannot collide with layer 1's). Backstopped in the schema:
`db/migration/influora-api/src/main/resources/db/migration/V24__coupon_codes.sql:62` — `idempotency_key VARCHAR(100) NOT NULL UNIQUE` on `coupon_redemptions`.

The layers are independent: layer 1 guards the *delivery-handling operation*, layer 2 guards the
*redemption row*, and even if layer 1 were removed the DB `UNIQUE` would still refuse a second row.
Both are fed the same derived key deliberately (`ShopifyWebhookController.java:196, :209`), so a
replay is a clean no-op at whichever layer the retry races against — caught at `:210-218` and
acknowledged 200.

**Both keys are derived from `(store, orderId)` and deliberately exclude the topic.** That was F-0619
(Shopify, `:288-296`) and F-0521 (WooCommerce, `:326-339`): Shopify sends both `orders/paid` and
`orders/create` for one order, and with the topic in the key each reserved independently and **the
same discount code was redeemed twice**. The dedup identity has to be the commercial event.

**Three caveats I would not want you to discover later:**

1. **A FAILED attempt is not deduped — by design.** `IdempotencyService.java:146` reclaims a FAILED
   reservation back to IN_PROGRESS so the next delivery re-runs the action. Correct for transient
   faults, but it means the Q29 `FREESHIP` path re-executes the full lookup (and, per Q27, another
   Admin API call) on every redelivery. Only the *outcome* is idempotent there, not the work.
2. **The key uses the stored `shopDomain`/`siteUrl`, not the header** (`:196`, `:245`) — right, but
   it inherits Q12(c)/Q20's rotate-in-place bug: a workspace whose stored domain is stale keys its
   dedup on the wrong store.
3. **An edited order is deduped away.** Same order id with a different discount code (a merchant
   editing an order post-checkout) produces the same key, so the second code is never attributed and
   nothing reports it. That is the accepted cost of keying on the commercial event; it should be
   documented for support, because "I changed the coupon on the order and nothing happened" is a
   ticket we will get.

---

## The three things I would fix first (scoped to Q1–Q34)

**1. `rotateToken`/`rotateSecret` never update the store identifier — fix both, today.**
`domain/entity/WooCommerceIntegration.java:92-96` and
`domain/entity/ShopifyIntegration.java:104-107`. This is live and reachable on WooCommerce right now
(Q20): a brand connecting a second store is told in writing that store B is connected while store A's
row is what we keep, both stores stop attributing, and `GET /integrations/store/status` contradicts
the success message they just read. One-line entity fix plus a test that asserts the persisted
`siteUrl`/`shopDomain` equals the one the response reports. Same bug written twice, in the two
classes whose javadocs each claim to mirror the other.

**2. Make the unique constraints revoked-aware so a store can be reconnected.**
`V29__woocommerce_integrations.sql:32` and `V27__shopify_integrations.sql:29`. Today disconnect keeps
the row and reconnect takes the insert branch, so **a brand can never reconnect their own store** —
409 `DATA_INTEGRITY_VIOLATION` with no explanation (Q19, and Q12(d) for Shopify). The same constraint
also hands any authenticated user a permanent domain-squat against a competitor (Q17/Q18), with the
half-written defence already sitting unused at
`WooCommerceIntegrationRepository.java:25` and `ShopifyIntegrationRepository.java:23`. Fix the
constraint, wire those `exists…` finders into the connect path, and return a specific error code.

**3. Stop the F-0726 ownership check from disconnecting honest merchants, before a real store ever
connects.** `ShopifyWebhookController.java:188-194` and
`ShopifyOrderOwnershipVerifier.java:139-156`. `SHOP_ORDER_MISMATCH` is a 401 thrown outside the
guarded block, so a false 404 — read-after-write lag, an archived order, or `read_orders` not
covering older orders — becomes a permanent non-2xx and Shopify deletes the subscription after ~48h.
That is precisely the self-deleting-integration outcome F-0725 was written to end, reintroduced on a
narrower trigger (Q26, Q28, Q31). Treat a definitive rejection as terminal-and-alerted rather than
retryable, move the parse and the ownership check inside the classified block, and add
`INVALID_WEBHOOK_PAYLOAD` / `ORDER_AMOUNT_INVALID` to `TERMINAL_CODES`.

**Two more I would not ship without, listed because they are cheap:** close the WooCommerce
customer-enumeration oracle (Q22 — return 401 for an unresolvable site, one line at
`WooCommerceWebhookController.java:219-222`, and correct the javadoc at `:79-82` that claims the leak
does not exist); and reconcile the two API origins baked into the two build paths (Q16 — one of them
points at a host that fails TLS, and it decides the Delivery URL we print for brands).

**And one thing that is not a bug but is the headline:** Shopify is advertised to brands
(`src/content/festival-box.ts:225`) and cannot be connected in any environment, because
`influora.shopify.api-key`/`api-secret` are bound nowhere (Q2). Everything built for Shopify —
OAuth, webhook registration, HMAC verification, the ownership check, both idempotency layers — is
**WIRED and has never met Shopify**. Before any of it is called done, we need a real Partner app, the
credentials in `generate-env.sh` and the compose files, the redirect-uri agreeing with `src/App.tsx`
on the deploy box, and one real order end-to-end. Until then no amount of green tests changes the
answer to your question: **a brand who clicks "Connect Shopify" today gets a 503, and no creator has
ever been paid a commission from a Shopify sale because no Shopify sale has ever reached us.**
