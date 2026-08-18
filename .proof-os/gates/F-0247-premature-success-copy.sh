#!/usr/bin/env bash
# F-0247-premature-success-copy.sh — gate for F-0247 (premature-success-copy).
#
# FundEscrowButton's own header states the contract: success is confirmed by SERVER escrow
# status = FUNDED. The render did not honour it. The secured-money line
# "₹X secured. Released only on your approval." was gated on
# `serverAmount && status !== 'idle' && !helperText` — a negative condition over an
# incidental fact (whether some other phase happened to have helper text). Four phases had
# no helper text, so `awaiting_payment` (Razorpay modal open, nothing paid) and `verifying`
# (still polling) both reached it. The brand read that their money was secured while zero
# rupees had moved. That is not a copy nit on a money surface: it is the app asserting a
# completed escrow hold that does not exist.
#
# The fix inverts the guard to a positive one — `status === 'funded' && serverAmount` — so
# the assertion is structurally reachable from exactly one state, the server-confirmed one,
# and adding a new phase can never silently reopen it. That structural property is what
# this gate protects; it deliberately does NOT assert any particular wording, because copy
# is allowed to change and the invariant is not.
#
# Two legs:
#   1. The guard is still positive and still keyed on 'funded'. A revert to the
#      not-idle/no-helper-text form is the exact bug and is caught here.
#   2. The spec: every non-FUNDED phase renders without the secured assertion; FUNDED with it.
#   exit 0 = proved · 1 = broken · 2 = unavailable
set -u
ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "$ROOT" || { echo "· project root unreadable — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

BTN=src/components/feature/meera/FundEscrowButton.tsx
[ -f "$BTN" ] || { echo "· $BTN missing — unavailable"; exit 2; }
BTN_CODE=$(code_view "$BTN") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· the secured-money assertion is gated on the server-confirmed funded state"
if ! grep -qE "status === 'funded' && serverAmount" "$BTN_CODE"; then
  grep -nE "secured\. Released only on your approval" "$BTN_CODE"
  echo "VERDICT: broken — the secured-money copy is no longer gated on status === 'funded'"
  echo "         (F-0247); an in-flight or unpaid escrow can assert that funds are held"
  exit 1
fi
echo "  clean — positive guard on 'funded'"

echo "· the guard has not reverted to the incidental not-idle / no-helper-text form"
if grep -qE "status !== 'idle'.*!helperText|!helperText.*status !== 'idle'" "$BTN_CODE"; then
  grep -nE "status !== 'idle'" "$BTN_CODE"
  echo "VERDICT: broken — the old negative guard is back (F-0247); any phase that happens to"
  echo "         have no helper text reaches the secured-money copy again"
  exit 1
fi
echo "  clean — no negative guard on the money assertion"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }
SUITE=src/components/feature/meera/__tests__/FundEscrowButton.test.tsx
[ -f "$SUITE" ] || { echo "· $SUITE missing — unavailable"; exit 2; }

echo "· vitest run $SUITE"
out=$(node_modules/.bin/vitest run "$SUITE" 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -25
  echo "VERDICT: broken — an unfunded escrow phase claims the money is secured (F-0247)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests " | tail -1

echo "VERDICT: aligned (proved) — only a server-confirmed FUNDED escrow claims to hold money"
echo "NOT CHECKED: that the server's FUNDED status means the money is actually held — this is"
echo "             a client-side copy invariant and proves nothing about EscrowService; that"
echo "             the replacement copy for initiating/awaiting_payment/verifying is itself"
echo "             accurate, only that it does not assert a completed hold; and the four"
echo "             top-up phases (insufficient_funds, topping_up, awaiting_topup_payment,"
echo "             confirming_topup), which were incidentally safe before the fix because"
echo "             they carried helper text — they are safe structurally now, but no test"
echo "             drives a real top-up round trip"
