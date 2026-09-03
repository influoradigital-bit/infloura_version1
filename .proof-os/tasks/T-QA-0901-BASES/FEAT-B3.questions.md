# FEAT-B3 Questions — Batch Testing for 10 Features

Generated: 2026-09-03
Tester: Kavya (QA Lead)

---

## 1. Meera AI

### 1. WORKS?
Does Meera work end-to-end when a brand chats "find creators for my skincare launch" — from the `POST /meera/turn` that charges 1 credit, through the Python SSE stream that calls `show_creators`, through the `POST /internal/meera/show_creators` that validates the on-behalf JWT, to the rows written to `meera_tool_calls` and the creator results streamed back to the browser?

### 2. HOW?
When the AI calls `create_campaign` followed by `request_payment` followed by `confirm_launch`, what is the actual sequence of database writes — which service writes `campaign_intents`, what state does the campaign row reach after each tool, and where does the budget null→funded transition actually happen?

### 3. WHY NOT AS ADVERTISED?
The feature doc says "MeeraSessionService persists a placeholder assistant echo (real text from Python)" and "JWKS/AI config not in committed yml (must inject; eager beans throw on blank keys)". If the JWKS keys or AI config are blank at boot time, what happens when the first brand tries to start a Meera session — does the controller 500, does the ES256 token mint fail, or does something else break?

### 4. WHEN DOES IT BREAK?
When a brand spams 502 turns in under a minute (daily cap is 500), does the 429 throttle fire correctly, and if a turn is refused at credit-check time but the browser had already opened the SSE stream with a valid 60s token, what does the Python service return when it tries to call back into `/internal/meera/*` on a conversation that was never persisted?

### 5. WHAT IS MISSING?
The doc says the LLM lives in Python and that "JWKS/AI config not in committed yml (must inject)" — where is the injected JWKS public key actually stored in production (environment variable, mounted secret, hardcoded), and how does the `SpringJwksKeyService` fail when that key is absent or malformed?

---

## 2. TrendSpark

### 6. WORKS?
Does TrendSpark work end-to-end when a brand dashboard loads at 06:05 IST the day after the n8n pipeline wrote a Holi trend with `campaign_type=OWN_CONTENT` and `theme_tags` overlapping the brand's profile — from `GET /brand/trendspark/nudge`, through `ThemeMatchService.score`, through the AI phrasing call, to a nudge card rendered in the UI with the templated-or-AI-phrased copy?

### 7. HOW?
When `TrendSparkNudgeService` decides between OWN_CONTENT and SNAPSBY, what is the actual logic in `ContentGapService.decide` — does it call `BrandOwnContentService` to check `last_posted_at`, does it compare that timestamp to the trend's recency, and what threshold makes it choose catalog videos over "post your own content"?

### 8. WHY NOT AS ADVERTISED?
The doc says "not yet production-authorized (pending live gate per project notes)" and that theme matching is "keyword-overlap, not NLP". If a trend is tagged `["Holi", "Festival"]` and a brand profile has `theme_tags=["holi celebration", "spring festivals"]`, does the current `ThemeMatchService.score` count that as 2 overlaps, 0 overlaps, or something else because the strings don't match exactly?

### 9. WHEN DOES IT BREAK?
When the n8n pipeline writes 17 active trends on the same day and a brand profile has `theme_tags` overlapping 4 of them, which nudge does `GET /brand/trendspark/nudge` return — the highest-scoring one, the most recent one, or does it silently pick one at random because there's no `ORDER BY` clause in `TrendRepository.findActive`?

### 10. WHAT IS MISSING?
The doc says the nudge depends on "Meta insights (own-content gap signal)" — where does `BrandOwnContentService` actually pull the brand's `last_posted_at` timestamp from: the `brand_profiles` table (V11 has that column), a Meta API call via `InstagramMetricsFetcher`, or is `last_posted_at` always null because the Meta polling job is "built but not invoked"?

---

## 3. Meta / Instagram Integration

### 11. WORKS?
Does Meta integration work end-to-end when a creator clicks "Connect Instagram", grants consent on Facebook, lands on `/creator/settings/meta/callback?code=X&state=Y` — from `MetaOAuthStateStore.consume` validating the 10-minute single-use state, through the code→short-lived→long-lived token exchange, to the AES-GCM encrypted token written to `meta_oauth_tokens` with scopes and `expires_at`?

### 12. HOW?
When `MetaTokenRefreshService` runs daily at 02:30, what is the actual refresh mechanism — does it iterate every non-revoked token, call `exchangeForLongLivedToken` again to get a new 60-day token, update `expires_at`, or does it do something else because the doc says "no refresh token stored (self-refresh by re-exchange)"?

### 13. WHY NOT AS ADVERTISED?
The doc says "No real Meta app exists yet — placeholders only" and "config must be injected; token bean throws on blank key". If `MetaApiProperties` has a blank `appId` or `appSecret` at boot, does the bean throw and crash the app, or does the OAuth flow silently 502 when a creator tries to connect because the Facebook redirect fails?

### 14. WHEN DOES IT BREAK?
When a creator's Instagram account is switched from Business to Personal (revoking the `instagram_basic` scope), and the next day `InstagramMetricsFetcher` tries to pull metrics using that token, what happens — does `MetaGraphApiClient` get a 403 and soft-revoke the token, does it retry forever, or does the per-token try/catch isolation log the error and skip that creator?

### 15. WHAT IS MISSING?
The doc says "`InstagramMetricsFetcher` built but not invoked by the job" and "per-post `media_metrics` polling not wired". Where is the scheduled job that is supposed to call `InstagramMetricsFetcher` — does it exist but is commented out, is there a `@Scheduled` method that never calls the fetcher, or is the job entirely absent from the codebase?

---

## 4. Shopify Integration

### 16. WORKS?
Does Shopify integration work end-to-end when a shopper places an order on `mystore.myshopify.com` using discount code `CREATOR10`, Shopify fires an `orders/paid` webhook to `/webhooks/shopify` — from `ShopifyWebhookSignatureVerifier` validating the `X-Shopify-Hmac-Sha256` header, through resolving the shop by `X-Shopify-Shop-Domain`, to `RedemptionService.redeem` writing a `coupon_redemptions` row and incrementing the creator's affiliate earnings?

### 17. HOW?
When `ShopifyConnectController` processes the OAuth callback, what is the actual shop domain validation — does `ShopifyOAuthService` apply the regex `^[a-z0-9][a-z0-9-]*\.myshopify\.com$` before or after the token exchange, and if the state contains a shop domain that doesn't match that pattern, does the callback 400 before calling Shopify or does it fail later?

### 18. WHY NOT AS ADVERTISED?
The doc says "requires a provisioned Shopify app + secrets" and "per-shop `webhook_secret` column reserved/unused". If the app-level webhook secret in `application.yml` is blank or still a placeholder, what happens when a webhook arrives — does `ShopifyWebhookSignatureVerifier` fail-closed with a 401, does it skip verification and accept any payload, or does it throw at bean-construction time?

### 19. WHEN DOES IT BREAK?
When a brand connects `store-a.myshopify.com`, disconnects it, then reconnects the same store 10 minutes later, does `ShopifyOAuthService` reuse the existing `shopify_integrations` row (update the token), create a duplicate row (violating the `shop_domain` unique constraint), or soft-delete the old one and insert a new one?

### 20. WHAT IS MISSING?
The doc says idempotency is `sha256(shopDomain|topic|orderId)` — where is that idempotency key actually enforced: in `IdempotencyService` before `RedemptionService.redeem`, inside `RedemptionService` itself, or is there a unique constraint on `coupon_redemptions` that would cause a duplicate webhook to silently no-op?

---

## 5. WooCommerce Integration

### 21. WORKS?
Does WooCommerce integration work end-to-end when a brand submits `POST /woocommerce/connect {siteUrl: "https://example.com", webhookSecret: "wc_secret_xyz"}` — from `WooCommerceSiteUrl.normalize` stripping the trailing slash and protocol variations, through encrypting the per-integration secret, to a `woocommerce_integrations` row written with `site_url` and `webhook_secret_encrypted`, and then a webhook from that site verifying correctly?

### 22. HOW?
When a WooCommerce webhook arrives at `POST /webhooks/woocommerce` with `X-WC-Webhook-Source: https://example.com/` and `X-WC-Webhook-Signature: base64HmacHere`, what is the actual verification sequence — does the controller resolve the site first (decrypt secret from DB), then verify the HMAC, or does it verify using some other mechanism?

### 23. WHY NOT AS ADVERTISED?
The doc says the controller does "resolve site → decrypt secret → verify" (different from Shopify's app-level secret). If two workspaces both connect `example.com` (same site URL, different webhook secrets), does `findBySiteUrlAndRevokedFalse` return both rows, one row, or does the `site_url` unique constraint (if it exists) prevent the second connection entirely?

### 24. WHEN DOES IT BREAK?
When a WooCommerce webhook fires for `order.created` but the order object has no `coupon_lines` field (no coupon was used), does the controller parse the empty array, call `RedemptionService.redeem` with a null/empty code (which fails), or does it silently skip the redemption and return 200?

### 25. WHAT IS MISSING?
The doc says idempotency is `sha256(siteUrl|topic|orderId)` just like Shopify — if the same order ID appears in both an `order.created` and an `order.updated` webhook (WooCommerce fires both), does the idempotency key treat them as duplicates (same redemption) or distinct events (topic differs)?

---

## 6. Conversion Tracking (Generic + UTM)

### 26. WORKS?
Does conversion tracking work end-to-end when a brand generates a UTM link for campaign `C1` + creator `CR5`, a shopper clicks `GET /track/click/{utmId}`, the click count increments, the shopper lands on the brand's store and buys, then an external system posts `POST /webhooks/conversion {utmCampaignId, revenue, idempotencyKey}` with the workspace's `X-Influora-Signature` HMAC — from signature verification, through `ConversionTrackingService.incrementConversionCount` and `addRevenue`, to the `utm_campaigns` row updated?

### 27. HOW?
When `ConversionWebhookSecretController` generates a new secret via `POST /webhook-secret/generate`, what is the actual secret generation — does it use `SecureRandom`, how many bytes, is it base64-encoded, and where is the plaintext secret revealed to the brand (response body, one-time only)?

### 28. WHY NOT AS ADVERTISED?
The doc says "store webhooks do not feed UTM (documented cut — the ULID never rides an order object)" — if a brand has both a Shopify integration AND a UTM campaign for the same creator, and a shopper clicks the UTM link then uses a discount code at checkout, does the conversion get double-counted (once via click, once via redemption), or is there no link between the two systems at all?

### 29. WHEN DOES IT BREAK?
When an external system posts a conversion webhook with `idempotencyKey=abc123` twice (network retry), does `ConversionTrackingService` skip the second one because the key is workspace-namespaced and cached, or does it double-count the revenue because idempotency is only enforced on redemptions not conversions?

### 30. WHAT IS MISSING?
The doc says "affiliate accrual from redemptions is delayed" — when `RedemptionService.redeem` writes a `coupon_redemptions` row, does it also write to `affiliate_earnings` immediately, does it stage a row that gets picked up by a batch job later, or does nothing happen until an admin manually triggers settlement?

---

## 7. Admin Dashboard

### 31. WORKS?
Does the admin dashboard work end-to-end when an admin with `AdminRole.ADMIN` and satisfied MFA logs in, navigates to `/admin/disputes`, views dispute `D123` between brand `B1` and creator `C2`, clicks "Resolve in favor of creator", and submits — from `POST /admin/disputes/{id}/resolve`, through the role+MFA check in `AdminDisputeService`, through `EscrowService` settling the held funds, to the `disputes` row updated with `status=RESOLVED` and `resolved_at` timestamp?

### 32. HOW?
When the admin console frontend calls `POST /admin/support/tickets/T456/reply {message: "We are looking into this"}`, what is the actual persistence — does `AdminSupportService` append a `support_ticket_messages` row with `role=ADMIN` and `content="We are looking into this"`, and does it update the ticket's status or last_activity timestamp, or just write the message and nothing else?

### 33. WHY NOT AS ADVERTISED?
The doc says "some admin frontend calls (`support escalate`/`getStats`) hit nonexistent endpoints → 404" — if an admin clicks the "Escalate" button on a support ticket, does the frontend call an endpoint that 404s (the call is made but fails), does the button do nothing (no call is made), or does it show a "not implemented" toast?

### 34. WHEN DOES IT BREAK?
When two admins simultaneously resolve the same dispute `D789` (one in favor of brand, one in favor of creator), does the second `POST /admin/disputes/{id}/resolve` get a 409 conflict because the dispute status already changed, does it silently overwrite the first resolution, or does the escrow settlement happen twice (double-payout)?

### 35. WHAT IS MISSING?
The doc says "billing console is mock (AdminBillingController partial)" — when an admin navigates to `/admin/billing` and views the list of subscription overrides, does `AdminBillingController` return real data from the `subscriptions` table, mock hardcoded rows, or does the endpoint not exist and the frontend renders a placeholder UI?

---

## 8. Brand Dashboard

### 36. WORKS?
Does the brand dashboard work end-to-end when a brand workspace member logs in with `brand_token`, lands on `/brand/dashboard`, the `DashboardController` queries for active campaigns + recent deliverables + wallet balance, and the `DashboardPage` renders KPI cards showing "3 active campaigns, 12 pending deliverables, ₹45,000 in wallet"?

### 37. HOW?
When a brand member clicks the command bar shortcut `⌘K` and types "wallet", what is the actual navigation — does `brand-layout.tsx` render a `CommandBar` component that fuzzy-matches routes and calls `router.push('/wallet')`, or is the command bar entirely mock and the keyboard shortcut does nothing?

### 38. WHY NOT AS ADVERTISED?
The doc says "session-expiry currently breaks (no auto-refresh)" — when a brand member's JWT expires (say, after 24 hours idle), and they click "Create Campaign", does the frontend call `POST /campaigns` with the expired token and get a 401, does it silently redirect to login, or does the request hang because the guard never intercepted it?

### 39. WHEN DOES IT BREAK?
When a brand workspace has `role=VIEWER` (read-only) and that member navigates to `/campaigns/new` to create a campaign, does the frontend's `ProtectedRoute` allow the page to load (because it only checks token presence, not role), then the `POST /campaigns` call fails server-side with 403, or does the route guard block navigation entirely?

### 40. WHAT IS MISSING?
The doc says "several sub-pages still mock-backed" including campaign detail, wallet, messages — when a brand clicks into `/campaigns/C123/details`, does the `CampaignDetailPage` call a real `GET /campaigns/C123` endpoint that returns 404 (endpoint missing), does it call an endpoint that always returns mock data, or does the page render entirely client-side with hardcoded values?

---

## 9. Creator Dashboard

### 41. WORKS?
Does the creator dashboard work end-to-end when a creator logs in with `creator_token`, lands on `/deals`, the `CreatorDeliverableController` returns deals in `NEGOTIATING`, `ACCEPTED`, `IN_PROGRESS` states, and the `creator-deals` page renders the unified deal hub with tabs for "Pending", "Active", "Completed"?

### 42. HOW?
When a creator navigates to `/portfolio` and the `PortfolioController` returns their public profile with `audience_demographics`, where does that demographics data actually come from — is it the JSON blob pulled from Instagram via `InstagramInsightsClient`, is it a denormalized column on `creator_profiles`, or is it always null because the Meta polling job never runs?

### 43. WHY NOT AS ADVERTISED?
The doc says "affiliate earnings placeholder" and "coupons 'not implemented' banner in places" — when a creator navigates to `/affiliate`, does the `CreatorAffiliateEarningController` return an empty array (no earnings exist), does it 501 Not Implemented, or does the frontend show a "Coming Soon" banner without calling the backend at all?

### 44. WHEN DOES IT BREAK?
When a creator's Meta OAuth token expires (`expires_at` in the past) and they view `/analytics`, does `CreatorAnalyticsController` call `InstagramInsightsClient` with the expired token (which 401s from Meta), does it soft-revoke the token and return empty metrics, or does it crash because there's no null-check on the token?

### 45. WHAT IS MISSING?
The doc says "mock surfaces (dashboard/chat/inbox/wallet)" — when a creator views `/wallet` to see their balance and withdrawal history, does `WalletController` return real rows from the `wallets` and `wallet_transactions` tables scoped to that creator, or does the page always render "₹0" because the controller filters by `userType=BRAND` only?

---

## 10. Support Tickets

### 46. WORKS?
Does support ticketing work end-to-end when an admin calls `GET /admin/support/tickets?status=OPEN&priority=HIGH`, the `AdminSupportService` queries `support_tickets` with those filters, returns a page of tickets, the admin clicks ticket `T789`, views the thread from `support_ticket_messages`, types a reply "Please share your account email", and `POST /admin/support/tickets/T789/reply` writes an ADMIN message without changing the ticket status?

### 47. HOW?
When an admin changes a ticket from `IN_PROGRESS` to `RESOLVED` via `PUT /admin/support/tickets/T123 {status: "RESOLVED"}`, what is the actual status transition enforcement — does `AdminSupportService` validate that only certain transitions are allowed (like disputes do), or does it accept any status value and just stamp `resolved_at` when the new status is RESOLVED?

### 48. WHY NOT AS ADVERTISED?
The doc says "no user-facing ticket-creation endpoint in this surface" — if a brand or creator has a problem and wants to file a support ticket, where do they do it: is there a separate `POST /support/tickets` endpoint not mentioned in this doc, does the frontend have a "Contact Support" form that calls nothing, or is ticket creation entirely manual (admins create tickets on behalf of users)?

### 49. WHEN DOES IT BREAK?
When an admin calls `POST /admin/support/tickets/T456/escalate` (which the doc says is not implemented), does the call 404 immediately, does it 501 Not Implemented, or does the endpoint exist but do nothing (returns 200 without changing the ticket)?

### 50. WHAT IS MISSING?
The doc says "`TicketDetailDto.relatedEntities` is an honest empty stub" — when a ticket is about a specific campaign or deal, where is the `campaignId` or `collaborationId` actually stored: is there a `related_entity_id` column on `support_tickets` that is always null, is it in a separate join table that doesn't exist, or does the admin have to manually read the ticket messages to figure out what entity it's about?
