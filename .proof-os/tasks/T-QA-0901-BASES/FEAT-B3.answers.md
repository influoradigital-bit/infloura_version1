# FEAT-B3 Answers — Meera AI, TrendSpark, Meta, Shopify, WooCommerce, Conversion Tracking, Admin/Brand/Creator Dashboards, Support Tickets

Answered from the code by Priya Sharma (CTO), 2026-09-03. The feature docs in docs/docs/features are the register of what exists, not evidence of behaviour; where a doc claim contradicts the source, the source wins and the contradiction is named in place.

---

## 1. Meera AI

### 1. WORKS?
Partly. Two premises in the question are wrong: there is no POST /meera/turn — the charging edge is the send-turn route on the brand controller — and show_creators writes nothing to meera_tool_calls. The chain that does exist is real end to end: the send route charges one credit through the send-gate before any token is minted, the browser streams from Python directly, and the tool callback re-validates the on-behalf JWT for the workspace and the tool scope before executing. But the ledger row the question expects is absent: the read-tier executor records an audit-log entry only, and the three writers of meera_tool_calls are create_campaign, request_payment and confirm_launch. So creator rows do reach the browser, and nothing lands in meera_tool_calls for that turn.
`influora-api/src/main/java/com/influora/web/MeeraController.java:117` "/sessions/{conversationId}/messages"
`influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java:220` "tryConsumeForTurn(workspaceId, TURN_CREDIT_COST, messageId)"
`influora-api/src/main/java/com/influora/web/MeeraInternalController.java:149` "/show_creators"
`influora-api/src/main/java/com/influora/service/meera/tool/ShowCreatorsExecutor.java:79` "auditLogService.recordToolCall"

### 2. HOW?
create_campaign writes three rows in one transaction: a campaign_intents row at status READY, a campaigns row at DRAFT with no budget field set at all, then the intent is confirmed with the campaign id and a meera_tool_calls row is appended. request_payment writes nothing to the campaign — it derives the amount server-side, appends a meera_tool_calls row carrying that server amount, and returns PENDING_CONFIRM plus a URL for the human to click. confirm_launch reads escrow_holds fresh, refuses unless one is FUNDED, then flips the campaign to ACTIVE, charges the publish fee, binds the holds to the collaborations it just invited and appends its own ledger row. There is no budget null-to-funded transition anywhere in this sequence: the campaign budget columns are never written by any of the three tools, and funded means an escrow_holds row created by the separate human-clicked funding endpoint.
`influora-api/src/main/java/com/influora/service/meera/tool/CreateCampaignExecutor.java:316` "campaignRepository.save(campaignBuilder.build())"
`influora-api/src/main/java/com/influora/service/meera/tool/RequestPaymentExecutor.java:148` "toolCallRepository.save"
`influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java:263` "if (fundedHolds.isEmpty())"
`influora-api/src/main/java/com/influora/service/meera/tool/ConfirmLaunchExecutor.java:338` "campaign.setStatus(CampaignStatus.ACTIVE)"

### 3. WHY NOT AS ADVERTISED?
The placeholder-echo half of the doc is simply false: the send path returns a TurnResult whose assistantMessageId and placeholderReply are both hard-coded null, and no assistant row is written until the Python write-back lands. The JWKS half is half right. Blank key material does not always throw — when both PEMs are blank and the env is dev, the service generates a throwaway in-memory ES256 keypair and logs a warning, so the first brand session succeeds and only breaks across a restart. Outside dev, or with one PEM set and the other blank, the strict parse path runs and the constructor throws IllegalStateException, which fails the whole application context at boot — so the controller never 500s on a request, the process never comes up at all.
`influora-api/src/main/java/com/influora/service/meera/MeeraSessionService.java:260` "return new TurnResult(userMessage.getId(), null, streamToken"
`influora-api/src/main/java/com/influora/security/SpringJwksKeyService.java:93` "if (privateBlank && publicBlank && isDev)"
`influora-api/src/main/java/com/influora/security/SpringJwksKeyService.java:106` "parsePkcs8EcPrivateKey(props.getPrivateKeyPem())"

### 4. WHEN DOES IT BREAK?
The 500/day cap is never the thing that fires. The send route sits in its own rate-limit bucket at 20 requests per user per 60-second window, so request 21 of the 502 gets a 429 with code RATE_LIMITED from the servlet filter, and the daily counter only ever reaches 20. The second half of the question rests on a false premise: the charge runs before the stream token is minted, so a turn refused at credit-check never produced a token for the browser to open a stream with. If Python nonetheless calls back with a conversation id that does not exist, the write-back route resolves the conversation first and throws CONVERSATION_NOT_FOUND as a 404 before the on-behalf JWT is even checked.
`influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:181` "influora.meera.turn-rate-limit-per-window"
`influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java:255` "response.setStatus(429)"
`influora-api/src/main/java/com/influora/service/meera/AICreditService.java:133` "credit.getDailyActionsUsed() >= DAILY_ACTION_HARD_CAP"
`influora-api/src/main/java/com/influora/web/MeeraInternalController.java:278` "sessionService.resolveConversation(body.conversationId())"

### 5. WHAT IS MISSING?
It is an environment variable, injected from a generated dotenv file into the container. The compose files pass INFLUORA_JWKS_PRIVATEKEYPEM and INFLUORA_JWKS_PUBLICKEYPEM straight through, and the generator script writes them as single-line PEMs with literal backslash-n separators; the yaml block that would have bound the differently-named variables is commented out, so binding happens purely by Spring relaxed-binding from the environment onto the properties prefix. Absent in a non-dev environment, the constructor throws a hard IllegalStateException naming the missing property, which aborts context refresh. Malformed is the same outcome by a different route: the PEM decode throws and is rewrapped as not a valid PKCS#8 EC private key PEM, again at construction, so there is no runtime degradation path — only a boot failure.
`deploy/utho/docker-compose.utho.yml:157` "INFLUORA_JWKS_PRIVATEKEYPEM"
`deploy/utho/generate-env.sh:58` "INFLUORA_JWKS_PRIVATEKEYPEM="
`influora-api/src/main/resources/application.yml:268` "# jwks:"
`influora-api/src/main/java/com/influora/security/SpringJwksKeyService.java:207` "is not a valid PKCS#8 EC (P-256) private key PEM"

---

## 2. TrendSpark

### 6. WORKS?
No, not as the question describes it. The wiring is complete — the route exists, the service scores, gap-checks, catalog-matches, calls the AI phraser with a templated fallback, writes a nudge_log row and returns a card the dashboard renders — but two things break the described path. First, campaign_type on the trend never selects the nudge mode: mode comes only from the gap check, and campaign_type is passed through untouched into the response and the log row. Second, theme overlap is exact string equality against a threshold of two, so a Holi trend and a loosely-worded brand profile score zero and the endpoint returns 204. When it does fire, the mode is SNAPSBY every time, for the reason given in answer 10.
`influora-api/src/main/java/com/influora/web/TrendSparkController.java:40` "/nudge"
`influora-api/src/main/java/com/influora/service/trendspark/TrendSparkNudgeService.java:102` "bestScore < props.getScoreThreshold()"
`influora-api/src/main/java/com/influora/service/trendspark/TrendSparkNudgeService.java:167` "bestTrend.getCampaignType().name()"
`src/lib/api.ts:5814` "http.requestOrNull<TrendSparkNudge>('GET', '/brand/trendspark/nudge')"

### 7. HOW?
It does call the own-content service, but not to read a timestamp — it asks Meta directly for the ten most recent media items and derives caption themes from them. The last-posted timestamp is used separately and is never compared to the trend's recency: it is compared to now minus a configured gap window whose default is four days. Trend recency enters only through one narrow clause, peak window of three days or fewer combined with the same stale-post flag. And there is no threshold that tips the choice toward catalog videos: gap is a four-way OR, so any single true clause forces SNAPSBY, and only an all-clear on every clause yields OWN_CONTENT.
`influora-api/src/main/java/com/influora/service/trendspark/ContentGapService.java:72` "brandOwnContentService.checkOwnContent(brandProfile, trend)"
`influora-api/src/main/java/com/influora/service/trendspark/ContentGapService.java:94` "Instant.now().minus(props.getGapDays(), ChronoUnit.DAYS)"
`influora-api/src/main/java/com/influora/service/trendspark/ContentGapService.java:67` "trend.getPeakWindowDays() <= 3"
`influora-api/src/main/java/com/influora/service/trendspark/ContentGapService.java:77` "gap ? NudgeMode.SNAPSBY : NudgeMode.OWN_CONTENT"

### 8. WHY NOT AS ADVERTISED?
Zero overlaps. Both sides are parsed into plain string hash sets and the overlap loop is a raw set-membership test — no lowercasing, no stemming, no substring or token containment. Holi is not equal to holi celebration and Festival is not equal to spring festivals, so the score is 0, which is below the default threshold of 2, and the endpoint returns 204 with no nudge at all. The keyword-containment matcher that would have caught it exists in the same class, but it is only applied to Instagram captions in the own-content signal, never to the brand profile tags on the scoring path.
`influora-api/src/main/java/com/influora/service/trendspark/ThemeMatchService.java:66` "brandThemes.contains(theme)"
`influora-api/src/main/java/com/influora/service/trendspark/ThemeMatchService.java:128` "new HashSet<>(themes)"
`influora-api/src/main/java/com/influora/config/TrendSparkProperties.java:13` "private int scoreThreshold = 2"

### 9. WHEN DOES IT BREAK?
The premise is false: the query does carry an ORDER BY, on detected date descending. The selection loop keeps the highest score, and because the comparison is strictly greater-than, the first row in that ordering wins any tie. So with four matching trends the answer is the highest-scoring one, and among equal scores the most recently detected one. Nothing here is random. The one real weakness is that the loop scores every active row in memory rather than filtering in SQL, so 17 rows means 17 JSON parses per dashboard load.
`influora-api/src/main/java/com/influora/repository/TrendRepository.java:14` "ORDER BY t.detectedDate DESC"
`influora-api/src/main/java/com/influora/service/trendspark/TrendSparkNudgeService.java:96` "if (score > bestScore)"
`influora-api/src/main/java/com/influora/service/trendspark/TrendSparkNudgeService.java:91` "trendRepository.findActive(Instant.now())"

### 10. WHAT IS MISSING?
The column exists on brand_profiles and the entity maps it, but nothing in the application ever writes it — the setter is declared and has no caller in main, so the value is null for every brand in production. That is not a cosmetic gap: a null timestamp is treated as stale, stale forces the gap flag true, and the gap flag is the first term of an OR, so the nudge mode is unconditionally SNAPSBY and the OWN_CONTENT branch is dead code today. The Meta side is genuinely wired — the own-content service really does call the Instagram media endpoint with a decrypted token — but its answer can only ever narrow the result, never rescue OWN_CONTENT, because the stale-post term is evaluated independently and OR-ed in regardless.
`influora-api/src/main/java/com/influora/domain/entity/BrandProfile.java:64` "last_posted_at"
`influora-api/src/main/java/com/influora/domain/entity/BrandProfile.java:147` "public void setLastPostedAt"
`influora-api/src/main/java/com/influora/service/trendspark/ContentGapService.java:91` "if (lastPostedAt == null)"
`influora-api/src/main/java/com/influora/service/trendspark/BrandOwnContentService.java:112` "instagramInsightsClient.getMedia(igBusinessAccountId"

---

## 3. Meta / Instagram Integration

### 11. WORKS?
Yes as code, with one path correction. The browser route in the question is a frontend page; the API edge is the creator-scoped OAuth callback under the meta oauth mapping, and it is authenticated, so the creator must still hold a session when Meta redirects back. From there the chain is real: the state token is single-use, ten-minute, and bound to the initiating user id and to the login path that started it, so a mismatch on any of those four conditions is one indistinguishable rejection. The connect service then does code to short-lived to long-lived in two hops, resolves the IG business account and granted scopes, and stores the token AES-GCM encrypted with an expiry derived from the response and a sixty-day fallback when Meta omits expires_in.
`influora-api/src/main/java/com/influora/web/MetaOAuthController.java:122` "/callback"
`influora-api/src/main/java/com/influora/integration/meta/oauth/MetaOAuthStateStore.java:20` "Duration.ofMinutes(10)"
`influora-api/src/main/java/com/influora/service/creatorcopilot/CreatorMetaOAuthService.java:105` "exchangeForLongLivedToken(shortLived.accessToken())"
`influora-api/src/main/java/com/influora/integration/meta/oauth/MetaTokenStorage.java:50` "AES/GCM/NoPadding"

### 12. HOW?
It does not iterate every non-revoked token. The sweep asks storage for only the tokens within the configured days-before-expiry window, so a token with fifty days left is never touched. For each of those it re-exchanges the current access token at the long-lived endpoint — the Facebook refresh method is literally a one-line delegate to the long-lived exchange — computes a new expiry from the returned expires_in, and re-stores it, choosing the Instagram refresh endpoint instead when the row's auth path says Instagram Login. So the doc's self-refresh-by-re-exchange description is accurate. One correction to the question: the cron carries no zone attribute, unlike the metrics job which pins UTC, so 02:30 is server-local, not IST or UTC by declaration.
`influora-api/src/main/java/com/influora/job/MetaTokenRefreshService.java:64` "0 30 2 * * *"
`influora-api/src/main/java/com/influora/job/MetaTokenRefreshService.java:80` "findTokensExpiringSoon(props.getTokenRefreshDaysBeforeExpiry())"
`influora-api/src/main/java/com/influora/integration/meta/oauth/MetaOAuthService.java:151` "return exchangeForLongLivedToken(currentToken)"
`influora-api/src/main/java/com/influora/job/MetaTokenRefreshService.java:170` "computeExpiresAt(refreshed.expiresInSeconds())"

### 13. WHY NOT AS ADVERTISED?
Neither. The properties class defaults both fields to empty strings and nothing constructs a bean from them eagerly, so boot succeeds. The authorize endpoint checks the configured flag first and throws a 503 carrying the code META_NOT_CONFIGURED, so the creator never receives an authorize URL with an empty client_id and Meta is never contacted — there is no 502. The comment on that guard records that it was added only after a live deploy handed back a blank client_id and Meta answered with an invalid-app-id error, so the fail-loud behaviour is recent, not original. The doc's placeholders-only claim is also stale against the app-review state recorded elsewhere in this repo.
`influora-api/src/main/java/com/influora/config/MetaApiProperties.java:17` "private String appId"
`influora-api/src/main/java/com/influora/web/MetaOAuthController.java:91` "!metaApiProperties.isConfigured()"
`influora-api/src/main/java/com/influora/web/MetaOAuthController.java:93` "META_NOT_CONFIGURED"
`influora-api/src/main/resources/application.yml:376` "app-id: ${META_APP_ID:}"

### 14. WHEN DOES IT BREAK?
The third option, and the token is never revoked. A revoked scope surfaces as a permission-denied exception, which is a subclass of the general Meta API exception, so it falls through the rate-limit and token-expired handlers into the catch-all, which logs an error, counts the creator as failed and returns. Nothing writes the revoked flag, nothing clears expires_at, and the row still satisfies the not-revoked-and-not-yet-expired query the job selects on. So the concrete failure is a silent permanent loop: that creator is re-polled and re-fails every six hours until the token's own expiry date passes, with no operator signal beyond a log line and a failed count in the audit row.
`influora-api/src/main/java/com/influora/integration/meta/exception/MetaPermissionDeniedException.java:6` "extends MetaApiException"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:259` "catch (MetaApiException e)"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:138` "findByRevokedFalseAndExpiresAtAfter(Instant.now())"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:171` "creatorsFailed"

### 15. WHAT IS MISSING?
Both halves of the doc claim are false as of the current tree. The metrics polling job runs on a six-hourly UTC cron, and its per-creator method calls the recent-media helper right after saving the follower snapshot; that helper calls the fetcher's media-with-insights method and bulk-saves one media_metrics row per post. The whole per-post leg is behind a property that defaults to true and is bound to an environment variable, so it is on unless a deploy explicitly turns it off. What is genuinely absent is any revocation or alerting on the failure path described in answer 14, not the job itself.
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:122` "0 0 */6 * * *"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:247` "pollRecentMedia(creatorProfileId, igBusinessAccountId, token.get(), authPath)"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:290` "metricsFetcher.fetchMediaWithInsights"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:316` "mediaMetricsRepository.saveAll(rows)"
`influora-api/src/main/resources/application.yml:391` "media-metrics-enabled: ${META_MEDIA_METRICS_ENABLED:true}"

---

## 4. Shopify Integration

### 16. WORKS?
Partly — the last clause of the question is wrong. Signature verification really does run first, against the raw body, before any parsing or shop lookup; the shop domain header is then resolved server-side to the owning connection row; and the redemption writer does insert a coupon_redemptions row and bump the coupon usage counter, inside a real transaction. What it does not do is touch affiliate earnings. There is no call to the earnings service anywhere in that write path, so the creator's accrual does not happen at webhook time — see answer 30 for where it actually happens.
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:122` "signatureVerifier.verify(rawPayload, signature, null)"
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:138` "findByShopDomainAndRevokedFalse(shopDomain)"
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:88` "redemptionRepository.save(redemption)"
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:92` "coupon.incrementUsageCount()"

### 17. HOW?
Before, and twice. The callback validates the shop query parameter against the anchored pattern as its second statement, immediately after resolving the brand workspace and before the state token is consumed, and the authorization-URL builder re-validates independently so no unvalidated host can ever be concatenated into a Shopify URL. A non-matching value throws a 400 carrying INVALID_SHOP_DOMAIN, so the callback fails before the state store is touched and long before any token exchange. Note the check normalizes to lower case before matching and returns the lower-cased value, so the domain persisted and later matched by the webhook is always lower case.
`influora-api/src/main/java/com/influora/web/ShopifyConnectController.java:86` "ShopifyOAuthService.validateShopDomain(shop)"
`influora-api/src/main/java/com/influora/integration/shopify/oauth/ShopifyOAuthService.java:55` "Pattern.compile("
`influora-api/src/main/java/com/influora/integration/shopify/oauth/ShopifyOAuthService.java:83` "INVALID_SHOP_DOMAIN"
`influora-api/src/main/java/com/influora/web/ShopifyConnectController.java:88` "!stateStore.consume(state, principal.getUserId(), validatedShop)"

### 18. WHY NOT AS ADVERTISED?
It fails closed with a 401, and it does so without ever constructing an HMAC. The verifier returns false when the secret is null, blank, or still carries the placeholder prefix, and the controller turns that into an unauthorized response with code INVALID_WEBHOOK_SIGNATURE. Nothing throws at bean construction — the properties class defaults the secret to an empty string and there is no yaml placeholder for it at all under the shopify block, only the token-encryption key, so on a deploy that never sets the property every Shopify webhook is silently rejected as unsigned rather than failing loudly at boot.
`influora-api/src/main/java/com/influora/integration/shopify/webhook/ShopifyWebhookSignatureVerifier.java:53` "secret == null || secret.isBlank()"
`influora-api/src/main/java/com/influora/integration/shopify/webhook/ShopifyWebhookSignatureVerifier.java:55` "return false"
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:124` "INVALID_WEBHOOK_SIGNATURE"
`influora-api/src/main/java/com/influora/config/ShopifyProperties.java:28` "private String webhookSigningSecret"
`influora-api/src/main/resources/application.yml:428` "token-encryption-key: ${INFLUORA_SHOPIFY_TOKENENCRYPTIONKEY:}"

### 19. WHEN DOES IT BREAK?
The disconnect half of the scenario cannot happen: the connect controller exposes only authorize and callback, and the revoke method on the token storage has no caller anywhere in the application, so there is no way for a brand to disconnect a store through the API. Reconnecting without disconnecting takes the existing-row branch, which rotates the token and scopes in place — and that branch never rewrites shop_domain, so pointing the same workspace at a different store leaves the row still keyed to the first store while holding the second store's token. If a revoked row ever did exist, the workspace lookup would miss it and the insert would collide with the global unique key on shop_domain, surfacing as a 409 data-conflict rather than a duplicate row.
`influora-api/src/main/java/com/influora/web/ShopifyConnectController.java:79` "@GetMapping("
`influora-api/src/main/java/com/influora/integration/shopify/oauth/ShopifyTokenStorage.java:85` "repository.findByWorkspaceIdAndRevokedFalse(workspaceId)"
`influora-api/src/main/java/com/influora/domain/entity/ShopifyIntegration.java:104` "public void rotateToken(String encryptedAccessToken, String grantedScopesJson)"
`influora-api/src/main/resources/db/migration/V27__shopify_integrations.sql:29` "UNIQUE KEY uq_shopify_shop_domain (shop_domain)"
`influora-api/src/main/java/com/influora/common/GlobalExceptionHandler.java:114` "DATA_INTEGRITY_VIOLATION"

### 20. WHAT IS MISSING?
All three, deliberately layered. The controller derives the key itself from shop domain, topic and order id, hex-encodes the digest with a shopify prefix, and wraps the whole handler in the shared reservation service under its own scope. It then passes that same key down as the redemption idempotency key, where the redemption service checks for an existing row by that key before any validation and reserves it again under a second scope. Underneath both, the coupon_redemptions table declares the key column NOT NULL UNIQUE, so even if both application layers were bypassed the second insert would fail rather than double-count. A duplicate delivery therefore no-ops at the outermost layer and returns 200.
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:161` "deriveIdempotencyKey(integration.getShopDomain(), effectiveTopic, order.orderId())"
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:164` "idempotencyService.executeOnce"
`influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java:177` "CouponRedemption replay = replayIfPresent(idempotencyKey)"
`influora-api/src/main/resources/db/migration/V24__coupon_codes.sql:62` "idempotency_key         VARCHAR(100) NOT NULL UNIQUE"

---

## 5. WooCommerce Integration

### 21. WORKS?
Yes, with one correction to the premise: normalize does not strip a trailing slash, it discards the whole path. It rebuilds the URL from scheme plus lower-cased host plus port only, so a site submitted with a path or a slash canonicalizes to the bare origin. The submitted secret is AES-GCM encrypted before the row is written, the column is declared NOT NULL as ciphertext, and a later webhook resolves the same normalized value and verifies against that decrypted per-site secret. So the round trip does close.
`influora-api/src/main/java/com/influora/web/WooCommerceConnectController.java:61` "/connect"
`influora-api/src/main/java/com/influora/integration/woocommerce/WooCommerceSiteUrl.java:54` "scheme.toLowerCase()"
`influora-api/src/main/java/com/influora/web/WooCommerceConnectController.java:77` "integrationService.connect(workspace.getId(), normalizedSiteUrl, webhookSecret)"
`influora-api/src/main/resources/db/migration/V29__woocommerce_integrations.sql:26` "encrypted_webhook_secret    TEXT NOT NULL"

### 22. HOW?
Site first, exactly as you describe, and the code says so explicitly. The order is: reject a blank source header with a 400, normalize the header value the same way connect normalized it, look the integration up globally by that normalized URL and 404 if there is none, decrypt the per-integration secret, and only then HMAC the raw body against it and 401 on mismatch. The controller's own comment records that this deliberately inverts Shopify's verify-first ordering because WooCommerce has no app-level secret to check against, and defends it on the grounds that a header read plus an indexed lookup is not payload parsing. The raw JSON is genuinely not parsed until after the signature passes.
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:166` "WooCommerceSiteUrl.normalize(siteUrlHeader)"
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:169` "findBySiteUrlAndRevokedFalse(normalizedSiteUrl)"
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:177` "integrationService.decryptSecret(integration)"
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:180` "INVALID_WEBHOOK_SIGNATURE"

### 23. WHY NOT AS ADVERTISED?
The unique key exists and it is global on site_url with no workspace or revoked column in it, so the second workspace's connect cannot insert and surfaces as a 409 data conflict. The lookup can therefore never return two rows — its return type is a single Optional and the constraint guarantees at most one. The real defect on this path is the other direction: connect resolves the existing row by workspace, and the rotate method only replaces the ciphertext, never the site URL. A brand that reconnects pointing at a different site keeps the original site_url on the row while holding the new site's secret, so webhooks from the new site 404 and webhooks from the old site verify against the wrong secret.
`influora-api/src/main/resources/db/migration/V29__woocommerce_integrations.sql:32` "UNIQUE KEY uq_woocommerce_site_url (site_url)"
`influora-api/src/main/java/com/influora/repository/WooCommerceIntegrationRepository.java:23` "Optional<WooCommerceIntegration> findBySiteUrlAndRevokedFalse(String siteUrl)"
`influora-api/src/main/java/com/influora/integration/woocommerce/WooCommerceIntegrationService.java:87` "repository.findByWorkspaceIdAndRevokedFalse(workspaceId)"
`influora-api/src/main/java/com/influora/integration/woocommerce/WooCommerceIntegrationService.java:91` "existing.get().rotateSecret(encrypted)"

### 24. WHEN DOES IT BREAK?
It silently skips and returns 200 — the third option, and it is deliberate. The payload record takes the first coupon line's code or null when the list is empty, and the controller returns an empty 200 the moment that value is null or blank, before deriving any idempotency key and before the redemption service is reached. So the redemption service is never called with an empty code, and no reservation row is burned either. The parse step also tolerates the field being absent rather than merely empty, so a store that omits coupon_lines entirely lands on the same branch.
`influora-api/src/main/java/com/influora/integration/woocommerce/webhook/WooCommerceOrderWebhookPayload.java:90` "couponLines.isEmpty() ? null : couponLines.get(0).code()"
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:192` "order.couponCode() == null || order.couponCode().isBlank()"
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:195` "return ResponseEntity.ok().build()"

### 25. WHAT IS MISSING?
Distinct events, and that is a real double-count. The topic is a component of the hashed key, and both order.created and order.updated are in the acted-on set, so the same order arriving under both topics produces two different keys, passes both reservation layers, and writes two coupon_redemptions rows with two usage-count increments for one purchase. The database unique constraint is on the idempotency key alone, not on coupon and order, so it does not catch this either. Shopify has the identical shape with orders/create and orders/paid. Nothing in this path deduplicates on the order id itself.
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:198` "deriveIdempotencyKey(integration.getSiteUrl(), effectiveTopic, order.orderId())"
`influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java:184` "TOPIC_ORDER_UPDATED.equals(effectiveTopic)"
`influora-api/src/main/resources/db/migration/V24__coupon_codes.sql:62` "idempotency_key         VARCHAR(100) NOT NULL UNIQUE"

---

## 6. Conversion Tracking (Generic + UTM)

### 26. WORKS?
Yes, and the click and conversion halves both land — but note the conversion route parses the JSON body before it verifies anything, because the UTM id inside the payload is what resolves the workspace whose secret the signature is checked against. That is a documented inversion of the verify-before-parse rule the store webhooks follow. After verification the tracking writer increments the conversion count and adds the revenue on the same utm_campaigns row inside one real transaction, and the click route separately increments the click counter and 302s to the stored destination URL.
`influora-api/src/main/java/com/influora/web/ConversionWebhookController.java:250` "parseConversionPayload(rawPayload)"
`influora-api/src/main/java/com/influora/web/ConversionWebhookController.java:254` "verifySignatureOrReject(rawPayload, signature, workspaceId)"
`influora-api/src/main/java/com/influora/service/tracking/ConversionTrackingWriter.java:60` "utm.addRevenue(orderAmount)"
`influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java:273` "utm.incrementClickCount()"

### 27. HOW?
A shared SecureRandom instance produces 32 bytes, which are URL-safe base64 encoded without padding — so the brand receives roughly a 43-character token. The plaintext is encrypted with the same AES-GCM scheme used for store tokens before the row is written, and only the ciphertext is persisted. The controller returns the plaintext in the response body of the generate call and there is no read endpoint anywhere on that controller, only generate and a revoke, so that single response really is the one and only reveal. Calling generate again rotates in place rather than adding a row, which silently invalidates any signature the brand's system was already producing.
`influora-api/src/main/java/com/influora/integration/tracking/webhook/ConversionWebhookSecretService.java:52` "private static final int SECRET_BYTES = 32"
`influora-api/src/main/java/com/influora/integration/tracking/webhook/ConversionWebhookSecretService.java:166` "Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)"
`influora-api/src/main/java/com/influora/integration/tracking/webhook/ConversionWebhookSecretService.java:99` "existing.get().rotateSecret(encrypted)"
`influora-api/src/main/java/com/influora/web/ConversionWebhookSecretController.java:54` "new ConversionWebhookSecretResponse(plaintext)"

### 28. WHY NOT AS ADVERTISED?
No link at all, and therefore no double count — the two systems write to different tables and never meet. The click increments a counter on the utm_campaigns row; the discount code at checkout produces a coupon_redemptions row through a different service reached from a different controller. The reason the ULID cannot ride an order is visible in the link builder: the utm_campaign parameter is a slugified campaign title, not the tracking row's id, so nothing in the order payload could be resolved back to a UTM row even if a store webhook wanted to. The store webhook controllers say so in their own scope notes.
`influora-api/src/main/java/com/influora/service/tracking/CampaignLinkService.java:176` "SlugUtils.slugify(campaign.getTitle())"
`influora-api/src/main/java/com/influora/service/tracking/ConversionTrackingWriter.java:59` "utm.incrementConversionCount()"
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:88` "redemptionRepository.save(redemption)"
`influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java:216` "redemptionService.redeem(workspaceId, discountCode, orderId, orderAmount, null, idempotencyKey)"

### 29. WHEN DOES IT BREAK?
It skips the second one, but not for the reason offered — the caller's key plays no part in the dedup decision. The reservation key is built from the workspace id, a literal separator, the UTM id and the order id; the supplied key is passed through only to the writer for the audit row. So the retry is a clean no-op because workspace, UTM and order all repeat. The corollary is the real hazard: two genuinely distinct conversions on the same UTM link that share an order id are silently collapsed into one, and conversely a caller who varies the order id can double-count the same sale no matter what idempotency key it sends.
`influora-api/src/main/java/com/influora/service/tracking/ConversionTrackingService.java:155` "workspaceId) + ":conv:" + utmCampaignId"
`influora-api/src/main/java/com/influora/service/tracking/ConversionTrackingService.java:157` "idempotencyService.executeOnce"
`influora-api/src/main/java/com/influora/web/ConversionWebhookController.java:261` "request.idempotencyKey()"

### 30. WHAT IS MISSING?
Nothing happens at redemption time — the writer saves the redemption row, bumps the coupon usage count and records a money audit event, and there is no call to the earnings service anywhere in it. The accrual is produced entirely by a scheduled reconciliation job that runs hourly at minute 15, finds redemptions with no matching affiliate_earnings row, and re-invokes the earnings service for each. That job also honours a thirty-minute grace window, so a creator's earning appears somewhere between half an hour and an hour and a half after the sale. Note the job's own javadoc describes a synchronous call inside the redemption transaction that no longer exists in the code — the job is not a belt-and-braces floor any more, it is the only writer.
`influora-api/src/main/java/com/influora/service/tracking/RedemptionWriter.java:93` "couponCodeRepository.save(coupon)"
`influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java:82` "0 15 * * * *"
`influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java:64` "Duration.ofMinutes(30)"
`influora-api/src/main/java/com/influora/job/AffiliateEarningReconciliationJob.java:105` "affiliateEarningsService.recordEarning(redemption)"

---

## 7. Admin Dashboard

### 31. WORKS?
Yes, though the class in the question does not exist — dispute resolution lives in the shared DisputeService, not an admin-specific one, and the role plus MFA gate is its very first statement, accepting SUPER_ADMIN or ADMIN. Resolving in the creator's favour runs the escrow release for the collaboration before the status is written, counts the frozen holds beforehand and refuses with a conflict if fewer holds moved than were frozen, and the entity stamps the resolved timestamp inside its own resolve method. One correction: the terminal status is RESOLVED_CREATOR, not a generic RESOLVED — the status enum has one terminal value per outcome.
`influora-api/src/main/java/com/influora/web/AdminDisputeController.java:73` "/{disputeId}/resolve"
`influora-api/src/main/java/com/influora/service/DisputeService.java:201` "adminContext.requireRoleWithMfaSatisfied("
`influora-api/src/main/java/com/influora/service/DisputeService.java:250` "escrowService.adminReleaseForDispute(dispute.getCollaborationId())"
`influora-api/src/main/java/com/influora/domain/entity/Dispute.java:122` "this.resolvedAt = Instant.now()"

### 32. HOW?
It writes the message and nothing else. The reply method creates a support ticket message with a fresh ULID, the ticket id, the acting admin's id and a sender type of ADMIN — note the column is sender type, not a role field — saves it, and returns the re-read detail DTO. The ticket row itself is never saved on this path, so neither status nor the updated-at column moves; the ticket's touch helper is only reached from the status, assign and escalate mutators. It also deliberately skips the admin audit log here, because message content is PII that the audit trail is not allowed to hold.
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:150` "SenderType.ADMIN, content"
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:151` "supportTicketMessageRepository.save(message)"
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:156` "return toDetailDto(ticket)"
`influora-api/src/main/java/com/influora/domain/entity/SupportTicket.java:58` "updated_at"

### 33. WHY NOT AS ADVERTISED?
The doc is stale on both counts. The escalate route exists on the admin support controller and the frontend contract posts to exactly that path, so the button reaches a real endpoint that raises the ticket to URGENT and audit-logs the reason. The stats route also exists, deliberately hosted on a sibling controller mounted one path segment higher so that it resolves as support-slash-stats rather than under the tickets prefix — its own javadoc explains that Spring cannot escape a class-level mapping any other way. So neither call 404s today.
`influora-api/src/main/java/com/influora/web/AdminSupportController.java:105` "/{id}/escalate"
`src/admin/services/api-contracts.ts:625` "/support/tickets/${id}/escalate"
`influora-api/src/main/java/com/influora/web/AdminSupportStatsController.java:26` "/admin/support"
`influora-api/src/main/java/com/influora/web/AdminSupportStatsController.java:35` "/stats"

### 34. WHEN DOES IT BREAK?
The second admin gets a 409 and no money moves twice. There are three independent guards. First, a fast-path check rejects a dispute whose status is no longer active with DISPUTE_ALREADY_RESOLVED before escrow is touched at all. Second, for a true concurrent race where both requests load the row before either commits, the entity carries a version column and the save is a flush so the optimistic-lock failure is raised synchronously inside the method and translated to a conflict. Third, even if both reached settlement, the frozen-hold count is taken before settling and the resolution is refused when fewer holds moved than were frozen — so the loser cannot audit-log a payout that did not happen.
`influora-api/src/main/java/com/influora/service/DisputeService.java:225` "if (!dispute.getStatus().isActive())"
`influora-api/src/main/java/com/influora/service/DisputeService.java:227` "DISPUTE_ALREADY_RESOLVED"
`influora-api/src/main/java/com/influora/domain/entity/Dispute.java:73` "@Version"
`influora-api/src/main/java/com/influora/service/DisputeService.java:327` "disputeRepository.saveAndFlush(dispute)"
`influora-api/src/main/java/com/influora/service/DisputeService.java:295` "settlements.size() < frozenHoldsBefore"

### 35. WHAT IS MISSING?
Real data. The billing controller exposes four routes and the list route delegates to a service that runs a real paged specification query against the subscriptions repository; the metrics route counts by status and plan against the same repository, and the comp and override routes mutate real subscription rows with an audit entry. Nothing in that service returns hardcoded rows. The doc's mock claim does not match the current tree — what is genuinely partial is elsewhere, for example the support stats DTO's average-response-time field, which is an honest zero because no first-reply timestamp column exists.
`influora-api/src/main/java/com/influora/web/AdminBillingController.java:104` "/subscriptions"
`influora-api/src/main/java/com/influora/service/admin/AdminBillingService.java:115` "subscriptionRepository.findAll(spec, pageable)"
`influora-api/src/main/java/com/influora/service/admin/AdminBillingService.java:189` "subscriptionRepository.countByStatusAndPlanIdAndCompFalse("
`influora-api/src/main/java/com/influora/web/AdminBillingController.java:127` "/override"

---

## 8. Brand Dashboard

### 36. WORKS?
Partly, and the premise is wrong about the shape. There is no single dashboard query returning campaigns plus deliverables plus balance: the dashboard controller exposes only an actions route and a pipeline route, and the page fires three parallel calls — actions, wallet and pipeline — then composes the cards from those. So the wallet figure and the pipeline counts are real, but they arrive from three endpoints, not one, and a failure in any one degrades that card independently rather than the page. The file's own header comment lists exactly those three routes.
`influora-api/src/main/java/com/influora/web/DashboardController.java:34` "/actions"
`influora-api/src/main/java/com/influora/web/DashboardController.java:41` "/pipeline"
`src/components/brand/dashboard/dashboard-page.tsx:153` "api.wallet.get('brand')"
`src/components/brand/dashboard/dashboard-page.tsx:154` "api.dashboard.pipeline('brand')"

### 37. HOW?
Real, not mock — but not via a Next router either; this is a Vite and React Router app. The layout holds the open state and renders the command bar, which registers its own document-level keydown listener for the k chord with either meta or control and toggles the dialog. Selecting an item closes the dialog, clears the search box, and calls the React Router navigate function with the item's path — for the wallet entry that path is the brand-prefixed wallet route, not a bare one. Matching is the cmdk dialog's own built-in filtering over the item list, not a hand-written fuzzy matcher. The only wrinkle recorded in the code is that the layout also listens on the window and unconditionally opens, so the command bar has to stop propagation to remain able to close itself.
`src/components/brand/command-bar.tsx:123` "e.key === 'k' && (e.metaKey || e.ctrlKey)"
`src/components/brand/command-bar.tsx:137` "navigate(action)"
`src/components/brand/command-bar.tsx:81` "Balance & transactions"
`src/components/brand/brand-layout.tsx:614` "<CommandBar open={commandBarOpen} onOpenChange={setCommandBarOpen} />"

### 38. WHY NOT AS ADVERTISED?
The doc is out of date — auto-refresh exists twice over. Before any authenticated request the client decodes the access token's expiry and, if it is inside the skew window, silently refreshes against the refresh endpoint using the HttpOnly cookie. If the server still answers 401, a reactive path refreshes once and replays the original request with the new token. So the create-campaign click after a day idle succeeds transparently for as long as the thirty-day refresh cookie lives. Only when that refresh itself fails is the stale token cleared and the original 401 returned to the caller's normal error handling. The retry is deliberately 401-only, so a 403 is never retried.
`src/lib/api.ts:488` "private async ensureFreshToken(role: Role): Promise<string | null>"
`src/lib/api.ts:495` "TOKEN_REFRESH_SKEW_MS"
`src/lib/api.ts:525` "res.status === 401 && hasAuthHeader && !retried"
`src/lib/api.ts:531` "this.clearToken(role)"

### 39. WHEN DOES IT BREAK?
The route guard does let the page load — it reads the brand token from storage and adds a server-side onboarding check, but never looks at the workspace role. The second half of the question is wrong though: the create call does not 403 either. Campaign creation only calls the membership check, and that method resolves an active member row and throws only when there is none; the role-restricting helper is never invoked on the create path, unlike update and the publish and delete paths which do pass an explicit allowed-role list. So a VIEWER can create a campaign today. The failure is a missing server-side gate, not a permissive client guard.
`src/App.tsx:93` "readAuthToken('brand_token')"
`src/App.tsx:123` "if (!isAuthenticated && !isDemoMode)"
`influora-api/src/main/java/com/influora/service/CampaignService.java:147` "brandContext.requireMember(principal, workspace.getId())"
`influora-api/src/main/java/com/influora/service/BrandContextService.java:94` "public void requireRole(WorkspaceMember member, MemberRole... allowed)"
`influora-api/src/main/java/com/influora/service/CampaignService.java:236` "requireRole(member, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MANAGER)"

### 40. WHAT IS MISSING?
None of the three — campaign detail is live. The page's loader fires a real request to the campaign-by-id route alongside deals, analytics and the platform-fee lookup, and the backend controller declares that exact mapping under the campaigns prefix. The path in the question does not exist in either half; the client requests the bare campaign id path, with no details suffix. Note the analytics and fee calls are individually caught and degraded to null, so a partial backend failure renders a thinner page rather than an error — which is probably what earned the mock-backed label in the doc.
`src/pages/brand-campaign-detail.tsx:618` "api.campaigns.get(id)"
`src/lib/api.ts:1504` "http.request<CampaignApiRow>('GET', `/campaigns/${id}`)"
`influora-api/src/main/java/com/influora/web/CampaignController.java:58` "/{campaignId}"
`src/pages/brand-campaign-detail.tsx:620` "api.campaigns.analytics(id).catch(() => null)"

---

## 9. Creator Dashboard

### 41. WORKS?
Yes, but the controller named is the wrong one — deals come from the deals route, not the creator deliverable controller, which is mounted under creator deliverables and only serves upload, submit, metrics, proof and verify. The page requests the deals list with a status filter and renders six chips, not three: All, New, Negotiating, Active, Completed and Disputed. The statuses on the wire are lower-case and the Active chip is a union of contracted, in_progress and review sent as a comma-joined filter, so there is no single ACCEPTED state as the question assumes.
`src/pages/creator-deals.tsx:249` "api.deals.list('creator', chip?.apiFilter ?? activeFilter)"
`src/pages/creator-deals.tsx:84` "apiFilter: 'contracted,in_progress,review'"
`src/lib/api.ts:2100` "http.request<Deal[]>('GET', '/deals', { role, query: { status } })"
`influora-api/src/main/java/com/influora/web/CreatorDeliverableController.java:39` "/creator/deliverables"

### 42. HOW?
None of the three, though the closest is the first. The portfolio does not expose a demographics blob at all — it reads the most recent audience_demographics snapshot row for the creator and reduces the city breakdown to the top five city names by count. Those snapshot rows are written by a weekly job that runs Sunday at 03:30 and does call the Instagram insights client, so this is real Meta-derived data persisted into its own table, not a denormalized column on creator_profiles and not permanently null. When no snapshot exists the method returns an empty list rather than null, and the creator-facing analytics route returns an explicit empty shape for the same reason.
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:738` "audienceDemographicsRepository"
`influora-api/src/main/java/com/influora/service/portfolio/PortfolioService.java:739` "findFirstByCreatorProfileIdOrderByTimeDesc(creatorProfileId)"
`influora-api/src/main/java/com/influora/job/AudienceDemographicsJob.java:96` "0 30 3 * * SUN"
`influora-api/src/main/java/com/influora/job/AudienceDemographicsJob.java:236` "demographicsRepository.save(snapshot)"

### 43. WHY NOT AS ADVERTISED?
The doc is stale. There is a real controller mounted at the creator affiliate-earnings path, it delegates to the earnings service with paging, the frontend client calls exactly that path in live mode, and the page renders a dedicated view component rather than a placeholder. So it returns whatever rows exist — which will often be empty, but that is data, not a stub. There is no 501 anywhere on this route and no coming-soon banner in the affiliate page. What is true is answer 30's timing: rows only appear once the hourly reconciliation job has run.
`influora-api/src/main/java/com/influora/web/CreatorAffiliateEarningController.java:20` "/creator/affiliate-earnings"
`influora-api/src/main/java/com/influora/web/CreatorAffiliateEarningController.java:35` "affiliateEarningsService.listForCreator(principal, page, limit)"
`src/lib/api.ts:5032` "'GET', '/creator/affiliate-earnings'"
`src/pages/creator-affiliate-earnings.tsx:2` "AffiliateEarningsView"

### 44. WHEN DOES IT BREAK?
None of the three — the read path never touches Meta, so an expired token cannot break it. The analytics controller delegates to a service that only reads persisted rows: demographics come from the latest snapshot and return an explicit empty response when none exists. The expired token matters only to the background jobs, and there the creator-token lookup filters on the expiry being in the future, so it simply yields an empty Optional and the polling job logs a warning and skips that creator. Nothing soft-revokes: the row keeps its revoked flag false forever, exactly as in answer 14. The user-visible symptom is silently stale analytics, with no prompt to reconnect.
`influora-api/src/main/java/com/influora/web/CreatorAnalyticsController.java:54` "/demographics"
`influora-api/src/main/java/com/influora/service/analytics/AnalyticsService.java:288` "return CreatorDemographicsResponse.empty()"
`influora-api/src/main/java/com/influora/integration/meta/oauth/MetaTokenStorage.java:389` "t.getExpiresAt().isAfter(Instant.now())"
`influora-api/src/main/java/com/influora/job/MetricsPollingJob.java:193` "no valid token for creator"

### 45. WHAT IS MISSING?
Real rows. The wallet controller branches on the principal's user type on both the summary and the balance routes, and the creator branch calls the user-scoped service methods rather than the workspace-scoped ones — there is no brand-only filter to strand a creator at zero. The transactions route branches the same way, and payouts is deliberately creator-only. The creator wallet page's own header comment lists the three routes it uses and they all resolve. So the mock-surfaces label in the doc does not hold for wallet on the current tree.
`influora-api/src/main/java/com/influora/web/WalletController.java:75` "principal.getUserType() == UserType.CREATOR"
`influora-api/src/main/java/com/influora/web/WalletController.java:77` "walletService.getSummaryForUser(principal.getUserId())"
`influora-api/src/main/java/com/influora/web/WalletController.java:175` "/payouts"
`src/pages/creator-wallet.tsx:450` "api.wallet.get('creator')"

---

## 10. Support Tickets

### 46. WORKS?
Yes, end to end as described. The list route accepts both filters plus four more, parses them into enums, builds a JPA specification and pages the result sorted by creation date descending. The detail route returns the ticket with its message thread. The reply route writes an ADMIN-sender message and, as established in answer 32, saves nothing on the ticket row itself — so the status genuinely does not change, and neither does the updated-at column. The whole surface is gated on SUPER_ADMIN, ADMIN or SUPPORT with MFA satisfied.
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:110` "AdminRole.SUPER_ADMIN, AdminRole.ADMIN, AdminRole.SUPPORT"
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:120` "SupportTicketSpecs.withFilters("
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:125` "Sort.by(Sort.Direction.DESC, "
`influora-api/src/main/java/com/influora/web/AdminSupportController.java:78` "/{id}/reply"

### 47. HOW?
It accepts any valid enum value, with no transition graph at all — the contrast with disputes is exact and deliberate on the dispute side, not this one. The method parses the string into the ticket status enum, 400s only when it is unparseable, then hands the value straight to the entity, which assigns it and stamps the resolved timestamp when and only when the new value is RESOLVED. Two consequences follow: moving a ticket back out of RESOLVED leaves the old resolved timestamp in place because nothing ever clears it, and re-setting RESOLVED restamps it. The audit entry records the from and to status as metadata, which is the only place a nonsensical transition would ever show up.
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:166` "parseEnum(TicketStatus.class, status, "
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:175` "ticket.updateStatus(toStatus)"
`influora-api/src/main/java/com/influora/domain/entity/SupportTicket.java:120` "if (newStatus == TicketStatus.RESOLVED)"
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:179` "adminAuditLogService.record("

### 48. WHY NOT AS ADVERTISED?
The third, and it is worse than the doc implies — creation is not merely absent from this surface, it is absent from the whole application. There is no support controller outside the admin one, and the admin controller itself exposes only list, detail, reply, status update, assign and escalate; there is no create route anywhere, for a user or for an admin. On the frontend, the brand help page is static how-it-works copy whose only action funnels the user into the Meera chat with a preseeded prompt — no form, no contact address, no client call. So a ticket can only come into existence by a direct database insert.
`influora-api/src/main/java/com/influora/web/AdminSupportController.java:48` "/admin/support/tickets"
`influora-api/src/main/java/com/influora/web/AdminSupportController.java:57` "@GetMapping"
`src/pages/brand-help.tsx:21` "Static "
`src/pages/brand-help.tsx:66` "MEERA_HELP_PRESEED_PARAM"

### 49. WHEN DOES IT BREAK?
It does not break — the doc's not-implemented claim is stale. The route exists on the admin support controller, requires a reason in the body, and delegates to a service method that rejects a blank reason with a 400, raises the ticket's priority to URGENT, saves the row and writes an audit entry carrying the before and after priority. It returns the full ticket detail DTO with a 200. One design note the code states plainly: the ticket status enum has no escalated value, so priority is the only axis escalation moves and the status is deliberately untouched.
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:239` "reason is required to escalate a ticket"
`influora-api/src/main/java/com/influora/service/admin/AdminSupportService.java:243` "ticket.escalate()"
`influora-api/src/main/java/com/influora/domain/entity/SupportTicket.java:141` "this.priority = TicketPriority.URGENT"
`influora-api/src/main/java/com/influora/web/AdminSupportController.java:111` "adminSupportService.escalate(principal, request, id, body.reason())"

### 50. WHAT IS MISSING?
The second option, near enough: there is no column and no join table. The migration gives support_tickets an id, the owning user and user type, a free-text category and subject, status, priority, assignee and three timestamps — nothing that could hold a campaign or collaboration id, and no separate cross-reference table exists. The DTO's own javadoc says exactly that, and the field is typed as a list of bare Object, so even the shape of a future related entity is undecided. The practical answer is therefore the third: the admin reads the subject and the message thread and works it out, because the category column is a free-text label, not a foreign key.
`influora-api/src/main/resources/db/migration/V34__admin_tables.sql:72` "category        VARCHAR(100) NOT NULL"
`influora-api/src/main/resources/db/migration/V34__admin_tables.sql:80` "INDEX idx_ticket_user (user_id)"
`influora-api/src/main/java/com/influora/web/dto/admin/AdminSupportDtos.java:13` "cross-reference table exists for support tickets"
`influora-api/src/main/java/com/influora/web/dto/admin/AdminSupportDtos.java:50` "List<Object> relatedEntities"
