#!/usr/bin/env bash
# Gate for the 2026-09-12 Meta connect dead-end.
#
# WHAT HAPPENED. Meta redirected a creator to
#   https://influora.in/api/v1/meta/oauth/callback?code=…&state=…
# and the API answered UNAUTHENTICATED. Not a session problem: JwtAuthenticationFilter reads
# authorization ONLY from an `Authorization: Bearer` header, and Meta's redirect is a top-level
# browser navigation from facebook.com, which carries none. That configuration could never have
# succeeded for anyone, once. Meta must be pointed at the SPA route, which reads code/state off its
# own query string and calls GET /meta/oauth/callback itself as an authenticated XHR.
#
# WHY A GATE AND NOT A TEST. Three separate wrong values coexisted, in three files that are each
# internally consistent, and no unit test anywhere could see any of them:
#   - the live .env held the API path (fixed by hand on the box);
#   - deploy/utho/generate-env.sh generated an app.influora.in host, which T-DOMAIN-0820 §4.5 had
#     already ruled out ("No `app.` subdomain is needed under this decision");
#   - application-dev.yml's commented example showed the API path as the shape to copy.
# Only the AGREEMENT between the config and the route that serves it was broken. So, exactly as
# shopify-connect-is-completable.sh does for F-0731, every value here is EXTRACTED from its own file
# and compared against another extracted value — never against a literal written into this gate,
# which is how this file avoids becoming the stale fourth copy.
#
# NOT COVERED: whether the Meta app dashboard's Valid OAuth Redirect URIs list contains the value,
# and what the live .env actually holds. Neither is in this repo. A live connect remains the only
# proof of those two, and the code-side backstop for them is the 503
# META_REDIRECT_URI_MISCONFIGURED that GET /meta/oauth/authorize now raises before sending a creator
# to a dialog this environment cannot complete.
#
# NOTE ON THE COMMAND: this repo has no aggregator pom — influora-api/pom.xml is the only one, so
# `mvn -pl influora-api test` dies in the reactor and never reaches surefire. cwd must be inside
# the module. Do not "simplify" this to -pl.
#
# exit 0 = proved · 1 = broken · 2 = unavailable (never green)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/influora-api"
YAML="$MODULE/src/main/resources/application.yml"
CONSTANT="$MODULE/src/main/java/com/influora/config/MetaRedirectUri.java"
CONTROLLER="$MODULE/src/main/java/com/influora/web/MetaOAuthController.java"
APP_TSX="$ROOT/src/App.tsx"
ENV_GEN="$ROOT/deploy/utho/generate-env.sh"
TESTS='MetaRedirectUriTest,MetaRedirectUriStartupValidatorTest,MetaOAuthServiceTest'

for f in "$YAML" "$CONSTANT" "$CONTROLLER" "$APP_TSX" "$ENV_GEN"; do
  if [ ! -f "$f" ]; then
    echo "UNAVAILABLE: expected file missing: $f"
    exit 2
  fi
done
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNAVAILABLE: mvn not on PATH — this gate cannot report green without running"
  exit 2
fi

# --- the route that actually completes a connect -------------------------------------------
# Anchored on the React route, which is the one value the creator's browser really lands on.
ROUTE_PATH=$(grep -o 'path="/creator/settings/meta/[^"]*"' "$APP_TSX" | sed 's/path="//; s/"$//' | head -1)
if [ -z "$ROUTE_PATH" ]; then
  echo "BROKEN: no /creator/settings/meta/* route is registered in src/App.tsx, so Meta's redirect"
  echo "        lands on a path the SPA does not serve and every connect dead-ends."
  exit 1
fi

# --- 1. the yaml default must derive that route from web-base-url --------------------------
# The nested ${influora.web-base-url} is stepped over explicitly: a naive [^}]* stops at that INNER
# brace and extracts an empty string, which reads as a mismatch. This is the same extraction
# shopify-connect-is-completable.sh had to correct against the real file.
for key in META_REDIRECT_URI META_INSTAGRAM_REDIRECT_URI; do
  line=$(grep "\${$key:" "$YAML" | head -1)
  if [ -z "$line" ]; then
    echo "BROKEN: application.yml no longer binds $key at all. An env var name that appears only in"
    echo "        a comment binds to nothing, and the property falls back to blank."
    exit 1
  fi
  config_path=$(printf '%s' "$line" | grep -o 'web-base-url}[^}]*' | sed 's|web-base-url}||' | head -1)
  if [ "$config_path" != "$ROUTE_PATH" ]; then
    echo "BROKEN: the $key default and the SPA route that serves it disagree."
    echo "        application.yml default path : ${config_path:-<not derived from web-base-url>}"
    echo "        src/App.tsx route path       : $ROUTE_PATH"
    echo "        Nothing else catches this: backend and frontend are each internally consistent."
    exit 1
  fi
done

# --- 2. the Java constant must be the same string ------------------------------------------
# MetaRedirectUri.CREATOR_CALLBACK_PATH is what the startup validator reports and what
# servesCreatorCallback() accepts. If it drifts, the code would "helpfully" reject the real route.
CONST_PATH=$(grep -o 'CREATOR_CALLBACK_PATH = "[^"]*"' "$CONSTANT" | sed 's/.*= "//; s/"$//' | head -1)
if [ "$CONST_PATH" != "$ROUTE_PATH" ]; then
  echo "BROKEN: MetaRedirectUri.CREATOR_CALLBACK_PATH ($CONST_PATH) is not the route src/App.tsx"
  echo "        serves ($ROUTE_PATH). The startup validator would flag the correct config as wrong."
  exit 1
fi

# --- 3. the generated env must agree with the app origin AND the route ----------------------
APP_DOMAIN=$(grep -o '^APP_DOMAIN=.*' "$ENV_GEN" | sed 's/^APP_DOMAIN=//' | head -1)
if [ -z "$APP_DOMAIN" ]; then
  echo "UNAVAILABLE: could not read APP_DOMAIN out of deploy/utho/generate-env.sh"
  exit 2
fi
for key in META_REDIRECT_URI META_INSTAGRAM_REDIRECT_URI; do
  value=$(grep -o "^$key=.*" "$ENV_GEN" | sed "s/^$key=//" | head -1)
  if [ -z "$value" ]; then
    echo "BROKEN: deploy/utho/generate-env.sh no longer generates $key, so a regenerated .env leaves"
    echo "        it unset on the box."
    exit 1
  fi
  if [ "$value" != "https://$APP_DOMAIN$ROUTE_PATH" ]; then
    echo "BROKEN: generate-env.sh would write a $key that cannot complete a connect."
    echo "        generated : $value"
    echo "        expected  : https://$APP_DOMAIN$ROUTE_PATH   (APP_DOMAIN + the App.tsx route)"
    echo "        This file generated an app. host for months while looking correct in isolation."
    exit 1
  fi
done

# --- 4. the pre-dialog refusal must still be wired ------------------------------------------
# Without this call, a misconfigured deploy goes back to telling the creator nothing until Meta has
# already redirected them — which is the entire failure being gated.
if ! grep -q "assertRedirectUriUsable" "$CONTROLLER"; then
  echo "BROKEN: MetaOAuthController no longer calls assertRedirectUriUsable, so a creator on a"
  echo "        misconfigured deploy is sent to Meta and discovers the dead end after granting"
  echo "        permissions."
  exit 1
fi

cd "$MODULE" || exit 2

# -DfailIfNoTests=true so a renamed or deleted test is a failure rather than a silent pass.
if ! out="$(mvn -o -q test -Dtest="$TESTS" -DfailIfNoTests=true 2>&1)"; then
  echo "BROKEN: the redirect-uri decision table or its guard no longer holds."
  echo "$out" | tail -30
  exit 1
fi

echo "PROVED: the Meta redirect target ($ROUTE_PATH) is served by a real SPA route, the yaml default,"
echo "        the Java constant and generate-env.sh all agree on it, and /authorize refuses a deploy"
echo "        whose redirect-uri points at this API instead ($TESTS)"
exit 0
