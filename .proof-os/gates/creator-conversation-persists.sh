#!/usr/bin/env bash
# Gate for F-0751 — a CREATOR Meera conversation must actually persist against a real schema.
# Also carries F-0754 (a NOT NULL ENUM keeps an implicit first-value default, so removing the
# DEFAULT does not make an omitting INSERT fail; ck_conv_workspace_tenant_is_not_starter does).
#
# Runs CreatorConversationPersistIntegrationTest, which performs the feature's OWN write.
# MeeraCreatorPhaseABootValidationTest already covered this slice and missed F-0751 because it
# asserts tables EXIST; only doing the write finds a FK that forbids the row.
#
# Exit 0 proved · 1 broken · 2 could not run (Docker absent — NOT a pass).
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$REPO/influora-api"
CLASS=CreatorConversationPersistIntegrationTest

command -v mvn >/dev/null 2>&1 || { echo "UNAVAILABLE — mvn not on PATH"; exit 2; }
if ! docker ps >/dev/null 2>&1; then
  echo "creator-conversation-persists: UNAVAILABLE — Docker is not running, Testcontainers cannot start"
  echo "A local build reports this class as Skipped inside a BUILD SUCCESS. A skipped gate is an"
  echo "UNRUN gate, never a passing one — which is why this exits 2 rather than 0."
  exit 2
fi

LOG="$(mktemp)"
# Never pipe mvn into grep: the pipeline reports grep's status, so a failed suite reads as exit 0.
( cd "$MODULE" && mvn -o -B test -Dtest="$CLASS" -DfailIfNoTests=true ) > "$LOG" 2>&1
RC=$?
LINE="$(grep -E '^\[(INFO|WARNING|ERROR)\] Tests run:.*Skipped:' "$LOG" | tail -1)"

if [ "$RC" -ne 0 ]; then
  echo "BROKEN  mvn exited $RC"
  grep -oE "foreign key constraint fails[^\"]{0,120}|ck_conv_[a-z_]*|Tests run:[^,]*, Failures: [0-9]*, Errors: [0-9]*" "$LOG" | head -6
  echo "  full log: $LOG"; exit 1
fi
# Skipped>0 means Testcontainers did not run it. That is the vacuous pass this refuses.
if ! echo "$LINE" | grep -q 'Skipped: 0'; then
  echo "BROKEN  the gate did not actually execute: ${LINE:-<no Tests run line>}"; exit 1
fi
if ! echo "$LINE" | grep -q 'Failures: 0, Errors: 0'; then
  echo "BROKEN  $LINE"; exit 1
fi
rm -f "$LOG"
echo "creator-conversation-persists: PROVED — $LINE"
echo "NOT CHECKED: whether the RUNNING production jar and schema carry this migration — this"
echo "  proves the source against a throwaway MySQL, not the box; and whether a CHECK violation"
echo "  surfaces to the API as a 409 rather than a 500 (F-0755, still open)"
exit 0
