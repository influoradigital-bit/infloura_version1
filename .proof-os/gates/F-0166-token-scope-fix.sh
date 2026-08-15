#!/usr/bin/env bash
# F-0166 — DeliverableVerificationService, MetricsPollingJob, and
# AudienceDemographicsJob must all resolve creator tokens via the
# creator-scoped getValidCreatorToken, never the workspace-scoped getValidToken
# with a creator row's null workspaceId (which MetaOAuthTokenRepository's query
# can never match). Re-runs all three pinning suites live; exits 0 only if all
# are green.
#
# Usage: F-0166-token-scope-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO/influora-api"
mvn -o -q test -Dtest=DeliverableVerificationServiceTest,MetricsPollingJobTest,AudienceDemographicsJobTest -DfailIfNoTests=true
