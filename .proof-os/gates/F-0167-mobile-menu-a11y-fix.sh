#!/usr/bin/env bash
# F-0167 — the mobile hamburger menu button in creator-layout.tsx had no accessible
# name (no aria-label, no text child, icon rendered aria-hidden) — WCAG 2.1 AA
# 4.1.2. Fixed with a state-driven aria-label ('Open menu' / 'Close menu') and
# aria-expanded. Re-runs the pinning regression suite live; exits 0 only if it's
# green, and also re-runs the two adjacent header suites to catch regressions.
#
# Usage: F-0167-mobile-menu-a11y-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run \
  src/components/creator/creator-layout-mobile-menu.test.tsx \
  src/components/creator/creator-layout-search.test.tsx \
  src/components/creator/creator-layout-logout.test.tsx
