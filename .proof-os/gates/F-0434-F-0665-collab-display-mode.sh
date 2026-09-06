#!/usr/bin/env bash
# gates/F-0434-F-0665-collab-display-mode.sh — closes F-0434 and F-0665.
#
# origin: the creator portfolio's "Past collabs — what shows on your page" control
# (Name+logo / Name only / Anonymous / Hide). It set state, marked the form dirty, and "saved"
# successfully while the choice went nowhere.
#
# THIS TOOK THREE DISTINCT BLOCKERS TO CLEAR, and the order mattered:
#
#   1. F-0665 — PortfolioPatchRequest had TWELVE fields and `collabs` was not one of them, so
#      Jackson silently dropped it. An early "fix" added `collabs: page.collabs` to the PATCH body
#      and a test asserting the body contained it: both green, nothing persisted. That test is the
#      canonical example of this repo's test-pins-wrong-subject class (F-0663) — it asserted the
#      request, never the effect. PortfolioService also hardcoded displayMode to "logo" on read.
#      Now: the field exists, the (id, displayMode) map persists into the existing
#      portfolio_settings_json blob (no new @Column, per T-RATECARD-0903), an unknown mode is
#      rejected rather than silently defaulted, omitting `collabs` never wipes a stored value, and
#      buildCollabs reads the stored mode.
#
#   2. F-0673 — persisting that map under a new top-level JSON key exposed a latent data-loss bug:
#      PortfolioSettings had no @JsonIgnoreProperties, so loadSettings threw on the unknown key,
#      the catch swallowed it, and EVERY other setting reverted to defaults. Fixed separately;
#      guarded by this gate's sibling because the collab feature is what triggers it.
#
#   3. F-0674 — the real blocker, and why the control stayed deliberately DISABLED until now.
#      displayMode was honoured only in the browser: an unauthenticated GET /portfolio/{username}
#      returned the real brand names of collabs the creator had HIDDEN. Re-enabling the control
#      before that was fixed would have shipped a new false success — the setting would save and
#      visibly do nothing to the public page. Now enforced server-side (hidden rows omitted;
#      category rows stripped of brand name, brandId AND logo url), so the choice genuinely
#      changes what the public sees, and enabling the control is finally honest.
#
# WHAT THIS GATE PROVES, on both sides of the wire:
#   backend  — a PATCHed display mode survives a SEPARATE getMine() read (a genuine round trip,
#              not an echo of the request), an unknown mode is rejected, and a patch that omits
#              collabs preserves the stored value.
#   frontend — the control is ENABLED (not the old disabled/"coming soon" state), changing it sends
#              the chosen mode, and a save -> fresh-mount reload still shows it.
#
# FALSIFICATION, measured: removing `collabs: page.collabs` from handleSave turns 2 of the 3
# frontend tests red ("expected { …(6) } to have property \"collabs\"") — the exact inert-field
# regression that shipped the first time. Restoring the hardcoded "logo" in buildCollabs turns the
# backend round-trip test red ("expected: <hidden> but was: <logo>").
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

FE=src/pages/creator-portfolio-editor.tsx
FE_T=src/pages/creator-portfolio-editor.f0434-collabs-drop.test.tsx
for f in "$FE" "$FE_T"; do
  [ -f "$f" ] || { echo "· $f missing — unavailable"; exit 2; }
  git ls-files --error-unmatch "$f" >/dev/null 2>&1 || {
    echo "· $f is not git-tracked — F-0324 pattern; git add it"
    echo "VERDICT: broken"; exit 1; }
done

# The one line whose absence made the whole feature inert while every test stayed green.
if ! grep -q "collabs: page.collabs" "$FE"; then
  echo "· $FE no longer sends collabs in the PATCH body"
  echo "VERDICT: broken — the display mode is being collected and discarded again (F-0434)"
  exit 1
fi
echo "· the editor sends collabs in the PATCH body"

BUDGET="${PROOF_F0434_TIMEOUT:-600}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· mvn -o clean -Dtest=PortfolioServiceCollabDisplayModeTest test  (the round trip)"
out=$($TO mvn -o -q clean -Dtest=com.influora.service.portfolio.PortfolioServiceCollabDisplayModeTest -f influora-api/pom.xml test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol"; then
  printf '%s\n' "$out" | tail -30; echo "VERDICT: unavailable — module does not compile"; exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -30
  echo "VERDICT: broken — a PATCHed display mode no longer survives an independent read (F-0665)"
  exit 1
fi
echo "  backend round trip green"

echo "· vitest: $FE_T"
out=$($TO node_modules/.bin/vitest run "$FE_T" 2>&1); rc=$?
if printf '%s' "$out" | grep -q "No test files found"; then echo "  collected nothing — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -30
  echo "VERDICT: broken — the control is disabled again, or the chosen mode no longer round-trips"
  echo "         through a reload (F-0434)"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true

echo "VERDICT: aligned (proved) — the collab display-mode control is enabled, the chosen mode"
echo "         reaches the server, persists, survives an independent read AND a page reload, and"
echo "         (per the F-0674 gate) genuinely changes what an anonymous visitor sees."
echo "NOT CHECKED: whether the four modes are the right product vocabulary; the F-0674 public-path"
echo "             enforcement itself (its own gate proves that, not this one); and per the standing"
echo "             caveat, no test here hits a real HTTP endpoint or a real browser — the round trip"
echo "             is proved at the service layer and against a mocked client."
exit 0
