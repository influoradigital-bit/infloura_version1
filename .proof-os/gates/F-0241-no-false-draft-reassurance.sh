#!/usr/bin/env bash
# gates/F-0241-no-false-draft-reassurance.sh
# origin failure: F-0241 (false-save-reassurance) — the box claimed "this campaign is saved as
# a draft, so nothing is lost" at a moment when the create call had thrown and saved nothing.
#
# F-0329 (this gate's OWN defect, repaired here). Ledger record F-0241 is CLOSED against this
# file, and until now this file could not fail. It forbade one sentence and required one phrase:
#     grep -qiE "saved as a draft, so nothing is lost"   (must be absent)
#     grep -qiE "been saved yet"                         (must be present)
# Both are snapshots of a wording, and one reword satisfies both while telling the same lie:
#     "This campaign hasn’t been saved yet as a live campaign — we’ve kept it as a draft, so
#      nothing is lost."
# The required phrase is present; the forbidden sentence is not literally present; nothing has
# been saved. Observed at exit 0, VERDICT: aligned, in
# .proof-os/tasks/T-F0329-GATES/F-0241.inject.log.
#
# THE REPAIR. The assertion is now about what the copy CLAIMS, not which words it uses. The box
# is rendered and its prose (buttons and links removed — those are offers, not claims) is put
# through an audit: no un-negated "this is saved / stored / kept / safe" claim may appear, and at
# least one negated one must, so the box actually says the work is unsaved rather than merely
# avoiding the old sentence. Rewording stays green; a reintroduced false claim does not. Per
# F-0273 the audit is first run over a frozen table of known-bad copy (F-0241 verbatim, the wrong
# fix above, and three more shapes) plus one frozen known-good — if it certifies a known-bad, or
# rejects the known-good, the suite fails and this gate reports rather than certifies.
#
# LAW: exit 1 = real finding · 2 = cannot run · 0 = proved.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
cd "${1:-.}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }
# F-0266: grep CODE, not file bytes. A gate that cannot tell a comment from a
# statement fails the fix whose own comment quotes the string it forbids, and
# greens a "fix" that was only ever described in one. gates/_code.sh is the one
# shared, tested place that distinction lives.
. "$SELF/_code.sh" 2>/dev/null || { echo "gates/_code.sh unreadable - unavailable"; exit 2; }
code_ready || { echo "$(code_why) - unavailable"; exit 2; }

F=src/components/brand/VerificationRequiredBox.tsx
SPEC="$SELF/F-0241.no-false-save-claim.spec.tsx"
[ -f "$F" ] || { echo "· $F missing — unavailable"; exit 2; }
[ -f "$SPEC" ] || { echo "· $SPEC missing — the execution leg is gone — unavailable"; exit 2; }
F_CODE=$(code_view "$F") || { echo "$(code_why) - unavailable"; exit 2; }

# ---------------------------------------------------------------------------
# 1 · the component still exists as something the audit can render.
#     (A file that no longer exports the box would make the suite fail to
#     import, which is a real finding, but a clearer one said here.)
# ---------------------------------------------------------------------------
if ! grep -qE "export (function|const) VerificationRequiredBox" "$F_CODE"; then
  echo "· $F no longer exports VerificationRequiredBox — the box the brand is shown on"
  echo "  WORKSPACE_NOT_VERIFIED is gone or renamed, and nothing here can audit its claims"
  echo "VERDICT: broken — F-0241's subject no longer exists (F-0241)"
  echo "NOT CHECKED: what is shown in its place"
  exit 1
fi

# ---------------------------------------------------------------------------
# 2 · EXECUTION. Render the box, audit what it claims. The suite carries its own
#     self-falsification (frozen known-bad + known-good copy) as its first tests.
# ---------------------------------------------------------------------------
echo "· vitest: F-0241.no-false-save-claim.spec.tsx — render the box, audit its claims"
if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: everything this gate asserts. It has no grep-only fallback on purpose: the"
  echo "             grep version of this gate is precisely what could not fail (F-0329)."
  exit 2
fi
# F-0334: gate fixtures live under .proof-os/gates/, which vitest.config.ts now EXCLUDES so
# they are not swept into the product suite or into build.sh's `npm test` leg. vitest applies
# `exclude` even to an explicitly-passed path, so the spec is run under gates/vitest.gates.config.ts
# — the project config with that single exclusion removed, derived from it at load time.
GATES_CFG="$SELF/vitest.gates.config.ts"
[ -f vitest.config.ts ] || { echo "· vitest.config.ts missing — unavailable"; exit 2; }
[ -f "$GATES_CFG" ] || { echo "· $GATES_CFG missing — the gate fixture cannot be collected — unavailable"; exit 2; }
BUDGET="${PROOF_F0241_VITEST_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
out=$($TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$SPEC" 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"
  exit 2
fi
if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected no test file for $SPEC — unavailable"
  exit 2
fi
if printf '%s' "$out" | grep -q "THIS TEST CANNOT FAIL"; then
  printf '%s\n' "$out" | tail -40
  echo "· THIS GATE CANNOT FAIL: its own copy audit accepted copy that reintroduces F-0241."
  echo "  Refusing to report a verdict about the real component from a check that has just"
  echo "  proved itself blind."
  echo "VERDICT: broken — the F-0241 gate's audit no longer detects F-0241 (F-0329)"
  echo "NOT CHECKED: the real component — this run never got a trustworthy answer about it"
  exit 1
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken (F-0241 regressed) — the verification box makes a claim about the"
  echo "         campaign being saved that was not true when it rendered"
  echo "NOT CHECKED: whether the save-draft button actually persists anything"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — the box was RENDERED in three prop states and its prose"
echo "         audited: it carries no un-negated claim that the campaign is saved, stored, kept"
echo "         or safe, it does state outright that the work is not saved yet, and it offers an"
echo "         enabled save-as-draft action rather than asserting one already happened. The"
echo "         audit was proved falsifiable against five frozen known-bad copies (including"
echo "         F-0241 verbatim) and proved to accept a frozen known-good, on this run."
echo "NOT CHECKED: whether the save-draft button actually persists a draft — the handler is a"
echo "             prop and this gate stubs it; whether campaign-form.tsx renders this box ONLY"
echo "             on the thrown-create path (a box shown after a SUCCESSFUL save would make its"
echo "             honest copy the wrong copy, and nothing here would see it); the toast and"
echo "             error copy elsewhere in the wizard; runtime behaviour against a live backend."
exit 0
