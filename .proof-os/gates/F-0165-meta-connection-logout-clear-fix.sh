#!/usr/bin/env bash
# F-0165 — clearCreatorSession() now also removes the `meta_connection` localStorage mirror, so
# a second creator on a shared browser is never seeded with the previous creator's Meta
# connection state (accountType, granted scopes). Re-runs the pinning suite live; exits 0 only
# if green.
#
# Usage: F-0165-meta-connection-logout-clear-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/lib/__tests__/creator-session.test.ts
