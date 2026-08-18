#!/usr/bin/env bash
# F-0260-absent-metric-not-zero.sh — gate for F-0260 (fabricated-zero-metrics).
#
# buildLiveCreatorView (brand-creator-profile.tsx) hardcoded avgLikes/avgComments/avgViews to
# literal 0, and audience.gender to { female: 0, male: 0, other: 0 } — DiscoveryDtos.
# CreatorPublicProfileResponse has no such fields at all, so every live creator printed "0 Avg
# Likes" and "Female 0% Male 0%" as if measured. The file's own comment (originally attached to
# the metrics.completionRate/onTimeDelivery/repeatClients fix) already names this exact class of
# bug and the fix already sitting next to it: `null`, not a fabricated 0, rendered as an explicit
# "—" / "Not available" / "Not yet scored" state — the pattern `stats.rating` and
# `audience.authenticity` already use.
#
# Three legs:
#   1. SOURCE CHECK. buildLiveCreatorView must not assign a literal 0 to avgLikes/avgComments/
#      avgViews, and must not hardcode gender to all-zero.
#   2. RENDER CHECK. The Stats Grid must render avgLikes/avgViews conditionally (null-safe), not
#      by calling formatNumber directly on a value that can be null.
#   3. VITEST SUITE. Regression coverage for a creator profile whose audience/stats fields are
#      null/undefined from the API.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

FILE=src/pages/brand-creator-profile.tsx
[ -f "$FILE" ] || { echo "· $FILE missing — unavailable"; exit 2; }
FILE_CODE=$(code_view "$FILE") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· source check: buildLiveCreatorView must not hardcode avgLikes/avgComments/avgViews to 0"
hits=$(grep -nE '^\s*avg(Likes|Comments|Views):\s*0\b' "$FILE_CODE" 2>/dev/null)
if [ -n "$hits" ]; then
  echo "$hits"
  echo "VERDICT: broken — a live creator's avgLikes/avgComments/avgViews is still hardcoded to a fabricated"
  echo "         0 instead of an absent/null value (F-0260)"
  exit 1
fi
echo "  clean — no literal-0 avg metric"

echo "· source check: buildLiveCreatorView must not hardcode gender to an all-zero split"
if grep -nE "gender:\s*\{\s*female:\s*0,\s*male:\s*0,\s*other:\s*0\s*\}" "$FILE_CODE" >/dev/null 2>&1; then
  grep -nE "gender:\s*\{\s*female:\s*0,\s*male:\s*0,\s*other:\s*0\s*\}" "$FILE_CODE"
  echo "VERDICT: broken — audience.gender is still hardcoded to a fabricated 0/0/0 split instead of an"
  echo "         absent/null value (F-0260)"
  exit 1
fi
echo "  clean — no hardcoded zero gender split"

echo "· render check: Stats Grid reads avgLikes/avgViews through a null-safe path"
# The unsafe pattern is formatNumber(...avgLikes/avgViews) with NO null guard (`!= null` /
# `== null`) on the same line — a bare call prints '0' for an absent value. A guarded ternary
# (the fix) has the guard token on the same line as the call, so it's excluded here.
unsafe=$(grep -nE "formatNumber\(creator\.stats\.avg(Likes|Views)\)" "$FILE_CODE" 2>/dev/null | grep -vE '(!=|==) *null')
if [ -n "$unsafe" ]; then
  echo "$unsafe"
  echo "VERDICT: broken — Stats Grid still calls formatNumber directly on avgLikes/avgViews with no null"
  echo "         guard, which prints '0' for an absent value exactly like the fabricated-zero bug this"
  echo "         ticket exists to close (F-0260)"
  exit 1
fi
echo "  clean — no unguarded formatNumber(...avgLikes/avgViews) call"

SUITE=src/pages/__tests__/brand-creator-profile.absent-metrics.test.tsx
if [ ! -f "$SUITE" ]; then
  echo "· $SUITE missing — cannot run the vitest leg; source/render scans alone are not the whole gate"
  exit 2
fi
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed (--no-install) — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  echo "$out" | tail -40
  echo "VERDICT: broken — absent-metric regression test fails (F-0260)"
  exit 1
fi
echo "$out" | tail -10

echo "VERDICT: aligned (proved) — an absent avg-likes/avg-views/gender metric renders as absent, never as a"
echo "         fabricated measured 0"
echo "NOT CHECKED: whether a genuine server-reported 0 (once the DTO grows these fields) still renders as"
echo "             0 rather than being swallowed into the same 'not available' state — that requires a real"
echo "             field to exist first; any other absent-metric surface outside this file."
exit 0
