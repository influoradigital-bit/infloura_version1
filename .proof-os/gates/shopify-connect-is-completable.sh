#!/usr/bin/env bash
# Gate for F-0730 (absent-webhook-subscription) and F-0731 (dead-end-oauth-redirect).
#
# These two shipped together because they are the same defect seen from each end: a connect flow
# that could be started and never finished. F-0730 meant a connected store never delivered an order;
# F-0731 meant the merchant never got back into the app to see that it had "worked".
#
# F-0730. A Shopify app receives ONLY the topics it subscribes to, via the Admin API or a
# declarative shopify.app.toml manifest. This repo had neither — verified by grepping the whole tree
# for webhooks.json / registerWebhook / createWebhook / *.toml before the fix. ShopifyWebhookController
# was HMAC-verified, idempotency-guarded, unit-tested, and unreachable by any real store.
#
# F-0731. ShopifyConnectController's own javadoc described "the frontend route at redirect-uri" that
# reads the callback result and forwards the user. No such route existed. /shopify/oauth/callback
# answers with JSON, not a 302, so the merchant's browser landed on the API host showing raw JSON.
#
# WHY THESE CHECKS.
#
#   registerOrderWebhooks call site — the registrar existing proves nothing if connect does not call
#     it. This is the F-0725/F-0726 lesson: a gate that only runs mocked tests cannot tell a wired
#     dependency from an orphaned one, and a crossed commit produces exactly that state.
#
#   ROUTE/CONFIG COUPLING — the heart of the F-0731 half. The frontend route path and the
#     redirect-uri default MUST be the same string. If either drifts, Shopify sends the merchant to
#     a path the SPA does not serve and the flow dead-ends again, in a way no unit test anywhere can
#     see: the backend is fine, the frontend is fine, and only their agreement is broken. The two
#     values are extracted and compared rather than each being checked against a hardcoded literal
#     in this gate, so this file cannot itself become the stale third copy.
#
#   callback_registersOrderWebhooks / callback_webhookRegistrationFailure_rollsBackTheConnect — the
#     second is the load-bearing one. A failed registration REVOKES the token and fails the connect,
#     because the tempting alternative (keep the token, log, return connected:true) recreates the
#     exact silent half-connected state F-0730 describes.
#
# NOT COVERED: whether Shopify actually accepts these subscriptions. No credentials exist in any
# environment (F-0729), so nothing here has run against a real store — the registrar's HTTP contract
# is mocked at the RestClient boundary. This gate proves the flow is WIRED, not that Shopify likes
# it. A live smoke test remains required before this integration can be called working.
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
REGISTRAR="$MODULE/src/main/java/com/influora/integration/shopify/ShopifyWebhookRegistrar.java"
YAML="$MODULE/src/main/resources/application.yml"
APP_TSX="$ROOT/src/App.tsx"
TESTS='ShopifyConnectControllerTest'

for f in "$CONTROLLER" "$REGISTRAR" "$YAML" "$APP_TSX"; do
  if [ ! -f "$f" ]; then
    echo "UNAVAILABLE: expected file missing: $f"
    exit 2
  fi
done
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

# --- F-0730: connect must actually subscribe -----------------------------------------------
if ! grep -q "registerOrderWebhooks" "$CONTROLLER"; then
  echo "BROKEN: ShopifyConnectController no longer calls registerOrderWebhooks. A store can complete"
  echo "        OAuth, report connected, and never deliver a single order — with nothing reporting a"
  echo "        problem (F-0730)."
  exit 1
fi

# --- F-0731: the redirect target and the route that serves it must agree --------------------
# Anchored on the redirect-uri line, and the nested ${influora.web-base-url} is stepped over
# explicitly. A naive [^}]* stops at that INNER brace and extracts an empty string, which reads as a
# mismatch — this gate was a false RED until the extraction was checked against the real file rather
# than assumed to work.
CONFIG_PATH=$(grep 'redirect-uri:' "$YAML" | grep -o 'web-base-url}[^}]*' | sed 's|web-base-url}||' | head -1)
ROUTE_PATH=$(grep -o 'path="/brand/settings/shopify/[^"]*"' "$APP_TSX" | sed 's/path="//; s/"$//' | head -1)

if [ -z "$CONFIG_PATH" ]; then
  echo "BROKEN: influora.shopify.redirect-uri no longer derives a frontend path from web-base-url."
  echo "        Shopify would send the merchant somewhere this app does not control (F-0731)."
  exit 1
fi
if [ -z "$ROUTE_PATH" ]; then
  echo "BROKEN: no /brand/settings/shopify/* route is registered in src/App.tsx, so Shopify's"
  echo "        post-approval redirect lands on a path the SPA does not serve (F-0731)."
  exit 1
fi
if [ "$CONFIG_PATH" != "$ROUTE_PATH" ]; then
  echo "BROKEN: the Shopify redirect target and the route that serves it disagree (F-0731)."
  echo "        redirect-uri path : $CONFIG_PATH"
  echo "        App.tsx route path: $ROUTE_PATH"
  echo "        Nothing else can catch this: backend and frontend are each internally consistent."
  exit 1
fi

cd "$MODULE" || exit 2

if ! out="$(mvn -o -q test -Dtest="$TESTS" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: the Shopify connect flow no longer subscribes the store, or a failed subscription no"
  echo "        longer rolls the connect back (F-0730)."
  echo "$out" | tail -30
  exit 1
fi

# -DfailIfNoTests=true so a renamed or deleted test is a failure rather than a silent pass.
echo "PROVED: connect subscribes the shop to its order topics and rolls back if it cannot, and the"
echo "        redirect target ($CONFIG_PATH) is served by a real SPA route ($TESTS)"
exit 0
