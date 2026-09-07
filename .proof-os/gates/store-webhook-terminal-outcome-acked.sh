#!/usr/bin/env bash
# Gate for F-0725 — webhook-retry-storm.
#
# The failure: ShopifyWebhookController and WooCommerceWebhookController wrapped the redemption in
# a try that caught ONLY IdempotencyService.AlreadyCompleted/AlreadyInProgress. Every ordinary
# business outcome escaped and was mapped to a non-2xx by GlobalExceptionHandler — INVALID_CODE
# 404, CODE_EXPIRED 400, CODE_LIMIT_REACHED 400, UNSUPPORTED_DISCOUNT_TYPE 500. The controllers
# reach that code whenever an order carries ANY discount code, and on a real store most discount
# codes are the merchant's own (FREESHIP, last week's sale, a promo at its cap), so this was the
# ordinary path, not an edge case.
#
# Both platforms retry any non-2xx, and Shopify removes the subscription after ~48h of continuous
# failures. IdempotencyService#reclaimFailedForRetry handed the reservation back on each attempt,
# so every retry genuinely re-ran and re-failed — the loop had no dampening anywhere. The
# integration disconnected itself and the brand saw only that tracking had stopped.
#
# WHY THESE TESTS. The suites are pinned as a MATCHED PAIR per controller and the pair is the whole
# gate:
#
#   receive_terminalRedemptionOutcome_isAcknowledgedNotRetried  — parameterized over all four
#     terminal codes, asserts HTTP 200 AND that redeem() was still genuinely attempted with the
#     resolved workspaceId. A "fix" that skipped the redemption entirely would return 200 and fail
#     this.
#   receive_transientRedemptionFailure_stillSurfacesNon2xx      — asserts IDEMPOTENCY_KEY_IN_PROGRESS
#     still escapes as a non-2xx. This is what stops the fix from being a blanket catch(Exception)
#     that would also swallow a database outage — the case where the platform's retry IS the
#     recovery path. Without this half, a catch-everything regression stays green.
#
#   receive_hostileWebhook_crossTenantCouponCode_isRejected     — carried along deliberately. Its
#     assertion changed from assertThrows to a 200 when F-0725 was fixed, so it is exactly the test
#     someone could "restore" while believing they were re-hardening security. It still asserts the
#     load-bearing property (redeem called with the receiving shop's OWN workspaceId, never the
#     other workspace, never the legacy global overload), and pinning it here records that the
#     status change was deliberate.
#
# FALSIFIED, not assumed: with StoreWebhookRedemptionOutcome.isTerminal forced to return false
# (behaviourally identical to the pre-fix rethrow), this command exits 1 with 10 errors — all eight
# parameterized terminal cases plus both cross-tenant tests. The transient test stays green under
# that probe, which is correct and is why it cannot be the only assertion.
#
# NOTE ON THE COMMAND: this repo has no aggregator pom — influora-api/pom.xml is the only one, so
# `mvn -pl influora-api test` dies in the reactor and never reaches surefire. cwd must be inside
# the module. Do not "simplify" this to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
TESTS='ShopifyWebhookControllerTest,WooCommerceWebhookControllerTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

# The shared classifier is the single point both controllers depend on; if it is gone the suites
# would not compile, but say so plainly rather than reporting a compile error as a behaviour finding.
if [ ! -f "$MODULE/src/main/java/com/influora/web/StoreWebhookRedemptionOutcome.java" ]; then
  echo "BROKEN: StoreWebhookRedemptionOutcome is missing — the terminal/transient split F-0725"
  echo "        depends on no longer exists, so every redemption outcome reaches the platform as"
  echo "        whatever GlobalExceptionHandler maps it to."
  exit 1
fi

cd "$MODULE" || exit 2

if ! out="$(mvn -o -q test -Dtest="$TESTS" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: a store webhook no longer acknowledges a terminal redemption outcome, or it now"
  echo "        swallows a transient one. Either way a connected Shopify/WooCommerce store is"
  echo "        heading for an unbounded retry loop and eventual disconnection (F-0725)."
  echo "$out" | tail -30
  exit 1
fi

# -DfailIfNoTests=true so a renamed or deleted test is a failure rather than a silent pass.
echo "PROVED: terminal redemption outcomes are acknowledged 200 and transient ones still surface"
echo "        non-2xx, on both store webhooks ($TESTS)"
exit 0
