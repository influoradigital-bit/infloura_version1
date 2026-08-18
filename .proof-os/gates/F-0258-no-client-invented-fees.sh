#!/usr/bin/env bash
# F-0258-no-client-invented-fees.sh — gate for F-0258 (client-invented-fee-split).
#
# brand-campaign-detail.tsx rendered "Creator Pay (est.)", "Platform Fee (10%)", "GST (18% on
# fee)" and "Contingency" as money, computed client-side as budget.max * 0.82 / 0.10 / 0.018 /
# 0.062 (and the sibling Settlement Summary block did the same off budget.spent). No fee-schedule
# endpoint backed any of it — a real one exists (GET /brand/platform-fee, wired at
# api.wallet.brandPlatformFee) but returns only a single feeBps/feePercent, never a four-way
# split. The whole breakdown sat under a Lock icon that implies the money is already
# apportioned/escrowed, which is also false at this stage (nothing has been funded yet).
#
# Three legs:
#   1. CONTENT SCAN. The fabricated multipliers (0.82 / 0.018 / 0.062 — arbitrary, not derivable
#      from any real rate) must not reappear anywhere in the page.
#   2. WIRING CHECK. The page must actually call the real server fee source, not merely delete
#      the fake one and go back to silence.
#   3. VITEST SUITE. Regression coverage for the fee block against a live campaign.
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

FILE=src/pages/brand-campaign-detail.tsx
[ -f "$FILE" ] || { echo "· $FILE missing — unavailable"; exit 2; }
FILE_CODE=$(code_view "$FILE") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· content scan: $FILE for the fabricated fee-split multipliers"
# Deliberately narrow to the arbitrary constants (0.82 Creator Pay, 0.018 GST, 0.062
# Contingency) that have no server-side basis at all — not 0.10/0.15, which are legitimate
# real platform-fee rates once sourced from the server response rather than hardcoded.
hits=$(grep -nE '\* ?0\.82\b|\* ?0\.018\b|\* ?0\.062\b' "$FILE_CODE" 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*|\{/\*)')
if [ -n "$hits" ]; then
  echo "$hits"
  echo "VERDICT: broken — client-invented fee-split multipliers still present in $FILE (F-0258)"
  exit 1
fi
echo "  no fabricated multiplier found"

echo "· wiring check: $FILE calls the real brand platform-fee endpoint"
if ! grep -q "brandPlatformFee" "$FILE_CODE"; then
  echo "VERDICT: broken — no call to the real GET /brand/platform-fee source (api.wallet.brandPlatformFee);"
  echo "         the fee block is either still invented or silently deleted with nothing real in its place (F-0258)"
  exit 1
fi
echo "  clean — brandPlatformFee is wired"

echo "· content scan: Budget Breakdown card no longer sits under a Lock (implies escrowed/apportioned money)"
lock_near_budget=$(grep -n "Budget Breakdown" "$FILE_CODE" | head -1)
if [ -n "$lock_near_budget" ]; then
  ln=$(echo "$lock_near_budget" | cut -d: -f1)
  start=$((ln - 3))
  [ "$start" -lt 1 ] && start=1
  context=$(sed -n "${start},${ln}p" "$FILE_CODE")
  if echo "$context" | grep -q "Lock"; then
    echo "$context"
    echo "VERDICT: broken — Budget Breakdown card still uses the Lock icon, implying the money is already"
    echo "         apportioned/escrowed (F-0258)"
    exit 1
  fi
fi
echo "  clean — no Lock icon on the budget breakdown"

SUITE=src/pages/__tests__/brand-campaign-detail.fee-block.test.tsx
if [ ! -f "$SUITE" ]; then
  echo "· $SUITE missing — cannot run the vitest leg; content/wiring scans alone are not the whole gate"
  exit 2
fi
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed (--no-install) — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  echo "$out" | tail -40
  echo "VERDICT: broken — fee-block regression test fails (F-0258)"
  exit 1
fi
echo "$out" | tail -10

echo "VERDICT: aligned (proved) — no client-invented fee split renders as money; the real fee source is wired"
echo "NOT CHECKED: whether GET /brand/platform-fee's returned feePercent is itself correct for the caller's"
echo "             plan (that's PlatformFeeService's job, not this page's); the mock-mode value; any fee"
echo "             surface outside this file; whether a brand with no budget set yet sees a sane empty state"
echo "             (covered structurally, not exercised here)."
exit 0
