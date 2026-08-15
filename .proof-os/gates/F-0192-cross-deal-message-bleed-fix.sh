#!/usr/bin/env bash
# F-0192 — brand-chat.tsx and creator-chat.tsx now clear their message list synchronously
# when selectedDeal.id changes (scoped to a genuine deal switch, not any same-deal resync
# caller — that would reopen F-0151/F-0152), so switching deals can no longer render the
# previous deal's thread under the new deal's header while the new fetch is in flight.
# Re-runs the pinning suites live; exits 0 only if green.
#
# Usage: F-0192-cross-deal-message-bleed-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run \
  src/pages/brand-chat-visibility-resync.test.tsx \
  src/pages/brand-chat-proposal.test.tsx \
  src/pages/creator-chat-visibility-resync.test.tsx \
  src/pages/creator-chat-refresh.test.tsx \
  src/pages/creator-chat-verified-badge.test.tsx
