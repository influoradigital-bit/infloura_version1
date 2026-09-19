#!/usr/bin/env bash
# gates/f0961-analytics-verified-rows-only.sh — closes F-0961 (unverified-as-verified).
#
# Analytics headline figures, growth and trend used the newest creator_metrics row of ANY
# data_source, so a creator-declared (CREATOR_REPORTED) follower count was shown to brands as the
# creator's analytics. Now: findByCreatorProfileIdAndDataSourceOrderByTimeDesc(META_API) plus an
# in-memory isPlatformVerified guard on both the headline and the date-window paths.
#
# Runs the tests on a clean `git archive HEAD`. Falsified 2026-09-19 (plus kabir's own 13 mutants):
#   - unfiltered finder                       -> 9 failures, 6 errors
#   - in-memory guard removed                 -> 4 failures
#   - window filter removed                   -> testCreatorReportedRowsAreNotGrowthOrTrendPoints
#   - query ignoring the source (H2)          -> CreatorMetricsRepositorySourceQueryTest
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "· not a git repo — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
TMP="$(mktemp -d)" || exit 2
git -C "$ROOT" archive HEAD influora-api | tar -x -C "$TMP" || { echo "· archive failed — unavailable"; exit 2; }
for f in influora-api/src/test/java/com/influora/repository/CreatorMetricsRepositorySourceQueryTest.java \
         influora-api/src/test/java/com/influora/service/analytics/AnalyticsServiceTest.java; do
  [ -f "$TMP/$f" ] || { echo "VERDICT: broken — $f is not committed"; exit 1; }
done
for t in testNewerCreatorReportedRowIsNotTheHeadline testCreatorReportedRowsAreNotGrowthOrTrendPoints \
         testOnlyCreatorReportedRowsGiveTheEmptyResponse declaredBurstCannotStarveTheMetaRows; do
  grep -rq "$t" "$TMP/influora-api/src/test" || { echo "VERDICT: broken — regression test $t is gone"; exit 1; }
done
( cd "$TMP/influora-api" && mvn -o -B test \
    -Dtest='AnalyticsService*Test,CreatorAnalytics*Test,CreatorMetricsRepositorySourceQueryTest' \
    -Dsurefire.failIfNoSpecifiedTests=false ) > "$TMP/mvn.log" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  if grep -qE "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$TMP/mvn.log"; then
    grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$TMP/mvn.log" | head -5
    echo "VERDICT: broken — F-0961 analytics source tests fail"; exit 1
  fi
  tail -5 "$TMP/mvn.log"; echo "· mvn did not reach the tests — unavailable"; exit 2
fi
grep -q "CreatorMetricsRepositorySourceQueryTest" "$TMP/mvn.log" || { echo "· H2 query test did not run — unavailable, never green"; exit 2; }
grep -E "Tests run: [0-9]+, Failures: 0, Errors: 0, Skipped: [0-9]+$" "$TMP/mvn.log" | tail -1
echo "VERDICT: proved — F-0961 analytics read only Meta-synced rows"
exit 0
