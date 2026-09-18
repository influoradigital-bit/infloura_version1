#!/usr/bin/env bash
# gates/f0951-f0954-creator-analytics.sh — closes F-0951, F-0952, F-0953, F-0954.
#
#   F-0951 dropped-field      — follower count was computed then dropped from CreatorMetricsResponse.
#   F-0952 dropped-field      — per-post likes/comments/saves/shares/views sent, never rendered.
#   F-0953 mislabeled-metric  — per-post averages labelled "Total ..."; a duplicate views card.
#   F-0954 stale-until-cron   — no metrics sync on connect; now an @Async AFTER_COMMIT listener.
#
# Runs the tests on a clean `git archive HEAD` so another session's uncommitted edits can neither
# break nor green it. Falsified 2026-09-18:
#   - returning 0L for followers                        -> AnalyticsServiceTest red
#   - UI files restored from the pre-fix commit         -> 11 frontend tests red
#   - a video_views column re-added (never written)     -> post-counts test red
#   - 'Total Engagements' restored on /brand/analytics  -> aggregate test red
#   - demographics panel restored from HEAD             -> meta-threshold test red
#   - onCreatorConnected made a no-op                   -> 3 MetricsPollingJobTest red
#   - @TransactionalEventListener -> plain @EventListener -> testConnectListenerIsAsyncAfterCommit red
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "· not a git repo — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "· npx not on PATH — unavailable"; exit 2; }
TMP="$(mktemp -d)" || exit 2
git -C "$ROOT" archive HEAD | tar -x -C "$TMP" || { echo "· archive failed — unavailable"; exit 2; }

for f in src/components/analytics/__tests__/ContentPerformancePanel.post-counts.test.tsx \
         influora-api/src/test/java/com/influora/job/MetricsPollingJobTest.java; do
  [ -f "$TMP/$f" ] || { echo "VERDICT: broken — $f is not committed"; exit 1; }
done

( cd "$TMP/influora-api" && mvn -o -B test \
    -Dtest='AnalyticsService*Test,CreatorAnalyticsServiceTest,CreatorAnalyticsControllerTest,MetricsPollingJobTest' \
    -Dsurefire.failIfNoSpecifiedTests=false ) > "$TMP/mvn.log" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
  if grep -qE "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$TMP/mvn.log"; then
    grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])|FAIL" "$TMP/mvn.log" | head -5
    echo "VERDICT: broken — creator analytics backend tests fail"; exit 1
  fi
  tail -5 "$TMP/mvn.log"; echo "· mvn did not reach the tests — unavailable"; exit 2
fi
for t in testGetCreatorMetricsReturnsNewestFollowerCount testFollowerGrowthUsesTheRequestedWindow testFollowerGrowthIgnoresOtherPlatformsInWindow testConnectListenerIsAsyncAfterCommit testConnectEventPollsThatCreatorOnce; do
  grep -rq "$t" "$TMP/influora-api/src/test" || { echo "VERDICT: broken — regression test $t is gone"; exit 1; }
done

# The frontend needs the repo's installed node_modules.
# A directory junction, never `ln -s`: under Git Bash without Windows developer mode `ln -s`
# silently COPIES the whole tree. The junction is left in $TMP; never `rm -rf` through it.
if command -v cygpath >/dev/null 2>&1; then
  cmd //c mklink /J "$(cygpath -w "$TMP/node_modules")" "$(cygpath -w "$ROOT/node_modules")" >/dev/null 2>&1
else
  ln -s "$ROOT/node_modules" "$TMP/node_modules"
fi
[ -d "$TMP/node_modules/vitest" ] || { echo "· node_modules not linkable — unavailable"; exit 2; }
( cd "$TMP" && npx vitest run src/pages/creator-analytics.test.tsx \
    src/pages/__tests__/brand-creator-analytics.f0886-upgrade-gate.test.tsx \
    src/pages/__tests__/brand-analytics.aggregate.test.tsx     src/pages/__tests__/brand-analytics.metric-titles.test.ts src/pages/__tests__/brand-analytics.single-view.test.tsx     src/pages/__tests__/brand-creator-analytics.live-demo-gate.test.tsx \
    src/components/analytics/__tests__/ContentPerformancePanel.post-counts.test.tsx     src/components/analytics/__tests__/AudienceDemographicsPanel.meta-threshold.test.tsx ) > "$TMP/vitest.log" 2>&1
RC=$?
CLEAN=$(sed 's/\x1b\[[0-9;]*m//g' "$TMP/vitest.log")
if [ $RC -ne 0 ]; then
  if echo "$CLEAN" | grep -qE "Tests +.*[0-9]+ failed"; then
    echo "$CLEAN" | grep -E "FAIL|Tests " | head -6
    echo "VERDICT: broken — creator analytics frontend tests fail"; exit 1
  fi
  echo "$CLEAN" | tail -5; echo "· vitest did not reach the tests — unavailable"; exit 2
fi
echo "$CLEAN" | grep -E "Test Files|Tests " | tail -2
echo "VERDICT: proved — F-0951..F-0954 creator analytics fixes hold on HEAD"
exit 0
