#!/usr/bin/env bash
# F-0112 (CR-98) — the brand's own accept/counter/reject on deal-room-dashboard.tsx
# must refetch both deals AND messages. Re-runs the pinning regression suite live;
# exits 0 only if it's green.
#
# Usage: F-0112-actor-refresh-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/components/brand/deals/deal-room-dashboard-actor-refresh.test.tsx
