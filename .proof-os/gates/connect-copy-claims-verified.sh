#!/usr/bin/env bash
# gates/connect-copy-claims-verified.sh — F-0699.
#
# WHAT THIS PROVES
# The creator-facing Instagram connect/disconnect copy makes no claim the code cannot back.
#
# WHY
# This one sentence has now been wrong three times:
#   1. "deliverable verification stops"            — that path cannot reach a creator token at all
#   2. "brands will no longer see your reach"      — disconnect never touches platform_stats
#   3. "frozen at today's numbers"                 — implies the frozen figures are current as of
#                                                    the disconnect; MetaTokenStorage
#                                                    .revokeCreatorToken (MetaTokenStorage.java:394)
#                                                    marks the token row revoked and writes an
#                                                    audit entry, and touches platform_stats not
#                                                    at all — so what survives is whatever the LAST
#                                                    SYNC wrote, potentially months stale.
# Rounds 1 and 2 were caught by a human reviewer; round 3 by a fresh-context claim audit. Nothing
# automated saw any of them, and the test that existed pinned the exact wrong string, which locked
# the overstatement in rather than catching it.
#
# The whole point of the connect screens is that a creator can trust them. A trust screen that
# overstates is worse than no trust screen, because the correction arrives after they have already
# granted access.
#
# Exit 0 = the copy assertions hold. Exit 1 = a claim regressed. Exit 2 = cannot run.

set -uo pipefail

cd "$(dirname "$0")/../.." || { echo "cannot reach project root"; exit 2; }
[ -f package.json ] || { echo "no package.json — wrong working directory"; exit 2; }

SPECS=(
  "src/components/creator/connected-accounts.test.tsx"
  "src/pages/creator-onboarding.trust.test.tsx"
)

for s in "${SPECS[@]}"; do
  [ -f "$s" ] || { echo "missing spec: $s"; exit 2; }
done

# Each spec is run as its OWN invocation: a multi-file vitest invocation returns misleading
# exit codes on Windows here, which would make this gate green on a real failure.
RC=0
for s in "${SPECS[@]}"; do
  if npx vitest run "$s" --reporter=basic >/dev/null 2>&1; then
    echo "  ok   $s"
  else
    echo "  FAIL $s"
    RC=1
  fi
done

if [ "$RC" -ne 0 ]; then
  echo "BROKEN — a creator-facing connect/disconnect claim no longer matches the code."
  echo "Re-run the failing spec above to see which sentence regressed."
  exit 1
fi

# Round 3 specifically: the freshness overstatement must not come back in any form.
#
# Comment lines are excluded. The history of this sentence is documented in a comment directly
# above the corrected copy — quoting the wrong wording in order to explain why it was wrong is
# exactly what should be encouraged, and a gate that goes red for that teaches people to delete
# the explanation. Only rendered text counts, so `//`- and `*`-prefixed lines are dropped.
OFFENDERS=$(grep -rn "today's numbers\|today’s numbers" src --include='*.tsx' --include='*.ts' \
            | grep -v '\.test\.' \
            | grep -vE '^[^:]+:[0-9]+: *(//|\*|/\*)' || true)

if [ -n "$OFFENDERS" ]; then
  echo "BROKEN — 'today's numbers' is back in creator-facing copy:"
  echo "$OFFENDERS"
  echo "The revoke path never touches platform_stats; say 'last synced numbers'."
  exit 1
fi

echo "PROVED — connect/disconnect copy claims hold, and the freshness overstatement has not returned."
exit 0
