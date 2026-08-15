#!/usr/bin/env bash
# F-0110 (CR-93) — both creator and brand deal-chat pages must resync messages +
# deal state on tab foreground, independent of the SSE reconnect logic. Re-runs
# the pinning regression suites live; exits 0 only if both are green.
#
# Usage: F-0110-chat-visibility-resync.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run \
  src/pages/creator-chat-visibility-resync.test.tsx \
  src/pages/brand-chat-visibility-resync.test.tsx
