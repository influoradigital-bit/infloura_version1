#!/usr/bin/env bash
# F-0113 (CR-99/CR-100) — Meta Graph API path calls must use igBusinessAccountId,
# never the internal creatorProfileId ULID. Re-runs the three pinning regression
# suites live; exits 0 only if all three are green.
#
# Usage: F-0113-meta-igid-fix.sh [path-to-influora-api]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/influora-api}"
cd "$REPO"
mvn -o -q test \
  -Dtest=MetricsPollingJobTest,AudienceDemographicsJobTest,DeliverableVerificationServiceTest \
  -DfailIfNoTests=true
