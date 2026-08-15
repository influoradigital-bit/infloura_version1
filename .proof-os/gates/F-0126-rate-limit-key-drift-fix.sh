#!/usr/bin/env bash
# F-0126 — MetricsPollingJob, AudienceDemographicsJob, and DeliverableVerificationService must
# key MetaRateLimitTracker calls (getCurrentUsage/markLimited, and the businessAccountId arg
# passed into InstagramInsightsClient.getMediaInsights) on igBusinessAccountId, the same
# namespace MetaGraphApiClient itself uses for its enforcement and usage-header parsing — never
# the internal ULID creatorProfileId, which put every job-level pre-flight guard in a disjoint,
# permanently-empty key space. Re-runs the pinning suites live; exits 0 only if green.
#
# Usage: F-0126-rate-limit-key-drift-fix.sh [repo-root]
set -euo pipefail
REPO="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO/influora-api"
mvn -o -q test -Dtest=MetricsPollingJobTest,AudienceDemographicsJobTest,DeliverableVerificationServiceTest -DfailIfNoTests=true
