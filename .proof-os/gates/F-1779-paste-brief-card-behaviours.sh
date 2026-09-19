#!/usr/bin/env bash
# gates/F-1779-paste-brief-card-behaviours.sh — origin: F-1779 (behaviour-with-no-failing-test), found
# by priya at the U-2 last call (.proof-os/tasks/T-MEERA-CREATOR-PHASE-B/PRIYA-LASTCALL-U2-0918.md),
# tests added by ananya, proved fresh-context by meera 2026-09-19 (MEERA-U2-TESTS-PROOF-0919.md):
# each behaviour removed turned its own test red.
#
# THE DEFECT. Four PasteBriefCard behaviours were correct, but nothing went red when they broke:
#   1. a server CONSENT_REQUIRED on the paste opens the consent screen;
#   2. the textarea has no maxLength, so the browser never truncates a paste silently;
#   3. dismissed flags are scoped BRIEF:{id}, so a dismissal carries to the get_brief tool card;
#   4. a server FEATURE_DISABLED on the paste hides the card.
#
# WHAT THIS GATE DECIDES. The oracle is vitest on the two test files. This gate also requires:
#   1. the guarding tests exist by name. A deleted test would otherwise leave vitest green;
#   2. vitest ran tests (> 0) and none failed. A renamed file would otherwise pass on zero tests.
#
# NOT CHECKED: behaviour against a live backend (owed after the Phase A deploy); how the card looks.
#
# Exit 0 proved · 1 broken · 2 unavailable (never green).
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CARD="$ROOT/src/components/creator/copilot/PasteBriefCard.test.tsx"
PAGE="$ROOT/src/pages/creator-copilot-paste-brief.test.tsx"

command -v npx >/dev/null 2>&1 || { echo "UNAVAILABLE: npx not on PATH"; exit 2; }
[ -d "$ROOT/node_modules" ] || { echo "UNAVAILABLE: node_modules missing"; exit 2; }
for f in "$CARD" "$PAGE"; do [ -f "$f" ] || { echo "BROKEN: $f is missing"; exit 1; }; done

missing=0
need() { grep -aqF -- "$2" "$1" || { echo "BROKEN: test '$2' is gone from $(basename "$1")"; missing=1; }; }
need "$CARD" "a flag dismissed on the paste card is also hidden on the get_brief tool card"
need "$CARD" "has no maxLength attribute on the textarea"
need "$CARD" "rejects with a server CONSENT_REQUIRED refusal"
need "$PAGE" "server CONSENT_REQUIRED on the paste itself opens the consent screen"
need "$PAGE" "server FEATURE_DISABLED on the paste call itself unmounts the card"
[ "$missing" -eq 0 ] || exit 1

OUT="$(mktemp -d)/vitest.json"
( cd "$ROOT" && npx vitest run "src/components/creator/copilot/PasteBriefCard.test.tsx" \
    "src/pages/creator-copilot-paste-brief.test.tsx" --reporter=json --outputFile="$OUT" >/dev/null 2>&1 )
rc=$?
[ -f "$OUT" ] || { echo "BROKEN: vitest wrote no report (exit $rc)"; exit 1; }
read -r total failed <<<"$(python - "$OUT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
print(d.get("numTotalTests", 0), d.get("numFailedTests", 0) + d.get("numFailedTestSuites", 0))
PY
)"
if [ "$rc" -ne 0 ] || [ "${failed:-1}" -ne 0 ]; then echo "BROKEN: vitest exit $rc, tests=$total failed=$failed"; exit 1; fi
[ "${total:-0}" -ge 1 ] || { echo "BROKEN: vitest ran $total tests; vacuous"; exit 1; }
echo "PROVED: PasteBriefCard behaviour tests=$total failed=0; the five guarding tests are present"
exit 0
