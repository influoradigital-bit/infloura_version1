#!/usr/bin/env bash
# Gate for F-0341 (ui-dead-control) and the first-run guidance surface built on top of it.
#
# WHAT BROKE
# ----------
# Brand onboarding step 3 renders the copy "Pick where to go first:" above two cards —
# *Create your first campaign* and *Discover creators*. Both shipped as plain <div>s: no
# onClick, no href, no navigate, and no handler passed at either call site. The one screen in
# the product that asks a brand-new user to choose a destination had nothing clickable on it
# except the button that ignores the choice. graphify reported NextActionCard at Degree 1 —
# "contains" and no outbound edge — which is what a dead control looks like in the graph.
#
# WHY A GATE AND NOT A CODE REVIEW
# --------------------------------
# A dead control is invisible in every artefact a reviewer looks at. It renders correctly, it
# screenshots correctly, it passes a type check, it passes a lint, and the page it sits on has
# no failing test. The only observer that can tell the difference is one that clicks it. This
# defect survived in the codebase through the entire brand-surface audit for exactly that
# reason, on the single highest-intent screen a new brand ever sees.
#
# The same reasoning covers the checklist this defect motivated: its whole value is that a step
# ticks only when the account state proves it. A step that quietly defaults to "done" on a failed
# request congratulates a user for work they have not done, and looks identical in a screenshot
# to one that is right.
#
# WHAT IS DELIBERATELY OUT OF SCOPE
# ---------------------------------
# Whether the ladder actually reduces first-run confusion. That is a claim about users, and no
# activation funnel is instrumented to test it (see
# wiki/decisions/first-run-dashboard-guidance-2026-08-19.md §6). This gate proves the controls
# are live and the derivation is honest — not that the design works.
#
# Exit 0 proved · 1 broken · 2 could not run.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

command -v npx >/dev/null 2>&1 || { echo "GATE UNAVAILABLE: npx not on PATH"; exit 2; }

SPECS=(
  # The dead control itself: the cards are enabled buttons AND carry a destination.
  "src/pages/brand-onboarding.next-action-live.test.tsx"
  # Undeterminable never renders as done, and never enters the denominator.
  "src/components/shared/__tests__/FirstRunChecklist.test.tsx"
  # Derivation from real state + the widget cannot white-screen the dashboard.
  "src/components/brand/dashboard/__tests__/BrandFirstRunChecklist.test.tsx"
  # Cumulative stage counting — the rule that stops the ladder going backwards.
  "src/lib/__tests__/brand-pipeline-progress.test.ts"
  # Pre-existing dashboard behaviour the checklist must not have displaced.
  "src/components/brand/dashboard/__tests__/dashboard-page-f0278.test.tsx"
  # L2 — one source of truth for the six steps, in-app routes registered and guarded, and no
  # link sending a signed-in user back out to the public marketing page.
  "src/content/__tests__/how-it-works-single-source.test.tsx"
  # L3 — the walkthrough URL is environment configuration; only an allowlisted origin may ever
  # reach an <iframe src>.
  "src/lib/__tests__/walkthrough-video.test.ts"
)

for spec in "${SPECS[@]}"; do
  [ -f "$spec" ] || { echo "GATE BROKEN: missing spec $spec"; exit 1; }
done

npx vitest run "${SPECS[@]}" >/dev/null 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  echo "GATE BROKEN: first-run guidance specs failed (vitest exit $rc)"
  echo "  reproduce: npx vitest run ${SPECS[*]}"
  exit 1
fi

# Belt-and-braces on the defect class itself: NextActionCard must not regress to a <div>, and
# its handler prop must stay non-optional, which is what stops a new call site shipping inert.
if ! grep -q 'onSelect: () => void;' src/pages/brand-onboarding.tsx; then
  echo "GATE BROKEN: NextActionCard's onSelect is no longer a required prop"
  exit 1
fi

# The public marketing pages carry the HowTo JSON-LD that AI answer engines lift close to
# verbatim. Extracting STEPS into a shared module must not have changed a word of it, and no
# future edit to that module may quietly rewrite the public schema either.
for page in src/pages/how-it-works-brands.tsx src/pages/how-it-works-creators.tsx; do
  if ! grep -q "from '@/content/how-it-works-steps'" "$page"; then
    echo "GATE BROKEN: $page no longer renders the shared steps — the public HowTo schema can drift"
    exit 1
  fi
done

echo "GATE PROVED: first-run guidance controls are live and derivation is evidence-backed"
exit 0
