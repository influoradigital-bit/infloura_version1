#!/usr/bin/env bash
# gates/F-0272-demo-sign-flow-alive.sh
# origin failure: F-0272 (demo-sign-flow-dead) — api.contracts.get resolved `null` in mock mode
# (api.ts), so DealContractTab's F-0237 fix (which correctly gates the Sign control on a real
# fetched contract record) left EVERY demo/offline walkthrough dead: contractRecord was always
# null, the panel rendered "Contract terms are not available", and Sign could never be reached.
#
# The fix must give mock mode a real fixture WITHOUT reopening the live-mode fabrication F-0237/
# F-0238 closed — the fixture may only ever be reachable through the same isLive()/isApiLive()
# guard every other mock in api.ts already uses. This gate is written to catch BOTH failure
# directions: the original bug (mock resolves null) and the dangerous wrong fix (mock leaks into
# live mode because the isLive() guard around contracts.get was removed, inverted, or bypassed).
#
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

API=src/lib/api.ts
API_CODE=$(code_view "$API") || { echo "$(code_why) - unavailable"; exit 2; }
COMP=src/components/brand/deal-room/deal-contract-tab.tsx
COMP_CODE=$(code_view "$COMP") || { echo "$(code_why) - unavailable"; exit 2; }
for f in "$API" "$COMP"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
done

fail() {
  echo "VERDICT: broken — $1 (F-0272)"
  echo "NOT CHECKED: whether the rendered fixture matches any real backend contract, live UI"
  echo "             rendering in a browser, or the sign-and-send network round trip"
  exit 1
}

# ---------------------------------------------------------------------------
# 1 · component still fetches a real record and gates Sign on it (F-0237 must stay intact —
#     a demo fixture is worthless if the panel that consumes it stops reading it).
# ---------------------------------------------------------------------------
echo "· component: still fetches contractRecord and renders/gates on it"
grep -q "contractRecord" "$COMP_CODE" || fail "DealContractTab no longer reads contractRecord"
grep -q "contractRecord.milestones" "$COMP_CODE" || fail "terms are no longer rendered from the real contract's milestones"
grep -qE "disabled=\{[^}]*!contractRecord" "$COMP_CODE" || fail "Sign control is not gated on the real contract record"
echo "  clean — contractRecord is fetched, rendered and gates Sign"

# ---------------------------------------------------------------------------
# 2 · isolate contracts.get() in api.ts and inspect its body directly, not just the file at
#     large — a healthy mockOr(fixture) call belonging to a DIFFERENT endpoint (list/generate/
#     sign, OR another object entirely — `get: (role: Role, id: string) =>` is not a unique
#     signature file-wide) must not be able to satisfy this gate for contracts.get().
# ---------------------------------------------------------------------------
echo "· api.ts: isolating contracts.get() body"
CSTART=$(grep -n "^export const contracts = {" "$API_CODE" | head -1 | cut -d: -f1)
[ -n "$CSTART" ] || fail "could not find 'export const contracts = {' in api.ts"
GETLINE=$(awk -v start="$CSTART" 'NR>start && /^  get: \(role: Role, id: string\) =>/{print NR; exit}' "$API_CODE")
[ -n "$GETLINE" ] || fail "could not find contracts.get()'s signature after the contracts object starts"
# Each property in this object literal is one statement ending in a trailing comma, separated
# from the next by a blank line — so the first blank line after the start line is the real end
# of contracts.get(), regardless of whether its mock branch is a bare `null` or a multi-line
# object literal (a fixed-indent closing-brace pattern would wrongly run past a bare `null)`
# case and swallow the next property's block instead).
GETBLOCK=$(awk -v startln="$GETLINE" '
  NR==startln { flag=1 }
  flag {
    if (NR>startln && $0 ~ /^[[:space:]]*$/) exit
    print
  }
' "$API")
LINES=$(printf '%s\n' "$GETBLOCK" | grep -c . )
if [ -z "$GETBLOCK" ] || [ "$LINES" -lt 3 ] || [ "$LINES" -gt 20 ]; then
  fail "could not cleanly isolate contracts.get()'s body ($LINES lines) — the isLive()/mockOr split cannot be verified"
fi
echo "  isolated $LINES lines"

echo "· api.ts: contracts.get() still branches on isLive(), live branch calls the real endpoint"
printf '%s\n' "$GETBLOCK" | grep -q "isLive()" \
  || fail "contracts.get() no longer branches on isLive() — nothing stops the mock fixture from being served in live mode"
printf '%s\n' "$GETBLOCK" | grep -q "http.request<ContractApiRecord>('GET', \`/contracts/\${id}\`" \
  || fail "contracts.get()'s live branch no longer calls the real GET /contracts/:id endpoint"

# Ordering: isLive() must appear before the http.request call, which must appear before mockOr —
# i.e. http.request is the TRUE branch of the isLive() ternary and mockOr is the FALSE branch.
# A swapped/inverted ternary (the classic "leak the mock into live" wrong fix) reverses this.
isLive_ln=$(printf '%s\n' "$GETBLOCK" | grep -n "isLive()" | head -1 | cut -d: -f1)
http_ln=$(printf '%s\n' "$GETBLOCK" | grep -n "http.request<ContractApiRecord>('GET'" | head -1 | cut -d: -f1)
mockOr_ln=$(printf '%s\n' "$GETBLOCK" | grep -n "mockOr<ContractApiRecord" | head -1 | cut -d: -f1)
[ -n "$isLive_ln" ] && [ -n "$http_ln" ] && [ -n "$mockOr_ln" ] \
  || fail "could not locate isLive()/http.request/mockOr as three distinct lines in contracts.get()"
if ! [ "$isLive_ln" -lt "$http_ln" ] || ! [ "$http_ln" -lt "$mockOr_ln" ]; then
  fail "contracts.get()'s isLive() ? http.request : mockOr ordering is broken — the live branch is not guaranteed to hit the real endpoint"
fi
echo "  clean — isLive() gates http.request (live) ahead of mockOr (mock), in that order"

# ---------------------------------------------------------------------------
# 3 · the mock branch must resolve an actual fixture, not the original bug's null.
# ---------------------------------------------------------------------------
echo "· api.ts: mock branch resolves a real fixture, not null"
if printf '%s\n' "$GETBLOCK" | grep -qE "mockOr<ContractApiRecord \| null>\(null\)"; then
  fail "contracts.get()'s mock branch still resolves null — the demo sign flow is dead again"
fi
printf '%s\n' "$GETBLOCK" | grep -q "milestones:" \
  || fail "contracts.get()'s mock fixture carries no milestones — nothing to sign over in the demo"
echo "  clean — mock branch resolves a fixture with milestones"

echo "NOT CHECKED (interim): none — all static legs passed"

# ---------------------------------------------------------------------------
# 4 · vitest — the behavioral proof. Runs only once every static leg above has passed, so a
#     scratch copy built for the negative-control runs (no node_modules) never needs to reach it.
# ---------------------------------------------------------------------------
TESTFILE=src/components/brand/deal-room/__tests__/demo-sign-flow-alive.test.tsx
[ -f "$TESTFILE" ] || { echo "· $TESTFILE missing — unavailable"; exit 2; }
command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
# node_modules/.bin/vitest directly, never `npx --yes` — a bare `npx vitest` can silently
# resolve/install a registry copy instead of running THIS tree's pinned version, and can hang or
# fail on a machine with no registry access instead of failing fast and legibly.
[ -f node_modules/.bin/vitest ] || { echo "· vitest not installed — unavailable"; exit 2; }

BUDGET="${PROOF_F0272_VITEST_TIMEOUT:-180}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi

echo "· node_modules/.bin/vitest run $TESTFILE (budget ${BUDGET}s)"
out=$($TO node_modules/.bin/vitest run "$TESTFILE" 2>&1); rc=$?

if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  echo "NOT CHECKED: everything below this line — the suite did not finish, so no test result was observed"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — demo-sign-flow-alive.test.tsx does not pass (F-0272)"
  echo "NOT CHECKED: nothing further — the suite ran and failed"
  exit 1
fi
echo "  suite green — demo-sign-flow-alive.test.tsx"

echo "VERDICT: aligned (proved) — contracts.get() in mock mode resolves a real fixture with"
echo "         milestones, reachable only behind the same isLive() guard every other api.ts mock"
echo "         uses, the live branch still calls the real GET /contracts/:id endpoint ahead of"
echo "         that guard, DealContractTab still gates Sign on the fetched record, and"
echo "         demo-sign-flow-alive.test.tsx passes for both the demo and live directions"
echo "NOT CHECKED: whether the rendered fixture matches any real backend contract, live UI"
echo "             rendering in a browser, or the sign-and-send network round trip"
exit 0
