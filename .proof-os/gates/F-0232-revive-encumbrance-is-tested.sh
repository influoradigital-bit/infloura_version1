#!/usr/bin/env bash
# F-0232-revive-encumbrance-is-tested.sh — gate for F-0232 (revive-ignores-attached-artifacts).
#
# THE RECORD. The first F-0225 fix reasoned that a CANCELLED collaboration cannot carry a
# contract, because CANCELLED is written only by DealService#doReject, gated on the pre-contract
# canReject() allowlist. That reasoning is false twice over: rows cancelled under the pre-CR-22a
# denylist are in the database today with live contracts behind them, and ShipmentService never
# reads CollaborationStatus at all, so a Shipment attaches to a row of any status. Reviving such
# a row flips it back to APPLIED/INVITED, which is exactly what RE-ARMS EscrowService's
# status-based funding and release guards against whatever is still attached.
#
# WHAT MISSED IT — and therefore what this gate is:
#   "no test asserted what may be ATTACHED to a row being revived; the behavioural tests mocked
#    the repository and could not see it."
#
# A mocked repository answers whatever the test stubs. So a probe the test never stubs is a
# probe the suite cannot see: Mockito's default (false / empty Optional / empty List) makes it
# look clean, every assertion still passes, and DELETING that probe from the service would not
# turn a single test red. That is the precise shape of F-0232 — a guard nothing holds.
#
# This gate therefore holds the COVERAGE, not the prose:
#   1. STRUCTURE — describeEncumbrance still probes all three artifact families the record names
#      (contract twice: the ContractService#generate duplicate question AND the status-blind
#      "does any contract row exist" question; escrow; shipment). Deleting a probe to make leg 2
#      pass fails here instead.
#   2. COVERAGE — EVERY repository probe describeEncumbrance actually calls must be stubbed
#      POSITIVE by a test that constructs CollaborationReviveService and asserts
#      COLLABORATION_NOT_REVIVABLE. An unstubbed probe is an unheld guard.
#   3. BEHAVIOUR — that suite must actually run green, so leg 2 cannot be satisfied by a test
#      that is @Disabled or does not compile.
#
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u

SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }

# F-0266: grep CODE, not file bytes. This service's javadoc NAMES the probe methods it calls
# (existsByCollaborationIdAndStatusNot, and the status-blind one, in the "F-0232 — why the
# contract probe is status-blind" section). A byte-level grep would find those names in the
# comment and certify a service whose describeEncumbrance body had been gutted.
. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }

SVC=influora-api/src/main/java/com/influora/service/CollaborationReviveService.java
TESTDIR=influora-api/src/test/java/com/influora
[ -f "$SVC" ] || { echo "· $SVC missing — unavailable"; exit 2; }
[ -d "$TESTDIR" ] || { echo "· $TESTDIR missing — unavailable"; exit 2; }

SVC_CODE=$(code_view "$SVC") || { echo "· $(code_why) — unavailable"; exit 2; }

# ---- 1. structure: the probes the record names are still in describeEncumbrance --------------
# The method body, signature to its own 4-space closing brace.
BODY=$(awk '/private String describeEncumbrance\(/,/^    \}/' "$SVC_CODE")
[ -n "$BODY" ] || {
  echo "· could not locate describeEncumbrance() in the code view of $SVC — unavailable"
  exit 2
}

echo "· structure: describeEncumbrance still probes contract (x2), escrow and shipment"
n_contract=$(printf '%s\n' "$BODY" | grep -c "contractRepository")
n_escrow=$(printf '%s\n' "$BODY" | grep -c "escrowHoldRepository")
n_ship=$(printf '%s\n' "$BODY" | grep -c "shipmentRepository")
missing=""
# TWO contract probes on purpose. existsByCollaborationIdAndStatusNot answers
# ContractService#generate's DUPLICATE question, under which a CANCELLED contract is "clean" —
# but a cancelled contract leaves its PaymentMilestone and Deliverable rows keyed on this
# collaboration and nothing reverses them. Collapsing back to one probe re-opens F-0232 one
# status further along.
[ "$n_contract" -ge 2 ] || missing="$missing\n  contract probed $n_contract time(s), expected 2 (duplicate-question AND status-blind)"
[ "$n_escrow" -ge 1 ] || missing="$missing\n  no escrowHoldRepository probe"
[ "$n_ship" -ge 1 ] || missing="$missing\n  no shipmentRepository probe"
if [ -n "$missing" ]; then
  printf '%b\n' "$missing"
  echo "VERDICT: broken — describeEncumbrance no longer asks what is ATTACHED to the row it is"
  echo "         about to revive; reviving re-arms EscrowService's status guards against it (F-0232)"
  echo "NOT CHECKED: coverage and behaviour legs did not run — the structure leg failed first."
  exit 1
fi
echo "  present — contract x$n_contract, escrow x$n_escrow, shipment x$n_ship"

# The probe method names actually CALLED in the body. Restricted to query-shaped prefixes so
# Optional#isPresent / List#isEmpty on the results are not mistaken for repository probes.
PROBES=$(printf '%s\n' "$BODY" \
  | grep -oE '\.(exists|find|count|has|get)[A-Za-z0-9_]*\(' \
  | sed 's/^\.//; s/($//; s/(//' | sort -u)
[ -n "$PROBES" ] || { echo "· no repository probes parsed out of describeEncumbrance — unavailable"; exit 2; }

# ---- 2. coverage: every probe is stubbed POSITIVE by a revive test that asserts the refusal ---
echo "· coverage: each probe is exercised by a test that stubs it POSITIVE and expects the 409"

# Test files that actually construct the service (in CODE, not in a comment).
TESTS=$(code_grep_r 'new CollaborationReviveService\(' "$TESTDIR" 2>/dev/null | cut -d: -f1 | sort -u)
rc=$?
[ $rc -eq 2 ] && { echo "· $(code_why) — unavailable"; exit 2; }
if [ -z "$TESTS" ]; then
  echo "  no test file constructs CollaborationReviveService"
  echo "VERDICT: broken — nothing exercises the revive encumbrance guard at all (F-0232)"
  echo "NOT CHECKED: the behaviour leg did not run — there is no suite to run."
  exit 1
fi

uncovered=""
for probe in $PROBES; do
  found=""
  for t in $TESTS; do
    tv=$(code_view "$t") || { echo "· $(code_why) — unavailable"; exit 2; }
    # The file must be about this refusal at all...
    grep -q "COLLABORATION_NOT_REVIVABLE" "$tv" || continue
    # ...and, in a 4-line window opening at each mention of the probe (a Mockito stub is
    # `when(repo.probe(...))` on one line and `.thenReturn(...)` on the next), it must stub a
    # POSITIVE answer. `.thenReturn(false)` / `Optional.empty()` in setUp is the DEFAULT state
    # — it proves nothing; only a positive stub can prove the probe is consulted.
    win=$(awk -v p="$probe" '
      { if (match($0, "(^|[^A-Za-z0-9_])" p "\\(")) c = 4
        if (c > 0) { print; c-- } }' "$tv")
    printf '%s\n' "$win" \
      | grep -qE 'thenReturn\((true|Optional\.of|List\.of|java\.util\.List\.of|Collections\.singletonList|new ArrayList)' \
      && { found=1; break; }
  done
  [ -n "$found" ] || uncovered="$uncovered\n  $probe — never stubbed with a positive answer by any revive test"
done

if [ -n "$uncovered" ]; then
  printf '%b\n' "$uncovered"
  echo "  (test files inspected: $(printf '%s ' $TESTS))"
  echo "VERDICT: broken — a probe in describeEncumbrance is invisible to the suite. The repository is"
  echo "         MOCKED, so an unstubbed probe returns Mockito's empty default and looks clean:"
  echo "         deleting that probe outright would not turn one test red, which is exactly how"
  echo "         F-0232 got through. Stub it positive and assert COLLABORATION_NOT_REVIVABLE."
  echo "NOT CHECKED: the behaviour leg did not run — coverage failed first, so a green suite would"
  echo "             have proved nothing about the uncovered probe."
  exit 1
fi
echo "  every probe has a positive-stub test"

# ---- 3. behaviour: that suite actually runs green ---------------------------------------------
command -v mvn >/dev/null 2>&1 || {
  echo "· mvn not on PATH — cannot run the suite"
  echo "VERDICT: unavailable — coverage holds, but the suite could not be executed"
  echo "NOT CHECKED: whether the covering tests compile, run and pass."
  exit 2
}
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — unavailable"; exit 2; }

# Names, not paths: -Dtest takes simple class names.
CLASSES=$(for t in $TESTS; do basename "$t" .java; done | sort -u | paste -sd, -)
echo "· mvn -o test -Dtest=$CLASSES"
TMO=""; command -v timeout >/dev/null 2>&1 && TMO="timeout 600"
out=$(cd influora-api && $TMO mvn -q -o test -Dtest="$CLASSES" -DfailIfNoSpecifiedTests=false 2>&1); rc=$?
if [ $rc -eq 124 ]; then
  echo "VERDICT: unavailable — the suite exceeded the 600s wall clock; refusing to call that broken"
  echo "NOT CHECKED: whether the covering tests pass."
  exit 2
fi
if [ $rc -ne 0 ]; then
  if printf '%s' "$out" | grep -qiE "Could not resolve dependencies|Non-resolvable|Unknown host|was cached in the local repository|No compiler is provided"; then
    printf '%s\n' "$out" | grep -iE "ERROR" | head -5
    echo "VERDICT: unavailable — maven could not build offline (dependency/toolchain), not a test failure"
    echo "NOT CHECKED: whether the covering tests pass."
    exit 2
  fi
  printf '%s\n' "$out" | grep -E "ERROR|Tests run|FAIL" | tail -20
  echo "VERDICT: broken — the revive encumbrance suite does not pass (F-0232)"
  echo "NOT CHECKED: nothing beyond this failure was evaluated."
  exit 1
fi
echo "  suite green"

echo "VERDICT: aligned (proved) — every artifact probe guarding a revive is exercised by a test that"
echo "         stubs it positive and asserts the 409, so deleting a probe now turns the suite red"
echo "NOT CHECKED: that the probes are the RIGHT set. This gate proves each probe the service asks is"
echo "             held by a test; it cannot know of an artifact family nobody thought to probe (a"
echo "             future table keyed on collaboration_id with its own repository). It also does not"
echo "             touch a database: the tests are Mockito, so the claim 'rows cancelled under the"
echo "             pre-CR-22a denylist carry live contracts TODAY' is asserted only against mocks —"
echo "             confirming it needs a query against production MySQL, which nothing here runs."
echo "             PaymentMilestone/Deliverable/Dispute are covered only TRANSITIVELY (they cannot"
echo "             exist without a contract or escrow row to trip on); if that ever stops being true"
echo "             this gate will not notice. And it says nothing about whether the 409 message"
echo "             reads correctly to a brand or a creator."
exit 0
