#!/usr/bin/env bash
# F-0131 — no-live-region (money-path). The Fund Campaign Escrow card must expose a polite
# role=status live region so screen readers hear its state changes, above all the FundEscrowButton
# appearing after a campaign is picked (an otherwise-silent DOM insertion). Re-runs the render-test
# gate, which asserts getByRole('status') + aria-live=polite and the ready-to-fund transition;
# removing the region fails the suite. Exits 0 only if green.
#
# Usage: F-0131-fund-escrow-live-region.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/components/brand/wallet/FundEscrowStatus.test.tsx
