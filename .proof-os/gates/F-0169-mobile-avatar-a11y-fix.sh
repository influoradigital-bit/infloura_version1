#!/usr/bin/env bash
# F-0169 — the mobile avatar DropdownMenuTrigger button in creator-layout.tsx was
# icon/avatar-only with no aria-label; accessible name was empty, or a bare
# initials string once identity loaded. WCAG 2.1 AA 4.1.2. Fixed with a fixed
# string aria-label="Account menu" that does not depend on identity state.
# Re-runs the pinning regression suite live; exits 0 only if it's green, and
# also re-runs the three adjacent header suites to catch regressions.
#
# Usage: F-0169-mobile-avatar-a11y-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run \
  src/components/creator/creator-layout-mobile-account-menu.test.tsx \
  src/components/creator/creator-layout-mobile-menu.test.tsx \
  src/components/creator/creator-layout-search.test.tsx \
  src/components/creator/creator-layout-logout.test.tsx
