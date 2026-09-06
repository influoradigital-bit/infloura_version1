#!/usr/bin/env bash
# gates/F-0471-approval-surfaces-report-held-release.sh — instance gate, closes F-0471.
#
# origin: F-0471 (partial-fix-narrows-defect) — "F-0406 was fixed in the backend and on one of
# three brand approval surfaces; the two most-used surfaces still discard the held-release outcome,
# so a brand approving from those still sees success while no money moved."
#
# WHY THIS IS A MONEY BUG AND NOT A TOAST BUG. Approving a deliverable is what pays the creator.
# EscrowService#tryReleaseOnApproval can legitimately decline to release — unfunded milestone,
# unmet release condition, dispute freeze — and it returns ReleaseOutcome(released, heldReason)
# WITHOUT throwing. A `catch` block can therefore never see it. A call site that ignores the return
# value tells the brand the money moved when it did not, and nobody finds out until the creator
# asks where their payment is.
#
# MEASURED: four FE approval call sites, not three. contracts-and-deliverables.tsx and
# DeliverableViewer.tsx already branched; deliverable-review-panel.tsx and brand-chat.tsx both read
# `await deliverablesApi.approve(id);` and discarded the result entirely. Both now branch.
#
# THE RULE IS REPO-WIDE, DELIBERATELY. The finding's own missed_by asked for "a test asserting
# EVERY approval call site surfaces a held release to the user". Two component tests would prove
# the two components someone remembered; a fifth surface added tomorrow would sail past them —
# which is exactly how F-0471 came to exist after F-0406 was closed.
#
# LAW: exit 0 proved · 1 broken · 2 unavailable.
set -u
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || { echo "· cannot resolve gate dir — unavailable"; exit 2; }
ROOT=$(cd "$SELF/../.." 2>/dev/null && pwd) || { echo "· cannot resolve project root — unavailable"; exit 2; }
cd "${1:-$ROOT}" 2>/dev/null || { echo "· not a directory — unavailable"; exit 2; }

command -v python >/dev/null 2>&1 || { echo "· python not on PATH — unavailable"; exit 2; }
SCAN="$SELF/_f0471_scan.py"
[ -f "$SCAN" ] || { echo "· $SCAN missing — unavailable"; exit 2; }
TEST=src/components/brand/timeline/panels/__tests__/deliverable-review-panel-held-release.test.tsx

# --- CHECK A: every approval call site branches on the outcome ---------------------------------
python "$SCAN"; rc=$?
[ "$rc" -eq 2 ] && exit 2
[ "$rc" -ne 0 ] && exit 1

# --- CHECK B: the server must still SEND an outcome to branch on -------------------------------
# If ReviewResponse loses the field, every call site above branches on undefined - permanently
# falsy - and silently reverts to warning on successful payments. Structure on one side of a
# contract proves nothing about the other.
DTO=influora-api/src/main/java/com/influora/web/dto/deliverable/BrandDeliverableDtos.java
if [ -f "$DTO" ]; then
  grep -q "boolean paymentReleased" "$DTO" || {
    echo "VERDICT: broken — ReviewResponse no longer carries paymentReleased, so every frontend"
    echo "         branch above reads undefined and the held case becomes invisible again (F-0471)"
    exit 1; }
  grep -q "String paymentHeldReason" "$DTO" || {
    echo "VERDICT: broken — ReviewResponse no longer carries paymentHeldReason; the brand would be"
    echo "         told payment was held with no way to learn why (F-0471)"
    exit 1; }
  echo "· the server still sends paymentReleased and paymentHeldReason"
else
  echo "· backend DTO not present here — CONTRACT NOT CHECKED"
fi

# --- CHECK C: behaviour on the surface that was broken -----------------------------------------
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  git ls-files --error-unmatch "$TEST" >/dev/null 2>&1 || {
    echo "VERDICT: broken — $TEST is not git-tracked; it proves nothing on a fresh clone (F-0324)"
    exit 1; }
  echo "· the regression test is git-tracked"
fi
command -v npx >/dev/null 2>&1 || { echo "· npx unavailable — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f package.json ] || { echo "· no package.json here — BEHAVIOUR NOT PROVED"; exit 2; }
[ -f "$TEST" ] || { echo "VERDICT: broken — the F-0471 regression test is gone"; exit 1; }
log=$(mktemp 2>/dev/null || echo "/tmp/f0471.$$")
npx vitest run --reporter=basic "$TEST" >"$log" 2>&1; trc=$?
if grep -qE "Cannot find module|Failed to load|ERR_MODULE_NOT_FOUND" "$log"; then
  echo "· the suite could not load — unavailable, NOT a pass"; tail -10 "$log"; exit 2; fi
if [ "$trc" -ne 0 ]; then
  echo "VERDICT: broken — the F-0471 regression test does not pass"
  sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "×|Tests " | head -10; exit 1; fi
sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -E "Tests |Test Files " | head -2
grep -q "confirms the payment when the release really happened" "$TEST" || {
  echo "VERDICT: broken — the happy-path test is gone. Without it, code that warns on EVERY"
  echo "         approval passes the held-release test while crying wolf on real payments"
  exit 1; }
echo "· both directions are pinned: the held warning AND the successful payment"

echo "VERDICT: aligned (proved) — every deliverable-approval call site in the frontend branches on"
echo "         paymentReleased, the server still sends both that flag and its reason, and the"
echo "         surface that discarded it now warns on a held release without crying wolf on a"
echo "         successful one."
echo "NOT CHECKED: whether the WORDING of paymentHeldMessage helps a brand act — this proves a"
echo "             destructive toast fires carrying that text, not that the text is good. The"
echo "             other three surfaces are covered STRUCTURALLY by the repo-wide rule but have no"
echo "             behavioural test of their own, so a branch that exists yet renders nothing"
echo "             would pass. And nothing here proves the backend's held/loud split is CORRECT —"
echo "             which reasons are safe to retry is F-0406's territory, proved by its own gate."
exit 0
