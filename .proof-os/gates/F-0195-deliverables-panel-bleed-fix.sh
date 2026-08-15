#!/usr/bin/env bash
# F-0195 — brand-chat.tsx's deliverables panel: the deal-switch effect now clears
# liveDeliverables (mirroring F-0192's message-thread clear), and loadDeliverables carries the
# same request-token + mount guard F-0152 gave loadMessages, so a slow, stale response for a
# previously-selected deal can no longer overwrite a newer deal's freshly-loaded rows.
# Re-runs the pinning suites live; exits 0 only if green.
#
# Usage: F-0195-deliverables-panel-bleed-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/pages/brand-chat-visibility-resync.test.tsx src/pages/brand-chat-proposal.test.tsx
