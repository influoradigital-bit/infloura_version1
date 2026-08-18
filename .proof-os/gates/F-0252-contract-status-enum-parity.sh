#!/usr/bin/env bash
# F-0252-contract-status-enum-parity.sh — gate for F-0252 (fe-be-enum-divergence).
#
# The frontend `ContractStatus` union (src/lib/types.ts) and the backend `ContractStatus` enum
# (influora-api/.../domain/enums/ContractStatus.java) must carry EXACTLY the same member set.
# Divergence is a real bug in both directions:
#   - FE invents a member the backend can never produce  -> dead branches gated on it, and no
#     branch ever fires for whatever the backend actually sends when it means that state.
#   - FE omits a member the backend CAN produce           -> a real value on the wire does not
#     typecheck / falls through every switch's `default` silently.
#
# This is a genuine PARITY check, not a grep for one literal: it derives both member sets from
# the real source files and diffs them, so it keeps working when either side changes later —
# adding a backend status without updating the FE (or vice versa) trips this gate again without
# anyone having to hand-edit the check itself.
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

JAVA_FILE=influora-api/src/main/java/com/influora/domain/enums/ContractStatus.java
TS_FILE=src/lib/types.ts

[ -f "$JAVA_FILE" ] || { echo "· $JAVA_FILE missing — unavailable"; exit 2; }
JAVA_FILE_CODE=$(code_view "$JAVA_FILE") || { echo "$(code_why) - unavailable"; exit 2; }
[ -f "$TS_FILE" ] || { echo "· $TS_FILE missing — unavailable"; exit 2; }
TS_FILE_CODE=$(code_view "$TS_FILE") || { echo "$(code_why) - unavailable"; exit 2; }

echo "· deriving backend member set from $JAVA_FILE"
# Grab everything between the `enum ContractStatus {` line and its closing brace, strip
# comments/whitespace, split on commas, uppercase-identifier lines only.
java_block=$(awk '/enum[ \t]+ContractStatus[ \t]*\{/{flag=1; sub(/^.*enum[ \t]+ContractStatus[ \t]*\{/, ""); if ($0 ~ /\}/) { sub(/\}.*$/, ""); print; exit } print; next} flag{ if ($0 ~ /\}/) { sub(/\}.*$/, ""); print; exit } print }' "$JAVA_FILE_CODE")
if [ -z "$java_block" ]; then
  echo "VERDICT: broken — could not locate an 'enum ContractStatus { ... }' block in $JAVA_FILE"
  echo "         (F-0252) — treating an unparsable source as a parity failure rather than a"
  echo "         silent pass"
  exit 1
fi
java_members=$(printf '%s' "$java_block" \
  | tr ',' '\n' \
  | sed -E 's/\/\/.*$//; s/[[:space:]]//g' \
  | grep -E '^[A-Z][A-Z0-9_]*$' \
  | sort -u)
if [ -z "$java_members" ]; then
  echo "VERDICT: broken — parsed an enum block but extracted zero member names from"
  echo "         $JAVA_FILE (F-0252) — parser likely broken, not a real empty enum"
  exit 1
fi
echo "  backend members: $(printf '%s' "$java_members" | tr '\n' ' ')"

echo "· deriving frontend member set from $TS_FILE"
# Grab the `export type ContractStatus = ...;` declaration (may be single- or multi-line),
# then pull every single-quoted literal out of it.
ts_block=$(awk '/^export type ContractStatus[ \t]*=/{flag=1} flag{print; if ($0 ~ /;/) exit}' "$TS_FILE_CODE")
if [ -z "$ts_block" ]; then
  echo "VERDICT: broken — could not locate 'export type ContractStatus = ...;' in $TS_FILE"
  echo "         (F-0252)"
  exit 1
fi
ts_members=$(printf '%s' "$ts_block" | grep -oE "'[A-Z][A-Z0-9_]*'" | tr -d "'" | sort -u)
if [ -z "$ts_members" ]; then
  echo "VERDICT: broken — parsed a ContractStatus type declaration but extracted zero string"
  echo "         literal members from $TS_FILE (F-0252) — parser likely broken"
  exit 1
fi
echo "  frontend members: $(printf '%s' "$ts_members" | tr '\n' ' ')"

echo "· diffing the two member sets"
invented=$(comm -13 <(printf '%s\n' "$java_members") <(printf '%s\n' "$ts_members"))
omitted=$(comm -23 <(printf '%s\n' "$java_members") <(printf '%s\n' "$ts_members"))

broken=0
if [ -n "$invented" ]; then
  echo "  FE-only (invented — no backend state can ever produce these):"
  printf '%s\n' "$invented" | sed 's/^/    - /'
  broken=1
fi
if [ -n "$omitted" ]; then
  echo "  BE-only (omitted from FE — a real backend value that will not typecheck):"
  printf '%s\n' "$omitted" | sed 's/^/    - /'
  broken=1
fi

if [ "$broken" -eq 1 ]; then
  echo "VERDICT: broken — src/lib/types.ts ContractStatus and influora-api ContractStatus.java"
  echo "         member sets diverge (F-0252)"
  echo "NOT CHECKED: whether any TypeScript switch/branch typed on ContractStatus still compiles"
  echo "             (that is tsc's job, run separately, not this gate's); whether every call"
  echo "             site that consumed an invented member was updated to something meaningful"
  echo "             instead of just type-erased; runtime behavior against a live backend"
  exit 1
fi

echo "  clean — identical member sets: $(printf '%s' "$ts_members" | tr '\n' ' ')"
echo "VERDICT: aligned (proved) — src/lib/types.ts ContractStatus and influora-api"
echo "         ContractStatus.java carry exactly the same member set"
echo "NOT CHECKED: whether any TypeScript switch/branch typed on ContractStatus still compiles"
echo "             (that is tsc's job, run separately, not this gate's); whether every call site"
echo "             that consumed an invented member was updated to something meaningful instead"
echo "             of just type-erased; runtime behavior against a live backend"
exit 0
