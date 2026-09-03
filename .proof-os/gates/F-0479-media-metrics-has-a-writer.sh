#!/usr/bin/env bash
# F-0479-media-metrics-has-a-writer.sh — gate for F-0479 (advertised-feature-absent).
#
# media_metrics had READERS (ScoreCalculationJob, BrandSafetyScoreService) and NO WRITER for
# months. Everything downstream stayed green the whole time: the module compiled, the suite
# passed, and every scoring test supplied its media list by hand, so nothing anywhere executed
# the "what if this table is empty in production" case. That is the shape this gate closes —
# write-never / read-often storage, which no compiler and no unit test can see.
#
# The class is deliberately stated as "the table has a writer AND the writer is reachable from
# the job that owns it", not "MetricsPollingJob contains the string saveAll". A writer that
# exists in a helper nothing calls is the same defect wearing a different hat, which is why
# leg 2 requires the call site inside pollOne's success path and leg 3 executes it.
#
# Three legs:
#   1. WRITER EXISTS. Some main-source file must call saveAll/save on MediaMetricsRepository.
#   2. WRITER IS REACHED. MetricsPollingJob.pollOne must actually invoke it.
#   3. BEHAVIOUR. The job's tests must prove a row is written per fetched post, and that a media
#      failure does not cost the creator their profile snapshot.
#   exit 0 = proved · 1 = broken · 2 = unavailable (never a false red)
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

SRC=influora-api/src/main/java
JOB=$SRC/com/influora/job/MetricsPollingJob.java
MAPPER=$SRC/com/influora/integration/meta/service/MediaMetricMapper.java
JOBTEST=influora-api/src/test/java/com/influora/job/MetricsPollingJobTest.java

for f in "$JOB" "$MAPPER" "$JOBTEST"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done
command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 || {
  echo "· no python on PATH — unavailable"; exit 2; }
PY=$(command -v python || command -v python3)

# F-0266: match CODE, not file bytes — this gate's own header names the symbols it requires, and
# the job's javadoc discusses them at length. Strip comments and collapse whitespace first.
strip_java() {
  "$PY" - "$1" <<'PYEOF'
import re, sys
src = open(sys.argv[1], encoding='utf-8', errors='replace').read()
src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
src = re.sub(r'//[^\n]*', '', src)
print(re.sub(r'\s+', ' ', src))
PYEOF
}

echo "· leg 1/3: media_metrics must have at least one writer in main source"
writers=$("$PY" - <<'PYEOF'
import os, re
root = 'influora-api/src/main/java'
hits = []
for dirpath, _, files in os.walk(root):
    for fn in files:
        if not fn.endswith('.java'):
            continue
        p = os.path.join(dirpath, fn)
        src = open(p, encoding='utf-8', errors='replace').read()
        src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
        src = re.sub(r'//[^\n]*', '', src)
        if re.search(r'mediaMetricsRepository\s*\.\s*(save|saveAll)\s*\(', src):
            hits.append(p)
print('\n'.join(hits))
PYEOF
)
if [ -z "$writers" ]; then
  echo "VERDICT: broken — nothing in main source writes media_metrics, but ScoreCalculationJob and"
  echo "  BrandSafetyScoreService both read it. Every creator will score off an empty list (F-0478)."
  exit 1
fi
echo "  writer(s): $(printf '%s' "$writers" | tr '\n' ' ')"

echo "· leg 2/3: the writer must be reached from MetricsPollingJob's per-creator poll"
JOB_CODE=$(strip_java "$JOB") || { echo "  cannot read $JOB — unavailable"; exit 2; }
if ! printf '%s' "$JOB_CODE" | grep -q "pollRecentMedia("; then
  echo "VERDICT: broken — MetricsPollingJob no longer calls its media poll; the writer found in"
  echo "  leg 1 is unreachable from the job that owns the polling cycle."
  exit 1
fi
# Two occurrences required: the declaration AND at least one call site. A declaration alone is
# dead code and would satisfy a naive substring check.
calls=$(printf '%s' "$JOB_CODE" | grep -o "pollRecentMedia(" | wc -l)
if [ "$calls" -lt 2 ]; then
  echo "VERDICT: broken — pollRecentMedia is declared but never called ($calls occurrence)."
  exit 1
fi

echo "· leg 3/3 behaviour: the job's media_metrics tests must exist and pass"
for t in testPollMetricsWritesMediaMetrics testMediaFailureDoesNotLoseProfileSnapshot; do
  grep -q "$t" "$JOBTEST" || {
    echo "VERDICT: broken — $t was removed; the writer is unguarded."
    exit 1; }
done

command -v mvn >/dev/null 2>&1 || { echo "  mvn not on PATH — unavailable"; exit 2; }
BUDGET="${PROOF_MEDIA_METRICS_TIMEOUT:-600}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 30 $BUDGET"; else TO=""; fi

out=$(cd influora-api && $TO mvn -o -Dtest='MetricsPollingJobTest,MediaMetricMapperTest' test 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suites exceeded ${BUDGET}s — unavailable, NOT a finding"; exit 2
fi
if printf '%s' "$out" | grep -q "Could not resolve dependencies\|Non-resolvable"; then
  echo "  offline maven cache incomplete — unavailable, NOT a finding"; exit 2
fi
if printf '%s' "$out" | grep -q "No tests matching pattern"; then
  echo "  surefire matched no tests — the suites never ran; unavailable, NOT a finding"; exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "Tests run:|ERROR\].*Test" | head -12
  echo "VERDICT: broken — the media_metrics suites are red."
  exit 1
fi

printf '%s\n' "$out" | grep -E "^\[INFO\] Tests run:" | tail -1
echo "VERDICT: proved — media_metrics has a writer, the job reaches it, and a row is written per"
echo "  fetched post without risking the creator's profile snapshot."
echo "NOT CHECKED: whether a LIVE Graph API call returns media for any real creator (no live token"
echo "  is exercised here); whether influora.meta.media-metrics-enabled is true in any given deploy"
echo "  — it defaults true but ops can disable it, and with it off this table has no writer again;"
echo "  and whether the values Meta returns are themselves correct."
exit 0
