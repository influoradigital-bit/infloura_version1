#!/usr/bin/env bash
# F-0295-absent-audience-surfaces.sh — gate for F-0295 (absent-metric-surfaces-missed-in-scope).
#
# buildLiveCreatorView (src/pages/brand-creator-profile.tsx) sets audience.ageGroups and
# audience.topCities to `[]` in live mode (DiscoveryDtos.CreatorPublicProfileResponse has no
# audience-demographics fields at all), and the Audience tab renders both with a bare `.map()`
# under the "Age Distribution" / "Top Cities" headings — an empty box with no "Not available"
# state. This is the same class of bug as F-0260 (gender split rendering a fabricated
# "Female 0% Male 0%"), fixed three lines away in the same file with an explicit
# `== null ? <absent state> : <real content>` branch. ageGroups/topCities were left out of that
# fix's scope even though buildLiveCreatorView can leave them just as unpopulated.
#
# This gate is deliberately NOT satisfied by grepping for a literal string the fix is expected
# to add (e.g. "Not available") — that would also pass a wrong fix that prints the string
# unconditionally, or one that only handles `null` and still renders nothing for `[]`. Instead
# it renders the real page with a live creator whose ageGroups/topCities are empty arrays (the
# actual shape the live DTO produces) and asserts an absent-state message is on screen — the
# behavior, not the source text.
#
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

PAGE=src/pages/brand-creator-profile.tsx
[ -f "$PAGE" ] || { echo "· $PAGE missing — unavailable"; exit 2; }
PAGE_CODE=$(code_view "$PAGE") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· buildLiveCreatorView still exists and maps audience demographics"
grep -q "buildLiveCreatorView" "$PAGE_CODE" || {
  echo "VERDICT: broken — buildLiveCreatorView is gone; cannot verify the live-mode mapping (F-0295)"
  exit 1; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/pages/__tests__/brand-creator-profile.absent-audience.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE (behavioral: empty ageGroups/topCities must render an absent state)"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — Age Distribution / Top Cities render as empty boxes for a live creator"
  echo "         with no audience-demographics data, instead of an explicit absent state (F-0295)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — an empty audience array renders an explicit absent state, matching"
echo "         the gender-split / authenticity pattern already in this file"
echo "NOT CHECKED: whether a real (non-empty) ageGroups/topCities payload from a future backend"
echo "             endpoint renders correctly — only the absent-state path is exercised here; and"
echo "             whether other still-empty CreatorDisplayModel fields (portfolio, rates,"
echo "             pastBrands, reviews) need the same treatment — out of scope for F-0295."
exit 0
