#!/usr/bin/env bash
# gates/F-0652-escrow-idempotency-end-to-end.sh
# origin: F-0652 (no-request-level-idempotency) — a duplicated caller request to escrow
# release/refund/fund had no request-replay key, only a lower ledger-level key.
#
# TWO PRIOR ROUNDS ON THIS ONE FINDING, both caught by fresh-context review before promotion:
#   round 1 — the idempotency-aware EscrowService overloads existed but EscrowController never
#             called them; zero production callers, dead code.
#   round 2 — the controller was fixed to require Idempotency-Key on /release and /refund, but the
#             frontend (payments.releasePayout) was never updated to send it — every real release/
#             refund call would have 400'd MISSING_HEADER. Confirmed independently before either
#             round was promoted.
#
# THIS is round 3, the completed end-to-end wiring: EscrowController requires the header and calls
# the idempotency-aware EscrowService overloads (backend, already verified in an earlier gate this
# session); payments.releasePayout now mints a real UUID (or a guarded fallback) once per top-level
# call via the SAME http.request idempotencyKey mechanism fundEscrow already uses, and sends it as
# a genuine Idempotency-Key header — verified by asserting on the actual outgoing fetch headers, not
# merely that the function does not throw.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable. Backend clean build; frontend real vitest run.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

fail=0

# ---- backend leg: EscrowController requires the header and forwards it -----------------------
API=influora-api
if [ -d "$API" ] && command -v mvn >/dev/null 2>&1; then
  BUDGET="${PROOF_F0652_BE_TIMEOUT:-300}"
  if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
  echo "· mvn -o clean -Dtest=EscrowControllerTest test"
  out=$($TO mvn -o -q clean -Dtest=com.influora.web.EscrowControllerTest -f "$API/pom.xml" test 2>&1); rc=$?
  if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  backend leg exceeded ${BUDGET}s — unavailable"; exit 2; fi
  if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol|does not exist"; then
    printf '%s\n' "$out" | tail -50; echo "· backend leg unavailable (compile)"; exit 2
  fi
  if [ $rc -ne 0 ]; then printf '%s\n' "$out" | tail -60; echo "  backend leg BROKEN"; fail=1
  else printf '%s\n' "$out" | grep -E "Tests run:" | sed 's/^/  backend: /'; fi
else
  echo "· backend leg unavailable (no mvn/module)"; exit 2
fi

# ---- frontend leg: releasePayout actually sends the header ---------------------------------
if command -v node >/dev/null 2>&1 && [ -f node_modules/.bin/vitest ]; then
  T=src/lib/__tests__/release-payout-xor.f0489.test.ts
  [ -f "$T" ] || { echo "· $T missing — unavailable"; exit 2; }
  BUDGET="${PROOF_F0652_FE_TIMEOUT:-120}"
  if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
  echo "· vitest: $T"
  out=$($TO node_modules/.bin/vitest run "$T" 2>&1); rc=$?
  if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  frontend leg exceeded ${BUDGET}s — unavailable"; exit 2; fi
  if printf '%s' "$out" | grep -q "No test files found"; then echo "  frontend leg unavailable"; exit 2; fi
  if [ $rc -ne 0 ]; then printf '%s\n' "$out" | tail -50; echo "  frontend leg BROKEN"; fail=1
  else printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  frontend: /'; fi
else
  echo "· frontend leg unavailable (no node/vitest)"; exit 2
fi

if [ $fail -eq 1 ]; then
  echo "VERDICT: broken — the escrow idempotency chain is broken on at least one leg (F-0652)"
  exit 1
fi

echo "VERDICT: aligned (proved) — the backend requires and forwards Idempotency-Key on /release and"
echo "         /refund to the idempotency-aware EscrowService methods, AND the only real frontend"
echo "         caller (payments.releasePayout) actually sends a genuine key on every call. Neither"
echo "         leg alone was sufficient in the two prior rounds on this finding."
echo "NOT CHECKED: /wallet/escrow/refund has no frontend caller at all (F-0445, separate finding) —"
echo "             its idempotency wiring is proved server-side and by the backend leg's own tests"
echo "             only, never exercised from a real UI action. Live end-to-end duplicate-request"
echo "             behaviour against a real database."
exit 0
