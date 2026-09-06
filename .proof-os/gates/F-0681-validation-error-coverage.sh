#!/usr/bin/env bash
# gates/F-0681-validation-error-coverage.sh — instance gate, closes F-0681.
#
# origin: F-0681 (partial-fix-worse-than-none) — F-0668 wired ApiError.fields into the creator
# edit-profile dialog for SIX of CreatorProfilePatchRequest's twelve constrained fields, then
# titled every validation failure "Please fix the highlighted fields". For the six it did not wire
# (categories, languages, contentStyles, avatarUrl, coverImageUrl, phone) the creator was told to
# fix a highlight that existed nowhere on screen — strictly worse than the one generic toast
# F-0668 replaced. `categories` was reachable in a single keypress: the add-category handler
# applies no cap while the DTO declares @Size(max = 3).
#
# WHY THIS GATE IS DERIVED, NOT A LIST. The whole defect was a snapshot going stale: six fields
# were wired at one moment and the seventh constraint was invisible thereafter. So the gate reads
# the constrained set OUT OF THE JAVA DTO every run and demands each member be either highlighted
# inline or named in the toast. A constraint added tomorrow re-arms it with no edit here —
# verified by planting a `tagline` field, which it caught.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_f0681_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }

# --- CHECK A: coverage, derived from the DTO -------------------------------------------------
python "$SCAN"; rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -ne 0 ] && exit 1

# --- CHECK B: the test must be GIT-TRACKED ---------------------------------------------------
# F-0324, hit on five consecutive waves: a test on disk but never `git add`ed passes every local
# gate and does not exist on a pushed branch. A coverage proof that vanishes on a fresh clone is
# not a proof.
TEST=src/pages/creator-profile.field-errors.test.tsx
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  if ! git ls-files --error-unmatch "$TEST" >/dev/null 2>&1; then
    echo "VERDICT: broken — $TEST is not git-tracked; it proves nothing on a fresh clone (F-0324)"
    exit 1
  fi
  echo "· $TEST is git-tracked"
else
  echo "· not a git repo — TRACKING NOT CHECKED"
fi

# --- CHECK C: behaviour ----------------------------------------------------------------------
# Structure proves shape, not effect. These six tests include the three F-0681 cases (categories
# highlighted, `categories[0]` normalised, an unhighlightable field named not falsely promised).
command -v npx >/dev/null 2>&1 || { echo "· npx unavailable — coverage checked, BEHAVIOUR NOT PROVED"; exit 2; }
[ -f package.json ] || { echo "· no package.json here — BEHAVIOUR NOT PROVED"; exit 2; }
echo "· running $TEST"
log=$(mktemp 2>/dev/null || echo "/tmp/f0681.$$")
npx vitest run --reporter=basic "$TEST" >"$log" 2>&1; trc=$?
if grep -qE "Cannot find module|Failed to load|ERR_MODULE_NOT_FOUND" "$log"; then
  echo "· the suite could not load — unavailable, NOT a pass"; tail -12 "$log"; exit 2
fi
if [ "$trc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0681 regression tests do not pass"
  sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "×|Tests |FAIL" | head -15
  exit 1
fi
sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "Tests |Test Files " | head -3
for t in "highlights a categories violation inline" "categories\[0\]" "names a field it cannot highlight"; do
  grep -q "$t" "$TEST" || { echo "VERDICT: broken — the test pinning '$t' is gone; a green run above"; echo "         proves only that the SURVIVING tests pass (F-0681)"; exit 1; }
done
echo "· all three F-0681 cases are present in the green run"

echo "VERDICT: aligned (proved) — no server validation error on this form is silently swallowed:"
echo "         every constrained DTO field is either highlighted inline or named in the toast,"
echo "         no key promises a highlight the form does not render, list-index names collapse"
echo "         onto their control, and the tests pinning all three are tracked and green."
echo "NOT CHECKED: OTHER forms in the app — this is the creator edit-profile dialog only, and"
echo "             F-0668's class (dead-plumbing) covers the rest of the surface; whether the"
echo "             server's raw Bean Validation strings ('size must be between 0 and 3') read"
echo "             well to a creator — they are shown verbatim, unlocalised, which is a copy"
echo "             question this gate takes no position on; and the absence of a client-side cap"
echo "             on categories, which is left deliberately so the server stays the authority."
exit 0
