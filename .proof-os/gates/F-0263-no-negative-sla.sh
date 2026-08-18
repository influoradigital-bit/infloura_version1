#!/usr/bin/env bash
# F-0263-no-negative-sla.sh — gate for F-0263 (negative-duration-rendered).
#
# src/pages/brand-pipeline.tsx's `hoursUntil(deal.nextDeadline)` has no floor: an overdue deal
# (deadline already in the past) produces a negative `slaHoursRemaining`, and all three render
# sites (board CollaborationCard banner, list-view SLA column, timeline-view badge) printed it
# verbatim — "at risk: -37h remaining" reads as a countdown still ticking, not as overdue. A
# negative number is type-correct (`number | undefined`), so nothing caught this statically.
#
# Two legs:
#   1. Static: an overdue-aware formatter exists and is actually used at all three render sites
#      that print slaHoursRemaining — not just defined and orphaned.
#   2. vitest: a deal whose nextDeadline is in the PAST (the live-mode mapping path, not the
#      hardcoded mock array) renders an overdue state, and no raw negative number reaches the
#      DOM (no "-<digits>h" / "−<digits>h" text).
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

PAGE=src/pages/brand-pipeline.tsx
[ -f "$PAGE" ] || { echo "· $PAGE missing — unavailable"; exit 2; }
PAGE_CODE=$(code_view "$PAGE") || { echo "$(code_why) - unavailable"; exit 2; }

fail=0

echo "· leg 1: an overdue-aware formatter is defined and used at all three render sites"
if ! grep -qE "function formatOverdueHours" "$PAGE_CODE"; then
  echo "  formatOverdueHours not found — no overdue-aware formatting introduced"
  fail=1
fi
uses=$(grep -cE "formatOverdueHours\(" "$PAGE_CODE")
# 1 definition + >=1 call per render site (card banner, list SLA column, timeline badge) = >=4
# occurrences of the identifier total (def + 3 call sites minimum).
if [ "$uses" -lt 4 ]; then
  echo "  formatOverdueHours referenced only $uses time(s) — expected the definition plus a call"
  echo "  at each of the card banner, list SLA column, and timeline badge render sites"
  fail=1
fi
[ "$fail" -eq 0 ] && echo "  clean — formatOverdueHours defined and used at all three sites"

if [ "$fail" -ne 0 ]; then
  echo "VERDICT: broken — no overdue-aware rendering wired into brand-pipeline.tsx (F-0263)"
  exit 1
fi

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/pages/__tests__/brand-pipeline.sla.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — an overdue deal still renders a negative hour count (F-0263)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — a past-deadline deal renders an overdue state, not a negative"
echo "         hour count"
echo "NOT CHECKED: daysRemaining (a separate, also-unfloored field, out of this ticket's scope);"
echo "             whether the server's nextDeadline itself is ever wrong; list/timeline view"
echo "             switching in the live test (only the default board view is asserted)."
exit 0
