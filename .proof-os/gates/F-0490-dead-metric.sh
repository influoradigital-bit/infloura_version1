#!/usr/bin/env bash
# gates/F-0490-dead-metric.sh — CLASS gate for dead-metric-declared-but-never-written.
# Closes/tracks F-0490, F-0506, F-0525, F-0688, F-0689 - a class that recurred 5 times with no
# gate naming it, which is why it still BLOCKS all work in the files it touches.
#
# F-0813 (this gate reding on a LIVE metric): RULE 1's first cut searched a 160-character window
# around the literal `UsageMetric.CONST` for a write-shaped call. UsageMetric.CREATOR_ANALYTICS_VIEW
# is returned by EntitlementService#meteredMetricFor, bound to a local, and passed to
# incrementUsage (EntitlementService.java:147) and recordCreatorLookup (:150) - literal and write in
# different methods - so it read as DEAD though it is incremented on both branches. Worse, that
# false positive was written into the ratchet baseline with a note asserting it was verified. A
# ratchet baseline is dated, machine-readable and trusted by whoever reads it next: a wrong finding
# in there is not a red run someone re-checks, it is a fact nobody re-derives. RULE 1 now follows
# one local-variable hop, and the NOT CHECKED block below states that limit rather than implying
# more. TRACKED_CREATOR is still correctly reported dead, so the false red was not traded for a
# false green.
#
#   F-0490  UsageMetric.TRACKED_CREATOR - read by BillingController, incremented nowhere.
#   F-0506  CreatorMetric.avgEngagementRate / avgReachPerPost / avgImpressionsPerPost - written
#           by nothing, read as if they were real.
#   F-0525  AdminSupportService.avgResponseTime - a hardcoded 0.0 handed straight to
#           SupportStatsDto with no query or computation between declaration and use.
#   F-0688  UsageMetric.EXPORT - already fixed (deleted from the enum). Its real defect was the
#           scanner itself: RULE 1's constant parser required a trailing comma, so the LAST
#           declared enum constant was invisible and a dead meter appended at the end passed
#           silently. That parser is fixed in _dead_metric_scan.py as part of THIS gate.
#   F-0689  MediaMetric.avgWatchTimeSeconds - same shape as F-0506, different entity.
#
# WHAT THIS GATE DOES: runs `_dead_metric_scan.py` (RULE 1 enum meters, RULE 2 entity fields,
# RULE 3 literal-stat locals returned as measurements - all three already implemented there) and
# ratchets its output against a baseline instead of failing outright.
#
# WHY A RATCHET, NOT A HARD FAIL: the 5+ named instances above are LIVE defects. Fixing them is
# real feature work (implementing TRACKED_CREATOR increments, computing the CreatorMetric
# averages, mapping watch time, stamping a first-response column) that is explicitly out of this
# gate's scope. A gate that just exits 1 on their presence would block everything in these files
# forever. `.proof-os/gates/_dead_metric_baseline.json` lists every CURRENTLY-known dead metric;
# this gate is GREEN as long as the live set stays a SUBSET of that baseline, and turns RED the
# moment a metric appears that the baseline does not name - a sixth dead meter, or a seventh
# literal-stat field, lands as a gate failure instead of a silent pass. This is the same
# baseline-ratchet shape as doc-stale-doc-claim.sh / _doc_claim_baseline.json, adapted from a
# count ceiling to a named-set ceiling because a dead metric needs to be identified, not just
# tallied, before anyone can decide whether it belongs on the list.
#
# LAW: exit 0 proved (ratcheted - live set known and bounded) · 1 broken (new metric OR scanner
#      found a defect the baseline does not cover) · 2 unavailable (tool/scanner/baseline missing,
#      or the scanner's own parser fails its self-test).
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_dead_metric_scan.py"
BASELINE="$SELF/_dead_metric_baseline.json"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }
[ -f "$BASELINE" ] || { echo "· $BASELINE missing — unavailable, nothing to ratchet against"; exit 2; }

# PERMANENT SELF-TEST — kills the exact defect F-0688 was filed for. Execs the scanner's own
# parse_enum_constants() (no re-implementation here to drift out of sync) against a synthetic
# enum whose LAST constant has no trailing comma. A parser that still needs the comma reports 1
# constant instead of 2, and this gate refuses to trust its own scan.
SELFTEST_OUT=$(SCAN_PATH="$SCAN" python - <<'PY'
import os, sys
src = open(os.environ["SCAN_PATH"], encoding="utf-8").read()
ns = {}
exec(compile(src.replace("sys.exit(main())", "pass"), "scan", "exec"), ns)
sample = "enum UsageMetric {\n    TRACKED_CREATOR,\n    CREATOR_ANALYTICS_VIEW\n}\n"
constants, err = ns["parse_enum_constants"](sample)
if err:
    print(f"PARSE-ERROR:{err}")
    sys.exit(1)
if constants != ["TRACKED_CREATOR", "CREATOR_ANALYTICS_VIEW"]:
    print(f"WRONG:{constants}")
    sys.exit(1)
print("OK")
PY
)
if [ "$SELFTEST_OUT" != "OK" ]; then
  echo "· scanner self-test FAILED against a last-constant-no-trailing-comma sample: $SELFTEST_OUT"
  echo "VERDICT: unavailable — the enum parser cannot see a constant with no trailing comma, which"
  echo "         is the exact blind spot F-0688 was filed for. A scan run on top of this would be"
  echo "         worthless: it could report a shrinking, all-clear tree while a new dead meter sits"
  echo "         invisible at the end of the enum."
  exit 2
fi
echo "· scanner self-test passes: a last-constant-with-no-comma is visible to RULE 1"

# The scanner is the source of truth for the LIVE set. It exits 2 itself when it cannot see the
# tree at all (missing dirs, unreadable enum) — that must stay unavailable, never a pass.
SCAN_LOG=$(mktemp 2>/dev/null) || { echo "· cannot create temp file — unavailable"; exit 2; }
trap 'rm -f "$SCAN_LOG"' EXIT
python "$SCAN" >"$SCAN_LOG" 2>&1
rc=$?
sed 's/^/· /' "$SCAN_LOG"
if [ "$rc" -ne 0 ] && [ "$rc" -ne 1 ]; then
  echo "VERDICT: unavailable — the scanner itself could not complete (exit $rc)."
  exit 2
fi

# Compare via a temp file, not shell-variable interpolation into a heredoc, so nothing in the
# scanner's own output (quotes, backticks, $) can corrupt the python it is fed into.
RATCHET_OUT=$(SCAN_LOG_PATH="$SCAN_LOG" BASELINE_PATH="$BASELINE" python - <<'PY'
import json, os, re, sys
scan_output = open(os.environ["SCAN_LOG_PATH"], encoding="utf-8").read()
live = set(re.findall(r"^\s*DEAD METRIC: (.+?) - ", scan_output, re.M))
baseline_path = os.environ["BASELINE_PATH"]
try:
    baseline = json.load(open(baseline_path, encoding="utf-8"))
except Exception as e:
    print(f"BASELINE-UNREADABLE:{e}")
    sys.exit(2)
known = set(baseline.get("known_dead_metrics", []))
new = sorted(live - known)
shrunk = sorted(known - live)
if new:
    print("NEW:" + "|".join(new))
    sys.exit(1)
if shrunk:
    print("SHRUNK:" + "|".join(shrunk))
    sys.exit(0)
print("STABLE")
sys.exit(0)
PY
)
ratchet_rc=$?
case "$ratchet_rc" in
  2)
    echo "· $RATCHET_OUT"
    echo "VERDICT: unavailable — baseline file unreadable, cannot ratchet."
    exit 2
    ;;
  1)
    new_list="${RATCHET_OUT#NEW:}"
    echo "· NOT IN BASELINE (new dead metric — this is what the ratchet exists to catch):"
    echo "$new_list" | tr '|' '\n' | sed 's/^/    - /'
    echo "VERDICT: broken — a dead metric exists that _dead_metric_baseline.json does not name."
    echo "         Either it is a genuinely new instance (open a failure record for it, same shape"
    echo "         as F-0490/F-0506/F-0525/F-0689, then add it to known_dead_metrics once tracked)"
    echo "         or it was already fixed in a way the baseline should be lowered for instead —"
    echo "         never widen the baseline to make this pass without one of those two steps."
    exit 1
    ;;
esac

if [[ "$RATCHET_OUT" == SHRUNK:* ]]; then
  shrunk_list="${RATCHET_OUT#SHRUNK:}"
  echo "· FIXED SINCE BASELINE WAS RECORDED (no longer detected as dead):"
  echo "$shrunk_list" | tr '|' '\n' | sed 's/^/    - /'
  echo "· lower _dead_metric_baseline.json's known_dead_metrics to match — a baseline that still"
  echo "  names a fixed metric hides a real regression if that same metric goes dead again."
fi

echo "VERDICT: proved (ratcheted) — every dead metric the scanner finds is already a named,"
echo "         tracked instance in _dead_metric_baseline.json. No new dead metric has landed"
echo "         since the baseline was recorded."
echo "NOT CHECKED: whether the 5-6 baselined instances have actually been repaired — that is"
echo "             tracked feature work (TRACKED_CREATOR increments, the CreatorMetric averages,"
echo "             watch-time mapping, a first-response timestamp), explicitly out of this gate's"
echo "             scope. RULE 3 only recognizes a BARE numeric-literal local flowing unmodified"
echo "             into a *Dto/*Stats/*Response/*Summary constructor; a dead stat computed through"
echo "             an intermediate variable, returned via a builder pattern, or assigned across"
echo "             an if/else where both branches are literals is invisible to it. RULE 1 and"
echo "             RULE 2 do not see metrics read/written through reflection, method references, or"
echo "             a second UsageMetric-shaped enum elsewhere in the tree. RULE 1 follows a constant"
echo "             through exactly ONE local-variable hop (a direct assignment, or a local bound to"
echo "             a method's return value) before checking whether that local reaches a write-"
echo "             shaped call — a constant reaching a write through TWO OR MORE hops (e.g. passed"
echo "             through a second method parameter, or re-assigned to a further local before the"
echo "             write) still reads as dead. All three rules ignore test sources by construction"
echo "             (they only walk influora-api/src/main/java)."
exit 0
