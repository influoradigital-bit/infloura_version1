#!/usr/bin/env bash
# gates/false-red-tool-error.sh — CLASS gate for false-red-tool-error.
# Closes F-0407, F-0423.
#
# The class: a GATE reports a finding that is not real. Both rows are the same instrument failing
# the same way — the superseded FE reachability scanner called live endpoints unreachable:
#
#   F-0407  norm() rewrote a leading ${API_BASE_URL} interpolation to '*', so every call written as
#           fetch(`${API_BASE_URL}/path`) normalised to '*/path' and matched no backend path.
#   F-0423  four further call shapes were invisible to it — requestOrNull missing from the helper
#           list, a generic containing a paren defeating <[^(]*?>, an API client mounting, and more.
#
# WHY A FALSE RED IS WORSE THAN NO GATE. A false green is silence; a false red is noise that LOOKS
# like signal. It sends someone to fix code that was never broken, and after a few of those the team
# stops believing the gate at all — at which point the true reds die with the false ones.
#
# WHAT THIS GATE ASSERTS: not that the scanner reports nothing, but that it DISCRIMINATES. It must
# say "unreachable" about an endpoint that genuinely has no caller, and must NOT say it about
# endpoints reached through the call shapes these two findings named. A scanner that reports
# everything, or nothing, passes neither half.
#
# The superseded scanner must also stay retired: if the archived copy is executed again, or the
# registry starts naming it, both findings return with it.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCANNER=.proof-os/gates/unreachable-endpoint.py
[ -f "$SCANNER" ] || { echo "· $SCANNER missing — unavailable"; exit 2; }

out=$(python "$SCANNER" 2>&1) || true
if [ -z "$out" ]; then
  echo "· the scanner produced no output at all — unavailable, not a pass"
  exit 2
fi
if ! printf '%s' "$out" | grep -q "no frontend caller"; then
  echo "· the scanner's findings section is unrecognisable — its format changed, so every check"
  echo "  below would be reading nothing"
  exit 2
fi

# --- NEGATIVE CONTROL: it must still be able to SEE a real gap --------------------------------
# /workspace/members/switch genuinely has no caller and cannot get one (F-0685: nothing enumerates
# a user's workspaces). If the scanner stops reporting even this, it has gone blind and its silence
# elsewhere means nothing.
if ! printf '%s' "$out" | grep -q "workspace/members/switch"; then
  echo "VERDICT: broken — the scanner no longer reports /workspace/members/switch, which genuinely"
  echo "         has no caller. It has gone blind, so its clean verdict on everything else is"
  echo "         worthless (the false-GREEN twin of this class)"
  exit 1
fi
echo "· negative control: the scanner still sees a genuinely uncalled endpoint"

# --- POSITIVE CONTROL: the call shapes F-0407/F-0423 named must resolve ------------------------
# Each of these is reached only through the client helper indirection those findings said was
# invisible. If any is reported unreachable, the scanner has regressed to calling live code dead.
for route in "workspace/members/invites" "creator/deliverables/{deliverableId}/metrics" "contracts/unsigned"; do
  if printf '%s' "$out" | grep -F "$route" | grep -q "no frontend caller"; then
    printf '%s\n' "$out" | grep -F "$route" | sed 's/^/    /'
    echo "VERDICT: broken — the scanner reports $route as having no frontend caller, but it is"
    echo "         called through the client. That is the F-0407/F-0423 false red returning"
    exit 1
  fi
done
echo "· positive control: endpoints reached through the client helper are NOT reported unreachable"

# --- the superseded scanner must stay retired --------------------------------------------------
ARCHIVED=.proof-os/_archive/superseded-gates-0902/endpoint_reachability.py
if [ -f "$ARCHIVED" ]; then
  if [ -f .proof-os/registry.json ] && grep -q "endpoint_reachability" .proof-os/registry.json; then
    echo "VERDICT: broken — the registry names the superseded endpoint_reachability scanner. Both"
    echo "         findings in this class are ITS bugs; running it again returns them (F-0407/F-0423)"
    exit 1
  fi
  echo "· the superseded scanner is archived and unreferenced"
else
  echo "· the superseded scanner is gone entirely"
fi

echo "VERDICT: aligned (proved) — the reachability scanner discriminates: it still reports an"
echo "         endpoint that genuinely has no caller, and does NOT report endpoints reached"
echo "         through the client-helper indirection that defeated the superseded version. The"
echo "         scanner whose bugs these two findings describe stays retired and unreferenced."
echo "NOT CHECKED: the OTHER endpoints this scanner currently reports — this gate proves the tool"
echo "             discriminates on three known-good and one known-bad case, not that every one of"
echo "             its ~26 current findings is true. Each remains its own finding to confirm."
echo "             Nothing here proves the absence of a FOURTH invisible call shape: a shape nobody"
echo "             has written yet would be invisible to the scanner and to this gate alike, which"
echo "             is exactly how F-0423 followed F-0407."
exit 0
