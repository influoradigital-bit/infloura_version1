#!/usr/bin/env bash
# F-0224-release-has-a-caller.sh — gate for F-0224 (orphan-money-endpoint).
#
# `POST /wallet/escrow/release` shipped with ZERO callers anywhere in the app. Auto-release on
# approval (BrandDeliverableService) was the only path money could take out of escrow, and when
# it skipped — F-0223 documents eight conditions that do exactly that, and did it silently — no
# screen in the product could retry. Funds sat in escrow, approved, unreachable.
#
# Two legs:
#   1. The client method has a real caller. This is the whole defect: the wrapper existed and was
#      dead, so its presence proves nothing. A grep over src/ excluding api.ts and tests.
#   2. The behaviour suite: the right milestone id reaches the endpoint, the server's refusal is
#      surfaced verbatim rather than a generic failure, the control is absent for non-FUNDED
#      milestones and for derived placeholder rows (which carry no server id), and it never
#      appears on the creator mount.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

echo "· releasePayout has a real caller outside its own declaration"
callers=$(grep -rn "releasePayout" src/ --include='*.tsx' --include='*.ts' 2>/dev/null \
          | grep -v "src/lib/api.ts" | grep -v "\.test\." || true)
if [ -z "$callers" ]; then
  echo "VERDICT: broken — POST /wallet/escrow/release is orphaned again; funds stuck in escrow"
  echo "         have no path out of the product (F-0224)"
  exit 1
fi
printf '%s\n' "$callers" | head -3
echo "  clean — the endpoint is reachable from the UI"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/brand/deal-room/deal-payments-tab.f0222.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — the release control or its guards regressed (F-0224)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — a funded milestone can be released from the deal room"
echo "NOT CHECKED: that a release actually MOVES money — the endpoint is mocked here, and only a"
echo "             live run against a real ledger shows the transfer; the server's own"
echo "             preconditions (dispute freeze, release_condition, funded state), which are"
echo "             deliberately NOT mirrored in the UI — re-deriving them client-side is the CR-27"
echo "             trap, so the button is offered and the server refuses; and the creator has no"
echo "             visibility into a release happening, which is a separate gap."
exit 0
