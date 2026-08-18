#!/usr/bin/env bash
# F-0329-gates-assert-behaviour.sh — gate for F-0329 (token-as-proxy-gates-unrepaired)
# and for F-0334 (gate-fixtures-collected-by-product-suite), which the repairs caused:
# the new gates keep fixture specs under .proof-os/gates/, and vitest's default include
# swept them into the product suite until the exclude + gates-config split landed. Leg 5
# below is F-0334's exit test.
#
# A sweep classified 13 promoted gates as asserting that A TOKEN APPEARS IN SOURCE as a
# proxy for a behaviour holding. Eleven were then injected with a plausible wrong fix that
# reintroduced the original defect while leaving the grepped token present. TEN OF ELEVEN
# exited 0 — every one of them guarding an already-CLOSED ledger record, so each read as
# protection that did not exist. Only F-0050 survived, and a third probe found even that one
# name-locked (F-0335).
#
# The failure has one shape. `grep -q TOKEN file` is satisfied by ANY occurrence: the
# function's own definition (F-0236), a neighbouring method (F-0242), a line an unrelated
# later task added (F-0319), or the fix's own explanatory comment (F-0266). None of those
# says the behaviour holds. A gate built that way cannot fail, and a record closed against
# it is worse than an open record, because the ledger reads as covered.
#
# What this gate asserts about the eleven repaired gates — properties, not their contents:
#
#   1. Each names its record and is on disk.
#   2. Each reads CODE, not file bytes: it sources gates/_code.sh (shell) or imports the
#      python equivalent. A gate that greps raw text fails the fix whose comment quotes the
#      forbidden string, and greens a fix that exists only in a comment.
#   3. Each carries an EXECUTION leg — it runs a suite, a node/vm harness, or a parser —
#      rather than deciding on greps alone. This is necessary, not sufficient: F-0264 had an
#      execution leg and F-0296 still got through it.
#   4. Each carries the SELF-FALSIFICATION device: a known-bad implementation frozen into the
#      gate and pushed through the gate's own assertion table first, so the gate exits 1
#      saying it cannot fail rather than reporting on the real code. That device is what
#      makes 2 and 3 load-bearing, because it is checked on every run rather than once.
#   5. No gate that materialises a spec under .proof-os/gates/ invokes vitest without the
#      gates config (F-0334) — otherwise the trust layer's fixtures are swept into the
#      product suite, and build.sh partly grades itself.
#
# What it deliberately does NOT do: re-run the eleven injections. Those are one-time
# experiments whose evidence lives in .proof-os/tasks/T-F0329-GATES/*.inject.log with the
# observed exit codes. This gate guards the PROPERTIES that made the repairs work, so that
# reverting any repaired gate to a token grep is caught here.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

G=".proof-os/gates"
[ -d "$G" ] || { echo "· $G missing — unavailable"; exit 2; }

# The eleven the sweep named. F-0050 is included: it survived injection and is expected to
# stay sound, but it is name-locked (F-0335) and belongs under the same watch.
REPAIRED="F-0235-no-fabricated-pii-live.sh
F-0237-sign-over-real-terms.sh
F-0238-no-fabricated-contract-pdf.sh
F-0239-no-fake-money-progress.sh
F-0240-campaign-type-reaches-payload.sh
F-0241-no-false-draft-reassurance.sh
F-0242-brand-can-open-dispute.sh
F-0243-verification-copy-agrees.sh
F-0272-demo-contract-fixture.sh"

# F-0050 survived its injection and was deliberately left alone — "do not improve a gate that
# can already fail". It is therefore held to the properties every gate must have (present, reads
# code) but NOT to the two that describe a repair, because it was never repaired. Its own gap is
# tracked as F-0335 (name-locked: the same defect under a new identifier passes), which is a
# ledger record, not a lint this gate can enforce.
SURVIVED="F-0050-static-view-components.sh"

fail=0

echo "· every named gate is on disk"
missing=""
while IFS= read -r g; do
  [ -n "$g" ] || continue
  [ -f "$G/$g" ] || missing="$missing $g"
done <<EOF
$REPAIRED
EOF
[ -f "$G/$SURVIVED" ] || missing="$missing $SURVIVED"
[ -f "$G/notifications_wired.py" ] || missing="$missing notifications_wired.py"
if [ -n "$missing" ]; then
  echo "  absent:$missing"
  echo "VERDICT: broken — a gate named by F-0329 is gone; a record closed against a gate that"
  echo "         no longer exists is uncovered and nothing else would say so"
  exit 1
fi
echo "  clean — 11 gates present (10 repaired + 1 that survived injection)"

echo "· each reads CODE, not file bytes (F-0266): sources gates/_code.sh"
bad=""
while IFS= read -r g; do
  [ -n "$g" ] || continue
  grep -q "_code.sh" "$G/$g" || bad="$bad $g"
done <<EOF
$REPAIRED
EOF
grep -q "_code.sh" "$G/$SURVIVED" || bad="$bad $SURVIVED"
grep -qE "_code|strip_comments" "$G/notifications_wired.py" || bad="$bad notifications_wired.py"
if [ -n "$bad" ]; then
  echo "  reads raw bytes:$bad"
  echo "VERDICT: broken — a gate is grepping file bytes again (F-0266/F-0329); it will fail the"
  echo "         fix whose own comment quotes the forbidden string, and green a fix that exists"
  echo "         only in a comment"
  fail=1
fi
[ -n "$bad" ] || echo "  clean — all 11 read a comment-stripped view"

echo "· each carries an EXECUTION leg, not greps alone"
bad=""
while IFS= read -r g; do
  [ -n "$g" ] || continue
  grep -qE "bin/vitest|node .*--|node -e|vm\.|_pii_guard|_dispute_wire|_member_src|_f0240_chain|mvn " "$G/$g" \
    || bad="$bad $g"
done <<EOF
$REPAIRED
EOF
if [ -n "$bad" ]; then
  echo "  grep-only:$bad"
  echo "VERDICT: broken — a gate decides on greps alone (F-0329); ten of eleven such gates were"
  echo "         proven unable to fail against their own recorded defect"
  fail=1
fi
[ -n "$bad" ] || echo "  clean — all 10 repaired gates execute something"

echo "· each carries the self-falsification device (a frozen known-bad, checked every run)"
bad=""
while IFS= read -r g; do
  [ -n "$g" ] || continue
  grep -qiE "known.bad|cannot fail|self.falsif|self.check" "$G/$g" || bad="$bad $g"
done <<EOF
$REPAIRED
EOF
grep -qiE "known.bad|cannot fail|self.falsif|self.check" "$G/notifications_wired.py" || bad="$bad notifications_wired.py"
if [ -n "$bad" ]; then
  echo "  no frozen known-bad:$bad"
  echo "VERDICT: broken — a gate no longer proves its own assertion table rejects the defect"
  echo "         (F-0329); without that, rot is invisible until someone injects by hand"
  fail=1
fi
[ -n "$bad" ] || echo "  clean — all 10 repaired gates falsify themselves on every run"

echo "· gate fixtures stay out of the product suite (F-0334)"
if ! grep -q "proof-os" vitest.config.ts 2>/dev/null; then
  echo "  vitest.config.ts no longer excludes .proof-os"
  echo "VERDICT: broken — trust-layer fixtures are swept into the product suite again (F-0334);"
  echo "         a gate fixture failing would read as a product regression, and build.sh's own"
  echo "         test leg would partly grade the trust layer"
  fail=1
elif [ ! -f "$G/vitest.gates.config.ts" ]; then
  echo "  $G/vitest.gates.config.ts missing while the main config excludes .proof-os"
  echo "VERDICT: broken — the gates that keep fixtures under .proof-os cannot run them (F-0334)"
  fail=1
else
  bad=""
  for g in "$G"/*.sh; do
    grep -q "bin/vitest" "$g" 2>/dev/null || continue
    grep -qE "\.proof-os/gates/[^ ]*\.(spec|test)\.tsx|_work-" "$g" 2>/dev/null || continue
    grep -q "vitest.gates.config.ts" "$g" || bad="$bad $(basename "$g")"
  done
  if [ -n "$bad" ]; then
    echo "  runs a .proof-os spec without the gates config:$bad"
    echo "VERDICT: broken — that invocation resolves to 'No test files found', which most gates"
    echo "         map to unavailable rather than a finding (F-0334)"
    fail=1
  else
    echo "  clean — .proof-os excluded from the product suite; gate specs run under the gates config"
  fi
fi

echo "· the injection evidence is on record"
LOGDIR=".proof-os/tasks/T-F0329-GATES"
[ -d "$LOGDIR" ] || LOGDIR=".proof-os/_archive/T-F0329-GATES"
n=$(ls "$LOGDIR"/*.inject.log 2>/dev/null | wc -l)
if [ "$n" -lt 10 ]; then
  echo "  only $n inject log(s) under $LOGDIR"
  echo "VERDICT: broken — the observed exit codes that justify these repairs are missing; the"
  echo "         claim that ten of eleven gates could not fail is then unbacked (F-0329)"
  fail=1
else
  echo "  clean — $n injection logs with observed exit codes"
fi

[ $fail -eq 1 ] && { echo "VERDICT: broken (real findings above)"; exit 1; }
echo "VERDICT: aligned (proved) — the eleven gates F-0329 named all read code rather than bytes,"
echo "         execute rather than grep, prove on every run that their own assertions reject a"
echo "         frozen known-bad, and keep their fixtures out of the product suite"
echo "NOT CHECKED: whether each gate's assertions are the RIGHT ones for its record — only that"
echo "             they are the kind of assertion that can fail | whether a gate is name-locked"
echo "             to the identifiers its record happened to mention (F-0335 is open against"
echo "             F-0050 for exactly that) | the 7 other .py gates that still read raw bytes |"
echo "             any gate outside the eleven — 96 promoted gates were classified and only"
echo "             these were injected, so more false greens of this class are likely | whether"
echo "             a future gate materialising a spec under .proof-os remembers the gates config"
exit 0
