#!/usr/bin/env bash
# gates/meta-authpath-explicit.sh — F-0700.
#
# WHAT THIS PROVES
# Every caller of `metaOAuth.authorize` passes an explicit MetaAuthPath.
#
# WHY
# `api.metaOAuth.authorize()` with no argument defaults, server-side, to FACEBOOK_LOGIN —
# the Instagram configuration that requires a professional account already linked to a
# Facebook Page the creator can administer. A creator without such a Page dead-ends inside
# Meta's own UI with nothing explaining why.
#
# T-IGLOGIN-0820 fixed this for the Settings card by asking the creator first. It did not
# fix creator onboarding Step 1 or the Co-pilot nudge, both of which kept calling
# `authorize()` bare — so the one screen every creator passes through went on sending all
# of them down the Page-required path for weeks. tsc cannot see this (the parameter is
# optional by design, for backwards compatibility) and no test covered those two callers.
# This gate is the thing that would have caught it.
#
# Exit 0 = every call site is explicit. Exit 1 = at least one bare call. Exit 2 = cannot run.
#
# WHAT THIS GATE CANNOT SEE (Meera, fresh-context review)
# It is textual. It catches the literal `authorize()` regression and a UI surface that starts
# the redirect without offering the choice. It CANNOT see `authorize(x)` where `x` is a variable
# that happens to hold `undefined` — and that was the shape of the second real instance, the
# callback page's generic "Try Again" calling `handleRetry()` with an optional parameter. It
# also only checks that the dialog's NAME appears somewhere in a caller's file, not that the
# dialog's answer is what reaches that particular call.
#
# That hole is closed by the type system, not by this script: `metaOAuth.authorize` now takes a
# REQUIRED `MetaAuthPath` (src/lib/api.ts), so `npx tsc --noEmit` fails with TS2554 on a bare
# call and with TS2345 on a possibly-undefined argument. Verified by reverting one call site to
# `authorize()`: tsc exited 2 with
#   src/components/creator/copilot/IGConnectPrompt.tsx(38,56): error TS2554: Expected 1
#   arguments, but got 0.
# THE TYPE CHECK IS THE PRIMARY GATE. This script is the secondary one, and it covers the part
# tsc cannot express: that a UI surface asks the creator instead of choosing for them.

set -uo pipefail

cd "$(dirname "$0")/../.." || { echo "cannot reach project root"; exit 2; }

[ -d src ] || { echo "no src/ directory — wrong working directory"; exit 2; }
command -v grep >/dev/null 2>&1 || { echo "grep unavailable"; exit 2; }

# A bare call is the literal `authorize()`. The declaration in src/lib/api.ts reads
# `authorize: (authPath?: MetaAuthPath) =>` and never matches this pattern.
#
# Comment lines are excluded: this very defect is described in prose in several file headers
# and in the tests that pin it, and counting those as call sites would make the gate
# permanently red for documenting itself — a gate nobody can ever get green is a gate that
# gets deleted. Matching is therefore restricted to lines that are not `//`- or `*`-prefixed.
HITS=$(grep -rn "metaOAuth\.authorize()" src --include='*.ts' --include='*.tsx' 2>/dev/null \
       | grep -vE '^[^:]+:[0-9]+: *(//|\*|/\*)' || true)

if [ -n "$HITS" ]; then
  echo "BROKEN — metaOAuth.authorize() called with no authPath; these default to FACEBOOK_LOGIN"
  echo "$HITS"
  echo
  echo "Pass an explicit 'FACEBOOK_LOGIN' or 'INSTAGRAM_LOGIN'. If the caller is a UI surface,"
  echo "ask the creator first with <MetaConnectPathDialog> rather than choosing for them."
  exit 1
fi

# Second half: any UI surface that starts the OAuth redirect must render the path question.
# A surface that hardcodes a path without asking is the same dead-end with extra steps.
CALLERS=$(grep -rln "metaOAuth\.authorize(" src --include='*.tsx' 2>/dev/null \
          | grep -v '\.test\.tsx$' || true)

# A surface may opt out ONLY by marking the call `AUTHPATH-DELIBERATE` with a written reason.
# BusinessAccountRequired is the real case: it renders only for accountType 'personal' and its
# own instructions walk the creator through linking a Facebook Page, so FACEBOOK_LOGIN is
# correct there by construction and asking would be incoherent. The marker exists so that a
# deliberate hardcode is greppable and reviewed, rather than indistinguishable from the silent
# default that caused F-0700 in the first place.
MISSING=""
for f in $CALLERS; do
  if ! grep -q "MetaConnectPathDialog" "$f" && ! grep -q "AUTHPATH-DELIBERATE" "$f"; then
    MISSING="$MISSING $f"
  fi
done

if [ -n "$MISSING" ]; then
  echo "BROKEN — these surfaces start Meta OAuth without asking which configuration applies:"
  for f in $MISSING; do echo "  $f"; done
  echo
  echo "Render <MetaConnectPathDialog> and start the redirect from its onChoose."
  exit 1
fi

echo "PROVED — every metaOAuth.authorize call passes an explicit authPath,"
echo "         and every UI surface that starts the redirect asks the creator first."
exit 0
