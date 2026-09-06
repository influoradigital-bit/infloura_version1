#!/usr/bin/env bash
# gates/F-0380-single-metrics-write-path.sh — instance gate, closes F-0380.
#
# origin: F-0380 (dead-write-path) — "PUT /deliverables/{milestoneId}/metrics and
# POST /creator/deliverables/{deliverableId}/metrics are two independent write paths for
# creator-reported deliverable metrics. Only the latter has a UI caller; the former is unreachable,
# so any future caller wired to it writes through a path the app never reads back the same way."
#
# HOW IT WAS RESOLVED, AND WHY THAT NEEDED CHECKING. The duplicate went away as COLLATERAL:
# web/DeliverableMetricController.java was deleted in 7b49588 while closing F-0449, not by anyone
# acting on F-0380. The row's recorded `where` then pointed at a file that no longer existed, which
# is exactly the state in which it is tempting to mark a finding resolved by inference. It was left
# open deliberately until the code was read: git history confirms the controller existed and was
# deleted, the surviving POST has a real frontend caller, and no PUT mapping remains anywhere.
#
# WHAT THIS GATE ACTUALLY DEFENDS is the durable property, not the deletion: EXACTLY ONE controller
# write path for this entity. A second one reintroduces the finding whoever adds it, and that is
# what the row's own missed_by asked for.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_f0380_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }

# --- CHECK A: exactly one controller write path -------------------------------------------------
python "$SCAN"; rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -ne 0 ] && exit 1

# --- CHECK B: the deleted duplicate has not returned by name ------------------------------------
if [ -f influora-api/src/main/java/com/influora/web/DeliverableMetricController.java ]; then
  echo "VERDICT: broken — DeliverableMetricController.java is back. It was deleted in 7b49588 and"
  echo "         was the second writer this finding is about (F-0380)"
  exit 1
fi
echo "· the deleted duplicate controller has not returned"

# --- CHECK C: the surviving path has a real frontend caller -------------------------------------
# A single write path that nothing calls is the same defect wearing one fewer controller.
if ! grep -q 'deliverables/${deliverableId}/metrics' src/lib/api.ts 2>/dev/null; then
  echo "VERDICT: broken — the one remaining metrics write path has no client method; a creator"
  echo "         cannot report performance through the product (F-0380)"
  exit 1
fi
echo "· the surviving POST has a client method in src/lib/api.ts"

# --- CHECK D: the canonical scanner agrees -------------------------------------------------------
CLASS_GATE=.proof-os/gates/unreachable-endpoint.py
if [ -f "$CLASS_GATE" ]; then
  out=$(python "$CLASS_GATE" 2>&1) || true
  if ! printf '%s' "$out" | grep -q "no frontend caller"; then
    echo "· the scanner reported no unreachable endpoints at all, which contradicts the known open"
    echo "  findings — broken instrument, not a pass"
    exit 2
  fi
  if printf '%s' "$out" | grep -i "metrics" | grep -q "no frontend caller"; then
    printf '%s\n' "$out" | grep -i "metrics" | grep "no frontend caller" | sed 's/^/    /'
    echo "VERDICT: broken — a metrics endpoint has no frontend caller again (F-0380)"
    exit 1
  fi
  echo "· canonical scanner lists no unreachable metrics endpoint"
else
  echo "· scanner unavailable — REACHABILITY NOT INDEPENDENTLY CHECKED"
fi

echo "VERDICT: aligned (proved) — there is exactly ONE controller write path for creator-reported"
echo "         deliverable metrics, POST /creator/deliverables/{deliverableId}/metrics, and it has"
echo "         a real client method. The duplicate PUT path is gone and has not returned, and the"
echo "         canonical scanner flags no unreachable metrics endpoint."
echo "NOT CHECKED: the CLASS this finding's missed_by describes — 'two controllers writing the same"
echo "             domain entity where only one has a frontend caller' — is NOT implemented here."
echo "             This gate is scoped to deliverable metrics alone; the same shape elsewhere in the"
echo "             codebase would pass unnoticed, and a general version needs an entity-to-writer"
echo "             map no existing gate builds. Also unchecked: whether the three SERVICES that"
echo "             persist DeliverableMetric (CreatorDeliverableService, DeliverableMetricService,"
echo "             DeliverableVerificationService) agree on the shape they write — this counts"
echo "             controller entry points, not the rows they produce."
exit 0
