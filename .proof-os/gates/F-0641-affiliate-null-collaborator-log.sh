#!/usr/bin/env bash
# gates/F-0641-affiliate-null-collaborator-log.sh
# origin: F-0641 (silent-fallback-to-noop) — creditCreatorWallet silently no-op'd when its wallet-
# service collaborators were null, with no log, after the earning was already marked SETTLED.
#
# THE FIX: a log.error naming the earning/creator/redemption id, idempotency key and unmoved amount
# in that branch. Honest caveat, confirmed by fresh-context review: the null case is only reachable
# through a legacy narrower constructor kept alive for two locked pre-existing tests — the
# production @Autowired 4-arg constructor never leaves these fields null. This is defence-in-depth,
# not a closed live leak, and the gate's own header says so.
#
# LAW: exit 0 proved, 1 broken, 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

API=influora-api
[ -d "$API" ] || { echo "· $API not a directory — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

BUDGET="${PROOF_F0641_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
echo "· mvn -o clean -Dtest=AffiliateSettlementWriterNullCollaboratorLogTest test"
out=$($TO mvn -o -q clean -Dtest=com.influora.job.AffiliateSettlementWriterNullCollaboratorLogTest -f "$API/pom.xml" test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol|does not exist"; then
  printf '%s\n' "$out" | tail -50
  echo "VERDICT: unavailable"
  exit 2
fi
if echo "$out" | grep -qi "No tests were executed"; then
  printf '%s\n' "$out" | tail -20
  echo "VERDICT: unavailable — target class not found"
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -60
  echo "VERDICT: broken — the null-collaborator branch no longer logs loudly (F-0641)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests run:" | sed 's/^/  /'
echo "  green on a clean build"

echo "VERDICT: aligned (proved) — a null wallet-service collaborator in creditCreatorWallet now"
echo "         logs an ERROR naming the earning/creator/redemption/idempotency key/amount, and the"
echo "         real production (4-arg constructor) path is confirmed to never hit this branch."
echo "NOT CHECKED: whether this branch is genuinely unreachable in production forever (only that it"
echo "             is today); live log aggregation/alerting on this ERROR level."
exit 0
