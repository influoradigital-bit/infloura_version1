#!/usr/bin/env bash
# F-0109 (CR-90) — the sidebar/mobile-dropdown creator logout path must call the
# server logout endpoint before clearing local session state. Re-runs the
# pinning regression suite live; exits 0 only if it's green.
#
# Usage: F-0109-sidebar-logout-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/components/creator/creator-layout-logout.test.tsx
