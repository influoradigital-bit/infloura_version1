#!/usr/bin/env bash
# F-0170 — the desktop sidebar account trigger button in creator-layout.tsx had
# an empty accessible name while identity.displayName was still null (visible
# content was a bare skeleton span, no text). WCAG 2.1 AA 4.1.2. Fixed with an
# unconditional sr-only span ("Open account menu") that COMPOSES into the
# accessible name rather than overriding it, so the resolved-state name still
# contains the visible display name (WCAG 2.1 AA 2.5.3 Label in Name) — a plain
# aria-label would have violated that. Re-runs the pinning regression suite
# live plus the four adjacent header suites to catch regressions, including the
# accessible-name collision risk against F-0169's mobile avatar trigger.
#
# Usage: F-0170-desktop-account-trigger-a11y-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run \
  src/components/creator/creator-layout-desktop-account-trigger.test.tsx \
  src/components/creator/creator-layout-mobile-account-menu.test.tsx \
  src/components/creator/creator-layout-mobile-menu.test.tsx \
  src/components/creator/creator-layout-search.test.tsx \
  src/components/creator/creator-layout-logout.test.tsx
