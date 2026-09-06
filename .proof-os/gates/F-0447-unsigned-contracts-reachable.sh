#!/usr/bin/env bash
# gates/F-0447-unsigned-contracts-reachable.sh — instance gate, closes F-0447.
#
# origin: F-0447 (unreachable-endpoint) — "GET /contracts/unsigned has no frontend caller, and a
# stale comment in the client claims the endpoint does not exist, so a creator has no
# pending-signature list."
#
# WHY AN INSTANCE GATE, AND NOT THE CLASS GATE. F-0447's defect has been fixed for some time: the
# creator dashboard's "Contracts awaiting your signature" section calls api.contracts.listUnsigned,
# and .proof-os/gates/unreachable-endpoint.py no longer lists /contracts/unsigned among its
# findings. But that class gate exits 1, because TWENTY-SIX OTHER endpoints still have no caller
# (Meera sessions, workspace members, admin email, wallet balance, support tickets...). Promoting
# F-0447 against a red gate would be exactly the false close this project keeps rejecting — the
# gate's verdict would say "broken" while the ledger said "closed".
#
# The honest resolution is a gate scoped to THIS finding's endpoint. It reuses the canonical
# scanner rather than reimplementing reachability: the class gate stays the source of truth for the
# class, and this one only asks whether F-0447's own subject is still reachable. F-0443 and F-0446
# remain genuinely open and are NOT covered here.
#
# I record this because I twice told the CEO F-0447 was "a one-command close". That was wrong, and
# the reason it was wrong is worth keeping: an instance being fixed does not make its class gate
# pass, and a gate you have not run is not evidence.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

CLASS_GATE=.proof-os/gates/unreachable-endpoint.py
[ -f "$CLASS_GATE" ] || { echo "· $CLASS_GATE missing — unavailable"; exit 2; }
command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }

# The canonical scanner, read for THIS endpoint only. Its own exit code is deliberately ignored:
# it is expected to be 1 while other endpoints remain unreachable, and that is not F-0447.
echo "· running the canonical reachability scanner, reading its findings for /contracts/unsigned"
out=$(python "$CLASS_GATE" 2>&1) || true
if [ -z "$out" ]; then
  echo "· the scanner produced no output at all — unavailable, not a pass"
  exit 2
fi
# Sanity: the scanner must actually be reporting findings, or a clean grep below proves nothing.
if ! printf '%s' "$out" | grep -qE "findings:|no frontend caller|VERDICT"; then
  echo "· the scanner output has no recognisable findings section — its format changed, so a"
  echo "  negative grep below would be a false pass"
  printf '%s\n' "$out" | tail -15
  exit 2
fi

if printf '%s' "$out" | grep -q "contracts/unsigned"; then
  printf '%s\n' "$out" | grep -n "contracts/unsigned"
  echo "VERDICT: broken — GET /contracts/unsigned has no frontend caller again; a creator has no"
  echo "         pending-signature list (F-0447)"
  exit 1
fi
echo "  /contracts/unsigned is NOT among the unreachable endpoints"

# Positive control: prove the scanner still detects SOMETHING, so "absent from the findings" means
# reachable rather than "the scanner silently stopped finding anything".
n=$(printf '%s' "$out" | grep -c "no frontend caller" || true)
echo "· scanner still reports $n unreachable endpoint(s) overall — so absence here is a real signal"
if [ "$n" -eq 0 ]; then
  echo "· the scanner reported ZERO unreachable endpoints, which contradicts the known open"
  echo "  findings F-0443 and F-0446 — treating this as a broken instrument, not a pass"
  exit 2
fi

# The caller must genuinely exist, checked independently of the scanner.
if ! grep -rq "listUnsigned" src/lib/api.ts; then
  echo "· src/lib/api.ts no longer exposes listUnsigned"
  echo "VERDICT: broken — the client method F-0447 was closed on is gone"
  exit 1
fi
if ! grep -rq "listUnsigned" src/pages/creator-dashboard.tsx; then
  echo "· creator-dashboard.tsx no longer calls listUnsigned"
  echo "VERDICT: broken — the endpoint has a client method but no UI caller again (F-0447)"
  exit 1
fi
echo "· api.contracts.listUnsigned exists and the creator dashboard calls it"

echo "VERDICT: aligned (proved) — GET /contracts/unsigned is reachable from the product: the client"
echo "         method exists, the creator dashboard calls it, and the canonical scanner (which is"
echo "         still finding other unreachable endpoints, so it has not gone blind) does not list"
echo "         it."
echo "NOT CHECKED: the OTHER 20-plus unreachable endpoints, including the still-open F-0443"
echo "             (workspace member management) and F-0446 (creator search facets) — this gate is"
echo "             deliberately scoped to F-0447's own endpoint and says nothing about its class."
echo "             Whether the creator-facing list is CORRECT is F-0623/F-0625/F-0629 territory,"
echo "             proved by their own gates, not this one."
exit 0
