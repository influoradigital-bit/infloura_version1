#!/usr/bin/env bash
# F-0105 (CR-55) — creator-campaign-detail.tsx must use the canonical
# getApplicationStatusLabel, never a raw enum fallback. Re-runs the pinning
# regression suite live; exits 0 only if it's green.
#
# Usage: F-0105-status-label-drift-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/pages/creator-campaign-detail-status-label.test.tsx
