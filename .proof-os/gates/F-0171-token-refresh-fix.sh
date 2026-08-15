#!/usr/bin/env bash
# F-0171 — MetaTokenRefreshService must resolve/persist creator-owned tokens via
# the creator-scoped getValidCreatorToken/storeCreatorToken, never the
# workspace-scoped getValidToken/storeToken (which can never match a creator
# row's null workspaceId). Re-runs the pinning suite live; exits 0 only if
# green.
#
# Usage: F-0171-token-refresh-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO/influora-api"
mvn -o -q test -Dtest=MetaTokenRefreshServiceTest -DfailIfNoTests=true
