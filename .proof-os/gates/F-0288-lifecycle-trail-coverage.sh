#!/usr/bin/env bash
# F-0288-lifecycle-trail-coverage.sh — gate for F-0288 (thin-event-log).
#
# RECORD. "The only chronological trail that exists is DealMessage system messages, and exactly
# two are ever written — accept and reject. Contract generated/signed, escrow funded, deliverable
# submitted/approved and payment released write no system message, so the chat thread covers 2 of
# the roughly 11 event types FRD section 4 lists."
#   missed_by: "a test asserting each lifecycle transition appends a system message to the deal
#   thread"
#
# THE GATE IS THAT TEST. It runs one JUnit class —
#   influora-api/src/test/java/com/influora/service/DealTrailCoverageTest.java
# — and nothing else. Two legs, deliberately different in kind, because a text scan cannot see
# either half of this defect: the bug is an ABSENT write, and grep has nothing to match on.
#
#   Leg 1 — the six post-agreement handlers that now exist on DealService
#           (onContractGenerated / onContractSigned / onEscrowFunded / onDeliverableSubmitted /
#           onDeliverableApproved / onPayoutReleased) each persist a kind=system DealMessage on
#           the right collaboration, with non-blank content. Direct regression armour for the
#           handlers that landed, and — critically — the anti-vacuity control: it proves this
#           harness can SEE a trail row being written, so leg 2's negative result is a missing
#           row and not a broken rig.
#
#   Leg 2 — a lifecycle transition with NO handler at all is invisible to leg 1, because there is
#           nothing to call. Leg 2 therefore drives the real BrandDeliverableService review path
#           and asks whether what it publishes is something the deal trail listens to. The
#           listener set is read off DealService BY REFLECTION (@TransactionalEventListener
#           parameter types), never hardcoded, so a genuine fix makes it pass with no edit to the
#           test. It carries its own control: approve() — which does reach the trail — is asserted
#           in the same shape, so an always-red leg 2 is impossible to mistake for a finding.
#
# WHY IT IS RED TODAY. Leg 1 and the leg-2 control pass. Leg 2 fails on
# BrandDeliverableService#requestRevision: it saves the revision, calls
# CollaborationLifecycleService#onDeliverableReviewed (which moves the collaboration to
# REVISION_REQUESTED — a first-class CollaborationStatus), and publishes NO application event at
# all, so nothing on the DealService trail can hear it. A creator whose work is sent back sees
# nothing whatsoever in the deal room. That is the same thin-event-log defect this record names,
# in a transition the already-landed handlers do not cover.
#
#   exit 0 = proved (defect absent) · 1 = broken (defect present) · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; echo "VERDICT: unavailable"; echo "NOT CHECKED: everything"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; echo "VERDICT: unavailable"; echo "NOT CHECKED: everything"; exit 2; }

SUITE=influora-api/src/test/java/com/influora/service/DealTrailCoverageTest.java
CLASS=DealTrailCoverageTest

unavailable() {
  echo "· $1 — unavailable"
  echo "VERDICT: unavailable — the trail test did not reach a result, so nothing was observed"
  echo "NOT CHECKED: everything — no assertion ran"
  exit 2
}

command -v mvn >/dev/null 2>&1 || unavailable "mvn not on PATH"
[ -f influora-api/pom.xml ] || unavailable "no influora-api/pom.xml here"
[ -f "$SUITE" ] || unavailable "$SUITE missing (the gate IS this test — it cannot be inferred)"

BUDGET="${PROOF_F0288_TIMEOUT:-600}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 30 $BUDGET"; else TO=""; fi

echo "· mvn -o test -Dtest=$CLASS (offline; budget ${BUDGET}s)"
out=$(cd influora-api && $TO mvn -o test -Dtest="$CLASS" -DfailIfNoTests=false 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  unavailable "the run exceeded ${BUDGET}s"
fi

# A compile failure, a locked target/ (another session building concurrently), or a missing
# dependency is NOT a finding. The only thing that may ever produce exit 1 here is a surefire
# result line, i.e. the test actually ran and reported.
if printf '%s\n' "$out" | grep -qE "COMPILATION ERROR|Failed to clean project|NoClassDefFoundError|Could not resolve dependencies"; then
  printf '%s\n' "$out" | grep -E "^\[ERROR\].*(COMPILATION|does not exist|NoClassDefFound|Failed to clean|Could not resolve)" | head -6
  unavailable "the module did not build (compile error / locked target / missing dependency)"
fi

summary=$(printf '%s\n' "$out" | grep -E "Tests run: [0-9]+, Failures:" | tail -1)
[ -n "$summary" ] || { printf '%s\n' "$out" | tail -15; unavailable "no surefire summary in the output — the test never ran to a result"; }
echo "  $summary"

# A Mockito/ByteBuddy instrumentation blow-up is an environment fault, not a missing trail row.
# It shows up as an ERROR on every case at once; treat that as unavailable rather than red.
if printf '%s\n' "$out" | grep -q "Byte Buddy could not instrument all classes"; then
  unavailable "ByteBuddy could not instrument the mock hierarchy (stale/partial target/classes from a concurrent build)"
fi

if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "^\[ERROR\]   $CLASS" | head -10
  echo "VERDICT: broken — a deal lifecycle transition still appends no system message to the deal"
  echo "         thread (F-0288). Read the assertion text above: it names the exact transition."
  echo "         If the failing case is 'leg 2 (control)' rather than 'leg 2', the harness itself"
  echo "         is wrong and this is NOT a finding — fix the control first."
  echo "NOT CHECKED: the remaining uncovered transitions — DISPUTED (DisputeService publishes no"
  echo "             application event at all), COMPLETED, and CANCELLED-by-lifecycle; the exact"
  echo "             WORDING of any trail row; whether the row is visible to both parties in the"
  echo "             rendered UI; and whether the @Async/AFTER_COMMIT handlers fire against a real"
  echo "             Spring context and a real database — these are unit-level invocations."
  exit 1
fi

echo "VERDICT: aligned (proved) — every lifecycle transition this test covers appends a system row"
echo "         to the deal thread, and the brand review path publishes a signal the trail hears"
echo "NOT CHECKED: DISPUTED / COMPLETED / CANCELLED-by-lifecycle transitions; trail-row wording;"
echo "             UI rendering of the rows; and real @Async/AFTER_COMMIT delivery inside a live"
echo "             Spring context — this gate invokes the handlers directly, so a listener that is"
echo "             registered but never actually fired in production would still pass here."
exit 0
