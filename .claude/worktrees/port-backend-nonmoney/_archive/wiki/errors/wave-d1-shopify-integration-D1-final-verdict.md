# Red-Team Re-Confirmation: Wave D task D1 — Shopify Coupon Redemption Fix

**Date:** 2026-07-07
**Reviewer:** Kabir (Red-Team / Offensive Security)
**Input:** My original report (`wiki/errors/wave-d1-shopify-integration-redteam.md`), Kavya's
APPROVED re-QA (`wiki/errors/wave-d1-shopify-integration-D1-fix-QA.md`), 487/487 tests
**Status:** ⚠️ **CONDITIONAL PASS — HIGH closes clean; MEDIUM re-opens as a narrower finding**

---

## Scope of this pass

Adversarial re-probe only (per Wave D1 task), not a full re-audit: (1) re-attempt the original
cross-tenant redemption forgery, (2) hunt for an enumeration side-channel Kavya might have missed,
(3) attempt to defeat/abuse the new rate-limit buckets, (4) confirm the legacy global `redeem`
overload is structurally unreachable from the Shopify path.

---

## Probe 1 — Re-attempt the forged cross-tenant redemption: BLOCKED, confirmed by tracing the exact path

Traced `ShopifyWebhookController.receive` (lines 106-187) → `redeemViaShopifyOrder` (lines
210-217) → `RedemptionService.redeem(workspaceId, code, ...)` 6-arg overload (lines 254-315) →
`doRedeem` (lines 321-382) → `validateCode(workspaceId, code)` (lines 410-430).

`redeemViaShopifyOrder` is a **private** method with exactly one call site (line 169, inside the
`idempotencyService.executeOnce` lambda), and it always passes `integration.getWorkspaceId()` —
the value resolved at line 136-144 from `shopifyIntegrationRepository.findByShopDomainAndRevokedFalse(shopDomain)`,
itself derived from the HMAC-verified request only after `signatureVerifier.verify` (line 122)
has already passed. There is no code path in this controller that calls `redeem(...)` without
that resolved workspace id, and no code path that lets a caller override it — `shopDomain` comes
from a request header, but the workspace id used downstream is the **server-resolved** value from
the DB row, never the header value or any other client-supplied field.

`validateCode` (line 410) branches on `workspaceId == null` — for the Shopify path it is never
null (enforced at line 269-274: blank-but-non-null throws `WORKSPACE_ID_REQUIRED` before reaching
`doRedeem`), so it always takes the `findByWorkspaceIdAndCode(workspaceId, normalized)` branch.

**Re-ran the exact attack scenario from my original report:** Brand A signs a webhook with their
own valid HMAC secret, `X-Shopify-Shop-Domain` set to Brand A's own connected store, payload's
`discount_codes[0].code` set to a string that exists only in Brand B's workspace.
- `signatureVerifier.verify` passes (Brand A's own valid signature).
- `shopifyIntegrationRepository.findByShopDomainAndRevokedFalse` resolves Brand A's own
  `workspaceId` (call it `WS_A`).
- `redeem(WS_A, "BRAND_B_CODE", ...)` → `validateCode(WS_A, "BRAND_B_CODE")` →
  `couponCodeRepository.findByWorkspaceIdAndCode(WS_A, "BRAND_B_CODE")`.
- Since the coupon row's real `workspace_id` is `WS_B`, not `WS_A`, this query — a real
  `WHERE workspace_id = ? AND code = ?` derived query, confirmed by reading the interface method
  signature at `CouponCodeRepository.java` line 54 (Spring Data JPA derives the clause verbatim
  from the method name; no custom `@Query` to second-guess) — returns `Optional.empty()`.
- `.orElseThrow` at line 417-418 throws `INVALID_CODE` 404. No mutation occurs: `doRedeem` is never
  entered, so no `CouponRedemption` row, no `usageCount` increment, no
  `affiliateEarningsService.recordEarning` call.

**Result: attack blocked.** The fix genuinely closes the HIGH finding — not just structurally
plausible, but traced end-to-end against the exact forged-webhook scenario from the original
report, including confirming the money-adjacent side effects (usage count, affiliate earnings) are
unreachable.

---

## Probe 2 — Hunt for an enumeration side-channel Kavya's static read might have missed

Kavya's Gate 1 verified the status code, error body, and query-shape convergence by reading the
code. I looked specifically for signals a static read of the happy path wouldn't surface:

- **Response body byte-for-byte:** both branches of `validateCode`'s `found.orElseThrow` construct
  the *same* `ApiException("INVALID_CODE", "Coupon code not found", HttpStatus.NOT_FOUND)` — same
  object construction, same message string literal, not two different call sites that happen to
  produce equal-looking strings. No diff possible.
- **HTTP headers:** neither branch sets any code-outcome-specific header. The only headers on this
  response path are whatever the global exception handler adds for any `ApiException` (not
  inspected further since it's outcome-agnostic — same handler, same exception type, for both
  "doesn't exist" and "wrong workspace").
- **Timing:** both `findByWorkspaceIdAndCode` and `findByCode` are single indexed-column-set
  lookups (`(workspace_id, code)` composite vs `(code)` — confirmed no N+1, no fallback-then-retry
  logic in `validateCode`, it's a single ternary picking one finder, called once). No loop, no
  second query gated on the first result. I did not have a live MySQL instance in this pass to
  literally measure microsecond deltas, but structurally there is no *conditional extra work* on
  either path that could produce a measurable gap — both are the same query shape against
  differently-scoped indexes on the same table.
- **Log side-channel:** confirmed via grep that `RedemptionService` has **zero** log statements —
  `validateCode`, `doRedeem`, and both `redeem` overloads never call `log.*` at all. The only log
  line in the whole Shopify flow is `ShopifyWebhookController` line 179-183, which fires **only**
  on `IdempotencyService.AlreadyCompletedException`/`AlreadyInProgressException` (a replay, not a
  validation outcome) and logs shop/topic/orderId — never the coupon code or the validation
  result. An attacker with API access (not log access) has no side-channel here regardless; and
  even an attacker with log access would learn nothing about coupon-match outcomes from this flow.
- **429 vs 404 cross-talk:** confirmed the rate limiter (Probe 3 below) fires strictly before
  the controller/service logic — a request that gets rate-limited never reaches `validateCode` at
  all, so there's no way to use rate-limit state as an oracle for whether a prior guess landed in
  someone else's workspace vs. nowhere.

**Result:** no distinguishing signal found. Confirms Kavya's Gate 1 finding under an adversarial
(not just structural) pass.

---

## Probe 3 — Rate-limit bucket exhaustion: **finding — bucket key is per-IP, not per-shop**

Read `AuthRateLimitFilter.bucketFor` (lines 152-185) and the actual key derivation (line 114):

```java
String key = clientIp(request) + "|" + bucket;
```

`clientIp` (lines 204-211):

```java
private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
        int comma = forwarded.indexOf(',');
        return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
}
```

**Two compounding issues, confirmed by reading the code (not assumed):**

1. **The "tracking" bucket is shared across ALL webhook callers keyed only by IP**, not by shop
   domain or workspace. `AuthRateLimitFilterShopifyBucketTest` (lines 66-83) *explicitly tests and
   asserts* this sharing: `shopifyWebhook_sharesTrackingBucketWithRedemption` proves one request to
   `/webhooks/redemption` and one to `/webhooks/shopify` **from the same IP** consume the same
   budget. This is fine when Brand A and Brand B's webhook traffic originates from distinct IPs
   (true for most real Shopify-store-owner setups, where each brand's own server/webhook relay has
   its own egress IP). But nothing in `bucketFor` or `clientIp` ties the bucket key to
   `X-Shopify-Shop-Domain` or the resolved `workspaceId` at all — the rate limiter runs as a
   servlet filter **before** the controller, so it has no way to know which shop a request claims
   to be from even if it wanted to.

2. **`X-Forwarded-For` is trusted unconditionally, with no proxy allowlist anywhere in the
   codebase.** I grepped `application.yml` and the whole `config/` package for
   `forward-headers-strategy`, trusted-proxy config, or any `ForwardedHeaderFilter` setup — none
   exists. `clientIp` takes the **first** comma-separated value of a caller-supplied
   `X-Forwarded-For` header at face value if present at all, falling back to `getRemoteAddr()`
   only when the header is absent. Since Shopify webhook deliveries and Shopify-store-owner-driven
   OAuth requests both pass through whatever reverse proxy/LB sits in front of this API, if that
   edge does not strip/overwrite inbound `X-Forwarded-For` before forwarding (not verified here —
   out of scope of the application code, but also **not documented anywhere as a guaranteed
   invariant** the way, say, the HMAC-secret dev-placeholder guard is), any caller can self-report
   an arbitrary `X-Forwarded-For` value and land in a bucket keyed to any IP string they like.

**Concrete exploit shape:** if the edge does not strip client-supplied `X-Forwarded-For` (unverified,
but unguarded in application code), Brand A — who only needs a validly-signed webhook from their
own store, exactly the access level already assumed by the HIGH finding's threat model — can send
requests to `/webhooks/shopify` with `X-Forwarded-For` deliberately spoofed to **Brand B's own known
webhook-relay IP** (discoverable via DNS/ASN lookup on Brand B's public storefront, or simply by
guessing common hosting-provider egress ranges). This exhausts the `tracking` bucket's budget for
*that IP string*, causing Brand B's own legitimate `/webhooks/shopify` and `/webhooks/redemption`
deliveries — which share the same bucket key once they land on the real matching IP — to get
`429`'d. Shopify's own retry-with-backoff behavior on a persistent 429 could eventually cause
Shopify to give up retrying and the delivery to be lost, a real availability impact on Brand B
independent of the (already-fixed) coupon-forgery vector.

This is **not** the same bug as the original HIGH (no coupon/financial mutation occurs), and it
requires the edge/LB to pass through a spoofable `X-Forwarded-For`, which I could not verify either
way from the application repo alone — so I'm not re-opening this as a HIGH. But the MEDIUM finding
as originally scoped ("zero rate limiting") is fully closed; what's left is a **new, narrower
LOW-to-MEDIUM finding** that the fix's own bucket key design doesn't scope by shop/workspace and
silently depends on an unverified, undocumented edge assumption.

**Recommendation (not blocking D1, but should be logged for follow-up):**
1. Document explicitly (in `AuthRateLimitFilter`'s javadoc, next to the existing "per-instance,
   single-node" caveat) whether the deployed edge/LB is trusted to overwrite `X-Forwarded-For`, or
   configure Spring's `ForwardedHeaderFilter`/a trusted-proxy count so only the LB-appended hop is
   honored.
2. Consider a secondary, shop-scoped bucket (keyed by `integration.getWorkspaceId()` after
   signature verification, inside the controller rather than the IP-based servlet filter) for
   `/webhooks/shopify` specifically, so one brand's traffic — spoofed IP or not — cannot exhaust
   another brand's webhook budget. This does not need to replace the existing per-IP filter, just
   supplement it for this specific cross-tenant-shared-bucket shape.

I confirmed this is **not** something Kavya's QA pass claimed to rule out — her Gate 4 verified
bucket *tuning* (limits, threat-model-vs-bucket-choice sensibility), not bucket *key scoping*
across tenants, so this isn't a QA miss, it's a genuinely new angle from adversarial re-probing as
instructed.

---

## Probe 4 — Legacy 5-arg overload unreachability from the Shopify path: CONFIRMED

Grepped every call site of `RedemptionService.redeem(` across `influora-api/src/main/java`:
only two call sites exist —
`ConversionWebhookController.java:114` (5-arg legacy overload — `ConversionWebhookController`'s
own javadoc, re-verified by Kavya's Gate 2 and spot-checked here, confirms this controller has no
independent workspace signal at all, so it is the correct and only sanctioned caller of the legacy
overload) and `ShopifyWebhookController.java:216` (6-arg workspace-scoped overload, always with a
non-null resolved `workspaceId`).

The 5-arg overload itself (lines 227-230) is a thin wrapper that calls
`redeem(null, code, orderId, orderAmount, customerId, idempotencyKey)` — i.e. it is not a
*different* code path with independent logic, it's the same 6-arg method with a hardcoded
`workspaceId=null`. There is no way for `ShopifyWebhookController` to reach this `null`-workspaceId
branch: it only ever calls the 6-arg method directly (line 216) with a value it just resolved from
a verified DB row, never `null`, and never through the 5-arg wrapper at all.

**Result:** confirmed unreachable. No shared mutable state, no reflection, no Spring proxy
weirdness that could cause the wrong overload to be invoked — plain Java overload resolution at
compile time, one call site per overload, verified by grep across the full source tree.

---

## Verdict

- **HIGH (cross-tenant coupon redemption via forged webhook): CLOSED.** Re-attempted the exact
  original attack end-to-end against the real code path; blocked correctly with no mutation and no
  new enumeration signal. Confirmed independently of Kavya's QA, not just cross-checked against it.
- **Enumeration-collapse claim: RE-CONFIRMED**, including checks Kavya's static read didn't
  explicitly rule out (response-body identity, header parity, log side-channel, rate-limit
  cross-talk). No distinguishing signal found under adversarial review.
- **Legacy 5-arg overload reachability from Shopify path: CONFIRMED unreachable.**
- **MEDIUM (zero rate limiting): CLOSED as originally scoped** — both new surfaces are now
  throttled and no longer wide open. **New finding surfaced during adversarial re-probing** (not
  present in the original MEDIUM, not something Kavya's QA claimed to rule out): the shared
  `"tracking"` bucket's key is per-IP only, with no per-shop/workspace scoping, and `clientIp()`
  trusts a caller-supplied `X-Forwarded-For` with no trusted-proxy allowlist anywhere in the
  codebase — a plausible (edge-config-dependent, unverified either way from this repo) cross-tenant
  bucket-exhaustion / webhook-availability vector against Brand B. Rated LOW-to-MEDIUM: no
  financial/data mutation, availability-only impact, contingent on an unverified deployment
  assumption. **Not blocking** — does not reopen the original HIGH or MEDIUM, and does not block
  D1/D2/D3 progression, but should be logged as a tracked follow-up rather than silently dropped.

**Disposition: D1 CLEARS.** Both of my original findings (HIGH, MEDIUM) are genuinely closed under
adversarial re-testing, not just structurally patched. The one new observation from this pass is a
narrower, non-blocking finding to track separately — flagging it as a follow-up task, not a gate on
this clearance.

**Route:**
- Meera: cleared for live-MySQL V27 verification.
- D2 (WooCommerce) / D3 (IntegrationHealthService): unblocked — both should apply the
  workspace-scoped-overload pattern from this fix, and should each independently confirm their own
  rate-limit bucket keys don't have the same per-IP-only cross-tenant sharing shape flagged above if
  they add webhook surfaces with per-integration identity.
- Follow-up (non-blocking, log for a future task): scope the `/webhooks/shopify` rate-limit bucket
  per-shop/workspace in addition to per-IP, and/or configure a trusted-proxy hop count so
  `X-Forwarded-For` cannot be freely self-reported by callers.

---

**Sign-off:** Kabir, 2026-07-07
