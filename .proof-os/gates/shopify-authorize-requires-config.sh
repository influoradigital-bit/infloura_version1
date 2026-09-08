#!/usr/bin/env bash
# Gate for F-0732 — missing-config-guard.
#
# The failure: ShopifyConnectController#authorize minted a CSRF state and built a Shopify
# authorization URL without ever checking ShopifyProperties#isConfigured(). apiKey/apiSecret
# default to "" and nothing binds them anywhere — no yaml placeholder, no compose file, no
# generate-env.sh entry (F-0729) — so isConfigured() is false in EVERY environment today. The brand
# was therefore redirected into Shopify's own UI with client_id= empty, and Shopify answered with
# its own error page. To the brand that reads as their store or their account being broken, not as
# this deploy simply having no Shopify app.
#
# MetaOAuthController#authorize has had the identical guard since 2026-08-28 (META_NOT_CONFIGURED,
# 503) — and its code comment records that the guard was originally added for only ONE branch and
# had to be extended to the default branch later, for exactly this failure. Shopify never got the
# equivalent at all.
#
# WHY THESE CHECKS.
#
#   grep for isConfigured in the controller — the suite below mocks its collaborators, so a future
#     refactor could delete the guard and leave a test that still passes by construction if the
#     assertion were ever weakened. The structural check makes deletion of the CALL SITE red on its
#     own. This is the lesson from F-0725/F-0726: a gate that only runs a suite cannot distinguish
#     a wired guard from an orphaned one (see .proof-os/gates/shopify-webhook-shop-binding.sh).
#
#   authorize_notConfigured_refusedBeforeMintingState — asserts SHOPIFY_NOT_CONFIGURED + 503 AND
#     that stateStore.issue and buildAuthorizationUrl were never called. That second half is what
#     makes guarding only /authorize sufficient: /callback cannot be reached with a valid state
#     that was never minted, which is how Meta scopes its own guard. A "fix" that threw after
#     minting state would pass a status-only assertion and fail this one.
#
# The test uses a REAL ShopifyProperties POJO, not a mock. isConfigured() is the behaviour under
# test; stubbing it would assert the mock rather than the guard.
#
# FALSIFIED, not assumed: with the guard bypassed (`if (false && ...)`, behaviourally identical to
# pre-fix), this suite exits 1 with exactly 1 failure — authorize_notConfigured. The other six tests
# stay green under that probe, which is correct: they exercise the configured path.
#
# NOT COVERED: whether Shopify actually rejects a blank client_id the way this assumes. That is a
# third-party contract and is not provable offline — but it does not matter here, because the guard
# means we never send one. What IS uncovered is the opposite direction: nothing proves the flow
# works once real credentials exist, since none do in any environment (F-0729/F-0730/F-0731).
#
# NOTE ON THE COMMAND: this repo has no aggregator pom — influora-api/pom.xml is the only one, so
# `mvn -pl influora-api test` dies in the reactor and never reaches surefire. cwd must be inside
# the module. Do not "simplify" this to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
CONTROLLER="$MODULE/src/main/java/com/influora/web/ShopifyConnectController.java"
TESTS='ShopifyConnectControllerTest'

if [ ! -f "$MODULE/pom.xml" ]; then
  echo "UNAVAILABLE: no pom at $MODULE/pom.xml"
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

if [ ! -f "$CONTROLLER" ]; then
  echo "UNAVAILABLE: ShopifyConnectController not found at $CONTROLLER"
  exit 2
fi

if ! grep -q "isConfigured" "$CONTROLLER"; then
  echo "BROKEN: ShopifyConnectController no longer checks ShopifyProperties#isConfigured(), so"
  echo "        /authorize will hand the brand a Shopify URL with an empty client_id again and the"
  echo "        resulting Shopify error page reads as the brand's own store being broken (F-0732)."
  exit 1
fi

cd "$MODULE" || exit 2

if ! out="$(mvn -o -q test -Dtest="$TESTS" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: the Shopify authorize config guard no longer holds — either it stopped refusing an"
  echo "        unconfigured deploy, or it now refuses after already minting OAuth state (F-0732)."
  echo "$out" | tail -30
  exit 1
fi

# -DfailIfNoTests=true so a renamed or deleted test is a failure rather than a silent pass.
echo "PROVED: /shopify/oauth/authorize refuses an unconfigured deploy with 503 before minting state"
echo "        or building a URL ($TESTS)"
exit 0
