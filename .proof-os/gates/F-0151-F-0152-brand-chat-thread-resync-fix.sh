#!/usr/bin/env bash
# F-0151/F-0152 — brand-chat.tsx's message thread must survive a background resync
# (deal-switch, SSE onReconnect, foreground visibility resync, manual Retry): the thread render
# is no longer gated on messagesLoading/messagesError, and loadMessages now carries the same
# request-token + mount guard as creator-chat.tsx's equivalent, so a slower stale request can
# never clobber a faster newer one and a failed background resync no longer wipes an
# already-rendered thread. Re-runs the pinning suite live; exits 0 only if green.
#
# Usage: F-0151-F-0152-brand-chat-thread-resync-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/pages/brand-chat-visibility-resync.test.tsx src/pages/brand-chat-proposal.test.tsx
