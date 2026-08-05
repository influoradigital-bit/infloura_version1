#!/usr/bin/env bash
# fund_escrow_onfunded.sh — gate for F-0049 (unwired-callback-prop).
# Proves FundEscrowButton invokes the parent `onFunded` callback EXACTLY ONCE on
# a SERVER-verified FUNDED escrow (both the immediate-fund and the Razorpay
# poll path) and NEVER on a dismissed Checkout. Runs the real hook logic; only
# Razorpay/api/meera-api are mocked.
#   exit 0 = proved (suite green) · 1 = broken (suite red) · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed (--no-install) — unavailable"; exit 2; }

out=$(node_modules/.bin/vitest run src/components/feature/meera/FundEscrowButton.test.tsx 2>&1); rc=$?
printf '%s\n' "$out" | grep -E "Test Files|Tests |onFunded|FAIL|failed" | tail -8
if [ $rc -eq 0 ]; then
  echo "PASS: onFunded fires once on server-verified FUNDED, never on dismiss"
  echo "NOT CHECKED: real Razorpay SDK / live server polling (mocked); runtime UI beyond this suite"
  exit 0
fi
echo "FAIL: FundEscrowButton money-path suite is red (rc=$rc)"
exit 1
