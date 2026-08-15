#!/usr/bin/env bash
# F-0173 — MetaTokenStorage.storeCreatorToken's revoke-then-insert rotation (used on every
# creator token refresh, F-0171) now preserves the ORIGINAL row's createdAt on the new row
# instead of stamping a fresh "now", so MetaConnectionService.getStatus's "connected since" date
# no longer silently advances forward on every ~55-day auto-refresh cycle. First-time connects
# (no existing row) are unaffected and still stamp a real, current createdAt. Re-runs the
# pinning suite live; exits 0 only if green.
#
# Usage: F-0173-connected-since-date-stable-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO/influora-api"
mvn -o -q test -Dtest=MetaTokenStorageTest -DfailIfNoTests=true
