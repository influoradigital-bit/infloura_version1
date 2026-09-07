#!/usr/bin/env bash
# Gate for F-0749 — a present CreatorMetric with a null avgEngagementRate must not
# throw, and must be scored NEUTRAL rather than penalised as low engagement.
#
# Origin: RateEstimationService dereferenced getAvgEngagementRate() unguarded. The
# column is nullable (CreatorMetric:91 declares no nullable=false), so a row can carry
# a follower count with engagement not yet computed. The NPE propagated out of
# estimate() into CreatorAgentPreferencesService.createWithComputedDefaults and 500'd
# GET /api/v1/creator/agent-preferences — the request the creator Co-pilot page issues
# on load. Observed live on production 2026-09-07: the page died and "Open Meera"
# rendered "An unexpected error occurred".
#
# Exit 0 both cases proved · 1 broken · 2 cannot run.
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$REPO/influora-api"

# There is NO aggregator pom in this repo: influora-api/pom.xml is the only one, and
# `mvn -pl influora-api` dies in the reactor and runs nothing — a gate written that way
# can never go green. Run with the working directory inside the module.
if [ ! -f "$MODULE/pom.xml" ]; then
  echo "rate-estimation-null-engagement: UNAVAILABLE — no pom at $MODULE/pom.xml"
  exit 2
fi
command -v mvn >/dev/null 2>&1 || { echo "rate-estimation-null-engagement: UNAVAILABLE — mvn not on PATH"; exit 2; }

# Name the two methods explicitly with failIfNoTests. Running the whole CLASS would stay
# green if someone deleted these two tests — the other 32 would still pass and the gate
# would report success while proving nothing. Naming the methods makes deletion a failure.
TESTS='RateEstimationServiceTest#testNullEngagementRateDoesNotThrow+testNullEngagementRateIsNeutralNotPenalised'

LOG="$(mktemp)"
# Do NOT pipe mvn into grep here: the pipeline reports grep's exit status, so a failed
# suite arrives as "exit code 0". Capture to a file, read mvn's own status, then read.
( cd "$MODULE" && mvn -o -B test -Dtest="$TESTS" -DfailIfNoTests=true ) > "$LOG" 2>&1
RC=$?

LINE="$(grep -E '^\[INFO\] Tests run:' "$LOG" | tail -1)"

if [ "$RC" -ne 0 ]; then
  echo "BROKEN  mvn exited $RC"
  grep -E 'NullPointerException|Tests run:|ERROR\]' "$LOG" | head -8
  echo "  full log: $LOG"
  exit 1
fi

# Both named methods must have actually executed. A green run of ZERO tests is the
# vacuous pass this line exists to refuse.
if ! echo "$LINE" | grep -q 'Tests run: 2,'; then
  echo "BROKEN  expected exactly 2 tests to run, got: ${LINE:-<no Tests run line>}"
  echo "  full log: $LOG"
  exit 1
fi
if ! echo "$LINE" | grep -q 'Failures: 0, Errors: 0'; then
  echo "BROKEN  $LINE"
  exit 1
fi
# A skipped test is not a passing test. Read the Skipped count, never the exit code alone.
if ! echo "$LINE" | grep -q 'Skipped: 0'; then
  echo "BROKEN  a test was SKIPPED, which proves nothing: $LINE"
  exit 1
fi

rm -f "$LOG"
echo "rate-estimation-null-engagement: PROVED — $LINE"
echo "NOT CHECKED: whether the RUNNING production jar contains this fix (the container"
echo "  serves a prebuilt influora-api.jar; passing here proves the source, not the box),"
echo "  and whether any OTHER unguarded dereference of a nullable metric column remains"
exit 0
