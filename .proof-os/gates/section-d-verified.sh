#!/usr/bin/env bash
# gates/section-d-verified.sh — closes F-0661, F-0635, F-0673.
#
# Section D was 7 items. This gate closes THREE. The rest are deliberately excluded and still open,
# because a fresh-context CTO review found them not fixed — see the bottom of this header.
#
#   F-0661 (dto-drift) — FIXED, and fixed in the right DIRECTION. The backend agent was explicitly
#     forbidden to fabricate a server `metrics` payload to satisfy a TypeScript type, and it didn't:
#     it proved `PortfolioItemResponse` has no metrics field and never had one, that `description`
#     is legitimately always-null (one free-text field exists upstream and it is already mapped to
#     `title`), and it changed NOTHING server-side. The TS type was made honest instead. Reverting
#     `metrics?` back into types.ts gives TS2578 and tsc exit 2.
#
#   F-0635 (write-trusts-response-not-reread) — FALSE FINDING, confirmed twice. The PATCH response
#     IS the server's authoritative record for that write; a forced re-GET adds a round trip and a
#     new failure mode for no correctness gain. Closed on a test that pins the behaviour, including
#     the server-normalises-the-input case where trusting the response is MORE correct.
#
#   F-0673 (silent-data-loss) — the serious one, and it was already LIVE in promoted code.
#     `writeSettings` merges "rateCard" (added by F-0498, promoted earlier the same day) and
#     "collabDisplayModes" into portfolio_settings_json as extra top-level keys. `loadSettings`
#     read that back with a plain ObjectMapper — FAIL_ON_UNKNOWN_PROPERTIES defaults to TRUE — into
#     a PortfolioSettings class carrying no @JsonIgnoreProperties. It threw, the catch swallowed it,
#     and EVERY OTHER SETTING silently reverted to defaults on the next read.
#
#     All 29 pre-existing portfolio tests passed throughout, because not one of them ever read a
#     DIFFERENT field back after a write. That is the only shape that exposes it.
#
# A FAILED FALSIFICATION, RECORDED BECAUSE IT MATTERS. The first F-0673 test written for this
# asserted (a) page.visibility() is non-null and (b) the collab display mode survived. Removing the
# fix left all 30 tests GREEN. Both assertions were vacuous: getVisibility() defaults to a non-null
# object when the blob is lost, and loadCollabDisplayModes is a SEPARATE tree-level read that
# survives the failure. The test was rewritten against customLinks — which defaults to EMPTY — and
# now fails correctly (`expected: <1> but was: <0>`). This gate's author fell into the exact
# F-0663 class the same wave opened, and caught it only by falsifying. That is the argument for
# never skipping the falsification step, written by someone who needed it.
#
# EXCLUDED, STILL OPEN:
#   F-0665/F-0434 — the collab round trip is genuine, but F-0674 (buildCollabs applies displayMode
#     to NOTHING server-side, so an anonymous GET /portfolio/{username} leaks the brand names of
#     collabs a creator hid) means re-enabling the control would ship a new false-success. Blocked.
#   F-0634 — I labelled this a false finding; the review rejected that and is right. Only one
#     non-test caller of updateMe exists, so the finding is factually true. DEFERRED, not false.
#   F-0663 — its own gate now runs clean of phantom paths (6 corrected in the ledger) but still
#     exits 1 on two genuinely vacuous/mismatched tests it found, logged as F-0675/F-0676. A gate
#     that legitimately fails cannot be promoted against; it goes green when those are fixed.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

# F-0673 source tripwire: the annotation is load-bearing, and its absence is invisible to every
# test that only re-reads the field it just wrote — which was all 29 of them.
if ! grep -q "JsonIgnoreProperties(ignoreUnknown = true)" \
     influora-api/src/main/java/com/influora/service/portfolio/PortfolioSettings.java; then
  echo "· PortfolioSettings no longer tolerates unknown keys"
  echo "VERDICT: broken — the settings blob will fail to deserialise and loadSettings will"
  echo "         silently return defaults, wiping visibility/customLinks/pinnedPosts (F-0673)"
  exit 1
fi
echo "· PortfolioSettings tolerates the extra top-level keys writeSettings merges in"

command -v node >/dev/null 2>&1 || { echo "· node not on PATH — unavailable"; exit 2; }
[ -f node_modules/.bin/vitest ] || { echo "· vitest not found — unavailable"; exit 2; }
command -v mvn >/dev/null 2>&1 || { echo "· mvn not on PATH — unavailable"; exit 2; }

BUDGET="${PROOF_SECTION_D_TIMEOUT:-900}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 15 $BUDGET"; else TO=""; fi

echo "· tsc --noEmit  (F-0661 is a type-only change — the typechecker IS its gate)"
out=$(npx --no-install tsc --noEmit 2>&1); rc=$?
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | head -20; echo "VERDICT: broken — tsc fails"; exit 1; fi
echo "  tsc clean"

echo "· mvn -o clean -Dtest=PortfolioService* test"
out=$($TO mvn -o -q clean -Dtest='com.influora.service.portfolio.PortfolioServiceCollabDisplayModeTest,com.influora.service.portfolio.PortfolioServiceRateCardTest,com.influora.service.portfolio.PortfolioServiceTest' -f influora-api/pom.xml test 2>&1); rc=$?
if echo "$out" | grep -qiE "COMPILATION ERROR|cannot find symbol"; then
  printf '%s\n' "$out" | tail -40; echo "VERDICT: unavailable — backend does not compile"; exit 2
fi
if [ $rc -ne 0 ]; then printf '%s\n' "$out" | tail -40; echo "VERDICT: broken — a portfolio test failed"; exit 1; fi
echo "  portfolio suite green"

echo "· npm test (full suite)"
out=$($TO npm test 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then echo "  exceeded ${BUDGET}s — unavailable"; exit 2; fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | grep -E "FAIL|Test Files|Tests |AssertionError" | tail -25
  echo "VERDICT: broken — the full suite fails"; exit 1
fi
printf '%s\n' "$out" | grep -E "Test Files|Tests " | tail -2 | sed 's/^/  /'

echo "VERDICT: aligned (proved) — F-0661 (the portfolio-item type is honest against what the server"
echo "         actually sends, with nothing fabricated to satisfy it), F-0635 (trusting the PATCH"
echo "         response is correct and is now pinned) and F-0673 (an unrelated setting survives a"
echo "         write to the extra blob keys) hold on a typechecking tree with the suite green."
echo "NOT CHECKED: F-0665/F-0434 (blocked on F-0674, a server-side privacy leak), F-0634"
echo "             (deferred, not false), F-0663 (its gate legitimately still exits 1 on F-0675/"
echo "             F-0676). And the standing caveat: no test in this repo exercises a real backend"
echo "             or a real browser, so none of this is live-proven."
exit 0
