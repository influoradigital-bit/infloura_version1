#!/usr/bin/env bash
# F-0179 — the desktop sidebar account trigger's Avatar (initials fallback +
# avatar image) leaked into its composed accessible name once identity
# resolved (e.g. "Open account menu PS Priya Sharma"), announcing redundant
# letters. Fixed by marking the Avatar aria-hidden — decorative once the
# sr-only label (F-0170) and the visible display name already convey who/what
# this control is. Re-runs the pinning regression suite live plus the four
# adjacent header suites to catch regressions.
#
# Usage: F-0179-avatar-initials-leak-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run \
  src/components/creator/creator-layout-desktop-account-trigger.test.tsx \
  src/components/creator/creator-layout-mobile-account-menu.test.tsx \
  src/components/creator/creator-layout-mobile-menu.test.tsx \
  src/components/creator/creator-layout-search.test.tsx \
  src/components/creator/creator-layout-logout.test.tsx
