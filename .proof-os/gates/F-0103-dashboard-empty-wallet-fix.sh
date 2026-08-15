#!/usr/bin/env bash
# F-0103 — fabricated-critical-state (money-path). The brand dashboard's unknown/pre-load/error/
# 404'd wallet zero-state (EMPTY_WALLET) must classify 'healthy', never 'critical'. The bug set
# EMPTY_WALLET.runwayDays to 0, which the health rule maps to 'critical', so a wallet whose balance
# is merely unknown flashed a red Critical badge + Recharge CTA. Re-runs the walletRunwayHealth
# rule suite and the EMPTY_WALLET tripwire (which imports the real symbol); exits 0 only if green.
#
# Usage: F-0103-dashboard-empty-wallet-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/lib/wallet-runway.test.ts src/components/brand/dashboard/dashboard-empty-wallet.test.ts
