#!/usr/bin/env bash
# gates/F-0100-collision-check.sh — concurrent-write-collision detector.
# origin: F-0100, F-0123, F-0155 — an out-of-session process live-edits this repo and
#         has silently clobbered in-progress edits (DealService.java, PortfolioService.java,
#         CreatorAffiliateEarningController.java). build-exits-0 is NOT survival proof.
# WHAT IT PROVES: after a write, the symbol/marker the writer added is STILL PRESENT in
#         the file — i.e. the edit survived any concurrent overwrite between write and gate.
# LAW: tool-cannot-run => exit 2. exit 1 ONLY for a real finding (marker vanished =
#      the edit was clobbered). Bad arguments are 64.
# Usage: F-0100-collision-check.sh <file> <required-pattern> [<file> <pattern> ...]
set -u
if [ "$#" -lt 2 ] || [ $(( $# % 2 )) -ne 0 ]; then
  echo "usage: F-0100-collision-check.sh <file> <required-pattern> [<file> <pattern> ...]"
  echo "NOT CHECKED: anything — arguments rejected before any file was opened"
  exit 64
fi
FAIL=0
while [ "$#" -gt 0 ]; do
  f="$1"; pat="$2"; shift 2
  if [ ! -f "$f" ]; then
    echo "COLLISION? $f — file MISSING (deleted out-of-session, or wrong path)"; FAIL=1; continue
  fi
  if grep -q -- "$pat" "$f"; then
    echo "survived   $f — pattern present"
  else
    echo "COLLISION  $f — required pattern absent: the edit did not survive"; FAIL=1
  fi
done
echo "NOT CHECKED: whether the rest of the file is intact around the marker; whether the concurrent process re-broke behaviour without touching this pattern; anything in files not passed as arguments"
exit $FAIL
