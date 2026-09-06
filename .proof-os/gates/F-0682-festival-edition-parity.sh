#!/usr/bin/env bash
# gates/F-0682-festival-edition-parity.sh
# origin: Kabir H-1 (unbounded row growth on POST /festival/coupon-copied).
#
# THE FIX being guarded: edition is now validated against com.influora.domain.FestivalEditions.KNOWN
# before a festival_coupon_copies row is written, closing an unauthenticated 36^64-keyspace growth
# hole. That fix introduces a NEW failure mode, which is what this gate exists for.
#
# WHY A GATE AND NOT A JUNIT TEST: the two lists that must agree live on opposite sides of the repo
# — FestivalEditions.KNOWN (Java, influora-api/) and FESTIVAL_EDITIONS[].editionKey (TypeScript,
# src/content/). Nothing the compiler or either test suite can see links them, and a JUnit test
# cannot reach outside its Maven module (no test in influora-api/src/test does, checked).
#
# WHY IT MATTERS: the drift is silent and fails CLOSED in the worst direction. Launch Edition 02 on
# the frontend without adding it here and the page renders perfectly, every coupon-copy tap returns
# its usual 204, and every single one is dropped — the sponsor's Day-20 copy report reads zero, no
# error is logged on the client, and the first person to notice is a paying sponsor asking why the
# number they were sold does not exist.
#
# The opposite direction (known to Java, absent from the frontend) is harmless — an edition nobody
# can reach — so this gate deliberately only fails on FRONTEND-ONLY editions, and merely reports the
# other direction. A gate that fails on both would block the perfectly normal "add it to the backend
# first" ordering, and a gate people learn to work around is worse than no gate.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

JAVA_SRC=influora-api/src/main/java/com/influora/domain/FestivalEditions.java
TS_SRC=src/content/festival-editions.ts

[ -f "$JAVA_SRC" ] || { echo "· $JAVA_SRC missing — unavailable"; exit 2; }
[ -f "$TS_SRC" ]   || { echo "· $TS_SRC missing — unavailable"; exit 2; }

# Java side: the KNOWN set is Set.of(<CONSTANT>, ...) referencing constants declared in the same
# file, so resolve through the declarations rather than trying to read literals out of Set.of.
# Extracted as: every `public static final String NAME = "VALUE";` whose NAME appears inside Set.of.
known_line=$(grep -n 'KNOWN *= *Set\.of(' "$JAVA_SRC" | head -1 | cut -d: -f1)
[ -n "$known_line" ] || { echo "· could not locate 'KNOWN = Set.of(' in $JAVA_SRC — unavailable"; exit 2; }

# The Set.of(...) call may wrap across lines; take from its line until the closing ');'.
set_body=$(sed -n "${known_line},/);/p" "$JAVA_SRC")

JAVA_EDITIONS=$(
  while IFS= read -r name; do
    grep -oE "String[[:space:]]+${name}[[:space:]]*=[[:space:]]*\"[A-Z0-9_]+\"" "$JAVA_SRC" \
      | grep -oE '"[A-Z0-9_]+"' | tr -d '"'
  done < <(printf '%s' "$set_body" | grep -oE '[A-Z][A-Z0-9_]{2,}' | grep -v '^KNOWN$' | sort -u) | sort -u
)

# TS side: every editionKey literal in FESTIVAL_EDITIONS.
TS_EDITIONS=$(grep -oE "editionKey:[[:space:]]*'[A-Z0-9_]+'" "$TS_SRC" | grep -oE "'[A-Z0-9_]+'" | tr -d "'" | sort -u)

if [ -z "$JAVA_EDITIONS" ]; then echo "· extracted 0 editions from $JAVA_SRC — extraction broke, unavailable"; exit 2; fi
if [ -z "$TS_EDITIONS" ];   then echo "· extracted 0 editions from $TS_SRC — extraction broke, unavailable"; exit 2; fi

echo "· backend  FestivalEditions.KNOWN : $(printf '%s' "$JAVA_EDITIONS" | tr '\n' ' ')"
echo "· frontend FESTIVAL_EDITIONS keys : $(printf '%s' "$TS_EDITIONS" | tr '\n' ' ')"

FRONTEND_ONLY=$(comm -13 <(printf '%s\n' "$JAVA_EDITIONS") <(printf '%s\n' "$TS_EDITIONS"))
BACKEND_ONLY=$(comm -23 <(printf '%s\n' "$JAVA_EDITIONS") <(printf '%s\n' "$TS_EDITIONS"))

if [ -n "$BACKEND_ONLY" ]; then
  echo "· note (not a failure): known to the backend, no page on the frontend: $(printf '%s' "$BACKEND_ONLY" | tr '\n' ' ')"
fi

if [ -n "$FRONTEND_ONLY" ]; then
  echo "  BROKEN: these editions render a public page but are NOT in FestivalEditions.KNOWN:"
  printf '    %s\n' $FRONTEND_ONLY
  echo "  Every coupon-copy tap on those pages is silently dropped (204, no row) and the"
  echo "  sponsor's copy report will read zero. Add them to $JAVA_SRC."
  echo "VERDICT: broken"
  exit 1
fi

echo "VERDICT: proved — every frontend edition is recognized by the backend"
exit 0
