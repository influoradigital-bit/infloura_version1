#!/usr/bin/env bash
# F-0222-milestone-scoped-escrow-fund.sh — gate for F-0222 (escrow-hold-unbound).
# Proves the brand deal room funds a MILESTONE, not a bare campaign: the Payments
# panel must hand FundEscrowButton a real server `milestoneId`, pick the first
# PENDING milestone, withhold the control until both parties sign, and never
# render a brand-only money action on the creator mount (which passes no
# campaignId). A fund call with no milestoneId takes EscrowService.initiateFund's
# pool branch, which leaves collaboration_id NULL — the deal then never advances
# CONTRACTED -> IN_PROGRESS and the creator can never submit, with the brand's
# money already debited.
# Also holds the honesty half: "Escrow active" must come from the real
# escrowFunded field, never from contract status alone.
#   exit 0 = proved (suite green) · 1 = broken (suite red) · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed (--no-install) — unavailable"; exit 2; }

SUITE=src/components/brand/deal-room/deal-payments-tab.f0222.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?

if [ $rc -ne 0 ]; then
  echo "$out" | tail -30
  echo "VERDICT: broken — the deal room's fund path no longer carries a real milestoneId (F-0222 regressed)"
  exit 1
fi

echo "$out" | tail -6
echo "VERDICT: aligned (proved) — fund control passes a server milestoneId; creator mount stays clean"
echo "NOT CHECKED: Razorpay/checkout behaviour (that is F-0049's gate, fund_escrow_onfunded.sh);"
echo "             whether a hold created this way really lands with a non-null collaboration_id in"
echo "             the database — that needs a live run, not a component test; brand-chat.tsx's own"
echo "             wiring of campaignId/milestones, which no unit test covers."
exit 0
