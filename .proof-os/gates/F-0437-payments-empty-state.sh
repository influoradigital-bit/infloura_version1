#!/usr/bin/env bash
# gates/F-0437-payments-empty-state.sh
# origin failure: F-0437 (fabricated-data-in-ui), opened by priya 2026-09-02.
#
#   "When the server sends no milestones the payments tab replaces the empty list with a
#    fabricated schedule derived from the deal value and maps over it unconditionally, so a
#    brand sees an invented payment plan; the sibling contract tab has a real empty state."
#
# missed_by: "a test asserting the payments tab renders an empty state when the server returns
# no milestones". That sentence is this gate's whole specification.
#
# THE DEFECT, in src/components/brand/deal-room/deal-payments-tab.tsx:
#   const realMilestones = milestones ?? [];              // [] and undefined collapse together
#   const hasRealMilestones = realMilestones.length > 0;
#   const perDeliverable = deliverablesTotal > 0 ? Math.round(dealValue / deliverablesTotal) : dealValue;
#   const rows = hasRealMilestones ? realMilestones.map(...) : [ 'Funds secured' @ dealValue,
#                ...deliverablesTotal x ('Deliverable N payout' @ perDeliverable) ];
#   {rows.map(...)}                                       // rendered unconditionally
# brand-chat.tsx passes `milestones={liveApiMode ? liveContract?.milestones : undefined}`, so a
# live contract carrying zero payment_milestones hands this panel `[]` and the brand reads a
# complete payment plan whose every line amount was computed in the browser.
#
# WHY THIS GATE IS AN EXECUTION GATE, NOT A GREP. Both obvious greps are vacuous here.
# Forbidding the string `Deliverable ${i + 1} payout` is a wording snapshot: relabel the rows and
# the invented plan still renders. Requiring `hasRealMilestones` / `.length === 0` is worse — the
# identifier is ALREADY in this file three times (the ternary, the release-button guard, the
# disclaimer at the bottom) and the defect is live anyway, so such a check greens today. The
# arithmetic is the defect, so the spec renders the panel with an empty milestone list and looks
# for money that could only have come from `dealValue / deliverablesTotal`, plus the empty state
# the ledger says should be there.
#
# FALSIFICATION, MEASURED (not assumed), 2026-09-02, on this tree:
#   · unmodified tree                       -> exit 1, 4 self-checks pass, both real legs fail
#                                              (rendered ₹12,857; zero empty-state clauses)
#   · component patched so `milestones={[]}` yields rows=[] plus "No payment milestones on this
#     contract." (the deal-contract-tab shape)  -> exit 0, 6/6 green
#   The patch was reverted; the component is byte-identical to before this gate was written.
#   The first version of the prose reader used flat textContent and FAILED that correct fix
#   ("Payment milestonesNo payment..." — \bno\b cannot match inside a welded word); that false
#   red is why proseOf() below preserves block boundaries.
#
# LAW: exit 0 = proved · exit 1 = broken (real finding) · exit 2 = cannot run.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

# F-0266: look at CODE, not file bytes — a gate that cannot tell a statement from a comment
# fails the fix whose comment quotes the forbidden string, and greens a fix that was only ever
# described in one.
. "$SELF/_code.sh" 2>/dev/null || { echo "· gates/_code.sh unreadable — unavailable"; exit 2; }
code_ready || { echo "· $(code_why) — unavailable"; exit 2; }

F=src/components/brand/deal-room/deal-payments-tab.tsx
SPEC="$SELF/F-0437.payments-empty-state.spec.tsx"
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
[ -f "$SPEC" ] || { echo "· $SPEC missing — the execution leg is gone — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "· $(code_why) — unavailable"; exit 2; }

# ---------------------------------------------------------------------------
# 1 · the subject still exists as something the spec can mount. A renamed/removed
#     export would surface as an import error inside vitest; say it plainly here.
# ---------------------------------------------------------------------------
if ! grep -qE "export (function|const) DealPaymentsTab" "$F_CODE"; then
  echo "· $F no longer exports DealPaymentsTab — the panel F-0437 is about is gone or renamed,"
  echo "  and nothing here can render it"
  echo "VERDICT: broken — F-0437's subject no longer exists (F-0437)"
  echo "NOT CHECKED: whatever replaced it"
  exit 1
fi
echo "· subject present: DealPaymentsTab in $F"

# ---------------------------------------------------------------------------
# 2 · EXECUTION. Render the panel with an EMPTY server milestone list and audit
#     what the brand actually reads. The spec carries its own falsification
#     (frozen good/bad copy, a positive control on the rupee extractor, and a
#     real-milestone render) as its first four tests.
# ---------------------------------------------------------------------------
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
if [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: everything this gate asserts. There is deliberately no grep-only fallback:"
  echo "             the grep version of this check is exactly the vacuous one described above."
  exit 2
fi
# F-0334: gate fixtures live under .proof-os/gates/, which vitest.config.ts excludes from the
# product suite; vitest applies `exclude` even to an explicitly-passed path, so the spec runs
# under gates/vitest.gates.config.ts (the project config with that one exclusion removed).
GATES_CFG="$SELF/vitest.gates.config.ts"
[ -f vitest.config.ts ] || { echo "· vitest.config.ts missing — unavailable"; exit 2; }
[ -f "$GATES_CFG" ] || { echo "· $GATES_CFG missing — the gate fixture cannot be collected — unavailable"; exit 2; }

BUDGET="${PROOF_F0437_VITEST_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
echo "· vitest: F-0437.payments-empty-state.spec.tsx (budget ${BUDGET}s)"
out=$($TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$SPEC" 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  exit 2
fi
if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected no test file for $SPEC — unavailable"
  exit 2
fi
if printf '%s' "$out" | grep -q "THIS TEST CANNOT FAIL"; then
  printf '%s\n' "$out" | tail -40
  echo "· THIS GATE CANNOT FAIL: one of its own instruments failed its self-check — the rupee"
  echo "  extractor, the empty-state audit, or the render path is blind. Refusing to report a"
  echo "  verdict about the panel from a check that has just proved it cannot see."
  echo "VERDICT: broken — the F-0437 gate's own instruments no longer detect F-0437 (F-0273)"
  echo "NOT CHECKED: the real component — this run never got a trustworthy answer about it"
  exit 1
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -45
  echo "VERDICT: broken — with the server returning NO milestones the payments tab still shows the"
  echo "         brand a payment plan it invented from dealValue / deliverablesTotal, and/or never"
  echo "         says the contract has no milestones. The sibling deal-contract-tab.tsx renders"
  echo "         \"No milestones on this contract.\" for the same state (F-0437)"
  echo "NOT CHECKED: see below"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — the payments panel was RENDERED as the brand deal room with an"
echo "         empty server milestone list, in two funding/progress states. It printed no rupee"
echo "         figure derivable from dealValue / deliverablesTotal, and it stated outright that"
echo "         there are no payment milestones. Both instruments were proved falsifiable on this"
echo "         run: the rupee extractor found a planted ₹12,857 and did not hallucinate one; the"
echo "         empty-state audit rejected the panel's current disclaimer copy and three other"
echo "         frozen near-misses while accepting three frozen good ones; and a render carrying"
echo "         two REAL milestones surfaced their server amounts, so a clean result cannot be a"
echo "         blank read."
echo "NOT CHECKED: the milestones={undefined} mount — the creator deal room (creator-chat.tsx)"
echo "             and brand mock mode pass no milestones prop at all, and this gate does not"
echo "             rule on whether the derived placeholder is acceptable there; the ledger record"
echo "             is about the server ANSWERING with no milestones. Whether brand-chat.tsx"
echo "             actually reaches this panel with [] rather than undefined when a live contract"
echo "             has no payment_milestones (that is fetchLiveContract's shape, not this file's)."
echo "             Whether the Secured/Released summary cards are truthful when real milestones"
echo "             ARE present (F-0239/F-0222 territory). Any live-backend behaviour."
exit 0
