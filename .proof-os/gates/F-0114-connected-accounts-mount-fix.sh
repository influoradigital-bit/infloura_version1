#!/usr/bin/env bash
# F-0114 (CR-101) — ConnectedAccounts must be mounted on the creator Settings
# page. Re-runs the pinning regression suite plus its two siblings live;
# exits 0 only if all three are green.
#
# Usage: F-0114-connected-accounts-mount-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run \
  src/pages/creator-settings-connected-accounts.test.tsx \
  src/pages/creator-settings-logout.test.tsx \
  src/pages/creator-settings-change-password.test.tsx
