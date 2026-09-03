#!/usr/bin/env bash
# F-0478-absent-score-not-fabricated.sh — gate for F-0478 (fabricated-score-from-absent-data).
#
# media_metrics is written by nothing, so ScoreCalculationJob's recentMedia is always empty.
# QualityScoreService.calculate guarded only latestMetric.isEmpty(), so with no media it still
# computed: engagement 0, frequency 0, consistency 50 ("not enough data"), audienceMatch 50 —
# a composite of EXACTLY 20.00 for every connected creator. 20.00 is the dangerous shape: unlike
# 0 it does not read as "unscored", it reads as a measured, poor creator. It also fed
# RateEstimationService, where 20 < 40 cut every creator's estimated rate to 0.8x and 20 > 0
# bought a fabricated +20 confidence.
#
# The class this gate closes is NOT "the composite is 20" — a weight tweak would move that number
# without fixing anything. It is "absent data must not render as a measured score anywhere on the
# path". F-0260 already ruled on this shape for avgLikes/audience.gender; this is the same ruling
# applied to the algorithmic scores.
#
# Three legs, in cost order:
#   1. SOURCE. QualityScoreService must guard recentMedia, and AdminCreatorService must not
#      substitute BigDecimal.ZERO for a score that was never computed.
#   2. COVERAGE. The two regression test methods must still exist by name — a behavioural gate
#      whose tests can be quietly deleted is not a gate (F-0329).
#   3. BEHAVIOUR. Those tests must actually pass.
#   exit 0 = proved · 1 = broken · 2 = unavailable (never a false red)
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

QSS=influora-api/src/main/java/com/influora/service/scoring/QualityScoreService.java
ACS=influora-api/src/main/java/com/influora/service/admin/AdminCreatorService.java
QST=influora-api/src/test/java/com/influora/service/scoring/QualityScoreServiceTest.java
RET=influora-api/src/test/java/com/influora/service/scoring/RateEstimationServiceTest.java

for f in "$QSS" "$ACS" "$QST" "$RET"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 || {
  echo "· no python on PATH — unavailable"; exit 2; }
PY=$(command -v python || command -v python3)

# F-0266: grep CODE, not file bytes. This gate's own header quotes the strings it forbids, and a
# byte-level grep would fail the very fix it is guarding. Strip // and /* */ before matching.
strip_java() {
  "$PY" - "$1" <<'PYEOF'
import re, sys
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
src = re.sub(r'//[^\n]*', '', src)
# Collapse whitespace so the match below survives reformatting/line wrapping.
print(re.sub(r'\s+', ' ', src))
PYEOF
}

echo "· leg 1/3 source: QualityScoreService must treat absent media as unscored"
QSS_CODE=$(strip_java "$QSS") || { echo "  cannot read $QSS — unavailable"; exit 2; }
# MEASURED, this run: a bare `recentMedia.isEmpty()` grep passes VACUOUSLY even with the guard
# deleted, because calculateEngagementRate carries its own unrelated `recentMedia.isEmpty() ||
# metric.getFollowers() == 0` early return. Falsifying this gate against the pre-fix tree is what
# exposed that — the first draft went red only by accident, on a different leg. Match the guard's
# actual two-term expression (either ordering), not the substring.
if ! printf '%s' "$QSS_CODE" | grep -Eq \
    'latestMetric\.isEmpty\(\) \|\| recentMedia\.isEmpty\(\)|recentMedia\.isEmpty\(\) \|\| latestMetric\.isEmpty\(\)'; then
  echo "VERDICT: broken — calculate()'s guard no longer covers an empty recentMedia; with"
  echo "  media_metrics unwritten this returns a fabricated composite for every creator alive."
  exit 1
fi
if ! printf '%s' "$QSS_CODE" | grep -q "absent()"; then
  echo "VERDICT: broken — QualityScoreResult.absent() is gone; absence has no representation left."
  exit 1
fi

echo "· leg 1/3 source: AdminCreatorService must not substitute a fabricated zero"
ACS_CODE=$(strip_java "$ACS") || { echo "  cannot read $ACS — unavailable"; exit 2; }
if printf '%s' "$ACS_CODE" | grep -Eq 'getQualityScore\) \.orElse\(BigDecimal\.ZERO\)|getQualityScore\)\.orElse\(BigDecimal\.ZERO\)'; then
  echo "VERDICT: broken — latestQualityScore substitutes BigDecimal.ZERO for a score that was"
  echo "  never computed. Optional.map already collapses a NULL quality_score column to empty, so"
  echo "  this fabricates a 0 for every unscored creator — which renders as a measured terrible"
  echo "  one, not as an absent measurement (F-0260)."
  exit 1
fi

echo "· leg 2/3 coverage: the regression tests must still exist by name"
for t in testCalculateEmptyMediaNeverFabricatesTwenty testCalculateReturnsAbsentForEmptyMedia; do
  grep -q "$t" "$QST" || {
    echo "VERDICT: broken — $t was removed from QualityScoreServiceTest; the invariant is unguarded."
    exit 1; }
done
for t in testAbsentQualityDoesNotDiscountRate testAbsentQualityAddsNoConfidence; do
  grep -q "$t" "$RET" || {
    echo "VERDICT: broken — $t was removed from RateEstimationServiceTest; the rate/confidence"
    echo "  half of this defect is unguarded."
    exit 1; }
done

echo "· leg 3/3 behaviour: running the scoring suites"
command -v mvn >/dev/null 2>&1 || { echo "  mvn not on PATH — unavailable"; exit 2; }
[ -f influora-api/pom.xml ] || { echo "  no influora-api/pom.xml — unavailable"; exit 2; }

BUDGET="${PROOF_SCORING_SUITE_TIMEOUT:-600}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 30 $BUDGET"; else TO=""; fi

out=$(cd influora-api && $TO mvn -o -Dtest='QualityScoreServiceTest,RateEstimationServiceTest,ScoreCalculationJobTest' test 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suites exceeded ${BUDGET}s — unavailable, NOT a finding"
  exit 2
fi
if printf '%s' "$out" | grep -q "Could not resolve dependencies\|Non-resolvable"; then
  echo "  offline maven cache incomplete — unavailable, NOT a finding"
  exit 2
fi
# MEASURED, this run: surefire intermittently reports "No tests matching pattern ... were
# executed!" (seen once under a concurrent maven run racing on target/). The first draft of this
# gate read that as "the suites are red" and printed a finding for a suite that never ran — a
# false red, which the law forbids outright. Nothing ran, so nothing was proved OR disproved.
if printf '%s' "$out" | grep -q "No tests matching pattern"; then
  echo "  surefire matched no tests (stale or contended target/) — the suites never ran;"
  echo "  unavailable, NOT a finding"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "Tests run:|ERROR\].*Test" | head -12
  echo "VERDICT: broken — the scoring suites are red."
  exit 1
fi

printf '%s\n' "$out" | grep -E "^\[INFO\] Tests run:" | tail -1
echo "VERDICT: proved — absent media yields an UNSCORED result, and absence neither discounts the"
echo "  estimated rate nor buys confidence."
echo "NOT CHECKED: whether media_metrics is populated AT RUNTIME — F-0479 gave the table a writer"
echo "  (MetricsPollingJob.pollRecentMedia, guarded by influora.meta.media-metrics-enabled), and"
echo "  gates/F-0479-media-metrics-has-a-writer.sh is what holds that; this gate only proves that"
echo "  WHEN the media list is empty the score is absent rather than fabricated. Also unchecked:"
echo "  whether a real Graph API call returns rows, and whether any UI renders the null as a number."
exit 0
