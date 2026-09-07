#!/usr/bin/env bash
# Gate for F-0726 — unsigned-tenant-selector.
#
# The failure: X-Shopify-Hmac-Sha256 is an HMAC of the BODY against the single app-level client
# secret. It proves a payload came from SOME shop with our app installed; it never says WHICH shop,
# because the shop is named only in X-Shopify-Shop-Domain, which the HMAC does not cover.
# ShopifyWebhookController used that unsigned header to pick the workspace every downstream write
# is scoped to.
#
# WHY IT SURVIVED REVIEW. wiki/errors/wave-d1-shopify-integration-redteam.md:95 asserted the header
# was "a real, HMAC-authenticated workspace identity", and the whole D1 cross-tenant fix was built
# on that sentence. Scoping the coupon lookup to a workspace an unsigned header chooses only moves
# the attacker's choice from "which coupon" to "which workspace". A doc claim, believed, is what
# kept a HIGH open through a red-team pass and a fix.
#
# THE ATTACK. Install the app on a store you control; add a second webhook subscription pointing at
# your own collector (Shopify signs it with the same app secret); capture a body + valid HMAC for an
# order whose discount_codes[0].code you chose; replay it to us with the victim's shop domain in the
# header. Signature passes, workspace resolves to the victim, the victim's own coupon is redeemed —
# fabricated redemption, inflated usage count, affiliate accrual for an order that never happened.
#
# WHY THESE TESTS. The suites are pinned as a set and each covers a different way the fix can rot:
#
#   receive_orderNotInClaimedShop_isRejected              — the fix itself. Asserts SHOP_ORDER_MISMATCH
#     401 AND that neither RedemptionService nor IdempotencyService was touched, so a future
#     "reject but after the write" regression cannot pass it.
#   receive_ownershipCheckTransientFailure_surfacesNon2xx — an unreachable Admin API must NOT read as
#     "not your order". Without this, a fix that fails closed on any error would silently drop real
#     redemptions during a Shopify incident, and F-0725's terminal/transient split would be the only
#     thing standing between that and a retry storm.
#   receive_orderWithNoCoupon_skipsOwnershipCheck         — the check guards WRITES, not every
#     delivery. Most orders carry no Influora coupon; an outbound call on each one is a
#     self-inflicted rate limit.
#   ShopifyOrderOwnershipVerifierTest                     — the validate-before-interpolate guards.
#     orderId comes from the request body, so a crafted id must never reach the Admin API path, and
#     the host half must stay *.myshopify.com (SSRF).
#
# FALSIFIED, not assumed: with the controller's ownership check bypassed (behaviourally identical to
# pre-fix), ShopifyWebhookControllerTest exits 1 with exactly 2 failures — the two tests that assert
# the check exists. receive_orderWithNoCoupon_skipsOwnershipCheck correctly stays green under that
# probe, which is why it cannot be the only assertion.
#
# NOT COVERED BY THIS GATE, deliberately: whether Shopify's Admin API actually answers 404 for an
# order that is not in a shop. That is a third-party contract and it is not provable offline; every
# test here mocks at the verifier boundary. A live smoke test against a real store is the only thing
# that closes it, and it is recorded as open on the F-0726 verdict.
#
# NOTE ON THE COMMAND: this repo has no aggregator pom — influora-api/pom.xml is the only one, so
# `mvn -pl influora-api test` dies in the reactor and never reaches surefire. cwd must be inside
# the module. Do not "simplify" this to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
TESTS='ShopifyWebhookControllerTest,ShopifyWebhookIdempotencyTest,ShopifyOrderOwnershipVerifierTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

VERIFIER="$MODULE/src/main/java/com/influora/integration/shopify/ShopifyOrderOwnershipVerifier.java"
if [ ! -f "$VERIFIER" ]; then
  echo "BROKEN: ShopifyOrderOwnershipVerifier is missing — the shop-domain header is once again an"
  echo "        unsigned selector for the workspace every write is scoped to (F-0726)."
  exit 1
fi

# The check is worthless if the controller stops calling it. Cheap structural assertion so deleting
# the call site is a red gate and not just a green suite full of mocks.
if ! grep -q "orderBelongsToShop" "$MODULE/src/main/java/com/influora/web/ShopifyWebhookController.java"; then
  echo "BROKEN: ShopifyWebhookController no longer calls orderBelongsToShop — the verifier exists but"
  echo "        nothing invokes it, so the unsigned header selects the workspace again (F-0726)."
  exit 1
fi

cd "$MODULE" || exit 2

if ! out="$(mvn -o -q test -Dtest="$TESTS" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: a Shopify order webhook no longer confirms the order against the shop the header"
  echo "        claims, or it now rejects a delivery it should retry. A replayed validly-signed body"
  echo "        can redeem another workspace's coupon (F-0726)."
  echo "$out" | tail -30
  exit 1
fi

# -DfailIfNoTests=true so a renamed or deleted test is a failure rather than a silent pass.
echo "PROVED: a Shopify order webhook is rejected unless the claimed shop's own Admin API has the"
echo "        order, transient lookup failures still retry, and no-coupon orders skip the call ($TESTS)"
exit 0
