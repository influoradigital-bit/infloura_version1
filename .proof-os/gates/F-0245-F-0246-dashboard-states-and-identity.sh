#!/usr/bin/env bash
# F-0245-F-0246-dashboard-states-and-identity.sh — gate for F-0245 and F-0246.
#
# F-0245 (loading-equals-error-equals-empty). The brand dashboard had no skeleton and no
# error branch. A brand whose API was down, one whose data was still loading, and one with
# genuinely nothing pending all saw the SAME screen — and that screen stated, confidently
# and permanently, that they had no pending deliverables, no contracts and no payment
# releases. A failed fetch rendered as a factual zero. On a surface that reports money
# owed and contracts outstanding, "we could not reach the server" and "you owe nothing"
# are not interchangeable.
#
# F-0246 (placeholder-identity-live). Nothing in the BRAND flow ever writes the auth store
# — login()/setUser() are called only from creator-login, creator-register and the demo
# panel — so `user` is permanently null in a live brand session, and the sidebar fell back
# to 'Brand Account' / 'brand@company.com': a plausible-looking email belonging to nobody.
# The fix binds to the workspace identity from the ['workspace','me'] query already mounted
# app-wide by WorkspaceVerificationBanner, so it adds no network call, and degrades to
# 'Workspace' / 'No email on file' rather than to an invented address.
#
# Note what this does NOT fix: no PERSONAL name is captured for a brand user at all.
# persistBrandSession drops data.user.displayName (unlike persistCreatorSession, which
# keeps it) — recorded separately. This gate protects the weaker but real property: what
# is shown is either true or visibly absent, never fabricated.
#
# Three legs:
#   1. The fabricated identity literals are gone from live code paths.
#   2. The three-state machinery still exists in the dashboard.
#   3. The spec: pending renders a skeleton not a zero; rejected renders an error with a
#      retry not a zero; resolved-empty still renders the empty state.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

DASH=src/components/brand/dashboard/dashboard-page.tsx
LAYOUT=src/components/brand/brand-layout.tsx
for f in "$DASH" "$LAYOUT"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

echo "· no fabricated identity on a live code path"
# Strip line comments and block-comment bodies before looking, so the explanatory comment
# naming the old placeholder does not read as the placeholder itself.
stripped=$(sed -e 's://.*::' -e 's:\*.*::' "$LAYOUT")
if printf '%s' "$stripped" | grep -qE "brand@company\.com|'Brand Account'"; then
  grep -nE "brand@company\.com|'Brand Account'" "$LAYOUT"
  echo "VERDICT: broken — a fabricated brand identity is rendered again (F-0246); the"
  echo "         sidebar shows an email belonging to nobody"
  exit 1
fi
echo "  clean — placeholder identity survives only in a comment"

echo "· the dashboard still distinguishes loading, error and empty"
missing=""
for sym in LoadStatus DashboardCardError loadDashboard; do
  grep -q "$sym" "$DASH" || missing="$missing $sym"
done
if [ -n "$missing" ]; then
  echo "  missing:$missing"
  echo "VERDICT: broken — the dashboard's three-state machinery is gone (F-0245); an"
  echo "         unresolved or failed query renders as a factual zero again"
  exit 1
fi
echo "  clean — LoadStatus / DashboardCardError / retryable loadDashboard present"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/dashboard/__tests__/dashboard-page.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — a down or loading dashboard is reported as an empty one (F-0245),"
  echo "         or a fabricated identity is rendered (F-0246)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — an unreachable dashboard says so, and shows no invented user"
echo "NOT CHECKED: that a brand user's PERSONAL name is ever available — persistBrandSession"
echo "             drops data.user.displayName, so the fix binds to the WORKSPACE identity"
echo "             and the greeting still degrades to 'there' for everyone; the root cause is"
echo "             recorded separately and this gate would stay green if it were fixed;"
echo "             that the retry actually recovers against a real backend — the spec proves"
echo "             the control exists and re-invokes the loader, not that a second attempt"
echo "             succeeds; and every other brand surface — this covers the dashboard and"
echo "             the sidebar only, and the loading==error==empty shape may exist elsewhere"
