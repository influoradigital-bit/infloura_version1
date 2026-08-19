#!/usr/bin/env bash
# F-0326-injection-cannot-clobber-a-peer.sh — gate for F-0326
# (gate-falsification-clobbers-peer-edit).
#
# Producers are grouped by file ownership so two never write the same file. The hole: a
# gate-repair producer owns only .proof-os/gates/**, but proving its gate can FAIL means
# injecting a defect into the product file the gate guards and putting it back. That transient
# second ownership is declared nowhere, and a restore from a snapshot that went stale while the
# injection was live silently reverts whoever else wrote in that window. It did: F-0321's source
# edit was reverted by a peer's byte-perfect restore, tsc went red against code that no longer
# existed, and both agents' reports said green and were true when written.
#
# A lock alone does not fix this — it stops two simultaneous writers, not a stale restore. The
# mechanism is gates/_inject.sh, and the load-bearing part of it is the REFUSAL: inject_end
# compares the file against what the injector itself last left, and if a peer changed it,
# refuses to restore and says so, keeping both versions. This gate proves that refusal fires.
#
# Legs:
#   1. gates/_inject.sh exists and is sourceable alongside gates/_lock.sh.
#   2. SELF-FALSIFICATION — the helper is driven through a frozen scenario table in a scratch
#      directory, including the F-0326 scenario itself. If the helper accepts a scenario it must
#      reject (or rejects one it must accept), this gate exits 1 saying it cannot be trusted,
#      rather than reporting anything about the tree.
#   3. No gate writes into a product path except through the helper — the practice cannot drift
#      back to raw cp/sed against src/ or influora-api/.
#
# What it deliberately does NOT do: police what AGENTS type. A subagent can still hand-roll an
# injection and skip the helper entirely; nothing in a shell gate can prevent that. Leg 3 keeps
# the gate population honest, and the ledger record carries the rest.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

G=".proof-os/gates"
fail=0

echo "· the helper exists and is sourceable"
for f in _inject.sh _lock.sh; do
  [ -f "$G/$f" ] || { echo "  missing $G/$f"; echo "VERDICT: broken — the safe-injection mechanism F-0326 closes is gone"; exit 1; }
done
# shellcheck source=/dev/null
. "$G/_lock.sh"   2>/dev/null || { echo "· _lock.sh unsourceable — unavailable";   exit 2; }
# shellcheck source=/dev/null
. "$G/_inject.sh" 2>/dev/null || { echo "· _inject.sh unsourceable — unavailable"; exit 2; }
for fn in inject_begin inject_mark inject_end; do
  command -v "$fn" >/dev/null 2>&1 || { echo "  $fn not defined"; echo "VERDICT: broken — _inject.sh no longer provides its API (F-0326)"; exit 1; }
done
echo "  clean — inject_begin / inject_mark / inject_end available"

echo "· self-falsification: drive the helper through the frozen scenario table"
WORK="$(mktemp -d 2>/dev/null)" || { echo "· no scratch dir — unavailable"; exit 2; }
trap 'rm -rf "$WORK" 2>/dev/null' EXIT
mkdir -p "$WORK/.proof-os/tmp" || { echo "· scratch unusable — unavailable"; exit 2; }
_INJECT_DIR="$WORK/.proof-os/tmp"

scen_fail=0
say() { printf '    %-46s %s\n' "$1" "$2"; }

# (a) ordinary cycle — must restore the original byte for byte
SUBJ="$WORK/subject-a.txt"; printf 'original\r\nline two\r\n' > "$SUBJ"
BEFORE=$(sha256sum "$SUBJ" | cut -d' ' -f1)
inject_begin "$SUBJ" >/dev/null 2>&1 || scen_fail=1
printf 'INJECTED\r\nline two\r\n' > "$SUBJ"; inject_mark "$SUBJ" >/dev/null 2>&1
inject_end "$SUBJ" >/dev/null 2>&1; rc=$?
AFTER=$(sha256sum "$SUBJ" | cut -d' ' -f1)
if [ "$rc" -eq 0 ] && [ "$BEFORE" = "$AFTER" ]; then say "(a) ordinary cycle restores exactly" "ok"
else say "(a) ordinary cycle restores exactly" "WRONG (rc=$rc)"; scen_fail=1; fi

# (b) THE F-0326 SCENARIO — a peer writes while the injection is live.
SUBJ="$WORK/subject-b.txt"; printf 'original\n' > "$SUBJ"
inject_begin "$SUBJ" >/dev/null 2>&1
printf 'INJECTED\n' > "$SUBJ"; inject_mark "$SUBJ" >/dev/null 2>&1
printf 'PEER FIX — must survive\n' > "$SUBJ"          # the peer's write
inject_end "$SUBJ" >/dev/null 2>&1; rc=$?
PEER=$(cat "$SUBJ")
if [ "$rc" -eq 1 ] && [ "$PEER" = "PEER FIX — must survive" ]; then
  say "(b) peer write during injection is REFUSED" "ok"
else
  say "(b) peer write during injection is REFUSED" "WRONG (rc=$rc, file=$PEER)"; scen_fail=1
fi

# (c) an unclaimed file is not restorable — no silent no-op
SUBJ="$WORK/subject-c.txt"; printf 'x\n' > "$SUBJ"
inject_end "$SUBJ" >/dev/null 2>&1; rc=$?
if [ "$rc" -eq 2 ]; then say "(c) unclaimed file reports unusable" "ok"
else say "(c) unclaimed file reports unusable" "WRONG (rc=$rc)"; scen_fail=1; fi

if [ "$scen_fail" -eq 1 ]; then
  echo "VERDICT: broken — THIS GATE CANNOT BE TRUSTED. gates/_inject.sh no longer behaves as the"
  echo "         F-0326 mechanism requires; in particular a peer's write during a live injection"
  echo "         may now be silently reverted, which is the defect itself. Reporting nothing"
  echo "         about the tree until the helper is fixed."
  exit 1
fi
echo "  clean — 3/3 scenarios, including the F-0326 peer-write refusal"

echo "· no gate writes into a product path outside the helper"
bad=""
for g in "$G"/*.sh; do
  b=$(basename "$g")
  case "$b" in _inject.sh|F-0326-*) continue ;; esac
  # a redirect, cp, mv or in-place sed whose TARGET is a tracked product path
  if grep -nE "(>>?[[:space:]]*|cp[[:space:]]+[^|]*[[:space:]]|mv[[:space:]]+[^|]*[[:space:]]|sed[[:space:]]+-i[^|]*[[:space:]])(src|influora-api)/" "$g" >/dev/null 2>&1; then
    grep -q "_inject.sh" "$g" || bad="$bad $b"
  fi
done
if [ -n "$bad" ]; then
  echo "  writes product files without the helper:$bad"
  echo "VERDICT: broken — a gate mutates product code without the claim/refuse protocol (F-0326);"
  echo "         a restore from its snapshot can revert a peer's concurrent work"
  fail=1
else
  echo "  clean — no gate mutates src/ or influora-api/ outside gates/_inject.sh"
fi

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
echo "VERDICT: aligned (proved) — a temporary injection into a product file is claimed, marked and"
echo "         verified before restore, and a peer's write during the window is refused rather"
echo "         than silently reverted (F-0326)"
echo "NOT CHECKED: whether an AGENT uses the helper at all — a subagent can hand-roll an injection"
echo "             and nothing in a shell gate can stop it; leg 3 only keeps the gate population"
echo "             honest | whether two agents on DIFFERENT machines contend (the lock is"
echo "             in-store and advisory) | whether the peer's write was itself correct — this"
echo "             gate protects it from being lost, not from being wrong"
exit 0
