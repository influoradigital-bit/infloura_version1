#!/usr/bin/env bash
# gates/F-0643-rate-before-commitment.sh — instance gate, closes F-0643.
#
# Also closes F-0470 (partial-fix-narrows-defect): that row recorded F-0399's cumulative budget
# gate as defeated by a null agreedRate, since apply()/invite() set no rate yet are acceptable,
# so the incoming offer contributed ZERO at the comparison. The guard proved below rejects the
# transition before that comparison is ever reached, which removes the hole rather than repricing it.
#
# origin: F-0643 (worst-case-valuation-blocks-legitimate-flow) — F-0476 valued a rate-less
# collaboration at the campaign's full budgetMax to avoid undercounting the cap, so ONE
# invite-then-accept with no negotiated rate consumed the entire campaign budget and permanently
# blocked collaborator #2. The row was explicitly parked pending a product ruling.
#
# RULING (Swapnil, CEO, 2026-09-05): "Rate-less collab should not reach TERMS_AGREED, approved."
# The liability is REMOVED rather than priced — doAccept rejects the transition outright when
# agreedRate is absent, so no rate-less row can reach a budget-committed status going forward, and
# committedValue therefore no longer needs a worst-case figure.
#
# PROVENANCE WARNING, kept deliberately (see F-0680): the implementation this gate proves was
# written at 15:49 and already cited "(CEO ruling)" in five places. The ruling was actually given
# at ~17:56, over two hours later. The code matched the ruling that eventually arrived, so it
# stands — but this gate proves the CODE, and asserts nothing about the citation's honesty.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

SRC=influora-api/src/main/java/com/influora/service/DealService.java
[ -f "$SRC" ] || { echo "· $SRC missing — unavailable"; exit 2; }

# ---------------------------------------------------------------------------
# CHECK A — the guard exists and is INVOKED (not merely defined and dead).
# ---------------------------------------------------------------------------
if ! grep -q "private void requireAgreedRateForCommitment" "$SRC"; then
  echo "VERDICT: broken — requireAgreedRateForCommitment is not defined (F-0643)"
  exit 1
fi
calls=$(grep -c "^\s*requireAgreedRateForCommitment(collaboration);" "$SRC" || true)
if [ "$calls" -lt 1 ]; then
  echo "VERDICT: broken — requireAgreedRateForCommitment is defined but never called; the guard is"
  echo "         dead code and a rate-less accept still reaches TERMS_AGREED (F-0643)"
  exit 1
fi
echo "· guard defined and called ($calls call site(s))"

# ---------------------------------------------------------------------------
# CHECK B — ORDERING. The guard must precede the TERMS_AGREED transition AND the budget gate,
# because requireWithinRemainingBudget/committedValue now ASSUME every committed row is priced.
# A guard that runs after them is worse than none: the assumption would be false.
# ---------------------------------------------------------------------------
ln_guard=$(grep -n "^\s*requireAgreedRateForCommitment(collaboration);" "$SRC" | head -1 | cut -d: -f1)
ln_budget=$(grep -n "^\s*requireWithinRemainingBudget(collaboration);" "$SRC" | head -1 | cut -d: -f1)
ln_trans=$(grep -n "collaboration.transitionTo(CollaborationStatus.TERMS_AGREED);" "$SRC" | head -1 | cut -d: -f1)
if [ -z "$ln_budget" ] || [ -z "$ln_trans" ]; then
  echo "· could not locate the budget gate or the TERMS_AGREED transition — the method was"
  echo "  restructured, so an ordering claim here would be fabricated"
  exit 2
fi
echo "· line order: guard=$ln_guard budget=$ln_budget transition=$ln_trans"
if [ "$ln_guard" -ge "$ln_budget" ] || [ "$ln_guard" -ge "$ln_trans" ]; then
  echo "VERDICT: broken — the rate guard does not run FIRST; the budget gate's assumption that"
  echo "         every committed row is priced is therefore unfounded (F-0643)"
  exit 1
fi

# ---------------------------------------------------------------------------
# CHECK C — committedValue must value a null rate at ZERO, not budgetMax. Valuing it at budgetMax
# is the ORIGINAL F-0643 defect; a fix that guards accept but leaves the valuation would still let
# one legacy row block the whole campaign.
# ---------------------------------------------------------------------------
if ! grep -q "c.getAgreedRate() != null ? c.getAgreedRate() : BigDecimal.ZERO" "$SRC"; then
  echo "VERDICT: broken — committedValue no longer values a null agreedRate at ZERO; a legacy"
  echo "         rate-less committed row can block every future accept again (F-0643)"
  exit 1
fi
echo "· committedValue values a null rate at ZERO, not budgetMax"

# ---------------------------------------------------------------------------
# CHECK D — BEHAVIOUR. Structure above proves shape, not effect. mvn -o clean is mandatory here:
# a stale .class produced a FALSE GREEN on this project during an earlier revert-probe.
# ---------------------------------------------------------------------------
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — structure checked, BEHAVIOUR NOT PROVED"; exit 2; }
# A missing/!buildable module is an UNAVAILABLE instrument, never a finding. Without this, running
# the gate against a partial tree (a fixture, a sparse checkout) reported "the F-0643 regression
# tests do not pass" — an environment failure dressed up as a real defect, which is the one
# direction a gate must never fail in.
[ -f influora-api/pom.xml ] || { echo "· influora-api/pom.xml missing — cannot run tests here; structure checked, BEHAVIOUR NOT PROVED"; exit 2; }
echo "· running DealServiceBudgetTest + DealServiceTest (mvn -o clean test)"
log=$(mktemp 2>/dev/null || echo "/tmp/f0643.$$")
( cd influora-api && mvn -o clean test -Dtest=DealServiceBudgetTest,DealServiceTest -DfailIfNoTests=true ) >"$log" 2>&1
rc=$?
if grep -qE "No compiler is provided|Could not resolve dependencies|Non-resolvable|There is no POM in this directory|Unknown lifecycle phase|The goal you specified requires a project" "$log"; then
  echo "· the build could not run offline (toolchain/deps) — unavailable, NOT a pass"
  tail -15 "$log"; exit 2
fi
if [ "$rc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0643 regression tests do not pass"
  grep -E "Tests run|FAIL|ERROR\]" "$log" | head -25
  exit 1
fi
grep -E "Tests run:.*DealService" "$log" | head -5
if ! grep -q "testAcceptRejectsNullRateApplication" influora-api/src/test/java/com/influora/service/DealServiceBudgetTest.java; then
  echo "VERDICT: broken — the test that pins the REJECTION is gone; a green run below proves"
  echo "         only that the surviving tests pass (F-0643)"
  exit 1
fi
echo "· testAcceptRejectsNullRateApplication is present and in the green run"

echo "VERDICT: aligned (proved) — a rate-less collaboration cannot reach TERMS_AGREED: the guard"
echo "         is called, runs before both the budget gate and the transition, a legacy null-rate"
echo "         committed row is valued at ZERO so it no longer blocks the campaign, and the"
echo "         regression tests pinning both halves pass against a clean build."
echo "NOT CHECKED: whether the FRONTEND stops a brand reaching the Accept button with no rate —"
echo "             this proves the server refuses, not that the UI explains why. The pre-ruling"
echo "             rate-less rows already sitting in a committed status are undercounted at ZERO"
echo "             by design (accepted trade-off in the ruling), and no data migration re-prices"
echo "             them; this gate does not look for such rows in any live database. Says nothing"
echo "             about the honesty of the '(CEO ruling)' citations — that is F-0680."
exit 0
