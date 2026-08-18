#!/usr/bin/env bash
# gates/F-0243-verification-copy-agrees.sh
# origin failure: F-0243 (contradictory-gating-copy) — the banner said verification was required
# to launch while the KYC prompt on the same screen called it optional and non-blocking.
# Backend truth: CampaignValidator.validateStatusForWorkspace blocks ACTIVE, never DRAFT, and
# Workspace.applyKycDecision does NOT auto-publish drafts.
#
# F-0329 (this gate's OWN defect, repaired here). Ledger record F-0243 is CLOSED against this
# file, and until now this file could not fail. Two separate reasons:
#
#  1. THE APOSTROPHE. The forbid leg was `grep -q "won.t block your campaign"`. That `.` was
#     standing in for an apostrophe, and a bare `.` in a BRE/ERE matches ONE BYTE. U+2019 — the
#     curly apostrophe this codebase's copy uses everywhere (hasn't, you'll, couldn't, we'll) —
#     is three bytes in UTF-8, so the pattern only ever matched the ASCII form. Proved by two
#     back-to-back runs against the same injected sentence, differing in that one character:
#         "it won’t block your campaign"  → OLD gate exit 0, "VERDICT: aligned (proved)"
#         "it won't block your campaign"  → OLD gate exit 1, "VERDICT: broken"
#     Recorded in .proof-os/tasks/T-F0329-GATES/F-0243.inject.log. The regression the record is
#     about would, written the way this codebase writes copy, have gone straight through.
#
#  2. THE WRONG ASSERTION. Even with the apostrophe fixed, forbidding one sentence and requiring
#     another is a snapshot of today's wording on a record about AGREEMENT. Any rephrasing of the
#     contradiction escapes it; any rephrasing of the honest copy false-reds it, and a gate that
#     false-reds on a legitimate reword gets deleted, which leaves the record unguarded.
#
# THE REPAIR. Both surfaces are rendered, their prose normalised (typographic apostrophes and
# dashes folded to ASCII — assertion 1 can never recur), and each is CLASSIFIED for the position
# it takes: does verification stand between this brand and publishing? The gate then requires the
# surfaces to hold the same position, and that position to be the backend's. Per F-0273 the
# classifier is first run over frozen known-bad pairings (including the curly-apostrophe copy
# that greened the old gate) and frozen known-good pairings — one of which is a complete reword
# of both surfaces, so the gate proves on every run that rewording is allowed.
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

K=src/components/brand/campaigns/brand-kyc-prompt.tsx
B=src/components/brand/WorkspaceVerificationBanner.tsx
SPEC="$SELF/F-0243.copy-agrees.spec.tsx"
[ -f "$K" ] && [ -f "$B" ] || { echo "· source files missing — unavailable"; exit 2; }
[ -f "$SPEC" ] || { echo "· $SPEC missing — the execution leg is gone — unavailable"; exit 2; }
K_CODE=$(code_view "$K") || { echo "$(code_why) - unavailable"; exit 2; }
B_CODE=$(code_view "$B") || { echo "$(code_why) - unavailable"; exit 2; }

# ---------------------------------------------------------------------------
# 1 · both surfaces still exist to be compared. If one is deleted, the pair this
#     record is about is gone and the suite would fail on an import rather than
#     on a finding — say so here instead.
# ---------------------------------------------------------------------------
grep -qE "export function BrandKycPrompt" "$K_CODE" || {
  echo "· $K no longer exports BrandKycPrompt"
  echo "VERDICT: broken — one of F-0243's two surfaces is gone or renamed (F-0243)"
  echo "NOT CHECKED: what a first-run brand is told in its place"; exit 1; }
grep -qE "export function WorkspaceVerificationBanner" "$B_CODE" || {
  echo "· $B no longer exports WorkspaceVerificationBanner"
  echo "VERDICT: broken — one of F-0243's two surfaces is gone or renamed (F-0243)"
  echo "NOT CHECKED: what a first-run brand is told in its place"; exit 1; }

# ---------------------------------------------------------------------------
# 2 · both are still mounted where they collide. F-0243 is a SAME-SCREEN
#     contradiction; the banner self-gates to campaign routes and the prompt is
#     rendered by the new-campaign page. If either stops rendering there, the two
#     no longer meet and this gate is measuring a screen that does not exist.
# ---------------------------------------------------------------------------
NEWPAGE=src/pages/brand-new-campaign.tsx
if [ -f "$NEWPAGE" ]; then
  NEWPAGE_CODE=$(code_view "$NEWPAGE") || { echo "$(code_why) - unavailable"; exit 2; }
  grep -q "<BrandKycPrompt" "$NEWPAGE_CODE" || {
    echo "· brand-new-campaign.tsx no longer renders <BrandKycPrompt> — the two surfaces this"
    echo "  record is about no longer share a screen, so the comparison below is about a screen"
    echo "  that does not exist — unavailable"
    exit 2; }
  grep -q "brand/campaigns" "$B_CODE" || {
    echo "· the banner no longer gates itself to /brand/campaigns — unavailable"
    exit 2; }
else
  echo "· $NEWPAGE missing — cannot confirm the two surfaces still share a screen — unavailable"
  exit 2
fi

# ---------------------------------------------------------------------------
# 3 · EXECUTION. Render both, classify both, compare. The suite carries its own
#     self-falsification (frozen known-bad and known-good pairings) as its first
#     two tests.
# ---------------------------------------------------------------------------
echo "· vitest: F-0243.copy-agrees.spec.tsx — render both surfaces, compare the position each takes"
if [ ! -x node_modules/.bin/vitest ] && [ ! -f node_modules/.bin/vitest ]; then
  echo "· node_modules/.bin/vitest not found — unavailable"
  echo "NOT CHECKED: everything this gate asserts. There is deliberately no grep-only fallback:"
  echo "             the grep version of this gate is what could not fail (F-0329)."
  exit 2
fi
# F-0334: gate fixtures live under .proof-os/gates/, which vitest.config.ts now EXCLUDES so
# they are not swept into the product suite or into build.sh's `npm test` leg. vitest applies
# `exclude` even to an explicitly-passed path, so the spec is run under gates/vitest.gates.config.ts
# — the project config with that single exclusion removed, derived from it at load time.
GATES_CFG="$SELF/vitest.gates.config.ts"
[ -f vitest.config.ts ] || { echo "· vitest.config.ts missing — unavailable"; exit 2; }
[ -f "$GATES_CFG" ] || { echo "· $GATES_CFG missing — the gate fixture cannot be collected — unavailable"; exit 2; }
BUDGET="${PROOF_F0243_VITEST_TIMEOUT:-300}"
if command -v timeout >/dev/null 2>&1; then TO="timeout -k 10 $BUDGET"; else TO=""; fi
out=$($TO node_modules/.bin/vitest run --config "$GATES_CFG" --root . "$SPEC" 2>&1); rc=$?
if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
  echo "  suite exceeded ${BUDGET}s — unavailable, NOT a finding"; exit 2
fi
if printf '%s' "$out" | grep -q "No test files found"; then
  echo "  vitest collected no test file for $SPEC — unavailable"; exit 2
fi
if printf '%s' "$out" | grep -q "THIS TEST CANNOT FAIL"; then
  printf '%s\n' "$out" | tail -40
  echo "· THIS GATE CANNOT FAIL: its own copy classifier accepted a pairing that reintroduces"
  echo "  F-0243. Refusing to report a verdict about the real screen from a check that has just"
  echo "  proved itself blind."
  echo "VERDICT: broken — the F-0243 gate's classifier no longer detects F-0243 (F-0329)"
  echo "NOT CHECKED: the real components — this run never got a trustworthy answer about them"
  exit 1
fi
if printf '%s' "$out" | grep -q "false-red machine"; then
  printf '%s\n' "$out" | tail -40
  echo "· the classifier flags copy that AGREES — it would red on an honest reword, which is how"
  echo "  a gate gets deleted. This is a defect in the gate, not a finding about the product."
  exit 2
fi
if [ $rc -ne 0 ]; then
  printf '%s\n' "$out" | tail -40
  echo "VERDICT: broken (F-0243 regressed) — the verification surfaces on /brand/campaigns/new do"
  echo "         not agree about whether verification stands between the brand and publishing"
  echo "NOT CHECKED: copy on brand-verification.tsx and BrandKycForm.tsx, or the onboarding exit"
  echo "             copy that took a third position in the original record"
  exit 1
fi
printf '%s\n' "$out" | grep -E "Tests |Test Files " | sed 's/^/  /' || true
echo "  suite green"

echo "VERDICT: aligned (proved) — both surfaces were RENDERED (the banner in all three of its"
echo "         UNVERIFIED / PENDING / REJECTED states, the KYC prompt as a first-run brand sees"
echo "         it) and classified after folding typographic apostrophes and dashes to ASCII:"
echo "         neither claims verification is optional or non-blocking, neither promises that"
echo "         approval publishes drafts by itself, and at least one of them states plainly that"
echo "         verification gates publishing — which is what the backend enforces. The classifier"
echo "         was proved on this run to reject five frozen known-bad pairings (including the"
echo "         curly-apostrophe copy the previous gate certified) and to accept a frozen"
echo "         known-good pairing that rewords BOTH surfaces."
echo "NOT CHECKED: copy on brand-verification.tsx, BrandKycForm.tsx and the onboarding exit"
echo "             screen — the original record named a THIRD position there and this gate reads"
echo "             neither; whether the wording is understandable to a first-run brand at all;"
echo "             the DRAFT half of the backend rule (that saving a draft is never blocked) is"
echo "             not asserted against the backend here, only assumed; runtime behaviour."
exit 0
