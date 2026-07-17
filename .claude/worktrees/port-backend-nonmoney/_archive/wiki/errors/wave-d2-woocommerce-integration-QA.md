# QA Review: Wave D Task D2 — WooCommerce Webhook Integration
**Date:** 2026-07-07  
**Reviewer:** Kavya Reddy (QA Lead)  
**Status:** ✅ **APPROVED** — cleared for Kabir load-bearing security review

---

## Executive Summary

Wave D task D2 (WooCommerce webhook integration) **PASSES all QA gates**. The implementation demonstrates **learned discipline from D1's HIGH finding** — workspace-scoped redemption is built-in from day one, not added as a follow-up fix. All security surfaces (signature verification, secret encryption, workspace isolation, idempotency) match or exceed the patterns established by D1 (Shopify) and earlier integrations.

**561 tests passing, 0 failures, 0 errors** (orchestrator-confirmed independent ground truth post-D2+D3 merge).

**Key QA finding (non-blocking, documented):** Rate-limiting shares the same per-IP-only `"tracking"` bucket limitation Kabir already flagged for D1 in `task_568d968e` — this is an EXISTING, DOCUMENTED cross-tenant bucket-exhaustion limitation, not a NEW instance introduced by D2. Fix belongs in `AuthRateLimitFilter` once for all webhook surfaces, not piecemeal per integration.

---

## Files Reviewed

### Core Implementation (8 files)
- `influora-api/src/main/resources/db/migration/V29__woocommerce_integrations.sql` — migration
- `influora-api/src/main/java/com/influora/domain/entity/WooCommerceIntegration.java` — entity
- `influora-api/src/main/java/com/influora/repository/WooCommerceIntegrationRepository.java` — repository
- `influora-api/src/main/java/com/influora/integration/woocommerce/WooCommerceIntegrationService.java` — AES-256-GCM encryption service
- `influora-api/src/main/java/com/influora/integration/woocommerce/webhook/WooCommerceWebhookSignatureVerifier.java` — HMAC-SHA256 verifier
- `influora-api/src/main/java/com/influora/integration/woocommerce/webhook/WooCommerceOrderWebhookPayload.java` — JSON parser
- `influora-api/src/main/java/com/influora/web/WooCommerceConnectController.java` — brand-facing connect endpoint
- `influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java` — public webhook receiver

### Supporting Classes (4 files)
- `influora-api/src/main/java/com/influora/integration/woocommerce/WooCommerceSiteUrl.java` — URL normalization
- `influora-api/src/main/java/com/influora/integration/woocommerce/exception/WooCommerceApiException.java` — exception type
- `influora-api/src/main/java/com/influora/config/WooCommerceProperties.java` — Spring config properties
- `influora-api/src/main/java/com/influora/web/dto/woocommerce/WooCommerceDtos.java` — request/response DTOs

### Tests (6 files, 74 tests total per Vikram's handoff)
- `WooCommerceIntegrationServiceTest.java` (12 tests) — encryption round-trip, rotation, revoke, audit
- `WooCommerceWebhookSignatureVerifierTest.java` (10 tests) — HMAC verification, constant-time, fail-closed
- `WooCommerceOrderWebhookPayloadTest.java` (10 tests) — JSON parsing edge cases
- `WooCommerceConnectControllerTest.java` (11 tests) — connect flow, validation, normalization
- `WooCommerceWebhookControllerTest.java` (15 tests) — **hostile cross-tenant test confirmed present and passing**
- `WooCommerceSiteUrlTest.java` (10 tests) — URL normalization cases
- `AuthRateLimitFilterWooCommerceBucketTest.java` (6 tests) — rate-limit bucket assignment

### Configuration/Registration
- `influora-api/src/main/resources/application.yml` — WooCommerce properties block added
- `influora-api/.env.example` — `WOOCOMMERCE_TOKEN_ENCRYPTION_KEY` documented
- `influora-api/src/main/java/com/influora/config/SecurityConfig.java` — `/webhooks/woocommerce` permitAll
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java` — tracking bucket routing
- `influora-api/src/main/java/com/influora/InfluoraApiApplication.java` — `@EnableConfigurationProperties(WooCommerceProperties.class)`

---

## QA Checklist Results

### ✅ GATE 1: Code Standards (Java)
- [x] No unused imports — verified via grep, all imports used
- [x] No System.out/System.err logging — grep confirmed zero occurrences in production code
- [x] Exceptions properly typed — `ApiException` with HTTP status codes throughout
- [x] Naming conventions: PascalCase classes, camelCase methods — fully compliant
- [x] JavaDoc present on all public classes/methods — **exceeds standard**, comprehensive class-level security/trust-model docs

### ✅ GATE 2: Security (Load-Bearing for Kabir Review)

#### 2.1 Secret Storage ✅
- **V29 migration:** `encrypted_webhook_secret TEXT NOT NULL`, AES-256-GCM ciphertext only (line 26)
- **Encryption key:** Distinct from all other keys (`influora.woocommerce.token-encryption-key`), 256-bit enforced in `WooCommerceIntegrationService:65`
- **No plaintext paths:** Grepped codebase — zero direct writes to `encrypted_webhook_secret` column outside `WooCommerceIntegrationService.encrypt()`
- **Audit logging:** Never logs secret value, only workspace/site/outcome (`WOOCOMMERCE_WEBHOOK_SECRET_STORED` with `SENSITIVE` tier, line 105-112)
- **Round-trip test present:** `WooCommerceIntegrationServiceTest.testEncryptDecryptRoundTrip` (lines 143-155) — feeds REAL `connect()` output into REAL `decryptSecret()` input, proves semantic integrity (Wave C4 lesson applied)

#### 2.2 Signature Verification (HMAC-SHA256) ✅
- **Verify-before-parse discipline confirmed:**
  - Line 178: `signatureVerifier.verify(rawPayload, signature, secret)` 
  - Line 190: `WooCommerceOrderWebhookPayload.parse(rawPayload)` — **JSON parsing happens AFTER verify returns true**
  - Ordering difference from Shopify (resolve-before-verify vs verify-before-resolve) is **documented and justified** in controller javadoc lines 55-67: site resolution is a header+DB lookup (not payload parsing), so "verify raw body before any parsing" discipline is preserved
- **Constant-time comparison:** `WooCommerceWebhookSignatureVerifier:77-85` — XOR accumulation, same pattern as Shopify/Razorpay
- **Fail-closed:** Null/blank signature OR secret returns `false` (lines 55-60), never throws or falls back
- **Test coverage:** 10 tests in `WooCommerceWebhookSignatureVerifierTest` including tampered payload, missing signature, wrong secret

#### 2.3 Workspace Isolation (D1 Lesson Applied) ✅ ✅ ✅
**This is the load-bearing gate for D2 — confirming D1's HIGH cross-tenant coupon bug is NOT repeated.**

- **Controller always calls workspace-scoped overload:** `WooCommerceWebhookController:246` calls `redemptionService.redeem(workspaceId, couponCode, orderId, orderTotal, null, idempotencyKey)` — 6-arg overload with `workspaceId` first parameter
- **workspaceId source:** Line 207 passes `integration.getWorkspaceId()` — resolved from server-side DB lookup via `findBySiteUrlAndRevokedFalse`, NEVER caller-supplied
- **Legacy 5-arg overload unreachable:** Grepped all `redeem(` call sites — `WooCommerceWebhookController` has zero references to the global 5-arg signature
- **Hostile cross-tenant test confirmed present and passing:**
  - Test: `WooCommerceWebhookControllerTest.receive_hostileWebhook_crossTenantCouponCode_isRejected` (lines 339-387)
  - Scenario: Brand A's legitimately-signed webhook (real signature verified against Brand A's own stored secret) carries a coupon code belonging to Brand B
  - Assertion: Controller passes Brand A's own resolved `WORKSPACE_ID` into `RedemptionService.redeem`, NOT Brand B's workspace
  - Verification: Line 375-382 uses `ArgumentCaptor` to prove the exact 6-arg overload was invoked with `eq(WORKSPACE_ID)` (Brand A's)
  - Also asserts line 386: legacy 5-arg overload `never()` invoked
  - Test **passed** in surefire report: `TEST-com.influora.web.WooCommerceWebhookControllerTest.xml` shows `receive_hostileWebhook_crossTenantCouponCode_isRejected` with 0 failures

#### 2.4 Idempotency ✅
- **Two-layer dedup:** Same pattern as Shopify D1
  1. `IdempotencyService.executeOnce` wraps entire webhook operation (line 201-211)
  2. `RedemptionService.redeem`'s own internal idempotency key check
- **Shared derived key:** `deriveIdempotencyKey(siteUrl, topic, orderId)` (line 198) — SHA-256 hash of stable fields, same key passed to both layers
- **Replay test:** `WooCommerceWebhookControllerTest` line 212-220 confirms `AlreadyCompletedException` results in clean 200 no-op

#### 2.5 Input Validation ✅
- **Site URL normalization:** `WooCommerceSiteUrl.normalize` (line 166) — scheme+host lowercase, no path/query/fragment, rejects malformed URLs
- **No SSRF vector:** This integration never makes outbound calls to the brand's site (webhook-receive-only), confirmed in `WooCommerceConnectController` javadoc lines 34-40
- **JSON parsing error handling:** `WooCommerceOrderWebhookPayload.parse` throws typed `ApiException("INVALID_WEBHOOK_PAYLOAD", 400)` on malformed JSON/missing fields (lines 50-87)

### ✅ GATE 3: Architecture Compliance
- **Repository pattern:** `WooCommerceIntegrationRepository extends JpaRepository` — follows codebase convention
- **Service layer:** `WooCommerceIntegrationService` for business logic, controller delegates
- **Resolve-then-scope:** `findBySiteUrlAndRevokedFalse` (repository line 23) is documented as the ONLY exception to workspace-scoped queries (public webhook has no workspace principal until AFTER this lookup) — mirrors `ShopifyIntegrationRepository.findByShopDomainAndRevokedFalse`
- **DTO usage:** `WooCommerceDtos.WooCommerceConnectRequest/Response` — clean request/response separation
- **Exception handling:** All domain exceptions typed via `ApiException` with HTTP status codes

### ✅ GATE 4: Rate Limiting (Non-Blocking Documentation Gap)
- **Bucket assignment confirmed:** `AuthRateLimitFilter:189` — `/webhooks/woocommerce` routes to `"tracking"` bucket
- **Shared limitation documented:** Controller javadoc lines 96-105 explicitly states this reuses the SAME per-IP-only bucket as `/webhooks/shopify` and `/webhooks/redemption`, AND references Kabir's D1 non-blocking finding (`task_568d968e`) as the source of the known limitation
- **Test coverage:** `AuthRateLimitFilterWooCommerceBucketTest` (6 tests) proves bucket sharing behavior — same IP hitting `/webhooks/woocommerce` and `/webhooks/shopify` exhausts a single shared budget
- **QA verdict:** This is NOT a new instance of the problem — it's the SAME already-tracked limitation Kabir documented for D1. A proper fix (per-site/workspace scoping instead of per-IP-only) belongs in `AuthRateLimitFilter` once, for all webhook surfaces simultaneously, not piecemeal per integration. **Non-blocking for D2 merge** per Kabir's own D1 verdict ("follow-up, non-blocking").

### ✅ GATE 5: Database Schema (V29 Migration)
- **ULID primary key:** `id VARCHAR(26)` — matches codebase convention
- **Workspace FK:** `workspace_id VARCHAR(26) NOT NULL` with `CONSTRAINT fk_woocommerce_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id)` — enforced referential integrity
- **UNIQUE constraint on site_url:** Line 32 `UNIQUE KEY uq_woocommerce_site_url (site_url)` — prevents duplicate connections per site, also the indexed webhook-time lookup key
- **Encrypted secret storage:** `encrypted_webhook_secret TEXT NOT NULL` — AES-256-GCM ciphertext, base64-encoded (IV prepended)
- **Timestamp discipline:** `connected_at/created_at/updated_at TIMESTAMP` with app-controlled values (`Instant.now()` in entity builder), no `DEFAULT NOW()` on app-controlled columns — matches V27 Shopify precedent
- **Collation:** `utf8mb4/utf8mb4_unicode_ci` — matches all other tables
- **CTO ruling documented:** Migration header lines 19-21 references `wiki/decisions/2026-07-06-phase2-timescaledb-datastore.md` for MySQL discipline

### ✅ GATE 6: Test Quality (74 Tests, All Passing)
- **Round-trip encryption test:** `WooCommerceIntegrationServiceTest.testEncryptDecryptRoundTrip` feeds real write path into real read path — **Wave C4 lesson applied from the start**
- **Cross-contamination test:** `testRoundTripDoesNotCrossContaminateDifferentSecrets` proves two sites' ciphertexts never collide
- **Hostile cross-tenant test:** `WooCommerceWebhookControllerTest.receive_hostileWebhook_crossTenantCouponCode_isRejected` — **D1 HIGH regression test mirrored for WooCommerce**
- **Signature verification tests:** Invalid/missing/tampered signature all rejected before any processing
- **Idempotency replay test:** Duplicate delivery results in clean no-op
- **JSON parsing edge cases:** `WooCommerceOrderWebhookPayloadTest` covers malformed JSON, missing fields, numeric precision
- **URL normalization tests:** `WooCommerceSiteUrlTest` covers case variations, trailing slash, port handling, malformed URLs

---

## Security Decision Scrutiny (Per Orchestrator's Directive)

### Decision: Resolve Site BEFORE Signature Verification

**Vikram's claim:** "Unlike Shopify (single app-level secret verified generically), WooCommerce has no OAuth flow — each brand configures their OWN webhook secret per-integration. The integration is resolved BY SITE URL *before* signature verification (a deliberate ordering difference from Shopify/Razorpay)."

**QA analysis — APPROVED reasoning:**

1. **Is resolving-before-verifying itself a vulnerability?**
   - **NO.** The resolve step (`WooCommerceWebhookController:166-175`) is:
     - Header read: `X-WC-Webhook-Source` (line 148)
     - URL normalization: `WooCommerceSiteUrl.normalize(siteUrlHeader)` (line 166) — pure string transformation, no DB writes, no side effects
     - Indexed DB lookup: `integrationRepository.findBySiteUrlAndRevokedFalse(normalizedSiteUrl)` (line 168-175)
   - **None of these operations parse the JSON payload** — the raw body string is never handed to a JSON parser until line 190, AFTER `signatureVerifier.verify` returns `true` (line 178)
   - Controller javadoc lines 55-67 explicitly documents this ordering difference and why it preserves "verify raw body before any parsing/dispatch" discipline

2. **Does the resolve step do anything with attacker-controlled input before signature check?**
   - The site URL header IS attacker-controlled, BUT:
     - Normalization is a pure string operation (scheme+host extraction, lowercasing) — no external calls, no reflection, no code execution
     - DB lookup is a parameterized query (`findBySiteUrlAndRevokedFalse`) — no SQL injection vector (JPA derived query)
     - Unknown site → 404 `SITE_NOT_CONNECTED` (line 172-175) — rejection happens strictly BEFORE any secret is read from the DB (line 177) or any signature is checked (line 178)

3. **Timing side-channel on whether a site URL is registered?**
   - **ACCEPTED, documented as inherent to the trust model:** An attacker CAN distinguish "site not connected" (404 before signature check) from "site connected but wrong signature" (401 after signature check)
   - This is NOT a new leak — it's the same enumeration oracle every public webhook endpoint has when the caller-supplied identifier (shop domain, site URL) resolves server-side. Shopify's `ShopifyWebhookController` has the identical shape post-D1-fix: unknown shop → 404, known shop + bad signature → 401
   - The ALTERNATIVE (verify signature generically BEFORE resolving the site) is IMPOSSIBLE here because there is no app-level secret to verify against — each site has its own per-integration secret, so the controller MUST know which site's secret to check before it can verify at all
   - QA verdict: **Accepted as inherent to WooCommerce's no-OAuth trust model**, documented in controller javadoc

4. **Payload JSON parsing genuinely only happens AFTER signature verification succeeds?**
   - **CONFIRMED.** Code flow in `WooCommerceWebhookController.receive`:
     - Line 152-155: Header validation (no payload parsing)
     - Line 166-175: Site resolution (header + DB lookup, no payload parsing)
     - Line 178-181: **Signature verification** — `signatureVerifier.verify(rawPayload, signature, secret)` — the `rawPayload` string is passed to the verifier, but the verifier only computes an HMAC over the bytes, it never parses JSON (confirmed in `WooCommerceWebhookSignatureVerifier:65-70`)
     - Line 190: **`WooCommerceOrderWebhookPayload.parse(rawPayload)`** — this is the FIRST time a JSON parser touches the payload
   - If signature verification fails (line 178 returns `false`), line 179-180 throws `ApiException` and control never reaches line 190
   - **QA verdict: APPROVED** — parse-after-verify discipline preserved despite the ordering difference

### Decision: Rate-Limiting Reuses "tracking" Bucket

**Vikram's claim:** "Deliberately reused the 'tracking'/'meta-oauth' buckets, documenting the same per-IP-only limitation Kabir flagged for D1 (`task_568d968e`) rather than introducing a NEW instance."

**QA analysis — APPROVED reasoning:**

1. **Is this genuinely documented, not silently copied?**
   - **YES.** Controller javadoc lines 96-105 explicitly:
     - States `/webhooks/woocommerce` joins the SAME `"tracking"` bucket as Shopify/redemption
     - References Kabir's D1 non-blocking finding by task ID
     - States "reusing the same bucket here... does not introduce a NEW instance of that problem — it is the same already-tracked limitation"
     - Proposes the fix belongs in `AuthRateLimitFilter` once for all surfaces, not piecemeal
   - Test `AuthRateLimitFilterWooCommerceBucketTest` (lines 65-83) explicitly asserts bucket-sharing behavior
   - **QA verdict:** Documented at the required depth, not a silent copy

2. **Should D2 have invented a per-site bucket instead?**
   - **NO.** That would create an INCONSISTENT state: WooCommerce per-site, Shopify per-IP-only, redemption per-IP-only
   - A proper fix requires scoping ALL webhook buckets by resolved-workspace/site IN ADDITION TO IP, not just WooCommerce
   - Kabir's D1 verdict already rated this LOW-to-MEDIUM, non-blocking, contingent on unverified edge/LB configuration
   - **QA verdict:** Reusing the existing bucket is the correct tactical decision; fixing the limitation is a strategic cross-cutting task outside D2's scope

### Decision: AES-256-GCM Secret Storage Matches Shopify

**Vikram's claim:** "Encrypted webhook secret storage matches Shopify's established discipline."

**QA analysis — CONFIRMED:**

- Same cipher: `AES/GCM/NoPadding` (line 42)
- Same key length: 32 bytes enforced (line 65)
- Same IV handling: 12-byte random IV prepended to ciphertext, base64-encoded combined blob (lines 152-161)
- Same round-trip test discipline: `WooCommerceIntegrationServiceTest.testEncryptDecryptRoundTrip` feeds real write into real read
- Distinct encryption key: `influora.woocommerce.token-encryption-key` ≠ every other key (Shopify/Meta/JWT/stream/etc.) — confirmed in `application.yml:189`

---

## Test Results Verification

**Orchestrator-confirmed ground truth (independent of Vikram's report):**
```
mvn -o -f influora-api clean test
Tests run: 561, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**D2-specific test files confirmed present in surefire-reports:**
- `TEST-com.influora.web.WooCommerceWebhookControllerTest.xml` — 15 tests, 0 failures
- `TEST-com.influora.integration.woocommerce.WooCommerceIntegrationServiceTest.xml` — 12 tests, 0 failures
- `TEST-com.influora.integration.woocommerce.webhook.WooCommerceWebhookSignatureVerifierTest.xml` — 10 tests, 0 failures
- `TEST-com.influora.integration.woocommerce.webhook.WooCommerceOrderWebhookPayloadTest.xml` — 10 tests, 0 failures
- `TEST-com.influora.web.WooCommerceConnectControllerTest.xml` — 11 tests, 0 failures
- `TEST-com.influora.integration.woocommerce.WooCommerceSiteUrlTest.xml` — 10 tests, 0 failures
- `TEST-com.influora.security.AuthRateLimitFilterWooCommerceBucketTest.xml` — 6 tests, 0 failures

**Total D2 tests:** 74 (matches Vikram's handoff claim)

**Hostile cross-tenant test confirmed executed:**
```xml
<testcase name="receive_hostileWebhook_crossTenantCouponCode_isRejected" 
          classname="com.influora.web.WooCommerceWebhookControllerTest" 
          time="0.006"/>
```
Zero failures, zero errors — test PASSED.

---

## Issues Found

**NONE.** Zero critical, zero high, zero medium, zero low blocking issues.

---

## Non-Blocking Observations for Kabir's Load-Bearing Review

### 1. Brand-Configurable Secret Trust Model (NEW Surface)
WooCommerce's per-integration secret model is STRUCTURALLY DIFFERENT from Shopify/Razorpay:
- Shopify: OAuth-issued token, app-level webhook secret — both controlled by the platform
- Razorpay: API key/secret issued by Razorpay, webhook signature verified against a Razorpay-controlled value
- **WooCommerce: Brand generates their OWN webhook secret in their WooCommerce admin, submits it to us via `/woocommerce/connect`**

**Security implications for Kabir's review:**
- This is the FIRST integration where a brand supplies a secret we store and later use to trust inbound requests
- A malicious brand could:
  1. Generate a weak secret (e.g., "password123")
  2. Register their site with that weak secret
  3. An attacker who guesses/brute-forces that weak secret could forge webhooks for that brand
- **Mitigation in place:** We never VALIDATE the strength of the brand-supplied secret (no minimum length, no entropy check) — this is ACCEPTED RISK per the "webhook-receive-only, no OAuth" trust model WooCommerce's own documentation establishes
- **Flag for Kabir:** Is a minimum-length requirement (e.g., 32 chars) warranted, or is this accepted as the brand's own responsibility? Current implementation trusts whatever the brand provides.

### 2. Resolve-Before-Verify Enumeration Oracle
As analyzed above, an attacker CAN distinguish "site not connected" (404) from "site connected but wrong signature" (401). This leaks whether a given site URL has an active WooCommerce connection.

**Mitigation in place:** None — this is documented as inherent to WooCommerce's no-OAuth trust model.

**Flag for Kabir:** Is this acceptable, or should both failure modes collapse to a single generic rejection (e.g., 401 for both unknown-site AND bad-signature)? Current implementation prioritizes developer ergonomics (404 = "you haven't connected this site yet" is a useful signal for legitimate brands troubleshooting setup).

### 3. Rate-Limit Bucket Cross-Tenant Exhaustion (EXISTING LIMITATION)
Per-IP-only keying means Brand A can spoof Brand B's known webhook-relay IP (if edge/LB doesn't strip `X-Forwarded-For`) to exhaust Brand B's `/webhooks/woocommerce` rate budget.

**Mitigation in place:** Documented as reusing the SAME limitation Kabir already flagged for D1. No per-site/workspace scoping added yet.

**Flag for Kabir:** Should D2 be blocked pending a fix to `AuthRateLimitFilter`, or is the existing LOW-MEDIUM non-blocking verdict still applicable?

---

## Verdict

**✅ APPROVED** for Kabir load-bearing security review.

All QA gates passed. D1's HIGH cross-tenant coupon bug is NOT repeated — workspace-scoped redemption is built-in from the start, proven by hostile test. Encryption, signature verification, idempotency, and test quality all meet or exceed codebase standards.

---

## Next Steps

1. **Kabir load-bearing review** — focus on:
   - Brand-configurable secret trust model (NEW surface)
   - Resolve-before-verify enumeration oracle (accepted as inherent to WooCommerce's model, but flag if unacceptable)
   - Rate-limit bucket scoping (already-tracked D1 limitation, confirm non-blocking verdict still applies)
   - Adversarial re-probe of the hostile cross-tenant test (same red-team rigor as D1's final re-confirm)

2. **Meera local build verification** — `mvn -o -f influora-api clean test` + `npm run build` (if frontend touched) + curl check on `/webhooks/woocommerce` endpoint

3. **Arjun:** Route to Kabir with this QA report + the three non-blocking observations flagged above

---

**Kavya Reddy, QA Lead**  
Wave D task D2 — WooCommerce webhook integration  
2026-07-07
