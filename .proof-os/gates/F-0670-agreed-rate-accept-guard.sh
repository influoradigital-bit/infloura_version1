#!/usr/bin/env bash
# gates/F-0670-agreed-rate-accept-guard.sh — closes F-0670.
#
# origin: F-0670 (dead-control-from-new-guard) — a live break this project's OWN work created.
# An approved CEO ruling (F-0643) made the backend reject accepting a collaboration with no
# agreedRate: DealService#requireAgreedRateForCommitment throws AGREED_RATE_REQUIRED / HTTP 409 from
# doAccept. The frontend was never updated in the same wave. `grep AGREED_RATE_REQUIRED src/`
# returned ZERO hits, while brand invite, creator apply AND Meera's confirm_launch all create
# rate-less collaborations BY CONSTRUCTION — so Accept on a fresh invite always 409'd. Every creator
# hitting Accept on a new invite got an error.
#
# TWO SURFACES, and that is the point of this gate. The first pass fixed creator-deals.tsx only; a
# fresh-context review then found creator-chat.tsx gated its own bare-invite Accept on
# `collaborationStatus === 'INVITED'` — precisely the population that always 409s — and that
# describeProposalActionError had no AGREED_RATE_REQUIRED case, so the failure fell through to a
# branch setting stale:true and advising the creator to REFRESH. Refreshing cannot create a rate.
# `partial-fix-narrows-defect` is a blocked recurring class on this ledger; this gate checks BOTH.
#
# THE FIX (both surfaces): derive hasAgreedRate from the deal's own amount — exact, not a heuristic:
# budget/dealAmount is parseDealAmount(deal.dealValue) and dealValue is collaboration.getAgreedRate()
# verbatim (DealService.java:2138), while every real proposal/counter amount is server-validated
# @DecimalMin("0.01"), so the value can only be 0 when agreedRate is null. Accept is disabled with a
# VISIBLE stated reason (a silently hidden control is its own defect class here — dead-control and
# ux-dead-end are both live findings), Counter stays offered as the action that actually works, and
# AGREED_RATE_REQUIRED gets actionable copy in place of the refresh advice.
#
# FALSIFICATION, measured on both: reverting creator-deals.tsx turned its two behaviour-asserting
# tests red (toBeDisabled() against a live button; the AGREED_RATE_REQUIRED toast assertion timing
# out because the old code only ever sent the generic failure) while the priced-accept regression
# stayed green. Reverse-applying the creator-chat.tsx diff turned its two new tests red
# ("Received element is not disabled"), all others green.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }

# Both surfaces, checked by source as well as by test. The tests below prove behaviour, but a
# future edit could remove the guard from ONE page and leave the other's tests green — which is
# exactly how this finding survived its first fix.
for f in src/pages/creator-deals.tsx src/pages/creator-chat.tsx; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
  if ! grep -q "hasAgreedRate" "$f"; then
    echo "· $f no longer derives hasAgreedRate"
    echo "VERDICT: broken — a rate-less invite is being offered an Accept that always 409s (F-0670)"
    exit 1
  fi
  if ! grep -q "AGREED_RATE_REQUIRED" "$f"; then
    echo "· $f no longer handles the AGREED_RATE_REQUIRED response"
    echo "VERDICT: broken — the 409 falls through to generic copy; on creator-chat.tsx the generic"
    echo "         branch advises a REFRESH, which cannot create a rate (F-0670)"
    exit 1
  fi
done
echo "· both surfaces derive hasAgreedRate and handle AGREED_RATE_REQUIRED"

# Untracked tests have been a blocker on four consecutive waves (F-0324 pattern): a gate whose
# subject exists only on disk passes locally and is absent on a fresh clone.
for t in src/pages/creator-deals.test.tsx src/pages/creator-chat-bare-invite.test.tsx; do
  [ -f "$t" ] || { echo "· $t missing — unavailable"; exit 2; }
  git ls-files --error-unmatch "$t" >/dev/null 2>&1 || {
    echo "· $t is not git-tracked — this gate would be absent on a fresh clone"
    echo "VERDICT: broken — F-0324 pattern; git add it"
    exit 1; }
done
echo "· both regression tests are git-tracked"

BUDGET="${PROOF_F0670_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
echo "· vitest: creator-deals + creator-chat bare-invite"
out=$($TO node_modules/.bin/vitest run src/pages/creator-deals.test.tsx src/pages/creator-chat-bare-invite.test.tsx 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if printf '%s' "$out" | grep -q "No test files found"; then echo "  collected nothing — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken — a rate-less invite's Accept behaviour regressed on at least one surface"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true

echo "VERDICT: aligned (proved) — on BOTH the deals list and the chat bare-invite card, a rate-less"
echo "         invite no longer offers a bare Accept that always 409s: it is disabled with a stated"
echo "         reason, Counter is offered as the action that works, and an AGREED_RATE_REQUIRED"
echo "         response yields actionable copy rather than advice to refresh."
echo "NOT CHECKED: whether the BACKEND ruling itself (F-0643, agreedRate required before"
echo "             TERMS_AGREED) is the right product call — that was a CEO decision, not a"
echo "             defect, and this gate only proves the UI stopped contradicting it. Any OTHER"
echo "             surface that may offer an accept action is not enumerated here. Per the standing"
echo "             caveat, no test in this repo exercises a real backend."
exit 0
