# Red-Team Review: Wave D task D1 — Shopify OAuth + Order Webhooks

**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team / Offensive Security)
**Input:** Kavya's QA APPROVED (`wiki/errors/wave-d1-shopify-integration-QA.md`), 468/468 tests
**Status:** ❌ **FAIL — BLOCKED** (1 High, 1 Medium)

---

## Scope

`influora-api/src/main/java/com/influora/integration/shopify/**`,
`web/ShopifyConnectController.java`, `web/ShopifyWebhookController.java`,
`domain/entity/ShopifyIntegration.java`, `repository/ShopifyIntegrationRepository.java`,
`repository/CouponCodeRepository.java`, `service/tracking/RedemptionService.java`,
migration `V27__shopify_integrations.sql`, `config/SecurityConfig.java`,
`security/AuthRateLimitFilter.java`.

Adversarial checks run: webhook signature bypass (wrong secret, tampered body, missing header,
old-signature replay, base64/hex confusion, dev-placeholder fail-closed), SSRF gate bypass
(double-encoding, homoglyths, trailing dot, mixed case, IP literals, regex `$`-anchor
trailing-newline trick), token encryption + workspace isolation, OAuth CSRF state reuse/cross-shop
swap, webhook idempotency/replay.

---

## Gate 1 — Webhook Signature Verification: PASS, no bypass found

`ShopifyWebhookSignatureVerifier.verify` (lines 45-59): fails closed on null/blank signature,
fails closed on null/blank/`"REPLACE_WITH_"`-prefixed secret (dev-placeholder guard confirmed
correct against `application.yml`'s actual default `REPLACE_WITH_YOUR_SHOPIFY_WEBHOOK_SECRET`),
base64 encoding confirmed correct against Shopify's real header contract, constant-time comparison
verbatim-identical to the Razorpay verifier. Verified BEFORE parsing in
`ShopifyWebhookController.receive` (line 122, first statement). No wrong-secret, tampered-payload,
missing-header, or hex/base64-confusion bypass found. Old-signature-replay-with-modified-body is
correctly rejected (HMAC is over the raw body; a modified body no longer matches any prior valid
signature). `constantTimeEquals`'s early-return on length mismatch (line 74) is a theoretical
timing micro-leak of ciphertext-length-equivalent information — same shape as the pre-existing
Razorpay verifier, not a new regression, not blocking.

## Gate 2 — SSRF Gate: PASS, no bypass found

`SHOP_DOMAIN_PATTERN = ^[a-z0-9][a-z0-9-]*\.myshopify\.com$`, applied after `.toLowerCase()`,
called before any URL interpolation. Specifically tried and confirmed rejected:
- Trailing dot (`my-store.myshopify.com.`)
- Trailing `\n`, `\r\n`, and trailing space after the valid suffix (Java's `Matcher.matches()`
  requires full-string consumption, so the classic `$`-anchor-before-final-newline gotcha does
  NOT create a bypass here — confirmed via isolated regex test, all rejected)
- Mixed case (`.toLowerCase()` normalizes before matching)
- Unicode homoglyphs (pattern is ASCII-only `[a-z0-9-]`; no IDN/punycode normalization occurs
  that could smuggle a homoglyph through as a look-alike ASCII character — non-ASCII input fails
  the character class outright)
- IP-literal shop domains (pattern requires literal `.myshopify.com` suffix, no numeric-host path)
- Suffix-spoofing (`my-store.myshopify.com.evil.com`) and protocol/path-prefixed values — both
  genuinely tested in `ShopifyOAuthServiceTest` (lines 82-94), not just claimed

No bypass found. `RestClient.builder().build()` follows redirects by default, which is a
theoretical SSRF-via-redirect concern in general, but the validated host is always
`*.myshopify.com` — an attacker supplying `shop` cannot control Shopify's own redirect target, so
this is not exploitable through this parameter. Not blocking.

## Gate 3 — OAuth CSRF State: PASS, no bypass found

`ShopifyOAuthStateStore.consume` (lines 42-54): single-use (`Map.remove`, not `get`), TTL-checked
(10 min), and requires exact match on BOTH `userId` AND case-insensitive `shopDomain`. Tried:
state-reuse (blocked by `remove`-then-check-null), cross-shop-domain-swap (blocked by the
`shopDomain` equality check on consume — a state minted for shop A cannot be redeemed against shop
B). No bypass found.

## Gate 4 — Idempotency: PASS, no bypass found

Two independent layers confirmed to share one derived key
(`sha256(shopDomain|topic|orderId)`): the controller's `IdempotencyService.executeOnce` wraps the
whole operation, and `RedemptionService.redeem`'s own `findByIdempotencyKey`-first-then-reserve
path (race-safe via the same shared `executeOnce` primitive) guards the redemption row itself. A
replayed webhook delivery is a clean no-op at either layer. No double-processing path found.

## Gate 5 — Token Encryption + Workspace Isolation on the OAuth/storage layer: PASS

`ShopifyTokenStorage` AES-256-GCM round-trip correct, distinct key, no plaintext logged. OAuth
connect/callback flow resolves workspace server-side via `BrandContextService.requireBrandWorkspace`
and never trusts a caller-supplied workspace id. `findByWorkspaceIdAndRevokedFalse` /
`findByShopDomainAndRevokedFalse` split matches the documented resolve-then-scope exception
pattern used elsewhere (`CouponCodeRepository.findByCode`).

---

## [HIGH] Cross-tenant coupon redemption via Shopify webhook (new vulnerability, not pre-existing)

**Where:** `influora-api/src/main/java/com/influora/web/ShopifyWebhookController.java` lines
153-170, calling `RedemptionService.redeem(order.discountCode(), ...)` →
`RedemptionService.validateCode` → `CouponCodeRepository.findByCode(code)`.

**Issue:** `ShopifyWebhookController` resolves the calling shop to its owning workspace via
`X-Shopify-Shop-Domain` → `ShopifyIntegrationRepository.findByShopDomainAndRevokedFalse` — a real,
HMAC-authenticated workspace identity that the older `/webhooks/redemption` endpoint never had.
But it then discards that resolved workspace identity before redemption: `discountCode` from the
attacker-controlled JSON body (`order.discount_codes[0].code`, fully controlled by whoever owns
the Shopify store — i.e., any brand who completed their own legitimate OAuth connect) is passed to
`RedemptionService.redeem`, which looks the code up via `CouponCodeRepository.findByCode(code)` —
documented in that repository's own javadoc as global, NOT workspace-scoped, because
`UNIQUE(workspace_id, code)` (not a bare `UNIQUE(code)`) means the *same code string can legally
exist in two different brand workspaces*. `redeem` never cross-checks that the coupon's own
`workspaceId` equals `integration.getWorkspaceId()` (the resolved owner of the shop that sent the
webhook).

The original `/webhooks/conversion`/`/webhooks/redemption` design's "intentionally not
workspace-scoped" decision (documented in `ConversionWebhookController`'s class javadoc) was safe
*only* because there was no workspace signal available at that call site to cross-check against —
the caller had no identity claim beyond the code itself. The Shopify integration is different: it
uniquely identifies the calling workspace via a verified header before ever touching
`RedemptionService`, then throws that identity away. This turns a previously acceptable
design choice into an exploitable authorization gap the moment a second identity signal exists
and isn't checked.

**Impact:** Brand A (having completed their own legitimate Shopify OAuth connect and holding a
validly signed webhook secret for their own store) can send an `orders/paid`/`orders/create`
webhook with `discount_codes[0].code` set to a code string they know or guess belongs to Brand B's
campaign (coupon codes are brand-chosen human-readable slugs like `SUMMER25` — collision across
independent brands choosing common promo-style codes is realistic, not a 1-in-2^128 guess). If it
matches, Brand A's forged order:
- increments Brand B's coupon `usageCount` toward Brand B's `usageLimit` (denial-of-service against
  the real campaign — Brand B's actual customers start seeing `CODE_LIMIT_REACHED`)
- creates a `CouponRedemption` row attributing Brand A's fabricated order/amount to Brand B's
  creator, corrupting Brand B's attribution/analytics and (per `RedemptionService.doRedeem` line
  246) invokes `AffiliateEarningsService.recordEarning` — meaning this can trigger **incorrect
  commission accrual for Brand B's creator based on a fully forged order that never happened on
  Brand B's store**, a financial-integrity issue, not just a data-quality one.

This requires the attacker to hold a real (even if free, self-service) Shopify OAuth connection —
not a fully anonymous attack — but nothing in the signup/connect flow verifies the attacker's own
brand identity against the coupon codes they attempt, and zero rate limiting currently applies to
this endpoint (see Medium finding below), so the cross-tenant code space is brute-forceable.

**Fix:** In `ShopifyWebhookController.receive`, after resolving `integration`, either:
1. (preferred) Pass `integration.getWorkspaceId()` into `RedemptionService.redeem` and add a
   workspace-scoped overload / verification step that confirms the resolved `CouponCode.workspaceId`
   equals the caller's resolved workspace before mutating anything — reject with a generic
   `INVALID_CODE` (404, same status as "doesn't exist," to preserve the existing no-enumeration
   property) if they don't match, or
2. Add `CouponCodeRepository.findByWorkspaceIdAndCode(workspaceId, code)` and use it here instead
   of the global `findByCode`, mirroring the workspace-scoped finder pattern already established
   for brand-authed reads on this same repository.

Either way, the fix must not introduce a status-code distinction that lets an attacker tell
"code exists in another workspace" from "code doesn't exist anywhere" — collapse both to the same
`INVALID_CODE` 404 already used for "no such code."

---

## [MEDIUM] `/webhooks/shopify` and `/shopify/oauth/{authorize,callback}` have zero rate limiting

**Where:** `influora-api/src/main/java/com/influora/security/AuthRateLimitFilter.java`,
`bucketFor` (lines 136-164).

**Issue:** `AuthRateLimitFilter.bucketFor` throttles `/webhooks/redemption`, `/webhooks/conversion`,
`/track/click/*`, and the Meta OAuth GETs, but was never updated for D1's new public surface.
`POST /webhooks/shopify` and `GET /shopify/oauth/authorize` / `/shopify/oauth/callback` all fall
through to `bucketFor`'s final `return null`, meaning **no per-IP throttling applies at all**.

**Impact:** Compounds the High finding above — an attacker can hammer `/webhooks/shopify` with
signed requests (signed with their own legitimate shop's secret) trying different
`discount_codes[0].code` guesses against other brands' coupon keyspace with no throttling, and
separately, `/shopify/oauth/authorize`/`callback` (brand-authenticated but still worth bounding)
have no abuse ceiling the way the equivalent Meta OAuth surface does.

**Fix:** Add `POST /webhooks/shopify` to the existing `"tracking"` bucket (same treatment as
`/webhooks/redemption`), and add `/shopify/oauth/authorize` / `/shopify/oauth/callback` to a
bucket mirroring `"meta-oauth"`.

---

## Non-blocking observations (already flagged by Kavya, assessed here)

- **Enumeration bit on `RedemptionService.redeem`** (`INVALID_CODE` 404 vs.
  `CODE_EXPIRED`/`CODE_LIMIT_REACHED` 400): accepted as-is for the pre-existing
  `/webhooks/redemption` surface (brand-chosen slugs + rate limiting). For the Shopify path this
  is superseded by the High finding above — fixing that finding correctly should also collapse
  "belongs to another workspace" into the same 404 bucket, which is the right generalization of
  this existing behavior, not a new problem to solve separately.
- Two on-disk `V27` migrations initially appeared to collide (`V27__affiliate_earnings_settlement.sql`
  found via glob) — verified this is a stale copy under `influora-api/target/classes/` (build
  output), not a real source conflict. Actual source tree has `V27__shopify_integrations.sql` and
  `V28__affiliate_earnings_settlement.sql`, correctly sequenced. Not a finding.
- `RestClient.builder().build()`'s default-follow-redirects behavior in `exchangeCodeForToken` is a
  theoretical SSRF-via-redirect note, not exploitable through the `shop` parameter since the
  validated host is always `*.myshopify.com` and Shopify's own redirect target is outside caller
  control. Logged for awareness, not blocking.

---

## Verdict

**FAIL — BLOCKED.** One High (cross-tenant coupon redemption / forged-order financial-integrity
issue), one Medium (missing rate limiting on the new public surface). Everything else audited
(signature verification, SSRF gate, OAuth CSRF state, idempotency, token encryption, workspace
isolation on the OAuth/connect side) is clean with no bypass found under adversarial testing.

**Route:** Vikram (backend) — fix High + Medium in
`ShopifyWebhookController`/`RedemptionService`/`CouponCodeRepository` and
`AuthRateLimitFilter.bucketFor`. Re-submit to Kabir for re-test before this clears for Meera's
live-MySQL V27 check. **D1 is NOT cleared; D2/D3 remain blocked** until re-test passes.
