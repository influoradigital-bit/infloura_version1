#!/usr/bin/env bash
# F-0115 (CR-102) — a creator must be able to disconnect Meta/Instagram from
# the UI, with an accurate confirmation dialog. Re-runs the pinning regression
# suites (frontend + backend) live; exits 0 only if both are green.
#
# Usage: F-0115-disconnect-capability-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO"
npx vitest run src/components/creator/connected-accounts.test.tsx
(cd influora-api && mvn -o -q test -Dtest=MetaConnectionServiceTest,MetaOAuthControllerTest -DfailIfNoTests=true)
