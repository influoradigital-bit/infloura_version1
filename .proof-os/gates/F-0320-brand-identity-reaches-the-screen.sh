#!/usr/bin/env bash
# F-0320-brand-identity-reaches-the-screen.sh — gate for F-0320 (persisted-value-no-consumer).
#
# F-0282 made persistBrandSession keep data.user.displayName, write it to
# localStorage['brand_display_name'], and expose getBrandDisplayName() — but NOTHING in
# production ever called getBrandDisplayName(), and nothing in the brand login/register flow
# ever called useAuthStore().login()/setUser() at all (only the creator flow does, per CR-06).
# A live brand session's `user` therefore stayed null forever, and the brand dashboard greeting
# (dashboard-page.tsx) kept rendering "Good morning, there" — the exact symptom the F-0282
# record originally described, reproduced unchanged by the fix that claimed to close it. A
# newly-introduced dead recovery API, structurally identical to F-0279.
#
# THE MISTAKE THIS GATE REFUSES TO REPEAT: a static "is getBrandDisplayName referenced
# somewhere?" grep is precisely the check that would have missed F-0320 — the previous round's
# fix DID add the function and DID export it; the only thing missing was a real caller that
# threads the value all the way to render. So the deciding leg here is a RENDERED-OUTCOME
# vitest suite, not a reference-exists grep. The wrong fix this is built to catch: a consumer
# that reads getBrandDisplayName() (so it "shows up" in any static reachability check) and then
# still renders the placeholder anyway.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266 — grep CODE, not file bytes. A raw-byte grep fails a fix whose own comment quotes the
# forbidden string, and greens a "fix" that exists only in a comment.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

AUTH_SESSION=src/lib/auth-session.ts
DASHBOARD=src/components/brand/dashboard/dashboard-page.tsx
LOGIN=src/pages/brand-login.tsx
REGISTER=src/pages/brand-register.tsx

for f in "$AUTH_SESSION" "$DASHBOARD" "$LOGIN" "$REGISTER"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· sanity: persistBrandSession still keeps the real displayName (F-0282, must not regress)"
AUTH_CODE=$(code_view "$AUTH_SESSION") || { echo "$(code_why) - unavailable"; exit 2; }
if ! grep -qE "displayName" "$AUTH_CODE"; then
  echo "VERDICT: broken — auth-session.ts no longer threads displayName at all; F-0282 regressed"
  echo "         underneath F-0320 (nothing left to consume)"
  exit 1
fi
echo "  clean — displayName is still threaded through auth-session.ts"

# T-BRANDOPEN-0817 (F-0320 self-check) — a gate that only checks "getBrandDisplayName is
# referenced somewhere in src/" is satisfied by a consumer that calls it and throws the result
# away (e.g. `getBrandDisplayName(); return <>{greeting}, {user?.firstName || 'there'}</>`).
# That exact shape is what shipped last round and is what this record exists to catch. So the
# grep below is demoted to a SANITY check (informational only, never the verdict) and the real
# verdict comes from the rendered-outcome vitest suites further down.
echo "· sanity: getBrandDisplayName has at least one call site outside its own module/tests"
callers=$(code_grep_r "getBrandDisplayName\(" "src" "(src/lib/auth-session\.ts|\.test\.)"); crc=$?
if [ $crc -eq 2 ]; then
  echo "  · $(code_why) — sanity check unavailable, continuing to the rendered-outcome legs"
elif [ -z "$callers" ]; then
  echo "  no caller found outside auth-session.ts/tests (informational — see rendered legs below"
  echo "  for the actual verdict; a caller existing proves nothing on its own, see header)"
else
  printf '%s\n' "$callers" | head -3
  echo "  informational — a caller exists, but this alone does NOT pass this gate (see header)"
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· node_modules/.bin/vitest not installed — unavailable"; exit 2; }

IDENTITY_SUITE=src/pages/__tests__/brand-auth-identity.test.tsx
GREETING_SUITE=src/components/brand/dashboard/__tests__/dashboard-page-greeting.test.tsx
for f in "$IDENTITY_SUITE" "$GREETING_SUITE"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

BUDGET="${PROOF_F0320_VITEST_TIMEOUT:-120}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

echo "· vitest run $IDENTITY_SUITE $GREETING_SUITE (budget ${BUDGET}s)"
out=$($TO node_modules/.bin/vitest run "$IDENTITY_SUITE" "$GREETING_SUITE" 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish, so no test result"
  echo "             was observed"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "✓|×|FAIL|Tests |Test Files" | tail -40
  echo "VERDICT: broken — a live brand session does not reach the dashboard with its real"
  echo "         identity end to end (login/register does not populate useAuthStore().user with"
  echo "         the persisted displayName, or the dashboard greeting does not render it) (F-0320)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files" | tail -2
echo "  suites green — brand-auth-identity.test.tsx, dashboard-page-greeting.test.tsx"

echo "VERDICT: aligned (proved) — a real backend displayName, once persisted by"
echo "         persistBrandSession, now reaches useAuthStore().user via the brand login AND"
echo "         register forms, and the dashboard greeting renders that real name instead of the"
echo "         'there' placeholder; an absent displayName still falls back honestly (empty"
echo "         string), never a fabricated 'Brand Account'-style name"
echo "NOT CHECKED: a live round trip against the real backend (both suites mock api.auth.brandLogin"
echo "             /brandRegister at the boundary and seed localStorage to stand in for what"
echo "             persistBrandSession would have written); the brand-layout.tsx sidebar path"
echo "             (already fixed separately under F-0246 via a workspace-record fallback, not"
echo "             touched here); whether every OTHER brand page that reads useAuthStore().user"
echo "             renders it correctly — only the dashboard greeting is asserted."
exit 0
