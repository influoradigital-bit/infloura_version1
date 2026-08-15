#!/usr/bin/env bash
# F-0099 — false-critical-runway-fallback (money-path). A wallet's runwayDays is number|null;
# null means a dormant/funded wallet (no spend in the trailing window) and MUST read 'healthy',
# never 'critical'. The bug defaulted/coerced the unknown case to 0 and compared `< 14`, so
# `null < 14`/`0 < 14` (both true in JS) flashed a false red CRITICAL alarm on every pre-load,
# 404'd new workspace, or dormant wallet. Re-runs the pinning regression suite for the shared
# runwayTone helper; exits 0 only if green.
#
# Usage: F-0099-runway-alarm-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/lib/wallet-runway.test.ts
