#!/usr/bin/env bash
# gates/F-0770-F-0771-k3-brand-wrapper.sh — origin: F-0770 (test-compares-object-to-itself) and
# F-0771 (unclassified-nested-field-trusted), both found by priya on 2026-09-18 at the K-3 last
# call, fixed by vikram across K-3 rounds 2-4, and judged MET by priya fresh-context
# (.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/PRIYA-LASTCALL-K3R4-0918.md).
#
# THE DEFECTS.
#   F-0770  The K-3 browser-copy tests compared Spring's payload object to itself, so a loop that
#           mutated that object in place (popping brand fields, escaping '<') stayed green. The
#           fix: a deep copy taken before the run, compared after.
#   F-0771  influora-ai/app/tools/loop.py classified only top-level and per-deal keys, so an
#           unknown key nested inside a trusted container (quote.brand_budget_note), or a
#           non-scalar under a trusted key, reached the model outside the untrusted wrapper. The
#           fix: scalars only in trusted fields, a whole-container wrap for anything unrecognised,
#           and a drift test against the Java DTO records.
#
# WHAT THIS GATE DECIDES. The oracles are the two pytest modules. priya showed each goes red when
# its piece is removed. This gate adds what a bare pytest run cannot:
#   1. the guarding tests still exist by name. A deleted test would otherwise leave pytest green;
#   2. the modules really collected and passed tests (the JUnit XML counts are > 0 and 0 failed).
#      A syntax error or a renamed module would otherwise "pass" on zero tests.
#
# The two browser-copy branches priya's round 4 found unguarded (B13 zero-deal payload, B14
# no-brand-fields deal) are now required by name below; vikram showed each goes red.
#
# NOT CHECKED: whether the model obeys the persona rule about the wrapper; D-1's draft_reply,
# which is not yet a wrapped tool.
#
# Exit 0 proved · 1 broken · 2 unavailable (never green).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AI="$ROOT/influora-ai"
DISPATCH="$AI/tests/tools/test_loop_creator_dispatch.py"
DRIFT="$AI/tests/tools/test_k3_dto_field_classification_drift.py"

command -v python >/dev/null 2>&1 || { echo "UNAVAILABLE: python not on PATH"; exit 2; }
for f in "$DISPATCH" "$DRIFT"; do [ -f "$f" ] || { echo "BROKEN: $f is missing"; exit 1; }; done

missing=0
need() { grep -aq -- "$2" "$1" || { echo "BROKEN: '$2' is gone from $(basename "$1")"; missing=1; }; }
need "$DISPATCH" "def test_f0771_depth_probe_lands_inside_the_wrapper"
need "$DISPATCH" "def test_creator_tool_result_data_passes_through_unchanged"
need "$DISPATCH" "copy.deepcopy"
need "$DISPATCH" "N10A_empty_deals_unknown_top_level"
need "$DISPATCH" "def test_k3_get_my_deals_zero_deals_clean_payload_reaches_browser_unwrapped_and_unchanged"
need "$DISPATCH" "def test_k3_get_my_deals_all_trusted_deal_clean_payload_reaches_browser_unwrapped_and_unchanged"
[ "$missing" -eq 0 ] || exit 1

XML="$(mktemp -d)/k3.xml"
( cd "$AI" && python -m pytest -q -p no:cacheprovider "tests/tools/test_loop_creator_dispatch.py" \
    "tests/tools/test_k3_dto_field_classification_drift.py" --junitxml="$XML" >/dev/null 2>&1 )
rc=$?
[ -f "$XML" ] || { echo "BROKEN: pytest wrote no report (exit $rc)"; exit 1; }
read -r tests failures errors <<<"$(python - "$XML" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
suites = [root] if root.tag == "testsuite" else list(root)
t = sum(int(s.get("tests", 0)) for s in suites)
f = sum(int(s.get("failures", 0)) for s in suites)
e = sum(int(s.get("errors", 0)) for s in suites)
print(t, f, e)
PY
)"
if [ "$rc" -ne 0 ] || [ "${failures:-1}" -ne 0 ] || [ "${errors:-1}" -ne 0 ]; then
  echo "BROKEN: pytest exit $rc, tests=$tests failures=$failures errors=$errors"; exit 1
fi
[ "${tests:-0}" -ge 1 ] || { echo "BROKEN: pytest collected $tests tests; vacuous"; exit 1; }
echo "PROVED: K-3 modules tests=$tests failures=0 errors=0; guarding tests present"
exit 0
