# Red-Team Review: Wave D task D2 — WooCommerce Webhook Integration
**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team / OWASP Security)
**Status:** PASS — cleared for Meera's live-MySQL V29 check

---

## Scope

Adversarial review of the WooCommerce webhook integration, per orchestrator directive and Kavya's QA (`wiki/errors/wave-d2-woocommerce-integration-QA.md`, APPROVED). Files traced directly against source, not taken on the QA report's word:

- `influora-api/src/main/java/com/influora/web/WooCommerceConnectController.java`
- `influora-api/src/main/java/com/influora/web/WooCommerceWebhookController.java`
- `influora-api/src/main/java/com/influora/integration/woocommerce/WooCommerceIntegrationService.java`
- `influora-api/src/main/java/com/influora/integration/woocommerce/webhook/WooCommerceWebhookSignatureVerifier.java`
- `influora-api/src/main/java/com/influora/repository/WooCommerceIntegrationRepository.java`
- `influora-api/src/main/java/com/influora/domain/entity/WooCommerceIntegration.java`
- `influora-api/src/main/resources/db/migration/V29__woocommerce_integrations.sql`
- `influora-api/src/main/java/com/influora/service/tracking/RedemptionService.java` (traced, not just cited)
- `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java` (bucket assignment, traced)
- `influora-api/src/test/java/com/influora/web/WooCommerceWebhookControllerTest.java`

Four directed probes per the brief, below.

---

## Probe 1: Brand-configurable secret trust model — no strength validation

**Confirmed.** `WooCommerceConnectController.connect` (lines 68-75) validates only non-null/non-blank:

```java
String webhookSecret = request == null ? null : request.webhookSecret();
if (webhookSecret == null || webhookSecret.isBlank()) {
    throw new ApiException("WEBHOOK_SECRET_REQUIRED", ..., HttpStatus.BAD_REQUEST);
}
integrationService.connect(workspace.getId(), normalizedSiteUrl, webhookSecret);
```

No minimum length, no entropy check, no rejection of common/weak values (`"1234"`, shop name, `"password"`). A brand could submit a 1-character secret and it would be AES-256-GCM encrypted and trusted forever after.

**Is this a third-party risk, or self-inflicted?**

Traced the blast radius of a compromised/guessed WooCommerce secret:
1. `WooCommerceWebhookSignatureVerifier.verify` (lines 54-63) checks the HMAC using ONLY the secret resolved via `integration.getWorkspaceId()` for the site that matched `X-WC-Webhook-Source`.
2. The resolved `workspaceId` is passed into `RedemptionService.redeem(workspaceId, ...)` — the 6-arg overload.
3. `RedemptionService.validateCode` (line 410-415), when `workspaceId != null`, calls `couponCodeRepository.findByWorkspaceIdAndCode(workspaceId, normalized)` — a query scoped by `UNIQUE(workspace_id, code)` (V24 migration, line 41).
4. There is no code path anywhere that lets a forged webhook against Brand A's weak secret reach Brand B's `workspace_id`. `workspaceId` here is 100% a function of which `site_url` row the secret decrypted against — an attacker who breaks Brand A's secret can only ever forge webhooks that resolve to Brand A's own `workspaceId`.

**Verdict: genuinely self-inflicted, not a cross-tenant/third-party vector.** A brute-forced weak secret lets an attacker forge orders/redemptions against the OWNING brand's own coupons/campaigns/creator payouts — real harm (a malicious third party could deplete Brand A's own coupon usage_limit, or worse, trigger real `AffiliateEarningsService.recordEarning` accrual against Brand A's own creators from fabricated orders) but it stays inside Brand A's blast radius. It cannot be leveraged to touch Brand B's data, confirmed by the workspace-scoped query chain above, same enforcement point that closes D1's HIGH.

**Recommendation (non-blocking, but should be fixed before wide brand rollout):** Add a minimum-length gate to `WooCommerceConnectController.connect` — 32 chars is reasonable and matches WooCommerce's own auto-generated secret length (WooCommerce's admin auto-generates a 50-char secret by default; brands who accept the default are already safe, this only protects brands who override it with something trivial). This is a real gap, not a false-positive: the actual financial harm above (forged orders triggering real affiliate commission accrual) is a legitimate one-brand-scoped impact, not merely theoretical. Recommend: reject on submit if `webhookSecret.length() < 16` at minimum (matches typical HMAC-key-strength floors), 32 preferred. This does not block D2 merge — it is a hardening follow-up, same tier as D1's rate-limit finding, because the risk is contained to the submitting brand's own account and requires that brand's own secret to be brute-forced or leaked first (attacker still needs the site URL AND enough requests to defeat rate-limiting, itself bucketed — see Probe 4).

---

## Probe 2: Resolve-before-verify enumeration oracle

**Confirmed as described, re-verified directly in `WooCommerceWebhookController.receive`:**
- Unknown site → line 168-175, `orElseThrow` → `404 SITE_NOT_CONNECTED`, thrown BEFORE line 177 (`decryptSecret`) or line 178 (`signatureVerifier.verify`) ever execute.
- Known site + bad signature → line 178-181, `401 INVALID_WEBHOOK_SIGNATURE`, only reachable after a successful DB resolve.

These are genuinely two distinguishable HTTP outcomes (404 vs 401) with distinguishable bodies (`SITE_NOT_CONNECTED` vs `INVALID_WEBHOOK_SIGNATURE`). An attacker probing arbitrary site URLs against this endpoint CAN learn which site URLs have an active WooCommerce connection in this system.

**Severity assessment:**
- What does this leak concretely? Only "this URL has *a* WooCommerce integration connected to Influora" — not which workspace, not the secret, not any coupon/campaign data, not whether the connection is healthy. The `X-WC-Webhook-Source` value is a store's own public storefront URL — typically already public/known (it's the brand's own e-commerce site), so the "secret" being leaked here is closer to "is this brand a customer of ours" than to any credential material.
- Compare to Shopify's shape: Shopify's `ShopifyWebhookController` has the IDENTICAL oracle post-D1-fix (unknown shop → 404, known shop + bad HMAC → 401) — confirmed by re-reading D1's final verdict doc and Kavya's cross-reference, this is not a new class of leak introduced by D2, it is the SAME shape already accepted for D1.
- Is collapsing both branches to one generic rejection actually achievable here? No — unlike Shopify (one app-level secret, so a generic "verify signature, THEN existence-check" ordering is possible), WooCommerce structurally cannot verify a signature without first knowing whose secret to check against. Collapsing 404-vs-401 into a single status code (e.g., always 401) is possible cosmetically, but doesn't eliminate the oracle — timing alone (DB lookup+miss vs DB lookup+hit+HMAC compute) still leaks the same bit unless deliberately padded, and padding timing for a low-value "is this domain a customer" signal is not proportionate effort.

**Verdict: accepted as inherent to the no-OAuth trust model, consistent with the already-accepted D1 precedent.** Not a merge blocker. The leaked bit (site-is-connected) is low sensitivity and structurally unavoidable without disproportionate engineering (constant-time DB-miss padding) for a boolean that is not itself a secret or a path to one.

---

## Probe 3: Adversarial re-probe of the hostile cross-tenant test

Did not just confirm the test exists — traced the actual attack path myself against the real (non-mocked) enforcement code, same rigor as the D1 final re-confirm.

**Attack scenario:** Brand A registers `https://brand-a-store.example.com` with their own real webhook secret. Brand A crafts a WooCommerce order payload with `coupon_lines[0].code = "BRAND_B_SUMMER25"` (a code that only exists in Brand B's workspace) and sends it to `/webhooks/woocommerce` with a signature that is genuinely valid HMAC-SHA256 over that payload using Brand A's own secret (Brand A is not forging the signature — they own it legitimately).

**Traced end-to-end:**
1. `X-WC-Webhook-Source: https://brand-a-store.example.com` → `WooCommerceSiteUrl.normalize` → `integrationRepository.findBySiteUrlAndRevokedFalse` resolves to Brand A's OWN `WooCommerceIntegration` row → `integration.getWorkspaceId()` = Brand A's workspace. This resolution is 100% server-side/DB-driven; Brand A cannot influence which `workspaceId` comes back regardless of payload content.
2. `signatureVerifier.verify(rawPayload, signature, secret)` — TRUE, because Brand A really does own this secret and really did compute this HMAC. Signature verification is not the defense here (correctly so — Brand A is a legitimate, authenticated sender of the webhook, just supplying a hostile payload body).
3. `WooCommerceOrderWebhookPayload.parse` extracts `couponCode = "BRAND_B_SUMMER25"`.
4. `redeemViaWooCommerceOrder(integration.getWorkspaceId(), "BRAND_B_SUMMER25", ...)` — line 246 — calls `redemptionService.redeem(workspaceId, couponCode, ...)` — the 6-arg workspace-scoped overload, ALWAYS, no conditional path to the 5-arg overload exists in this controller (grepped: zero other `redeem(` call sites in `WooCommerceWebhookController`).
5. `RedemptionService.redeem` → `doRedeem` → `validateCode(workspaceId, code)` (line 410) — `workspaceId` is Brand A's, non-null, so line 413-415 takes the `findByWorkspaceIdAndCode(workspaceId, normalized)` branch, NOT the global `findByCode` branch.
6. `CouponCodeRepository.findByWorkspaceIdAndCode("<Brand A's workspace>", "BRAND_B_SUMMER25")` — the coupon row for that code actually has `workspace_id = "<Brand B's workspace>"` (enforced unique per `UNIQUE(workspace_id, code)`, V24 line 41) — the derived query's `WHERE workspace_id = ? AND code = ?` cannot match a row whose `workspace_id` differs, so this returns empty.
7. Empty → `orElseThrow` → `ApiException("INVALID_CODE", ..., 404)` at line 416-418 of `RedemptionService`. Zero mutation: `redemptionRepository.save`, `coupon.incrementUsageCount`, `affiliateEarningsService.recordEarning`, `auditLogService.recordMoneyEvent` are all unreached — they sit after the `validateCode` call inside `doRedeem`, which never returns.
8. The exception propagates out of the `IdempotencyService.executeOnce` lambda uncaught (not one of the two caught idempotency-replay exception types) and out of `WooCommerceWebhookController.receive` as a real error — this does NOT resolve to a quiet 200.

**This is the same enforcement point, same query, same unique constraint that closed D1's HIGH** — not a superficial pattern match, an actual re-trace confirms the mechanism is real and correctly reached from D2's call site. The `WooCommerceWebhookControllerTest.receive_hostileWebhook_crossTenantCouponCode_isRejected` test mocks `RedemptionService` (correctly — it's a controller-scoped unit test, and `RedemptionService`'s own cross-tenant enforcement is independently proven by `RedemptionServiceTest`, not re-proven here) but the mock's stubbed behavior (`thenThrow(INVALID_CODE)`) matches EXACTLY what the real `validateCode` path does, confirmed by reading it directly rather than trusting the test's docstring claim.

**No distinguishing signal on the reject path** — same `INVALID_CODE`/404 used for "code doesn't exist anywhere," collapsing "cross-tenant match" and "no such code" into one response, so this cannot be used to enumerate whether a guessed code string belongs to some other workspace. Verified no separate log line, header, or timing branch splits these two cases in `RedemptionService.validateCode`.

**Verdict: attack path genuinely closed.** D1's lesson is truly applied from day one here, not merely claimed.

---

## Probe 4: Rate-limit bucket sharing — does `task_568d968e`'s verdict still apply unchanged

Traced `AuthRateLimitFilter.bucketFor` directly (not taken from javadoc citation):

```
/webhooks/shopify, /webhooks/redemption, /webhooks/woocommerce  -> "tracking" bucket   (line 186-190)
/woocommerce/connect                                             -> "meta-oauth" bucket (line 192, same as Shopify OAuth surface)
```

Bucket keying is unchanged: still `clientIp(request) + "|" + bucket` (same method, not touched by D2's diff — confirmed no edits to the key-derivation function itself, only to `bucketFor`'s path-matching branches). The underlying limitation from `task_568d968e` (per-IP-only keying, `X-Forwarded-For` trusted unconditionally with no verified trusted-proxy hop-count config) is a property of the key-derivation function, which D2 did not touch at all — it only added `/webhooks/woocommerce` and `/woocommerce/connect` as two more paths routed into the SAME two pre-existing buckets Shopify already uses.

**Confirmed: this is not a new instance, and does not make the underlying limitation worse.** D2 adds attack surface area (one more endpoint an IP-spoofing attacker could target) but does not change the exploitability, blast radius, or root cause of the original finding — it's the same bucket, same key function, same unresolved `X-Forwarded-For` trust assumption. `AuthRateLimitFilterWooCommerceBucketTest` (6 tests) proves the bucket-sharing behavior for the new paths specifically, consistent with what `AuthRateLimitFilterShopifyBucketTest` already proved for D1.

**Verdict: `task_568d968e`'s non-blocking LOW-to-MEDIUM verdict applies unchanged.** No new finding, no severity escalation. The eventual fix (per-site/workspace scoping in `AuthRateLimitFilter`, or resolving trusted-proxy hop count for `X-Forwarded-For`) remains a single, cross-cutting follow-up for all webhook surfaces at once — correctly not attempted piecemeal in D2.

---

## Additional spot-checks

- **AES-256-GCM implementation** (`WooCommerceIntegrationService`): distinct 32-byte key enforced at startup (`decodeKey`, throws `IllegalStateException` if misconfigured — fails closed, app won't boot with a bad key), random 12-byte IV per encryption via `SecureRandom`, IV prepended not reused — no ECB/static-IV mistakes. Matches Shopify's established pattern exactly, independently re-verified rather than taken on QA's word.
- **Constant-time signature comparison**: `constantTimeEquals` (lines 77-85) — XOR-accumulate over full length, length-mismatch short-circuits before the loop (a length check leaks length via timing, but HMAC-SHA256-base64 output length is fixed/public, so this leaks nothing).
- **Migration V29**: `UNIQUE KEY uq_woocommerce_site_url (site_url)` and `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)` both present and correctly typed (`VARCHAR(26)` ULID, matches convention). No plaintext secret column exists.
- **No SSRF vector**: confirmed directly — grepped `WooCommerceConnectController`/`WooCommerceIntegrationService`/`WooCommerceWebhookController` for any outbound HTTP client usage (`RestClient`/`WebClient`/`HttpClient`) — none exists. This integration is genuinely receive-only; the submitted site URL is never dialed.
- **Idempotency**: two-layer dedup (`IdempotencyService.executeOnce` wrapping the whole handler + `RedemptionService`'s own idempotency-key uniqueness) traced and consistent with D1/D4's already-verified pattern; SHA-256 derived key means no attacker-suppliable idempotency-key squatting vector (endpoint takes no caller-supplied idempotency key at all).

No new findings beyond the four probes above.

---

## Verdict

**PASS — clean, with one non-blocking hardening recommendation.**

1. Brand-configurable secret trust model: no strength validation exists; confirmed self-inflicted-only risk (no cross-tenant path), but recommend adding a minimum-length gate (16 min / 32 preferred) to `WooCommerceConnectController.connect` as a follow-up hardening item — not a merge blocker.
2. Resolve-before-verify enumeration oracle: confirmed real, low severity, structurally unavoidable given WooCommerce's no-OAuth trust model, consistent with the already-accepted Shopify/D1 precedent. Accepted as-is.
3. Hostile cross-tenant test: re-traced end-to-end against real (non-mocked) `RedemptionService`/`CouponCodeRepository` enforcement, not just confirmed present. Attack path is genuinely closed via `UNIQUE(workspace_id, code)` + `findByWorkspaceIdAndCode`, same mechanism that closed D1's HIGH. No new bypass found.
4. Rate-limit bucket sharing: confirmed `task_568d968e`'s non-blocking LOW-to-MEDIUM verdict applies unchanged — D2 reuses the same bucket/key function, does not worsen the underlying per-IP-only limitation.

D2 is cleared. No HIGH/CRITICAL findings. One non-blocking hardening recommendation (secret minimum length) logged for follow-up, same tier as D1's still-open rate-limit-bucket-scoping follow-up.

**NEXT:** Meera live-MySQL V29 verification. After that, D2 joins D1/D3/D4 as fully cleared through every gate.

---

**Kabir, Red-Team / OWASP Security**
Wave D task D2 — WooCommerce webhook integration
2026-07-07
